# Frontend：Vue 前端

负责用户交互、视频上传、多轮对话、时间线展示、任务反馈与成片预览。

## 当前状态与边界

**计划中：** 本目录已建立设计文档，尚未初始化工程或实现业务功能。以下内容来自 v0.1 架构预研，章节编号沿用原设计，便于追溯。容量、预算和性能参数均为设计建议，尚无实测结果。

## 必要依赖（设计选型）

Vue 3 + TypeScript；通过 Java Public API 与 SSE 访问业务状态，通过预签名 URL 将素材直接上传到 MinIO。前端构建工具及包管理器待选定。

## 运行与验证

尚无安装、环境准备、启动、格式检查、静态检查、构建或测试命令；构建配置、依赖版本、锁文件和 CI 待工程初始化时确定。初始化后应在所属工程 README 中补充确切命令与工作目录。

本阶段仅可核对文档内容、相对链接与 Markdown 结构，并在仓库根目录执行 `git diff --check`；这些检查不代表功能验证。

## 已知限制

本文保留设计目标和示例，不构成已实现的接口、数据库迁移、JSON Schema 或部署配置。模块功能、故障恢复与集成行为仍待实现和验证。

## 阅读导航

- [系统总览与需求](../README.md)。
- [业务 API 与上传流程](../backend/api/README.md)。
- [剪辑 DSL 与版本规则](../backend/timeline/README.md)。
- [多轮对话与任务状态](../docs/conversation/README.md)。

## 6.9 Vue：前端交互与实时反馈

### 6.9.1 建议页面

第一版可以包含：

| 页面 | 功能 |
|---|---|
| Project List | 项目列表 |
| Project Workspace | 项目主工作台 |
| Asset Library | 素材浏览、上传 |
| Agent Chat | 自然语言交互 |
| Timeline Preview | 剪辑时间线 |
| Render Preview | 成片预览 |
| Task Panel | 分析与渲染状态 |

### 6.9.2 工作台布局

可以采用：

```text
┌───────────────────────────────────────────────────┐
│                  项目名称 / 导出                    │
├────────────────┬──────────────────────────────────┤
│                │                                  │
│   素材列表      │           视频预览                │
│                │                                  │
├────────────────┤                                  │
│                ├──────────────────────────────────┤
│   Agent Chat   │                                  │
│                │           剪辑时间线              │
│                │                                  │
└────────────────┴──────────────────────────────────┘
```

用户在对话中提出要求后，Agent 可以修改右侧时间线，前端同时展示修改差异。

### 6.9.3 SSE

建议 Java 向前端推送：

```text
agent.task.started
agent.task.progress
agent.task.completed
agent.task.failed

asset.analysis.progress
asset.analysis.completed

edit.plan.updated

render.job.progress
render.job.completed
```

Spring MVC 提供 `SseEmitter` 支持服务端事件流，因此第一版不需要强制使用 WebSocket。[Home](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html?utm_source=chatgpt.com)

需要特别注意：

SSE 只负责实时通知，不应该成为唯一的状态来源。

前端断线重连后，应能够重新查询 Java 中持久化的任务状态。

建议为重要事件提供单调递增的事件序号，并支持基于 `Last-Event-ID` 恢复遗漏事件。

---
