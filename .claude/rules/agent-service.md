# Career Copilot Agent Service Rules

本文件约束 `agent-service/` 下的 Python Agent Runtime 开发。

根目录 `AGENTS.md` 中的系统级架构规则始终有效。本文件只补充 Python / FastAPI / LangGraph / Tool / Agent State / Memory / Checkpoint 等 Agent Runtime 特有规则。

---

# 1. Role of Agent Service

`agent-service/` 是 Career Copilot 的 Agent Runtime。

它负责：

* 用户意图识别
* Agent State
* Routing
* Planning
* Tool Selection
* Tool Calling
* Workflow
* Context Construction
* Checkpoint
* Human-in-the-loop
* Replan
* Agent-level Memory Retrieval
* Structured Response Generation

它不是第二套业务后端。

Agent Service 不拥有：

* Resume 业务数据
* Job 业务数据
* Interview 业务数据
* Knowledge Base 业务数据
* Preparation 业务数据
* Profile 最终业务事实
* 用户权限事实
* 业务事务

这些数据的 System of Record 始终是 Spring Boot Backend。

---

# 2. Golden Architecture Rule

必须遵循：

```text
User
 ↓
Frontend
 ↓
Agent Service
 ↓
Career Agent
 ↓
Tool
 ↓
Java Backend
 ↓
Service
 ↓
Repository
 ↓
PostgreSQL / Redis
```

禁止：

```text
Agent Service
 ↓
Direct SQL
 ↓
Java-owned Business Tables
```

Python 不得为了“方便”直接连接业务数据库读取或修改：

```text
resume
job
interview
knowledge_base
profile
preparation
user
```

等 Java 管理的业务表。

---

# 3. What Python May Persist

Python 可以持久化 Agent Runtime 自身状态，例如：

* LangGraph Checkpoint
* Agent Run runtime metadata
* temporary orchestration state
* Agent execution trace
* Agent-specific cache

但这些数据不得成为业务事实源。

例如：

```text
selected_resume_id = 102
```

可以存在 Agent State 中。

但：

```text
Resume #102 的真实内容
```

必须通过 Java Tool 获取。

---

# 4. Recommended Structure

推荐结构：

```text
agent-service/
├── pyproject.toml
├── uv.lock
├── .env.example
├── README.md
│
├── src/
│   └── career_copilot/
│       │
│       ├── main.py
│       ├── config.py
│       │
│       ├── api/
│       │   ├── chat.py
│       │   ├── runs.py
│       │   └── health.py
│       │
│       ├── agent/
│       │   ├── graph.py
│       │   ├── state.py
│       │   ├── router.py
│       │   └── response.py
│       │
│       ├── tools/
│       │   ├── resume.py
│       │   ├── interview.py
│       │   ├── knowledge.py
│       │   ├── profile.py
│       │   ├── preparation.py
│       │   └── job.py
│       │
│       ├── clients/
│       │   └── backend.py
│       │
│       ├── schemas/
│       │   ├── message.py
│       │   ├── action.py
│       │   ├── tool.py
│       │   └── common.py
│       │
│       ├── memory/
│       │   ├── working.py
│       │   ├── episodic.py
│       │   └── profile.py
│       │
│       ├── persistence/
│       │   └── checkpoint.py
│       │
│       └── observability/
│           └── tracing.py
│
└── tests/
```

不要在项目早期一次性创建所有目录。

只有出现真实需求时再增加对应模块。

---

# 5. Start Simple

Career Copilot 当前优先建立稳定 Agent 主干。

不要一开始构建超级 Graph。

第一阶段优先支持：

```text
User Message
 ↓
Intent Router
 ↓
Direct Answer / Tool Call
 ↓
Structured Response
 ↓
END
```

简单查询例如：

```text
我最近复习得怎么样？

我的 JVM 水平怎么样？

给我看看最近的模拟面试。

JVM GC 是什么？
```

通常不需要复杂 Planner。

---

# 6. LangGraph Usage

LangGraph 只用于真正需要状态化编排的任务。

适合：

* 多步骤任务
* 多 Tool 依赖
* 长时间执行
* Checkpoint
* Resume
* Interrupt
* Human-in-the-loop
* Replanning
* WAITING_ASYNC

不适合：

```text
查询一个 Profile
```

或：

```text
调用一次 RAG
```

这种简单请求。

禁止为了“体现 Agent”而把每个接口都包装成大型 StateGraph。

---

# 7. Graph Design

Graph Node 必须保持职责单一。

推荐：

```text
route_intent

load_context

plan

execute_tool

observe

build_response

update_memory
```

不推荐：

```text
process_everything
```

一个 Node 不应同时：

* 调多个无关 Tool
* 修改大量 State
* 做 LLM 推理
* 决定路由
* 构造前端响应
* 更新长期记忆

---

# 8. Agent State Rules

Agent State 是工作流状态，不是业务数据库。

State 应尽量小。

推荐：

```python
class CareerAgentState(TypedDict):
    messages: list
    user_id: int

    intent: str | None
    goal: str | None

    plan: list
    current_step: int

    context: dict
    tool_results: list

    pending_action: dict | None
    response: dict | None

    status: str
```

不要将大量完整业务对象塞入 State。

优先保存：

```text
resource id

small snapshot

artifact reference

tool result summary
```

而不是：

```text
整个 Resume Entity

整个 Interview History

整个 Knowledge Base
```

---

# 9. State Must Be Serializable

所有进入 LangGraph State / Checkpoint 的内容必须可可靠序列化。

避免：

* HTTP Client
* DB Connection
* model client
* coroutine
* open file handle
* arbitrary class instance

等运行时对象进入 State。

运行时依赖应通过：

* dependency injection
* runtime context
* service container

传递。

---

# 10. Intent Routing

Intent Router 应输出有限、稳定的枚举值。

例如：

```text
GENERAL_CHAT

KNOWLEDGE_QA

PROFILE_QUERY

PREPARATION_QUERY

INTERVIEW_CREATE

INTERVIEW_REVIEW

RESUME_QUERY

JOB_ANALYSIS

JOB_MATCH

NAVIGATION

COMPLEX_GOAL
```

不要让 LLM 返回任意字符串作为路由名。

路由输出必须结构化验证。

---

# 11. Intent Router Should Be Cheap

Intent 判断通常属于：

```text
classification
```

优先使用：

* 低延迟模型
* temperature = 0
* Structured Output
* 少量 Context

不要为了 Intent Router 将完整：

```text
Conversation History
Resume
Profile
Knowledge Base
Interview History
```

全部塞给模型。

只传完成当前判断所需的最小上下文。

---

# 12. Tool Architecture

Tool 是 Agent 与 Java Backend 的业务边界。

Python Tool 本身应保持薄。

推荐：

```python
async def get_skill_profile(...) -> SkillProfile:
    return await backend_client.get_skill_profile(...)
```

Tool 负责：

* Tool schema
* Agent-friendly parameter
* 调用 Java Client
* 结果转换
* 统一错误映射

Tool 不负责：

* 数据库访问
* 复杂业务规则
* Java Service 的复制实现
* 页面逻辑
* 大量 Agent reasoning

---

# 13. Tool Naming

Tool 名称使用：

```text
动词 + 业务对象
```

例如：

```text
get_resume

list_resumes

get_resume_analysis

get_interview_history

get_interview_report

search_knowledge

get_skill_profile

get_preparation_progress

create_preparation_plan
```

不要使用含糊名称：

```text
handle_data

process

execute

do_action

smart_query
```

Tool 名称本身应该能够帮助 LLM 判断用途。

---

# 14. Tool Input Schema

Tool 参数必须最小化。

例如：

```python
class GetResumeInput(BaseModel):
    resume_id: int
```

不要向 Agent 暴露：

```text
created_at
updated_at
internal status
database flags
storage path
```

等无关字段。

---

# 15. Tool Output Schema

Tool 返回必须结构化。

优先使用 Pydantic Model。

例如：

```python
class SkillProfile(BaseModel):
    skill: str
    score: float
    confidence: float | None = None
```

禁止依赖不可控的：

```text
dict[str, Any]
```

贯穿整个系统。

在边界处可以使用动态结构，但核心业务 Tool 应尽量强类型。

---

# 16. Tool Permission Model

Tool 分为：

```text
READ

SAFE_WRITE

CONFIRM_WRITE
```

READ：

```text
Agent 可直接执行
```

SAFE_WRITE：

```text
可自动执行，但必须可追踪
```

CONFIRM_WRITE：

```text
必须用户确认
```

不要根据 Prompt 临时决定一个 Tool 是否危险。

权限等级应由代码或 Tool metadata 明确定义。

---

# 17. Human-in-the-loop

CONFIRM_WRITE 不得直接执行。

正确：

```text
Agent
 ↓
prepare action
 ↓
response:
confirmation block
 ↓
WAITING_USER
 ↓
User confirms
 ↓
resume graph
 ↓
execute tool
```

禁止：

```text
Agent:
“我认为用户应该同意”

→ 自动执行
```

用户确认必须来自真实前端事件。

---

# 18. Idempotency

所有可能因为：

* Retry
* Resume
* Network timeout
* Checkpoint recovery

而重复执行的写 Tool 必须考虑幂等。

优先由 Java Backend 实现最终幂等保障。

Agent Service 可生成：

```text
run_id
step_id
idempotency_key
```

传给 Backend。

不要假设一个 LangGraph Node 永远只执行一次。

---

# 19. Backend Client

所有 Java Backend HTTP 调用集中在：

```text
clients/backend.py
```

或等价的明确 Client 层。

不要在：

```text
graph.py

router.py

memory.py

api/chat.py
```

里直接散落 `httpx` 请求。

---

# 20. HTTP Client Rules

复用长生命周期异步 HTTP Client。

优先：

```python
httpx.AsyncClient
```

不要每次 Tool 调用都：

```python
async with httpx.AsyncClient() as client:
```

重新创建连接池，除非有明确理由。

配置：

* timeout
* base URL
* headers
* auth
* retry policy

应集中管理。

---

# 21. Timeout Rules

外部调用必须显式设置 Timeout。

不同类型调用可以有不同时间限制。

例如：

```text
普通 READ Tool:
较短 timeout

LLM:
更长 timeout

异步任务启动:
只等待任务创建
```

禁止无限等待外部 HTTP。

---

# 22. Error Handling

Java Backend 业务错误必须转换为 Agent 可以理解的结构化错误。

例如：

```python
class BusinessToolError(Exception):
    code: str
    message: str
    retryable: bool
```

区分：

```text
business error

network error

timeout

validation error

model error

unexpected internal error
```

不要统一：

```python
except Exception:
    return "失败了"
```

---

# 23. Do Not Leak Backend Details to LLM

不要把以下内容直接塞给模型：

```text
Java stack trace

SQL error

PostgreSQL table name

internal endpoint

secret

token

raw exception dump
```

Tool 应转换为适合模型决策的错误。

例如：

```text
RESUME_NOT_FOUND

JOB_MATCH_IN_PROGRESS

KNOWLEDGE_BASE_NOT_READY
```

---

# 24. Async Business Tasks

Java 中已经存在的异步业务任务，例如：

```text
Resume Analysis

Knowledge Vectorization

Interview Evaluation
```

Agent 不应该同步阻塞等待。

推荐：

```text
Agent
 ↓
start task
 ↓
Java returns task id
 ↓
Agent status = WAITING_ASYNC
 ↓
checkpoint
```

后续重新恢复：

```text
get_task_status
```

完成后继续工作流。

不要：

```python
while True:
    await asyncio.sleep(...)
    poll()
```

在单个请求里无限等待。

---

# 25. Planning

Planner 只用于：

```text
COMPLEX_GOAL
```

例如：

```text
我十天后要面试 Java 后端，
根据我的简历和过去表现帮我制定准备方案。
```

简单任务不要 Planning：

```text
我 JVM 水平多少？
```

---

# 26. Plan Must Be Executable

Planner 输出的 Step 必须对应：

* 已知 Tool
* 已知 Agent operation
* 用户交互
* 可验证结果

不要生成模糊 Step：

```text
深入理解用户

全面分析职业发展

进行智能优化
```

推荐：

```text
load_resume

load_job

get_skill_profile

analyze_gap

create_preparation_plan
```

---

# 27. Model Output Must Be Structured Where Possible

对于：

* Intent
* Tool decision
* Plan
* Turn classification
* Action
* Memory extraction

优先使用 Pydantic Structured Output。

不要靠：

```python
if "FOLLOW_UP" in response:
```

解析自然语言。

---

# 28. Model Temperature

结构化决策类任务默认：

```text
temperature = 0
```

或非常低。

创作型任务例如：

* 用户建议
* 学习解释
* 最终自然语言回复

可以使用更高自由度。

不要给所有节点共享完全相同的模型参数。

---

# 29. Prompt Rules

Prompt 应按职责拆分。

例如：

```text
intent_router

planner

final_response

profile_summary

memory_extractor
```

不要建立一个万能 System Prompt：

```text
你是 Career Copilot，
请完成所有事情……
```

然后所有 Node 都使用它。

Prompt 应尽量明确：

```text
Input

Task

Allowed Output

Constraints
```

---

# 30. Context Construction

Context 必须按需加载。

禁止：

```text
每次用户说一句话
 ↓
读取全部 Resume
读取全部 Interview
读取全部 Profile
读取全部 Preparation
读取全部 Memory
读取全部 RAG
 ↓
全部塞入 Prompt
```

应该：

```text
Intent
 ↓
identify required context
 ↓
load only relevant data
```

---

# 31. Token Discipline

对 Tool Result 进入 LLM Context 前进行：

* 字段裁剪
* 数量限制
* 摘要
* relevance filtering

例如：

```text
get_interview_history
```

可能返回 50 场面试。

模型通常不需要完整 50 场所有问题。

可以提供：

```text
latest 5 interviews

aggregated scores

important weaknesses
```

---

# 32. RAG

Python 不重建 RAG Pipeline。

正确：

```text
CareerAgent
 ↓
search_knowledge Tool
 ↓
Java Backend
 ↓
KnowledgeBaseQueryService
 ↓
pgvector
```

Python 负责判断：

```text
是否需要知识检索
```

Java 负责实际：

```text
query rewrite

retrieval

vector search

document filtering
```

---

# 33. RAG Is Not Default Context

不要每次用户消息都调用 RAG。

例如：

```text
我最近复习得怎么样？
```

主要需要：

```text
Profile
Preparation
Interview History
```

而不是 RAG。

例如：

```text
讲一下 JVM GC
```

才适合：

```text
search_knowledge
```

---

# 34. Memory

Agent Service 中的 Memory 访问必须按三层语义区分。

## Working Memory

当前 Run 的 State。

---

## Episodic Memory

历史重要事件。

---

## Profile Memory

长期稳定画像。

不要使用一个：

```text
memory: list[str]
```

把所有概念混在一起。

---

# 35. Memory Retrieval

长期记忆按当前任务检索。

例如：

```text
User:
给我来一场 JVM 面试
```

需要：

```text
JVM Profile

Recent JVM Interview Evidence
```

通常不需要：

```text
用户半年前一次前端岗位聊天记录
```

---

# 36. Memory Write

不要每轮 Chat 都生成长期 Memory。

只有具备长期价值的信息才进入 Memory。

例如：

```text
用户长期目标改变

完成重要面试

某项技能持续表现偏弱

完成一个准备阶段
```

普通：

```text
“好的”

“继续”

“谢谢”
```

不应生成长期 Memory。

---

# 37. Profile Is Not Owned by Python

Agent 可以：

```text
读取 Profile

提议 Profile observation

生成 summary

生成 evidence interpretation
```

最终持久化和聚合规则由 Java Profile 模块负责。

Python 不应该维护一套独立：

```text
profile.json
```

作为用户真实画像。

---

# 38. Adaptive Interview Boundary

Career Agent 不处理模拟面试每一轮。

不要构建：

```text
CareerAgent Graph
 ↓
Q1
 ↓
Answer
 ↓
Graph
 ↓
Q2
 ↓
Answer
 ↓
Graph
```

实时面试属于 Java Interview Engine。

Agent 只负责：

```text
recommend_interview

build interview configuration

create_interview

read interview report
```

---

# 39. Structured Response

Agent 最终输出不是任意 JSON。

必须符合统一 Message Schema。

建议：

```python
class CopilotResponse(BaseModel):
    content: str
    blocks: list[MessageBlock] = []
```

Block 应使用明确 Union / discriminator。

例如：

```text
TextBlock

NavigationBlock

ConfirmationBlock

SkillProfileBlock

ProgressBlock

ChartBlock

PreparationPlanBlock
```

---

# 40. Do Not Generate UI Code

模型不得返回：

```text
React component

HTML

CSS

JavaScript

onclick code
```

模型只能返回允许的结构化协议。

Frontend 决定最终表现。

---

# 41. Navigation Security

Agent 不得返回任意 URL 让前端直接跳转。

优先使用：

```text
route key
+
validated params
```

例如：

```json
{
  "route": "INTERVIEW_CREATE",
  "params": {
    "focus": "JVM"
  }
}
```

由前端映射：

```text
INTERVIEW_CREATE
→ /interviews/create
```

比让模型直接输出：

```text
target = 任意字符串
```

更安全、更稳定。

---

# 42. Streaming

Streaming 主要用于：

* 自然语言回答
* 长内容生成
* Copilot 对话体验

Tool 调用结果与结构化 Block 必须有明确生命周期。

不要让前端从 token stream 中猜：

```text
Tool 到底调用完没有

Block JSON 到底结束没有
```

推荐使用事件类型：

```text
message_delta

tool_started

tool_completed

block

run_status

done

error
```

---

# 43. API Layer

FastAPI API 层只负责：

* request validation
* authentication context propagation
  -调用 Agent Runtime
* streaming
* response serialization

API Router 不承担 Agent business reasoning。

不要把核心逻辑全部写入：

```python
@router.post("/chat")
async def chat(...):
    # 500 lines
```

---

# 44. Authentication Context

来自前端的用户身份不能由 LLM 决定。

用户身份应由：

* validated token
* trusted backend context
* gateway

确定。

Tool 调用必须传递受信任：

```text
user_id
```

不要接受模型生成：

```text
user_id = ...
```

---

# 45. Configuration

使用统一配置类。

优先：

```python
pydantic-settings
```

例如：

```python
class Settings(BaseSettings):
    backend_base_url: str
    llm_api_key: SecretStr
```

不要散落：

```python
os.getenv(...)
```

到各个 Service / Tool 中。

---

# 46. Secrets

禁止：

* API Key 写源码
* Token 写测试
* Secret 打日志
* Secret 放 Agent State
* Secret 传给 LLM

`.env.example` 只能包含占位值。

---

# 47. Logging

使用结构化、可检索日志。

重要字段建议包括：

```text
run_id

conversation_id

user_id

node

tool

status

duration
```

不要记录完整：

```text
resume content

private conversation

API key

token
```

除非明确必要并经过脱敏。

---

# 48. Observability

对关键 Agent Node 和 Tool 调用保留：

```text
start time

duration

success / failure

tool name

model name

token usage

retry
```

不要让 observability 逻辑散落在每个 Node。

后期优先通过统一 middleware / callback / wrapper 实现。

---

# 49. Testing Strategy

Agent Service 测试至少分为：

```text
unit

graph

tool contract

API
```

Unit：

```text
router

policy

schema

response builder
```

Graph：

```text
route correctness

interrupt

resume

failure routing
```

Tool：

```text
mock Java Backend

validate request

validate response

error mapping
```

API：

```text
request schema

stream format

error response
```

---

# 50. LLM Tests

普通单元测试不得默认调用真实收费模型。

优先：

```text
fake model

stub model

mock structured response
```

真实模型测试单独标记，例如：

```text
integration

manual

llm
```

避免普通：

```bash
pytest
```

突然开始燃烧 Token。

---

# 51. Tool Tests

Tool 测试重点验证：

```text
correct backend endpoint

correct auth context

correct params

correct schema conversion

business error mapping

timeout handling
```

不要只测试：

```text
函数能被调用
```

这种接近哲学证明的内容。

---

# 52. Graph Tests

Graph 不需要测试模型语言质量。

Graph 测试主要验证：

```text
given intent X
→ route Y

tool failure
→ correct failure branch

confirmation required
→ WAITING_USER

async task pending
→ WAITING_ASYNC

resume
→ continue correct node
```

---

# 53. Current Development Priority

当前 Career Copilot Agent Service 开发优先级：

```text
P0
FastAPI skeleton

P1
Chat endpoint + streaming

P2
Intent Router

P3
READ Tools

P4
Structured Response

P5
Profile integration

P6
Preparation integration

P7
CONFIRM_WRITE + HITL

P8
Complex Goal Planner

P9
Checkpoint / Resume

P10
Memory / Evaluation / Observability enhancement
```

不要跳过基础能力直接开始 Multi-Agent 或复杂 Planning。

---

# 54. First Tools

第一批 Tool 优先保持 READ-only。

推荐：

```text
list_resumes

get_resume_analysis

get_interview_history

get_interview_report

search_knowledge

get_skill_profile

get_preparation_progress
```

在 READ Tool 稳定之前，不要一次性开放大量业务写操作。

---

# 55. Reuse Existing Career Copilot Contracts

新增功能前必须搜索：

```text
schemas/

tools/

clients/

agent/
```

是否已经存在：

* 对应 DTO
* 对应 Tool
* 对应 Client
* 对应 Response Block

不要创建：

```text
SkillProfile

SkillProfileDTO

SkillProfileData

SkillProfileResult

UserSkillProfile
```

五个几乎相同模型。

边界模型数量应该被刻意控制。

---

# 56. Dependency Direction

推荐依赖方向：

```text
api
 ↓
agent
 ↓
tools
 ↓
clients
 ↓
external Java Backend
```

Schemas 可以被各层引用。

不要让：

```text
clients
```

反向依赖：

```text
graph
```

不要让 Tool 依赖 FastAPI Router。

保持基础设施层不知道上层 orchestration。

---

# 57. Avoid Circular Imports

不要使用：

```python
if TYPE_CHECKING
```

和运行时局部 import 去长期掩盖错误模块设计。

出现循环依赖时优先检查职责边界。

---

# 58. Async by Default for I/O

涉及：

* HTTP
* LLM
* checkpoint I/O
* external service

优先使用 async。

不要在 FastAPI async 请求链中执行明显阻塞 I/O。

需要运行同步重任务时显式隔离。

---

# 59. No Fire-and-Forget Inside Request

不要：

```python
asyncio.create_task(...)
```

然后认为可靠异步任务已经完成。

请求生命周期结束后任务可能：

* 丢失
* 异常无人处理
* 服务重启消失

可靠长任务应由：

* Java async infrastructure
* durable job system
* persisted Agent Run

承载。

---

# 60. Avoid Retry Explosion

不要多层同时：

```text
HTTP Client retry 3

Tool retry 3

Graph retry 3

LLM retry 3
```

否则一次错误可能变成几十次请求。

Retry 策略必须明确只有合适层负责。

业务错误通常不重试。

网络瞬时错误才考虑有限重试。

---

# 61. Never Do

在 `agent-service/` 中永远不要：

* 直接访问 Java-owned business tables
* 复制 Java 业务逻辑
* 重新实现现有 RAG Pipeline
* 将每次聊天都变成复杂 Planner
* 将实时 Interview Loop 放进 Career Agent Graph
* 让 LLM 返回任意前端代码
* 让模型决定 user identity
* 让模型绕过 Tool 权限
* 让 CONFIRM_WRITE 自动执行
* 把所有 Conversation History 无脑塞进 Prompt
* 把完整业务 Entity 长期存进 Agent State
* 将 Secret 放进 Prompt / State / Log
* 用大量 `dict[str, Any]` 替代稳定 Schema
* 解析自由文本来判断关键路由
* `except Exception: pass`
* 无限轮询异步任务
* 无 Timeout 调用外部服务
* 为简单逻辑创建大量 Factory / Manager / Registry
* 在没有明确需求时引入 Multi-Agent
* 为“未来可能需要”提前实现复杂框架

---

# 62. Definition of Done

Agent Service 任务完成前必须确认：

```text
1. 架构边界没有被破坏

2. Python 没有复制 Java 业务逻辑

3. 新 Tool 有明确 schema

4. 错误可以被结构化处理

5. Agent State 保持精简

6. Relevant tests pass

7. 没有泄露 secret

8. 没有无关重构

9. 关键流程已验证

10. 文档与代码行为一致
```

任务总结必须说明：

```text
修改内容

Agent 流程变化

新增 / 修改 Tool

验证方式

当前限制

潜在后续工作
```

---

# 63. Guiding Principle

遇到架构选择时，优先考虑：

```text
业务事实留在 Java

决策编排留在 Agent

交互表现留在 React
```

以及：

```text
Simple before complex.

Tool before planner.

Selection before generation.

Evidence before memory.

Deterministic boundary before LLM autonomy.
```

Career Copilot 的目标不是让 LLM 控制更多东西。

而是让 LLM 在明确、可靠、可恢复的工程边界内做它擅长的决策。
