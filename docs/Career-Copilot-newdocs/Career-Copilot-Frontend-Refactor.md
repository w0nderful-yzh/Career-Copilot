# Career Copilot 前端重构设计文档

> 文档定位：Career Copilot Agent 化前端的长期开发基线  
> 适用范围：`frontend/`  
> 目标：将原 InterviewGuide 的“功能菜单型前端”重构为“Agent + Workspace 型前端”
>  
> 配套落地文档：[`Career-Copilot-Frontend-Refactor-Execution-Plan.md`](./Career-Copilot-Frontend-Refactor-Execution-Plan.md)，用于区分当前代码事实、目标态能力与简历项目实施顺序。

---

# 1. 重构背景

Career Copilot 不再是一个以“简历分析 / 模拟面试 / 知识库问答”等独立功能作为主要入口的系统。

新的产品模型是：

```text
用户表达目标
    ↓
Career Copilot
    ↓
理解意图
    ↓
调用业务 Tool / RAG / Agent Workflow
    ↓
返回文本、卡片、按钮、图表或导航动作
```

因此前端也必须从传统的：

```text
功能菜单
├── 简历管理
├── 模拟面试
├── 面试记录
├── 面试日程
├── 知识库管理
└── 问答助手
```

转变为：

```text
Career Copilot
├── Conversation
├── Context
├── Workspace
└── Business Pages
```

核心变化不是单纯换皮，而是：

> 从“用户寻找功能”转变为“用户表达意图，Agent 调度功能”。

---

# 2. 前端最终定位

Career Copilot 前端由三部分组成：

```text
┌─────────────────────────────────────────────────────┐
│                     App Shell                       │
│                                                     │
│  Left Sidebar     Copilot Workspace    Context Panel│
│                                                     │
└─────────────────────────────────────────────────────┘
                         │
                         ▼
                  Business Pages
```

三部分职责如下。

## 2.1 Left Sidebar

负责：

- 新建对话
- Conversation History
- Workspace 入口
- Settings
- 外观切换

它不再承担“把所有业务功能全部铺开”的职责。

---

## 2.2 Copilot Workspace

系统最主要的交互区域。

负责：

- 对话
- 流式回复
- 文件拖拽
- Action
- Choice
- Structured UI
- Agent Run 状态
- Tool 执行反馈

用户大多数任务从这里开始。

---

## 2.3 Context Panel

右侧上下文区域。

负责展示：

- 当前目标
- 当前简历
- 当前 Job / JD
- 长期能力画像
- Preparation Progress
- 今日任务
- 当前 Agent Artifact

它的作用是让用户始终知道：

> Agent 当前正在基于什么上下文工作。

---

# 3. 最终页面结构

桌面端采用三栏布局：

```text
┌───────────────┬────────────────────────────────────┬─────────────────────┐
│               │                                    │                     │
│   Sidebar     │        Copilot Workspace           │   Context Panel     │
│               │                                    │                     │
│  Conversations│  Conversation / Structured UI     │ 当前目标            │
│               │                                    │ 当前资源            │
│  Workspace    │                                    │ 能力画像            │
│               │                                    │ 今日计划            │
│  Settings     │         Composer                   │                     │
│               │                                    │                     │
└───────────────┴────────────────────────────────────┴─────────────────────┘
```

推荐宽度：

```text
Sidebar:
220 ~ 260px

Context Panel:
280 ~ 320px

Center:
flex: 1
min-width: 0
```

右侧 Context Panel 支持折叠。

---

# 4. Sidebar 重构

原侧边栏存在较多一级业务入口：

```text
Agent 工作台
简历管理
模拟面试
面试记录
面试日程
知识库管理
知识库面试
问答助手
设置
```

重构后：

```text
Career Copilot
智能求职助手

+ 新建对话

最近对话
├── 准备 Java 后端实习
├── JVM 专项复习
├── 分析字节 JD
└── 优化个人简历


WORKSPACE

准备计划
能力画像
简历
模拟面试
知识库


────────────

深色模式
设置
```

---

# 5. Sidebar 信息架构原则

## 5.1 Conversation 优先

Conversation 是 Copilot 的主要任务上下文。

用户应该可以快速进入：

```text
准备 Java 后端实习
JVM 专项复习
字节岗位分析
简历优化
```

会话标题可以由 Agent 自动生成。

---

## 5.2 Workspace 保留

Agent 化并不意味着删除所有 GUI。

Workspace 是用户查看长期资源的入口：

```text
准备计划
能力画像
简历
模拟面试
知识库
```

原则：

```text
Agent = 操作入口
Workspace = 数据管理入口
```

---

## 5.3 合并低价值一级入口

以下功能不再作为左侧一级导航：

```text
面试记录
面试日程
知识库面试
问答助手
```

处理方式：

```text
面试记录
→ 合并到 模拟面试

面试日程
→ 合并到 模拟面试 / 准备计划

知识库面试
→ 作为知识库的二级能力

问答助手
→ 被 Career Copilot + RAG 完全吸收
```

---

# 6. 顶部 Header

顶部 Header 应保持简洁。

推荐：

```text
Career Copilot                         当前目标：Java 后端实习 ▼
```

或会话开始后：

```text
准备 Java 后端实习
Career Copilot
```

右侧可以放：

- Context Panel 折叠按钮
- Conversation Menu
- 当前目标切换

避免继续显示：

```text
Agent 工作台
```

这种重复信息。

---

# 7. Copilot 空状态

首次进入 `/copilot` 或新建 Conversation 时：

```text
                  Career Copilot

             今天想为求职做点什么？

      描述你的目标，或者直接拖入简历 / JD / 学习资料


       [ 上传简历 ]       [ 添加 JD ]

       [ 今日计划 ]       [ 模拟面试 ]


┌──────────────────────────────────────┐
│ 输入你的目标，或拖入简历 / JD...    │
│                                      │
│ 📎                              ↑    │
└──────────────────────────────────────┘
```

---

# 8. Quick Actions

空状态提供四个 Quick Action：

```text
上传简历
添加 JD
今日计划
模拟面试
```

注意：

这些按钮不一定直接导航页面。

优先设计为：

```text
Quick Action
    ↓
ACTION_SELECTED
    ↓
Career Agent
```

例如：

```text
点击“模拟面试”
    ↓
Agent:
“你想进行哪种面试？”

[基于当前简历]
[目标岗位面试]
[JVM 专项]
[项目深挖]
```

这使首页始终保持 Agent-first。

---

# 9. Conversation UI

聊天区域不是纯：

```text
User Text
Assistant Text
```

而是支持：

```text
Message
+
Blocks
```

示例：

```text
User

我最近准备得怎么样？


Career Copilot

本周完成了 8 / 12 个任务。

Java 与 Spring 整体较稳定，
JVM 与 MQ 仍然是目前主要薄弱项。


┌──────────────────────────────┐
│ 本周进度                      │
│ 8 / 12                67%    │
│ ███████░░░                    │
│                              │
│ [继续今日计划] [JVM专项面试] │
└──────────────────────────────┘
```

---

# 10. Message Block Renderer

建议新增统一：

```text
MessageBlockRenderer
```

负责根据：

```text
block.type
```

动态渲染。

第一阶段支持：

```text
markdown
attachment
choice
action
navigation
confirmation
progress
skill_profile
resume_analysis
task_list
chart
```

---

# 11. 建议的前端 Block 类型

```ts
export type CopilotBlock =
  | MarkdownBlock
  | AttachmentBlock
  | ChoiceBlock
  | ActionBlock
  | NavigationBlock
  | ConfirmationBlock
  | ProgressBlock
  | SkillProfileBlock
  | ResumeAnalysisBlock
  | TaskListBlock
  | ChartBlock;
```

---

# 12. ChoiceBlock

用于 Agent 要求用户从几个明确选项中选择。

例如：

```text
我识别到你上传了一份简历。

你想用它做什么？

[分析简历]
[优化简历]
[模拟面试]
[岗位匹配]
```

建议协议：

```ts
export interface ChoiceBlock {
  type: "choice";
  title?: string;
  options: {
    action: CopilotAction;
    label: string;
    payload?: Record<string, unknown>;
  }[];
}
```

---

# 13. NavigationBlock

用于 Agent 建议跳转业务页面。

例如：

```text
模拟面试已创建。

[进入模拟面试]
```

Agent 返回：

```ts
{
  type: "navigation",
  route: "INTERVIEW_SESSION",
  params: {
    interviewId: 1024
  }
}
```

前端维护 route mapping：

```ts
INTERVIEW_SESSION
→ /interviews/:interviewId
```

禁止让 LLM 直接返回任意 URL。

---

# 14. ConfirmationBlock

用于需要用户确认的写操作。

例如：

```text
我已经生成了一份 7 天准备计划。

是否保存并作为当前计划？

[创建计划]
[暂时不用]
```

点击后发送：

```text
ACTION_SELECTED
```

而不是把按钮文字重新当成自然语言发送给 Agent。

---

# 15. Composer 重构

现有底部输入框升级为：

```text
CopilotComposer
```

组件结构：

```text
CopilotComposer
├── AttachmentPreviewList
├── TextArea
├── FilePickerButton
├── AdditionalActionButton
└── SendButton
```

---

# 16. Composer 支持三类输入

Career Copilot 统一输入模型：

```text
Text
File
Action
```

## Text

开放自然语言：

```text
我十天后要面 Java 后端，帮我安排一下。
```

## File

上下文资源：

```text
resume.pdf
jd.pdf
学习资料.pdf
```

## Action

确定性用户意图：

```text
ANALYZE_RESUME
START_INTERVIEW
CREATE_PLAN
```

---

# 17. 文件拖拽

支持：

```text
dragenter
dragover
drop
```

同时必须保留：

```text
📎 文件选择
```

避免只有拖拽操作。

---

# 18. 文件上传职责

文件上传不经过 Agent Service。

正确：

```text
React
 ↓
Java Attachment API
 ↓
RustFS / S3
 ↓
Tika
 ↓
Attachment Resource
 ↓
attachmentId
 ↓
Career Agent
```

Python Agent 只拿：

```text
attachmentId
```

不要直接管理文件二进制。

---

# 19. Attachment 状态

建议前端支持：

```text
UPLOADING
STORED
PARSING
READY
FAILED
```

展示：

```text
📄 resume.pdf
上传中 64%
```

或：

```text
📄 resume.pdf
正在解析...
```

完成：

```text
📄 resume.pdf
已识别为：简历
```

失败：

```text
上传失败
[重试]
```

---

# 20. Attachment 类型

第一阶段：

```text
RESUME
JOB_DESCRIPTION
KNOWLEDGE_DOCUMENT
INTERVIEW_NOTE
UNKNOWN
```

前端根据 `documentType` 可展示不同 Icon / Label。

---

# 21. 文件上传后的 Agent 交互

例如上传：

```text
JavaResume.pdf
```

Agent：

```text
我识别到这是一份简历。

[分析简历]
[优化简历]
[模拟面试]
[岗位匹配]
```

上传：

```text
ByteDance-Java-JD.pdf
```

Agent：

```text
我识别到这是一份岗位描述。

[分析岗位]
[简历匹配]
[生成准备计划]
[岗位模拟面试]
```

---

# 22. 多文件上下文

后期允许：

```text
Resume + JD
```

同时进入 Conversation Context。

例如：

```text
📄 JavaResume.pdf
🎯 ByteDance Java Intern
```

Agent 可以直接提供：

```text
[岗位匹配]
[能力差距分析]
[生成准备计划]
[岗位模拟面试]
```

---

# 23. Right Context Panel

右侧 Context Panel 是新版前端的重要新增区域。

目标：

> 把 Agent 当前使用的关键业务 Context 显式展示给用户。

不应只存在于隐藏 Prompt 中。

---

# 24. Context Panel 默认内容

推荐：

```text
当前目标

Java 后端实习

准备度
72%

────────────

活跃资源

📄 JavaResume.pdf
🎯 ByteDance JD

────────────

能力画像

Java        82
Spring      80
Redis       74
JVM         52
MQ          61

────────────

今日任务

☑ JVM GC
□ MQ 消息可靠性
□ 项目深挖准备
```

---

# 25. Context Panel 内容动态切换

Context Panel 不需要永远显示相同内容。

## 普通 Copilot 对话

显示：

```text
当前目标
能力画像
今日任务
```

## 简历任务

显示：

```text
当前简历
简历评分
优势
不足
```

## JD 分析

显示：

```text
目标岗位
Match Score
Strong Match
Skill Gap
```

## Preparation

显示：

```text
计划进度
Day X / Y
今日任务
风险项
```

## Interview Result

显示：

```text
最近面试
得分
技能变化
主要弱点
```

---

# 26. Context Panel 不是聊天内容替代品

原则：

```text
聊天区：
解释、建议、决策、动作

右侧：
持续上下文、状态、Artifact
```

不要把所有 Context Panel 数据重复成聊天消息。

---

# 27. 建议路由结构

第一阶段：

```text
/copilot

/preparation

/profile

/resumes

/resumes/:id

/interviews

/interviews/:id

/interviews/:id/report

/knowledge

/settings
```

后期可以增加：

```text
/jobs

/jobs/:id
```

---

# 28. 首页默认路由

登录完成默认进入：

```text
/copilot
```

不再进入传统 Dashboard。

Career Copilot 是产品主要入口。

---

# 29. Business Page 原则

Agent 不替代所有业务页面。

例如：

```text
Copilot
 ↓
create_interview
 ↓
NavigationBlock
 ↓
/interviews/{id}
```

面试结束：

```text
/interviews/{id}/report
 ↓
返回 Copilot
```

Copilot 再继续：

```text
本次 JVM 表现有所提升。

[调整准备计划]
```

形成：

```text
Copilot
→ Business Page
→ Copilot
```

---

# 30. Conversation 模型

前端应支持 Conversation History。

建议：

```ts
export interface ConversationSummary {
  id: string;
  title: string;
  updatedAt: string;
}
```

功能：

- 新建
- 切换
- 删除
- 重命名
- 自动标题
- 最近对话排序

第一版只实现：

```text
新建
列表
切换
```

其他可以后续补充。

---

# 31. Message 模型

推荐：

```ts
export interface CopilotMessage {
  id: string;
  conversationId: string;
  role: "user" | "assistant";
  content: string;
  blocks: CopilotBlock[];
  createdAt: string;
  status?: "streaming" | "completed" | "failed";
}
```

---

# 32. 用户输入协议

推荐统一：

```ts
export interface CopilotInput {
  conversationId: string;
  message?: string;
  attachments?: AttachmentRef[];
  action?: CopilotActionEvent;
}
```

示例：

```ts
{
  conversationId: "conv_1024",
  message: "根据这份简历直接对项目进行深挖",
  attachments: [
    {
      attachmentId: 2048
    }
  ]
}
```

---

# 33. Action Event

用户点击 Agent 生成的按钮：

```ts
export interface CopilotActionEvent {
  type: "ACTION_SELECTED";
  action: CopilotAction;
  payload?: Record<string, unknown>;
}
```

不要把：

```text
“开始模拟面试”
```

重新包装成普通 Chat Message。

Action 是确定性输入，应保留结构化信息。

---

# 34. Streaming Event

建议 Agent Service 使用 SSE。

前端消费事件：

```text
message_start

message_delta

tool_started

tool_completed

block

run_status

done

error
```

---

# 35. Tool 状态 UI

未来可以在聊天中轻量展示：

```text
正在读取能力画像...
正在查询最近面试...
正在检索知识库...
```

完成：

```text
✓ 已读取能力画像
✓ 已查询最近面试
```

不要展示：

```text
调用 GET /internal/agent/profile/skills
```

等工程细节给普通用户。

---

# 36. Agent Run 状态

支持：

```text
RUNNING
WAITING_USER
WAITING_ASYNC
COMPLETED
FAILED
```

对应 UI：

```text
RUNNING
→ 正在处理

WAITING_USER
→ 显示 Confirmation / Choice

WAITING_ASYNC
→ 等待后台分析结果

FAILED
→ 显示可恢复错误
```

---

# 37. 前端目录建议

推荐逐步向 Feature-based 结构演进：

```text
frontend/src/
│
├── api/
│   ├── copilot.ts
│   ├── attachment.ts
│   └── request.ts
│
├── components/
│   ├── copilot/
│   │   ├── CopilotShell.tsx
│   │   ├── CopilotSidebar.tsx
│   │   ├── ConversationList.tsx
│   │   ├── MessageList.tsx
│   │   ├── MessageRenderer.tsx
│   │   ├── MessageBlockRenderer.tsx
│   │   ├── CopilotComposer.tsx
│   │   ├── AttachmentPreview.tsx
│   │   └── ContextPanel.tsx
│   │
│   └── blocks/
│       ├── ChoiceBlock.tsx
│       ├── NavigationBlock.tsx
│       ├── ConfirmationBlock.tsx
│       ├── ProgressBlock.tsx
│       ├── SkillProfileBlock.tsx
│       ├── ResumeAnalysisBlock.tsx
│       ├── TaskListBlock.tsx
│       └── ChartBlock.tsx
│
├── pages/
│   ├── CopilotPage.tsx
│   ├── PreparationPage.tsx
│   ├── ProfilePage.tsx
│   ├── ResumePage.tsx
│   ├── InterviewPage.tsx
│   ├── KnowledgePage.tsx
│   └── SettingsPage.tsx
│
├── types/
│   ├── copilot.ts
│   ├── attachment.ts
│   └── action.ts
│
├── constants/
│   ├── routes.ts
│   └── actions.ts
│
└── hooks/
    ├── useCopilot.ts
    ├── useCopilotStream.ts
    └── useAttachmentUpload.ts
```

---

# 38. 不建议第一阶段大规模迁移旧目录

当前 InterviewGuide 已存在：

```text
api/
components/
hooks/
pages/
types/
```

第一阶段采取：

> Additive Refactor

即：

```text
新增 Copilot 组件
+
逐步迁移
```

而不是一次性重写整个前端目录。

---

# 39. 前端状态管理

第一阶段不必立刻引入大型状态管理。

可采用：

```text
React State
+
Context
+
Query Cache
```

主要状态：

```text
currentConversation

messages

attachments

streamState

contextPanelState
```

如果后期状态明显复杂，再考虑：

```text
Zustand
```

避免为了三个 boolean 就先建全球状态中心。

---

# 40. Responsive

桌面端为主要目标。

宽屏：

```text
Sidebar
Center
Context Panel
```

中等屏幕：

```text
Sidebar
Center

Context Panel
→ Drawer / collapsible
```

较小屏幕：

```text
Center only

Sidebar
→ Drawer

Context
→ Drawer
```

---

# 41. Empty / Loading / Error State

所有核心区域都必须设计状态。

Conversation：

```text
empty
loading
streaming
failed
```

Attachment：

```text
uploading
parsing
ready
failed
```

Context：

```text
loading
empty
loaded
```

业务卡片：

```text
loading
error
success
```

---

# 42. 视觉风格

保持现有 InterviewGuide 的现代浅色风格，但减少后台系统感。

推荐：

- 白 / 浅灰背景
- 紫蓝作为主要 Accent
- 小范围使用绿色表示良好
- 橙色表示薄弱项
- Round Corner
- 轻 Shadow
- 较多留白
- Lucide React Icons

避免：

- Dashboard 堆满卡片
- 强烈渐变背景
- 营销 Banner
- 订阅 / Pro / Upgrade
- 广告位
- 商业化推荐内容

Career Copilot 是工具，不是 SaaS 落地页。

---

# 43. 禁止商业化 UI

项目明确不加入：

```text
Upgrade
Pricing
Pro Plan
Premium
Subscription
广告
商业推荐
推广 Banner
```

设置页面仅承担：

```text
模型配置
外观
个人设置
系统设置
```

---

# 44. 第一阶段开发顺序

目前 `/copilot` 页面已经建立。

接下来建议：

## Phase 1：Shell 重构

实现：

```text
CopilotSidebar
ConversationList
Copilot Workspace
ContextPanel Skeleton
```

完成后：

```text
旧菜单型 UI
→ Agent + Workspace UI
```

---

## Phase 2：Message Protocol

实现：

```text
CopilotMessage
CopilotBlock
MessageRenderer
MessageBlockRenderer
```

先支持：

```text
markdown
choice
navigation
progress
```

---

## Phase 3：Composer

实现：

```text
Text
Attachment
Action
```

包括：

```text
拖拽上传
文件选择
Attachment Preview
发送
```

---

## Phase 4：Agent Streaming

打通：

```text
React
 ↓
FastAPI
 ↓
SSE
 ↓
React
```

支持：

```text
message_delta
done
error
```

---

## Phase 5：第一个业务 Block

推荐：

```text
PROFILE_QUERY
 ↓
SkillProfileBlock
```

第一次真正跑通：

```text
Agent
→ Tool
→ Java
→ Structured UI
```

---

## Phase 6：Attachment Flow

实现：

```text
File
 ↓
Java Attachment API
 ↓
attachmentId
 ↓
Agent
 ↓
ChoiceBlock
```

例如：

```text
Resume
→ 分析 / 优化 / 面试
```

---

## Phase 7：Context Panel 动态数据

接：

```text
Profile
Preparation
Active Resources
Current Goal
```

右栏从静态骨架升级成真实业务数据。

---

## Phase 8：Business Navigation

Agent 返回：

```text
NavigationBlock
```

打通：

```text
Copilot
→ Interview
→ Report
→ Copilot
```

---

# 45. MVP 验收标准

第一版前端可以认为完成，当以下流程全部成立：

## 流程 A：普通对话

```text
/copilot
→ 输入文本
→ Streaming response
```

## 流程 B：业务查询

```text
“我最近准备得怎么样？”
→ Progress / Profile Card
```

## 流程 C：文件

```text
拖入 Resume
→ 上传成功
→ Agent 识别
→ ChoiceBlock
```

## 流程 D：Action

```text
点击“模拟面试”
→ ACTION_SELECTED
→ Agent
→ NavigationBlock
→ Interview Page
```

## 流程 E：Context

右侧能够正确展示：

```text
当前目标
当前 Resume / JD
能力画像
Preparation
```

---

# 46. 不要过早实现的功能

第一阶段禁止被以下内容拖走：

```text
复杂动画
多主题系统
可拖拽 Layout
多 Agent Avatar
Agent Marketplace
插件商店
MCP UI
复杂 Artifact Editor
复杂 Dashboard
```

这些都不是当前核心。

优先保证：

```text
Conversation
+
Structured UI
+
Tool
+
Context
```

稳定。

---

# 47. 最终前端产品体验

用户第一次进入：

```text
今天想为求职做点什么？

[上传简历]
[添加 JD]
[今日计划]
[模拟面试]
```

用户上传：

```text
JavaResume.pdf
```

Agent：

```text
我识别到这是一份简历。

[分析简历]
[优化简历]
[模拟面试]
```

用户直接输入：

```text
按照 Java 后端实习要求，
直接针对我的项目进行深挖面试。
```

Agent：

```text
已基于当前简历和能力画像准备项目深挖面试。

[开始模拟面试]
```

用户完成面试后返回：

```text
本次综合得分 73。

JVM：
52 → 61

项目表达表现较好，
类加载仍然是主要薄弱点。

[查看报告]
[调整准备计划]
```

右侧同步显示：

```text
Java 后端实习

准备度 72%

Java 82
Spring 80
Redis 74
JVM 61
MQ 61
```

整个产品形成：

```text
Conversation
    ↓
Agent
    ↓
Business Action
    ↓
Business Result
    ↓
Context Update
    ↓
Continue Conversation
```

---

# 48. 核心前端原则

整个重构过程中始终遵守：

```text
1. Agent 是主要入口，但不是唯一 UI。

2. Workspace 管理数据，Copilot 执行任务。

3. Text 是开放意图。

4. File 是上下文资源。

5. Action 是确定性意图。

6. Agent 返回受控 Structured UI，而不是任意 UI 代码。

7. 右侧 Context 必须让 Agent 使用的数据对用户可见。

8. Business Page 继续负责复杂专用交互。

9. 不要把所有功能都塞进聊天页面。

10. 简洁优先于“AI 感”装饰。
```

---

# 49. 最终目标

Career Copilot 前端最终不应给人的感觉是：

> 一个 InterviewGuide 首页加了聊天框。

而应该是：

> 一个以 Career Agent 为操作中枢，以长期用户 Context 为基础，以 Resume / Interview / RAG / Preparation 等业务页面作为执行工具的求职工作台。

最终产品关系：

```text
                  Career Copilot
                        │
          ┌─────────────┼─────────────┐
          │             │             │
      Conversation    Context      Workspace
          │             │             │
          └─────────────┼─────────────┘
                        │
                        ▼
                    Career Agent
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
       Resume       Interview        RAG
          │             │             │
          └─────────────┼─────────────┘
                        ▼
                  Long-term Profile
                        │
                        ▼
                    Preparation
```

前端所有新增功能，都应该服务于这条核心关系，而不是继续增加新的一级功能入口。
