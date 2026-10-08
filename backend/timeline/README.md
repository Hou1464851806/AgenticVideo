# Timeline：Video Editing DSL 与计划版本

由 Java 权威存储并验证剪辑状态；管理 Edit Plan、不可变 Revision、事务性 Patch 与 Timeline Compiler 的规则。

## 当前状态与边界

**计划中：** 本目录已建立设计文档，尚未初始化工程或实现业务功能。以下内容来自 v0.1 架构预研，章节编号沿用原设计，便于追溯。容量、预算和性能参数均为设计建议，尚无实测结果。

## 必要依赖（设计选型）

Java + Spring Boot、PostgreSQL JSONB；Python Agent 通过 Internal Tool API 提交计划；Render Worker 按指定版本读取并编译 DSL。

## 运行与验证

尚无安装、环境准备、启动、格式检查、静态检查、构建或测试命令；构建配置、依赖版本、锁文件和 CI 待工程初始化时确定。初始化后应在所属工程 README 中补充确切命令与工作目录。

本阶段仅可核对文档内容、相对链接与 Markdown 结构，并在仓库根目录执行 `git diff --check`；这些检查不代表功能验证。

## 已知限制

本文保留设计目标和示例，不构成已实现的接口、数据库迁移、JSON Schema 或部署配置。模块功能、故障恢复与集成行为仍待实现和验证。

## 阅读导航

- [Milestone 1 开发范围、实施步骤与验收](../../docs/milestone-1-plan.md)。
- [系统总览与需求](../../README.md)。
- [后端工程](../README.md)。
- [剪辑计划 API](../api/README.md)。
- [渲染执行](../rendering/README.md)。
- [多轮状态协作](../../docs/conversation/README.md)。

## 4.8 Video Edit Plan

这是整个系统最重要的业务模型之一。

建议采用：

**关系表维护版本与状态，JSONB 保存 Video Editing DSL。**

### Edit Plan

```text
id
project_id
name
current_revision
status
created_at
updated_at
```

### Edit Plan Revision

```text
id
plan_id
revision
schema_version
plan_data JSONB
plan_hash
created_by
created_at
```

要求：

- Revision 一旦提交即不可变。
- 修改操作必须生成新 Revision。
- 必须检查预期版本。
- 每次渲染绑定一个明确的 Revision。
- 每次用户确认绑定一个明确的 Revision。

这样可以保证 Agent 修改视频时有完整的版本历史。

### Video Editing DSL 示例

```json
{
  "schema_version": "1.0",
  "plan_id": "plan_001",
  "revision": 3,

  "output": {
    "width": 1080,
    "height": 1920,
    "fps_num": 30,
    "fps_den": 1,
    "video_codec": "h264"
  },

  "tracks": [
    {
      "track_id": "video_track_1",
      "type": "video",
      "clips": [
        {
          "clip_id": "clip_001",
          "asset_id": "asset_001",
          "asset_version": 1,
          "source_in_ms": 120000,
          "source_out_ms": 135000,
          "timeline_start_ms": 0,
          "crop_mode": "center"
        },
        {
          "clip_id": "clip_002",
          "asset_id": "asset_002",
          "asset_version": 1,
          "source_in_ms": 30000,
          "source_out_ms": 50000,
          "timeline_start_ms": 15000,
          "crop_mode": "center"
        }
      ]
    }
  ],

  "constraints": {
    "target_duration_ms": 35000,
    "locked_clip_ids": []
  }
}
```

需要注意：

`source_in_ms` 和 `source_out_ms` 表示源视频中的时间范围，`timeline_start_ms` 表示在最终作品中的位置。

该示例是 MVP 级单视频轨道设计。

后续应扩展音频、字幕、转场、变速等操作。

为了支持准确剪辑，内部渲染编译器还应根据源视频的时间基和实际帧时间戳进行对齐。毫秒时间戳适合 API 交互，但不能保证任意视频都能在对应毫秒处精确无损裁切。

## 6.6 Video Editing DSL：剪辑执行协议

### 6.6.1 设计目标

Video Editing DSL 是整个系统最核心的中间表示。

它连接：

```text
用户自然语言
      ↓
Python Agent
      ↓
Video Editing DSL
      ↓
Java Timeline Compiler
      ↓
FFmpeg
      ↓
视频成片
```

### 6.6.2 设计原则

DSL 必须满足：

- 可序列化。
- 可验证。
- 可持久化。
- 可版本化。
- 可增量修改。
- 可回滚。
- 可确定性编译。
- 可以追溯源素材。

### 6.6.3 Patch-Based Editing

建议使用局部操作修改已有方案。

例如：

```json
{
  "plan_id": "plan_001",
  "base_revision": 3,

  "operations": [
    {
      "op": "trim",
      "clip_id": "clip_002",
      "trim_end_ms": 5000
    },
    {
      "op": "move_before",
      "clip_id": "clip_003",
      "target_clip_id": "clip_001"
    }
  ]
}
```

Java 按顺序执行并验证操作。

对于包含多个修改的请求，建议采用事务性处理：

**要么所有 Patch 成功并产生一个新版本，要么全部失败，不留下部分提交的状态。**

### 6.6.4 Plan Validator

分为四类校验：

| 类别 | 校验内容 |
|---|---|
| Schema Validation | 字段、类型、枚举是否合法 |
| Reference Validation | Asset、Clip、Track 是否存在 |
| Timeline Validation | 时长、排序、重叠、空白是否合法 |
| Business Validation | 用户权限、锁定约束、方案状态 |

示例：

```text
source_in >= 0

source_out > source_in

source_out <= asset.duration

asset.project_id == plan.project_id

base_revision == current_revision
```

还需明确时间线规则：

- 视频轨道是否允许留白。
- 多轨道是否允许重叠。
- 转场是否占用重叠时间。
- 变速后的实际时长如何计算。
- 音频轨道如何对齐。
- 字幕是否超出成片时间范围。

这些规则必须由 Java 的 DSL Validator 与 Timeline Compiler 共同遵守。

---
