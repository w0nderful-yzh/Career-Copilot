"""意图路由测试：验证结构化分类结果正确映射为意图。"""

import pytest

from career_copilot.agent.router import (
    Intent,
    IntentClassification,
    IntentRouter,
    NavigationRoute,
)
from tests.conftest import make_fake_model


async def classify_with(fake_model, message: str) -> IntentClassification:
    router = IntentRouter(fake_model)
    return await router.classify(message)


@pytest.mark.asyncio
async def test_classify_general_chat():
    """闲聊消息应分类为 GENERAL_CHAT。"""
    fake = make_fake_model(IntentClassification(intent=Intent.GENERAL_CHAT))
    result = await classify_with(fake, "你好")
    assert result.intent == Intent.GENERAL_CHAT


@pytest.mark.asyncio
async def test_classify_resume_query():
    """询问简历应分类为 RESUME_QUERY。"""
    fake = make_fake_model(IntentClassification(intent=Intent.RESUME_QUERY))
    result = await classify_with(fake, "我的简历分析结果怎么样")
    assert result.intent == Intent.RESUME_QUERY


@pytest.mark.asyncio
async def test_classify_interview_review():
    """询问面试表现应分类为 INTERVIEW_REVIEW。"""
    fake = make_fake_model(IntentClassification(intent=Intent.INTERVIEW_REVIEW))
    result = await classify_with(fake, "我最近面试表现怎么样")
    assert result.intent == Intent.INTERVIEW_REVIEW


@pytest.mark.asyncio
async def test_classify_knowledge_qa():
    """技术概念问题应分类为 KNOWLEDGE_QA。"""
    fake = make_fake_model(IntentClassification(intent=Intent.KNOWLEDGE_QA))
    result = await classify_with(fake, "JVM GC 是什么")
    assert result.intent == Intent.KNOWLEDGE_QA


@pytest.mark.asyncio
async def test_classify_navigation_with_route():
    """开始面试意图应分类为 NAVIGATION 且附带 INTERVIEW_CREATE 路由。"""
    fake = make_fake_model(
        IntentClassification(
            intent=Intent.NAVIGATION,
            navigation_route=NavigationRoute.INTERVIEW_CREATE,
        )
    )
    result = await classify_with(fake, "给我来场模拟面试")
    assert result.intent == Intent.NAVIGATION
    assert result.navigation_route == NavigationRoute.INTERVIEW_CREATE