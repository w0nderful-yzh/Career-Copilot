# Career Copilot 自适应模拟面试引擎设计文档

## 1. 背景

原 InterviewGuide 的模拟面试采用预生成模式：

```text
创建面试
  ↓
一次性生成全部题目
  ↓
Q1
Q2
Q3
Q4
Q5
  ↓
用户按顺序回答
```

这种方案具有：

- 响应快
- 实现简单
- 面试流程稳定
- 不依赖实时 LLM 推理

等优点。

但也存在明显问题：

- 无法根据用户回答动态追问
- 无法根据回答质量调整难度
- 用户已经明显不会时仍继续按照原顺序提问
- 用户回答很好时无法继续深入
- 无法根据剩余时间动态调整题目
- 面试体验更像“AI 题库”而不是真实面试官

旧版 CareerAI 曾尝试改造为：

```text
用户回答
  ↓
Agent 分析
  ↓
判断追问 / 换题
  ↓
LLM 生成下一题
  ↓
展示下一题
```

虽然动态性更强，但实际体验较差。

核心问题在于：

> 每轮面试都依赖同步 LLM 推理和问题生成。

一次回答之后可能需要：

```text
回答评估
+
决策
+
问题生成
```

多个 LLM 调用串行执行。

这会带来明显的等待时间。

因此 Career Copilot 不采用完全静态，也不采用完全动态，而采用：

> **预生成 Question Graph + 实时轻量决策 + Lookahead 异步补题 + 动态生成兜底**

的混合架构。

---

# 2. 设计目标

Career Copilot 自适应面试引擎需要同时满足以下目标。

## 2.1 实时性

用户提交回答后，应尽快得到下一题。

理想目标：

```text
普通下一题：
< 500ms ~ 1s

需要轻量 LLM 判断：
1 ~ 2s

真正动态生成：
仅少数情况允许更高延迟
```

不得每一轮都阻塞等待复杂问题生成。

---

# 3. 自适应能力

系统应能够根据用户回答动态决定：

```text
追问
跳过
进入下一题
进入下一 Topic
提高难度
降低难度
请求补充
提前结束当前 Topic
```

---

# 4. 稳定性

Agent 不应拥有无限制追问能力。

必须通过代码限制：

```text
最大追问次数
Topic 最大时间
剩余时间
难度范围
题目数量
知识点覆盖率
```

LLM 负责语义判断。

代码负责流程边界。

---

# 5. 个性化

面试内容应该根据用户长期画像进行调整。

例如：

```text
Java       82
Spring     80
Redis      75
JVM        52
RabbitMQ   58
```

则创建 Java Backend Interview 时：

```text
Java       15%
Spring     15%
Redis      15%
JVM        25%
RabbitMQ   20%
Project    10%
```

弱项获得更高考察权重。

---

# 6. 可解释

每一次动态决策都应留下记录：

```text
为什么追问

为什么跳题

为什么提升难度

为什么结束当前 Topic
```

便于：

- Debug
- Agent Observability
- Interview Report
- Evaluation

---

# 7. 总体架构

最终架构：

```text
                      Career Copilot
                            │
                            │ create_interview
                            ▼
                  Interview Engine
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
         ▼                  ▼                  ▼
 Question Pool      Decision Engine    Coverage Tracker
         │                  │                  │
         └──────────────────┼──────────────────┘
                            │
                            ▼
                     Next Question
                            │
                            ▼
                          User
                            │
                            ▼
                     User Answer
                            │
                            ▼
                    Turn Evaluator
                            │
                            ▼
                    Decision Engine
                            │
                            └────→ Next Question
```

同时后台存在：

```text
Lookahead Generator
        │
        ▼
Question Pool
```

面试结束后：

```text
Interview Completed
        │
        ▼
Async Full Evaluation
        │
        ├── Interview Report
        ├── Skill Evidence
        ├── Profile Update
        └── Career Copilot Memory
```

---

# 8. 核心设计原则

整个系统最核心的原则是：

> **选择优先于生成。**

也就是说：

错误设计：

```text
每答一道题
    ↓
生成下一道题
```

正确设计：

```text
每答一道题
    ↓
判断下一步
    ↓
优先从已有 Question Pool 中选择
    ↓
只有不存在合适问题时才动态生成
```

预计比例：

```text
约 70% ~ 85%
从 Question Pool 直接选择

约 10% ~ 20%
模板 / Rule-based 生成

约 5% ~ 10%
真正调用 LLM 动态生成
```

---

# 9. Question Graph

传统 InterviewGuide 为：

```text
Question List
```

Career Copilot 改为：

```text
Question Graph
```

例如 JVM Topic：

```text
JVM

Q1：JVM 内存区域有哪些？
│
├── F1：堆和方法区分别存什么？
│
├── F2：对象一定分配在堆上吗？
│
└── F3：线程私有的区域有哪些？

Q2：Minor GC 和 Full GC 有什么区别？
│
├── F1：哪些情况会触发 Full GC？
│
├── F2：频繁 Full GC 怎么排查？
│
└── F3：CMS 和 G1 有什么区别？

Q3：介绍一下类加载机制。
│
├── F1：什么是双亲委派？
│
├── F2：为什么需要双亲委派？
│
└── F3：哪些场景会打破双亲委派？
```

Question Graph 并不是固定执行路径。

而是：

```text
候选问题集合
+
问题之间的语义关系
```

---

# 10. Question 数据结构

建议问题结构：

```json
{
  "id": 1001,

  "topic": "JVM",

  "knowledgePoint": "GC",

  "type": "MAIN",

  "difficulty": 2,

  "question": "Minor GC 和 Full GC 有什么区别？",

  "parentId": null,

  "tags": [
    "GC",
    "JVM",
    "Java Backend"
  ],

  "expectedPoints": [
    "young generation",
    "old generation",
    "stop-the-world",
    "trigger conditions"
  ]
}
```

Follow-up：

```json
{
  "id": 1002,

  "topic": "JVM",

  "knowledgePoint": "GC",

  "type": "FOLLOW_UP",

  "difficulty": 3,

  "question": "线上频繁 Full GC 你会怎么排查？",

  "parentId": 1001,

  "followupType": "DEPTH"
}
```

---

# 11. Follow-up 类型

Follow-up 可以提前分类。

建议至少支持：

```text
CLARIFICATION
澄清回答

DEPTH
继续深挖

SCENARIO
场景题

WHY
追问原理

TRADEOFF
询问优缺点

IMPLEMENTATION
询问实现方式

DEBUG
故障排查
```

例如：

```text
用户：
G1 使用 Region 来管理堆内存。

Decision：

FOLLOW_UP
type = DEPTH
```

系统从：

```text
G1 相关 DEPTH Questions
```

中选择：

```text
G1 是如何实现可预测停顿时间的？
```

无需重新生成。

---

# 12. Turn Evaluator

用户每次回答后，不执行完整面试评估。

只进行：

> Turn-level Evaluation

目的是：

```text
给下一题决策提供足够信息
```

而不是生成完整评价报告。

---

# 13. Turn Evaluation 输出

推荐结构：

```json
{
  "score": 72,

  "confidence": 0.86,

  "coverage": 0.68,

  "coveredPoints": [
    "Region",
    "Young GC"
  ],

  "missingPoints": [
    "停顿预测机制"
  ],

  "incorrectPoints": [],

  "answerState": "PARTIAL",

  "recommendedAction": "FOLLOW_UP",

  "recommendedFocus": "G1 pause prediction"
}
```

---

# 14. Answer State

回答状态可以限定为：

```text
EXCELLENT

GOOD

PARTIAL

WEAK

WRONG

NO_ANSWER
```

这比让模型返回长篇分析更加稳定。

---

# 15. 快速模型

Turn Evaluator 应优先使用：

```text
低延迟模型
+
temperature = 0
+
Structured Output
```

不需要使用整个系统最强模型。

因为这个任务本质上只是：

```text
Classification
+
Extraction
+
Scoring
```

而不是复杂推理任务。

---

# 16. Decision Engine

Decision Engine 输入：

```text
Turn Evaluation

Current Topic

Current Difficulty

Follow-up Count

Topic Time

Remaining Interview Time

Coverage

User Profile
```

输出：

```text
FOLLOW_UP

NEXT_QUESTION

NEXT_TOPIC

UPGRADE

DOWNGRADE

SKIP

END_INTERVIEW
```

---

# 17. Decision 数据结构

例如：

```json
{
  "action": "FOLLOW_UP",

  "topic": "JVM",

  "knowledgePoint": "GC",

  "difficulty": 3,

  "followupType": "DEPTH",

  "reason": "用户提到了 G1 Region，但未说明可预测停顿机制"
}
```

---

# 18. 代码优先规则

部分决策不应交给 LLM。

例如：

```text
if followupCount >= 2:
    NEXT_QUESTION
```

---

```text
if topicDuration > topicBudget * 1.3:
    NEXT_TOPIC
```

---

```text
if remainingTime < 5min:
    DISABLE_DEEP_FOLLOWUP
```

---

```text
if answer == NO_ANSWER:
    SKIP or DOWNGRADE
```

---

```text
if twoContinuousScores > 85:
    UPGRADE
```

---

```text
if twoContinuousScores < 40:
    DOWNGRADE
```

因此：

> LLM 判断内容，Policy 控制流程。

---

# 19. Next Question Policy

推荐独立实现：

```text
NextQuestionPolicy
```

职责：

```text
根据 Decision
+
Coverage
+
Question Pool

选择下一问题
```

流程：

```text
Decision
   │
   ▼
Query Question Pool
   │
   ├── Candidate Found
   │       ↓
   │   Rank Questions
   │       ↓
   │   Select Question
   │
   └── No Candidate
           ↓
    Dynamic Generator
```

---

# 20. Question Ranking

候选题排序可以综合：

```text
知识点相关度

难度匹配

是否已经问过

与当前回答的关联性

长期画像权重

当前 Topic 覆盖度

剩余时间
```

简单实现：

```text
score =
0.30 * relevance
+
0.20 * profilePriority
+
0.20 * coverageNeed
+
0.15 * difficultyMatch
+
0.15 * freshness
```

第一版不需要机器学习模型。

规则评分即可。

---

# 21. Coverage Tracker

为了防止系统一直追问某一个 Topic，需要维护：

```text
Coverage Tracker
```

例如：

```json
{
  "JVM": {
    "target": 0.25,
    "actual": 0.30
  },

  "Redis": {
    "target": 0.20,
    "actual": 0.08
  },

  "Spring": {
    "target": 0.20,
    "actual": 0.15
  },

  "Project": {
    "target": 0.15,
    "actual": 0.10
  }
}
```

如果：

```text
JVM actual >> target
```

即使当前用户回答一般，也应该考虑：

```text
记录 JVM weakness
    ↓
结束 JVM
    ↓
进入 Redis
```

而不是无限追问。

---

# 22. 时间控制

真实面试最大的约束之一是：

```text
时间
```

每个 Interview 应保存：

```text
totalDuration

remainingDuration

topicBudget
```

例如 30 分钟：

```text
Java       5 min

JVM        7 min

Redis      5 min

Spring     5 min

Project    6 min

Reserve    2 min
```

运行时允许轻微动态调整。

例如：

```text
JVM 表现很差
    ↓
JVM + 2 min

Spring 表现很好
    ↓
Spring - 1 min
```

但必须设置最大偏差。

---

# 23. Lookahead Generation

这是降低延迟的核心机制之一。

用户回答期间通常拥有：

```text
20秒
30秒
60秒
甚至更久
```

这段时间后台可以提前准备后续问题。

例如当前：

```text
Q3
```

用户正在回答。

后台：

```text
预测可能的下一路径

Q3 → Follow-up A

Q3 → Follow-up B

Q3 → Q4
```

提前保证这些问题存在于 Question Pool。

---

# 24. Lookahead 流程

```text
User starts answering Q3
        │
        ▼
LookaheadService
        │
        ├── Ensure Q3 Follow-ups
        │
        ├── Ensure Q4
        │
        └── Ensure Q4 Follow-ups
```

用户提交 Q3：

```text
Answer
  ↓
Turn Evaluation
  ↓
Decision
  ↓
Question Pool
  ↓
立即拿到问题
```

---

# 25. Lookahead 不要求百分百预测正确

Lookahead 只是：

```text
Speculative Preparation
```

即使预测错误，也只是多生成少量问题。

而不是影响面试结果。

---

# 26. Dynamic Question Generator

动态生成依然保留，但仅作为：

```text
Fallback
```

例如用户回答：

```text
我们线上碰到 Metaspace 一直上涨，
最后发现是动态代理导致 ClassLoader 泄漏。
```

Question Pool 可能没有合适问题。

Decision：

```text
DYNAMIC_FOLLOW_UP

topic:
ClassLoader Leak
```

才调用：

```text
DynamicQuestionGenerator
```

产生：

```text
如果 ClassLoader 无法被 GC，
它加载的 Class 会发生什么？
```

---

# 27. Dynamic Generator 输出也必须结构化

例如：

```json
{
  "topic": "JVM",

  "knowledgePoint": "ClassLoader",

  "difficulty": 3,

  "type": "FOLLOW_UP",

  "question": "ClassLoader 泄漏为什么会导致 Metaspace 持续增长？",

  "expectedPoints": [
    "Class unloading",
    "ClassLoader reachability",
    "Metaspace"
  ]
}
```

生成完成后：

```text
写入 Question Pool
```

而不是成为一次性文本。

---

# 28. 用户说“不会”

用户回答：

```text
不会
```

不要再调用复杂评估。

直接：

```text
answerState = NO_ANSWER
```

然后 Policy 判断：

```text
首次不会
    ↓
可选：
降难度

再次不会
    ↓
NEXT_TOPIC
```

例如：

```text
Q:
G1 为什么采用 Region？

User:
不会

下一题：
那你先说一下 JVM 堆主要分为哪些区域？
```

实现难度自适应。

---

# 29. 用户回答非常优秀

例如：

```text
score > 85
+
coverage > 0.8
```

可以：

```text
UPGRADE
```

下一问题优先：

```text
Scenario

Debug

Tradeoff
```

而不是继续问基础概念。

---

# 30. 长期画像联动

Career Copilot 的长期画像需要参与：

```text
面试创建
```

而不是只在回答后参与。

Profile：

```text
Java       82
Spring     80
Redis      75
JVM        52
MQ         58
```

初始 Topic Weight：

```text
JVM        HIGH

MQ         HIGH

Redis      MEDIUM

Spring     LOW
```

因此面试从创建阶段就已经个性化。

---

# 31. Profile 不直接限制问题

长期画像只是：

```text
Prior
```

不能因为：

```text
JVM = 52
```

就永远只问简单题。

因为面试本身也是一次新的测量。

需要允许：

```text
Profile = weak

Interview Answer = excellent

→ dynamically increase difficulty
```

---

# 32. 面试过程产生 Evidence

每一轮回答可以产生：

```text
Interview Evidence
```

例如：

```json
{
  "skill": "JVM",

  "knowledgePoint": "GC",

  "score": 78,

  "sourceType": "INTERVIEW_TURN",

  "sourceId": 1024
}
```

但在实时阶段：

```text
只记录
```

不要立即重新计算完整 Profile。

---

# 33. 面试结束后统一更新画像

流程：

```text
Interview Completed
       ↓
Full Evaluation
       ↓
Aggregate Turn Evidence
       ↓
Interview Skill Score
       ↓
Profile Updater
       ↓
Long-term Profile
```

例如：

```text
Before

JVM = 52


Interview

GC          78
ClassLoader 70
Memory      72


After

JVM = 61
```

Profile 更新应该考虑：

```text
历史结果

本次表现

数据时间

样本数量

Evidence Confidence
```

不能简单覆盖。

---

# 34. 面试结束后的完整评估

实时 Turn Evaluation 只负责下一题。

完整评估异步执行。

包括：

```text
总体得分

Topic 得分

逐题表现

优势

弱点

遗漏知识点

错误回答

表达能力

项目深度

建议复习内容
```

最终产生：

```text
InterviewReport
```

---

# 35. Career Copilot 接入方式

CareerAgent 不直接管理每一轮面试。

正确架构：

```text
CareerAgent
     │
     │ create_interview
     ▼
Interview Engine
     │
     │ realtime session
     ▼
Interview Completed
     │
     ▼
Interview Report
     │
     ▼
CareerAgent
```

也就是说：

```text
CareerAgent = 高层 Orchestrator

InterviewEngine = 实时业务引擎
```

不要：

```text
每一道问题都回 CareerAgent / LangGraph
```

否则实时链路会变得过重。

---

# 36. 为什么实时 Interview Engine 不采用 LangGraph

LangGraph 更适合：

```text
复杂 Goal

多 Tool

长任务

Checkpoint

Human-in-the-loop

Planning

Replanning
```

而模拟面试每轮实际上是：

```text
ASK

ANSWER

EVALUATE

DECIDE

NEXT
```

属于：

> 状态机 + Policy Engine

因此建议由 Java Interview 模块实现。

例如：

```text
InterviewSessionService

InterviewTurnService

QuestionPoolService

TurnEvaluationService

InterviewDecisionService

NextQuestionPolicy

CoverageTracker

LookaheadService
```

---

# 37. Java 模块建议

最终：

```text
modules/
└── interview/
    │
    ├── controller/
    │
    ├── service/
    │
    │   ├── InterviewSessionService
    │
    │   ├── InterviewTurnService
    │
    │   ├── TurnEvaluationService
    │
    │   ├── InterviewDecisionService
    │
    │   ├── QuestionPoolService
    │
    │   ├── LookaheadService
    │
    │   └── InterviewEvaluationService
    │
    ├── policy/
    │   ├── NextQuestionPolicy
    │   ├── DifficultyPolicy
    │   ├── CoveragePolicy
    │   └── TimePolicy
    │
    ├── model/
    │
    ├── repository/
    │
    └── dto/
```

---

# 38. Interview Session State

示例：

```json
{
  "sessionId": 1001,

  "status": "IN_PROGRESS",

  "currentTopic": "JVM",

  "currentQuestionId": 202,

  "questionCount": 4,

  "followupCount": 1,

  "remainingSeconds": 1240,

  "difficulty": 2,

  "coverage": {
    "JVM": 0.28,
    "Redis": 0.10,
    "Spring": 0.06
  }
}
```

---

# 39. 面试状态机

```text
CREATED

READY

ASKING

WAITING_ANSWER

EVALUATING

DECIDING

COMPLETED

FAILED
```

主要流程：

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
```

直到：

```text
COMPLETED
```

---

# 40. 第一版 MVP

第一版不要实现全部能力。

MVP 只实现：

```text
Question Graph

Turn Evaluation

FOLLOW_UP

NEXT_QUESTION

NEXT_TOPIC

追问上限

Coverage

简单 Difficulty Adjustment
```

暂不实现：

```text
Lookahead AI Prediction

复杂动态题生成

自动 Topic 时间动态分配

完整实时画像更新
```

---

# 41. Phase 1

目标：

> 把原固定 Question List 改成 Question Graph。

实现：

```text
Main Question

Follow-up Question

Difficulty

Topic

Knowledge Point
```

创建 Interview 时：

```text
一次生成：

主问题
+
候选追问
```

---

# 42. Phase 2

增加：

```text
Turn Evaluator
```

用户回答后：

```text
Answer
 ↓
Evaluate
 ↓
FOLLOW_UP / NEXT
```

不实时生成问题。

---

# 43. Phase 3

增加：

```text
Coverage Tracker

Difficulty Policy

Time Policy
```

实现真正的：

```text
Adaptive Interview
```

---

# 44. Phase 4

增加：

```text
Lookahead
```

利用用户回答时间提前补充 Question Pool。

---

# 45. Phase 5

增加：

```text
Dynamic Question Generator
```

仅用于特殊回答。

---

# 46. Phase 6

与 Career Copilot Profile 打通：

```text
Profile
 ↓
Interview Configuration

Interview
 ↓
Evidence

Evidence
 ↓
Profile Update
```

形成：

```text
Profile → Interview → Profile
```

闭环。

---

# 47. 最终完整链路

```text
Career Copilot

用户：
给我来场 Java 后端模拟面试
        │
        ▼
get_skill_profile
        │
        ▼
创建 Interview Config
        │
        ▼
弱项：
JVM
MQ
        │
        ▼
Generate Question Graph
        │
        ▼
Start Interview
        │
        ▼
Q1
        │
        ▼
Answer
        │
        ▼
Turn Evaluator
        │
        ▼
Decision
        │
        ├── FOLLOW_UP
        │
        ├── NEXT
        │
        ├── UPGRADE
        │
        └── NEXT_TOPIC
        │
        ▼
Question Pool
        │
        ▼
Next Question
        │
       ...
        │
        ▼
Interview Completed
        │
        ▼
Full Evaluation
        │
        ▼
Interview Report
        │
        ▼
Skill Evidence
        │
        ▼
Profile Update
        │
        ▼
Career Copilot

“你本次 JVM 表现从之前的薄弱提升到了中等，
但类加载仍然建议继续复习。”
```

---

# 48. 与原 InterviewGuide 相比

原方案：

```text
Create
 ↓
Generate All Questions
 ↓
Fixed Question List
 ↓
Answer
 ↓
Next Question
```

Career Copilot：

```text
Profile
 ↓
Generate Question Graph
 ↓
Answer
 ↓
Turn Evaluation
 ↓
Decision Engine
 ↓
Question Pool
 ↓
Adaptive Next Question
 ↓
Full Evaluation
 ↓
Profile
```

最大的升级并不是：

> “使用 Agent 生成问题”。

而是：

> **把模拟面试从固定题目列表升级为一个状态驱动、覆盖度感知、画像感知、难度自适应的实时 Interview Engine。**

---

# 49. 设计原则总结

整个模块开发过程中遵守以下原则：

```text
1. 选择优先于生成

2. 实时阶段只做轻量判断

3. 完整评估延后处理

4. LLM 判断语义

5. Java 控制业务边界

6. Question Pool 保证实时性

7. Lookahead 利用用户回答时间

8. Dynamic Generation 只作为兜底

9. Profile 影响面试，但不决定面试

10. CareerAgent 不管理单轮面试状态
```

---

# 50. 最终目标

Career Copilot 的模拟面试不应只是：

```text
AI 出题
+
用户回答
+
AI 打分
```

最终目标应该是：

> **一个能够根据用户长期能力画像初始化考察方向，并在实时面试过程中根据回答质量、知识覆盖、难度、追问次数和剩余时间动态选择下一题的自适应模拟面试系统。**

系统通过 Question Graph 与 Question Pool 保证响应速度，通过 Turn Evaluation 和 Decision Policy 实现动态追问与跳题，通过 Lookahead 和动态生成补足开放性，并在面试结束后将结果转化为长期 Skill Evidence，持续反哺 Career Copilot 的用户画像与准备计划。

这样模拟面试不再是 Career Copilot 中一个孤立功能，而会成为：

```text
长期画像
   ↓
模拟面试
   ↓
能力验证
   ↓
画像更新
   ↓
学习计划调整
```

这一核心成长闭环中的关键执行组件。