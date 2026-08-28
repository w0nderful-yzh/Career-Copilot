"""Agent 意图路由：意图枚举、结构化分类与路由执行。

遵循规则：简单意图直接短路执行，不引入通用 Planner 与大型 LangGraph；
意图判断属于分类任务，使用低延迟模型 + 结构化输出，temperature=0。
"""

from enum import StrEnum
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, field_validator


class Intent(StrEnum):
    """Agent 支持的最小意图集合，路由输出必须是有限、稳定的枚举。

    ATTACHMENT_RECEIVED / COMPLEX_GOAL 由确定性规则或预留分支产生，
    不进入 LLM 分类输出；PREPARATION_QUERY 为预留意图。
    """

    GENERAL_CHAT = "GENERAL_CHAT"  # 普通闲聊/无业务数据需求的回答
    RESUME_QUERY = "RESUME_QUERY"  # 简历相关查询（列表/分析结果）
    RESUME_OPTIMIZATION = "RESUME_OPTIMIZATION"  # 简历优化（子图，预留）
    INTERVIEW_REVIEW = "INTERVIEW_REVIEW"  # 面试表现回顾
    INTERVIEW_CREATE = "INTERVIEW_CREATE"  # 发起模拟面试（Agent 推荐配置 + 确认）
    KNOWLEDGE_QA = "KNOWLEDGE_QA"  # 技术知识问答（需要 RAG）
    PROFILE_QUERY = "PROFILE_QUERY"  # 能力画像查询（get_skill_profile，P3 已开通）
    PREPARATION_QUERY = "PREPARATION_QUERY"  # 学习计划/复习进度查询（预留）
    ATTACHMENT_RECEIVED = "ATTACHMENT_RECEIVED"  # 收到附件（确定性，非 LLM 输出）
    NAVIGATION = "NAVIGATION"  # 建议跳转到业务页面
    COMPLEX_GOAL = "COMPLEX_GOAL"  # 复杂目标（Goal Execution，预留）


class ActionRoute(StrEnum):
    """动作白名单路由 key，由前端映射到真实路由，禁止 LLM 输出任意 URL。"""

    RESUME_UPLOAD = "RESUME_UPLOAD"
    RESUME_LIBRARY = "RESUME_LIBRARY"
    RESUME_DETAIL = "RESUME_DETAIL"
    INTERVIEW_CREATE = "INTERVIEW_CREATE"
    INTERVIEW_SESSION = "INTERVIEW_SESSION"
    INTERVIEW_HISTORY = "INTERVIEW_HISTORY"
    KNOWLEDGE_BASE = "KNOWLEDGE_BASE"
    KNOWLEDGE_CHAT = "KNOWLEDGE_CHAT"
    SETTINGS = "SETTINGS"


class IntentClassification(BaseModel):
    """意图分类的结构化输出。"""

    intent: Intent
    action_route: ActionRoute | None = None

    @field_validator("action_route", mode="before")
    @classmethod
    def blank_route_to_none(cls, value: Any) -> Any:
        """DeepSeek 等模型对非导航意图可能输出空字符串路由，归一为 None 避免枚举校验失败。"""
        return value or None


INTENT_SYSTEM_PROMPT = """你是 Career Copilot 的意图识别器。
根据用户消息判断其意图，只能从以下枚举中选择：
- GENERAL_CHAT：普通闲聊、问候，或不需要业务数据即可回答的问题
- RESUME_QUERY：询问简历、简历分析、简历上传相关
- RESUME_OPTIMIZATION：请求优化简历（如"帮我优化简历""按这份 JD 改简历"）
- INTERVIEW_REVIEW：询问模拟面试历史、面试表现、面试回顾
- INTERVIEW_CREATE：请求发起/开始一场模拟面试（如"来场 JVM 面试""模拟面试"），Agent 推荐配置后创建
- KNOWLEDGE_QA：询问技术知识概念（如 JVM、Redis、算法），需要知识库回答
- PROFILE_QUERY：询问能力画像、技能水平、擅长/薄弱技能
- PREPARATION_QUERY：询问学习计划、复习进度、今天该学什么
- NAVIGATION：用户想跳转到某个业务页面（如查看面试记录、进入设置）

如果意图是 NAVIGATION，必须同时从以下白名单路由中选择一个：
- RESUME_UPLOAD：上传简历
- RESUME_LIBRARY：查看简历库（已上传的简历管理页）
- INTERVIEW_CREATE：创建/开始模拟面试
- INTERVIEW_HISTORY：查看面试历史
- KNOWLEDGE_BASE：管理知识库
- KNOWLEDGE_CHAT：知识库问答助手
- SETTINGS：系统设置

只输出 json 对象（{"intent": "...", "action_route": "..."}），不要输出任何额外文本。"""


class IntentRouter:
    """使用结构化输出对用户消息做意图分类。

    模型通过构造器注入，测试时可替换为 fake 模型，避免真实调用。
    """

    def __init__(self, model: Any) -> None:
        # with_structured_output 返回包装后的 Runnable，保证输出符合 IntentClassification。
        # 使用 json_mode：兼容 DeepSeek 等仅支持 response_format=json_object 的 OpenAI 兼容服务
        # model 为 duck typing 的 ChatModel，测试可注入 fake
        self._structured = model.with_structured_output(
            IntentClassification, method="json_mode"
        )

    async def classify(self, message: str, history: str | None = None) -> IntentClassification:
        messages: list[Any] = [SystemMessage(content=INTENT_SYSTEM_PROMPT)]
        if history:
            messages.append(HumanMessage(content=f"对话历史：\n{history}"))
        messages.append(HumanMessage(content=message))
        result = await self._structured.ainvoke(messages)
        assert isinstance(result, IntentClassification)
        return result