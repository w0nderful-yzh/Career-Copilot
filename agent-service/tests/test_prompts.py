"""提示词资源（ARCH-2）：集中管理 + 版本可追踪。

以前 6 处 SYSTEM_PROMPT 是内联字符串、没有 id 也没有版本——改了提示词，
日志与指标里看不出「跑的是哪一版」。这两个测试守的就是「版本必须能从代码里读出来」。
"""

import pytest

from career_copilot import prompts

EXPECTED_IDS = {
    "answer",
    "history_summary",
    "intent",
    "interview_proposal",
    "resume_patch",
    "resume_review",
}


def test_all_expected_prompts_are_registered():
    """六个已迁移的提示词都能按 id 加载（漏一个就会在运行时才炸）。"""
    assert EXPECTED_IDS <= set(prompts.available())


@pytest.mark.parametrize("prompt_id", sorted(EXPECTED_IDS))
def test_prompt_header_declares_identity_and_version(prompt_id: str):
    """每个资源自带 id 与版本，且观测标识为 id@vN。"""
    prompt = prompts.load(prompt_id)

    assert prompt.id == prompt_id
    assert prompt.version >= 1
    assert prompt.ref == f"{prompt_id}@v{prompt.version}"
    assert prompt.text.strip(), "提示词正文不能为空"


def test_unknown_prompt_fails_loudly():
    """未登记的名字直接报错，并列出可用项——静默用错版本比报错更糟。"""
    with pytest.raises(FileNotFoundError, match="未登记的提示词"):
        prompts.load("no_such_prompt")


def test_missing_header_is_rejected(tmp_path, monkeypatch):
    """缺少头部声明的资源必须被拒绝（否则版本追踪会悄悄失效）。"""
    rogue = tmp_path / "rogue.md"
    rogue.write_text("没有头部声明的提示词", encoding="utf-8")
    monkeypatch.setattr(prompts, "_PROMPT_DIR", tmp_path)
    prompts.load.cache_clear()
    try:
        with pytest.raises(ValueError, match="缺少头部声明"):
            prompts.load("rogue")
    finally:
        prompts.load.cache_clear()
