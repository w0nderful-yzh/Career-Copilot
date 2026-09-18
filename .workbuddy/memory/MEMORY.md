# Career-Copilot 项目备忘

## 门禁与命令
三端全绿是底线，禁止带失败基线累计（CI = `.github/workflows/ci.yml`）。
- Java：`export JAVA_HOME=~/.sdkman/candidates/java/25.0.4.1-tem && ./gradlew :app:test --no-daemon`
- Python（agent-service）：`uv run ruff check src tests` → `mypy src` → `pytest`（line-length 100，strict）
- 前端：`pnpm install --frozen-lockfile` → 各 `test:*` → `build` → `test:e2e`；新增单测脚本必须同步进 CI。

## 架构红线
Java = System of Record；Python 只做编排，禁止直连业务库，业务数据经 `clients/backend.py` 调 Java
`/api/agent/tools`。LLM/HTTP/S3 不得在数据库事务内执行。进度源 = `docs/TodoList.md`（写证据，不打勾）。

## 本地验证
- boss 的 dev 实例常驻 **8081**；验证换 8082 起自己的实例，只 kill 自己那个。后台起服务用工具
  background（`bootRun &` 会被带走 → 502 upstream connect failed）。
- 查库用 `.venv/bin/python` + psycopg（无 psql）；空库验迁移：临时库 →
  `POSTGRES_DB=xxx ./gradlew :app:cleanTest :app:test`（必须 cleanTest）。
- 会话缓存 Redis 值是 Redisson 二进制 → 用「散列是否变化」判断缓存状态；新增 Flyway 迁移前先看已用到哪一号。
- 共享 dev 库集成测试用独立探针名播种；探针产生的画像证据要通过**应用删除会话**回收
  （级联清证据 + 重算画像），别直接改库。

## 面试引擎不变量
- 题单合并必须走 `InterviewQuestionDTO.withIndex`（`create(...)` 会丢 difficulty / followUpType / expectedPoints）。
- 「已发生轮次」判据 = 该题有作答记录，不按 `currentQuestionIndex`；终态会话不得再产出当前题。
  进度分母 = 主问题数；前端推导集中在 `utils/interviewTurns.ts`。
- 退出会话登记 `closedInterviewSessionsRef`，否则旧 interview_session 信号块会拉回 Interview Mode。
- 技能名恒定：追问序号走 `followUpIndex`，绝不拼进 category（会变画像伪技能）。
- 逐轮参照物（TurnEvaluationRequest）= 当前题+回答 + 简历片段（`resumeSnippetFor`）+ 最近相关问答
  （`recentTurnsFor`）+ 画像基线（`profileBaselineFor`）；覆盖摘要与剩余预算尚未接入（属 P4Q-2）。
- 简历取数唯一入口 = `InterviewResumeContextResolver`；指定却读不到必须报可见原因。

## 逐轮提交一致性（P4-9a，V20260919）
- 闸门 = `interview_sessions.turn_version` + 条件更新（版本 / 待答题 / 仍在进行中，缺一即 0 行 → 拒绝）；
  答案、索引、状态、评估请求、幂等记录同属**一个短事务**，模型调用在其外。
- 幂等 = 表 `interview_turn_requests`（`(session_id, request_id)` 唯一 + 载荷指纹 + 原结果）：
  重放返回原结果；换载荷 3010；处理中重复 3012；过期 3011；非当前题 3013；已结束 3004。
- **「要不要收束」看 `completing`，「怎么推进」看 `action`**：`ofFinish` 的 completing 曾错置 false，
  导致交卷被当成普通作答（已结束会话被写回 IN_PROGRESS）；mock 掩盖、真实 DB 集成测试才抓到。
- 缓存跟随提交一次写齐；版本或题号不匹配先按 DB 实体重建缓存再报错。
- 评估触发带 `evaluate_epoch`，消费端原子领取、代次落后丢弃；重试先置回 PENDING 再入队。
- DB 集成测试共用门控 `interview.guide.support.LocalDatabaseGate`。排查提示：
  `questions_json` 反序列化失败会被吞成 3001「会话不存在」。

## Tool 契约（ARCH-1）
事实源 = Java `AgentToolRequests`；新增 Tool = 枚举 + record + dispatch 三处同改。改模型后按
`docs/TodoList.md` 7.7 的命令重导出，并把 `docs/contracts/agent-tools.json` 复制进 Python 包内副本。
两侧都拒未知参数（Python 12002）。**Schema 片段禁用 `Map.of`（顺序不稳）→ 统一 LinkedHashMap**。

## LLM 执行（ARCH-2）
Python 唯一入口 `agent/llm.py::LlmExecutor`；`LlmResult` 区分「调用失败」与「无内容」，失败时
`unwrap()` 抛 `LlmFailure`。预算两档 realtime / background，解析重试只针对 PARSE_FAILED；流式不重试。
Prompt 在 `prompts/*.md`（头部带 id/version，改内容必须递增）。Java 对应 `StructuredOutputInvoker`
+ `resources/prompts/*.st`。

## 面试状态与答案语义
- 异步评估只写 DB；唯一漂移窗口是「缓存 COMPLETED 但报告待生成」，`getSession` 仅在该窗口回源自愈。
- `evaluateStatus` 是区分评估中 / 失败的**唯一**依据；重试必须先重置 PENDING。
- `answer_state` 四态 ANSWERED / SKIPPED / DECLINED / UNANSWERED；未考察不落库；只有 ANSWERED
  参与评分与画像证据。
- 跳过是一等动作，与提交共用 `recordTurn`；前端轨迹判据 = 有答案或有 answerState（进行中题目列表
  来自缓存，漏带状态刷新就丢「已跳过」）。

## 能力画像（P3）
- 简历来源 = 声明型证据（score=NULL，不参与聚合）；确认结构化简历后按 resumeId 整体替换，删除级联清理。
  focus 必须真正影响出题，未命中任何分类时返回原方向全量分类。
- 画像差分零新增存储；修复端点 `/api/profile/repair/*`（幂等；**勿加 `@Transactional`**）。

## 踩坑
- DB 集成测试不能用方法内 `assumeTrue` → 类级 `@EnabledIf`。
- 异步生成器 `finally` 里不能 `yield`（会连带跳过后续 await）。
- SSE 中断测试须驱动 `body_iterator` + `aclose()`；脱手任务别复用请求作用域依赖。
- Tailwind v4：伪元素上 `@apply dark:` 会被重写成空 `:where()`。
- E2E：失败窗口用开关变量；路由桩用正则忽略查询参数；不要对同一文件并行 Edit。
