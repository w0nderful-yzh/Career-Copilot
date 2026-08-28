# Career Copilot 简历优化功能需求文档

## 1. 功能名称

**Resume Optimization**

简历智能优化 / 修改建议 / JD 定向优化。

---

## 2. 功能背景

Career Copilot 已将简历作为用户求职上下文中的核心资源。

现有简历能力主要包括：

- 简历上传
- 文件解析
- 简历内容提取
- 简历分析
- 模拟面试

下一阶段需要增加“简历优化”能力，使用户可以直接通过 Copilot 发起：

```text
帮我优化一下这份简历
```

```text
按照 Java 后端实习方向优化
```

```text
根据这份 JD 帮我修改简历
```

系统根据用户简历、目标岗位、长期能力画像及已有真实经历生成结构化修改建议。

---

## 3. 核心目标

该功能不是简单调用 LLM 重写整份简历。

目标流程：

```text
Resume
   ↓
Career Agent
   ↓
读取相关上下文
   ↓
生成 Resume Patch
   ↓
用户查看 Diff
   ↓
用户确认
   ↓
应用修改
   ↓
生成新的 Resume Version
```

核心原则：

> Agent 负责提出修改建议，用户决定是否应用。

---

## 4. 用户入口

### 4.1 Copilot 文件上传

用户在 `/copilot` 页面拖入简历：

```text
JavaResume.pdf
```

系统识别文件类型：

```text
RESUME
```

Copilot 返回：

```text
我识别到这是一份简历。

你想用它做什么？

[分析简历]
[优化简历]
[模拟面试]
[岗位匹配]
```

点击：

```text
[优化简历]
```

进入优化流程。

### 4.2 自然语言触发

用户可以直接输入：

```text
帮我优化当前简历
```

或者：

```text
把项目经历写得更像 Java 后端岗位一点
```

Agent 判断：

```text
intent = RESUME_OPTIMIZATION
```

如果当前 Conversation 已绑定 Resume，则直接使用。

如果存在多份候选 Resume，则要求用户选择。

---

## 5. 优化模式

第一版支持三种优化模式。

### 5.1 通用优化

用户：

```text
帮我优化一下简历
```

重点处理：

- 表达冗余
- 技术栈堆砌
- 项目描述不清晰
- 缺少问题 / 行动 / 结果
- 技能栏排序
- 表述专业性

### 5.2 目标方向优化

例如：

```text
按照 Java 后端实习方向帮我优化
```

或：

```text
按照 AI 应用开发方向优化
```

Agent 根据目标方向调整：

- 项目优先级
- 技术关键词
- 技能顺序
- 描述重点

### 5.3 JD 定向优化

用户提供目标 JD：

```text
按照这份字节 Java 后端 JD 优化我的简历
```

系统结合：

```text
Resume
+
Job Description
+
Skill Profile
+
Career Evidence
```

生成针对该岗位的修改建议。

---

## 6. 核心业务流程

```text
用户发起简历优化
        ↓
确定 Resume
        ↓
确定优化模式
        ↓
读取 Resume
        ↓
读取 Resume Analysis
        ↓
可选读取 Job / JD
        ↓
读取 Skill Profile
        ↓
读取 Career Evidence
        ↓
Agent 分析
        ↓
生成 ResumePatch[]
        ↓
前端展示修改建议
        ↓
用户接受 / 忽略
        ↓
确认应用修改
        ↓
Java Backend 应用 Patch
        ↓
生成 Resume Version
```

---

## 7. Resume Patch

Agent 不直接返回完整的新简历。

所有修改以结构化 `ResumePatch` 表示。

示例：

```json
{
  "id": "patch_001",
  "operation": "REPLACE",
  "section": "PROJECT",
  "itemId": "career-copilot",
  "field": "description",
  "before": "使用 LangGraph 实现 Agent 功能",
  "after": "基于 LangGraph 构建 Stateful Agent 工作流，通过 Tool Calling 编排简历分析、岗位匹配与模拟面试等业务能力",
  "reason": "减少技术栈堆砌，突出 Agent 编排职责",
  "status": "PENDING"
}
```

---

## 8. Patch 类型

第一版支持：

```text
REPLACE
ADD
DELETE
REORDER
```

### REPLACE

修改现有内容。

### ADD

增加新的 Bullet / 内容。

### DELETE

建议删除冗余内容。

删除操作必须明确展示给用户。

### REORDER

调整：

- 技能顺序
- 项目顺序
- Bullet 顺序

---

## 9. Patch 状态

```text
PENDING
ACCEPTED
REJECTED
APPLIED
```

流程：

```text
PENDING
 ↓
用户操作
 ↓
ACCEPTED / REJECTED

ACCEPTED
 ↓
用户最终确认
 ↓
APPLIED
```

---

## 10. 前端交互

优化结果推荐采用 Diff UI。

例如：

```text
项目经历 · Career Copilot

原内容
────────────────────────
使用 LangGraph 实现 Agent 功能。

建议修改
────────────────────────
基于 LangGraph 构建 Stateful Agent 工作流，
通过 Tool Calling 编排多个求职业务能力。

修改原因
────────────────────────
原描述偏技术栈罗列，
没有体现实际架构职责。

[接受]
[忽略]
```

支持：

```text
接受当前修改
忽略当前修改
接受全部
忽略全部
```

第一版不要求实现完整在线 Word 编辑器。

---

## 11. 修改确认

用户选择 Patch 后：

```text
已选择 5 项修改。

应用后将生成新的简历版本，
原始简历不会被覆盖。

[确认应用]
[取消]
```

该操作属于：

```text
CONFIRM_WRITE
```

只有用户确认后才允许调用写 Tool。

---

## 12. Resume Version

任何 Agent 修改不得覆盖原始简历。

例如：

```text
Java 后端简历

V1
原始版本

V2
通用表达优化

V3
Java 后端岗位版

V4
ByteDance JD 定向版
```

版本至少记录：

```text
id
resumeId
version
sourceVersionId
optimizationType
targetJobId
createdAt
```

---

## 13. 真实性约束

简历优化必须遵循：

> 不允许创造用户不存在的经历或成果。

Agent 可以：

```text
优化表达
调整内容顺序
减少冗余
突出已有技术能力
根据 JD 调整关键词
建议用户补充缺失信息
```

Agent 禁止：

```text
虚构实习经历
虚构项目
虚构技术栈
虚构性能指标
虚构用户数量
虚构 QPS
虚构业务成果
虚构奖项
```

例如用户没有提供数据：

```text
优化接口性能
```

不得直接修改为：

```text
接口性能提升 70%
```

应提示：

```text
建议补充实际优化前后的响应时间或性能数据。
```

---

## 14. Evidence 约束

优化可以参考：

```text
Resume
Resume Analysis
Job Description
Skill Profile
Interview Evidence
Career Evidence
```

例如：

```text
长期画像：

JVM = 52
```

Agent 不应该建议：

```text
深入掌握 JVM
```

可建议：

```text
熟悉 JVM 内存模型、GC 基础及常见排查思路
```

长期画像用于辅助判断：

```text
描述强度
能力重点
岗位匹配度
```

---

## 15. Career Evidence

后续建议增加：

```text
CareerEvidence
```

用于保存已经确认过的真实经历。

例如：

```text
工业磅秤
→ TCP
→ Redis
→ MES

贴标系统
→ MES
→ AGV 搬运任务

Career Copilot
→ Agent Tool
→ Adaptive Interview
```

优化简历时优先使用已确认 Evidence。

---

## 16. Java Backend 职责

Java 负责：

```text
Resume 数据管理
Resume Version
Resume Patch 持久化
文件解析
文件存储
用户权限校验
Patch 应用
版本生成
PDF / DOCX 导出
```

Java 是 Resume 数据的 System of Record。

---

## 17. Python Agent 职责

Python Agent 负责：

```text
识别优化意图
选择优化策略
读取 Resume Context
读取 JD / Profile / Evidence
调用 LLM
生成 ResumePatch[]
生成修改原因
生成整体优化建议
```

Python 不直接修改 Resume 数据库。

---

## 18. Tool 设计

### READ Tool

```text
get_resume
get_resume_analysis
get_job
get_skill_profile
get_resume_evidence
```

### WRITE Tool

```text
apply_resume_patches
```

该 Tool：

```text
permission = CONFIRM_WRITE
```

必须在用户确认后执行。

---

## 19. Agent 流程

简单版本：

```text
RESUME_OPTIMIZATION
      ↓
load_resume_context
      ↓
load_optional_job
      ↓
load_profile
      ↓
load_evidence
      ↓
generate_resume_patches
      ↓
build_resume_diff_response
      ↓
WAITING_USER
```

用户确认：

```text
ACTION_SELECTED
      ↓
apply_resume_patches
      ↓
create_resume_version
      ↓
build_success_response
      ↓
END
```

该流程可以作为 Career Copilot Turn Graph 的一个 Branch。

第一版不需要单独创建复杂 Planner。

---

## 20. Structured UI

建议新增：

```text
ResumeOptimizationBlock
```

协议示例：

```json
{
  "type": "resume_optimization",
  "resumeId": 1001,
  "patches": [
    {
      "patchId": "patch_001",
      "section": "PROJECT",
      "before": "...",
      "after": "...",
      "reason": "...",
      "status": "PENDING"
    }
  ]
}
```

---

## 21. 优化完成后的响应

例如：

```text
已应用 5 项修改，并生成新的简历版本。

Java 后端简历 V2

本次主要调整：

• 强化项目技术职责
• 删除冗余表达
• 提升 Java 后端关键词匹配度
• 调整技能栏顺序

[查看新版本]
[继续优化]
[基于该简历模拟面试]
```

---

## 22. 后续与其他模块联动

### 与 Job 联动

```text
JD
+
Resume
→
Targeted Resume Optimization
```

### 与 Profile 联动

```text
Skill Profile
→
判断描述强度
```

### 与 Interview 联动

简历优化完成后：

```text
[根据新简历模拟面试]
```

### 与 Preparation 联动

岗位差距较大时：

```text
Resume Optimization
 ↓
Skill Gap
 ↓
Preparation Plan
```

---

## 23. 第一版 MVP

必须实现：

```text
1. 用户选择 / 上传 Resume
2. Copilot 发起简历优化
3. 通用优化
4. JD 定向优化
5. Agent 生成 ResumePatch
6. 前端显示 Before / After / Reason
7. 用户接受 / 忽略 Patch
8. 用户确认写入
9. 创建新的 Resume Version
10. 原始版本保持不变
```

---

## 24. 暂不实现

第一阶段不做：

```text
完整 Word 在线编辑器
复杂拖拽排版
大量 Resume Template
自动投递
自动修改原 PDF
多人协作
简历公开链接
自动生成虚构量化指标
```

---

## 25. 验收场景

### Case 1：普通优化

输入：

```text
帮我优化一下当前简历
```

期望：

```text
读取当前 Resume
生成若干 Patch
展示 Diff
允许接受 / 忽略
```

### Case 2：JD 定向优化

用户上传：

```text
resume.pdf
byte-java-jd.pdf
```

输入：

```text
按照这份 JD 优化我的简历
```

期望：

```text
结合 Resume + JD
生成定向修改建议
```

### Case 3：部分接受

用户：

```text
接受 Patch 1 / 2 / 4
忽略 Patch 3
```

最终 Resume Version 只应用：

```text
1 / 2 / 4
```

### Case 4：禁止编造

Resume 中没有高并发数据。

Agent 不得生成：

```text
支撑百万级 QPS
```

应改为：

```text
建议补充真实性能数据。
```

---

## 26. Definition of Done

功能完成至少满足：

```text
Resume Optimization Intent 可识别
Resume Context 可读取
JD Context 可选读取
ResumePatch 使用结构化输出
Patch 可以单独接受 / 拒绝
Apply 操作需要用户确认
原始 Resume 不被覆盖
新 Resume Version 可查询
Agent 不直接写数据库
不存在明显事实编造
```

---

## 27. 核心设计原则

```text
Patch before Rewrite.
Evidence before Generation.
User Confirmation before Write.
Versioning before Overwrite.
Java owns Resume truth.
Python owns optimization reasoning.
```

中文：

```text
Patch 优先于整份重写
事实证据优先于自由生成
写操作必须经过用户确认
新版本优先于覆盖原版本
Java 管理简历事实
Python 负责优化决策
```

---

## 28. 最终目标

Career Copilot 的简历优化能力最终形成：

```text
Resume
   +
Job
   +
Profile
   +
Evidence
   ↓
Career Agent
   ↓
Resume Optimization
   ↓
ResumePatch[]
   ↓
Human-in-the-loop
   ↓
Resume Version
   ↓
Interview / Job Match / Preparation
```

使简历优化从普通的：

```text
AI 文本润色
```

升级为：

```text
基于用户真实经历和长期求职上下文的
可解释、可确认、可追溯简历优化工作流。
```
