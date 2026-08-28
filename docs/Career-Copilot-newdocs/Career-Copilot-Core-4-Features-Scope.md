# Career Copilot 核心功能开发范围

> 目标：接下来只完成 4 个核心功能，不继续扩展无关能力。  
> 项目定位：基于 InterviewGuide 进行 Agent 化重构的智能求职准备平台。  
> 核心原则：优先完成闭环，不追求功能数量。

---

# 1. 后续只做四个核心功能

Career Copilot 后续开发范围限定为：

```text
1. Copilot Agent 主入口
2. 简历优化
3. 自适应模拟面试
4. 长期用户能力画像
```

其他功能只有在支撑以上四项时才允许开发。

---

# 2. 核心功能一：Copilot Agent 主入口

## 目标

将 `/copilot` 作为整个系统的统一操作入口。

用户不再必须手动进入简历、面试、知识库等页面寻找功能，而是通过自然语言、文件和按钮表达意图。

## 必须完成

### 输入

支持：

```text
Text
File
Action
```

例如：

```text
“我最近准备得怎么样？”
拖入 resume.pdf
点击 [优化简历]
```

### Agent 主 Graph

实现：

```text
Input
 ↓
Intent Router
 ↓
Context
 ↓
Tool / Workflow
 ↓
Structured Response
```

主 Graph 负责：

```text
意图识别
简单 Tool Routing
附件处理
业务 Workflow 路由
Structured Response
```

### Tool Calling

至少打通：

```text
Python CareerAgent
 ↓
Tool
 ↓
CareerBackendClient
 ↓
Java Internal API
 ↓
Business Service
```

Java 仍然作为 System of Record。

### Structured UI

第一版支持：

```text
ChoiceBlock
NavigationBlock
ProgressBlock
SkillProfileBlock
ResumeOptimizationBlock
ConfirmationBlock
```

### Streaming

完成：

```text
React
 ↓
FastAPI
 ↓
LangGraph
 ↓
SSE
 ↓
React
```

前端只消费自定义 Copilot Event：

```text
message_delta
tool_started
tool_completed
block
run_status
done
error
```

不直接暴露 LangGraph 原生事件。

---

# 3. 核心功能二：简历优化

## 目标

用户可以直接：

```text
上传 Resume
+
可选上传 JD
↓
让 Agent 优化简历
```

不是简单让 LLM 重写整份简历。

## 核心流程

```text
Resume
+
Optional JD
+
Skill Profile
+
Evidence
 ↓
Career Agent
 ↓
ResumePatch[]
 ↓
Diff
 ↓
用户确认
 ↓
Apply
 ↓
Resume Version
```

## 必须完成

### 优化模式

至少支持：

```text
通用优化
目标方向优化
JD 定向优化
```

### ResumePatch

Agent 输出结构化 Patch：

```text
REPLACE
ADD
DELETE
REORDER
```

Patch 至少包含：

```text
before
after
reason
section
itemId
```

### Diff UI

前端展示：

```text
原内容
建议修改
修改原因

[接受]
[忽略]
```

### Human-in-the-loop

写入前必须：

```text
用户确认
 ↓
apply_resume_patches
```

不得直接覆盖用户简历。

### Resume Version

支持：

```text
V1 原始版本
V2 通用优化
V3 Java 后端版
V4 某 JD 定向版
```

原版本保留。

### 真实性约束

Agent 禁止：

```text
虚构技术
虚构项目
虚构实习经历
虚构性能数据
虚构量化结果
```

优化必须基于已有 Resume / Evidence。

---

# 4. 核心功能三：自适应模拟面试

## 目标

解决传统模拟面试：

```text
一次性生成全部问题
 ↓
固定 Q1 → Q2 → Q3
```

无法根据用户回答动态调整的问题。

## 核心架构

实时面试逻辑放在 Java Interview Engine。

不是放进 Career Agent LangGraph。

## 推荐流程

```text
创建面试
 ↓
Question Pool
 ↓
提问
 ↓
User Answer
 ↓
Turn Evaluation
 ↓
Decision Policy
 ↓
Follow-up / Next / Difficulty Change
 ↓
下一题
 ↓
...
 ↓
Interview Report
```

## 必须完成

### Question Pool

提前准备：

```text
Main Question
Follow-up Question
Depth Question
Scenario Question
```

### Turn Evaluation

每轮回答输出结构化结果：

```text
score
coverage
missingPoints
answerState
recommendedAction
```

### Decision Policy

代码控制：

```text
最大追问次数
Topic 时间
剩余时间
难度范围
重复问题
覆盖率
```

### 动态行为

至少支持：

```text
FOLLOW_UP
NEXT_QUESTION
NEXT_TOPIC
UPGRADE
DOWNGRADE
SKIP
END_INTERVIEW
```

### 核心原则

```text
Selection Before Generation
```

优先从 Question Pool 选择题目。

只有没有合适题目时，才动态调用 LLM 生题。

### Interview Report

面试结束生成：

```text
综合评分
Topic 表现
优势
薄弱点
关键 Evidence
```

并将结果提供给长期能力画像。

---

# 5. 核心功能四：长期用户能力画像

## 目标

让 Career Copilot 不只是完成一次任务，而是持续了解用户当前能力状态。

例如：

```text
Java      82
Spring    80
Redis     74
JVM       61
MQ        63
```

## Profile 数据来源

优先使用：

```text
Resume Evidence
Interview Evidence
Preparation Evidence
```

其中第一阶段重点使用：

```text
Resume
Interview
```

Preparation 可以后续作为补充，不单独扩展复杂功能。

## 核心原则

```text
Evidence-driven Profile
```

不是：

```text
LLM 觉得用户 JVM 很强
→ JVM = 85
```

而是：

```text
Interview Evidence
+
Resume Evidence
 ↓
Profile Aggregator
 ↓
Skill Score
```

## 必须完成

### Skill Profile

至少维护：

```text
skill
score
evidenceCount
updatedAt
```

### Evidence

每个能力评分应能够追溯到：

```text
Resume
Interview Session
Interview Turn
```

### Profile 更新

典型闭环：

```text
Skill Profile
 ↓
决定面试重点
 ↓
Adaptive Interview
 ↓
New Evidence
 ↓
Update Skill Profile
```

### Agent 使用 Profile

Profile 必须真正参与后续决策。

例如：

```text
JVM = 52
```

则：

```text
推荐 JVM 专项面试
简历中避免使用“深入掌握 JVM”
面试优先覆盖 JVM
```

否则 Profile 只是一个好看的数字页面，没有意义。

---

# 6. 必须完成的三条产品闭环

项目是否完成，不按照“页面数量”判断，而按照以下闭环是否真实跑通判断。

## 闭环一：简历 → JD → 优化

```text
Resume
+
JD
 ↓
Agent
 ↓
Gap / Match Analysis
 ↓
ResumePatch
 ↓
用户确认
 ↓
Resume Version
```

## 闭环二：画像 → 面试 → 新画像

```text
Skill Profile
 ↓
决定 Interview Focus
 ↓
Adaptive Interview
 ↓
Interview Evidence
 ↓
Profile Update
```

## 闭环三：Copilot → 业务能力 → 结果继续使用

```text
User Intent
 ↓
Copilot Agent
 ↓
Tool / Workflow
 ↓
Java Business Capability
 ↓
Structured Result
 ↓
Conversation / Context Update
```

例如：

```text
“根据我的简历来场 JVM 面试”
 ↓
Agent
 ↓
读取 Resume + Profile
 ↓
create_interview
 ↓
Adaptive Interview
 ↓
Interview Report
 ↓
Profile Update
```

---

# 7. 项目最终 Demo 主链

最终演示应该能够完整跑通：

```text
进入 /copilot
 ↓
上传 Resume
 ↓
上传 JD
 ↓
Agent 识别上下文
 ↓
进行简历定向优化
 ↓
用户确认修改
 ↓
生成新 Resume Version
 ↓
Agent 根据 Profile 创建模拟面试
 ↓
Adaptive Interview 动态追问
 ↓
生成 Interview Report
 ↓
产生新的 Skill Evidence
 ↓
更新 Skill Profile
 ↓
返回 Copilot
 ↓
Agent 根据新画像给出下一步建议
```

只要这条主链稳定，项目就可以停止继续扩展功能。

---

# 8. 明确不做的功能

除非四个核心功能已经全部完成，否则不开发：

```text
Multi-Agent
Agent Marketplace
插件系统
MCP Server
MCP UI
自动投递
Offer 管理
招聘信息爬虫
复杂 Job 推荐系统
复杂 Preparation Planner
完整学习管理系统
完整 Calendar
Voice Agent 重构
在线 Word 编辑器
大量简历模板
复杂 Dashboard
复杂 Observability 平台
复杂 Agent Evaluation 平台
```

这些东西对当前简历项目价值远低于完整闭环。

---

# 9. RAG 的定位

RAG 保留。

但只作为：

```text
Career Agent Tool
```

用于：

```text
知识问答
面试知识辅助
必要的求职知识检索
```

不再单独扩展成新的核心功能。

---

# 10. Preparation 的定位

Preparation 可以保留最小能力：

```text
简单计划
任务
进度
```

用于支持：

```text
Agent 给下一步建议
```

但暂不开发复杂：

```text
Planner
自动每日 Replan
长期学习调度
复杂 Workflow
```

除非四个核心功能已经完成。

---

# 11. Memory 的定位

第一阶段只实现：

## Conversation Memory

```text
conversation_id
最近消息
Conversation Summary
Active Resume
Active Job
```

## Long-term Memory

主要就是：

```text
Skill Profile
Interview Evidence
Resume Evidence
```

暂时不做复杂：

```text
全聊天历史向量化
复杂 Episodic Memory
自动 Memory Reflection
```

---

# 12. ReAct 的定位

主 Graph 不默认使用 ReAct Loop。

默认：

```text
Intent
 ↓
Tool
 ↓
Response
```

只有未来真正出现复杂多步 Goal 时再加入：

```text
Reason
 ↓
Act
 ↓
Observe
 ↓
Reason
```

当前四个核心功能不依赖复杂 ReAct 才能完成。

---

# 13. 推荐开发顺序

## Phase 1：Copilot Agent 主入口

完成：

```text
/copilot
Text
Action
Attachment
Intent Router
Tool Calling
SSE
Structured UI
```

并至少打通一个：

```text
Python → Java Tool
```

## Phase 2：简历优化

完成：

```text
Resume Optimization Subgraph
ResumePatch
Validation
Diff UI
HITL
Resume Version
```

## Phase 3：长期能力画像基础

完成：

```text
Skill Profile
Resume Evidence
Interview Evidence
Profile Aggregation
```

让 Profile 具备真实数据来源。

## Phase 4：自适应模拟面试

完成：

```text
Question Pool
Turn Evaluation
Decision Policy
Dynamic Follow-up
Interview Report
```

## Phase 5：打通闭环

重点验证：

```text
Profile
→ Interview
→ Evidence
→ Profile
```

以及：

```text
Resume + JD
→ Optimization
→ New Resume
```

最后再由 Copilot 串起来。

---

# 14. 简历项目最终技术亮点

四个功能完成后，项目简历重点应集中在：

```text
Java System of Record
+
Python Agent Runtime

LangGraph Stateful Workflow

Tool Calling

Structured UI

Human-in-the-loop

Resume Patch + Versioning

Adaptive Interview Engine

Question Pool + Decision Policy

Evidence-driven Skill Profile

SSE Streaming
```

其中核心亮点优先级：

```text
1. Java / Python Agent 架构边界
2. 自适应模拟面试
3. Evidence-driven Profile
4. Resume Optimization Workflow
5. LangGraph + Tool Calling + HITL
```

---

# 15. 项目停止扩展标准

当以下条件全部满足：

```text
[ ] Copilot 可以稳定处理 Text / File / Action
[ ] Agent 可以通过 Tool 调 Java
[ ] Resume + JD 可以完成定向优化
[ ] Resume Patch 可以确认并生成新版本
[ ] 自适应面试可以根据回答动态追问
[ ] Interview 可以生成结构化 Evidence
[ ] Evidence 可以更新 Skill Profile
[ ] Profile 可以影响下一次 Interview
[ ] Copilot 可以串联以上业务
[ ] 三条核心闭环全部可以真实 Demo
```

则认为：

> Career Copilot 已经达到简历项目完成标准。

此时停止继续增加功能。

后续时间优先投入：

```text
项目复盘
架构图
技术难点总结
性能 / 稳定性测试
README
Demo
简历描述
面试问答准备
```

---

# 16. 最终原则

接下来所有需求都先问：

```text
它是否直接服务于：

Copilot Agent
Resume Optimization
Adaptive Interview
Long-term Profile
```

如果不是：

```text
暂时不做。
```

项目接下来的目标不是：

> 做更多 AI 功能。

而是：

> 把四个核心功能之间的因果关系和数据闭环真正做通。
