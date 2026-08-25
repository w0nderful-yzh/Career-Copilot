# Career Copilot Agent Service

Career Copilot 的 Agent Runtime，负责 Agent 编排（意图识别、Routing、Planning、Tool Calling、Checkpoint、Human-in-the-loop）。

> 架构约束：本服务**不是业务后端**。所有业务数据（简历、岗位、面试、知识库、学习计划、画像）的 System of Record 是 Spring Boot Backend，Python 通过 Tool 调用 Java API 读写数据，禁止直接访问业务数据库。

## 技术栈

- Python 3.12 / uv
- FastAPI
- LangGraph
- OpenAI 兼容 LLM（langchain-openai）

## 快速开始

```bash
cp .env.example .env   # 编辑填入 BACKEND_BASE_URL / LLM_API_KEY

uv sync                # 安装依赖（生成 .venv）
uv run uvicorn career_copilot.main:app --reload
```

服务默认启动于 `http://localhost:8000`。

## 目录结构

> 当前为骨架阶段，仅包含工程配置。源码目录按需创建，不提前铺开。

```text
agent-service/
├── pyproject.toml
├── uv.lock
├── .python-version
├── .env.example
├── Dockerfile
└── README.md
```

## 开发命令

```bash
uv run pytest                 # 测试
uv run ruff check .           # Lint
uv run mypy src               # 类型检查
```

## 架构边界

```text
User → Frontend → Agent Service → Tool → Java Backend → PostgreSQL / Redis
```

- Python 只做 Agent 编排，不做业务逻辑。
- 所有 Tool 调用集中走 `clients/backend.py`。
- 结构化输出优先 Pydantic。
- 详细规则见 `.claude/rules/agent-service.md`。
