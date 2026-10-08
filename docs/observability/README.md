# 可观测性与评测

维护系统、Agent、视频质量三层指标与跨语言 Trace 设计；评测基线与风险验证见根 README 第 7 章。

## 当前状态与边界

**计划中：** 本目录已建立设计文档，相关工程尚未初始化，业务功能尚未实现。以下内容来自 v0.1 架构预研，章节编号沿用原设计，便于追溯。容量、预算和性能参数均为设计建议，尚无实测结果。

## 必要依赖（设计选型）

OpenTelemetry 跨语言 Trace；HTTP、消息、模型调用和工具执行的关联标识；具体监控平台、采集器与评测脚本待实现。

## 文档验证

本文无需安装或启动。修改后核对设计内容、相对链接、Markdown 结构及示例，在仓库根目录执行 `git diff --check`。相关工程的安装、启动、构建和测试命令尚待初始化；性能与集成行为需后续实测。

## 已知限制

本文保留设计目标和示例，不构成已实现的接口、数据库迁移、JSON Schema 或部署配置。模块功能、故障恢复与集成行为仍待实现和验证。

## 阅读导航

- [系统总览与需求](../../README.md)。
- [文档索引](../README.md)。
- [容量假设与 SLO](../capacity/README.md)。
- [工具治理](../../agent-service/tools/README.md)。
- [根 README：风险、基线与里程碑](../../README.md)。

## 6.10 可观测性与评测

系统必须同时观测三个层次。

### 系统层

- API QPS。
- P95 / P99。
- 错误率。
- RabbitMQ 队列长度与最老消息等待时间。
- CPU、内存和存储。
- 视频渲染耗时。

### Agent 层

- Agent Task 成功率。
- LLM Token 使用量。
- Tool Call 数量。
- Tool Call 成功率。
- Agent Loop 轮数。
- VLM 分析成本。
- Checkpoint 恢复成功率。
- 工具调用预算超限次数。

### 视频质量层

- 精彩片段定位准确度。
- 视频时长约束满足率。
- 剪辑计划校验通过率。
- 成片成功率。
- 用户修改次数。
- 人工评价的内容连贯性和剪辑质量。

建议使用 `trace_id` 串联：

```text
Vue Request
    ↓
Java API
    ↓
RabbitMQ
    ↓
Python Agent
    ↓
Tool Gateway
    ↓
Java TimelineService
    ↓
Render Worker
```

可以使用 OpenTelemetry 为 HTTP、消息处理、模型调用和工具执行记录统一 Trace。OpenTelemetry 已提供跨语言系统中相关操作的语义约定。[OpenTelemetry](https://opentelemetry.io/docs/specs/semconv/general/trace/?utm_source=chatgpt.com)

注意避免将用户完整视频内容、私有对话和访问凭证无控制地写入日志。

---
