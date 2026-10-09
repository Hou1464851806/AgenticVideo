# Workers：Agent 与视频分析异步执行

承接原设计中的 Python Agent Worker、Python Analysis Worker 与消息 Consumer 入口，分别控制 Agent 任务与分析任务的并发及资源消耗。

## 当前状态与边界

**计划中：** 本目录已建立设计文档，尚未初始化工程或实现业务功能。以下内容来自 v0.1 架构预研，章节编号沿用原设计，便于追溯。容量、预算和性能参数均为设计建议，尚无实测结果。

## 必要依赖（设计选型）

M2：Python、PostgreSQL 持久化任务与租约、分析 provider 适配器；API/Analysis Worker 分进程。原预研的 LangGraph Checkpointer 与 RabbitMQ 分别留到 M3/M4。

## 运行与验证

尚无安装、环境准备、启动、格式检查、静态检查、构建或测试命令；构建配置、依赖版本、锁文件和 CI 待工程初始化时确定。初始化后应在所属工程 README 中补充确切命令与工作目录。

本阶段仅可核对文档内容、相对链接与 Markdown 结构，并在仓库根目录执行 `git diff --check`；这些检查不代表功能验证。

## 已知限制

本文保留设计目标和示例，不构成已实现的接口、数据库迁移、JSON Schema 或部署配置。模块功能、故障恢复与集成行为仍待实现和验证。

## 阅读导航

- [M2 视频理解与检索计划（尚未实现）](../../docs/milestone-2-plan.md)。

- [系统总览与需求](../../README.md)。
- [Python 工程](../README.md)。
- [Agent 执行逻辑](../agent/README.md)。
- [分析流水线](../analysis/README.md)。
- [任务交接、ACK、租约与消息契约](../../docs/messaging/README.md)。
- [取消与过期结果控制](../../docs/conversation/README.md)。

## 运行入口规划

Agent API、Agent Worker、Analysis Worker 是可分进程运行的逻辑入口。Consumer 应在任务可靠持久化且可恢复后 ACK，Worker 执行中维护 Checkpoint、心跳与租约；具体交接流程与重试规则统一维护在消息可靠性文档中。M2 先使用 PG 领取分析任务与短 HTTP 持久受理，不依赖 RabbitMQ；代次、租约、取消与预算约束见 M2 计划。现阶段没有可执行的启动脚本。
