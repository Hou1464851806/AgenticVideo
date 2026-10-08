# Backend：Java 业务控制中心

负责权威业务状态、业务 Workflow、权限校验、任务调度和 SSE 推送；采用模块化单体。Render Worker 与后端共用工程，但可以独立进程部署。

## 当前状态与边界

**计划中：** 本目录已建立设计文档，尚未初始化工程或实现业务功能。以下内容来自 v0.1 架构预研，章节编号沿用原设计，便于追溯。容量、预算和性能参数均为设计建议，尚无实测结果。

## 必要依赖（设计选型）

Java + Spring Boot；PostgreSQL、RabbitMQ、PGSTY Silo、Redis；渲染依赖 FFmpeg。M1 拟采用 JDK 21、Spring Boot 4.1.x 与 Maven Wrapper，具体版本与兼容性在工程初始化时锁定和验证；RabbitMQ、Redis 不纳入 M1 运行依赖。

## 运行与验证

尚无安装、环境准备、启动、格式检查、静态检查、构建或测试命令；构建配置、依赖版本、锁文件和 CI 待工程初始化时确定。初始化后应在所属工程 README 中补充确切命令与工作目录。

本阶段仅可核对文档内容、相对链接与 Markdown 结构，并在仓库根目录执行 `git diff --check`；这些检查不代表功能验证。

## 已知限制

本文保留设计目标和示例，不构成已实现的接口、数据库迁移、JSON Schema 或部署配置。模块功能、故障恢复与集成行为仍待实现和验证。

## 阅读导航

- [Milestone 1 开发范围、实施步骤与验收](../docs/milestone-1-plan.md)。
- [系统总览与需求](../README.md)。
- [Public API 与 Internal Tool API](api/README.md)。
- [Timeline：剪辑计划、DSL 与验证](timeline/README.md)。
- [Rendering：渲染任务与 Worker](rendering/README.md)。
- [数据所有权与实体关系](../docs/data-model/README.md)。
- [Conversation 与 Memory 协作](../docs/conversation/README.md)。
- [消息契约与可靠性](../docs/messaging/README.md)。
- [可观测性与评测](../docs/observability/README.md)。

## 目录划分

`api/`、`timeline/`、`rendering/` 承接已有详细设计。Auth、Project、Asset、Conversation、Agent Task、Workflow、Tool Gateway、Messaging、Observability 仍是同一 Java 工程内的逻辑模块；Java 包名与源代码目录待工程初始化时确定。

## 6.1 Java Backend：业务控制中心

### 6.1.1 职责

Java 后端是整个系统的业务控制中心。

主要负责：

- 用户与项目管理。
- 素材上传与管理。
- Conversation 生命周期。
- Agent Task 生命周期。
- Video Editing DSL 的持久化。
- 业务 Workflow。
- 权限校验。
- 视频渲染任务调度。
- SSE 事件推送。

推荐采用模块化单体架构：

```text
Java Backend
│
├── Auth Module
├── Project Module
├── Asset Module
├── Conversation Module
├── Agent Task Module
├── Workflow Module
├── Timeline Module
├── Rendering Module
├── Tool Gateway Module
├── Messaging Module
└── Observability Module
```

各模块通过明确的 Service 接口交互。

初期不需要让每个模块都成为独立微服务。

### 6.1.2 主业务 Workflow

建议 Java 管理以下状态：

```mermaid
flowchart TD
    A["素材已上传"] --> B["视频预处理"]
    B --> C["语义分析"]
    C --> D["素材已就绪"]

    D --> E["接收用户剪辑需求"]
    E --> F["创建 Agent Task"]

    F --> G["Agent 执行完成"]
    G --> H["校验剪辑方案"]

    H -->|"失败"| I["请求 Agent 修正"]
    I --> H

    H -->|"成功"| J["生成预览"]
    J --> K["用户审核"]

    K -->|"修改"| L["创建新 Agent Task"]
    L --> G

    K -->|"确认"| M["冻结确认版本"]
    M --> N["最终渲染"]
    N --> O["成片交付"]
```

这里建议将“生成预览”和“用户最终确认”区分开。

Agent 可以在预算许可范围内自动生成预览，但正式导出必须对应用户明确确认的版本。

---

## 4.4 Project

项目是系统的最高级业务对象。

建议字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID | 项目 ID |
| owner_id | UUID | 用户 ID |
| name | VARCHAR | 项目名称 |
| description | TEXT | 项目描述 |
| status | VARCHAR | 项目状态 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

一个 Project 可以拥有：

- 多个视频素材。
- 多个 Conversation。
- 多个 Edit Plan。
- 多个渲染结果。

这允许用户在同一批素材基础上制作不同版本的视频。

例如：

```text
Project：旅行素材
│
├── Assets
│   ├── A.mp4
│   ├── B.mp4
│   └── C.mp4
│
├── Edit Plan A：两分钟旅行 Vlog
│
├── Edit Plan B：30秒旅行短视频
│
└── Conversations
    ├── Conversation 1
    └── Conversation 2
```

## 4.5 Asset

素材表负责管理原始视频及其分析状态。

主要字段：

```text
id
project_id
filename
storage_key
content_hash
media_type
file_size
duration_ms
width
height
fps_num
fps_den
analysis_status
created_at
```

其中 `content_hash` 用于支持素材去重和分析缓存。

一旦素材被剪辑计划引用，应将对应的素材版本视为不可变对象。

如果用户重新上传同名文件，不能静默替换旧版本，否则可能导致历史剪辑方案无法复现。

## 4.6 Conversation 与 Message

建议把 Conversation 和 Message 分开存储。

**Conversation：**

```text
id
project_id
title
status
created_at
updated_at
```

**Message：**

```text
id
conversation_id
turn_id
client_message_id
role
content
content_type
created_at
```

其中：

- `conversation_id` 是长期会话标识。
- `turn_id` 用于关联一次用户请求及对应响应。
- `client_message_id` 用于前端请求幂等。
- `role` 区分 user、assistant、system_event 等类型。

建议额外设计 `ConversationTurn`：

```text
id
conversation_id
sequence
status
created_at
completed_at
```

它解决多轮对话中的消息排序、任务关联及处理中断问题。

对于同一个 Conversation，影响剪辑状态的 Agent 写入任务默认串行化。

## 4.7 Agent Task

Agent Task 是一次可独立执行、追踪、重试或取消的任务。

主要字段：

```text
id
conversation_id
turn_id
project_id
task_type
status
input_payload
result_reference
run_id
idempotency_key
created_at
started_at
completed_at
```

建议状态机：

```mermaid
stateDiagram-v2
    [*] --> QUEUED
    QUEUED --> RUNNING
    RUNNING --> WAITING_USER
    WAITING_USER --> QUEUED
    RUNNING --> SUCCEEDED
    RUNNING --> FAILED
    RUNNING --> CANCEL_REQUESTED
    QUEUED --> CANCELLED
    CANCEL_REQUESTED --> CANCELLED
    CANCEL_REQUESTED --> SUCCEEDED
    FAILED --> QUEUED: retry
    SUCCEEDED --> [*]
    CANCELLED --> [*]
```

这里保留 `CANCEL_REQUESTED → SUCCEEDED` 的可能性，是因为取消请求发出时任务可能已经完成。

真正决定是否接受结果的是 Java 的任务状态与版本检查。
