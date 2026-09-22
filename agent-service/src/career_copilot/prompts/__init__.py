"""Prompt 资源集中管理（ARCH-2）。

此前 6 处 SYSTEM_PROMPT 是内联字符串、散在 4 个文件里，既没有 id 也没有版本：
提示词一改，日志与指标里看不出「跑的是哪一版」，出问题只能翻 diff 猜。

约定：每个 ``.md`` 必须带头部注释声明 id 与版本，加载时校验；观测里统一用 ``id@vN`` 标识。
改了提示词内容就**递增 version**——版本号是给人看的，不是装饰。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import cache
from pathlib import Path

__all__ = ["Prompt", "available", "load"]

_PROMPT_DIR = Path(__file__).parent

#: 头部格式：<!-- prompt: <id> | version: <n> | 用途：… -->
_HEADER = re.compile(
    r"\A<!--\s*prompt:\s*(?P<id>[a-z_]+)"
    r"\s*\|\s*version:\s*(?P<version>\d+)"
    r"\s*\|(?P<rest>.*?)-->[ \t]*\n",
    re.S,
)


@dataclass(frozen=True)
class Prompt:
    """一个提示词资源：id + 版本 + 正文。"""

    id: str
    version: int
    text: str

    @property
    def ref(self) -> str:
        """观测用标识，如 ``intent@v1``。"""
        return f"{self.id}@v{self.version}"


def available() -> list[str]:
    """全部已登记的 prompt id（按名称排序）。"""
    return sorted(path.stem for path in _PROMPT_DIR.glob("*.md"))


@cache
def load(prompt_id: str) -> Prompt:
    """按 id 加载提示词。

    缺文件或头部不合规**直接抛错**：宁可启动就炸，也不要静默用错版本——
    「跑的是哪一版提示词」必须能从代码与日志里直接读出来。
    """
    path = _PROMPT_DIR / f"{prompt_id}.md"
    if not path.exists():
        raise FileNotFoundError(
            f"未登记的提示词: {prompt_id}（可用: {', '.join(available())}）"
        )
    raw = path.read_text(encoding="utf-8")
    matched = _HEADER.match(raw)
    if matched is None:
        raise ValueError(
            "提示词缺少头部声明，格式应为 "
            f"<!-- prompt: {prompt_id} | version: N | 用途：… -->：{path.name}"
        )
    return Prompt(
        id=matched.group("id"),
        version=int(matched.group("version")),
        text=raw[matched.end() :].strip(),
    )
