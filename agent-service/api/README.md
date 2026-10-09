# Agent API：FastAPI 接口边界

承接原架构中的 Python Agent API 入口，与 Agent Worker 和 Analysis Worker 共享 Python 工程。前端业务接口仍由 Java 提供。

## 当前状态与边界

**计划中：** 本目录已建立设计文档，尚未初始化工程或实现业务功能。以下内容来自 v0.1 架构预研，章节编号沿用原设计，便于追溯。容量、预算和性能参数均为设计建议，尚无实测结果。

## 必要依赖（设计选型）

Python + FastAPI；复用 agent/、analysis/ 与 tools/ 的能力；具体 HTTP 路由、鉴权和健康检查接口待详细设计。

## 运行与验证

尚无安装、环境准备、启动、格式检查、静态检查、构建或测试命令；构建配置、依赖版本、锁文件和 CI 待工程初始化时确定。初始化后应在所属工程 README 中补充确切命令与工作目录。

本阶段仅可核对文档内容、相对链接与 Markdown 结构，并在仓库根目录执行 `git diff --check`；这些检查不代表功能验证。

## 已知限制

本文保留设计目标和示例，不构成已实现的接口、数据库迁移、JSON Schema 或部署配置。模块功能、故障恢复与集成行为仍待实现和验证。

## 阅读导航

- [M2 视频理解与检索计划（尚未实现）](../../docs/milestone-2-plan.md)。

- [系统总览与需求](../../README.md)。
- [Python 工程](../README.md)。
- [Agent 决策组件](../agent/README.md)。
- [异步 Worker](../workers/README.md)。
- [Java 提供的接口边界](../../backend/api/README.md)。

## 接口范围

原预研仅确定 FastAPI 技术栈与独立启动入口。M2 计划新增内部分析受理、状态、取消、撤销与检索接口：任务持久入库后短 HTTP 返回 202，长任务由独立 Worker 执行，禁止用同步请求或内存队列替代可靠任务。浏览器仍经 Java 访问；RabbitMQ 传输留到 M4。计划路径尚未实现，详细契约在工程初始化时建立。
