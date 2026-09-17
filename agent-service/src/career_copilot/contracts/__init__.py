"""Agent Tool 参数契约（ARCH-1：契约单一事实源）。

``agent-tools.json`` 是**产物**：事实源是 Java 的类型化请求模型
（``app/src/main/java/interview/guide/modules/agenttool/model/AgentToolRequests.java``），
由 ``AgentToolContractTest`` 导出。本模块只做两件事：

1. 把契约读进内存（进程内只读一次）；
2. 在调用 Java Tool 之前用契约校验参数。

为什么值得加这一层：契约以前是三份手写副本（Java 枚举 / Java 手写 schema 字符串 /
本服务里的 ToolSpec 列表），零校验、已经漂移。漂移的代价不是「报错」，而是
**功能静默失效**——``focusCategories`` 就曾这样变成纯装饰：界面写着「重点考察 JVM」，
实际照旧问 MySQL，谁都没发现。

契约漂移由两侧测试共同看住：Java 侧比对导出结果与 ``docs/contracts/agent-tools.json``，
Python 侧比对本目录副本与该 golden 文件。
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from functools import cache
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError, create_model

__all__ = [
    "CONTRACT_FILE",
    "ContractViolation",
    "tool_names",
    "tool_schema",
    "validate_arguments",
]

#: 契约文件（由 Java 侧导出，请勿手改）
CONTRACT_FILE = Path(__file__).with_name("agent-tools.json")

#: pydantic 错误类型 → 中文原因（不把英文报错原样抛给调用方）
_REASON_ZH: dict[str, str] = {
    "missing": "缺失（必填参数）",
    "extra_forbidden": "未知参数（契约中不存在）",
    "int_parsing": "类型错误（应为整数）",
    "int_type": "类型错误（应为整数）",
    "float_parsing": "类型错误（应为数字）",
    "string_type": "类型错误（应为字符串）",
    "bool_type": "类型错误（应为布尔值）",
    "bool_parsing": "类型错误（应为布尔值）",
    "list_type": "类型错误（应为数组）",
    "dict_type": "类型错误（应为对象）",
    "greater_than": "取值必须大于下限",
    "greater_than_equal": "取值低于允许范围",
    "less_than": "取值必须小于上限",
    "less_than_equal": "取值超出允许范围",
    "string_too_short": "不能为空",
    "too_short": "元素数量不足（至少一个）",
}

#: JSON Schema 约束 → pydantic Field 约束（只映射契约实际会导出的几种）
_CONSTRAINT_MAP: tuple[tuple[str, str], ...] = (
    ("minLength", "min_length"),
    ("maxLength", "max_length"),
    ("minimum", "ge"),
    ("maximum", "le"),
    ("exclusiveMinimum", "gt"),
    ("exclusiveMaximum", "lt"),
    ("minItems", "min_length"),
    ("maxItems", "max_length"),
)


class ContractViolation(Exception):
    """调用前契约校验失败：参数不符合 Java 侧导出的 Tool 契约。"""

    def __init__(self, tool: str, detail: str) -> None:
        super().__init__(f"Tool {tool} 参数不符合契约: {detail}")
        self.tool = tool
        self.detail = detail


@cache
def _contract() -> dict[str, Any]:
    """整份契约；文件缺失或损坏属于构建/部署问题，直接抛出让人看见。"""
    loaded: dict[str, Any] = json.loads(CONTRACT_FILE.read_text(encoding="utf-8"))
    return loaded


def tool_names() -> list[str]:
    """契约中声明的全部 Tool 名（用于漂移排查）。"""
    return sorted(_contract()["tools"])


def tool_schema(tool: str) -> dict[str, Any]:
    """单个 Tool 的 JSON Schema；未知 Tool 视为契约漂移。"""
    schema = _contract()["tools"].get(tool)
    if not isinstance(schema, dict):
        raise ContractViolation(tool, f"契约中没有这个 Tool（已知: {', '.join(tool_names())}）")
    return schema


def validate_arguments(tool: str, arguments: Mapping[str, Any]) -> None:
    """调用前校验参数；不通过时抛 :class:`ContractViolation`（含字段级中文原因）。

    未知 Tool 也走同一条拒绝路径——契约里没有却在被调用，本身就是漂移。
    """
    try:
        _model(tool).model_validate(dict(arguments))
    except ValidationError as exc:
        raise ContractViolation(tool, _describe(exc)) from exc


@cache
def _model(tool: str) -> type[BaseModel]:
    """按 Tool 生成校验模型（进程内缓存：同一个 Tool 只构建一次）。"""
    schema = tool_schema(tool)
    required = set(schema.get("required") or [])
    fields: dict[str, Any] = {
        name: _field(name, prop, required)
        for name, prop in (schema.get("properties") or {}).items()
    }
    return create_model(
        f"{_pascal(tool)}Contract",
        # 未知参数拒绝：与 Java 侧 additionalProperties=false 保持一致
        __config__=ConfigDict(extra="forbid"),
        **fields,
    )


def _field(name: str, prop: Mapping[str, Any], required: set[str]) -> tuple[Any, Any]:
    """JSON Schema 片段 → pydantic 字段定义（required 用省略号，可选默认 None）。"""
    annotation = _annotation(prop)
    constraints = _constraints(prop)
    if name in required:
        return (annotation, Field(**constraints))
    return (annotation | None, Field(default=None, **constraints))


def _annotation(prop: Mapping[str, Any]) -> Any:
    """JSON Schema 类型 → Python 类型；数组递归到元素类型。"""
    match prop.get("type"):
        case "string":
            return str
        case "integer":
            return int
        case "number":
            return float
        case "boolean":
            return bool
        case "array":
            # 元素类型用「运行时下标」构造：mypy 不接受变量做类型下标（运行时合法），显式忽略
            return list[_annotation(prop.get("items") or {})]  # type: ignore[misc]
        case _:
            return dict


def _constraints(prop: Mapping[str, Any]) -> dict[str, Any]:
    """JSON Schema 约束 → pydantic Field 约束（未声明约束的字段为空）。"""
    return {target: prop[source] for source, target in _CONSTRAINT_MAP if source in prop}


def _describe(exc: ValidationError) -> str:
    """把 pydantic 错误转成「字段 原因」的可读串：调用方要能一眼看出该改什么。"""
    parts = []
    for error in exc.errors():
        field = ".".join(str(item) for item in error["loc"]) or "(整体)"
        parts.append(f"{field} {_REASON_ZH.get(error['type'], error['msg'])}")
    return "；".join(parts)


def _pascal(name: str) -> str:
    """snake_case → PascalCase（仅用于生成模型类名，便于报错时辨认）。"""
    return "".join(part.capitalize() for part in name.split("_"))
