# Career Copilot Agent Rules

Career Copilot is an Agent-driven career preparation platform built on top of the original InterviewGuide project.

Tech stack:

* Backend: Spring Boot 4.1.0 / Java 25 / Gradle / Spring AI 2.0.0
* Agent Runtime: Python / FastAPI / LangGraph
* Frontend: React 18 / TypeScript / Vite / TailwindCSS 4
* Database: PostgreSQL + pgvector
* Cache & Async: Redis / Redisson / Redis Stream
* Storage: RustFS / S3
* Document Parsing: Apache Tika

This file is the cross-tool entry point for Coding Agents such as Codex, Claude Code and OpenCode.

Only keep rules here that are:

* long-lived,
* difficult to infer safely from code,
* architectural,
* or expensive to get wrong.

More detailed implementation rules live under `.claude/rules/`.

---

# 1. Product Goal

Career Copilot is not a collection of independent AI features.

The primary product interaction is the **Career Copilot Agent**.

Users express goals or intentions through conversation, for example:

```text
帮我看看最近复习得怎么样。

我想准备 Java 后端实习。

给我来一场 JVM 专项模拟面试。

这份 JD 和我的简历匹配吗？

我今天应该学什么？
```

The Agent should determine whether to:

* answer directly,
* query business data,
* call a business Tool,
* use RAG,
* return structured UI,
* suggest navigation,
* request user confirmation,
* create or update a plan,
* or start a longer workflow.

Core product loop:

```text
User Goal
   ↓
Career Agent
   ↓
Resume + Job + Profile + History
   ↓
Gap Analysis
   ↓
Preparation
   ↓
Learning / RAG
   ↓
Mock Interview
   ↓
Evaluation
   ↓
Profile Update
   ↓
Replan
```

The project should evolve toward this loop incrementally.

Do not rewrite the whole system at once.

---

# 2. Core Architecture

Career Copilot has three major application layers:

```text
React Frontend
      │
      ▼
Spring Boot Business Backend
      │
      ▼
Python Agent Runtime
```

Their responsibilities must remain clearly separated.

---

# 3. Spring Boot Is the System of Record

The Java backend is the authoritative source of business truth.

Spring Boot owns:

* users and authorization,
* resumes,
* jobs,
* interview sessions,
* interview reports,
* knowledge bases,
* preparation plans,
* long-term profiles,
* files,
* PostgreSQL business data,
* pgvector data,
* Redis-backed business state,
* transactions,
* idempotency,
* business validation,
* asynchronous business tasks.

All durable business data must be managed through the Java backend.

Correct:

```text
Career Agent
    ↓
Business Tool
    ↓
Java API
    ↓
Service
    ↓
Repository
    ↓
PostgreSQL / Redis
```

Do NOT create:

```text
Career Agent
    ↓
Direct SQL
    ↓
Business Tables
```

The Python Agent service must not become a second business backend.

---

# 4. Python Agent Runtime Responsibilities

The Python `agent-service/` is responsible for Agent orchestration only.

It may own:

* intent recognition,
* Agent state,
* routing,
* planning,
* Tool selection,
* Tool calling,
* context construction,
* checkpointing,
* Human-in-the-loop,
* workflow recovery,
* reasoning,
* replanning,
* Agent-level memory retrieval.

It must NOT duplicate Java business logic.

Avoid implementing concepts such as:

```text
ResumeRepository
InterviewRepository
PreparationRepository
Business SQL
```

inside Python.

Business state should be read or modified through Tools backed by Java APIs.

---

# 5. Reuse Before Rewrite

Career Copilot is built on top of an already working InterviewGuide system.

Before implementing a new capability:

1. Search for an existing implementation.
2. Understand its current business flow.
3. Reuse it if possible.
4. Wrap it as a Tool if needed.
5. Extend it only when necessary.
6. Rewrite it only when there is a clear architectural reason.

Do not casually reimplement existing capabilities.

Especially reuse existing implementations for:

* resume parsing,
* resume analysis,
* interview evaluation,
* LLM Provider management,
* Structured Output,
* knowledge-base ingestion,
* embedding,
* pgvector retrieval,
* RAG query,
* Redis Stream infrastructure,
* file storage,
* rate limiting,
* export.

Avoid parallel implementations such as:

```text
Old Java RAG
+
New Python RAG
```

unless a task explicitly requires replacing the old architecture.

---

# 6. Current Migration Strategy

Career Copilot is being evolved incrementally from InterviewGuide.

During the migration phase:

* keep the original project runnable,
* do not delete existing modules merely because they are not yet exposed through Copilot,
* avoid large package moves,
* avoid mass renaming,
* avoid unrelated cleanup,
* prefer additive changes over destructive rewrites.

Original features such as:

* Resume,
* Interview,
* Knowledge Base,
* LLM Provider,
* Settings,
* Voice Interview,
* Interview Schedule

may remain in place while Career Copilot gradually takes over the primary user entry point.

A temporarily unused module is not automatically dead code.

---

# 7. Project Structure

Current major structure:

```text
Career-Copilot/
├── app/
│   └── Spring Boot business backend
│
├── frontend/
│   └── React frontend
│
├── agent-service/
│   └── Python Agent Runtime
│
├── docs/
│   └── architecture and product documentation
│
├── docker/
│
├── AGENTS.md
└── .claude/
    └── rules/
```

Java:

```text
app/src/main/java/interview/guide/
├── common/
├── infrastructure/
└── modules/
```

Frontend:

```text
frontend/src/
├── api/
├── components/
├── constants/
├── hooks/
├── pages/
├── types/
└── utils/
```

Prompts:

```text
app/src/main/resources/prompts/
```

---

# 8. Java Backend Architecture

The backend follows:

```text
Controller
   ↓
Service
   ↓
Repository
```

Rules:

* Controller handles routing, request validation and delegation only.
* Service owns business orchestration.
* Repository owns persistence access.
* `@Transactional` belongs in the Service layer.
* Keep transaction scopes as small as possible.
* Never perform LLM, S3 or external HTTP calls inside a database transaction.
* Infrastructure capabilities belong in `common/` or `infrastructure/`.
* Business logic belongs inside its domain module.
* External responses use `Result<T>`.
* Never expose JPA Entity objects directly to the frontend.

Repositories should extend Spring Data JPA `JpaRepository`.

Prefer:

* derived query methods,
* `@Query`,
* batching,

before introducing unnecessary persistence abstractions.

---

# 9. Backend Coding Rules

Business failures must use:

```java
throw new BusinessException(
    ErrorCode.XXX,
    "描述信息"
);
```

Do not use:

```java
throw new RuntimeException(...)
```

for expected business failures.

Global exception handling follows the existing project convention:

```text
HTTP 200
+
Result.error(code, message)
```

unless the architecture is intentionally changed project-wide.

Naming:

```text
XxxRequest
XxxResponse
XxxDTO
XxxEntity
```

Prefer immutable Java `record` for request models when appropriate.

Entity → DTO / Response mapping should prefer MapStruct.

Dependency injection:

```text
constructor injection
```

Prefer Lombok:

```java
@RequiredArgsConstructor
```

Java style:

* 2-space indentation,
* no wildcard imports,
* avoid inline fully-qualified class names,
* keep methods focused,
* do not introduce abstractions without a concrete need.

Code comments:

* code must include necessary Chinese comments explaining business intent,
  non-obvious branches and complex call chains (applies to Java, Python and TypeScript),
* comments should explain *why*, not restate what the code obviously does,
* simple self-explanatory code does not need comments,
* keep comments in sync with the code when changing behavior.

Logging:

```java
log.error("message {}", value, exception);
```

Use SLF4J placeholders.

Exceptions must be passed as the final logging argument.

---

# 10. AI Invocation Rules

Chat models must be obtained through:

```java
LlmProviderRegistry.getChatClientOrDefault(provider)
```

Do not instantiate or configure model clients ad hoc inside business services.

Structured LLM output must use:

```java
StructuredOutputInvoker
```

Do not duplicate:

* retry logic,
* parsing fallback,
* structured-output recovery

inside business modules.

Prompt templates belong in:

```text
app/src/main/resources/prompts/
```

Use StringTemplate `.st` files.

Do not bury large prompts inside Java or Python source files when they belong to reusable prompt resources.

---

# 11. Agent Tool Architecture

Tools are the contract between the Agent Runtime and business capabilities.

A Tool should have:

* a clear purpose,
* a small input schema,
* a clear output schema,
* minimal unrelated fields,
* ownership validation,
* predictable failure behavior.

Tools should not contain Agent reasoning.

Correct:

```text
Agent decides:
"Need recent interview performance"

        ↓

Tool:
get_interview_history

        ↓

Java returns structured business data
```

Incorrect:

```text
Tool internally decides:
what user should study next,
whether to interview,
how to update the plan,
and what Agent should say.
```

That reasoning belongs to the Agent or a clearly defined business policy.

---

# 12. Tool Permission Levels

Tools should conceptually belong to one of three categories.

## READ

Read-only business operations.

Examples:

```text
get_resume
get_resume_analysis
get_interview_history
get_interview_report
get_skill_profile
get_preparation_progress
search_knowledge
```

The Agent may usually execute READ Tools automatically.

---

## SAFE_WRITE

Low-risk writes that may be automated but must be auditable.

Examples may include:

```text
save_agent_artifact
update_execution_progress
```

Use sparingly.

---

## CONFIRM_WRITE

Operations with meaningful business side effects must require explicit user confirmation.

Examples:

```text
create_preparation_plan
replace_preparation_plan
modify_resume
delete_resource
overwrite_profile
```

Flow:

```text
Agent proposes action
      ↓
Interrupt / confirmation block
      ↓
User confirms
      ↓
Tool executes
```

Do not let the model silently perform destructive or high-impact writes.

---

# 13. Career Copilot Response Protocol

Career Copilot responses are not always plain text.

The Agent may return:

```text
content
+
structured blocks
```

Conceptual response:

```json
{
  "content": "你当前 JVM 表现仍然偏弱。",
  "blocks": [
    {
      "type": "skill_profile",
      "data": {}
    },
    {
      "type": "action",
      "data": {}
    }
  ]
}
```

Supported block concepts may include:

```text
text

action

navigation

confirmation

skill_profile

progress

chart

preparation_plan

task_list

interview_summary
```

The exact schema must remain strongly typed.

Do not allow LLM output to directly contain arbitrary:

* React code,
* HTML,
* JavaScript,
* frontend component names without validation,
* unrestricted URLs.

The frontend renders known block types through a controlled renderer.

---

# 14. Navigation Actions

The Agent may suggest navigation.

Example:

```text
User:
我想练一下 JVM

Agent:
建议开始一次 JVM 专项模拟面试。

[开始模拟面试]
```

The Agent may return a navigation action describing:

```text
route
label
params
```

The frontend decides how to render and execute it.

The Agent must not directly manipulate browser navigation.

Prefer:

```text
Agent recommendation
→ user click
→ frontend router
```

over automatic unexpected page transitions.

---

# 15. Frontend Responsibilities

The React frontend owns:

* Copilot Workspace,
* conversation rendering,
* streaming UI,
* structured message blocks,
* business cards,
* navigation actions,
* confirmation UI,
* existing business pages.

The frontend does NOT perform Agent reasoning.

API calls belong in:

```text
frontend/src/api/
```

Reuse the existing Axios instance from `request.ts`.

Shared types belong in:

```text
frontend/src/types/
```

Do not redefine the same API contracts inside individual pages.

Pages belong in:

```text
frontend/src/pages/
```

Reusable UI belongs in:

```text
frontend/src/components/
```

Route constants belong in:

```text
frontend/src/constants/routes.ts
```

Reuse the existing design language and `lucide-react` icons.

---

# 16. RAG Rules

Keep and reuse the original InterviewGuide RAG architecture.

Current RAG infrastructure includes:

```text
PostgreSQL
+
pgvector
```

Vector settings:

```text
dimension = 1024
distance = COSINE
```

Career Copilot treats RAG as a capability / Tool.

Preferred flow:

```text
Career Agent
     ↓
Need external knowledge?
     ↓
search_knowledge
     ↓
Java Knowledge Base
     ↓
pgvector
     ↓
Retrieved evidence
     ↓
Agent response
```

Do not automatically run vector retrieval for every conversation.

RAG should be used when knowledge evidence is relevant.

Avoid meaningless behavior such as:

```text
User: 你好

→ vector search("你好")
```

---

# 17. Long-Term Profile Rules

Career Copilot must maintain a long-term user profile.

Profile is NOT equivalent to a summary of conversation history.

Profile should be based on evidence.

Possible evidence sources:

```text
Resume

Interview

Preparation

Job Match

Learning Activities
```

Conceptually:

```text
Skill Profile
=
Structured Evidence
+
Aggregation
+
LLM Explanation
```

LLMs may:

* summarize,
* explain,
* extract observations,
* recommend actions.

LLMs should not arbitrarily overwrite numerical skill scores without evidence.

Example:

```text
JVM = 52
```

should be explainable through sources such as:

```text
Interview #101 → 48

Interview #117 → 56

Preparation Tasks → partially completed
```

Profile changes should remain traceable.

---

# 18. Memory Model

Career Copilot separates memory into three concepts.

## Working Memory

Used during the current Agent Run.

Examples:

```text
messages
intent
goal
plan
current step
tool results
pending action
```

---

## Episodic Memory

Important historical user events.

Examples:

```text
Completed JVM interview

Received low RabbitMQ score

Finished preparation plan

Targeted a specific job
```

Do not save every conversation sentence as an episodic memory.

---

## Semantic / Profile Memory

Stable, long-lived knowledge about the user.

Examples:

```text
target role

skill levels

persistent strengths

persistent weaknesses

learning preferences
```

Do not blindly inject all historic messages into every prompt.

Retrieve only context relevant to the current task.

---

# 19. Adaptive Interview Architecture

The realtime interview loop belongs to the Java Interview Engine.

It does NOT belong inside the main Career Agent LangGraph.

Career Agent responsibilities:

```text
recommend interview

create interview configuration

start interview

read interview result

use result for future planning
```

Interview Engine responsibilities:

```text
question pool

question graph

turn evaluation

follow-up

skip

difficulty adjustment

coverage tracking

time budget

next-question selection
```

The realtime interview should behave like a deterministic state machine with LLM-assisted semantic evaluation.

---

# 20. Selection Before Generation

The most important adaptive interview principle is:

> Selection Before Generation.

Do NOT default to:

```text
User Answer
    ↓
LLM full evaluation
    ↓
Agent reasoning
    ↓
LLM generates next question
    ↓
User waits
```

Preferred architecture:

```text
User Answer
    ↓
Lightweight Turn Evaluation
    ↓
Decision Policy
    ↓
Question Pool
    ↓
Select next question
```

Only use dynamic generation as fallback when the Question Pool has no suitable candidate.

Expected design:

```text
mostly selection

sometimes templates / rules

rare dynamic LLM generation
```

---

# 21. Interview Decision Boundaries

LLMs may help determine semantic facts such as:

```text
answer quality

covered knowledge points

missing knowledge points

recommended follow-up type
```

Code should enforce hard business constraints such as:

```text
maximum follow-up count

topic time budget

remaining interview time

difficulty bounds

topic coverage

question duplication
```

Principle:

> LLM decides meaning; code controls boundaries.

Do not give the model unrestricted control over the entire interview lifecycle.

---

# 22. Interview Evaluation

Realtime turn evaluation should remain lightweight.

Realtime evaluation exists mainly to support:

```text
FOLLOW_UP

NEXT_QUESTION

NEXT_TOPIC

UPGRADE

DOWNGRADE
```

Do not generate a full interview report after every answer.

Detailed evaluation belongs after interview completion and should normally be asynchronous.

Post-interview evaluation may produce:

```text
overall score

topic scores

per-question feedback

strengths

weaknesses

skill evidence

profile updates
```

---

# 23. Async Rules

Redis Stream producers and consumers should use the existing abstractions:

```text
AbstractStreamProducer

AbstractStreamConsumer
```

Before asynchronous processing:

* verify the target entity still exists,
* if the entity was deleted, ACK and discard the stale task when appropriate.

Do not keep database transactions open while waiting for:

* LLM,
* S3,
* HTTP,
* long-running external services.

Long operations should be modeled as asynchronous tasks when appropriate.

---

# 24. Rate Limiting

Use the existing repeatable:

```java
@RateLimit
```

infrastructure.

Do not implement scattered hand-written Redis rate limiting logic.

---

# 25. Configuration

Configuration belongs in:

```text
application.yml

.env

@ConfigurationProperties
```

Do not scatter:

```java
@Value
```

through Service classes.

Sensitive values such as:

* API Keys,
* Tokens,
* database passwords,

must only exist in local or deployment environment configuration.

Never commit secrets to Git.

Local backend default:

```text
server.port: ${SERVER_PORT:8080}
```

Development may use:

```text
ddl-auto=update
```

Production must not rely on automatic schema creation.

---

# 26. Development Workflow

Before modifying code:

1. Read this `AGENTS.md`.
2. Read the closest relevant rule file under `.claude/rules/`.
3. Search for existing implementations.
4. Understand the existing call chain.
5. Identify the smallest safe change.
6. Only then start coding.

Do not immediately create new files simply because the requested feature sounds new.

---

# 27. Change Scope

Prefer minimal, focused changes.

Do not:

* refactor unrelated modules,
* rename unrelated packages,
* move large directory trees,
* change public APIs unnecessarily,
* delete code you do not understand,
* introduce an abstraction because it “might be useful later”.

Every change should have a concrete reason connected to the current task.

---

# 28. Architectural Complexity

Do not prematurely introduce:

```text
microservices

Kafka

Kubernetes

Multi-Agent

A2A

complex event buses

reflection loops

self-modifying prompts

dozens of Tools
```

without a demonstrated requirement.

Do not turn simple logic into unnecessary combinations of:

```text
Factory

Manager

Registry

Coordinator

Dispatcher

Executor

Strategy
```

Existing abstractions should be reused when they solve the problem.

New abstractions must solve an actual current problem.

---

# 29. LangGraph Usage

Not every request requires LangGraph.

Simple interactions such as:

```text
查询复习进度

查询能力画像

搜索知识库

推荐一个页面
```

may follow a short routing / Tool flow.

Use complex planning graphs only when the task genuinely requires:

```text
multiple dependent steps

long-running execution

checkpointing

interrupts

replanning

multiple Tools
```

Do not use LangGraph complexity as a substitute for ordinary application logic.

---

# 30. Testing

Backend:

```bash
./gradlew :app:compileJava

./gradlew :app:test --no-daemon
```

Run:

```bash
./gradlew :app:bootRun
```

Frontend:

```bash
cd frontend && pnpm run dev

cd frontend && pnpm run build
```

Infrastructure:

```bash
docker compose -f docker-compose.dev.yml up -d
```

Backend tests use:

```text
JUnit 5

Mockito

AssertJ
```

Use Chinese `@DisplayName` descriptions for test intent.

Use `@Nested` for complex grouped scenarios.

Integration tests may use H2 where compatible.

Tests that depend on Redis-specific behavior require real Redis.

When modifying backend common infrastructure, run at least:

```bash
./gradlew :app:test --no-daemon
```

When modifying frontend behavior, run at least:

```bash
cd frontend && pnpm run build
```

For Python Agent changes, run the relevant Agent tests and type/lint checks configured by `agent-service`.

---

# 31. Verification Before Completion

Before considering a task complete:

* compile or typecheck affected code,
* run relevant tests,
* run frontend build when frontend changed,
* verify critical flows when practical,
* review the final diff for unrelated changes.

Final task summary should state:

```text
What changed

Why it changed

What was verified

Known limitations / remaining risks
```

Do not claim success without verification.

---

# 32. Never Do

Never:

* throw raw `RuntimeException` for expected business failures,
* return JPA Entity objects directly to frontend,
* scatter `@Value` through Services,
* call LLM / S3 / external HTTP inside DB transactions,
* rely on same-class internal `@Transactional` calls,
* silently swallow exceptions,
* perform DB calls inside avoidable loops,
* hardcode secrets,
* use `Executors.newXxxThreadPool()`,
* let Python directly mutate Java-owned business tables,
* duplicate the existing RAG pipeline without explicit architectural intent,
* put the realtime interview turn loop inside the main Career Agent graph,
* dynamically generate every interview question by default,
* let arbitrary LLM output directly control React components or browser navigation,
* perform destructive Agent writes without explicit confirmation,
* rewrite existing modules before understanding their current behavior.

---

# 33. Engineering Memory

When agents discover important project knowledge, preserve only information with long-term reuse value.

Good memory candidates:

```text
business flows

non-obvious module boundaries

field mappings

important invariants

validated call chains

architectural decisions

recurring pitfalls

hidden dependencies
```

Do NOT record:

```text
routine code changes

temporary debugging logs

every Git commit

obvious facts visible directly from code

one-off implementation details
```

Prefer durable knowledge over activity logs.

---

# 34. Architecture Decisions

When making an important architectural decision that future developers may question, document:

```text
Decision

Reason

Trade-off

Consequences
```

Examples of existing project decisions:

```text
Java is the System of Record.

Python Agent uses Tools instead of direct business DB access.

Existing Java RAG is reused as an Agent capability.

Realtime interview loop remains in Java.

Selection is preferred over generation for adaptive interviews.

Profile is evidence-driven rather than chat-summary-driven.
```

Do not silently reverse these decisions during unrelated tasks.

---

# 35. More Rules

Backend Java rules:

```text
.claude/rules/backend.md
```

AI, rate limit and async rules:

```text
.claude/rules/ai-and-async.md
```

Frontend rules:

```text
.claude/rules/frontend.md
```

When `agent-service/` becomes active, maintain dedicated rules for Python Agent development under:

```text
.claude/rules/agent-service.md
```

If a rule in a more specific file conflicts with this root document, do not blindly choose one.

First determine whether the root architectural constraint is intentional. Architectural boundaries in this file should normally take precedence over local implementation convenience.
