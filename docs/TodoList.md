# Career Copilot TodoList

> **范围基线**：`Career-Copilot-newdocs/Career-Copilot-Core-4-Features-Scope.md`
> 只做 4 个核心功能：**Copilot Agent 主入口 / 简历优化 / 长期用户能力画像 / 自适应模拟面试**，
> 以及 3 条产品闭环。其他需求一律问「是否直接服务这四个」——不是则暂缓。
>
> **声明：项目不做多用户功能，无需设计用户数据分离。**
> `user_id` 字段仅作架构预留，恒为 `default`，所有相关 TODO 已删除。
>
> 详细设计见：`Career-Copilot-Agent-Graph-Design.md`（Graph）、`Career-Copilot-Resume-Optimization-Requirement.md`（简历优化）、`Career Copilot 自适应模拟面试引擎设计文档.md`（自适应面试）、`Career-Copilot-Inline-Interview-Design.md`（内嵌面试交互）。

---

## 开发工具

```bash
./scripts/dev.sh start|stop|restart|status    # 三服务一键启停（8081 / 8001 / 5173）
./scripts/dev.sh logs [java|agent|web]
```

⚠️ 已知问题：`restart` 在端口半开状态下 `wait_java` 的 curl 无超时会挂起；bash 会话被杀会连带杀掉后台子进程。建议修复脚本（curl 加 `-m`、启动用 `setsid`/`start_new_session` 脱离进程组）。

---

## 里程碑（已完成）

- [x] **Copilot Workspace MVP**：/copilot 工作台、SSE 流式、受控 Block 渲染（text/action/resume_summary/interview_summary/knowledge_citations）、Action 白名单导航
- [x] **对话持久化**：Java conversation 模块 + 流式后保存 + 前端会话侧栏
- [x] **Agent 模型统一管理**：Java Provider 配置 → Python 启动同步 + 首个请求惰性重试
- [x] **主 Graph（Copilot Turn Graph）**：LangGraph `normalize_input → load_history → resolve_context → route_intent → 分支 → build_response`；plan 式执行（API 层 SSE）；意图短路；ATTACHMENT / ACTION 确定性路由；ChoiceBlock + execute_action 注册表（Python 侧协议完成）
- [x] **简历上传附件**：Composer 拖入 PDF → 直传 Java 简历库 → Agent 如实确认（含 duplicate 提示）
- [x] **简历 Tool 链**：`get_resume_list` / `get_resume_analysis` / `get_resume`（完整文本 + maxChars 截断）；定向简历查询**内容感知**（注入全文 8000 字符截断）
- [x] **目标简历解析（§26）**：附件 > 消息中文件名 > 唯一自动锁定 > 默认最近一份并说明
- [x] **短期记忆（Conversation Memory）**：Java `context/summary` 端点、`load_history` 注入意图分类与回答、滚动摘要写回、PG Checkpoint（独立库 `agent_checkpoint`，剥离瞬时字段，流式兼容）
- [x] **P1-1 前端 ChoiceBlock 渲染 + Action 提交**：BlockRenderer 白名单渲染 choice 块、ACTION_SELECTED 回传、RESUME_DETAIL 受控路由（`ba15bbe`）

---

# 核心一：Copilot Agent 主入口（收尾）—— 优先级 P1

> 目标：/copilot 成为统一入口，稳定处理 Text / File / Action 并结构化返回。Graph 与记忆已完成，剩前端协议打通与少量真实分支。

- [x] **P1-1 前端 ChoiceBlock 渲染 + Action 提交**
  - `types/copilot.ts` 增加 `choice` 块类型 + BlockRenderer 白名单渲染
  - ChoiceOption 点击 → 发送 `{action: {type:"ACTION_SELECTED", action, payload}}`（复用 streamChat）
  - `RESUME_DETAIL` 加入 ACTION_ROUTE_MAP（带 params.resumeId 跳转）
  - 已补齐重复点击锁定、Action 可读历史气泡、非法动态路由参数拒绝与前端单元测试
  - 已实测：ChoiceBlock「分析简历」→ ACTION_SELECTED → Graph execute_action → RESUME_DETAIL → `/history/:resumeId`
  - UI 占位说明：右侧目标/能力画像/今日任务目前为明确标注的预览数据，不进入 Agent Context 或持久化；P1-3/P3 接入真实数据后替换
- [x] **P1-2 SSE Tool / Run 事件**
  - Graph 执行期经 LangGraph custom stream（`get_stream_writer`）实时转发节点埋点事件
  - `tool_started` / `tool_completed` 成对（load_history/resume_query/resume_insight/interview_review/knowledge_search + 中文 label）；run_status RUNNING / WAITING_USER / COMPLETED / FAILED
  - 前端消息气泡内轻量状态行（spinner + label），首个 block/delta 后清除；WAITING_USER 显示「等待你的选择…」
  - `/chat` 同步入口与单测 ainvoke 路径无 writer 时静默丢弃，行为不变
- [x] **P1-3 Conversation 绑定活动资源**（Conversation Memory 的 Active Resume）
  - Java：`agent_conversations.active_resume_id` 迁移 + `PUT /{id}/active-resume`（null 解绑）+ context/detail 响应透出
  - Python：定向简历分析后自动回写绑定；`resolve_context` 优先级 附件 > 会话绑定（`bound_resume_id`），无附件追问跨轮锁定同一目标
  - 已实测：轮1 带附件分析 → 绑定落库；轮2 无附件追问 → 恢复 resume 1 且内容感知继续生效
- [x] **P1-4 面试发起 Agent 化**（交互终态见 `Career-Copilot-Inline-Interview-Design.md`）
  - 意图命中模拟面试时不再直接跳页：Agent 读 Resume / `list_skills` → 推导方向/难度/focus → 输出面试提案确认块（[按推荐开始] / [调整配置]，调整走 ChoiceBlock 再推荐）
  - `CREATE_INTERVIEW` action → `create_interview` 写 Tool（Java interview 引擎已有创建能力，薄封装，权限 CONFIRM_WRITE）
  - **过渡方案**：创建成功先用 NavigationBlock 进入现有面试会话页；P4-0 的 InterviewSessionBlock 就绪后原地内嵌替换（Inline 文档 Case 2：不强制跳 /interview-hub）
  - 已实测：自然语言「来一场模拟面试」→ 提案块（Java 后端 · 校招 · PROJECT/JAVA/MYSQL）→ CREATE_INTERVIEW → 创建成功 → NavigationBlock（INTERVIEW_SESSION → /interview/session/:id）；无简历回落默认推荐；缺失 direction 拒绝创建
- [x] **P1-5 KNOWLEDGE_QA 保持 Tool 化**（已通，回归验证即可）
  - 已回归：意图「JVM GC 是什么」→ KNOWLEDGE_QA → knowledge_tool 节点（未裸答）；本地知识库为空时如实兜底；search_knowledge → RAG 答案 → knowledge_citations 引用块由既有单测覆盖（test_chat_api）
- [ ] **P1-6 打磨（非闭环必需，穿插做）**
  - 流式中断消息标记（Java message status「已停止」）
  - 会话重命名 / 归档 UI（API 已有）
  - Composer 移除 window.alert，改内联错误提示

**验收**：Text/File/Action 三类输入稳定可用；Tool 调用有可见状态；附件→确认→选择→跳转全链路在前端真实可点。

---

# 实施顺序（2026-08 重构）

> 依据画像依赖关系与价值释放节奏确定：**P3 画像基础 → P2 简历优化 → P4 自适应面试**。
> P3 基础（存储 + 聚合 + 查询）是 P2 与 P4 的共同地基且规模最小，先做可让 P2 一次性原生消费画像（描述强度约束）、P4 一次接入（低分技能 → focus）；P3-3「Profile 参与决策」不单独成项，拆进 P2-1 与 P4-3 两个消费点。
> 简历优化方案重设计见：`career_copilot_resume_optimization_design.md`（JSON-first Patch + Typst 导出）与 `career_copilot_resume_optimization_interaction_design.md`（Preview PDF / 自评审 / Clarification）。
> 已确认决策：只做 PDF 导出（Typst，XeLaTeX/DOCX 不做）；HITL 用提案持久化 + ACTION_SELECTED（不用 LangGraph interrupt）；自评审循环预留节点、循环次数配置化、一期默认最小，文档如实记录；Preview PDF「勾选即重渲」；正式 PDF 手动导出；REORDER 一期 schema 保留、校验器拒绝。

---

# 核心三（先行）：长期用户能力画像（基础）—— 优先级 P3

> 目标：Evidence-driven Skill Profile，评分可追溯（Resume / Interview Session / Turn），并真正参与后续决策。
> 数据现状就绪：简历分析（关键词/技能条目）与现有面试报告（categoryScores）已可聚合，P3 建成即有真实数据；画像必须在 P4 之前（自适应面试要消费画像定重点）。

- [x] **P3-1 Java SkillProfile + Evidence 存储**
  - `skill_profiles`（skill / score / evidenceCount / updatedAt）与 `skill_evidence`（sourceType: RESUME/INTERVIEW_SESSION/INTERVIEW_TURN、sourceId、scoreContribution、timestamp）
  - Aggregator：简历分析关键词/技能条目 + 面试题评分为输入聚合出分；每次新 Evidence 触发增量更新
  - 已落地：`modules/profile`（entity/repository/Aggregator/Extractor/Constants）+ `V20260829` 迁移；聚合 = 等权均值（可由 evidence 逐条还原），`(user_id, skill, source_type, source_id)` 唯一保证评估重放幂等；评估完成钩子（EvaluateStreamConsumer）+ 会话/简历删除级联清理已接；未作答题（「未考」）不计证据
  - 一期证据输入只有面试逐题分（category=技能名）；RESUME 类型待 P2-0 结构化解析后接入，INTERVIEW_SESSION 为冗余证据暂不写入
  - 已验证：13 个单测 + 真库集成测试（提取→聚合→级联全链路，.env 不可用时自动跳过）+ 全量 `:app:test` 通过
- [x] **P3-2 Profile 查询链路**
  - `get_skill_profile` Agent Tool + `/internal/agent/profile/skills`
  - Graph：`load_profile` 节点（PROFILE_QUERY / 简历优化 / 面试创建前使用）；`SkillProfileBlock` 前端渲染
  - PROFILE_QUERY 占位分支替换为真实数据；Copilot 右侧画像面板从 P1-1 预览假数据切换为真实数据
  - 已落地：Java `GET_SKILL_PROFILE` READ Tool（画像 + 证据明细一次取全，双层信封）；Python `profile_query` 节点（无数据时引导面试，SkillProfileBlock + `summarize_skill_profile` 上下文）；前端 `SkillProfileBlockView`（分数条 + 点击展开证据来源）+ 侧栏 `ProfileSection`（真实 API、loading/error/empty 态）
  - 已实测（真实链路）：「我的技能水平怎么样」→ PROFILE_QUERY → 读取技能画像 → 画像卡（MySQL 83 绿条 / JVM 55 橙条）→ 点开 JVM 展开证据「模拟面试答题（sessionId:2）· 55 分」→ LLM 引用证据解读并如实说明样本量少；侧栏同步显示真实数据；空库时如实告知并引导面试
- [x] **P3-4 用户快照**：新会话首轮注入 top 技能 + 最近面试概要（低成本跨会话感知，复用 get_skill_profile/get_interview_history）
  - 已落地：`load_snapshot` 节点（仅首轮拉取，两 READ Tool 并行，失败静默降级）；快照经 `format_history(snapshot=...)` 注入 direct_answer / business_tools / profile_query 的回答上下文；PREPARATION_QUERY 占位分支升级为基于快照的真实回答（无快照时保持占位）
  - 已实测（真实链路）：新会话「帮我看看最近复习得怎么样」→「了解你的近期表现」Tool 轨迹 → LLM 综合技能画像（MySQL 83 / JVM 55）+ 最近面试状态给出针对性复习建议，数值全部来自 Evidence；已有历史的会话不重复注入（Token 纪律）
- [ ] P3-3 已拆分：「低分技能 → focus」并入 P4-3，「描述强度约束」并入 P2-1（没有这两个消费点，画像就没有意义）

**验收**：能看到有数据来源的技能分列表；任一分数能点出其 Evidence 来源；「我 JVM 水平怎么样」返回真实 Evidence 驱动回答。

---

# 核心二：简历优化 —— 优先级 P2

> 目标：Resume（+可选 JD +画像）→ JSON-first Patch → Diff + Preview PDF → 确认 → 新版本 → 导出 PDF。不做整份重写，不覆盖原简历，不做 DOCX。
> 详细需求：`Career-Copilot-Resume-Optimization-Requirement.md`；方案设计：`career_copilot_resume_optimization_design.md`（JSON/版本/Typst）+ `career_copilot_resume_optimization_interaction_design.md`（Preview/自评审/Clarification）

- [x] **P2-0 Java 简历结构化地基**（一切的前置：Preview 质量上限 = 解析质量）
  - `resume_versions` 表（id/resumeId/version/sourceVersionId/optimizationType/targetJobId/contentJson/source/sourceCreatedAt）
  - ResumeParse：现有 Tika raw_text → LLM 结构化解析（StructuredOutputInvoker + prompts/*.st）→ Resume JSON（basicInfo/education/experience/projects/skills + **customSections 兜底**，解析 prompt 明确要求非标准段完整保留，防静默丢内容）
  - 解析失败/字段缺失标 NEED_USER_INFO，不猜测；解析结果需用户确认（确认端点 + 状态流转）
  - `get_resume_version` READ Tool（Python 子图取数路径）
  - 已落地：`V20260830` 迁移（含 confirmation_status 状态机 PENDING_CONFIRMATION/ACTIVE/NEED_USER_INFO）；`ResumeContentJson` schema（record 树）；`ResumeParseStructuredService`（不猜测原则 + 缺失字段汇总，姓名缺失=NEED_USER_INFO）；`ResumeVersionService`（V1 幂等创建/确认流转/ACTIVE 取数）；触发挂 AnalyzeStreamConsumer（评分分析成功后，解析失败不影响评分）；端点：versions 列表/详情/confirm（可携修正内容）；`get_resume_version` READ Tool（默认最新 ACTIVE，可按版本号定位）
  - 已验证：13 个新单测 + 全量 `:app:test` 通过；真实链路 reanalyze 触发分析→解析→V1 落库（真实 LLM）
- [x] **P2-1 Python 优化子图**（替换现 `stub.resume_optimization` 占位）
  - 流程：resolve_resume（复用 §26）→ determine_mode（GENERAL/TARGET_DIRECTION/JD_TARGETED）→ load_resume_version → load_jd/load_profile（桩位可空：JD 待 P2-5、画像已在 P3 就绪）→ context_check（信息不足才 Clarification，ChoiceBlock 确定性问询，只问影响方向的问题）→ generate_patch（JSON-path 结构化输出）→ validate_patch（代码校验）→ 提案落库 → ResumeOptimizationBlock + WAITING_USER
  - ResumePatch schema：`{id, type: REPLACE|ADD|DELETE|REORDER, path: "projects[0].bullets[0]", oldValue, newValue, reason, status}`；REORDER schema 保留、校验器一期直接拒绝
  - **真实性双保险（原 P2-5 融入此处）**：Prompt 层禁止虚构清单（量化数字/QPS/经历/奖项不得新增）+ 代码校验器（newValue 引入原文没有的量化数字/技术栈 → 拒绝或标 NEED_USER_INFO），单测覆盖需求文档 Case 4
  - 自评审循环：`review_resume` 节点留位，循环次数走配置（默认最小/关闭）；真实性由代码校验器兜底，不依赖 LLM review；简历长度代码可算，review 只负责匹配度/表达/冗余
  - HITL：提案持久化到 Java（含全部 patch 与状态，审计追溯）+ ACTION_SELECTED 新回合应用（P1-1/P1-4 已验证的无状态模式），**不用 LangGraph interrupt**
  - **P3 接入点**：generate_patches 注入 Skill Profile 描述强度约束（JVM 低分 → 避免「深入掌握」）
  - 已落地（含 P2-1a/c 的 Java 支撑）：`resume_optimization_proposals` 表（V20260831）+ ProposalService（创建/查询/PENDING→APPLIED·REJECTED 幂等流转）；Python `resume_optimization` 节点（resume_version → profile_query → generate_patch → patch_validator → save_proposal → ResumeOptimizationBlock + WAITING_USER）；`patch_validator`（REORDER 拒绝/path 白名单/oldValue 必填/newValue 新增数字拒绝——真实性代码兜底）；OPTIMIZE_RESUME action 接入子图；`apply_resume_patches` CONFIRM_WRITE Tool（第 12 个，JSON path 应用 + oldValue 一致性校验 + 新版本 AI_OPTIMIZE）+ APPLY_RESUME_PATCHES action → NavigationBlock；自评审循环未实现（一期默认最小，TodoList 决策如实记录——校验器已兜底真实性）
  - 已实测（真实 LLM 链路）：「优化简历」→ 5 条建议落库（oldValue 精确摘录原文）→ apply patch_1 → V2 生成（改写生效、其余 bullet 未动）→ 提案 APPLIED；重复应用被拒（幂等保护）
- [ ] **P2-2 Java Patch 应用 + 版本生成**（CONFIRM_WRITE）
  - `apply_resume_patches` Agent Tool（挂现有 /api/agent/tools/ 统一入口，同 create_interview 模式）：按 proposalId 校验提案存在 → 逐条按 JSON path 应用（oldValue 一致性校验）→ 生成新版本（source=AI_OPTIMIZE），原版本不动
  - Patch 提案持久化（proposal + patches + 状态 PENDING/ACCEPTED/REJECTED/APPLIED）
  - APPLY_RESUME_PATCHES action（payload 只带 proposalId + patchIds）→ 应用成功 → NavigationBlock 跳版本详情
- [ ] **P2-3 前端：解析确认 + Diff + Preview**
  - 解析结果确认/补录视图（解析错则全错，确认是必要门槛）
  - ResumeOptimizationBlock：Patch 卡片（oldValue/newValue/reason + [接受][忽略]）+ 全部操作 + [应用选中修改]（ACTION_SELECTED 回传）
  - **Preview PDF「勾选即重渲」**：勾选变化防抖调预览端点，`<iframe>` + blob URL 内嵌；桌面左 Diff 右预览分栏，移动端折叠；预览内容 = 已勾选 patch 的合成结果；附「排版不满意？原始上传件仍在你手里」退路说明
- [ ] **P2-4 Typst 导出**（只做 PDF；渲染归 Java，Python/前端不参与排版）
  - Spike：本机装 Typst + 真实解析 JSON 调通 classic-zh 中文模板（typst watch 迭代）
  - `TypstCompiler` 薄组件（ProcessBuilder + 超时 + stderr 入日志不透传 + `--root` 限定临时目录）；单测 stub 化，真实编译走集成测试 + golden 测试（fixture JSON 含 `* _ $` 等字符 → %PDF 头 + 体积断言）
  - 正式导出：版本表 content_json → 渲染 → RustFS → [导出 PDF] 按钮（详情页手动导出，不自动渲染）；字体 Noto Sans CJK 随 resources 打包；Dockerfile 拷贝 typst 二进制（~40MB）
  - Preview 端点：`POST /internal/agent/resume/preview`（原版 JSON + 已选 patch + templateId → 内存 apply → 渲染 → PDF 字节直返，**不入库不落存储**；Preview ≠ 正式版本）
- [ ] **P2-5 JD 接入**（点亮 JD_TARGETED + context_check 真实分支）
  - JD 作为第二类附件（`AttachmentRef.kind="job_description"`），复用 Tika 解析并单独存储（不动简历库 hash/去重语义）
  - Java：JD 上传/查询端点 + `get_job` Tool；会话绑定 `active_job_id`（对称 P1-3）
  - Python attachment_flow 扩展 JOB_DESCRIPTION 分支（ChoiceBlock：「JD 匹配 / 生成准备建议」）
  - 前端 Composer 附件类型标记（简历/JD 切换 tag）

**验收**：「按这份 JD 优化我的简历」→ 解析确认 → JSON-path Patch Diff + 勾选实时 PDF 预览 → 部分接受 → 应用 → 新版本可查（原版不变）→ 手动导出 PDF → 全程停留在 /copilot。无编造内容（Case 4 校验器兜底）。

**已知衔接**：优化新版本后，模拟面试一期仍用 resumeId 绑定的原始 resumeText（V1），版本选择后续再做，不阻塞 P2。

---

# 核心四：内嵌自适应模拟面试 —— 优先级 P4

> 目标：交互与引擎双升级——面试在 Copilot 内发起并内嵌执行（不再跳配置页），由固定题单升级为动态追问。
> 交互设计：`Career-Copilot-Inline-Interview-Design.md`；引擎设计：`Career Copilot 自适应模拟面试引擎设计文档.md`（Selection Before Generation）
> 边界（Inline §3/§23-26）：Agent 管发起/配置推荐/结果解释；Java Engine 管实时执行（状态机 + Turn Evaluation + Decision Policy）；React InterviewSessionBlock 管展示。**答题直连 Java API，不过 Agent Graph**。

- [ ] **P4-0 InterviewSessionBlock 前端基线**（Inline §11-14、§19）
  - 协议 `interview_session`（interviewId / status: READY|RUNNING|COMPLETED / direction / difficulty / mode / focus）加入受控 Block 白名单
  - 组件树：Header / Progress / CurrentQuestion / AnswerComposer / Timer / Result
  - 面试运行期隐藏或禁用普通 Composer（避免回答入口歧义）；ContextPanel 切换 Interview Context（进度 / 当前 Topic / 已覆盖·待覆盖 / 剩余时间，复用 P1-1 的面板）
  - 面试过程集中在 Block 内，不写入 Conversation Message；结束折叠为结果卡（综合分 + per-skill 分数 + [查看详细报告]）
  - 答题请求 React → Java Interview API 直连（语音面试暂沿用现有页面，Focus Mode 暂缓）
- [ ] **P4-1 Question Pool 结构化**
  - 每 Topic 预置 Main / Follow-up / Depth / Scenario 四类题目入库（题库生成走现有异步链路）
- [ ] **P4-2 轻量 Turn Evaluation**
  - 每轮回答 → 结构化输出（score / coverage / missingPoints / answerState / recommendedAction），低延迟模型；不复用整场报告
- [ ] **P4-3 Decision Policy（代码控边界，LLM 只判语义）**
  - 最大追问数 / Topic 时间预算 / 剩余总时长 / 难度上下限 / 话题覆盖率 / 出题去重
  - **P3 接入点（原 P3-3 拆入）**：面试推荐时低分技能作为 focus 传入 create_interview——升级 P1-4 已上线的 interview_proposal 节点，从「LLM 猜 focus」变「Evidence 驱动」
- [ ] **P4-4 动态行为集**
  - FOLLOW_UP / NEXT_QUESTION / NEXT_TOPIC / UPGRADE / DOWNGRADE / SKIP / END_INTERVIEW
  - Selection before Generation：优先 Pool 选题，无合适候选才 LLM 动态生题（fallback）
- [ ] **P4-5 Interview Report 增强 → Evidence**
  - 报告增加 per-skill 评分与关键 Turn 引用，落库后调用 Profile Aggregator 更新画像（闭环二右半段，依赖核心三）
- [ ] **P4-6 面试完成后回流 Copilot**（Inline §22）
  - 完成后 Agent 解释结果：强弱项对比（含画像变化 JVM 54→61 这类）+ 下一步 Action（[专项复习][再来一场][查看报告]）
  - 用 InterviewSessionBlock 替换 P1-4 的过渡跳转方案，会话创建后原地内嵌展示
- [ ] **P4-7 /interview-hub 重定位**（Inline §9，可选收尾）
  - 默认展示最近面试与分数，仅点「创建自定义面试」才展开完整配置；Agent 成为默认入口

**验收**（对齐 Inline §28 MVP 十项 + §30 四个 Case）：自然语言发起 → 推荐 → 确认创建 → 内嵌答题 → 同主题追问不重复、难度可升降 → 结果卡 → Evidence 更新画像 → Copilot 给出下一步建议；全程停留在 /copilot。

---

# Phase 5：三条产品闭环贯通（验收主线）—— 优先级 P5

- [ ] **闭环一（简历→JD→优化）**：Resume + JD → Gap 分析 → Patch → 确认 → 新版本
- [ ] **闭环二（画像→面试→新画像）**：低分技能 → 定向自适应面试 → Evidence → 分数回升可查
- [ ] **闭环三（Copilot 串全场）**：「根据我的简历和画像来场 JVM 面试」一条消息串联 读简历+读画像→create_interview→报告→画像更新→Copilot 返回下一步建议
- [ ] **Demo 主链**（Core-4 §7）：进 /copilot → 传简历 → 传 JD → 定向优化 → 确认 → 新版本 → 按 Profile 开面试 → 动态追问 → Report → Evidence → Profile 更新 → 回 Copilot 给建议
- [ ] **停止标准自查**（Core-4 §15 十项全绿后停止加功能）

---

# 暂缓 / 明确不做

**明确不做**（Core-4 §8 + 项目声明；除非四核心全部完成）：

```text
Multi-Agent / Agent Marketplace / 插件系统 / MCP Server·UI
自动投递 / Offer 管理 / 招聘爬虫 / 复杂 Job 推荐
复杂 Preparation Planner / 学习管理系统 / 复杂 Calendar
Voice Agent 重构（现有语音面试保留原样）
在线 Word 编辑器 / 大量简历模板 / 复杂 Dashboard
复杂 Observability / Agent Evaluation 平台
多用户隔离与用户数据分离（已明确移除，userId 恒为 default）
全聊天历史向量化 / 复杂 Episodic Memory / 自动 Memory Reflection
```

**暂缓**（有桩位、待依赖就绪）：

- [ ] COMPLEX_GOAL / Goal Execution Subgraph —— 四核心不含此项，保持现有占位回复；受限 ReAct 循环只在真正出现复杂多步 Goal 时随此进入
- [ ] Preparation 最小能力（简单计划/任务/进度）—— 仅当 Agent 下一步建议需要时再补，不建复杂 Planner
- [ ] 语音 Interview Focus Mode（Inline §27 的全屏语音交互 UI）—— 第一阶段文字面试优先，现有语音面试页保留
- [ ] Replay 型 Knowledge 复习卡片等衍生 —— 不做

**定位原则**（摘自 Core-4）：RAG 仅作为 Agent Tool 保留；Memory 一期只做 Conversation Memory + Skill Profile 两层；主 Graph 不默认 ReAct。

---

## 架构决策记录

| 决策 | 理由 |
|---|---|
| Java 是 System of Record，Python 只编排 | 业务规则/事务留 Java，Agent 服务无库 |
| 对话数据由 Java 持久化；blocks 用 JSON TEXT 列 | 与 Python MessageBlock 判别联合对齐，前端受控渲染 |
| Python 流式结束后一次性保存 | Java 无需 prepare/complete 两阶段 |
| 前端显式建会话，首条消息规则生成标题 | 不调 LLM |
| Agent 模型配置由 Java Provider 统一管理 + 请求期惰性重试 | 配置事实留 Java；解决启动顺序竞态 |
| 主 Graph 单程路由，不做无限 Agent Loop | §64；LLM 判语义、代码控边界 |
| 主 Graph 不默认 ReAct；受限循环只在未来 Goal 子图内 | Core-4 §12；当前四功能不依赖 |
| 短期记忆权威来源是 Java 历史；Checkpoint 持久化工作状态 | SoR 原则 + 跨轮恢复/HITL 地基 |
| Checkpoint 用独立 PG 库 `agent_checkpoint` 并剥离瞬时字段 | 流式 StreamPlan 含 AsyncIterator 不可序列化；避免污染业务库 |
| 简历内容经 `get_resume` Tool 按 maxChars 截断注入 | Token 纪律；与简历优化共用取数路径 |
| **不做多用户 / 用户数据分离** | 项目定位个人简历项目；userId 仅作字段预留 |
| 目标简历解析：附件 > 文件名指名 > 唯一锁定 > 默认最近 | 设计文档 §26；多份场景显式说明所选目标 |
| 自适应面试循环留在 Java Engine | Selection Before Generation；代码控制策略边界 |
| 面试发起 Agent 化、执行引擎化、结果回流 Copilot（Inline §3/§32） | Agent 管意图/推荐/解释，Java 管实时状态机；体验停留 /copilot 而架构解耦 |
| InterviewSessionBlock 答题直连 Java Interview API，不过 Agent Graph | 实时轮次不进 LLM 路由；低延迟、可控、可测试 |
| 面试过程集中在 InterviewSessionBlock，不写入 Conversation Message | 十题十答+追问会灌爆会话历史；Copilot 只保留结果 Artifact |
| **实施顺序 P3 → P2 → P4**（2026-08） | P3 基础是 P2/P4 共同地基且规模最小；先建画像使 P2 原生消费（描述强度）、P4 一次接入（低分→focus），无返工 |
| **简历优化 JSON-first**：Tika→LLM→Resume JSON，Patch 打 JSON path | before 文本精确匹配纯文本易因空白/换行失败；JSON path 精确无歧义、REORDER 可行、Diff/模板消费同一数据 |
| **customSections 兜底段**进 Resume Schema | 真实简历有证书/奖项/链接等非标准段；无处安放会被静默丢弃，违反「不虚构、不丢失」 |
| **解析结果需用户确认** | 解析错则后续 Patch/Preview/导出全错；确认是必要门槛 |
| **只做 PDF 导出，用 Typst（不做 XeLaTeX/DOCX）** | 单 ~40MB 二进制、中文内建、100ms 级编译支撑「勾选即重渲」；字符串按字面渲染免转义层与注入面；DOCX 后续走 Java docx4j/POI 不硬套 Typst |
| **HITL 用提案持久化 + ACTION_SELECTED，不用 LangGraph interrupt** | 提案本就必须落 Java（审计）；无状态回合是 P1-1/P1-4 已验证模式，不改流式协议、重启不丢 |
| **自评审循环预留节点、配置化、一期默认最小** | 每轮 2 次 LLM 调用 + 20-40s 等待；真实性靠代码校验器（确定性）而非 LLM review；有实测证据再开多轮 |
| **Preview PDF 勾选即重渲；Preview ≠ 正式版本** | Typst 性能撑得起实时预览；确认前零持久化（临时 JSON 直渲 PDF 字节） |
| **正式 PDF 手动导出** | 避免用户不导出时的浪费渲染；完成回执给导航，按钮放版本详情页 |
