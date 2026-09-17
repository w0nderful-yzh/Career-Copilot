"""意图路由测试：验证结构化分类结果正确映射为意图。"""

import pytest

from career_copilot.agent.router import (
    ActionRoute,
    Intent,
    IntentClassification,
    IntentRouter,
)
from tests.conftest import make_fake_executor


async def classify_with(classification: IntentClassification, message: str) -> IntentClassification:
    """用 fake 执行器跑一次意图分类（模型走 fake，不触发真实调用）。"""
    router = IntentRouter(make_fake_executor(classification))
    return await router.classify(message)


@pytest.mark.asyncio
async def test_classify_general_chat():
    """闲聊消息应分类为 GENERAL_CHAT。"""
    classification = IntentClassification(intent=Intent.GENERAL_CHAT)
    result = await classify_with(classification, "你好")
    assert result.intent == Intent.GENERAL_CHAT


@pytest.mark.asyncio
async def test_classify_resume_query():
    """询问简历应分类为 RESUME_QUERY。"""
    classification = IntentClassification(intent=Intent.RESUME_QUERY)
    result = await classify_with(classification, "我的简历分析结果怎么样")
    assert result.intent == Intent.RESUME_QUERY


@pytest.mark.asyncio
async def test_classify_interview_review():
    """询问面试表现应分类为 INTERVIEW_REVIEW。"""
    classification = IntentClassification(intent=Intent.INTERVIEW_REVIEW)
    result = await classify_with(classification, "我最近面试表现怎么样")
    assert result.intent == Intent.INTERVIEW_REVIEW


@pytest.mark.asyncio
async def test_classify_knowledge_qa():
    """技术概念问题应分类为 KNOWLEDGE_QA。"""
    classification = IntentClassification(intent=Intent.KNOWLEDGE_QA)
    result = await classify_with(classification, "JVM GC 是什么")
    assert result.intent == Intent.KNOWLEDGE_QA


@pytest.mark.asyncio
async def test_classify_navigation_with_route():
    """开始面试意图应分类为 NAVIGATION 且附带 INTERVIEW_CREATE 白名单路由。"""
    classification = IntentClassification(
            intent=Intent.NAVIGATION,
            action_route=ActionRoute.INTERVIEW_CREATE,
        )
    result = await classify_with(classification, "给我来场模拟面试")
    assert result.intent == Intent.NAVIGATION
    assert result.action_route == ActionRoute.INTERVIEW_CREATE