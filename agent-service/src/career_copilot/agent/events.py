"""SSE Tool / Run 事件：节点内产出轻量进度事件，经 LangGraph custom stream 转发。

协议（与前端 Copilot Event 约定对齐）：
- tool_started / tool_completed: {tool, label}，label 为面向用户的中文描述
- run_status: {status}，取值 RunStatus（RUNNING / WAITING_USER / ... / COMPLETED / FAILED）

设计约束：
- 事件发射必须无副作用：不在流式上下文（如 /chat 同步入口、单测直接 ainvoke）时静默丢弃
- 频控埋点在「一次 Java 往返」粒度，不覆盖每个小步骤
"""

import logging
from typing import Any

from langgraph.config import get_stream_writer

logger = logging.getLogger(__name__)

# 工具 key → 面向用户的中文描述（前端直接展示）
TOOL_LABELS_ZH: dict[str, str] = {
    "load_history": "回忆对话上下文",
    "user_snapshot": "了解你的近期表现",
    "resume_query": "查询简历库",
    "resume_insight": "读取简历分析",
    "interview_review": "读取面试记录",
    "profile_query": "读取技能画像",
    "knowledge_search": "检索知识库",
    "interview_proposal": "推导面试推荐",
    "list_skills": "读取面试方向",
    "create_interview": "创建面试会话",
}


def _safe_writer() -> Any | None:
    """获取当前节点所属运行的 stream writer；非流式上下文返回 None。"""
    try:
        return get_stream_writer()
    except Exception:
        # ainvoke / 单测环境无 writer 上下文
        return None


def _emit(event: dict[str, Any]) -> None:
    writer = _safe_writer()
    if writer is None:
        return
    try:
        writer(event)
    except Exception:
        # 事件失败绝不影响主流程
        logger.debug("emit custom stream event failed", exc_info=True)


def emit_tool_started(tool_key: str) -> None:
    _emit(
        {
            "type": "tool_started",
            "payload": {"tool": tool_key, "label": TOOL_LABELS_ZH.get(tool_key, tool_key)},
        }
    )


def emit_tool_progress(tool_key: str, label: str) -> None:
    """更新当前 pending 步骤的文案（如轮询等待中的第 N 次提示）。"""
    _emit({"type": "tool_progress", "payload": {"tool": tool_key, "label": label}})


def emit_tool_completed(tool_key: str) -> None:
    _emit({"type": "tool_completed", "payload": {"tool": tool_key}})


def emit_run_status(status: str) -> None:
    _emit({"type": "run_status", "payload": {"status": status}})
