"""LangGraph Checkpoint：PostgreSQL 持久化 + 瞬时字段剥离。

thread_id = conversation_id：跨轮次持久化 Agent 工作状态（history / active refs /
intent / tool_results），并支持后续 HITL / WAITING_ASYNC 恢复。

主 Graph 的 StreamPlan 含 AsyncIterator（文本流），不可序列化，持久化前剥离；
ainvoke 内存返回值不受影响，API 层仍可消费 plan 做 SSE 流式。
"""

import logging
from collections.abc import Sequence
from typing import Any, cast

from langchain_core.runnables.config import RunnableConfig
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
)
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg_pool import AsyncConnectionPool

logger = logging.getLogger(__name__)

# 持久化时剥离的瞬时字段（每轮重建，无需跨轮保留）
TRANSIENT_KEYS = ("plan",)


class CopilotPostgresSaver(AsyncPostgresSaver):
    """剥离瞬时字段的 Postgres checkpointer。

    仅剥离 channel_values / channel_versions / versions_seen / updated_channels
    与节点级写入中的 plan 等瞬时键，保证 checkpoint 可序列化；内存返回值不受影响。
    """

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        """异步持久化前剥离瞬时字段。"""
        stripped, stripped_versions = self._strip_transient(checkpoint, new_versions)
        return await super().aput(config, stripped, metadata, stripped_versions)

    def put(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        """同步持久化前同样剥离瞬时字段（与 aput 保持一致）。"""
        stripped, stripped_versions = self._strip_transient(checkpoint, new_versions)
        return super().put(config, stripped, metadata, stripped_versions)

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        """节点级写入持久化前同样剥离瞬时字段（plan 等每轮重建）。"""
        writes = [(c, v) for c, v in writes if c not in TRANSIENT_KEYS]
        return await super().aput_writes(config, writes, task_id, task_path)

    def put_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        """同步版节点级写入剥离（与 aput_writes 保持一致）。"""
        writes = [(c, v) for c, v in writes if c not in TRANSIENT_KEYS]
        return super().put_writes(config, writes, task_id, task_path)

    @staticmethod
    def _strip_transient(
        checkpoint: Checkpoint, new_versions: ChannelVersions
    ) -> tuple[Checkpoint, ChannelVersions]:
        """复制 checkpoint 并剥离瞬时字段，返回 (剥离后的 checkpoint, 剥离后的 new_versions)。"""
        copy = checkpoint.copy()
        copy["channel_values"] = {
            k: v
            for k, v in checkpoint["channel_values"].items()
            if k not in TRANSIENT_KEYS
        }
        copy["channel_versions"] = {
            k: v
            for k, v in checkpoint["channel_versions"].items()
            if k not in TRANSIENT_KEYS
        }
        copy["versions_seen"] = {
            k: v for k, v in checkpoint["versions_seen"].items() if k not in TRANSIENT_KEYS
        }
        updated_channels = checkpoint.get("updated_channels")
        if updated_channels is not None:
            copy["updated_channels"] = [
                k for k in updated_channels if k not in TRANSIENT_KEYS
            ]
        stripped_versions = {
            k: v for k, v in new_versions.items() if k not in TRANSIENT_KEYS
        }
        return copy, stripped_versions


async def init_checkpointer(database_url: str) -> AsyncPostgresSaver | None:
    """创建连接池并初始化 checkpoint 表。

    失败（如数据库不存在）时返回 None：Graph 回退无 checkpoint 运行，
    短期记忆仍由 Java 历史注入兜底，不阻断服务启动。
    """
    try:
        pool = AsyncConnectionPool(
            database_url,
            open=False,
            kwargs={"autocommit": True, "prepare_threshold": 0},
        )
        await pool.open()
        saver = CopilotPostgresSaver(cast(Any, pool))
        await saver.setup()
        logger.info("checkpointer ready: %s", database_url)
        return saver
    except Exception:
        logger.exception(
            "checkpointer init failed, fallback to no checkpoint: %s", database_url
        )
        return None


async def close_checkpointer(saver: BaseCheckpointSaver[Any] | None) -> None:
    """关停连接池（应用退出时调用）。"""
    if not isinstance(saver, CopilotPostgresSaver):
        return
    conn = saver.conn
    if isinstance(conn, AsyncConnectionPool):
        await conn.close()