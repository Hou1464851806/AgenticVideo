# 跨模块设计文档

本目录维护跨工程共享设计。M1 手动剪辑闭环已实现；实际验证与限制见 [验收记录](milestone-1-acceptance.md)，预研协议仍需按阶段实现；[根 README](../README.md) 是需求、系统架构、风险与里程碑的入口。

| 主题 | 内容 |
|---|---|
| [M1 本地运行与故障](m1-operations.md) | 配置、稳定故障码、租约/清理、迁移与恢复边界 |
| [验证脚本](../scripts/README.md) | 真实集成、浏览器、媒体、故障与恢复命令 |
| [Milestone 2 开发计划](milestone-2-plan.md) | 中文口播/基本视觉、预算、任务、索引、UI 与量化验收；尚未实现 |
| [M2 模型选型依据](m2-model-research.md) | 官方接口、地域价格与资格门槛；尚未付费实测 |
| [Milestone 1 开发计划](milestone-1-plan.md) | 确定性编辑闭环的范围、Silo 选型、DSL、实施顺序与验收 |
| [容量与非功能需求](capacity/README.md) | 业务规模假设、成本、存储、SLO 与资源规划 |
| [数据模型](data-model/README.md) | 数据选型、所有权、实体关系与字段设计导航 |
| [多轮对话与状态](conversation/README.md) | Java 业务状态、Python Checkpoint、上下文与取消 |
| [异步消息与可靠性](messaging/README.md) | Envelope、RabbitMQ 拓扑、Outbox / Inbox、恢复与幂等 |
| [可观测性与评测](observability/README.md) | 系统、Agent、视频质量指标与统一 Trace |

## 模块设计入口

- [前端交互](../frontend/README.md)。
- [Java 后端与业务模型](../backend/README.md)、[API](../backend/api/README.md)、[剪辑 DSL](../backend/timeline/README.md)、[渲染](../backend/rendering/README.md)。
- [Python 工程](../agent-service/README.md)、[Agent 决策](../agent-service/agent/README.md)、[视频理解](../agent-service/analysis/README.md)、[工具治理](../agent-service/tools/README.md)、[Worker](../agent-service/workers/README.md)。

## 文档维护与验证

详细设计在对应文档中维护，根 README 和模块 README 提供导航。拆分内容沿用原章节编号，新增规范应明确标注设计状态与验证结果。

文档无需安装运行依赖。修改后检查相对链接及目标文件、Markdown 表格与代码围栏、JSON 示例；在仓库根目录执行 `git diff --check`。`python3 scripts/verify-docs.py` 检查链接、围栏和 JSON 示例，已接入集成 CI；文档检查不能替代业务验证。
