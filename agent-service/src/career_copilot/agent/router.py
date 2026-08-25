"""Agent 意图路由：意图枚举、结构化分类与路由执行。

遵循规则：简单意图直接短路执行，不引入通用 Planner 与大型 LangGraph；
意图判断属于分类任务，使用低延迟模型 + 结构化输出，temperature=0。
"""

from enum import StrEnum
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel


class Intent(StrEnum):
    """Agent 支持的最小意图集合，路由输出必须是有限、稳定的枚举。"""

    GENERAL_CHAT = "GENERAL_CHAT"  # 普通闲聊/无业务数据需求的回答
    RESUME_QUERY = "RESUME_QUERY"  # 简历相关查询（列表/分析结果）
    INTERVIEW_REVIEW = "INTERVIEW_REVIEW"  # 面试表现回顾
    KNOWLEDGE_QA = "KNOWLEDGE_QA"  # 技术知识问答（需要 RAG）
    NAVIGATION = "NAVIGATION"  # 建议跳转到业务页面


class NavigationRoute(StrEnum):
    """导航目标路由 key，由前端映射到真实路由，禁止 LLM 输出任意 URL。"""

    RESUME_UPLOAD = "RESUME_UPLOAD"
    INTERVIEW_CREATE = "INTERVIEW_CREATE"
    INTERVIEW_HISTORY = "INTERVIEW_HISTORY"


class IntentClassification(BaseModel):
    """意图分类的结构化输出。"""

    intent: Intent
    navigation_route: NavigationRoute | None = None


INTENT_SYSTEM_PROMPT = """你是 Career Copilot 的意图识别器。
根据用户消息判断其意图，只能从以下枚举中选择：
- GENERAL_CHAT：普通闲聊、问候，或不需要业务数据即可回答的问题
- RESUME_QUERY：询问简历、简历分析、简历上传相关
- INTERVIEW_REVIEW：询问模拟面试历史、面试表现、面试回顾
- KNOWLEDGE_QA：询问技术知识概念（如 JVM、Redis、算法），需要知识库回答
- NAVIGATION：用户想直接开始某项操作（如开始面试、上传简历），适合跳转页面

如果意图是 NAVIGATION，必须同时从以下路由中选择一个：
- RESUME_UPLOAD：上传简历
- INTERVIEW_CREATE：创建/开始模拟面试
- INTERVIEW_HISTORY：查看面试历史

只输出结构化结果，不要输出任何额外文本。"""


class IntentRouter:
    """使用结构化输出对用户消息做意图分类。

    模型通过构造器注入，测试时可替换为 fake 模型，避免真实调用。
    """

    def __init__(self, model: Any) -> None:
        # with_structured_output 返回包装后的 Runnable，保证输出符合 IntentClassification
        # model 为 duck typing 的 ChatModel，测试可注入 fake
        self._structured = model.with_structured_output(IntentClassification)

    async def classify(self, message: str) -> IntentClassification:
        result = await self._structured.ainvoke(
            [
                SystemMessage(content=INTENT_SYSTEM_PROMPT),
                HumanMessage(content=message),
            ]
        )
        assert isinstance(result, IntentClassification)
        return result