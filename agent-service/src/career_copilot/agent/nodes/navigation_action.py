"""navigation_action：NAVIGATION 意图 → 白名单动作块，前端渲染跳转按钮。

只允许白名单路由（ActionRoute），禁止 LLM 输出任意 URL。
"""

from typing import Any

from career_copilot.agent.deps import GraphDeps
from career_copilot.agent.plan import StreamPlan, static_text
from career_copilot.agent.router import ActionRoute
from career_copilot.agent.state import CareerAgentState
from career_copilot.schemas.message import ActionBlock

# 白名单路由 → (引导文案, 按钮文案)
ROUTE_COPY: dict[ActionRoute, tuple[str, str]] = {
    ActionRoute.RESUME_UPLOAD: ("好的，我们先从简历开始吧。", "上传简历"),
    ActionRoute.RESUME_LIBRARY: ("好的，简历库在这里。", "查看简历库"),
    ActionRoute.RESUME_DETAIL: ("好的，这是你的简历详情。", "查看简历"),
    ActionRoute.INTERVIEW_CREATE: ("好的，准备开始一场模拟面试。", "开始模拟面试"),
    ActionRoute.INTERVIEW_HISTORY: ("好的，这是你的面试记录入口。", "查看面试历史"),
    ActionRoute.KNOWLEDGE_BASE: ("好的，知识库管理入口在这里。", "管理知识库"),
    ActionRoute.KNOWLEDGE_CHAT: ("好的，可以在这里向知识库提问。", "打开问答助手"),
    ActionRoute.SETTINGS: ("好的，模型与系统设置在这里。", "打开设置"),
}


async def navigation_action(
    state: CareerAgentState, deps: GraphDeps
) -> dict[str, Any]:
    route = state.get("action_route")
    try:
        action_route = ActionRoute(route) if route else None
    except ValueError:
        # LLM 输出了白名单外的路由 key：忽略，回退默认引导
        action_route = None

    if action_route is None:
        return {"plan": StreamPlan(text=static_text("你想开始哪项操作？"))}

    content, label = ROUTE_COPY.get(action_route, ("你想开始哪项操作？", "开始模拟面试"))
    return {
        "plan": StreamPlan(
            blocks=[ActionBlock(route=action_route.value, label=label)],
            text=static_text(content),
        )
    }