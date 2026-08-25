<div align="center">

**Career Copilot** - 面向求职准备场景的智能职业 Copilot

用户表达目标，Agent 决定如何使用系统能力完成目标。

[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18.3-blue?logo=react)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-blue?logo=typescript)](https://www.typescriptlang.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-336791?logo=postgresql)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-AGPL--3.0-red.svg)](LICENSE)

</div>

---

## 项目介绍

Career Copilot 是一个 **Agent 驱动的求职准备平台**，由开源项目 InterviewGuide 演进而来。

与传统求职辅助系统不同，Career Copilot 不再要求用户主动寻找"简历分析""模拟面试""知识库""学习计划"等功能入口，而是以 **AI Agent 作为统一交互入口**：首页即对话工作台，用户只需要描述自己的目标：

```text
我准备找 Java 后端实习，帮我看看应该怎么准备。
我明天要面试字节 Java 后端，帮我准备一下。
我最近复习得怎么样？
给我来一场 Redis 专项模拟面试。
```

Career Copilot 结合用户简历、目标岗位、长期能力画像、历史模拟面试、学习进度与 RAG 知识库，自动理解用户意图并决定下一步动作：直接回答、查询业务数据、调用业务 Tool、展示结构化卡片、创建学习计划、发起模拟面试，或请求用户确认。

系统从 **Function Driven**（用户寻找功能）转变为 **Intent Driven**（用户表达意图），形成一个围绕求职目标持续工作的 Career Agent。

---

## 核心特性

### Career Agent 统一交互入口

- **意图识别**：识别普通问答、简历分析、岗位分析、学习计划、模拟面试、画像查询等意图，路由到对应能力。
- **结构化 Action**：Agent 消息不仅包含文本，还支持返回结构化动作，前端渲染真实业务组件：
  - `NAVIGATION`：跳转到业务页面并携带参数
  - `TOOL_CONFIRMATION`：具有业务副作用的操作请求用户确认（Human-in-the-loop）
  - `BUSINESS_CARD`：嵌入学习进度、能力画像等业务卡片
  - `CHART`：返回图表数据，由前端渲染
  - `TASK_LIST`：返回今日学习任务
  - `INTERRUPT`：缺少关键信息时暂停执行，等待用户选择后恢复
- **Tool 权限分级**：`READ` 直接调用、`SAFE_WRITE` 自动执行并记录审计、`CONFIRM_WRITE` 必须人工确认。

### 简历与岗位分析

- **简历管理**：多格式解析（PDF/DOCX/TXT）、异步分析（Redis Stream）、失败重试与重复检测、PDF 分析报告导出。
- **岗位分析**：JD 智能解析，简历与目标岗位的能力匹配，输出能力差距报告。

### 自适应模拟面试引擎 🚧 规划中

将模拟面试从固定题目列表升级为 **状态驱动、覆盖度感知、画像感知、难度自适应** 的实时面试引擎：

- **Question Graph**：主问题 + 候选追问 + 难度 + 知识点，形成候选问题集合而非固定执行路径。
- **Turn Evaluation**：每轮回答只做轻量级评分与结构化抽取（低延迟模型 + 结构化输出），为下一题决策提供信息。
- **Decision Engine**：根据回答质量、追问次数、Topic 时间、剩余时间与覆盖度决定追问 / 下一题 / 换 Topic / 提降难度。
- **代码优先规则**：LLM 判断内容语义，代码控制流程边界（追问上限、Topic 时间、难度范围）。
- **Question Pool + Lookahead**：优先从题目池中选择（选择优先于生成），利用用户作答时间后台预生成后续问题，动态生成仅作兜底。
- **画像联动**：面试创建时按长期画像分配 Topic 权重（弱项更高考察权重），结束后异步完整评估并反哺画像。

> 现状：当前代码库为固定题目列表模式，正在按上述设计分阶段演进（见[路线图](#路线图)）。

### 学习计划 Preparation 🚧 规划中

- 根据目标岗位与能力差距自动生成备考计划（任务 / 截止时间 / 状态流转）。
- 计划随模拟面试结果与画像演化自动 **Replan**：Plan → Act → Observe → Evaluate → Replan。

### 长期能力画像 Profile 🚧 规划中

- 持续演化的结构化用户模型：目标岗位、技能评分、优势劣势、学习偏好。
- 每个技能评分都有 **Evidence**（简历、面试轮次、学习任务），画像不依赖 LLM 主观生成。
- 三层 Memory：Working Memory（单次 Agent Run）、Episodic Memory（用户经历）、Semantic Profile（长期画像），不把全部历史塞入上下文。

### 知识库与 RAG

- 文档上传、解析、分块、异步向量化，PostgreSQL + pgvector 向量检索。
- 查询改写、相似度阈值、TopK 策略与引用来源。
- SSE 流式问答、会话管理、多知识库关联。
- 在 Career Copilot 中 RAG 定位为 **Agent Tool**：由 Agent 按需调用 `search_knowledge`，而非所有对话默认经过 RAG。

### 其他已实现能力

- **知识库题库面试**：从已向量化文档异步生成主问题 / 追问 / 评分标准，严格容量校验，统一评估与记录闭环。
- **语音面试**：WebSocket + Qwen3 语音模型，实时流式对话、服务端 VAD、回声防护、暂停/恢复。
- **面试安排**：邀请链接解析（飞书/腾讯会议/Zoom）、日历视图、状态流转与提醒。
- **多模型管理**：DashScope、DeepSeek、GLM、Kimi、LM Studio 等 Provider 可视化管理与默认模型切换。

---

## 系统架构

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

### Java 与 Python 职责边界

**Spring Boot（System of Record）** 负责全部真实业务数据：用户、简历、岗位、模拟面试、面试报告、知识库、学习计划、长期画像、文件存储、事务与数据一致性，以及 Tool 权限校验。

**Python Agent Service** 只负责 Agent 编排：意图理解、Agent State、Planning、Tool Selection、Tool Calling、Checkpoint、Human-in-the-loop、Memory Context 构建与 Replan。Python 不直接修改核心业务数据库，一切数据变更通过 Spring Boot API：

```text
Agent → Tool → Spring Boot API → Service → Repository → PostgreSQL
```

> 🚧 当前代码库尚未引入 Python Agent Service，Agent 编排层正在规划中。

### 核心业务闭环

```text
User Goal → Career Agent → Resume + Job → Gap Analysis → Preparation Plan
    → Learning / RAG → Mock Interview → Evaluation → Long-term Profile → Replan
```

---

## 技术栈

### 后端技术

| 技术                  | 版本  | 说明                          |
| --------------------- | ----- | ----------------------------- |
| Spring Boot           | 4.1.0 | 应用框架（System of Record）   |
| Java                  | 25    | 开发语言（虚拟线程）          |
| Spring AI             | 2.0.0 | AI 集成框架、OpenAI 兼容模型接入 |
| Spring AI Agent Utils | 0.10.0 | Skill 资源加载、Advisor 能力扩展 |
| PostgreSQL + pgvector | 14+   | 关系数据库 + 向量存储（Compose 默认 PG16） |
| Redis + Redisson      | 6+ / 4.0.0 | 缓存 + 消息队列（Stream） |
| Apache Tika           | 2.9.2 | 文档解析                      |
| iText 8               | 8.0.5 | PDF 导出                      |
| MapStruct             | 1.6.3 | 对象映射                      |
| SpringDoc OpenAPI     | 3.0.2 | API 接口文档                  |
| DashScope SDK         | 2.22.7 | 语音识别/合成（Qwen3 ASR/TTS）|
| AWS S3 SDK            | 2.29.51 | S3 兼容对象存储（MinIO/RustFS）|
| WebSocket             | -     | 语音面试实时双向通信          |
| Gradle                | 9.6.1 | 构建工具                      |
| Python / LangGraph    | -     | Agent 编排运行时 🚧 规划中     |

技术选型说明：

1. 数据存储为什么选择 PostgreSQL + pgvector？PG 的向量数据存储功能够用了，精简架构，不想引入太多组件。
2. 为什么引入 Redis？用 Redis 替代 `ConcurrentHashMap` 实现面试会话缓存，并基于 Redis Stream 实现简历分析、知识库向量化等异步任务（还能解耦，分析和向量化可以使用其他编程语言来做）。不引入 Kafka 这类成熟消息队列，同样是为了精简组件。
3. Agent 编排为什么用 LangGraph？LangGraph 提供 State、Planning、Checkpoint、Human-in-the-loop 等能力，适合复杂目标、多 Tool、长任务、可恢复的 Agent 工作流。

### 前端技术

| 技术              | 版本  | 说明           |
| ----------------- | ----- | -------------- |
| React             | 18.3  | UI 框架        |
| TypeScript        | 5.6   | 开发语言       |
| Vite              | 5.4   | 构建工具       |
| Tailwind CSS      | 4.1   | 样式框架       |
| React Router      | 7.11  | 路由管理       |
| Framer Motion     | 12.23 | 动画库         |
| Recharts          | 3.6   | 图表库         |
| Lucide React      | 0.468 | 图标库         |
| React Big Calendar| 1.19  | 面试日历组件   |
| React Virtuoso    | 4.18  | RAG 聊天虚拟列表 |
| pnpm              | 10.26 | 前端包管理器   |

---

## 项目结构

```
career-copilot/
├── app/                              # 后端应用
│   ├── src/main/java/interview/guide/
│   │   ├── App.java                  # 主启动类
│   │   ├── common/                   # 通用基础能力
│   │   │   ├── ai/                   # LLM Provider、结构化输出、Prompt 安全
│   │   │   ├── annotation/           # @RateLimit 可重复限流注解
│   │   │   ├── aspect/               # RateLimitAspect + Redis Lua 限流
│   │   │   ├── async/                # Redis Stream 生产者/消费者模板
│   │   │   ├── config/               # CORS、S3、OpenAPI、Jackson 等配置
│   │   │   ├── evaluation/           # 文字/语音共用的统一评估引擎
│   │   │   ├── exception/            # 业务异常与全局异常处理
│   │   │   └── result/               # 统一响应 Result<T>
│   │   ├── infrastructure/           # 基础设施
│   │   │   ├── export/               # PDF 导出
│   │   │   ├── file/                 # 文件解析、校验、清洗、S3 存储
│   │   │   ├── mapper/               # MapStruct 映射器
│   │   │   └── redis/                # RedisService、面试会话缓存
│   │   └── modules/                  # 业务模块
│   │       ├── interview/            # 模拟面试模块（自适应面试引擎演进中）
│   │       ├── interviewschedule/    # 面试安排模块
│   │       ├── knowledgebase/        # 知识库模块（RAG）
│   │       ├── llmprovider/          # 多模型 Provider 与语音配置
│   │       ├── resume/               # 简历模块
│   │       └── voiceinterview/       # 语音面试模块
│   └── src/main/resources/
│       ├── application.yml           # 应用配置
│       ├── prompts/                  # AI 提示词模板（StringTemplate）
│       ├── scripts/                  # Redis Lua 脚本
│       ├── skills/                   # 面试 Skill 定义和参考题库
│       └── voice-interview-opening.yml # 语音面试开场白配置
│
├── frontend/                         # 前端应用
│   ├── src/
│   │   ├── api/                      # API 接口
│   │   ├── components/               # 公共组件
│   │   ├── hooks/                    # 业务 Hooks
│   │   ├── pages/                    # 页面组件
│   │   ├── types/                    # 类型定义
│   │   └── utils/                    # 工具函数
│   ├── package.json
│   └── vite.config.ts
│
├── agent/                            # Python Agent Service 🚧 规划中
│
├── docker-compose.yml                # 完整部署：前端 + 后端 + PostgreSQL + Redis + MinIO
├── docker-compose.dev.yml            # 本地开发依赖：PostgreSQL + Redis + RustFS
├── docs/                             # 架构设计与改造记录
├── .env.example                      # 环境变量示例
└── README.md
```

---

## 快速开始

环境要求：

| 依赖          | 版本 | 必需 | 说明                                     |
| ------------- | ---- | ---- | ---------------------------------------- |
| JDK           | 25   | 是   | 开发语言                                 |
| Node.js       | 18+  | 是   | 前端构建                                 |
| pnpm          | 10+  | 推荐 | 前端包管理器（项目 packageManager 指定 10.26）|
| Docker        | -    | 推荐 | 一键启动依赖服务（PostgreSQL/Redis/RustFS）|

> 如果不用 Docker，需要自行安装 PostgreSQL 14+（含 pgvector 扩展）、Redis 6+ 和 S3 兼容存储。

### 1. 克隆项目

```bash
git clone https://github.com/w0nderful-yzh/Career-Copilot.git
cd Career-Copilot
```

### 2. 配置环境变量

推荐复制 `.env.example` 为 `.env`，后端 `bootRun` 会自动读取根目录 `.env`。最少需要填写 `AI_BAILIAN_API_KEY`，用于 DashScope 文本模型、ASR 和 TTS：

```bash
cp .env.example .env

# 编辑 .env
# AI_BAILIAN_API_KEY=your_dashscope_api_key
# AI_MODEL=qwen3.5-flash
```

如果你更习惯通过 shell 环境变量注入，也可以这样设置：

```bash
# macOS / Linux（zsh）
echo 'export AI_BAILIAN_API_KEY=your_api_key' >> ~/.zshrc
source ~/.zshrc
```

### 3. 启动依赖服务（可选）

项目提供了 `docker-compose.dev.yml`，可一键启动 PostgreSQL、Redis、RustFS（S3 兼容存储）三个依赖：

```bash
docker compose -f docker-compose.dev.yml up -d
```

启动后默认账号：

| 服务         | 地址             | 账号            | 密码            |
| ------------ | ---------------- | --------------- | --------------- |
| PostgreSQL   | `localhost:5432` | `postgres`      | `123456`        |
| Redis        | `localhost:6379` | -               | -               |
| RustFS 控制台 | `localhost:9001` | `rustfsadmin`   | `rustfsadmin`   |

> **注意**：应用启动时会自动检查并创建 `interview-guide` Bucket。使用 `docker-compose.dev.yml` + `:app:bootRun` 时，请确保 `.env` 中的 `APP_STORAGE_ACCESS_KEY` / `APP_STORAGE_SECRET_KEY` 与 RustFS 账号一致。如果本地已有 MinIO 或其他 S3 兼容存储，也可以直接使用，在 `.env` 中修改 `APP_STORAGE_*` 配置即可。

### 4. 启动应用

**后端：**

```bash
./gradlew :app:bootRun
```

后端服务启动于 `http://localhost:8080`

**前端：**

```bash
cd frontend
corepack enable
pnpm install
pnpm dev
```

前端服务启动于 `http://localhost:5173`

---

## Docker 快速部署

Docker Compose 编排了 6 个服务：PostgreSQL（pgvector）、Redis、MinIO（S3 兼容存储）、MinIO Bucket 初始化、Spring Boot 后端、React 前端（Nginx）。数据通过 Docker 命名卷持久化，`docker-compose down` 不会丢失数据。

### 1. 前置准备

- 安装 [Docker](https://www.docker.com/products/docker-desktop/) 和 Docker Compose
- 申请阿里云百炼 API Key（申请地址：<https://bailian.console.aliyun.com/>）

### 2. 快速启动

```bash
# 1. 复制环境变量配置文件
cp .env.example .env

# 2. 编辑 .env 文件，填入 AI 配置
# vim .env
# 必填：AI_BAILIAN_API_KEY=your_key_here
# 必填：APP_AI_CONFIG_ENCRYPTION_KEY=your_random_long_secret
# 可选：AI_MODEL=qwen3.5-flash   # 默认值为 qwen3.5-flash
# 也可以在设置页维护 DashScope、Kimi、DeepSeek、GLM、LM Studio 等 Provider

# 3. 构建并启动所有服务
docker-compose up -d --build
```

### 3. 服务访问

| 服务             | 地址                                           | 默认账号     | 默认密码     | 说明                   |
| ---------------- | ---------------------------------------------- | ------------ | ------------ | ---------------------- |
| **前端应用**     | [http://localhost](http://localhost)           | -            | -            | 用户访问入口           |
| **后端 API**     | [http://localhost:8080](http://localhost:8080) | -            | -            | RESTful API            |
| **接口文档**     | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | - | - | SpringDoc/Swagger UI |
| **MinIO 控制台** | [http://localhost:9001](http://localhost:9001) | `minioadmin` | `minioadmin` | 对象存储管理           |
| **MinIO API**    | `localhost:9000`                               | -            | -            | S3 兼容接口            |
| **PostgreSQL**   | `localhost:5432`                               | `postgres`   | `password`   | 数据库 (包含 pgvector) |
| **Redis**        | `localhost:6379`                               | -            | -            | 缓存与消息队列         |

### 4. 常用运维命令

```bash
# 查看服务状态
docker-compose ps

# 查看后端日志
docker-compose logs -f app

# 拉取新代码后重新构建部署
docker-compose up -d --build

# 停止并移除所有服务（数据保留在 Docker 卷中）
docker-compose down
```

---

## 路线图

### 自适应模拟面试引擎（Phase 1-6）

- [ ] Phase 1：固定题目列表 → Question Graph（主问题 + 候选追问 + 难度 + 知识点）
- [ ] Phase 2：Turn Evaluator（回答轻量评估 → 追问 / 下一题）
- [ ] Phase 3：Coverage Tracker + Difficulty Policy + Time Policy（实现真正的自适应面试）
- [ ] Phase 4：Lookahead（利用作答时间后台预生成问题）
- [ ] Phase 5：Dynamic Question Generator（仅作兜底）
- [ ] Phase 6：与长期画像打通（Profile → Interview → Profile 闭环）

### Agent 能力

- [ ] Career Agent 统一交互入口与意图路由（/copilot 工作台）
- [ ] 结构化 Action 协议（NAVIGATION / TOOL_CONFIRMATION / BUSINESS_CARD / CHART / INTERRUPT）
- [ ] Python Agent Service（LangGraph：State / Planning / Tool Calling / Checkpoint / Human-in-the-loop）
- [ ] 长期能力画像 Profile（Evidence 驱动，三层 Memory）
- [ ] 学习计划 Preparation（生成 / 进度 / 自动 Replan）
- [ ] RAG 接入 Agent Tool（search_knowledge 按需调用）

---

## 许可证

AGPL-3.0 License（只要通过网络提供服务，就必须向用户公开修改后的源码）
