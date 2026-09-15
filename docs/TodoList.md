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

⚠️ 已知问题：bash 会话被杀会连带杀掉后台子进程（建议启动用 `setsid`/`start_new_session` 脱离进程组）。`wait_java` 的 curl 无超时会挂起与「Agent 先于 Java 启动导致配置同步失败」两问题已修复（curl 加 `-m 2`；启动顺序改为 Java 就绪后再启动 Agent，2026-08-31）。

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
  - [x] 流式中断消息标记（Java message status「已停止」）—— 2026-09-14 完成，三态落库见下方「P1 待收口」
  - [x] 会话重命名 / 归档 UI（API 已有）—— 2026-09-14 完成，含归档视图与恢复入口
  - [x] Composer 移除 window.alert，改内联错误提示 —— 2026-09-14 完成

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
- [x] **P2-2 Java Patch 应用 + 版本生成**（CONFIRM_WRITE）
  - `apply_resume_patches` Agent Tool（挂现有 /api/agent/tools/ 统一入口，同 create_interview 模式）：按 proposalId 校验提案存在 → 逐条按 JSON path 应用（oldValue 一致性校验）→ 生成新版本（source=AI_OPTIMIZE），原版本不动
  - Patch 提案持久化（proposal + patches + 状态 PENDING/ACCEPTED/REJECTED/APPLIED）
  - APPLY_RESUME_PATCHES action（payload 只带 proposalId + patchIds）→ 应用成功 → NavigationBlock 跳版本详情
  - 已落地（提前并入 P2-1a/c，依赖顺序：Python 子图需要提案落库）：`resume_optimization_proposals` 表 + ProposalService + apply_resume_patches Tool（JSON path 白名单 + oldValue 一致性，漂移→PATCH_CONFLICT）+ APPLY_RESUME_PATCHES action → NavigationBlock
  - 与原计划的两处偏差：状态机三态 PENDING/APPLIED/REJECTED（无状态 HITL 下勾选发生在应用瞬间，不存在「已接受未应用」中间态，ACCEPTED 省略）；NavigationBlock 跳简历详情页（版本列表在其「简历版本」tab 内可见，不单开版本详情页）
  - 已实测：apply patch_1 → V2 生成（AI_OPTIMIZE，未勾选 bullet 原样）→ 提案 APPLIED → 重复应用被拒（幂等）
- [x] **P2-3 前端：解析确认 + Diff + Preview**
  - 解析结果确认/补录视图（解析错则全错，确认是必要门槛）
  - ResumeOptimizationBlock：Patch 卡片（oldValue/newValue/reason + [接受][忽略]）+ 全部操作 + [应用选中修改]（ACTION_SELECTED 回传）
  - **Preview PDF「勾选即重渲」**：勾选变化防抖调预览端点，`<iframe>` + blob URL 内嵌；桌面左 Diff 右预览分栏，移动端折叠；预览内容 = 已勾选 patch 的合成结果；附「排版不满意？原始上传件仍在你手里」退路说明
  - 已落地：`ResumeVersionPanel`（简历详情页第三个 tab：版本列表 + source/状态徽标 + 内容展开 + 解析确认卡片——missingFields 可读提示 + 确认按钮）；`ResumeOptimizationBlockView`（Diff 卡片：patch 类型徽标 + old 删除线/new 新增 Diff + reason + 勾选（默认全选/全不选切换）+ [应用勾选修改] → APPLY_RESUME_PATCHES action）；confirm 端点请求体包装修复（axios null body 触发 Content-Type 拒绝 + 空对象歧义误覆盖双重隐患）
  - 已验证：build + 前端 6 单测 + 后端全量通过；浏览器验证解析确认全流程（渲染→确认→ACTIVE）；优化后端链路三次真实落库 + 应用生成 V2；**Diff 卡片视觉验证待 LLM 网关恢复后补做**（验证期间网关持续间歇故障：意图分类/结构化输出多处 APIConnectionError 与 JSON 解析失败，均为外部依赖问题；为此把 generate_patch 的模型解析失败从整轮 error 修正为诚实回落「无建议」回复）
- [x] **P2-4 Typst 导出**（只做 PDF；渲染归 Java，Python/前端不参与排版）
  - Spike：本机装 Typst + 真实解析 JSON 调通 classic-zh 中文模板（typst watch 迭代）
  - `TypstCompiler` 薄组件（ProcessBuilder + 超时 + stderr 入日志不透传 + `--root` 限定临时目录）；单测 stub 化，真实编译走集成测试 + golden 测试（fixture JSON 含 `* _ $` 等字符 → %PDF 头 + 体积断言）
  - 正式导出：版本表 content_json → 渲染 → RustFS → [导出 PDF] 按钮（详情页手动导出，不自动渲染）；字体 Noto Sans CJK 随 resources 打包；Dockerfile 拷贝 typst 二进制（~40MB）
  - Preview 端点：`POST /internal/agent/resume/preview`（原版 JSON + 已选 patch + templateId → 内存 apply → 渲染 → PDF 字节直返，**不入库不落存储**；Preview ≠ 正式版本）
  - 已落地：`typst/resume-classic-zh.typ` 模板（防御性 `.at(key, default:"")` 取值，缺字段静默留空不炸编译；原生 list 做 bullets 修 grid 行高塌陷；空段 `.len() > 0` 判断）+ `TypstCompiler`（临时目录 + `--root` 限定 + 10s 超时 + stderr 只进日志；两参重载自动走 `TypstFontExtractor` 解包字体）+ `TypstFontExtractor`（classpath 字体懒解包到临时目录，无打包字体回退系统字体）+ `ResumePreviewService`（`TypstTemplateLoader` 模板白名单防任意 classpath 读取；apply 与正式应用共用 `applyPatchesToTree` 保证「预览内容 = 应用后内容」）+ Preview/导出端点 + 前端「生成预览/勾选防抖 600ms 重渲 iframe」与版本卡 [导出 PDF] 按钮
  - 字体决策：打包 Noto Sans CJK SC Regular 单字重（16MB），bold 由 typst synthetic embolden 合成（真实 Bold 多源下载均断链，视觉验证可接受）；模板字体回退链 `"Noto Sans CJK SC", "PingFang SC"`
  - 下载代理：RustFS bucket 非 public-read（既有简历直链同样 403，非本次引入），`GET /api/resume-exports/download?fileKey=` 后端流式代理（fileKey 限 `resume-exports/` 前缀），RustFS 仍留档可追溯
  - 已验证：golden 测试（特殊字符 `*Test_*` `$100 QPS` `C:\Users\test` `^[a-z]+$` 字面渲染 + %PDF 头）+ 9 个测试类全绿 + 本机编译 0.15-0.27s（勾选即重渲可行）+ 两页视觉验证（教育/项目/技能/自定义段渲染正常，空段不出现）+ Preview 端点实测（patch 应用后 24KB PDF）+ 导出实测（102880 字节 → RustFS → 代理下载回真 PDF，非法 fileKey 拒绝）
- [x] **P2-5 JD 接入**（点亮 JD_TARGETED + context_check 真实分支）
  - JD 作为第二类附件（`AttachmentRef.kind="job_description"`），复用 Tika 解析并单独存储（不动简历库 hash/去重语义）
  - Java：JD 上传/查询端点 + `get_job` Tool；会话绑定 `active_job_id`（对称 P1-3）
  - Python attachment_flow 扩展 JOB_DESCRIPTION 分支（ChoiceBlock：「JD 匹配 / 生成准备建议」）
  - 前端 Composer 附件类型标记（简历/JD 切换 tag）
  - 已落地：`job` 模块（job_descriptions 表 V20260901 + 上传 Tika 解析/文本创建/查询/删除，无去重语义——JD 迭代频繁按条目管理）+ `GET_JOB` READ Tool（第 13 个）+ 会话 `active_job_id`（PUT active-job + context/detail DTO 透出）；Python `AttachmentRef.kind` 扩展 + `attachment_flow` JD 分支（确认块：优化/匹配/面试三选项，自动 `bind_active_job` 失败不阻断）+ `resolve_context` 识别 JD 附件与会话绑定 + 优化子图 `active_job_id` 存在时注入 JD 全文（4000 字符截断）点亮 JD_TARGETED（真实性铁律仍兜底：禁止编造经历凑匹配度）+ `get_job` client；前端 Composer 附件类型切换 tag（简历/JD，上传前可改）+ JD 上传走 `/api/jobs/upload` + ContextPanel「JD 资源将在 P2-1 接入」占位替换为真实绑定 JD（Conversation Memory 优先，附件名回落）+ CopilotPage JD 上传分支
  - 删除不级联清会话绑定（与简历删除行为对称）：悬挂 active_job_id 由取数失败兜底

**验收**：「按这份 JD 优化我的简历」→ 解析确认 → JSON-path Patch Diff + 勾选实时 PDF 预览 → 部分接受 → 应用 → 新版本可查（原版不变）→ 手动导出 PDF → 全程停留在 /copilot。无编造内容（Case 4 校验器兜底）。

**已知衔接**：优化新版本后，模拟面试一期仍用 resumeId 绑定的原始 resumeText（V1），版本选择后续再做，不阻塞 P2。

---

# 核心四：内嵌自适应模拟面试 —— 优先级 P4

> 目标：交互与引擎双升级——面试在 Copilot 内发起并内嵌执行（不再跳配置页），由固定题单升级为动态追问。
> 交互设计：`Career-Copilot-Inline-Interview-Design.md`；引擎设计：`Career Copilot 自适应模拟面试引擎设计文档.md`（Selection Before Generation）
> 边界（Inline §3/§23-26）：Agent 管发起/配置推荐/结果解释；Java Engine 管实时执行（状态机 + Turn Evaluation + Decision Policy）；React InterviewSessionBlock 管展示。**答题直连 Java API，不过 Agent Graph**。
> 现状基线（2026-09-02）：题单是创建时 LLM 一次性生成的**线性 list**（questionsJson 存 session），`submitAnswer` 同步存答案 + index+1 固定顺序返回下一题；评估是**整场异步**（EvaluateStream → 报告 → Evidence → 画像，P4-5 后半段已通）。**无 Question Pool / Turn Evaluation / Decision Policy / difficulty 数值 / expectedPoints / 持久化 question id**，P4 是全新引擎。
> **P4 一期边界（2026-09-02 决策）**：Java 引擎改造成逐题决策；题库=创建时预生成 JSON 落库 + Selection；轻量评估同步在 submitAnswer 内做（只服务决策）；动态 LLM 生题 / Lookahead / 复杂时间分配 / 全量画像实时更新 二期再做。执行顺序改为 **Java 引擎先行（P4-1/2/3）→ 前端内嵌（P4-0）→ 端到端回接（P4-4/5/6）**。

## P4 实施顺序（2026-09 重排）

> 现有 TodoList P4-0..P4-7 原顺序把前端 UI 放在引擎改造之前，且未区分一期/二期。重排为「引擎先行、端到端最后」，理由：① P4-0 的 InterviewSessionBlock 需要建立在新的逐题 API 上，先改造 Java 才能定协议；② 现 /interview 独立页可在引擎改造期间继续做回归（不破坏既有入口）；③ 避免在旧题单协议上先做 UI 再返工。
> 一期 = P4-1/P4-2/P4-3/P4-0/P4-6a/P4-4a；二期 = P4-4b/P4-5/P4-6b/P4-7。

### 一期 · Java 引擎改造

- [ ] **P4-1 题库结构化（Question Graph 基础）**
  - 扩展 `InterviewQuestionDTO`：`difficulty`（数值 1-5）、`expectedPoints: List<String>`、`followUpType`（一期枚举 DEPTH/SCENARIO/WHY/CLARIFICATION，先支持前两者）
  - 题目仍创建时一次性生成：生成 prompt 要求按 direction categories 输出 Main 题 + 每题候选追问（挂 `expectedPoints`）；题库以 JSON 形式存 session（沿用 questionsJson，不加新表）
  - 追问与主问题**不再线性合并**，改为图结构（主问题 + 独立候选追问 list，主问题引用 candidate follow-ups）
  - 回归：现 /interview 独立页仍按主问题顺序作答可用（引擎未切换前顺序语义不变）
- [x] **P4-2 轻量 Turn Evaluation（同步、低延迟）**
  - `TurnEvaluationService`：回答 → 同步结构化评估（score/answerState/covered·missingPoints/recommendedFocus）；`coverage` 由要点列表**代码计算**（模型不输出浮点）；`score` 夹取 0-100、状态与分数互相补齐自洽；prompt 只含当前题 + 期望要点 + 回答（token 最小），不落库、只服务决策（P4-3 决策引擎接入）
  - NO_ANSWER 短路词表（"不会/不知道/跳过/i don't know"…）免 LLM；LLM 失败回落中性 PARTIAL 不阻塞答题
  - 新增 `TurnEvaluation` record + `turn-evaluation-system/user.st` + `TurnEvaluationProperties`；7 个单测（短路/归一/越界夹取/coverage/失败回落/无要点中性）+ 全量 `:app:test` 通过
- [x] **P4-3 Decision Policy + Next Question Selection（代码控边界，一期 Selection）**
  - `AdaptiveInterviewPolicy`：主问题 + 内嵌追问池线性题单上的「Selection Before Generation」——答好进追问组（组内顺序消费，天然去重 ≤ 出题上限）、答不上/答错（NO_ANSWER/WRONG/WEAK）中断追问组切下一主问题、追问池耗尽切主问题、主问题全答完 → 面试结束
  - 会话 `adaptive` 标记（entity/迁移 V20260903/cache/DTO/CreateInterviewRequest）：Agent `create_interview` 默认开启（adaptive=true），/interview-hub 与知识库面试保持原顺序行为
  - `submitAnswer` 自适应分支：同步 `TurnEvaluationService.evaluateTurn`（失败不阻塞）→ policy 选题；`nextQuestion` = 决策结果；全部主问题答完 → hasNextQuestion=false 进现有整场异步评估闭环（未答的未选追问不参与，答案索引对齐）
  - 一期明确不做：UPGRADE/DOWNGRADE 动态改写难度、动态 LLM 生题 fallback（题库无候选即结束/换题，二期随 P4-4b 补）
  - 已验证：policy 7 单测（追问进入/中断/耗尽/末题结束/恢复缺评估）+ 会话接线 3 单测（自适应跳过追问/进入追问/非自适应顺序不变）+ 全量 `:app:test` 通过

### 一期 · 前端内嵌 + Copilot 接回

- [x] **P4-0 InterviewSessionBlock（前端自管理状态机，复用 interviewApi 直连 Java）**
  - 受控 block `interview_session`（sessionId/skillId/difficulty/mode/focus/questionCount/directionName）进白名单（Python MessageBlock union + 前端 AgentBlock）
  - `execute_action._create_interview_action` 成功 → 产出 InterviewSessionBlock 原地内嵌（替换 P1-4 的 NavigationBlock 跳转方案；该 nav 仍被简历补丁等其他 action 使用，未删）
  - `InterviewSessionBlockView`：块挂载 getSession → 展示当前题（追问徽标）+ 文本输入（⌘/Ctrl+Enter 提交）→ submitAnswer（Java 决策引擎已返回下一题/结束）→ 结束后 3s 轮询 getSession 到 EVALUATED → getReport → 折叠结果卡（综合分 + per-skill）
  - **面试轮次不进 Conversation Message**：每轮只走 Java API，块内存活；刷新历史仅重放展示参数（重进会话由块重新拉取，Java 是权威）
  - 面试运行期隐藏普通 Composer（CopilotPage 检测 interview_session 块 → 提示「请在面试卡片内回答」）
  - 答题直连 Java `/api/interview/...`（不过 Agent Graph）；创建即 adaptive=true（P4-3 决策引擎生效）
  - ⚠️ 2026-09-03 Interview Mode 重构后 **已废弃删除**：`InterviewSessionBlockView` 大卡移除，`interview_session` 改为「进入 Interview Mode 的信号块」（见下方「Interview Mode 重构」）
- [x] **P4-6a 面试完成后回流 Copilot（一期最小）**
  - 结果卡 [让 Copilot 复盘这次面试] → REVIEW_INTERVIEW action → execute_action 读 Java `/details`（强项/弱项/逐题得分，**真实数据不得编造**）→ answerer 流式复盘 + [再来一场] Choice + [查看面试记录] Action
  - AgentTool 侧 `get_interview_detail` client（直连详情端点，非 Tool）+ `summarize_interview_detail` 裁剪（Token 纪律）
  - 差分画像话术（"JVM 54→61"）待 P4-6b（需报告/画像差分数据接入）
  - P1-4 NavigationBlock 保留为 /interview-hub 手动入口补充（未删）
  - 已验证：graph 2 例（详情命中 + 复盘流 + 动作；缺 sessionId 拒绝）+ agent-service 75 pytest 通过 + 前端 build

### 二期

- [ ] **P4-4b 动态行为集扩展**：Follow-up 类型扩展（SCENARIO/WHY 等）+ 候选池无合适题时 LLM 动态生题（fallback，结构化输出 + 写回池）
- [ ] **P4-5 报告增强（P4 侧）**：逐题评估可携带 difficulty/expectedPoints → 报告生成时若已有逐题数据可补充 per-skill 引用（Profile 聚合链路 P3 已通，闭环二右半段依赖核心三）
- [ ] **P4-6b 画像联动增强**：面试后 Copilot 按新画像给下一步建议（"JVM 54→61"）——**差分数据已就绪**（`GET /api/interview/sessions/{id}/profile-impact`，P3 待收口产出），仅剩接入与话术
- [ ] **P4-7 /interview-hub 重定位（可选收尾）**：默认最近面试列表，仅点「创建自定义面试」展开完整配置

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
| **P4 答题链路改造成逐题决策引擎**（一期） | 题单固定顺序无法动态追问/升降难度，是「AI 题库」而非自适应面试；一期先做 FOLLOW_UP/NEXT_QUESTION/NEXT_TOPIC + 追问上限 + 难度微调，Selection before Generation |
| **P4 题库=创建时预生成 JSON 落库，不加独立 Question 表**（一期） | 对齐现有「创建即生成」无新异步链路；池 JSON 存 session 够一期决策用；Question 独立表/Lookahead/跨会话题库 二期按需引入 |
| **P4 轻量 Turn Evaluation 同步在 submitAnswer 内做** | 轻量评估只服务下一题决策，1-3s 内返回可接受；不落库、不复用整场报告；避免再引入异步+轮询的复杂度 |
| **P4 一期不实时 LLM 动态生题；池无候选→NEXT_TOPIC/END** | 实时生成拉高延迟且违反 Selection before Generation；动态生成作为二期 fallback（结构化 + 写回池） |
| **P4-0 复用 InterviewPage 组件逻辑而非重写** | 答题交互已直连 Java API 且验证过；抽成块内子组件，避免 UI 双份实现，也保留 /interview 独立手动入口 |
| **P4 实施顺序：Java 引擎先行 → 前端内嵌 → 端到端回接**（2026-09） | P4-0 需建立在新逐题 API 上；现 /interview 页可作引擎改造期回归；避免在旧题单协议上先做 UI 再返工 |
| **停止生成落库改为「脱手任务」+ 消息三态 status**（2026-09-14） | 落库原在 SSE 生成器 `finally` 中且伴随 `yield`：客户端 abort 时 `finally` 的 yield 触发 `RuntimeError: async generator ignored GeneratorExit`，后续 `await` 落库被整体跳过，整轮对话丢失。三态 status 替代建表后从未被写入的 `completed` 布尔，使「已停止」刷新后可还原 |
| **未完成的助手消息允许空内容** | 用户停止/生成失败时可能尚未产出任何内容；若仍要求内容非空，只能是丢掉整条助手消息，刷新后无法区分「被停止」与「没人回答」。已完成消息与用户消息仍禁止空白 |
| **脱手落库独立建 BackendClient，不复用请求作用域** | 请求结束时 `get_backend_client` 会 aclose 连接池，而落库任务生命周期长于请求。单列为 `_new_persist_client()` 兼作测试接缝（模块内直建会绕过 `dependency_overrides`，测试会打真实后端） |
| **加载失败必须与空态显式区分**（2026-09-14） | 此前拉会话详情失败只 `console.error`、`messages` 保持为空，界面渲染出新会话首屏，用户会以为历史被清空；会话列表失败会显示「还没有对话」；删除失败更是毫无反馈。三处统一改为受控错误态 + 重试入口 |
| **「重新发送」语义 = 新的一轮，不新增 regenerate 协议** | Java `saveMessages` 会一并落一条用户消息，界面与历史必须与持久化一致（不做无痕重放）。若要「原地续写 / 重新生成」，需在 `ChatRequest` 增加 regenerate 语义以跳过 USER 落库——协议变更成本高于本轮收益，留作后续独立改动 |
| **代码高亮改 prism-light + 25 种白名单语言** | 默认 prism 构建含 300 种语言语法，异步 chunk 达 697.69 kB；未注册语言直接按纯文本渲染，比对未知语言依赖高亮器兜底更确定，也避免把整包语法拉回来 |
| **会话「删除」与「归档」语义分离**（2026-09-14） | 删除 = 硬删除且不可恢复；归档 = 软隐藏、保留记录、可恢复。数据层早有 `ConversationStatus{ACTIVE,ARCHIVED}` 且列表只查 ACTIVE，但既无归档入口也无恢复入口（只进不出的黑洞），故补齐归档/恢复端点与归档视图，并把删除确认文案改为引导改用归档 |
| **会话列表用 `?status=` 过滤而非两个端点**（2026-09-14） | 复用同一 DTO 与查询、语义直白；代价是前端路由桩必须与查询参数无关（本次已因此踩到 E2E 假失败） |
| **CI backend job 挂真实 Postgres，不挂 Redis**（2026-09-14） | 迁移链此前在 CI 里零验证；挂 Postgres 后每次 CI 都在空库上跑完整迁移且集成用例真跑。Redis 经实测不可用时上下文仍能启动（消费者仅告警），无需为其增加 CI 时长 |
| **题单合并必须走 `withIndex` 而非 `create(...)`**（2026-09-15） | `create(...)` 是旧的顺序题单工厂，只带 7 个字段，用它重建会把 difficulty / followUpType / expectedPoints 静默丢掉，自适应决策与轻量评估随即退化成「无难度的固定题单」。`withIndex` 只改索引、原样保留全部字段，并同步偏移 `parentQuestionIndex`（否则追问会挂错主问题） |
| **面试消息流的权威判据是「该题是否有作答记录」**（2026-09-15） | 自适应会话的题库含**候选择问**，被策略跳过的题仍留在 `questions` 数组里。按 `currentQuestionIndex` 遍历题库会把从未问过的题渲染成「已问过」。恢复规则抽成 `utils/interviewTurns.ts` 纯函数并单测固化（E2E 双保险） |
| **自适应进度分母 = 主问题数，不是 `totalQuestions`**（2026-09-15） | `totalQuestions` 是题库总数（含候选追问）。自适应会话按作答质量跳过一部分，用它做分母会出现「只答了 3 题却显示第 10 / 12 题」。故自适应显示「已答 N 题 · 主题 x/y」，非自适应顺序会话仍用「第 x / y 题」（此时题库总数即真实总题数） |
| **已主动退出的面试会话不再被旧信号块拉回**（2026-09-15） | 退出时追加「面试完成摘要」会让 messages 变化，自动进入 Interview Mode 的 effect 会重新扫描到那个旧 `interview_session` 块。用 `closedInterviewSessionsRef` 记录已退出的 session 来阻断 |
| **AI 面试复盘走「指定 session」而非「最近一场」**（2026-09-15） | 面试记录页的「让 Copilot 复盘这场面试」带 `reviewSessionId` 跳 `/copilot`，由该页发起 `REVIEW_INTERVIEW` action（payload `{sessionId}`），实现对指定场次的逐题复盘。为此 `CopilotOutletContext` 增加 `conversationsLoaded`，区分「还没加载」与「加载完确实为空」，避免带 action 跳转时误建新会话 |
| **集成测试用内存 store 代替 Redis**（2026-09-15） | 「缓存失效 → 按 DB 重建」是恢复路径的核心。若用「依次返回两个 stub」的写法，等于把预期结果直接塞回被测代码；让 `saveSession` 真写入、`getSession` 真读出，答案回填逻辑才真正被验证 |
| **简历来源以「声明型证据」入画像，不参与聚合**（2026-09-15） | 简历侧没有逐技能分（结构化 skills 只有技能名，分析只有四个维度分）。给未验证的技能编分会破坏 Core-4「画像分必须能由证据逐条还原」；声明证据（score=NULL）只表达「简历列过、还没考过」，恰好是 focus 选择与画像展示最需要的信息 |
| **简历声明的同步时机 = 用户确认结构化简历之后，且整体替换**（2026-09-15） | 解析结果未经确认不算权威；增量写入会在重新解析/纠正后留下永远清不掉的幽灵技能，故按 resumeId 整体替换 |
| **focus 必须真正影响出题，而不是展示**（2026-09-15） | 此前 focus 从未进 Java（纯装饰）——用户会看到「重点考察 JVM」然后照样被问 MySQL。透传后在 `focusOn` 按 key/label 大小写不敏感裁剪分类；**未命中任何分类时返回原方向全量分类**：空分类会让出题 prompt 失去依据，比没聚焦严重得多 |
| **focus 白名单遵循「LLM 判语义、代码控边界」**（2026-09-15） | 技能名到分类的映射是语义问题（JVM→Java），交给 LLM；但只有确实存在于该方向 categories 的分类才下发（key 精确 → label 精确 → 双向子串容忍 "SQL"→"MySQL"），全被拦掉时用画像的确定性候选兜底（简历已列未考 > 低分 <60，阈值与前端色阶一致） |
| **画像差分零新增存储，before/after 都由证据重算**（2026-09-15） | before = 排除本场证据的均值、after = 含本场证据的均值，与聚合器同口径；证据表已带来源/分数/时间，差分与追溯都能从它还原。本场首次考到的技能 before=null 且 delta=0——不报一个虚高的「涨幅」 |

---

# Interview Mode 重构（2026-09-03，交互模型升级，覆盖 P4-0 卡片方案）

> 决策来源：`Career_Copilot_模拟面试_Interview_Mode_重构说明.md`。
> 核心：模拟面试不是一张嵌入聊天的 Card，而是 Copilot 的一种**页面模式**——中间主交互区切换为 Interview Mode，
> 题目/回答以普通消息流渲染，输入复用底部 Composer，顶部轻量状态栏（题号/计时/结束面试）。

- [x] **结构调整**
  - `CopilotPage` 引入 `mode: chat | interview`（`InterviewModeState`）；`interview_session` 块 = 进入 Interview Mode 的信号（不再渲染 Card，删除 InterviewSessionBlockView）
  - `InterviewWorkspace`：顶部轻量状态栏 + 消息流（面试官题/用户答，复用气泡视觉）+ 底部答题输入（Enter 提交）+ 结束面试/提前交卷
  - `InterviewConfigPanel`：提案块下内联配置面板（方向/难度/题量/focus 多选，skillApi 数据），**点击调整配置不再发送聊天消息**；[按自定义配置] 与 [按推荐] 收敛同一 InterviewConfig → CREATE_INTERVIEW
- [x] **领域隔离（A）**：面试每轮 Q/A 不写普通 conversation；Java InterviewSession 为权威持久化；完成后写一条轻量「面试完成摘要」artifact（本地气泡 + POST /conversations/{id}/messages）供历史回放/复盘
- [x] **可恢复（B）**：切会话/刷新不结束面试；Java 会话保留，重新进入按 sessionId 恢复 Interview Mode（顶栏 status running/evaluating/completed）
- [x] **轻量结果（C）**：完成后留在 Interview Mode 展示综合分/维度分摘要 + [完成并返回对话]；不再用大型结果 Card
- [x] **调整配置三态**：① 内联面板手动改 ② Composer 自然语言「难度高一点，多问 JVM」→ interview_proposal 结合本条消息重新推荐（原「重新推荐」Choice 移除）

---

# 代码实现审计与产品优化清单（2026-09-14）

> 本节基于 `docs/TodoList.md` 与当前代码、测试结果逐项核对后追加。原有勾选记录保留，用于记录实施历史；本节中的“完成”以当前代码能够形成稳定、可验证的端到端行为为准。

## 一、当前完成情况

Todo 原始统计为 **32 / 48 项勾选（约 66.7%）**。由于列表中包含重复里程碑、阶段性实现和暂缓项，该比例不等同于产品完成度。

当前项目定位：**核心技术骨架基本齐备，可进入集成测试和产品打磨阶段，但还未达到完整产品验收标准。**

> **工程基线（2026-09-14 更新）**：P6-0 已完成 —— Java / Python / Frontend 三端质量门禁全部转绿，并已固化到 CI（含原 CI 从未触发的分支配置修复）。后续功能累计不得再引入失败基线。详见「四、验证基线问题」与「五、P6-0」。
> **P1 待收口进度**：**4 项全部完成**（Composer 内联错误 / 停止生成三态落库 / 加载与错误态 + 重发入口 / 会话重命名与归档恢复）。另完成一项主动性能优化（P6-5）。
> **P4 待修正进度**：**6 项全部完成**（合并元数据 / 按实际已提问轮次恢复 / 退出不被拉回 / 进度分母 / REVIEW_INTERVIEW 真实入口 / 四项集成测试）。P4 二期四项按原计划未动。
> **P3 待收口进度**：**4 项全部完成**（简历声明型证据 / 提案读画像选 focus 并真正生效 / 结果卡画像变化 / 场次追溯入口）。三个设计决定（声明型证据不参与聚合、focus 透传到 Java、结果卡展示）已与 boss 确认。

| 模块 | 代码审计状态 | 当前判断 |
|---|---|---|
| P1 Copilot 主链路 | 基本完成 | Agent、SSE、会话持久化和 Checkpoint 已实现；异常反馈、停止状态和会话管理仍需收口 |
| P3 能力画像 | 基本完成 | Evidence、聚合、查询、展示已实现；简历声明证据、提案 focus 画像化、画像变化与追溯已补齐（2026-09-15）；「描述强度约束」并入 P2-1 |
| P2 简历优化 | 部分完成 | 结构化版本、Proposal、Patch、预览和导出已实现；解析纠错、模式语义和真实性校验仍不完整 |
| P4 自适应面试 | 基本完成 | Turn Evaluation、决策策略、报告回流已实现；题目元数据、恢复、退出、复盘四处断点已修（2026-09-15）；二期四项待做 |
| P5 三条产品闭环 | 未完成 | 尚无一条达到稳定端到端验收及自动化回归标准 |

## 二、已确认实现

### P1 Copilot

- [x] LangGraph 主图、意图路由及业务节点已接通
- [x] SSE token、Tool、Run 事件及流结束后的消息持久化已接通
- [x] Java Conversation API、上下文加载和消息保存已实现
- [x] PostgreSQL Checkpoint 已接入 Agent 生命周期
- [x] 前端 Copilot Workspace、结构化 Block 和 Action 路由已实现

### P3 能力画像

- [x] Skill Profile、Skill Evidence 数据结构及迁移已实现
- [x] 面试 Evidence 提取、幂等聚合、删除后重算已实现
- [x] Profile 查询 Tool、Agent 节点和前端画像面板已实现
- [x] 面试报告完成后可触发 Evidence 和 Profile 更新

### P2 简历优化

- [x] Resume Version、Optimization Proposal 和目标岗位字段迁移已建立
- [x] Agent 可读取 Resume、Profile 和 JD 上下文生成 Patch Proposal
- [x] Java 可校验并应用 Patch，生成新版本
- [x] Typst PDF 预览和正式导出链路已建立
- [x] 前端已提供 Patch 勾选、Diff、Preview 和确认应用交互

### P4 自适应面试

- [x] 问题 DTO 已具备 difficulty、expectedPoints、followUpType 字段
- [x] 轻量 Turn Evaluation 和结果归一化已实现
- [x] FOLLOW_UP、NEXT_QUESTION、NEXT_TOPIC 等代码边界策略已实现
- [x] submitAnswer 已串联评估、选题、完成和异步报告
- [x] Interview Mode、配置面板和完成摘要的基础 UI 已实现

## 三、Todo 标记与实际实现的差异

### P1 待收口

- [x] 用受控错误状态和重试入口替换 Composer 中的 `window.alert`（2026-09-14）
  - `Composer` 附件类型不合法时改为输入框内联红条提示（带文件名、可关闭），发送/重新选择文件时自动清除；顺带修掉 file input 未重置 `value` 导致「连续选同一个文件不再触发 change」的问题
  - 说明：`window.alert` 在本仓库其余模块（语音面试、面试记录、日程、简历详情）仍存在，不属 P1 范围，未一并改动
- [x] 停止生成时由 Java 持久化 `STOPPED/CANCELLED` 状态，避免仅修改前端本地消息（2026-09-14）
  - **实际缺陷比原描述严重**：落库写在 SSE 生成器的 `finally` 中且伴随 `yield`，客户端 abort 会让 `finally` 抛 `RuntimeError: async generator ignored GeneratorExit`，后面的 `await` 落库被整体跳过 —— 即按「停止生成」后**本轮根本没落库**，刷新后用户的问题和已产出的回答一起消失。
  - Java：`V20260914` 迁移把 `agent_messages.completed`（建表后从未被写入/读取的死字段）替换为三态 `status`（COMPLETED/STOPPED/FAILED，含 CHECK 约束与历史回填）；`AgentMessageDTO` 与 `SaveMessagesRequest.MessagePayload` 透出 status；未完成的助手消息允许空内容（停止/失败时可能尚未产出内容，但「本轮未完成」需要留痕），已完成仍禁止空白。
  - Python：落库移出生成器 `finally`（`done` 改在正常/异常分支发出），改为调度**脱手任务**——独立 `BackendClient`（不复用请求作用域连接池，后者在请求结束时被 aclose，且模块级直建会绕过 dependency_overrides，故留 `_new_persist_client` 作为测试接缝）；终态默认 STOPPED，只有跑到流末尾才改写 COMPLETED，abort 天然落回「已停止」；`lifespan` 关闭时 `flush_pending_persists()` 收敛在途写入，避免进程退出丢内容。
  - 前端：`MessageStatus` 增加 `stopped`；`cancel()` 不再把中断标成 `done`；历史回放按 Java status 还原 done/stopped/error（缺省不误判为异常）；停止态用中性提示，与 error 红条区分。
  - 已验证：Java 对话服务 20 个单测（含 7 个终态用例）；Python 79 个测试通过，其中 `test_chat_stream_aborted_turn_persists_as_stopped` 直接驱动 `StreamingResponse.body_iterator` 后 `aclose()` 触发 GeneratorExit（TestClient 的 `response.close()` 不会真正中断服务端生成器，服务端会跑完，故无法用它复现；该用例在修复前必然失败）；前端新增 `test:copilot-turn-status` 4 个映射单测 + build + E2E 通过。
- [x] 补齐会话重命名、归档/恢复能力；明确"删除"和"归档"的产品语义（2026-09-14）
  - **侦察结论：一半已通电、一半是黑洞**。`PUT /title`、`PUT /pin`、`DELETE` 端点早已存在，前端 `conversationApi.rename/togglePin` 也写好了但**从未被调用**；实体与 DB 早有 `ConversationStatus{ACTIVE,ARCHIVED}`，`listConversations()` 也已经在过滤 ACTIVE —— 也就是说「归档」在数据层已经生效，但既没有归档入口，也没有查看/恢复入口，**已归档会话只进不出**。
  - Java：会话列表加 `?status=ACTIVE|ARCHIVED`（默认 ACTIVE）过滤；新增 `PUT /{id}/archive` 与 `PUT /{id}/restore`；仓储方法由写死 `status = 'ACTIVE'` 改为状态参数；新增错误码 `CONVERSATION_STATUS_INVALID(13003)`。
  - 前端：会话条目由「只有一个删除图标」扩成 置顶 / 重命名 / 归档 / 删除（归档视图为 恢复 / 删除）；重命名为就地编辑（Enter 保存、Esc 取消、失焦保存，空标题不提交）；侧栏底部加「已归档」入口，进入后顶部变为返回入口。
  - **语义定案**：删除 = 硬删除且不可恢复；归档 = 软隐藏、保留记录、可恢复。删除确认文案改为「删除后不可恢复。若只是想从列表收起、保留记录，请改用『归档』」。归档当前打开的会话时一并取消选中，避免它从列表消失却仍处于打开状态。
  - 已验证：Java 会话服务 27 个单测（含 5 个状态生命周期用例）；真实链路（bootRun 8081 + 真库）——建会话 → 重命名/置顶 → 归档后从活跃列表消失且出现在归档列表 → 恢复后回到活跃列表并从归档移除 → 非法 status 返回 `13003`；新增 `e2e/copilot-sessions.spec.ts` 2 条（就地重命名、归档→查看→恢复完整来回）
  - 踩坑：新增 `?status=` 查询参数后，E2E 里写死的路径 glob 桩不再匹配，请求落到真实后端而表现为「列表加载失败」——**是 E2E 抓到的真实回归**，桩已改为与查询参数无关的正则
- [x] 补充 Copilot 主链路的加载、空状态、断网、SSE 中断和 Tool 失败体验（2026-09-14）
  - **加载失败不再伪装成空态**：`CopilotPage` 拉会话详情失败此前只 `console.error`，`messages` 保持空数组 → 界面渲染出「今天想为求职推进哪一步？」的新会话首屏，用户会以为历史被清空。现在显式记录失败态，展示错误面板 + [重试]（重试走递增 reloadKey 重新触发加载 effect）
  - **会话列表失败同理**：`Layout` 记录 `conversationError` 并传入 `SessionList`；列表已有内容时作顶部提示（如删除失败——此前删除失败也只有 console，界面毫无反馈），列表为空时提示本身就是主体，两种情况都不退化成「还没有对话」
  - **统一重发入口**：`CopilotMessage.retry` 保存本轮原始请求（message / userContent / attachments / action），失败与停止态都渲染 [重新发送]，带附件与 Action 提交的轮次无需用户重新输入。附件在「上传失败」时才在载荷里携带原始 `File`（那时资源 id 尚不存在），上传成功的轮次用已有 id 重发不重复上传
  - **语义说明**：重发是「新的一轮」——后端会一并落一条用户消息，界面与历史里会再出现一次该提问，与持久化结果保持一致（不做无痕重放）。若要「原地续写 / 重新生成」，需要后端支持 regenerate 语义（跳过 USER 落库），属后续独立改动
  - 残留：断网未单独区分文案（走通用 error + 重发），也没有自动重连；SSE 中断靠 `stopped` 终态体现
  - 已验证：`e2e/copilot-states.spec.ts` 2 条用例（详情加载失败→错误+重试→恢复后 STOPPED 消息渲染为「已停止生成」；列表加载失败→不显示「还没有对话」→重试恢复）
  - 踩坑记录：E2E 打桩失败窗口不能用「调用序号」（第 1 次失败、第 2 次成功），应用自身会重新拉取列表，序号式桩会被第二次成功响应覆盖而测不出错误态；须用开关变量控制失败窗口

### P3 待收口

> **进度（2026-09-15）**：四项全部完成，P3 待收口清零。三个设计决定已与 boss 确认：简历来源走「声明型证据」、focus 透传到 Java 真正生效、画像变化展示在面试结果卡。

- [x] 将 Resume Analysis 等可信来源接入 Evidence；当前主要证据仍是 `INTERVIEW_TURN`
  - **关键侦察**：简历侧没有逐技能分可用——结构化简历的 `skills[]` 只有 category/content（技能名、无分），简历分析只有四个维度分（内容/结构/技能匹配/表达/项目），都不是技能分
  - 设计（boss 确认）：**声明型证据**——`V20260915` 让 `skill_evidence.score` 可空，RESUME 来源以 `score=NULL` 写入，表达「简历列过这项技能」；聚合器只取有分证据算均值，画像分仍完全由可量化的面试证据决定（忠于 Core-4「分必须能由证据逐条还原」，不给未验证的技能编分）
  - 技能名归一化：`ResumeSkillNormalizer` 确定性拆分（分隔符/连接词/修饰词/尾部泛化词/截断上限），不引入 LLM——技能名会进 focus 与画像展示，不应由模型即席生成；刻意不按空格拆（Spring Boot 是一个技能）、不做同义词归并（交给提案节点的语义判断）
  - 同步时机：**用户确认结构化简历**之后（`ResumeVersionService.confirmVersion`，未确认的解析不算权威），整体替换该简历的旧声明（防幽灵技能）；简历删除时级联清理
  - 查询侧：`SkillProfileResponse` 新增 `declaredSkills`（简历已列、尚无评分证据的技能）；已考过的技能按大小写不敏感排除，不会同时出现在两个列表
  - 已验证：`ResumeSkillNormalizerTest` 9 项、`ResumeEvidenceExtractorTest` 3 项、`SkillProfileAggregatorTest` 新增 4 项、`SkillProfileQueryServiceTest` 3 项；迁移在真库由集成测试链路执行
- [x] 面试提案读取 Skill Profile，自动将低分技能转换为 focusSkills
  - **关键侦察**：focus 此前是**纯装饰**——`_create_interview_action` 调 Java 时只传 skillId/difficulty/questionCount/resumeId，focus 从未进 Java，且 Java `create_interview` Tool 也没有该参数；用户会看到「重点考察 JVM」然后照样被问 MySQL
  - 修复（boss 确认透传生效）：`CreateInterviewRequest` 新增 `focusCategories`；`InterviewSkillService.focusOn` 按分类 key 或 label 大小写不敏感裁剪该方向的出题分类，**一个都没命中时返回原方向全量分类**（focus 是「重点考察」而非「只考这些」，空分类会让出题 prompt 失去依据）；Agent Tool 参数表同步透出
  - 提案节点：读画像（失败降级）→ prompt 注入画像参考（含低分技能与简历已列未考）→ LLM 返回的 focus 按 `_sanitize_focus` 白名单收进该方向真实分类（key 精确 → label 精确 → 双向子串容忍 "SQL"→"MySQL"）→ 全被拦掉时用 `_profile_focus_hints` 确定性候选兜底（简历已列未考 > 低分 <60，与前端色阶阈值一致）；模型异常的回落路径同样取画像候选
  - 已验证：`tests/test_interview_proposal.py` 9 项（含「LLM 臆造分类被拦掉后落到画像候选」「模型缺失时默认方向仍取画像候选」）
- [x] 面试完成后展示画像变化及对应 Evidence，而不只展示最新静态分数
  - 新增 `GET /api/interview/sessions/{sessionId}/profile-impact`（`SkillProfileImpactService`）：**差分是派生的、零新增存储**——before = 排除本场证据的均值、after = 含本场证据的均值，与聚合器同口径；声明型证据不参与
  - 本场首次考到的技能 `beforeScore=null` 且 `delta=0`（不报虚高涨幅，前端渲染为「本场新增」）；结果卡新增 `ProfileImpactCard`：前后分、分数条（与侧栏同色阶）、变化徽标，展开后是本场逐题证据
  - 已验证：`SkillProfileImpactServiceTest` 5 项；P4-6b（Copilot 建议话术）可消费同一端点，本轮按计划未做
- [x] 为分数变化提供来源、时间和面试场次追溯入口
  - 每条变化的展开区显示 `面试 · 第 N 题 · 分数 · 时间`（题号由 evidence.sourceId 解析，解析失败降级为「场次记录」）
  - 追溯入口：每条变化提供「查看该场面试 →」跳转面试记录页，按 sessionId 定位并高亮该行（标注「画像变化来源」）；同一场多条证据去重后只留一个入口
  - Agent 侧同步补齐：`summarize_skill_profile` 的证据摘要此前丢弃了来源类型与时间，现改为 `面试 {sessionId} 第N题 = X分 @日期`，并单列简历声明行
  - 已验证：`utils/profileImpact.ts` 7 项单测 + E2E `interview-restore.spec.ts`「结果卡展示本场画像变化，并提供可用的场次追溯入口」（真实点击跳转断言 URL）

### P2 待修正

- [ ] 增加简历解析纠错/补录页面；当前前端明确不提供结构化补录表单
- [ ] 落实 GENERAL、STRUCTURE、JD_TARGETED 模式判定及上下文不足时的澄清流程
- [ ] JD 定向优化时持久化 `JD_TARGETED` 和 `targetJobId`；禁止仍统一写为 `GENERAL`
- [ ] 扩展确定性真实性校验：除新增数字外，还要识别新增技术栈、公司、项目和经历事实
- [ ] “全选/全不选”后重新生成 Preview，保证选择状态与预览一致
- [ ] 明确 Proposal 的 REJECTED 操作入口及审计状态流转
- [ ] 评估是否启用配置化 self-review；没有质量证据前保持默认关闭

### P4 待修正

> **进度（2026-09-15）**：六项全部完成，P4 待修正清零。P4 二期四项（P4-4b/5/6b/7）按原计划保持未完成。

- [x] 修复简历题与通用题合并时 difficulty、expectedPoints、followUpType 元数据丢失
  - 根因：`mergeQuestionBatches` 用顺序题单工厂 `create(...)`（只带 7 个字段）重建合并后的题目
  - 修复：新增 `InterviewQuestionDTO.withIndex(newIndex, newParentQuestionIndex)` 只改索引、原样保留全部字段；合并改走该方法
  - 已验证：`InterviewQuestionServiceTest` 3 项（合并保留元数据 / 空侧原样返回 / withIndex 只改索引）
- [x] 恢复面试时按“实际已提问轮次”重建消息，不得按题库下标展示被策略跳过的追问题
  - 根因：前端 `buildTurns` 按 `currentQuestionIndex` 遍历整个题库，被策略跳过的候选追问仍留在数组里，会被当成「已问过」渲染
  - 修复：抽出 `utils/interviewTurns.ts`（`buildAnsweredTurns` / `currentQuestionOf` / `deriveInterviewView`），权威判据改为「该题是否有作答记录」；已结束会话不再返回「当前题」（`currentQuestionIndex` 可能停在未问过的追问上）
  - 已验证：`test:interview-turns` 8 项（含跳过追问 / 已结束后无当前题 / 索引越界不抛错）+ E2E `interview-restore.spec.ts` 真实组件断言被跳过的追问不出现
  - **回归有效性已验证**：临时把逻辑改回旧写法后，单测 5/8 失败、E2E 连续 3 次失败
- [x] 修复完成并退出后被旧 `interview_session` block 自动重新拉回 Interview Mode 的问题
  - 根因：退出时向会话追加「面试完成摘要」，messages 随之变化，自动进入 Interview Mode 的 effect 会重新扫描到那个旧信号块
  - 修复：`closedInterviewSessionsRef` 记录已主动退出的 session，信号块对它们不再触发自动进入
  - 已验证：E2E `interview-restore.spec.ts`「完成退出后不被历史里的 interview_session 信号块拉回面试模式」
- [x] 进度按实际已提问题数计算，候选追问题不得提前计入用户可见总进度
  - 根因：`totalQuestions` 是题库总数（含候选追问），自适应会话会跳过其中一部分，用它做分母会得出「只答了 3 题却显示第 10 / 12 题」
  - 修复：自适应会话显示「已答 N 题 · 主题 x/y」（分母为主问题数）；非自适应顺序会话保持「第 x / y 题」（此时题库总数即真实总题数）；`interviewProgress` 统一计算
  - 已验证：`test:interview-turns` 断言 `mainCount ≠ poolTotal`；E2E 断言不出现 `第 3 / 4 题`
- [x] 将 `REVIEW_INTERVIEW` 接到真实前端入口，并支持指定 session 的逐题复盘
  - 此前 Python 侧已实现 REVIEW_INTERVIEW（读 Java `/details` 做逐题复盘），但前端没有任何入口可触发
  - 修复：面试结果卡新增「让 Copilot 复盘」→ REVIEW_INTERVIEW（带 sessionId）；面试记录页每条已完成文字面试新增「让 Copilot 复盘这场面试」→ 带 `reviewSessionId` 跳 `/copilot`，由该页发起 action，因此支持复盘**指定的那一场**而不只是最近一场
  - 附带：`CopilotOutletContext` 新增 `conversationsLoaded`，区分「还没加载」与「加载完确实为空」，避免带 action 跳转过来时误建新会话
  - 已验证：E2E 真实点击「让 Copilot 复盘」并断言请求体 `ACTION_SELECTED / REVIEW_INTERVIEW / payload.sessionId` 等于被点击的那一场；点击后即退出 Interview Mode。`REVIEW_INTERVIEW` 的 Python 侧处理（读 `/details` 逐题复盘、缺 sessionId 拒绝）由既有 graph 测试覆盖
- [x] 为自适应选题补充包含“题目合并、跳过追问、刷新恢复、提前结束”的集成测试
  - 新增 `AdaptiveInterviewFlowTest`（8 项）沿合并后的真实题库走完整链路：元数据保留 + 索引偏移后追问归属正确（答好 Q1 得到 QF1 而不是上一批的 RF1）、答不上中断追问组、缓存失效后按 DB 重建（被跳过的追问不带答案）、缓存命中不回源、提前结束置 COMPLETED 并投递评估、重复交卷被拒、提前结束后恢复无当前题
  - 测试内用内存 store 代替 Redis，使 `saveSession → getSession` 的答案回填真正被验证，而不是把预期结果直接塞回被测代码
  - 新增 `test:interview-turns`（8 项）与 E2E `interview-restore.spec.ts`（2 项）
- [ ] P4-4b、P4-5、P4-6b、P4-7 继续保持未完成状态，按实际依赖逐项推进

## 四、验证基线问题

### Java

- [x] 修复 `SkillProfilePipelineIntegrationTest` 的数据库不可用跳过机制；当前 Spring/Flyway 在测试方法执行前已连接数据库，导致 `assumeTrue` 无法生效
  - 修复方式：改用类级 `@EnabledIf(value = "localDatabaseAvailable", ...)`（对齐 `TypstCompilerTest` 既有惯例），方法内 `assumeTrue` 移除
  - 条件内先判断凭据可解析、再以 1s 超时探测 `POSTGRES_HOST:PORT` TCP 连通性；求值发生在 Spring 上下文创建之前，因此不再触发 Flyway 连接
  - 同时把 `POSTGRES_PORT`/`POSTGRES_DB` 的解析从「仅环境变量」改为「环境变量 → .env → 默认值」，避免 .env 中配置的端口被忽略
  - 已验证：报告记录 `tests=1 skipped=1 failures=0`，跳过原因可读，不再整类报错
- [x] 恢复 `./gradlew :app:test --no-daemon` 全绿基线
- 当前结果（2026-09-14）：**384 个测试，0 个失败，0 个错误，50 个跳过，334 个通过**
  - 50 个跳过均为显式声明的预期跳过：`VoiceInterviewIntegrationTest`（@Disabled 待修 test profile 配置）、Typst golden（本机无 typst 二进制）、画像真实 DB 链路（本地无 DB）、4 个「待重写」用例
  - 附带修复：本机默认 `JAVA_HOME` 指向 Corretto 8，Gradle 需 JDK 17+；门禁命令需以 JDK 25 运行（CI 由 `setup-java` 保证）

### Python Agent

- [x] `uv run pytest`：75 个测试通过
- [x] 修复 `ruff check src tests` 的 8 个错误
  - `execute_action.py` 删除死代码（未使用的 `resume_id` / `params`）
  - `interview_proposal.py` 移除未使用导入 `BaseMessage`；prompt 内 JSON 示例改多行；`logger.info` 换行
  - `clients/backend.py` 的 `ASYNC109`：`call_tool(timeout=...)` 是透传给 httpx 的逐请求超时，非等待语义，故就地 `# noqa: ASYNC109` 并注明理由（未删除参数——`create_interview` 依赖 `timeout=300.0`）
  - `tests/test_graph.py` 3 处超长行拆行
- [x] 修复 `mypy src` 的 1 个错误：面试提案中的模型对象可能为 `None`
  - 修复方式：`_derive_proposal` 内取到模型后显式判空并 `raise RuntimeError`，由既有 `except Exception` 回落确定性默认推荐（失败不阻断面试发起）

### Frontend

- [x] `pnpm run build` 通过
- [x] 前端单元测试通过（2026-09-14：7 个脚本 29 项通过，含 Copilot Action 路由 6 项）
- [x] 修复 CSS 语法警告
  - 根因：`@custom-variant dark` 与伪元素选择器上的 `@apply dark:/hover:` 组合，会被重写成空的 `:where()`，产出 `::-webkit-scrollbar-track:where()` 这种非法语法
  - 修复方式：`.scrollbar-thin` 三条规则改为直接声明 + Tailwind 主题变量（`var(--color-slate-*)`），暗色显式写 `.dark` 祖先选择器
  - 已验证：构建产物中 `:where()` 出现 0 次，3 条 `css-syntax-error` 警告消失
- [x] 评估并拆分超过 500 KB 的 `syntax-highlighter` 等大 Chunk（2026-09-14，见「五、P6-5 性能优化」）
- [ ] 补充简历优化、能力画像和文字自适应面试 E2E；Copilot 加载/错误/停止态（`e2e/copilot-states.spec.ts`）与会话管理（`e2e/copilot-sessions.spec.ts`）已覆盖

## 五、Phase 6：产品优化与打磨——优先级 P6

### P6-0 先恢复工程质量门禁 ✅（2026-09-14 完成）

- [x] Java 全量测试通过 —— `./gradlew :app:test --no-daemon`：**442 测试 / 0 失败 / 0 错误 / 49 跳过**（跳过均为显式声明的预期行为；DB 在线时画像集成用例真跑，故比离线条目少 1 个跳过）
- [x] Python pytest、ruff、mypy 全部通过 —— ruff 0 错、mypy 0 错（40 源文件）、pytest **88** 通过
- [x] Frontend build、unit test、E2E 全部通过 —— build 成功且 CSS 语法警告清零、**9 个单测脚本 44 项通过**、Playwright E2E **11 项通过**
- [x] 将上述命令固化到 CI，禁止带失败基线继续累计功能
  - **关键修复**：`.github/workflows/ci.yml` 原触发分支写的是 `master`，而本仓库真实分支是 `main`（默认）+ `dev`，`master` 只存在于 upstream 父仓库 —— 即原 CI **从未被触发过**。已改为 `main` + `dev`
  - 新增 `agent` job：`uv sync --frozen` → `ruff check src tests` → `mypy src` → `pytest`（原 CI 完全没有 Python 门禁）
  - 新增 `test:copilot-action`（原 CI 漏跑）与 Playwright E2E（含 `playwright install --with-deps chromium`）；E2E 用例自带 WebSocket stub 与 API route mock，只需前端 dev server，不依赖 Java/DB
  - 新增聚合 `quality-gate` job（`needs: [backend, agent, frontend]`，`if: always()` 且任一非 success 即 exit 1）：分支保护只需勾选这一个 check
  - **backend job 挂真实 Postgres service**（`pgvector/pgvector:pg16` + 健康检查 + 注入 `POSTGRES_*`）：每次 CI 都会在**全新空库**上真跑一遍完整 Flyway 迁移链（此前 CI 里所有迁移都是零验证），依赖真实 DB 的集成用例也从「跳过」变为真跑；刻意不挂 Redis——已实测 Redis 不可用时上下文仍能启动（消费者只报连接告警），相关用例不依赖它
  - 该改动经本地空库预验证：新建空库 → `POSTGRES_DB=xxx ./gradlew :app:cleanTest :app:test` → 15 条迁移全部成功、hstore/uuid-ossp/vector 扩展自动创建、`agent_messages.status` 为 NOT NULL 且 `completed` 已移除、24 张表就绪

**验证方式**：以上三端命令均在本地以 CI 原样命令复跑通过；CI 触发分支与 YAML 结构已校验。

### P6-1 修复影响主流程的状态问题

- [ ] 修复 Interview Mode 的恢复、退出重入和候选题进度问题
- [ ] 修复问题池合并导致的结构化元数据丢失
- [ ] 补齐 SSE 中断、停止生成和 Agent Tool 失败后的可恢复状态
- [ ] 为所有异步状态提供明确的 loading、failed、retry 和 completed UI

### P6-2 打磨简历优化可信度与可解释性

- [ ] 支持解析内容纠错后再确认成为 ACTIVE 版本
- [ ] 正确区分通用优化、结构优化和 JD 定向优化
- [ ] 每条 Patch 展示“修改原因、依据、影响范围和真实性风险”
- [ ] 对新增事实执行确定性校验；无法确认时要求用户补充或确认
- [ ] 保证 Proposal、Preview、Apply、Version、Export 使用同一组选中 Patch

### P6-3 打通画像驱动的自适应体验

- [ ] 画像低分技能可一键生成定向面试提案
- [ ] 面试配置明确展示“为什么推荐这些 focusSkills”
- [ ] 面试报告产生 Evidence 后自动刷新画像
- [ ] Copilot 展示分数前后变化，并基于 Evidence 给下一步建议
- [ ] 所有画像变化均可追溯到 Resume、Interview 或 Learning Evidence

### P6-4 三条产品闭环 E2E 验收

- [ ] **简历闭环**：上传/选择 Resume → 绑定 JD → Gap/Patch → 预览 → 确认 → 新版本 → PDF
- [ ] **面试闭环**：低分技能 → 定向面试 → 自适应追问 → 报告 → Evidence → 新画像
- [ ] **Copilot 闭环**：一句自然语言完成上下文读取、面试提案、创建、执行、复盘和下一步建议
- [ ] 每条闭环至少包含 happy path、用户取消、刷新恢复、依赖失败和重试场景
- [ ] 三条闭环均通过自动化 E2E 后，再将 Phase 5 标记为完成

### P6-5 性能优化（记录，不阻塞验收）

> 主动加入的优化项，记录动机与实测数据，便于后续回归时判断是否被抵消。

- [x] 代码高亮异步 chunk 瘦身（2026-09-14）
  - 问题：`react-syntax-highlighter` 的默认 prism 构建自带 300 种语言语法，`i18n` 之外的整包被打进一个 697.69 kB 的异步 chunk（gzip 235.44 kB），也是构建里最后一条 >500 kB 警告
  - 做法：高亮器改用 `prism-light` 并只注册 25 种白名单语言；主题从 `styles/prism` 桶导出改为直接引 `styles/prism/one-dark`（桶导出会把 40+ 套主题一起打包）；语言模块写成显式静态字符串的 `import()`（模板字符串形式无法被 Vite 静态分析，会把整包语法拉回来）；未注册语言走纯文本渲染，不依赖高亮器兜底
  - 配套：`vite.config.ts` 的 `manualChunks` 由对象形式改为函数形式按包路径分组——对象形式只捕获包入口及其静态依赖，按需 `import()` 的语言模块会各自切出 0.12 kB 碎片 chunk（实测 25 个），折回同一 chunk 后碎片归零
  - 实测：`syntax-highlighter` **697.69 kB → 146.80 kB**（gzip 235.44 → 41.45 kB，-79%）；构建 >500 kB 警告清零；`react-vendor` / `ui-vendor` 体积不变
  - 已验证：`test:code-language` 6 项单测（别名映射、大小写归一、未知语言返回 null、解析结果必落在白名单内）+ 构建产物核对

## 六、产品打磨停止标准
满足以下条件后，才将当前阶段定义为“可演示、可稳定回归的产品版本”：

- [ ] P1/P2/P3/P4 无已知 P0/P1 主流程缺陷
- [ ] 三条 P5 产品闭环均有稳定 E2E 覆盖
- [x] Java、Python、Frontend 质量门禁全部为绿色（2026-09-14，见 P6-0）
- [ ] 刷新、切会话、停止、取消、失败重试不会破坏业务状态
- [ ] 用户能理解每一次 Agent 推荐的依据、即将产生的副作用和确认结果
- [ ] Profile 的每次变化都能追溯到结构化 Evidence
- [ ] Demo 主链无需人工修复数据库或手动绕过异常即可完成
