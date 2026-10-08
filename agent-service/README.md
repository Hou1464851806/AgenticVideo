# Agent Service：Python 智能决策与视频理解

负责剪辑意图理解、视频语义检索、自主规划、工具调用与执行恢复。Agent API、Agent Worker 和 Analysis Worker 共用一个 Python 工程，通过不同入口运行。

## 当前状态与边界

**计划中：** 本目录已建立设计文档，尚未初始化工程或实现业务功能。以下内容来自 v0.1 架构预研，章节编号沿用原设计，便于追溯。容量、预算和性能参数均为设计建议，尚无实测结果。

## 必要依赖（设计选型）

Python + FastAPI + LangGraph；PostgreSQL + pgvector、RabbitMQ、MinIO；LLM、VLM、ASR 与 Embedding 模型；视频分析工具选型见 analysis/README.md。Python 版本与依赖管理工具待确定。

## 运行与验证

尚无安装、环境准备、启动、格式检查、静态检查、构建或测试命令；构建配置、依赖版本、锁文件和 CI 待工程初始化时确定。初始化后应在所属工程 README 中补充确切命令与工作目录。

本阶段仅可核对文档内容、相对链接与 Markdown 结构，并在仓库根目录执行 `git diff --check`；这些检查不代表功能验证。

## 已知限制

本文保留设计目标和示例，不构成已实现的接口、数据库迁移、JSON Schema 或部署配置。模块功能、故障恢复与集成行为仍待实现和验证。

## 阅读导航

- [系统总览与需求](../README.md)。
- [api：FastAPI 边界](api/README.md)。
- [agent：决策循环与结构化输出](agent/README.md)。
- [analysis：分析流水线与语义索引](analysis/README.md)。
- [tools：注册、执行与治理](tools/README.md)。
- [workers：异步执行入口](workers/README.md)。
- [Java 业务工具 API](../backend/api/README.md)。
- [数据所有权](../docs/data-model/README.md)。
- [对话与 Checkpoint](../docs/conversation/README.md)。
- [消息可靠性](../docs/messaging/README.md)。

## 服务边界

Python 不直接修改 Java 业务表，也不直接持久化权威剪辑计划。读取和修改业务资源通过 Java Internal Tool API 完成，视频分析与 Agent 执行状态由 Python 管理。

`api/` 负责 FastAPI 边界，`agent/` 负责规划与决策，`analysis/` 负责视频分析和检索，`tools/` 负责工具注册与治理，`workers/` 负责异步任务交接及执行入口。目录划分不表示这些组件已经可运行。
