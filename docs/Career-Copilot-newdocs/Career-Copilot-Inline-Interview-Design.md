# Career Copilot 内嵌模拟面试设计文档

> 文档用途：指导 Career Copilot 将模拟面试从“独立配置页入口”重构为“Copilot 内发起 + 内嵌执行”的 Agent 化交互  
> 核心原则：**面试发起 Agent 化，面试执行引擎化，结果回流 Copilot**  
> 适用范围：Frontend / Python Agent Service / Java Interview Module

---

# 1. 背景

当前模拟面试流程为：

```text
Copilot
 ↓
点击“开始模拟面试”
 ↓
跳转 /interview-hub
 ↓
选择面试模式
 ↓
选择岗位方向
 ↓
选择难度
 ↓
选择更多配置
 ↓
创建面试
 ↓
进入面试页面
```

该流程存在两个问题。

第一，用户已经在 Copilot 中表达了意图，例如：

```text
根据这份简历来一场模拟面试
```

但系统仍要求用户进入新的配置页面重新选择：

```text
岗位方向
难度
模式
```

导致 Agent 已经知道上下文，却没有真正帮助用户减少操作。

第二，Copilot 与 Interview 页面之间体验割裂：

```text
Copilot
→ 跳业务页
→ 做面试
→ 用户手动返回
```

不利于形成：

```text
Copilot
→ Interview
→ Evidence
→ Profile
→ Copilot
```

的完整闭环。

---

# 2. 重构目标

新的模拟面试体验调整为：

```text
用户在 Copilot 表达面试意图
 ↓
Agent 根据 Resume / Profile / Job 推导配置
 ↓
通过对话确认方向、难度、模式
 ↓
创建 Interview Session
 ↓
在 Copilot 页面内显示 InterviewSessionBlock
 ↓
Java Interview Engine 独立执行面试
 ↓
Interview Report
 ↓
Evidence
 ↓
Skill Profile Update
 ↓
Copilot 给出下一步建议
```

目标是：

> 用户体验上始终停留在 Copilot，技术架构上仍保持 CareerAgent 与 Interview Engine 解耦。

---

# 3. 核心边界

必须明确：

```text
面试创建
→ Career Agent 负责

面试执行
→ Java Interview Engine 负责

面试展示
→ React InterviewSessionBlock 负责

面试结果解释
→ Career Agent 负责
```

禁止：

```text
每一轮面试回答
→ Copilot 主 Graph
→ Intent Router
→ Tool
→ Interview
```

实时面试不进入 CareerAgent Graph。

---

# 4. 推荐总体架构

```text
                     Copilot
                        │
            用户表达模拟面试意图
                        │
                        ▼
                  CareerAgent
                        │
             Resume + Profile
                        │
                        ▼
               Interview Proposal
                        │
             ┌──────────┴──────────┐
             │                     │
          [开始]                  [调整]
             │
             ▼
       create_interview Tool
             │
             ▼
       Java Interview Engine
             │
             ▼
       InterviewSessionBlock
             │
       ┌─────┴─────┐
       │           │
   Question     Answer
       │           │
       └─────┬─────┘
             ▼
      Turn Evaluation
             ↓
      Decision Policy
             ↓
      Next Question
             ↓
           ...
             ↓
          Complete
             ↓
         Evidence
             ↓
       Skill Profile
             ↓
          Copilot
```

---

# 5. 新的产品流程

## 5.1 用户发起

用户：

```text
根据这份简历给我来场模拟面试
```

Agent 已知：

```text
Active Resume
Skill Profile
Conversation Context
```

系统不再直接跳 `/interview-hub`。

---

## 5.2 Agent 推荐面试配置

CareerAgent 可以自动推导：

```text
方向
难度
模式
重点 Topic
预计时长
```

例如：

```text
结合你的简历和当前能力画像，我推荐：

Java 后端 · 校招难度 · 项目深挖

重点考察：
JVM / Redis / 项目设计

预计时长：
约 25 分钟

[按推荐开始]
[调整配置]
```

---

# 6. 面试参数推导

Agent 可基于：

```text
Resume
Job / JD
Skill Profile
Recent Interview
User Intent
```

推导：

```json
{
  "mode": "TEXT",
  "direction": "JAVA_BACKEND",
  "difficulty": "CAMPUS",
  "focus": [
    "PROJECT",
    "JVM"
  ]
}
```

---

# 7. 面试配置交互

如果用户希望调整：

```text
面试方向：
[Java 后端] [AI Agent 开发] [项目深挖]

难度：
[校招] [中级] [高级]

方式：
[文字] [语音]
```

这些选项使用 Structured ChoiceBlock。

不是让用户跳页面填写表单。

---

# 8. Agent 与配置页的关系

原 `/interview-hub` 不删除。

新的定位：

```text
Copilot
→ Agent 推荐 / 快速创建

/interview-hub
→ 手动高级配置入口
```

即：

```text
Agent = 默认入口
Workspace = 手动管理入口
```

---

# 9. /interview-hub 后续定位

未来 `/interview-hub` 可以改为：

```text
模拟面试

最近面试

Java 后端       73
JVM 专项        68
项目深挖        77

[创建自定义面试]
```

只有点击：

```text
创建自定义面试
```

才显示完整配置项。

因此 `/interview-hub` 不再是每场面试的必经入口。

---

# 10. 创建 Interview

用户确认：

```text
[按推荐开始]
```

前端发送结构化 Action：

```json
{
  "type": "ACTION_SELECTED",
  "action": "CREATE_INTERVIEW",
  "payload": {
    "mode": "TEXT",
    "direction": "JAVA_BACKEND",
    "difficulty": "CAMPUS",
    "focus": ["PROJECT", "JVM"]
  }
}
```

CareerAgent 调用：

```text
create_interview
```

流程：

```text
CareerAgent
 ↓
create_interview Tool
 ↓
CareerBackendClient
 ↓
Java InterviewService
 ↓
Interview Session
```

返回：

```text
interviewId
```

---

# 11. InterviewSessionBlock

创建成功后不跳页面。

Copilot 消息区域插入：

```text
InterviewSessionBlock
```

建议协议：

```ts
interface InterviewSessionBlock {
  type: "interview_session";

  interviewId: number;

  status:
    | "READY"
    | "RUNNING"
    | "COMPLETED";

  direction: string;

  difficulty: string;

  mode: "TEXT" | "VOICE";

  focus?: string[];
}
```

---

# 12. InterviewSessionBlock UI

文字面试：

```text
┌────────────────────────────────────┐
│ 模拟面试                           │
│ Java 后端 · 校招 · 项目深挖       │
│                                    │
│ 3 / 10                    12:32    │
│ ███████░░░                         │
│                                    │
│ Q3                                 │
│ 你在项目中为什么选择 Redis        │
│ 而不是直接查询数据库？             │
│                                    │
│ ┌────────────────────────────────┐ │
│ │ 输入你的回答...                │ │
│ └────────────────────────────────┘ │
│                                    │
│                         [提交回答] │
└────────────────────────────────────┘
```

---

# 13. 前端组件建议

```text
InterviewSessionBlock
├── InterviewHeader
├── InterviewProgress
├── CurrentQuestion
├── AnswerComposer
├── Timer
├── InterviewActions
└── InterviewResult
```

---

# 14. Interview Running 状态

进入面试后，Copilot 页面进入：

```text
Interview Mode
```

普通聊天 Composer 应：

```text
隐藏
或
Disable
```

避免用户不知道：

```text
应该在面试卡片回答
还是在 Copilot 输入框回答
```

---

# 15. 面试过程 API

InterviewSessionBlock 直接调用 Java Interview API。

推荐：

```text
React
 ↓
Java Interview API
 ↓
Interview Engine
```

不是：

```text
React
 ↓
CareerAgent Graph
 ↓
Tool
 ↓
Interview Engine
```

---

# 16. 实时面试执行

Java Interview Engine：

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

---

# 17. 自适应面试流程

```text
Question
 ↓
User Answer
 ↓
Turn Evaluation
 ↓
Decision Policy
 ↓
FOLLOW_UP
or
NEXT_QUESTION
or
NEXT_TOPIC
or
UPGRADE
or
DOWNGRADE
or
END
```

继续遵循：

```text
Selection Before Generation
```

优先从 Question Pool 选题。

---

# 18. 右侧 Context Panel

面试运行期间，右侧 Context Panel 可以切换为 Interview Context。

例如：

```text
模拟面试

Java 后端
校招

进度
4 / 10

当前 Topic
JVM

已覆盖
✓ Redis
✓ Spring

待覆盖
○ JVM
○ MQ

剩余时间
18 min
```

原：

```text
当前目标
活跃资源
今日任务
```

可暂时折叠或弱化。

---

# 19. 面试内容不要全部变成 Conversation Message

一场完整面试可能包含：

```text
10 个主问题
10 个回答
多个追问
```

如果全部进入 Copilot Message：

```text
Q1
Answer
Q2
Answer
Follow-up
Answer
...
```

Conversation 会过长。

推荐：

> 面试过程集中维护在 InterviewSessionBlock 内。

---

# 20. 面试结束 UI

面试结束后，InterviewSessionBlock 折叠为结果卡：

```text
┌───────────────────────────┐
│ 模拟面试完成              │
│                           │
│ Java 后端 · 26 min        │
│ 综合得分 73               │
│                           │
│ JVM     61                │
│ Redis   78                │
│ Spring  82                │
│                           │
│ [查看详细报告]            │
└───────────────────────────┘
```

Conversation 中保留这个 Artifact 即可。

---

# 21. 面试结束后的数据闭环

Java：

```text
Interview Completed
 ↓
Interview Report
 ↓
Skill Evidence
 ↓
Profile Aggregator
 ↓
Skill Profile
```

例如：

```text
JVM

Before:
54

After:
61
```

---

# 22. 面试完成后回流 Copilot

面试完成后重新交给 CareerAgent。

Copilot：

```text
这次模拟面试已经完成。

你的 Redis 和 Spring 表现稳定，
但 JVM 类加载和 GC 仍然偏弱。

相比上一次：

JVM
54 → 61

接下来建议：

[JVM 专项复习]
[再来一场 JVM 专项面试]
[查看完整报告]
```

形成：

```text
Copilot
 ↓
Interview
 ↓
Evidence
 ↓
Profile
 ↓
Copilot
```

---

# 23. CareerAgent 的职责

CareerAgent 负责：

```text
识别 Interview Intent

读取 Resume

读取 Profile

读取 Job

推荐 Interview Config

与用户确认配置

调用 create_interview

面试结束后解释结果

提供下一步 Action
```

---

# 24. CareerAgent 不负责

禁止 CareerAgent 负责：

```text
实时问题推进

每题 Answer Evaluation

Follow-up Decision

时间控制

题目去重

题目难度控制

Interview Session State
```

这些属于 Java Interview Engine。

---

# 25. Java Interview Engine 职责

Java 负责：

```text
Interview Session

Question Pool

Current Question

Turn Answer

Turn Evaluation

Decision Policy

Follow-up

Difficulty Change

Coverage

Time Budget

Interview Report

Evidence
```

---

# 26. React 职责

React 负责：

```text
InterviewSessionBlock

问题展示

Answer Composer

进度

计时

Interview Context

结果卡片
```

---

# 27. Voice Interview

文字面试优先直接内嵌。

语音面试由于 UI 更复杂：

```text
录音
波形
实时转写
静音
设备
时长
```

推荐仍然保留 `/copilot`，但切换：

```text
Interview Focus Mode
```

UI 可以扩大为：

```text
┌───────────────────────────────────────────────┐
│                                               │
│              AI Interviewer                  │
│                                               │
│       为什么 ThreadLocal 会导致内存泄漏？     │
│                                               │
│                   ◉                          │
│              正在聆听...                     │
│                                               │
│                 00:42                        │
│                                               │
│               [结束回答]                     │
│                                               │
└───────────────────────────────────────────────┘
```

第一阶段优先完成文字面试。

---

# 28. 第一阶段 MVP

必须完成：

```text
1. Copilot 自然语言发起面试

2. Agent 自动推荐面试方向 / 难度 / 重点

3. Structured Choice 调整配置

4. create_interview Tool

5. InterviewSessionBlock

6. 面试过程直接调用 Java Interview API

7. 普通 Copilot Composer 在面试时隐藏 / 禁用

8. 面试完成展示结果卡

9. Interview Evidence 更新 Skill Profile

10. Copilot 根据新 Profile 返回后续建议
```

---

# 29. 暂不实现

第一阶段不做：

```text
复杂语音 Interview Focus Mode

多人面试

多 Agent Interviewer

视频面试

实时虚拟人

复杂表情分析

自动摄像头行为分析
```

---

# 30. 验收场景

## Case 1

用户：

```text
根据这份简历给我来场模拟面试
```

系统：

```text
推荐 Java 后端 · 校招 · 项目深挖

[开始]
[调整]
```

---

## Case 2

用户点击：

```text
[开始]
```

系统：

```text
创建 Interview
```

并在当前 `/copilot` 页面显示 InterviewSessionBlock。

不能强制跳转 `/interview-hub`。

---

## Case 3

用户提交回答。

流程必须为：

```text
React
 ↓
Java Interview API
 ↓
Interview Engine
```

不经过 CareerAgent Graph。

---

## Case 4

面试结束。

系统：

```text
Interview Report
 ↓
Evidence
 ↓
Skill Profile Update
```

Copilot 返回下一步建议。

---

# 31. 最终产品闭环

```text
User
 ↓
Copilot
 ↓
Resume / Job / Profile
 ↓
Interview Proposal
 ↓
User Confirm
 ↓
Interview Session
 ↓
Adaptive Interview Engine
 ↓
Interview Report
 ↓
Evidence
 ↓
Skill Profile
 ↓
Copilot
 ↓
Next Action
```

---

# 32. 核心设计原则

```text
1. Agent 负责发起，不负责实时执行。

2. Interview Engine 是独立业务状态机。

3. 用户体验停留在 Copilot。

4. 技术实现保持 Java / Python 解耦。

5. 配置优先由 Agent 自动推导。

6. 用户只确认或调整关键参数。

7. 面试过程集中在 InterviewSessionBlock。

8. 面试结束后结果必须回流 Profile。

9. Profile 必须影响下一次 Interview。

10. /interview-hub 保留为高级手动入口。
```

---

# 33. 最终定位

旧模式：

```text
Copilot
→ 面试配置页
→ 面试页
→ 报告页
```

新模式：

```text
Copilot
→ Agent 推荐
→ Inline Interview
→ Profile Update
→ Copilot
```

最终目标不是：

> 把 Interview 页面强行塞进聊天框。

而是：

> 让 Copilot 成为模拟面试的统一入口和上下文中枢，同时让 Java Interview Engine 保持独立、稳定、可测试的实时执行能力。
