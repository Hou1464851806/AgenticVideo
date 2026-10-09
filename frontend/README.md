# Frontend：Vue 前端

负责用户交互、视频上传、多轮对话、时间线展示、任务反馈与成片预览。

## 当前状态与边界

已初始化 Vue 3 / TypeScript / Vite 工程；已整合注册登录、项目、直传素材/代理播放、毫秒选段/排序/裁切、比例与只读时间线、严格 DSL 导入、版本/冲突、预览、审批导出、任务轮询和下载。完整浏览器闭环已在真实服务通过，Compose 空卷启动与恢复结果见验收记录。以下 v0.1 章节为后续设计目标。

## 必要依赖与运行

Node 24.21.0、pnpm 11.24.0；依赖精确版本与 pnpm 锁文件已生成。工作目录 `frontend/`：

```bash
pnpm install --frozen-lockfile
pnpm dev
pnpm format:check
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm exec playwright install chromium
SILO_BROWSER_FIXTURE=/absolute/ignored/path.json pnpm test:e2e
M1_E2E=true pnpm exec playwright test uploads.spec.ts workspace.spec.ts
M1_E2E=true E2E_BASE_URL=http://localhost:5174 pnpm exec playwright test workspace.spec.ts
```

开发服务为 `http://localhost:5173`，`/api` 默认代理到 `http://localhost:8080`，可通过 `API_URL` 设置内部 API 地址。浏览器直传的预签名地址必须使用浏览器可访问 Host，不能改写签名 URL。

## 已知限制

真实 Silo 浏览器测试需要后端生成短期签名夹具；未提供时测试跳过。单元检查覆盖共享 DSL/OpenAPI；实际 API 响应也按契约验证。真实浏览器已通过上传、MOV 代理、双标签页 409、版本/任务刷新恢复、播放和下载；隐藏页面停止轮询，故障按 2–30 秒退避，状态见 [M1 记录](../docs/milestone-1-acceptance.md)。

## 阅读导航

- [M2 视频理解与检索计划（尚未实现）](../docs/milestone-2-plan.md)。

- [Milestone 1 开发范围、实施步骤与验收](../docs/milestone-1-plan.md)。
- [系统总览与需求](../README.md)。
- [业务 API 与上传流程](../backend/api/README.md)。
- [剪辑 DSL 与版本规则](../backend/timeline/README.md)。
- [多轮对话与任务状态](../docs/conversation/README.md)。

## 6.9 Vue：前端交互与实时反馈

### 6.9.1 建议页面

第一版可以包含：

| 页面              | 功能           |
| ----------------- | -------------- |
| Project List      | 项目列表       |
| Project Workspace | 项目主工作台   |
| Asset Library     | 素材浏览、上传 |
| Agent Chat        | 自然语言交互   |
| Timeline Preview  | 剪辑时间线     |
| Render Preview    | 成片预览       |
| Task Panel        | 分析与渲染状态 |

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
