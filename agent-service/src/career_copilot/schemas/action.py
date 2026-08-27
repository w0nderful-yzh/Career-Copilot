"""确定性动作协议：前端按钮点击回传的结构化输入。

Action 对应确定性路由（LLM 负责开放输入，Action 负责确定性输入），
action 值由后端产出的 ChoiceBlock / ActionBlock 定义，前端原样回传。
"""

from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, Field


class AgentAction(StrEnum):
    """确定性动作 key 白名单，execute_action 节点据此分发。"""

    ANALYZE_RESUME = "ANALYZE_RESUME"  # 分析简历
    OPTIMIZE_RESUME = "OPTIMIZE_RESUME"  # 优化简历（子图，预留）
    START_INTERVIEW = "START_INTERVIEW"  # 模拟面试
    JOB_MATCH = "JOB_MATCH"  # 岗位匹配（预留）


class ActionSelected(BaseModel):
    """用户点击某个动作后的回传请求。"""

    type: Literal["ACTION_SELECTED"] = "ACTION_SELECTED"
    action: str = Field(description="动作 key（见 AgentAction）")
    payload: dict[str, Any] = Field(
        default_factory=dict, description="动作参数（如 resumeId），由后端下发、前端原样回传"
    )