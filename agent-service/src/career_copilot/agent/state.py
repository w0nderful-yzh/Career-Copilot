"""Agent 运行状态定义。

State 保持精简：只保存必要 Reference 与运行状态，不保存完整业务数据
（完整 PDF / Resume 文本 / JD / 数据库 Entity / httpx Client 等）。
"""

from enum import StrEnum
from typing import Any, TypedDict


class InputType(StrEnum):
    """前端输入的确定性分类（normalize_input 推导，不调用 LLM）。"""

    TEXT = "TEXT"  # 纯文本
    TEXT_WITH_ATTACHMENT = "TEXT_WITH_ATTACHMENT"  # 文本 + 附件
    ATTACHMENT = "ATTACHMENT"  # 仅附件
    ACTION = "ACTION"  # 按钮点击回传的确定性动作


class RunStatus(StrEnum):
    """一次 Agent Run 的生命周期状态，供前端映射展示。"""

    RUNNING = "RUNNING"  # 正在处理
    WAITING_USER = "WAITING_USER"  # 等待用户选择
    WAITING_ASYNC = "WAITING_ASYNC"  # 后台任务处理中
    COMPLETED = "COMPLETED"  # 已完成
    FAILED = "FAILED"  # 执行失败


class CareerAgentState(TypedDict, total=False):
    """Copilot Turn Graph 的全局状态。"""

    # Trusted Runtime（来自 Java System of Record）
    conversation_id: str | int | None
    user_id: str

    # Input（由 normalize_input 归一化）
    input_type: str
    message: str
    attachments: list[dict[str, Any]]
    action: dict[str, Any] | None

    # Active Context References（resolve_context 恢复）
    active_resume_id: int | None
    active_job_id: int | None
    active_plan_id: int | None

    # Routing
    intent: str | None
    action_route: str | None

    # Tool Results
    tool_results: list[dict[str, Any]]

    # 执行计划（blocks + 可流式文本源，由 API 层消费）
    plan: Any

    # Output
    response: dict[str, Any] | None

    # Run Status
    status: str