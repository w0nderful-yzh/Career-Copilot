"""Patch 校验器（P2-1 / P2 待修正）：代码控边界的确定性校验。

真实性双保险的代码侧：LLM prompt 禁止虚构（第一层），本校验器做第二层。
校验必须**确定性**（不依赖 LLM 自觉），且以「原文出现过就放行」为基本判据，
避免把原文已有事实误判为编造。

校验维度（对齐设计文档 §35「未知技术 / 虚构公司 / 关键事实」）：
1. 结构性：REORDER 一期拒绝、path 白名单、必填值
2. 真实性-数字：newValue 引入原文没有的量化数字
3. 真实性-技术栈：newValue 引入原文没有的技术名词
4. 真实性-公司：newValue 引入原文没有的公司/机构实体
5. 真实性-经历事实：不得由 AI 新增/删除整段经历、不得改写身份字段
   （公司、岗位、项目名、学校、学历、起止时间、姓名、联系方式）
"""

import re
from dataclasses import dataclass, field

from career_copilot.schemas.resume_patch import PatchType, ResumePatch

# 数字 token：整数 / 小数
_NUMBER_PATTERN = re.compile(r"\d+(?:\.\d+)?")

# 拉丁 token（含 Node.js / C++ / C# / Java8 这类带符号或数字的写法）
_TOKEN_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9]*(?:[.+#][A-Za-z0-9]+)*")

# 单首字母大写的常见技术名：词形规则（内部大写/符号/数字）识别不出来，
# 而这些恰是最容易被模型「顺手加上」的技术栈，故用确定性词表兜底。
# 词表只用于「判断新出现的 token 是不是技术名词」，不做同义词归并。
_KNOWN_TECH_WORDS = frozenset({
    "java", "javascript", "typescript", "python", "golang", "rust", "scala",
    "kotlin", "php", "ruby", "swift", "shell", "bash", "sql", "nosql",
    "spring", "springboot", "springcloud", "django", "flask", "fastapi",
    "react", "vue", "angular", "svelte", "jquery", "bootstrap", "tailwind",
    "node", "nodejs", "deno", "express", "koa", "nestjs",
    "redis", "memcached", "kafka", "rabbitmq", "rocketmq", "zookeeper",
    "mysql", "postgres", "postgresql", "oracle", "sqlserver", "sqlite",
    "mongodb", "neo4j", "clickhouse", "elasticsearch", "lucene", "solr",
    "hadoop", "spark", "flink", "hive", "hbase", "presto", "doris",
    "docker", "kubernetes", "k8s", "istio", "jenkins", "gitlab", "github",
    "git", "maven", "gradle", "nginx", "tomcat", "jetty", "linux", "unix",
    "netty", "dubbo", "mybatis", "hibernate", "jpa", "jvm", "gc",
    "prometheus", "grafana", "zipkin", "skywalking", "sentry", "elk",
    "html", "css", "sass", "less", "webpack", "vite", "rollup", "babel",
    "langchain", "langgraph", "openai", "ollama", "huggingface", "pytorch",
    "tensorflow", "pandas", "numpy", "scikit",
})

# 中文公司/机构实体：以机构后缀结尾的名称
_COMPANY_PATTERN = re.compile(
    r"[\u4e00-\u9fa5A-Za-z0-9（）()]{2,24}"
    r"(?:公司|集团|科技|银行|研究院|研究所|事业部|工作室)"
)

# 公司实体误报拦截：描述性短语（"负责公司核心系统"）不是公司名
_COMPANY_FALSE_POSITIVE_WORDS = (
    "负责", "参与", "主导", "协助", "完成", "优化", "提升", "改进", "支持",
    "维护", "开发", "实现", "进行", "针对", "面向", "通过", "使用", "所在",
)

# 事实字段：这些是经历事实而非表达，AI 不得直接改写（用户可在解析确认页自行修正）
_FACT_FIELDS = frozenset({
    "name", "phone", "email",
    "company", "position", "school", "major", "degree",
    "startDate", "endDate",
})

# 整条目集合：新增/删除一个条目 = 增删一段经历事实
_ENTRY_COLLECTIONS = ("education", "experience", "projects")

_KNOWN_SEGMENTS = frozenset({
    "basicInfo", "education", "experience", "projects", "skills", "customSections",
})

# 整条目 path，如 projects[0]
_ENTRY_PATH_PATTERN = re.compile(
    r"^(education|experience|projects)\[\d+\]$"
)


@dataclass(frozen=True)
class _ResumeFacts:
    """原文事实索引：数字、拉丁 token、公司实体（用于「原文出现过即放行」判据）。"""

    lower_text: str
    numbers: frozenset[str]
    tokens: frozenset[str]

    @classmethod
    def from_text(cls, resume_text: str) -> "_ResumeFacts":
        text = resume_text or ""
        return cls(
            lower_text=text.lower(),
            numbers=frozenset(_NUMBER_PATTERN.findall(text)),
            tokens=frozenset(
                token.lower() for token in _TOKEN_PATTERN.findall(text)
            ),
        )


@dataclass
class PatchValidationResult:
    """校验结果：rejected 全部不合法 patch 的 (index, 原因)。"""

    rejected: list[tuple[int, str]] = field(default_factory=list)

    @property
    def has_rejection(self) -> bool:
        return bool(self.rejected)


def validate_patches(
    patches: list[ResumePatch],
    resume_text: str,
) -> PatchValidationResult:
    """对 Agent 产出的 patch 列表做确定性校验。

    规则（全部代码边界，不依赖 LLM 自觉），任一命中即拒绝该条建议：
    - REORDER 一期直接拒绝
    - REPLACE/DELETE 必须带 oldValue；REPLACE/ADD 必须带 newValue
    - path 必须指向已知结构段
    - 不得新增/删除整段经历（projects/experience/education 条目）
    - 不得改写身份/事实字段（公司、岗位、项目名、学校、学历、时间、姓名、联系方式）
    - newValue 不得引入原文没有的数字 / 技术名词 / 公司实体
    """
    result = PatchValidationResult()
    facts = _ResumeFacts.from_text(resume_text)

    for index, patch in enumerate(patches):
        reason = _validate_single(patch, facts)
        if reason:
            result.rejected.append((index, reason))
    return result


def _validate_single(patch: ResumePatch, facts: _ResumeFacts) -> str | None:
    if patch.type == PatchType.REORDER:
        return "暂不支持顺序调整（REORDER）类型的修改"

    if patch.type in (PatchType.REPLACE, PatchType.DELETE) and not (patch.oldValue or "").strip():
        return f"{patch.type.value} 类型必须提供 oldValue（用于应用时一致性校验）"
    if patch.type in (PatchType.REPLACE, PatchType.ADD) and not (patch.newValue or "").strip():
        return f"{patch.type.value} 类型必须提供 newValue"

    path = patch.path or ""
    top_segment = path.split("[", 1)[0].split(".", 1)[0]
    if top_segment not in _KNOWN_SEGMENTS:
        return f"path 非法：未知结构段 {top_segment!r}"

    # 经历事实边界：整段经历的增删由用户决定，AI 只能在既有条目内改写
    if patch.type == PatchType.ADD and path in _ENTRY_COLLECTIONS:
        return f"禁止新增整段经历（{path}）：经历事实需用户自行补充"
    if patch.type == PatchType.DELETE and _ENTRY_PATH_PATTERN.match(path):
        return f"禁止删除整段经历（{path}）：经历事实需用户确认后自行删除"

    # 身份/事实字段不得由 AI 改写
    last_field = _last_field(path)
    if patch.type in (PatchType.REPLACE, PatchType.DELETE) and last_field in _FACT_FIELDS:
        return f"禁止改写关键事实字段（{last_field}）：需用户自行确认修改"

    # 真实性校验只针对新增文本（ADD 的新值整段新增；REPLACE 检查新值引入的新内容）
    if patch.type in (PatchType.REPLACE, PatchType.ADD):
        new_value = patch.newValue or ""

        for number in _NUMBER_PATTERN.findall(new_value):
            # 原文出现过的数字可直接复用（一期从简：数字完全一致才放行）
            if number not in facts.numbers:
                return (
                    f"newValue 引入了原文没有的数字 {number!r}，"
                    "疑似编造量化业绩（需用户提供真实数据）"
                )

        new_tech = _find_new_tech_tokens(new_value, facts)
        if new_tech:
            return (
                f"newValue 引入了原文没有的技术名词 {'/'.join(new_tech)}，"
                "疑似编造技术栈（需用户确认真实掌握）"
            )

        new_company = _find_new_company(new_value, facts)
        if new_company:
            return (
                f"newValue 引入了原文没有的公司/机构 {new_company!r}，"
                "疑似编造经历事实（需用户确认）"
            )
    return None


def _last_field(path: str) -> str:
    """path 的最后一个字段名；以数组索引结尾（projects[0]）时返回空串。"""
    if path.endswith("]"):
        return ""
    return path.rsplit(".", 1)[-1]


def _find_new_tech_tokens(new_value: str, facts: _ResumeFacts) -> list[str]:
    """newValue 中原文未出现过的技术名词（按出现顺序去重，最多报 3 个）。"""
    found: list[str] = []
    for token in _TOKEN_PATTERN.findall(new_value):
        lowered = token.lower()
        if lowered in facts.tokens or lowered in found:
            continue
        if _looks_like_tech(token):
            found.append(token)
        if len(found) >= 3:
            break
    return found


def _looks_like_tech(token: str) -> bool:
    """技术名词词形：已知技术词 / 含 .+# 符号 / 含数字 / 词内有第二个大写字母。"""
    if token.lower() in _KNOWN_TECH_WORDS:
        return True
    if any(ch in token for ch in ".+#"):
        return True
    if any(ch.isdigit() for ch in token):
        return True
    return any(ch.isupper() for ch in token[1:])


def _find_new_company(new_value: str, facts: _ResumeFacts) -> str | None:
    """newValue 中原文未出现过的公司/机构实体（过滤描述性短语误报）。"""
    for raw_match in _COMPANY_PATTERN.findall(new_value):
        # 中文无词边界，正则起点可能含前置虚词（「在腾讯科技公司」→「腾讯科技公司」）
        entity = str(raw_match).strip().lstrip("在于是的了与和及为到从对")
        if len(entity) < 4 or entity.lower() in facts.lower_text:
            continue
        if any(word in entity for word in _COMPANY_FALSE_POSITIVE_WORDS):
            continue
        return entity
    return None
