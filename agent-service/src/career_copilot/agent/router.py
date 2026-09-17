"""Agent 意图路由：意图枚举、结构化分类与路由执行。

遵循规则：简单意图直接短路执行，不引入通用 Planner 与大型 LangGraph；
意图判断属于分类任务，使用低延迟模型 + 结构化输出，temperature=0。
"""

from enum import StrEnum
from typing import Any

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage
from pydantic import BaseModel, field_validator

from career_copilot.agent.llm import LlmExecutor
from career_copilot.prompts import load


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



class IntentRouter:
    """使用结构化输出对用户消息做意图分类。

    模型通过构造器注入，测试时可替换为 fake 模型，避免真实调用。
    """

    def __init__(self, llm: LlmExecutor) -> None:
        self._llm = llm

    async def classify(self, message: str, history: str | None = None) -> IntentClassification:
        prompt = load("intent")
        messages: list[BaseMessage] = [SystemMessage(content=prompt.text)]
        if history:
            messages.append(HumanMessage(content=f"对话历史：\n{history}"))
        messages.append(HumanMessage(content=message))
        # 结构化输出与有限修复在 executor 里统一处理（含 response_format=json_object 请求）
        return (await self._llm.json(prompt, messages, IntentClassification)).unwrap()