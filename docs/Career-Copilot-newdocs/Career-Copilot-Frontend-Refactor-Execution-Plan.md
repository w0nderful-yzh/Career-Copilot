# Career Copilot 前端重构可行性评审与落地计划

> 评审基线：2026-08-26 当前代码、`Career-Copilot-Frontend-Refactor.md` 与目标效果图  
> 文档用途：把长期目标态收敛成可验证、可演示、可写进简历的实施方案  
> 事实边界：本文将“当前已实现”“可复用”“需要新增”明确分开，不把效果图中的示例数据视为已有能力

## 1. 结论

整体方向可行，但不应作为一次单纯的前端换皮实施。

最合适的项目定位是：

> 基于受控 Structured UI 与 SSE 事件流，增量构建 Agent-first 求职工作台，并打通 React → Python Agent → Java Tool → 业务页面的真实闭环。

建议保留三栏信息架构，但调整实施顺序：

```text
原计划：Shell → 大量 Block → Composer → Streaming → 业务能力

优化后：协议收敛 → Shell → 流式状态机 → 真实业务纵向切片 → 证据型 Context → 扩展能力
```

原因是现有项目已经具备会话、SSE 和首批 Block 基础，真正欠缺的不是更多卡片，而是：

- 前端协议的稳定消费与运行状态管理；
- 带参数的安全导航和结构化 Action 输入；
- 当前代码中真实存在的业务数据闭环；
- 右侧 Context 与 Agent 实际使用证据的一致性；
- Copilot 专项测试与可量化验收。

## 2. 当前代码可行性矩阵

| 能力 | 当前状态 | 结论 |
| --- | --- | --- |
| `/copilot` 默认入口 | 已实现 | 直接保留 |
| Java 会话与消息持久化 | 已实现 | Java 继续作为 System of Record |
| Python SSE | 已实现 `block / message_delta / error / done` | 在现有协议上增量扩展 |
| 受控 Block | 已实现 `text / action / resume_summary / interview_summary / knowledge_citations` | 不要另起一套协议 |
| 前端 Block 白名单渲染 | 已实现 | 需要增加运行时校验和测试 |
| 路由白名单 | 已实现静态映射 | `params` 尚未真正生成动态路由 |
| 会话侧栏 | 已实现基础的新建、列表、切换、删除 | 可升级为目标图左栏 |
| 三栏 Copilot Shell | 未实现 | 前端可独立完成 |
| 结构化 Action 输入 | 未实现，ChatRequest 只有文本和会话 ID | 需要前后端协议共同修改 |
| Tool / Run 状态事件 | 未实现 | 需要 Python SSE 协议扩展 |
| 通用 Attachment 资源 | 未实现 | 不能只做前端模拟；应先复用简历和知识库上传 |
| JD、Preparation、长期 Profile | 当前无对应业务模块 | 属于后续全栈领域能力，不是前端重构前置条件 |
| 多用户隔离 | 当前会话固定为 `default` 用户 | 本地简历项目可接受，公开部署前必须补齐 |
| Copilot 前端专项测试 | 基本缺失 | 必须补 reducer、协议、Block 和 E2E 测试 |

因此，效果图中以下内容第一版不能静态伪造：

```text
准备度 72%
长期能力画像分数
今日准备任务
当前 Java 后端实习目标
ByteDance JD 活跃资源
```

在对应领域模型上线前，右栏应展示已有事实，例如最近简历、最近面试、知识库引用和本轮 Tool 证据，并提供真实 Empty State。

## 3. 对目标效果图的取舍

### 3.1 建议保留

- 左侧“品牌 + 新建对话 + 最近对话 + Workspace”信息架构；
- 中间以对话和结构化业务卡片为主的工作区；
- 右侧持续可见的上下文区域；
- 底部固定 Composer；
- 空会话中的四个高频 Quick Action；
- 通过语义色区分优势、风险和进行中状态。

### 3.2 建议优化

#### 空状态不要长期占据半屏

效果图在已有消息时仍保留大型欢迎区，会压缩主要对话空间。

建议：

- 新会话：显示完整欢迎区和 Quick Actions；
- 用户发送第一条消息后：欢迎区折叠为紧凑会话标题；
- 空状态高度控制在约 `280px`，而不是接近半个视口。

#### 右栏改为 Evidence Rail

右栏最有价值的不是多放几张卡，而是解释 Agent 基于什么作出判断。

第一版建议显示：

```text
本轮依据
├── 已读取 2 份简历
├── 已查询最近 5 场面试
└── 引用了 JVM 知识库

最近资源
├── JavaResume.pdf
└── 最近一场 JVM 面试

待完善
└── 尚未建立长期能力画像
```

后续有了 Profile / Preparation / Job 领域数据，再升级为效果图中的目标、画像和任务。

所有分数必须带来源或更新时间；不能只展示一个无法解释的数值。

#### 减少无业务价值的 Header 操作

效果图中的通知铃铛和全屏按钮不是当前核心。第一版 Header 只保留：

- 当前会话标题；
- Context Panel 折叠按钮；
- 会话菜单；
- 必要时显示运行状态。

#### 视觉上不要逐像素照搬通用 AI 模板

建议采用“专业求职工作室 / Evidence Workspace”的方向：

- 暖白纸张色作为底色，深墨色承载正文；
- 品牌紫只用于主操作、选中态与焦点态，减少大面积紫色渐变；
- 绿色和橙色只表达证据状态，不作为装饰；
- 卡片边界以细线和层级留白为主，阴影保持克制；
- 右栏通过“来源、更新时间、变化原因”形成项目辨识度。

### 3.3 推荐布局约束

```css
desktop:
  grid-template-columns: 248px minmax(520px, 1fr) 320px;

center content:
  max-width: 880px;

< 1280px:
  Context Panel -> Drawer

< 768px:
  Sidebar -> Drawer
  Context Panel -> Drawer
  Center only
```

Sidebar、消息区和 Context Panel 应分别滚动，Composer 固定在中心列底部。

## 4. 协议优化方案

### 4.1 Message Content 与 Block 分工

建议继续使用：

```ts
type CopilotMessage = {
  content: string;
  blocks: CopilotBlock[];
};
```

- `content` 统一按受控 Markdown 渲染；
- `blocks` 只承载需要交互或结构化展示的内容；
- 不再同时维护 `markdown` Block 和 Message Content，避免两套文本模型。

首个简历版本只保留：

```text
navigation
resume_summary
interview_summary
knowledge_citations
progress（有真实数据后）
```

`chart`、`skill_profile`、`task_list` 不应早于真实 Profile / Preparation 数据上线。

### 4.2 SSE 事件

不必一次引入复杂实时协议。建议在当前事件上增加最小元数据：

```ts
type StreamEnvelope = {
  protocolVersion: 1;
  runId: string;
  sequence: number;
  type:
    | "message_start"
    | "message_delta"
    | "block"
    | "tool_status"
    | "run_status"
    | "done"
    | "error";
  payload: unknown;
};
```

前端用 reducer 管理一次 Run：

```text
IDLE
→ SUBMITTING
→ STREAMING
→ WAITING_USER / WAITING_ASYNC
→ COMPLETED / FAILED / CANCELLED
```

切换会话、新建会话和离开页面前必须取消当前请求，避免旧流写入新会话 UI。

### 4.3 Block 必须运行时校验

当前 TypeScript 类型只在编译期生效，SSE 和历史消息都是外部数据。

应增加：

- Block discriminator 校验；
- 必填字段和数组项校验；
- 未知 Block 的可观测忽略；
- 协议错误日志，不向用户暴露内部字段；
- Python schema 与前端 fixture 的契约测试。

### 4.4 Navigation 与 Action 分开

```text
Navigation
→ 用户点击后跳到白名单业务路由

Action
→ 结构化事件重新进入 Agent

Confirmation
→ 对 CONFIRM_WRITE 进行明确确认
```

路由定义应是构建函数，而不是只有静态字符串：

```ts
INTERVIEW_SESSION: ({ sessionId }) => `/interview/session/${sessionId}`
RESUME_DETAIL: ({ resumeId }) => `/history/${resumeId}`
```

参数需经过白名单和类型校验，禁止拼接任意 URL。

## 5. 推荐实施计划

### Milestone 0：收敛范围与契约（0.5～1 天）

目标：先确定一条可录屏、可测试的完整演示脚本。

任务：

- 修正文档中 `/copoilot` 拼写和 Block 联合类型不一致；
- 固定第一版 Block / SSE / Action 协议；
- 记录当前页面截图、构建结果和核心 bundle 基线；
- 明确第一版不包含 JD、Preparation、长期 Profile 和通用 Attachment；
- 建立 Copilot 测试目录和最小 fixture。

验收：协议文档与 Python、TypeScript 类型一一对应。

### Milestone 1：Copilot Shell 与视觉骨架（2～3 天）

目标：先完成效果图中真正属于前端的部分。

任务：

- 把 `/copilot` 从旧菜单型 Layout 中拆成独立 `CopilotShell`；
- 保留旧业务页面和旧 Layout，采用 additive refactor；
- 实现品牌区、Conversation、Workspace 导航和主题入口；
- 实现中心 Header、可折叠欢迎区、Quick Actions；
- 实现 Context Panel / Drawer 的真实 Empty State；
- 完成 1440px、1024px、390px 三档响应式；
- 补齐键盘焦点、按钮语义、Drawer 焦点管理和 reduced-motion。

验收：

- `/copilot` 不出现双重侧栏；
- 已有消息后欢迎区自动折叠；
- 三档宽度无横向溢出；
- 所有核心操作可使用键盘完成。

### Milestone 2：流式会话状态机（2～4 天）

目标：让当前 SSE 能力达到可维护、可恢复的工程质量。

任务：

- 提取 `useCopilotRun` 或 reducer，统一处理事件；
- 会话创建失败时停止发送，不产生“看似成功但未持久化”的消息；
- 切换会话时取消旧流；
- 支持 Retry、Cancel、Failed 和 Loading 状态；
- 增加 Block 运行时校验；
- 让历史消息与流式消息经过同一个 normalize 流程；
- 为长会话设定虚拟化启用阈值，避免一开始就过度复杂化。

验收：

- reducer 和事件解析器单元测试通过；
- 取消、断网、服务端 error、未知 Block 均有稳定 UI；
- 快速切换会话不会串流或污染消息。

### Milestone 3：两条真实业务纵向切片（3～4 天）

目标：形成简历项目最重要的端到端闭环。

#### 链路 A：简历复盘

```text
用户：“看看我最近的简历分析”
→ Python Intent
→ Java get_resume_list / get_resume_analysis
→ ResumeSummaryBlock
→ RESUME_DETAIL 白名单导航
→ 现有简历详情页
```

#### 链路 B：面试复盘

```text
用户：“我最近面试表现怎么样？”
→ Python Intent
→ Java get_interview_history / get_interview_report
→ InterviewSummaryBlock
→ INTERVIEW_REPORT 白名单导航
→ 现有面试详情或报告页
```

要求：

- 路由参数真实生效；
- 卡片字段来自 Java 数据，不使用 mock；
- 空数据、评估中和评估失败都有状态；
- 从业务页返回 Copilot 时保留 conversationId；
- 补 Python API 契约测试、Java Tool 测试和 Playwright E2E。

### Milestone 4：Evidence Context Panel（2～3 天）

目标：让右栏真实表达“Agent 本轮使用了什么”。

分两层实现：

```text
Run Evidence（本轮临时上下文）
→ Tool 状态、读取的简历、面试、知识库引用

Workspace Summary（持久业务事实）
→ Java 聚合已有 Resume / Interview / Knowledge 数据
```

注意：

- Python 只产生 Run Evidence，不持久化 Java 业务事实；
- 持久化资源摘要由 Java 提供；
- 没有 Profile / Preparation 时显示真实空状态；
- 数值必须显示来源、样本量或更新时间。

验收：用户能看出当前回答引用了哪些业务数据，且右栏与聊天卡片不重复堆砌。

### Milestone 5：Action、Confirmation 与文件（可选，4～6 天）

此阶段不属于第一版前端重构的完成条件。

优先顺序：

1. 增加结构化 `ACTION_SELECTED` 输入；
2. 对写操作增加 `confirmationId`、过期和重复提交保护；
3. 先复用现有简历上传和知识库上传；
4. 只有 Resume + JD + Knowledge Document 确实需要统一生命周期时，再新增 Java Attachment 领域模型；
5. Python 只接收资源 ID，不处理二进制和业务表。

## 6. 第一版范围裁剪

### P0：必须完成

- 独立三栏 Copilot Shell；
- 会话列表、流式消息和 Composer；
- 受控 Block + 运行时校验；
- 简历复盘和面试复盘两条纵向切片；
- 带参数的安全导航；
- 错误、取消、空状态和响应式；
- 单元测试、契约测试、Playwright E2E。

### P1：完成后明显加分

- Evidence Context Panel；
- Tool / Run 状态；
- Markdown、代码块和引用体验；
- 长会话按阈值启用虚拟化；
- 可访问性检查和性能基线。

### P2：后续全栈能力

- 结构化 Choice / Confirmation；
- 通用 Attachment；
- JD 领域模型与岗位匹配；
- Preparation Plan；
- 基于证据的长期能力画像。

### 暂不做

- 自由拖拽布局；
- 多 Agent Avatar；
- 任意 URL 或任意组件渲染；
- Agent Marketplace / MCP UI；
- 为展示而展示的复杂图表和动画。

## 7. 测试与量化验收

### 前端

- TypeScript + Vite production build；
- reducer、SSE parser、Block validator、route builder 单元测试；
- React 组件测试覆盖 Empty / Streaming / Error / Unknown Block；
- Playwright 覆盖新建会话、流式回答、会话切换、业务跳转和返回；
- 1440px、1024px、390px 视觉回归截图。

### Agent 与后端

- Python schema / stream event 契约测试；
- Java Tool 与 Conversation service 测试；
- 不调用真实 LLM 的确定性测试；
- 对真实 LLM 仅保留独立 smoke test。

### 建议记录而非预先宣称的指标

```text
Copilot 路由首屏 JS / CSS 大小
首条消息到首个 token 的时间
长会话 100 / 500 条消息滚动帧率
会话切换与取消的错误率
Lighthouse Performance / Accessibility
关键 E2E 通过率
```

目标可以设定为：

- 关键交互无横向布局偏移；
- Lighthouse Accessibility ≥ 90；
- 关键 E2E 在本地连续运行 10 次无随机失败；
- Copilot 页面不因引入图表、编辑器而进入主包；
- 所有最终简历数字都由测量结果回填，不编造优化百分比。

## 8. 简历项目包装建议

不要把项目亮点只写成“使用 React + Tailwind 重构聊天页面”。

完成 P0～P1 后可以写成：

> 设计并落地 Agent-first 求职工作台，将传统功能菜单重构为 Conversation + Evidence Context + Business Workspace 三层交互，并通过受控 Structured UI 协议连接 Python Agent 与 Java 业务能力。

可拆成以下成果点，数字在实际测量后填写：

- 设计 TypeScript Discriminated Union + 白名单路由的 Structured UI 协议，阻断任意组件和任意 URL 渲染；
- 基于 SSE reducer 实现流式消息、取消、失败恢复与会话隔离，覆盖 `[实际数量]` 类运行状态；
- 打通 React → FastAPI Agent → Java Tool → Resume / Interview 页面两条端到端链路；
- 将 Agent 使用的简历、面试和 RAG 证据显式化为 Context Panel，提高回答可解释性；
- 建立 Python / Java / React 契约测试与 Playwright E2E，关键流程通过率为 `[实测结果]`；
- 通过路由级懒加载、按需图表和长列表阈值优化，将 Copilot 首屏体积或交互指标优化至 `[实测结果]`。

推荐录屏 Demo：

```text
1. 新建会话
2. 输入“看看我最近的简历分析”
3. 展示真实 SSE 与 ResumeSummaryBlock
4. 右栏同步显示本轮读取证据
5. 点击卡片进入具体简历详情
6. 返回原 Conversation
7. 输入“我最近面试表现怎么样”并进入报告页
```

这条 Demo 比展示很多静态卡片更能证明架构、协议、状态管理和业务复用能力。

## 9. 风险与边界

| 风险 | 处理方式 |
| --- | --- |
| 效果图数据早于领域模型 | 使用真实 Empty State；相关卡片延后 |
| 前端、Python、Java Block 类型漂移 | 统一 schema、fixture 和契约测试 |
| 会话切换导致 SSE 串流 | AbortController + runId + reducer 隔离 |
| Block 中出现任意路由 | route builder 白名单 + 参数校验 |
| 取消后保存了不完整回答 | 明确 `CANCELLED` 语义与持久化策略 |
| 公开部署发生用户数据串读 | 在部署前替换 `DEFAULT_USER_ID` 并增加鉴权与所有权校验 |
| 为简历亮点过度引入依赖 | 优先复用现有 React、Tailwind、Framer Motion、Recharts 和 Virtuoso |
| 视觉很好但没有业务闭环 | 每个里程碑都以真实纵向链路验收 |

## 10. 建议的第一步

先完成 Milestone 0～1，但同时锁定 Milestone 3 的接口和 Demo 数据。

最小开工切片：

```text
Copilot 独立 Shell
+
当前会话能力
+
ResumeSummaryBlock 带参数跳转
+
真实空状态 Context Panel
```

完成后立即做一次浏览器视觉验收，再进入 reducer 和两条业务链路，不继续扩张静态卡片数量。
