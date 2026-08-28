# Career Copilot 项目设计文档

> Agent-driven Career Preparation Platform  
> 面向求职准备场景的智能职业 Copilot

---

# 1. 项目定位

## 1.1 项目名称

**Career Copilot**

Career Copilot 是一个面向求职者的智能求职准备平台。

与传统求职辅助系统不同，Career Copilot 不再要求用户主动寻找“简历分析”“模拟面试”“知识库”“学习计划”等功能入口。

系统以 **AI Agent 作为统一交互入口**。

用户进入网站后，首页即为 Career Copilot 对话工作台。

用户只需要描述自己的目标或意图，例如：

```text
我准备找 Java 后端实习，帮我看看应该怎么准备。
```

```text
我明天要面试字节 Java 后端，帮我准备一下。
```

```text
我最近复习得怎么样？
```

```text
给我来一场 Redis 专项模拟面试。
```

Career Copilot 根据：

- 用户当前输入
- 历史对话
- 用户简历
- 目标岗位
- 长期能力画像
- 历史模拟面试
- 学习进度
- RAG 知识库

自动理解用户目标，并决定下一步应该：

- 直接回答
- 查询业务数据
- 调用业务 Tool
- 展示结构化卡片
- 请求用户确认
- 跳转到业务功能页面
- 创建学习计划
- 创建模拟面试
- 检索知识库
- 更新长期画像

最终形成一个围绕求职目标持续工作的 **Career Agent**。

---

# 2. 核心产品理念

Career Copilot 的核心设计原则是：

> **用户表达目标，Agent 决定如何使用系统能力完成目标。**

传统系统：

```text
用户
 ↓
寻找功能
 ↓
进入页面
 ↓
填写参数
 ↓
执行业务
```

Career Copilot：

```text
用户
 ↓
表达意图
 ↓
Career Agent
 ↓
理解目标
 ↓
选择 Tool / 页面 / RAG / 数据
 ↓
执行任务
 ↓
返回结果
 ↓
继续规划
```

因此，系统从：

```text
Function Driven
```

转变为：

```text
Intent Driven
```

---

# 3. 最终产品形态

## 3.1 首页即 Agent Workspace

用户登录 Career Copilot 后默认进入：

```text
/copilot
```

首页不再以功能菜单作为视觉中心。

核心页面为：

```text
┌──────────────────────────────────────────────────────┐
│ Career Copilot                                       │
├──────────────────────────────────────────────────────┤
│                                                      │
│ Copilot                                              │
│                                                      │
│ 下午好。                                             │
│ 你当前正在准备 Java 后端实习。                       │
│                                                      │
│ 最近两次模拟面试中 JVM 和 MQ 表现偏弱，              │
│ Redis 表现较稳定。                                   │
│                                                      │
│ [继续今日计划] [开始专项面试]                        │
│                                                      │
│ --------------------------------------------------   │
│                                                      │
│ User                                                 │
│ 我现在复习得怎么样？                                 │
│                                                      │
│ Copilot                                              │
│ 你本周完成了 8 / 12 个任务。                         │
│                                                      │
│ Java          ████████░░ 82                          │
│ Spring        ████████░░ 80                          │
│ Redis         ███████░░░ 74                          │
│ JVM           █████░░░░░ 52                          │
│ MQ            ██████░░░░ 61                          │
│                                                      │
│ 当前最值得继续补的是 JVM。                           │
│                                                      │
│ [开始 JVM 专项训练]                                  │
│                                                      │
├──────────────────────────────────────────────────────┤
│ 输入你的目标或问题...                                │
└──────────────────────────────────────────────────────┘
```

聊天界面不是简单的：

```text
Text → Text
```

而是：

```text
Intent
 ↓
Agent
 ↓
Business / Tool / RAG
 ↓
Structured UI
```

---

# 4. Agent 交互模式

Career Copilot 返回的消息不仅包含文本，还允许返回结构化 Action。

主要支持以下类型。

---

## 4.1 普通回答

适合知识解释或普通咨询。

例如：

```text
User:
Java 后端面试 JVM 一般会问哪些？
```

Agent 判断：

```text
intent = KNOWLEDGE_QA
```

然后调用 RAG：

```text
search_knowledge()
```

最终返回：

```text
JVM 面试主要集中在：

1. JVM 内存结构
2. GC
3. 类加载机制
4. 对象生命周期
5. JVM 调优

结合你当前画像来看，你在 GC 和类加载方面比较薄弱。
```

---

# 5. Agent Action

Agent 除了文本回答之外，可以生成系统 Action。

例如：

```json
{
  "type": "NAVIGATION",
  "target": "/interview/create",
  "params": {
    "skill": "java-backend",
    "focus": ["JVM"]
  }
}
```

前端收到 Action 后，不立即执行。

而是展示：

```text
Copilot：

根据你最近的表现，我建议进行一次 JVM 专项模拟面试。

[开始模拟面试]
```

用户点击后：

```text
React Router
     ↓
/interview/create
```

并自动携带：

```text
skill=java-backend
focus=JVM
```

---

# 6. Action 类型设计

Career Copilot 最终至少支持以下 Action。

## 6.1 NAVIGATION

请求跳转到业务页面。

```json
{
  "type": "NAVIGATION",
  "target": "/interview/create",
  "label": "开始模拟面试"
}
```

---

## 6.2 TOOL_CONFIRMATION

Agent 准备执行具有业务副作用的 Tool 时请求用户确认。

例如：

```text
我可以根据这份 JD 为你创建一份 7 天准备计划。

[创建计划]
[暂时不用]
```

对应：

```json
{
  "type": "TOOL_CONFIRMATION",
  "tool": "create_preparation_plan",
  "arguments": {
    "jobId": 1024
  }
}
```

---

## 6.3 BUSINESS_CARD

展示业务数据。

例如用户询问：

```text
我最近复习得怎么样？
```

Agent 调用：

```text
get_preparation_progress
get_skill_profile
```

返回：

```json
{
  "type": "BUSINESS_CARD",
  "component": "PreparationProgressCard",
  "data": {}
}
```

前端渲染真实 React 组件。

---

## 6.4 CHART

允许 Agent 返回图表数据。

例如：

```text
User:
我最近一个月面试表现怎么样？
```

Agent：

```text
get_interview_performance_trend()
```

返回：

```json
{
  "type": "CHART",
  "chartType": "line",
  "title": "近 30 天模拟面试表现",
  "data": []
}
```

前端负责渲染。

LLM 不生成图表 HTML。

---

## 6.5 TASK_LIST

返回准备计划。

例如：

```text
今天应该学什么？
```

返回：

```text
今日任务

✓ JVM 内存模型
□ GC 算法
□ RabbitMQ 消息可靠性
□ 30 分钟模拟面试

今日进度 25%
```

---

## 6.6 INTERRUPT

Agent 缺少关键信息时暂停执行。

例如：

```text
你目前保存了两份简历：

1. Java 后端实习.pdf
2. AI 应用开发.pdf

这次使用哪一份？

[Java 后端实习]
[AI 应用开发]
```

选择之后恢复原 Agent Run。

---

# 7. Agent 核心架构

整体架构：

```text
                         React
                           │
                           ▼
                 Career Copilot UI
                           │
                           ▼
                    Spring Boot
                 Business Backend
                           │
            ┌──────────────┼──────────────┐
            │              │              │
          Resume           Job        Interview
            │              │              │
       KnowledgeBase     Profile      Preparation
            │              │              │
            └──────────────┼──────────────┘
                           │
                     Agent Tool API
                           │
                           ▼
                 Python Agent Service
                           │
                  LangGraph Runtime
                           │
       ┌───────────────────┼───────────────────┐
       │                   │                   │
    Planner              Tools               Memory
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │
                           ▼
                          LLM
```

---

# 8. Java 与 Python 职责边界

这是 Career Copilot 最重要的架构约束之一。

## 8.1 Spring Boot

Spring Boot 是整个系统的业务核心。

负责：

- 用户
- 权限
- 简历
- 岗位
- 模拟面试
- 面试报告
- 知识库
- 学习计划
- 长期画像
- 文件
- PostgreSQL
- pgvector
- Redis
- 业务事务
- 数据一致性
- Tool 权限校验

Spring Boot 是：

```text
System of Record
```

所有真实业务数据最终由 Java 管理。

---

## 8.2 Agent Service

Python Agent Service 只负责：

- 意图理解
- Agent State
- Planning
- Tool Selection
- Tool Calling
- Workflow
- Checkpoint
- Human-in-the-loop
- Memory Context 构建
- 决策
- Replan

Python 不直接修改核心业务数据库。

正确：

```text
Agent
 ↓
Tool
 ↓
Spring Boot API
 ↓
Service
 ↓
Repository
 ↓
PostgreSQL
```

禁止：

```text
Agent
 ↓
SQL
 ↓
PostgreSQL
```

---

# 9. Career Agent

V1 和最终形态均以一个主 Agent 为核心：

```text
CareerAgent
```

不为了 Multi-Agent 而 Multi-Agent。

CareerAgent 负责理解：

```text
用户现在到底想干什么？
```

然后选择对应能力。

---

# 10. Agent 主循环

整体生命周期：

```text
START
  │
  ▼
Understand Intent
  │
  ▼
Load Context
  │
  ▼
Need Tool?
  │
  ├──────── No ───────→ Generate Response
  │
 Yes
  │
  ▼
Plan
  │
  ▼
Execute Tool
  │
  ▼
Observe Result
  │
  ▼
Need More Action?
  │
  ├── Yes ──→ Execute Tool
  │
  ├── Need User ──→ INTERRUPT
  │
  └── No
       │
       ▼
Generate Structured Response
       │
       ▼
Memory Update
       │
       ▼
END
```

---

# 11. Intent Router

CareerAgent 首先识别用户意图。

主要 Intent：

```text
GENERAL_CHAT

KNOWLEDGE_QA

RESUME_ANALYSIS

JOB_ANALYSIS

JOB_MATCH

PREPARATION_QUERY

PREPARATION_PLAN

INTERVIEW_CREATE

INTERVIEW_REVIEW

PROFILE_QUERY

PROFILE_ANALYSIS

KNOWLEDGE_SEARCH

NAVIGATION

COMPLEX_GOAL
```

例如：

```text
“我最近复习得怎么样？”
```

识别：

```text
PREPARATION_QUERY
```

执行：

```text
get_preparation_progress
get_skill_profile
```

---

例如：

```text
“给我来场 JVM 面试。”
```

识别：

```text
INTERVIEW_CREATE
```

Agent：

```text
get_skill_profile
get_interview_history
```

然后决定：

```text
focus = JVM
difficulty = MEDIUM
```

最后：

```text
是否开始 JVM 专项模拟面试？

[开始面试]
```

---

例如：

```text
“我十天后准备投 Java 后端岗位，帮我准备。”
```

识别：

```text
COMPLEX_GOAL
```

进入完整 Agent Workflow。

---

# 12. Tool System

所有业务能力均通过 Tool 暴露给 Agent。

## Resume Tools

```text
list_resumes

get_resume

get_resume_analysis

analyze_resume

compare_resumes
```

---

## Job Tools

```text
create_job

parse_job_description

get_job

analyze_job

match_resume_to_job
```

---

## Preparation Tools

```text
get_preparation_plan

get_preparation_progress

create_preparation_plan

update_preparation_plan

complete_task

get_today_tasks
```

---

## Interview Tools

```text
get_interview_history

get_interview_report

get_interview_performance

create_interview

recommend_interview
```

---

## Profile Tools

```text
get_user_profile

get_skill_profile

get_skill_gap

get_recent_weaknesses

update_profile_evidence
```

---

## Knowledge Tools

```text
search_knowledge

get_knowledge_document

recommend_learning_material
```

---

# 13. Tool 权限等级

Tool 分为三级。

## READ

Agent 可以直接调用：

```text
get_resume
get_skill_profile
get_preparation_progress
search_knowledge
```

---

## SAFE_WRITE

允许 Agent 自动执行，但记录审计日志。

例如：

```text
save_agent_artifact
update_agent_progress
```

---

## CONFIRM_WRITE

必须 Human-in-the-loop。

例如：

```text
create_preparation_plan
modify_resume
delete_plan
create_interview
overwrite_profile
```

流程：

```text
Agent
 ↓
Prepare Action
 ↓
Interrupt
 ↓
User Confirm
 ↓
Execute Tool
```

---

# 14. 长期用户画像

Career Copilot 必须保留并强化长期画像能力。

长期画像不是聊天记录总结。

它应该是一个持续演化的结构化用户模型。

---

# 15. Profile 结构

例如：

```json
{
  "targetRoles": [
    "Java Backend",
    "AI Application Engineer"
  ],

  "skills": {
    "Java": 0.82,
    "Spring": 0.80,
    "MySQL": 0.77,
    "Redis": 0.74,
    "JVM": 0.52,
    "RabbitMQ": 0.61
  },

  "strengths": [
    "Spring Boot",
    "Redis",
    "项目实践"
  ],

  "weaknesses": [
    "JVM",
    "MQ 原理",
    "系统设计"
  ],

  "preferences": {
    "learningStyle": "practice-first",
    "targetCity": "Hangzhou"
  }
}
```

---

# 16. Profile Evidence

画像不能完全由 LLM 主观生成。

每一个 Skill 应该具有 Evidence。

例如：

```text
JVM
Score: 52

Evidence
────────────────────

Resume
没有明显 JVM 项目实践

Interview #102
Score 48

Interview #117
Score 56

Learning Tasks
3 / 8 completed

Last Updated
2026-08-24
```

最终：

```text
Profile
=
Resume Evidence
+
Interview Evidence
+
Learning Evidence
+
Agent Analysis
```

---

# 17. 三层 Memory

Career Copilot 使用三层 Memory。

## Working Memory

当前 Agent Run 使用。

例如：

```text
goal
messages
plan
current_step
tool_results
```

生命周期：

```text
一次 Agent Run
```

---

## Episodic Memory

记录用户经历。

例如：

```text
2026-08-20
完成 JVM 专项面试

JVM 评分 54

主要问题：
GC 表达不完整
类加载理解不足
```

---

## Semantic / Profile Memory

用户长期稳定画像。

例如：

```text
Java Backend

Java      GOOD
Spring    GOOD
Redis     GOOD
JVM       WEAK
MQ        MEDIUM
```

Agent 每次运行时根据需要加载。

禁止每次把全部用户历史塞入 Context。

---

# 18. RAG 知识库

保留原 Interview Guide 项目的 RAG 能力。

RAG 继续使用：

```text
PostgreSQL
+
pgvector
```

负责：

- 文档上传
- 文档解析
- Chunk
- Embedding
- Vector Search
- Query Rewrite
- TopK
- Similarity Threshold
- 引用来源
- 流式问答

但在 Career Copilot 中，RAG 的定位改变为：

> **Agent Tool**

即：

```text
Career Agent
     │
     ▼
Does this task require knowledge?
     │
    Yes
     │
     ▼
search_knowledge
     │
     ▼
RAG Engine
     │
     ▼
Knowledge Context
     │
     ▼
Agent
```

不再让所有对话默认经过 RAG。

---

# 19. Agent + RAG 场景

用户：

```text
我今天想补 JVM。
```

Agent 查询画像：

```text
JVM = 52
```

查询历史：

```text
GC = Weak
ClassLoader = Weak
```

调用：

```text
search_knowledge(
    query="JVM GC 类加载 Java 面试"
)
```

得到知识库内容。

然后返回：

```text
根据你最近两次模拟面试，你目前 JVM 最主要的问题是：

1. GC
2. 类加载机制

我建议今天按下面顺序：

[GC 基础]
[垃圾收集器]
[类加载机制]
[专项模拟面试]
```

这就是：

```text
Profile
+
Memory
+
RAG
+
Agent
```

而不是普通 RAG Chat。

---

# 20. Preparation Plan

Career Copilot 需要新增 Preparation 模块。

核心实体：

```text
PreparationPlan
```

结构：

```text
Plan

Goal:
字节 Java 后端实习

Deadline:
2026-09-10

Tasks:

Day 1
├── JVM GC
└── JVM Memory

Day 2
├── RabbitMQ
└── Redis

Day 3
└── Mock Interview
```

任务状态：

```text
TODO

IN_PROGRESS

COMPLETED

SKIPPED
```

---

# 21. Agent 自动调整计划

Preparation Plan 不是一次生成后永久不变。

例如：

```text
第一次计划：

JVM
Redis
MQ
Spring
Interview
```

执行模拟面试后：

```text
Spring 86
Redis 82
JVM 51
MQ 58
```

Agent 执行：

```text
Observe
 ↓
Profile Update
 ↓
Gap Analysis
 ↓
Replan
```

新的计划：

```text
JVM
JVM
MQ
Interview
Project Deep Dive
```

形成真正的：

```text
Plan
 ↓
Act
 ↓
Observe
 ↓
Evaluate
 ↓
Replan
```

---

# 22. 首页动态业务组件

Copilot 消息允许嵌入业务组件。

例如：

## Progress Card

```text
本周学习进度

████████░░ 67%

8 / 12 Tasks
```

---

## Skill Radar / Skill Card

```text
Java       82
Spring     80
Redis      74
JVM        52
MQ         61
```

---

## Interview Trend

```text
85 ┤                     ●
80 ┤                ●
75 ┤          ●
70 ┤     ●
65 ┤ ●
   └────────────────────────
      1   2   3   4   5
```

---

## Resume Match

```text
Java Backend Intern

Match Score

78%

强匹配
Spring Boot
Redis
MySQL

主要差距
JVM
Linux
MQ
```

---

## Preparation Plan

```text
今天

✓ Redis 持久化
□ JVM GC
□ RabbitMQ Confirm
□ 30 min Mock Interview
```

---

# 23. 前端消息协议

后端返回统一 Chat Message。

例如：

```json
{
  "messageId": "msg_1024",

  "role": "assistant",

  "content": "你目前 JVM 仍然是最明显的薄弱项。",

  "blocks": [
    {
      "type": "skill_profile",
      "data": {}
    },
    {
      "type": "action",
      "action": {
        "type": "NAVIGATION",
        "label": "开始 JVM 专项面试",
        "target": "/interview/create"
      }
    }
  ]
}
```

前端通过：

```text
block.type
```

决定 React Component。

例如：

```text
text
→ MarkdownBlock

skill_profile
→ SkillProfileCard

progress
→ ProgressCard

chart
→ ChartBlock

preparation_plan
→ PreparationPlanCard

action
→ ActionButton

confirmation
→ ConfirmationCard
```

---

# 24. 页面结构

最终主要路由：

```text
/copilot
```

Career Copilot 首页。

---

```text
/preparation
```

完整学习计划。

---

```text
/profile
```

长期能力画像。

---

```text
/resumes
```

简历管理。

---

```text
/interviews
```

模拟面试。

---

```text
/knowledge
```

知识库。

---

```text
/jobs
```

目标岗位。

---

```text
/settings
```

模型和系统配置。

---

# 25. 首页 Sidebar

Sidebar 保留，但弱化功能入口属性。

推荐：

```text
Career Copilot

＋ New Chat

Chats
────────────────
准备字节 Java 实习
优化 Java 简历
Redis 专项复习

Workspace
────────────────
Preparation
Profile
Resumes
Interviews
Knowledge

Settings
```

默认永远进入：

```text
Career Copilot
```

---

# 26. Agent Run

复杂任务创建 Agent Run。

例如：

```text
Run #1024

Goal
准备 Java 后端实习

Status
RUNNING
```

---

# 27. Agent State

核心 State：

```python
class CareerAgentState(TypedDict):

    run_id: str

    user_id: int

    messages: list

    intent: str

    goal: str | None

    plan: list

    current_step: int

    context: dict

    tool_results: list

    status: str

    pending_action: dict | None

    final_response: dict | None
```

State 不直接承载大量业务数据。

真实 Resume、Job、Interview 等仍存在 Java Backend。

---

# 28. Agent 状态

支持：

```text
CREATED

RUNNING

WAITING_USER

WAITING_ASYNC

COMPLETED

FAILED

CANCELLED
```

---

# 29. Checkpoint

LangGraph 使用持久化 Checkpoint。

保证：

```text
Agent Run
 ↓
执行到 Step 4
 ↓
用户关闭网页
 ↓
第二天重新进入
 ↓
从 Step 4 恢复
```

而不是重新执行整个 Agent。

---

# 30. Artifact

Agent 在执行过程中会产生 Artifact。

例如：

```text
Agent Run

├── JobAnalysis
├── ResumeAnalysis
├── JobMatchReport
├── SkillGapReport
├── PreparationPlan
└── InterviewRecommendation
```

Artifact 既可以展示给用户，也可以提供后续 Agent 使用。

---

# 31. Agent 数据模型

## agent_run

```text
id

user_id

conversation_id

goal

intent

status

started_at

finished_at
```

---

## agent_step

```text
id

run_id

step_name

tool_name

status

input

output

started_at

finished_at
```

---

## agent_artifact

```text
id

run_id

type

resource_id

content

created_at
```

---

# 32. Conversation

用户可以创建多个 Conversation。

例如：

```text
准备 Java 后端秋招

优化 CareerAI 项目表达

Redis 面试复习
```

Conversation 是交互上下文。

Agent Run 是一次实际任务执行。

关系：

```text
Conversation

├── Message
├── Message
├── Agent Run
├── Message
└── Agent Run
```

---

# 33. 长任务异步执行

某些任务可能持续较长时间：

```text
Resume Analysis

Knowledge Vectorization

Interview Evaluation

Large Preparation Planning
```

采用：

```text
Agent
 ↓
Start Task
 ↓
Java Async Task
 ↓
WAITING_ASYNC
 ↓
Task Complete
 ↓
Resume Agent
```

禁止 Agent 长时间阻塞等待。

---

# 34. Agent Observability

系统提供 Agent Run Detail。

开发模式可查看：

```text
Run #1024

Intent
COMPLEX_GOAL

Plan
──────────────────

✓ load_profile
✓ load_resume
✓ analyze_job
✓ calculate_gap
● create_plan

Tool Calls
──────────────────

get_profile        31ms
get_resume         47ms
match_job          621ms
search_knowledge   384ms

LLM Calls
──────────────────

Intent Router
Planner
Final Response

Tokens
8,420

Duration
4.8s
```

---

# 35. Evaluation

Career Copilot 应支持 Agent Evaluation。

核心指标：

```text
Intent Accuracy

Tool Selection Accuracy

Task Completion Rate

Invalid Tool Call Rate

Plan Quality

Profile Accuracy

RAG Retrieval Quality

Average Token Usage

Average Latency
```

维护 Agent Eval Dataset。

例如：

```json
{
  "input": "给我来一场 JVM 面试",

  "expectedIntent": "INTERVIEW_CREATE",

  "expectedTools": [
    "get_skill_profile",
    "create_interview"
  ]
}
```

---

# 36. 核心业务闭环

Career Copilot 最核心的闭环为：

```text
User Goal
    │
    ▼
Career Agent
    │
    ▼
Resume + Job
    │
    ▼
Gap Analysis
    │
    ▼
Preparation Plan
    │
    ▼
Learning / RAG
    │
    ▼
Mock Interview
    │
    ▼
Evaluation
    │
    ▼
Long-term Profile
    │
    ▼
Replan
    │
    └─────────────────→ Career Agent
```

这是整个项目最重要的一条业务链路。

---

# 37. 典型完整场景

用户第一次进入 Career Copilot。

```text
User:

我准备投 Java 后端实习，这是 JD，
帮我看看接下来应该怎么准备。
```

Career Agent：

```text
Intent:

COMPLEX_GOAL
```

执行：

```text
parse_job_description

list_resumes

get_resume_analysis

match_resume_to_job

get_skill_profile

get_interview_history
```

形成：

```text
Job Requirements

Resume Capability

Historical Performance

Long-term Profile
```

Agent 完成 Gap Analysis：

```text
主要能力差距：

JVM
RabbitMQ
Linux
项目表达
```

Agent：

```text
根据岗位要求和你目前的能力画像，我建议制定一份 7 天准备计划。

优先级：

1. JVM
2. RabbitMQ
3. 项目表达
4. 模拟面试

[创建 7 天计划]
```

用户点击。

Agent：

```text
create_preparation_plan
```

创建成功：

```text
计划已创建。

今天建议完成：

□ JVM 内存结构
□ GC
□ JVM 专项模拟面试

[开始学习]
```

用户：

```text
GC 我不太会，先给我讲讲。
```

Agent：

```text
search_knowledge
```

调用原有 RAG。

完成学习。

之后用户：

```text
直接面试吧。
```

Agent：

```text
get_skill_profile

create_interview
```

返回：

```text
我已经根据你的薄弱点准备了一场：

Java Backend
JVM Focus
Medium Difficulty
30 Minutes

[进入模拟面试]
```

用户完成面试。

Interview Evaluation：

```text
JVM 52 → 63
```

Career Copilot 更新：

```text
Episodic Memory

Profile Evidence

Skill Profile
```

Agent：

```text
这次 JVM 提升比较明显。

GC 已经从薄弱提升到中等，
但类加载仍然存在明显问题。

我已经调整明天的学习重点。
```

整个过程不要求用户理解：

```text
Resume Module

RAG Module

Preparation Module

Interview Module
```

用户只需要：

> **和 Career Copilot 交流。**

---

# 38. 项目最终技术定位

Career Copilot 最终不是：

```text
Spring Boot + LLM API
```

也不是：

```text
ChatGPT Clone
```

更不是：

```text
RAG Demo
```

项目最终定位：

> **Career Copilot 是一个面向求职准备场景的 Stateful Agent Application。系统以 Career Agent 作为统一交互入口，根据用户意图、简历、岗位信息、长期能力画像和历史行为自主选择业务 Tool，通过 RAG、模拟面试、学习计划和岗位分析等能力完成任务，并利用 LangGraph Checkpoint、Human-in-the-loop、Structured UI、Long-term Memory 和 Evaluation 构建可恢复、可控、可解释、可持续演化的 Agent 工作流。**

---

# 39. 项目核心技术亮点

最终项目重点突出：

### Agent Orchestration

```text
LangGraph

State

Planning

Routing

Tool Calling

Checkpoint

Interrupt

Replan
```

### Agent Engineering

```text
Structured Tool

Idempotency

Human-in-the-loop

Async Task

Agent Artifact

Agent Observability

Agent Evaluation
```

### Memory

```text
Working Memory

Episodic Memory

Semantic Profile
```

### RAG

```text
PostgreSQL

pgvector

Embedding

Query Rewrite

Vector Search

Knowledge Tool
```

### Backend

```text
Java

Spring Boot

PostgreSQL

Redis

Spring AI

Business Tool API
```

### Frontend

```text
React

Agent Workspace

Structured Message

Dynamic Business Component

Action Protocol

Streaming Response
```

---

# 40. 最终目标

Career Copilot 最终希望实现的体验不是：

> 用户打开一个求职网站，然后寻找自己需要的功能。

而是：

> 用户拥有一个长期陪伴自己的 Career Copilot。

Career Copilot 知道：

```text
你是谁

你的目标是什么

你的简历是什么

你准备过哪些岗位

你参加过哪些模拟面试

你擅长什么

你薄弱什么

你最近在学什么

你的准备进度如何
```

用户只需要说：

```text
“我接下来该干什么？”
```

Career Copilot 能够基于真实业务数据回答：

```text
你距离 Java 后端岗位的主要差距目前集中在 JVM 和 MQ。

本周计划已经完成 67%。

今晚最值得完成的是：

1. JVM GC
2. RabbitMQ 可靠消息

完成后建议进行一次 30 分钟专项模拟面试。

[继续今日计划]
```

这就是 Career Copilot 的最终产品形态。