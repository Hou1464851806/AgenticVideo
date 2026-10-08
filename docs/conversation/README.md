# Conversation & Memory：跨服务状态协作

维护 Java Conversation / Editing State 与 Python Agent Execution State 的协作边界、上下文构建、串行写任务、取消与过期结果处理。

## 当前状态与边界

**计划中：** 本目录已建立设计文档，相关工程尚未初始化，业务功能尚未实现。以下内容来自 v0.1 架构预研，章节编号沿用原设计，便于追溯。容量、预算和性能参数均为设计建议，尚无实测结果。

## 必要依赖（设计选型）

Java 业务状态、Python LangGraph Checkpointer、内部业务 API、RabbitMQ、不可变 Edit Plan Revision。

## 文档验证

本文无需安装或启动。修改后核对设计内容、相对链接、Markdown 结构及示例，在仓库根目录执行 `git diff --check`。相关工程的安装、启动、构建和测试命令尚待初始化；性能与集成行为需后续实测。

## 已知限制

本文保留设计目标和示例，不构成已实现的接口、数据库迁移、JSON Schema 或部署配置。模块功能、故障恢复与集成行为仍待实现和验证。

## 阅读导航

- [系统总览与需求](../../README.md)。
- [文档索引](../README.md)。
- [对话与 Agent Task 模型](../../backend/README.md)。
- [Conversation API](../../backend/api/README.md)。
- [Agent 决策](../../agent-service/agent/README.md)。
- [计划版本与 Patch](../../backend/timeline/README.md)。
- [消息可靠性](../messaging/README.md)。

## 6.5 Conversation & Memory：多轮对话管理

### 6.5.1 为什么需要独立设计？

视频剪辑不是一次性的问答任务。

用户可能经历：

```text
创建剪辑方案
    ↓
修改开头
    ↓
调整节奏
    ↓
替换素材
    ↓
重新生成预览
    ↓
修改字幕
    ↓
最终确认
```

因此，Agent 需要知道：

- 当前编辑的是哪个项目。
- 当前使用哪个 Edit Plan。
- 剪辑计划最新版本是什么。
- 用户已经提出了哪些约束。
- 哪些素材已经分析过。
- 上一轮执行了什么操作。

但这些信息不应该全部依赖 LLM 的聊天历史。

### 6.5.2 三层状态设计

| 状态 | 负责方 | 内容 |
|---|---|---|
| Conversation State | Java | 用户消息、对话轮次、会话生命周期 |
| Agent Execution State | Python | 工具调用、执行进度、Checkpoint |
| Editing State | Java | 当前剪辑计划、Revision、素材引用 |

Python 可以使用 LangGraph Checkpointer 保存 Agent 的线程状态，以支持持续对话、执行恢复和人工介入；LangGraph 同时提供与线程状态分离的持久化 Store。[GitHub](https://github.com/langchain-ai/docs/blob/main/src/oss/langgraph/persistence.mdx?utm_source=chatgpt.com)

建议让：

```text
conversation_id
       ↓
LangGraph thread_id
```

形成稳定映射。

不过，同一个 Conversation 的 Agent 写入任务必须避免并发恢复同一线程状态。

第一版可以规定：

**同一个 Conversation 同一时刻只允许一个活动 Agent 写任务。**

### 6.5.3 上下文构建

每次运行 Agent 时，不应简单地加载全部历史消息。

推荐：

```text
Agent Context
│
├── 当前用户指令
│
├── 当前项目摘要
│
├── 当前 Edit Plan 引用与 Revision
│
├── 最近 N 轮对话
│
├── 历史约束摘要
│
├── 相关视频语义检索结果
│
└── 本轮任务预算
```

注意：真正的 Edit Plan、素材元数据和权限状态必须从权威服务重新读取。

历史摘要只是辅助模型理解，不应代替业务状态。

### 6.5.4 用户中途修改

假设 Agent 正在生成剪辑方案时，用户又提出：

> 不要剪三条了，改成一条一分钟的视频。

建议采用以下策略：

1. Java 收到新消息。
2. 识别为对当前任务的替代请求。
3. 将旧任务标记为 `CANCEL_REQUESTED`。
4. 通知 Python 尝试取消。
5. 旧任务在安全检查点停止。
6. 创建新的 Agent Task。
7. 新任务基于最新项目状态运行。
8. 如果旧任务仍然返回结果，Java 必须检查任务代际和剪辑计划版本，拒绝过期修改。

不能只依赖 Python Agent 自己判断哪次请求更新。

---
