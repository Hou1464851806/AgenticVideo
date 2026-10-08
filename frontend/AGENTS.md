# Frontend 开发入口

遵循[根规则](../AGENTS.md)。修改交互前阅读本目录 README 及 [API 设计](../backend/api/README.md)。

工作目录 `frontend/`，Node 24、pnpm 11.24.0：

- `pnpm install --frozen-lockfile`：安装锁定依赖。
- `pnpm dev`：开发服务，默认 `/api` 代理到 localhost:8080。
- `pnpm format:check`、`pnpm lint`、`pnpm typecheck`、`pnpm test`、`pnpm build`：交付检查。
- `pnpm exec playwright install chromium`：准备浏览器。
- `SILO_BROWSER_FIXTURE=/absolute/ignored/path.json pnpm test:e2e`：真实浏览器存储契约；未提供夹具会跳过，不能当作通过。

- `M1_E2E=true pnpm exec playwright test uploads.spec.ts workspace.spec.ts`：真实 API/PG/Silo/Worker 业务闭环。
- `E2E_BASE_URL=http://localhost:5174`：复用已启动的 Compose 前端，不启动 Vite。

当前工作台已实现 M1 手动剪辑闭环；后续 Agent 对话等仍为设计目标。业务变更同时复核真实响应契约与浏览器流程。
