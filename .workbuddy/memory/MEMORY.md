# Career-Copilot 项目长期备忘

## 质量门禁（三端全绿；CI 已固化 `.github/workflows/ci.yml`，触发 main + dev，含聚合 quality-gate）
新增功能必须保持三端全绿——禁止带失败基线继续累计。最新基线以 CI 为准。
- **Java**：`export JAVA_HOME=~/.sdkman/candidates/java/25.0.4.1-tem && ./gradlew :app:test --no-daemon`
  （本机默认 JAVA_HOME 是 Corretto 8，Gradle 直接拒绝执行）
- **Python**（`agent-service/`）：`uv run ruff check src tests` → `uv run mypy src` → `uv run pytest`
  （ruff line-length 100，select E/F/I/UP/B/ASYNC；mypy strict）。uv 在 `~/.local/bin`，也可直接用 `.venv/bin/*`
- **Frontend**：`pnpm install --frozen-lockfile` → 各 `test:*` 脚本 → `pnpm run build` → `pnpm run test:e2e`
  （pnpm 需先 `corepack enable pnpm`）。**新增单测脚本必须同步加进 CI 的 "Run frontend unit tests"**
- DB 在线时画像集成用例真跑，跳过数比离线少 1；跳过均为显式声明的预期行为（不是问题）

## 架构红线
Java 是 System of Record，Python 只做 Agent 编排；Python 禁止直连业务库，业务数据一律经
`clients/backend.py` 调 Java `/api/agent/tools`。LLM / HTTP / S3 调用不得在数据库事务内执行。

## 进度单一事实来源
`docs/TodoList.md`：目标形态、按优先级排列的待办（4.x）、验收入口、已完成能力。
更新时以「已验证（真实链路/命令）」写证据，而不是只打勾。

## 性能纪律（boss 明确要求）
- 时刻考虑响应时间，尤其是**用户感知延迟**（提交/取消/停止/重发/切换路径）
- 引入大库前先估包体积；优化必须带实测数据并记入文档
- 已知锚点：代码高亮 prism-light + 白名单（697.69 kB → 146.80 kB）
- 面试性能验收目标（待实测）：点击反馈 100ms、显式跳过 P95 < 300ms、普通回答至下一题 P95 ≤ 3s

## 本地开发与真实链路验证
- **boss 的 dev 实例常驻 8081**（`./scripts/dev.sh` 管理，跑启动那一刻的代码）。真实验证先探活，
  **换端口起自己的实例（8082）并只 kill 自己那个**，绝不杀 8081。
- **后台起服务必须用工具的 background 机制**；`./gradlew bootRun &` 会被工具调用结束带走，
  下一轮请求打到不存在的端口（表现为 502 upstream connect failed，易误判成应用报错）。
- 查库/建库：`.venv/bin/python` + psycopg（本机无 psql 客户端）；HTTP 层用 httpx。
- 空库验迁移链：建临时库 → `POSTGRES_DB=xxx ./gradlew :app:cleanTest :app:test`
  （**必须 cleanTest**，否则 Gradle 判 up-to-date，不感知环境变量变化）
- 会话缓存的 Redis 值是 Redisson 二进制（枚举编码成紧凑整数），纯文本客户端读不出来 →
  验证缓存状态用「**散列是否变化**」；需要写客户端时临时装 redis，验完必须卸载。
- **新增 Flyway 迁移前先看目录里已用到哪一号**——版本撞车会让应用直接起不来。
- **共享 dev 库上的集成测试不能用真实业务名播种**：聚合是对全部证据求均值，真实使用留下的证据
  会让断言漂移。用独立探针名（`e2e-*-probe`）并兜底清理。

## 面试引擎不变量
- 题单合并**必须**走 `InterviewQuestionDTO.withIndex`（保留全部字段并同步偏移 parentQuestionIndex）；
  用 `create(...)` 重建会静默丢掉 difficulty / followUpType / expectedPoints。
- 题库含**候选择问**：「已发生轮次」的判据是**该题是否有作答记录**，绝不能按 `currentQuestionIndex`
  遍历题库；已结束（COMPLETED/EVALUATED）会话不得再产出「当前题」。
- 进度分母 = 主问题数（非自适应会话才可用 `totalQuestions`）；前端推导集中在 `utils/interviewTurns.ts`，
  有单测 + E2E 双保险。
- 退出面试会话要在 `closedInterviewSessionsRef` 登记，否则旧 `interview_session` 信号块会把用户拉回 Interview Mode。
- 技能名恒为稳定标识：追问序号走 `InterviewQuestionDTO.followUpIndex`，**绝不拼进 category**
  （会经 `answers.category` 变成画像伪技能）；`SkillNameNormalizer` 兼容历史「（追问N）」后缀。
- 逐轮评估的参照物（P4Q-1 批 3 已补齐）：`TurnEvaluationRequest` = 当前题 + 回答 +
  **本场简历片段**（`resumeSnippetFor`：按小节标题与分类双向子串匹配，兜底取开头，≤ 800 字）+
  **最近相关问答**（`recentTurnsFor`：同技能最近 2 轮、单条 ≤ 120 字，判据是「该题确有作答记录」，
  候选择问从未发生不能当历史）+ **画像基线**（`profileBaselineFor`：轻量查询 `listProfiles`，
  按分类匹配 ≤ 3 个技能）。缺失时 prompt 给可读占位而不是留白。
- **仍未进逐轮上下文**：覆盖摘要与剩余预算——分别是覆盖证据存储与时间预算概念的产物（P4Q-2），
  现在没有数据可依；先落空壳字段只会得到永远为空的 prompt 变量。
- **简历上下文取数唯一入口 = `InterviewResumeContextResolver`**（P4Q-1 已落地）：优先级
  「明确指定版本 → 简历 ACTIVE 结构化版本 → 原文（标明来源）→ 无」；调用方传的 `resumeText` 只是
  **显式文本通道**（仅在无 resumeId 时生效）。指定了简历却读不到 → 报错给可见原因，
  **绝不静默退化成通用面试**（缺陷期间 Agent 侧硬编码 `resume_text=None`，简历题分支从未生效）。
- 出题依据落快照：`interview_sessions.resume_source / resume_version / resume_context_text`（`V20260918`），
  DTO 与 `CachedSession` 同步带来源与版本。

## Agent Tool 契约（ARCH-1，2026-09-17 已落地）
- **事实源 = Java 类型化请求模型**：`AgentToolRequests`（13 个 record + jakarta validation + `@ToolParam` 语义），
  `AgentToolName` 每个常量持有自己的请求模型。新增 Tool = 枚举 + record + dispatch 三处一起加。
- **产物**：`docs/contracts/agent-tools.json`（golden）+ `agent-service/src/career_copilot/contracts/agent-tools.json`（包内副本）。
  改模型后必须重新导出并复制：`AGENT_TOOLS_SCHEMA_WRITE=true ./gradlew :app:test --tests "*AgentToolContractTest*"`，
  再 `cp docs/contracts/agent-tools.json agent-service/src/career_copilot/contracts/`。
- **两侧漂移检查**（随现有 CI 自动跑）：Java `AgentToolContractTest` 比对导出 vs golden；Python `test_contracts.py`
  比对包内副本 vs golden + 扫 `BackendClient` 里写死的 Tool 名是否都在契约中。
- **入参校验两处一致**：Java 拒绝未知参数（此前静默忽略）+ jakarta validation；Python 在 `call_tool` 之前用 pydantic
  动态模型校验，错误码 12002 对齐 Java `AGENT_TOOL_ARGUMENT_INVALID`。`/api/agent/tools` 的 inputSchema 现在是
  实时导出的真 JSON Schema（此前是连合法 JSON 都不是的手写字符串）。
- 架构认知（决定取舍）：Agent 是**确定性路由 + 硬编码调用**，`/api/agent/tools`（Discovery）无调用方，
  不是 function calling 自动选参数 → JSON Schema 的第一价值是**校验 + 漂移检查 + 行为测试**，不是「给 LLM 看」。
- 踩坑：**Schema 片段不能用 `Map.of` 构造**——它不保证迭代顺序（每次 JVM 启动可能不同），会让 golden 文件时快时慢地
  「变化」、漂移检查随机失败。导出器统一用 LinkedHashMap 固定顺序。

## LLM 执行治理（ARCH-2，2026-09-17 已落地）
- **Python 侧唯一入口 = `agent/llm.py` 的 `LlmExecutor`**：拿模型、预算、结构化契约（JSON 抽取 + pydantic 校验 +
  解析失败才重试）、错误分类（TIMEOUT/RATE_LIMITED/PARSE_FAILED/UPSTREAM）、观测都在这里。
  `deps.answerer._model` **私有属性访问已全部消失**——新代码不要再引入。
- `LlmResult[T]` 用 `ok`/`error`/`attempts`/`latency_ms` 表达结果：**「调用失败」与「模型没给内容」必须分开**；
  失败时 `unwrap()` 抛 `LlmFailure`（带分类）。
- 预算两档（`config.py`）：实时（用户等待路径）`llm_timeout_realtime_seconds`、
  后台（报告/提案/摘要）`llm_timeout_background_seconds`；重试次数 `llm_parse_retries`（只针对解析失败）。
- **提示词资源在 `career_copilot/prompts/*.md`**，每个文件自带 `<!-- prompt: <id> | version: N | 用途：… -->`，
  加载时校验、缺头部即报错；改内容要**递增 version**，观测里用 `id@vN`。
- 边界：流式不重试（增量无法回滚）；重试责任只在这一层，不叠加成不受控重试链。
- Java 侧对应物是 `StructuredOutputInvoker`（2 次尝试 + 指标 + 严格 JSON 指令），配置在 `application.yml`
  的 `app.ai.structured-*`；Java 提示词在 `app/src/main/resources/prompts/*.st`（21 个，`{var}` 占位）。

## 面试状态与答案语义（P4Q-4/5/6）
- 异步评估链路**只写数据库**；唯一会漂移的窗口是「缓存状态 = COMPLETED，报告待生成」，
  `getSession` 只在该窗口回源 DB 自愈并回写，其余读取纯走缓存（性能护栏：不为一致性给答题链路加库压力）。
- `evaluateStatus` 是区分「评估中」与「评估失败」的**唯一依据**（status 两者都是 COMPLETED）。
  评估重试必须先把 evaluateStatus 重置 PENDING，否则消费端 `shouldSkip` 会让重试静默无效。
- **`interview_answers.answer_state` 四态**：ANSWERED / SKIPPED / DECLINED / UNANSWERED；
  **未考察不落库**（没有答案行即为未考察）。**只有 ANSWERED 参与报告评分与画像证据**。
- 跳过是一等动作（`POST /interview/sessions/{id}/skip` + 前端「跳过」按钮）：不调模型、不追问、不计分、
  不产生证据；提交与跳过共用 `recordTurn`，避免两套推进逻辑漂移。
- 自然语言分两层：**精确匹配短路**（拆「跳过」/「明确不会」两类词表，所有会话生效、不花模型）+
  语义判断（`TurnEvaluation.skipRequested`）。提示词禁止关键词匹配（「死锁不会发生」是技术判断）。
- 前端轨迹判据是「**发生过**」= 有答案**或**有 `answerState`。进行中会话的题目列表来自**缓存**，
  写回时漏带状态刷新后就看不到「已跳过」（踩过）。

## 能力画像（P3）
- 简历来源 = **声明型证据**：`skill_evidence.score=NULL`、**不参与聚合**（简历侧没有逐技能分，编分会破坏
  「画像分能由证据还原」）。`declaredSkills` = 有 RESUME 声明但不在 `skill_profiles` 的技能，大小写不敏感排除已考项。
- 同步时机 = **用户确认结构化简历之后**（`ResumeVersionService.confirmVersion`），按 resumeId **整体替换**
  （防重新解析后的幽灵技能）；简历删除级联清理。
- focus 必须真正影响出题：`create_interview` 带 `focusCategories` → `InterviewSkillService.focusOn` 按 key/label
  大小写不敏感裁剪；**未命中任何分类时返回原方向全量分类**（空分类比没聚焦严重）。
- 画像差分（`GET /api/interview/sessions/{id}/profile-impact`）**零新增存储**：before = 排除本场证据的均值、
  after = 含本场；首次考到 before=null 且 delta=0（前端渲染「本场新增」，不编造涨幅）。
- 画像技能名（来自面试 category，即 label）与方向分类 key 是两套命名——映射是语义问题交给 LLM，
  代码只做白名单（`_sanitize_focus`：key 精确 → label 精确 → 双向子串）。

## 历史数据修复端点（幂等，可重跑）
- `POST /api/profile/repair/follow-up-skills`：合并「（追问N）」伪技能证据并重算
- `POST /api/profile/repair/skip-semantics`：复核「已标记作答但得 0 分」的答案是否其实为跳过/明确不会
  （**会调用模型**，候选集很小），作废证据并重算
- 修复例程**不要加 `@Transactional`**：归类可能走模型调用，外部调用不得包在数据库事务里；
  逐条落库交给 Repository 的短事务。

## 已验证的踩坑（高价值）
- **JUnit + @SpringBootTest 的 DB 集成测试不能用方法内 `assumeTrue`**：Spring 上下文（含 Flyway）在方法体前
  已初始化，连不上库会**整类报错**。用类级 `@EnabledIf`（先判凭据可解析，再短超时 TCP 探测）。
- **异步生成器 `finally` 里不能 `yield`**：抛 `RuntimeError: async generator ignored GeneratorExit`，并**连带跳过
  后面的 `await`**（曾造成流式落库整轮丢失）。收尾副作用放正常/异常分支或脱手任务。
- **测试 SSE 中断（abort）不能用 TestClient**：`response.close()` 只停止读取，服务端生成器会跑完 →
  必须驱动 `StreamingResponse.body_iterator` 并 `await agen.aclose()`。
- **脱手任务里不要复用请求作用域的依赖**：`get_backend_client` 在请求结束 `aclose()`；模块内直建客户端会
  **绕过 `dependency_overrides`** 让测试打真实后端 → 单列工厂函数供 monkeypatch。
- **Tailwind v4**：`@custom-variant dark` 与**伪元素上的 `@apply dark:/hover:`** 组合会被重写成空 `:where()`，
  vite 压缩报 css-syntax-error → 伪元素直接写声明 + `var(--color-*)`，暗色显式写 `.dark` 祖先选择器。
- **E2E 打桩四条**：失败窗口用开关变量而非「调用序号」；路由桩必须与查询参数无关（用正则，否则请求落到
  真实后端表现为「加载失败」）；断言要限定作用域（strict mode）；**同一 URL 注册两次 `page.route` 以后注册者
  为准**。E2E 自带 WebSocket stub 与 mock，只需前端 dev server，不依赖 Java/DB。
- **Mockito 严格模式**：同类里既有纯策略用例又有链路用例时，`@BeforeEach` 的桩会触发
  `UnnecessaryStubbingException` → 用 `lenient()` 显式声明按需使用。
- **测「缓存失效后按 DB 重建」不要把预期结果塞回被测代码**：用 `doAnswer` 做内存 store 真读真写，
  答案回填逻辑才真正被验证。
- **不要对同一个文件并行发起多个 Edit**：实测三条同时打到 `MEMORY.md`，只有一条落盘、另两条被静默覆盖
  （都返回「成功」）。同一文件的多次修改必须串行。
- **`AgentMessageEntity.status` 三态**（`V20260914`）：COMPLETED / STOPPED / FAILED；未完成助手消息
  **允许空内容**；`completed` 布尔已删除（别和 RAG 的 `RagChatMessageEntity.completed` 搞混——那个在用）。
- **前端大包**：代码高亮走 prism-light + 显式静态字符串白名单（模板字符串无法被 Vite 静态分析）；
  `manualChunks` 用函数形式按包路径分组（对象形式会切出碎片 chunk）。
