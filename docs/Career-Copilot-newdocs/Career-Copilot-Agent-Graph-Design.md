# Career Copilot Agent Graph 设计文档

> 文档用途：Career Copilot Agent Runtime 的 Graph 设计与开发基线  
> 适用范围：`agent-service/`  
> 核心技术：FastAPI + LangGraph + Pydantic + httpx  
> 架构原则：Java 负责业务事实，Python 负责 Agent 编排

---

# 1. 设计目标

Career Copilot 的 Agent 不应该成为一个“什么都自己做”的万能服务。

整体边界：

```text
React
  ↓
Python Career Agent
  ↓
Tool
  ↓
CareerBackendClient
  ↓
Java Spring Boot
  ↓
Service
  ↓
Repository
```

Python 主要负责：

```text
输入理解
意图识别
上下文选择
Tool 调用
Workflow 编排
Human-in-the-loop
Checkpoint / Resume
Structured Response
```

Java 主要负责：

```text
Resume
Interview
Knowledge Base / RAG
Profile
Preparation
Job
File
数据库
事务
权限
业务状态
```

核心原则：

> Graph 编排“什么时候调用什么能力，以及调用完成后下一步是什么”。

Graph 不负责重新实现已有业务能力。

---

# 2. Graph 总体规划

Career Copilot 第一阶段只需要两个核心 Graph：

```text
1. Copilot Turn Graph
2. Resume Optimization Subgraph
```

后期增加：

```text
3. Goal Execution Subgraph
```

暂时不要创建：

```text
Memory Graph
RAG Graph
Interview Graph
File Graph
Profile Graph
Preparation Graph
```

除非未来业务复杂度真的需要。

---

# 3. Graph 层级关系

整体：

```text
                         Copilot Turn Graph
                                │
                         normalize_input
                                │
                         resolve_context
                                │
                           route_intent
                                │
          ┌─────────────────────┼─────────────────────┐
          │                     │                     │
          ▼                     ▼                     ▼
      Direct Answer       Business Tools        Attachment Flow
          │                     │                     │
          └──────────────┬──────┴──────────────┬──────┘
                         │                     │
                         ▼                     ▼
                Resume Optimization       Complex Goal
                    Subgraph               Subgraph
                         │                     │
                         └──────────┬──────────┘
                                    ▼
                              build_response
                                    │
                                    ▼
                                   END
```

主 Graph 是所有 `/copilot` 输入的统一入口。

Subgraph 只负责复杂、状态化、需要多步执行的任务。

---

# 4. Copilot Turn Graph

## 4.1 职责

Copilot Turn Graph 处理用户的一次输入。

一次输入可能来自：

```text
Text
File
Action
```

主 Graph 负责回答：

> 用户这一次输入应该进入哪个处理流程？

---

# 5. Copilot Turn Graph V1

推荐第一版：

```text
START
  ↓
normalize_input
  ↓
resolve_context
  ↓
route_intent
  │
  ├── GENERAL_CHAT
  │      ↓
  │   direct_answer
  │
  ├── KNOWLEDGE_QA
  │      ↓
  │   knowledge_tool
  │
  ├── PROFILE_QUERY
  │      ↓
  │   business_tools
  │
  ├── PREPARATION_QUERY
  │      ↓
  │   business_tools
  │
  ├── RESUME_QUERY
  │      ↓
  │   business_tools
  │
  ├── INTERVIEW_QUERY
  │      ↓
  │   business_tools
  │
  ├── INTERVIEW_CREATE
  │      ↓
  │   create_interview
  │
  ├── ATTACHMENT_RECEIVED
  │      ↓
  │   attachment_flow
  │
  ├── RESUME_OPTIMIZATION
  │      ↓
  │   Resume Optimization Subgraph
  │
  ├── ACTION_SELECTED
  │      ↓
  │   execute_action
  │
  └── COMPLEX_GOAL
         ↓
      Goal Execution Subgraph

所有分支
  ↓
build_response
  ↓
END
```

---

# 6. 输入模型

Career Copilot 统一支持：

```text
Text
File
Action
```

前端请求建议：

```json
{
  "conversationId": "conv_1024",
  "message": "按照这份 JD 优化简历",
  "attachments": [
    {
      "attachmentId": 2048
    }
  ],
  "action": null
}
```

Action 示例：

```json
{
  "conversationId": "conv_1024",
  "message": null,
  "attachments": [],
  "action": {
    "type": "ACTION_SELECTED",
    "action": "OPTIMIZE_RESUME",
    "payload": {
      "attachmentId": 2048
    }
  }
}
```

---

# 7. CareerAgentState

第一版 State 保持精简。

```python
class CareerAgentState(TypedDict):
    # Trusted Runtime
    conversation_id: str
    user_id: int

    # Input
    messages: list
    input_type: str
    attachments: list
    action: dict | None

    # Active Context References
    active_resume_id: int | None
    active_job_id: int | None
    active_plan_id: int | None

    # Routing
    intent: str | None

    # Tool Results
    tool_results: list

    # Output
    response: dict | None

    # Run Status
    status: str
```

不要在 State 中直接保存：

```text
完整 PDF
完整 Resume 文本
完整 JD
知识库所有 Chunk
数据库 Entity
httpx Client
LLM Client
Repository
```

State 应尽量：

```text
小
可序列化
可 Checkpoint
可恢复
```

---

# 8. RunStatus

推荐：

```python
class RunStatus(str, Enum):
    RUNNING = "RUNNING"
    WAITING_USER = "WAITING_USER"
    WAITING_ASYNC = "WAITING_ASYNC"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
```

前端映射：

```text
RUNNING
→ 正在处理

WAITING_USER
→ 等待用户选择

WAITING_ASYNC
→ 后台任务处理中

COMPLETED
→ 已完成

FAILED
→ 执行失败
```

---

# 9. normalize_input Node

## 职责

统一前端输入格式。

输入：

```text
Text
File
Action
```

输出：

```text
input_type
message
attachment_refs
action
```

示例：

```text
message != null
attachments empty
→ TEXT
```

```text
message != null
attachments not empty
→ TEXT_WITH_ATTACHMENT
```

```text
message == null
attachments not empty
→ ATTACHMENT
```

```text
action != null
→ ACTION
```

该节点使用确定性代码，不调用 LLM。

---

# 10. resolve_context Node

## 职责

解析当前 Conversation 中的活动业务资源。

例如：

```text
active_resume_id
active_job_id
active_plan_id
```

用户可能输入：

```text
按照这份 JD 优化
```

```text
继续刚才的计划
```

```text
根据我的当前简历
```

此节点负责恢复 Conversation Context。

推荐只加载 Reference：

```text
resumeId
jobId
planId
```

真正需要业务内容时，再通过 Tool 获取。

---

# 11. route_intent Node

## Intent 枚举

第一版建议：

```python
class Intent(str, Enum):
    GENERAL_CHAT = "GENERAL_CHAT"

    KNOWLEDGE_QA = "KNOWLEDGE_QA"

    PROFILE_QUERY = "PROFILE_QUERY"
    PREPARATION_QUERY = "PREPARATION_QUERY"

    RESUME_QUERY = "RESUME_QUERY"
    RESUME_OPTIMIZATION = "RESUME_OPTIMIZATION"

    INTERVIEW_QUERY = "INTERVIEW_QUERY"
    INTERVIEW_CREATE = "INTERVIEW_CREATE"

    ATTACHMENT_RECEIVED = "ATTACHMENT_RECEIVED"

    COMPLEX_GOAL = "COMPLEX_GOAL"
```

---

# 12. Intent Routing 原则

优先使用确定性规则，再使用 LLM。

例如：

```text
存在 action
→ 不进行 Intent LLM 分类
```

```text
只有附件，没有文本
→ ATTACHMENT_RECEIVED
```

```text
明确 Action = OPTIMIZE_RESUME
→ RESUME_OPTIMIZATION
```

只有开放自然语言输入才进入 Intent Router。

---

# 13. Intent Router 输出

使用结构化输出。

```python
class IntentDecision(BaseModel):
    intent: Intent
    confidence: float
    reason: str | None = None
```

例如：

```json
{
  "intent": "PREPARATION_QUERY",
  "confidence": 0.94
}
```

不要依赖字符串解析：

```text
“我认为用户应该是在询问准备计划……”
```

---

# 14. General Chat

用户：

```text
Java 的 HashMap 为什么线程不安全？
```

如果判断无需业务上下文、无需 RAG：

```text
GENERAL_CHAT
 ↓
direct_answer
 ↓
LLM
 ↓
build_response
```

---

# 15. Knowledge QA

用户：

```text
结合知识库讲一下 G1 的 Region。
```

流程：

```text
KNOWLEDGE_QA
 ↓
search_knowledge Tool
 ↓
Java KnowledgeBase Service
 ↓
pgvector
 ↓
Tool Result
 ↓
build_response
```

RAG 仍由 Java 管理。

Python 不重新实现：

```text
Chunk
Embedding
Vector Store
Rerank
```

---

# 16. Business Query

例如：

```text
我最近准备得怎么样？
```

流程：

```text
PREPARATION_QUERY
 ↓
get_preparation_progress
get_skill_profile
 ↓
build_response
```

第一阶段可以使用固定 Intent → Tool Mapping。

例如：

```python
INTENT_TOOL_MAPPING = {
    Intent.PROFILE_QUERY: [
        "get_skill_profile"
    ],

    Intent.PREPARATION_QUERY: [
        "get_preparation_progress",
        "get_skill_profile"
    ],

    Intent.INTERVIEW_QUERY: [
        "get_interview_history"
    ]
}
```

第一版不要过早开放无限 Tool Loop。

---

# 17. Attachment Flow

## 17.1 文件上传边界

上传不经过 Graph。

正确：

```text
React
 ↓
Java Attachment API
 ↓
File Storage
 ↓
Tika
 ↓
Attachment
 ↓
attachmentId
 ↓
Career Agent
```

---

## 17.2 Attachment Graph Branch

```text
ATTACHMENT_RECEIVED
      ↓
get_attachment_context
      ↓
document_type
      │
      ├── RESUME
      │      ↓
      │   Resume ChoiceBlock
      │
      ├── JOB_DESCRIPTION
      │      ↓
      │   Job ChoiceBlock
      │
      ├── KNOWLEDGE_DOCUMENT
      │      ↓
      │   Knowledge ChoiceBlock
      │
      └── UNKNOWN
             ↓
          Ask User
```

---

# 18. Attachment 类型

第一版：

```text
RESUME
JOB_DESCRIPTION
KNOWLEDGE_DOCUMENT
INTERVIEW_NOTE
UNKNOWN
```

---

# 19. Attachment Choice 示例

Resume：

```text
我识别到这是一份简历。

[分析简历]
[优化简历]
[模拟面试]
[岗位匹配]
```

Job：

```text
我识别到这是一份岗位描述。

[分析岗位]
[简历匹配]
[生成准备计划]
[岗位模拟面试]
```

Knowledge：

```text
我识别到这是一份学习资料。

[加入知识库]
[总结内容]
[生成复习题]
```

---

# 20. Structured Response

Agent 不只返回字符串。

推荐：

```json
{
  "content": "我识别到这是一份简历。",
  "blocks": [
    {
      "type": "choice",
      "data": {}
    }
  ]
}
```

第一版 Block：

```text
markdown
choice
navigation
confirmation
progress
skill_profile
resume_analysis
resume_optimization
task_list
```

---

# 21. Action 处理

按钮点击不重新转换成自然语言。

例如：

```text
[优化简历]
```

前端发送：

```json
{
  "type": "ACTION_SELECTED",
  "action": "OPTIMIZE_RESUME",
  "payload": {
    "resumeId": 1001
  }
}
```

主 Graph：

```text
ACTION_SELECTED
 ↓
execute_action
 ↓
根据 action 进入确定流程
```

核心原则：

> LLM 负责开放输入，Action 负责确定性输入。

---

# 22. Resume Optimization Subgraph

这是第一阶段最重要的 Stateful Workflow。

适用场景：

```text
帮我优化当前简历
```

```text
按照 Java 后端方向优化
```

```text
按照这份 JD 修改我的简历
```

---

# 23. Resume Optimization Graph

推荐：

```text
START
  ↓
resolve_resume
  ↓
determine_mode
  ↓
load_resume
  ↓
load_optional_job
  ↓
load_profile
  ↓
load_evidence
  ↓
generate_patches
  ↓
validate_patches
  ↓
build_diff
  ↓
interrupt
  │
  │ User accepts / rejects patches
  ▼
apply_selected_patches
  ↓
create_resume_version
  ↓
build_result
  ↓
END
```

---

# 24. Resume Optimization 模式

```text
GENERAL

TARGET_DIRECTION

JD_TARGETED
```

---

## GENERAL

输入：

```text
Resume
Profile
Evidence
```

目标：

```text
表达优化
内容压缩
项目描述优化
技术职责强化
```

---

## TARGET_DIRECTION

输入：

```text
Resume
Profile
Evidence
Target Direction
```

例如：

```text
Java 后端实习
AI 应用开发
```

---

## JD_TARGETED

输入：

```text
Resume
Job Description
Profile
Evidence
```

目标：

```text
岗位关键词匹配
项目排序
技能排序
Gap 提示
岗位定向表达
```

---

# 25. ResumeOptimizationState

```python
class ResumeOptimizationState(TypedDict):
    resume_id: int

    optimization_mode: str

    target_direction: str | None
    job_id: int | None

    resume: dict | None
    job: dict | None
    profile: dict | None
    evidence: list

    patches: list
    selected_patch_ids: list

    result_version_id: int | None

    status: str
```

主 State 不保存所有 Resume Optimization 细节。

复杂 Workflow 使用独立 State。

---

# 26. resolve_resume Node

负责确定目标 Resume。

优先级：

```text
Action payload.resumeId

当前 Conversation activeResumeId

唯一可用 Resume

用户选择
```

如果存在多份 Resume，且无法判断：

```text
WAITING_USER
```

返回 ChoiceBlock。

---

# 27. determine_mode Node

根据用户输入判断：

```text
GENERAL

TARGET_DIRECTION

JD_TARGETED
```

例如：

```text
帮我优化一下
→ GENERAL
```

```text
按照 AI 应用开发优化
→ TARGET_DIRECTION
```

```text
按照这份 JD 优化
→ JD_TARGETED
```

---

# 28. load_resume Node

通过 Tool：

```text
get_resume
```

调用：

```text
Python
 ↓
CareerBackendClient
 ↓
Java /internal/agent/resumes/{id}
```

获取结构化 Resume。

不要读取数据库。

---

# 29. load_optional_job Node

只有：

```text
JD_TARGETED
```

才需要。

调用：

```text
get_job
```

或：

```text
get_job_context
```

如果缺少 Job：

```text
WAITING_USER
```

请求：

```text
请选择或上传目标 JD。
```

---

# 30. load_profile Node

调用：

```text
get_skill_profile
```

Profile 用于：

```text
描述强度判断

已有优势识别

薄弱技能识别

避免夸大能力
```

---

# 31. load_evidence Node

调用：

```text
get_resume_evidence
```

Evidence 可以来自：

```text
Resume
Interview
Preparation
用户确认的项目经历
用户确认的实习经历
```

目标：

> 降低简历优化中的事实幻觉。

---

# 32. generate_patches Node

核心 LLM Node。

输入：

```text
Resume
+
optional Job
+
Profile
+
Evidence
+
Optimization Mode
```

输出：

```text
ResumePatch[]
```

---

# 33. ResumePatch Schema

```python
class ResumePatch(BaseModel):
    id: str

    operation: PatchOperation

    section: ResumeSection

    item_id: str | None = None

    field: str | None = None

    before: str | None = None

    after: str | None = None

    reason: str

    evidence_refs: list[str] = []

    status: PatchStatus = PatchStatus.PENDING
```

---

# 34. Patch 类型

```text
REPLACE
ADD
DELETE
REORDER
```

---

# 35. validate_patches Node

这是 Resume Optimization 的关键防护节点。

校验：

```text
section 是否存在

itemId 是否有效

before 是否与原 Resume 一致

after 是否引入未知技术

是否出现未知量化数字

是否虚构公司 / 项目

是否修改关键事实

是否违反 Evidence
```

---

# 36. Patch 校验示例

Resume：

```text
优化接口性能
```

Agent 生成：

```text
接口性能提升 70%
```

Evidence 中不存在：

```text
70%
```

则：

```text
Patch Invalid
```

处理方式：

```text
reject

or

mark NEED_USER_INFO
```

可以转换成建议：

```text
建议补充实际优化前后的响应时间或性能数据。
```

---

# 37. build_diff Node

输出：

```text
ResumeOptimizationBlock
```

示例：

```json
{
  "type": "resume_optimization",
  "resumeId": 1001,
  "patches": [
    {
      "patchId": "patch_001",
      "section": "PROJECT",
      "before": "使用 LangGraph 实现 Agent 功能",
      "after": "基于 LangGraph 构建 Stateful Agent 工作流，通过 Tool Calling 编排多个业务能力",
      "reason": "突出 Agent 编排职责",
      "status": "PENDING"
    }
  ]
}
```

---

# 38. Human-in-the-loop

Resume Patch 必须经过用户确认。

流程：

```text
generate_patches
 ↓
validate_patches
 ↓
build_diff
 ↓
interrupt
```

前端：

```text
Patch 1
[接受] [忽略]

Patch 2
[接受] [忽略]

[应用选中修改]
```

Graph 保存：

```text
selected_patch_ids
```

随后 Resume：

```text
apply_selected_patches
```

---

# 39. apply_selected_patches

写操作必须通过 Tool：

```text
apply_resume_patches
```

权限：

```text
CONFIRM_WRITE
```

Python 不直接写数据库。

调用：

```text
Python
 ↓
CareerBackendClient
 ↓
Java ResumeService
```

---

# 40. Resume Version

Java 负责创建：

```text
Resume V1
Resume V2
Resume V3
```

原则：

> 不直接覆盖原始 Resume。

Subgraph 最终保存：

```text
result_version_id
```

---

# 41. Resume Optimization 完成响应

示例：

```text
已应用 5 项修改，并生成新的简历版本。

Java 后端简历 V2

本次调整：
• 强化项目技术职责
• 删除冗余表达
• 优化 Java 后端关键词匹配
• 调整技能顺序

[查看新版本]
[继续优化]
[根据新简历模拟面试]
```

---

# 42. Resume Optimization 与主 Graph 的关系

主 Graph：

```text
route_intent
 ↓
RESUME_OPTIMIZATION
 ↓
Resume Optimization Subgraph
 ↓
build_response
 ↓
END
```

Resume Subgraph 完全负责：

```text
Context
Patch
Validation
HITL
Apply
Version
```

主 Graph 不需要知道内部细节。

---

# 43. Interview Create

Interview Create 不需要独立 Graph。

流程：

```text
INTERVIEW_CREATE
 ↓
load interview context
 ↓
create_interview Tool
 ↓
Java Interview Engine
 ↓
NavigationBlock
```

前端：

```text
[开始面试]
 ↓
/interviews/{id}
```

实时面试状态由 Java 管理。

---

# 44. Adaptive Interview 不进入 Career Agent Graph

正确：

```text
CareerAgent
 ↓
create_interview
 ↓
Java Interview Engine
```

Java：

```text
READY
 ↓
ASKING
 ↓
WAITING_ANSWER
 ↓
EVALUATING
 ↓
DECIDING
 ↓
ASKING
 ↓
...
 ↓
COMPLETED
```

Adaptive Interview 属于业务状态机。

---

# 45. Goal Execution Subgraph

该 Graph 后期实现。

适用：

```text
我两周后要面字节 Java 后端，
根据我的简历和历史表现帮我准备。
```

---

# 46. Goal Execution Graph

```text
START
 ↓
understand_goal
 ↓
load_goal_context
 ↓
plan
 ↓
execute_step
 ↓
observe
 ↓
evaluate_progress
 ↓
should_continue?
 ├── CONTINUE
 │      ↓
 │   execute_step
 │
 ├── REPLAN
 │      ↓
 │    replan
 │
 ├── WAIT_USER
 │      ↓
 │   interrupt
 │
 └── COMPLETE
        ↓
     summarize
        ↓
       END
```

---

# 47. Plan 约束

Planner 输出必须可执行。

例如：

```json
{
  "goal": "准备 Java 后端实习",
  "steps": [
    {
      "id": "step_1",
      "action": "GET_RESUME"
    },
    {
      "id": "step_2",
      "action": "GET_JOB"
    },
    {
      "id": "step_3",
      "action": "GET_SKILL_PROFILE"
    },
    {
      "id": "step_4",
      "action": "GET_INTERVIEW_HISTORY"
    },
    {
      "id": "step_5",
      "action": "ANALYZE_GAP"
    },
    {
      "id": "step_6",
      "action": "CREATE_PREPARATION_PLAN"
    }
  ]
}
```

禁止生成无法映射 Tool 的空泛步骤。

例如：

```text
深入分析用户
帮助用户成长
综合考虑未来发展
```

这种不属于可执行 Plan。

---

# 48. Goal Execution 实现时机

只有以下 Tool 基本稳定后再实现：

```text
Resume Tool
Job Tool
Profile Tool
Interview Tool
Preparation Tool
```

第一阶段只预留 Intent：

```text
COMPLEX_GOAL
```

不急于做 Planner。

---

# 49. Tool 设计

目录：

```text
tools/
├── resume.py
├── profile.py
├── interview.py
├── knowledge.py
├── preparation.py
├── job.py
└── attachment.py
```

---

# 50. 第一阶段 Tool

推荐优先实现：

```text
get_attachment_context

get_resume

get_resume_analysis

get_skill_profile

get_interview_history

get_interview_report

search_knowledge

get_preparation_progress
```

---

# 51. 写 Tool

后期：

```text
apply_resume_patches

create_interview

create_preparation_plan

update_preparation_task
```

写 Tool 必须标记权限。

推荐：

```text
READ_ONLY

WRITE

CONFIRM_WRITE
```

---

# 52. Tool 原则

Tool 保持薄。

正确：

```text
Tool
 ↓
CareerBackendClient
 ↓
Java API
```

错误：

```text
Tool
 ↓
SQL
 ↓
PostgreSQL
```

错误：

```text
Tool
 ↓
重新实现 ResumeService
```

---

# 53. CareerBackendClient

所有 Python → Java 调用必须集中：

```text
clients/backend.py
```

禁止：

```text
graph.py
→ httpx

resume.py
→ httpx

router.py
→ httpx
```

统一：

```text
Graph
 ↓
Tool
 ↓
CareerBackendClient
```

---

# 54. Java Internal API

推荐：

```text
/internal/agent/**
```

第一阶段：

```text
GET  /internal/agent/attachments/{id}

GET  /internal/agent/resumes/{id}

GET  /internal/agent/resumes/{id}/analysis

GET  /internal/agent/profile/skills

GET  /internal/agent/interviews/recent

GET  /internal/agent/interviews/{id}/report

GET  /internal/agent/preparation/progress

POST /internal/agent/knowledge/search
```

Resume Optimization 后续：

```text
GET  /internal/agent/resumes/{id}/evidence

POST /internal/agent/resumes/{id}/patches/apply
```

---

# 55. Error Mapping

Java 返回业务错误。

Python 映射：

```python
class BusinessToolError(Exception):
    code: str
    message: str
    retryable: bool
```

例如：

```text
RESUME_NOT_FOUND
JOB_NOT_FOUND
KNOWLEDGE_NOT_READY
PERMISSION_DENIED
ASYNC_TASK_PENDING
```

不要把：

```text
SQL
Stack Trace
Internal Exception
```

直接发送给 LLM。

---

# 56. Streaming Event

FastAPI → React 使用 SSE。

建议事件：

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

# 57. Tool Event 示例

```text
tool_started
{
  "tool": "get_skill_profile"
}
```

前端展示：

```text
正在读取能力画像...
```

而不是：

```text
Calling GET /internal/agent/profile/skills
```

---

# 58. Checkpoint

第一阶段以下场景值得使用：

```text
Resume Optimization HITL

Complex Goal

WAITING_ASYNC
```

不需要所有普通聊天都复杂化。

---

# 59. interrupt 使用场景

推荐：

```text
写操作确认

Resume Patch Review

创建 Preparation Plan

危险或影响业务状态的 Action
```

不推荐：

```text
普通 READ Tool

普通 Chat

RAG Query
```

---

# 60. WAITING_ASYNC

如果 Java 业务能力是异步：

```text
Resume Analysis
 ↓
Redis Stream
 ↓
ASYNC_PROCESSING
```

Agent：

```text
status = WAITING_ASYNC
 ↓
checkpoint
```

不要：

```text
while True:
    poll()
```

无限轮询。

---

# 61. 不使用 Graph 的场景

以下能力保持 Java / 普通 API。

## 文件上传

```text
React
→ Java
→ Object Storage
```

## PDF / DOCX 解析

```text
Java
→ Tika
```

## RAG Pipeline

```text
Java
→ pgvector
```

## Resume Analysis

```text
Java ResumeAnalysisService
```

## Adaptive Interview Realtime Loop

```text
Java Interview Engine
```

## CRUD

```text
Java Controller
→ Service
→ Repository
```

## 页面跳转

```text
NavigationBlock
→ React Router
```

---

# 62. 推荐目录结构

```text
agent-service/
├── pyproject.toml
└── src/career_copilot/
    │
    ├── main.py
    ├── config.py
    │
    ├── api/
    │   ├── chat.py
    │   └── health.py
    │
    ├── agent/
    │   ├── graph.py
    │   ├── state.py
    │   ├── router.py
    │   │
    │   ├── nodes/
    │   │   ├── normalize_input.py
    │   │   ├── resolve_context.py
    │   │   ├── route_intent.py
    │   │   ├── direct_answer.py
    │   │   ├── execute_tools.py
    │   │   ├── attachment_flow.py
    │   │   └── build_response.py
    │   │
    │   └── subgraphs/
    │       └── resume_optimization/
    │           ├── graph.py
    │           ├── state.py
    │           ├── nodes.py
    │           └── schemas.py
    │
    ├── tools/
    │   ├── attachment.py
    │   ├── resume.py
    │   ├── profile.py
    │   ├── interview.py
    │   ├── knowledge.py
    │   ├── preparation.py
    │   └── job.py
    │
    ├── clients/
    │   └── backend.py
    │
    ├── schemas/
    │   ├── message.py
    │   ├── action.py
    │   ├── response.py
    │   └── tool.py
    │
    └── llm/
        ├── provider.py
        └── roles.py
```

第一阶段不要创建大量空模块。

---

# 63. graph.py V1 示例结构

概念代码：

```python
graph = StateGraph(CareerAgentState)

graph.add_node("normalize_input", normalize_input)
graph.add_node("resolve_context", resolve_context)
graph.add_node("route_intent", route_intent)

graph.add_node("direct_answer", direct_answer)
graph.add_node("business_tools", business_tools)
graph.add_node("knowledge_tool", knowledge_tool)
graph.add_node("attachment_flow", attachment_flow)
graph.add_node("execute_action", execute_action)

graph.add_node(
    "resume_optimization",
    resume_optimization_graph
)

graph.add_node("build_response", build_response)

graph.add_edge(START, "normalize_input")
graph.add_edge("normalize_input", "resolve_context")
graph.add_edge("resolve_context", "route_intent")

graph.add_conditional_edges(
    "route_intent",
    route_by_intent,
    {
        "direct": "direct_answer",
        "business": "business_tools",
        "knowledge": "knowledge_tool",
        "attachment": "attachment_flow",
        "action": "execute_action",
        "resume_optimization": "resume_optimization",
    }
)

graph.add_edge("direct_answer", "build_response")
graph.add_edge("business_tools", "build_response")
graph.add_edge("knowledge_tool", "build_response")
graph.add_edge("attachment_flow", "build_response")
graph.add_edge("execute_action", "build_response")
graph.add_edge("resume_optimization", "build_response")

graph.add_edge("build_response", END)
```

---

# 64. 第一阶段不要做 Agent Infinite Loop

暂时不要：

```text
agent
 ↓
tools
 ↓
agent
 ↓
tools
 ↓
agent
 ↓
should_continue
```

第一阶段更推荐：

```text
route
 ↓
limited tool execution
 ↓
response
```

复杂循环只用于：

```text
Goal Execution
```

---

# 65. LLM 使用原则

LLM 主要用于：

```text
Intent Classification

Resume Patch Generation

Complex Goal Planning

Response Generation
```

确定性代码负责：

```text
Action Routing

Attachment Type Routing

Permission

Tool Boundary

Retry

Validation

HITL

Idempotency
```

原则：

> LLM decides meaning, code controls boundaries.

---

# 66. LLM Model Role

建议：

```text
AGENT_REASONING

FAST_DECISION

BUSINESS_ANALYSIS
```

主 Graph：

```text
route_intent
→ FAST_DECISION
```

Resume Optimization：

```text
generate_patches
→ AGENT_REASONING / BUSINESS_ANALYSIS
```

不要在不同 Node 中直接散落：

```python
ChatOpenAI(...)
```

统一：

```text
AgentModelProvider
```

---

# 67. 测试建议

## Unit Test

测试：

```text
normalize_input

intent routing

action routing

patch validation

error mapping
```

---

## Graph Test

测试：

```text
Text → GENERAL_CHAT

Attachment → ATTACHMENT_RECEIVED

Action → deterministic branch

Resume Optimization → interrupt

Resume Optimization Resume → apply
```

---

## Tool Test

Mock：

```text
CareerBackendClient
```

不默认调用真实 Java 服务。

---

## LLM Test

默认使用：

```text
Fake / Stub Structured Output
```

避免测试直接产生真实 API 费用。

---

# 68. 第一阶段开发顺序

## Phase 1

完成：

```text
React
→ FastAPI
→ Copilot Turn Graph
→ LLM
→ SSE
→ React
```

Graph：

```text
START
→ route_intent
→ direct_answer
→ END
```

---

## Phase 2

增加：

```text
PROFILE_QUERY
```

打通：

```text
Graph
→ Tool
→ Java
→ SkillProfileBlock
```

---

## Phase 3

增加：

```text
KNOWLEDGE_QA
→ search_knowledge
```

---

## Phase 4

增加：

```text
Attachment Flow
```

完成：

```text
Resume File
→ ChoiceBlock
```

---

## Phase 5

实现：

```text
Resume Optimization Subgraph
```

完成：

```text
Resume
→ Patch
→ Diff
→ interrupt
→ Apply
→ Version
```

---

## Phase 6

增加：

```text
INTERVIEW_CREATE
```

完成：

```text
Copilot
→ create_interview
→ NavigationBlock
→ Interview Page
```

---

## Phase 7

接入：

```text
Profile
Evidence
Preparation
```

---

## Phase 8

最后实现：

```text
Goal Execution Subgraph
```

---

# 69. 第一阶段 Definition of Done

主 Graph 完成标准：

```text
Text 输入可以进入 Graph

Action 可以确定性路由

Attachment 可以识别并生成 ChoiceBlock

READ Tool 可以调用 Java

RAG 可以作为 Tool 使用

Structured Response 可以返回 React

SSE 可以正常 Streaming

Graph Error 可以结构化处理
```

Resume Optimization 完成标准：

```text
可识别 Resume Optimization Intent

可确定当前 Resume

可选读取 JD

可读取 Profile / Evidence

LLM 输出结构化 ResumePatch

Patch 有 Validation

前端可显示 Diff

用户确认后才执行写操作

支持 interrupt / resume

生成新 Resume Version

不覆盖原 Resume
```

---

# 70. 当前 Graph 设计原则总结

```text
1. 一个主 Graph 作为统一 Agent 入口。

2. 只有复杂 Workflow 才创建 Subgraph。

3. 简单业务查询使用 Tool，不创建 Graph。

4. Resume Optimization 是第一阶段最重要的 Subgraph。

5. Complex Goal 最后实现。

6. Adaptive Interview 留在 Java Interview Engine。

7. RAG 留在 Java Knowledge Base。

8. 文件上传和解析不进入 Graph。

9. Action 使用确定性路由。

10. Python 不直接操作业务数据库。

11. State 只保存必要 Reference 和运行状态。

12. LLM 负责语义判断，代码负责业务边界。

13. 写操作优先使用 HITL。

14. Checkpoint 只用于真正需要恢复的 Workflow。

15. Selection / Routing 优先于无限 Agent Loop。
```

---

# 71. 最终目标架构

```text
                           /copilot
                              │
                              ▼
                       Copilot Turn Graph
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
          Direct LLM      Business Tool     Workflow
                              │               │
                              │        ┌──────┴─────────┐
                              │        │                │
                              ▼        ▼                ▼
                             Java   Resume Opt.     Complex Goal
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
          ▼                   ▼                   ▼
       Resume             Interview             RAG
          │                   │                   │
          └───────────────────┼───────────────────┘
                              │
                              ▼
                         Business Data
```

Career Copilot 的核心不是 Graph 数量，而是：

```text
用户输入
   ↓
正确理解
   ↓
正确选择能力
   ↓
安全执行
   ↓
必要时中断
   ↓
继续恢复
   ↓
结构化返回
```

只有当流程存在：

```text
多步骤
条件分支
中断恢复
多个 Tool
动态决策
```

时，才应该进一步引入新的 Graph。
