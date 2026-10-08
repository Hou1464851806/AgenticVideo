# M1 开发基础设施

已提供前端、API、独立 Worker、PostgreSQL、Silo 与一次性 bucket/应用权限初始化。固定镜像在 arm64 构建并启动通过，容器环境浏览器闭环已验证；可靠性结果见验收记录；真实 4 GB / 120 分钟测试按用户要求不执行。

依赖 Docker Compose。仓库根目录执行：

```bash
cp infra/.env.example infra/.env
# 编辑所有占位值，使用本机开发凭证；infra/.env 已忽略。
docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
docker compose --env-file infra/.env -f infra/compose.yaml up -d --build --wait
docker compose --env-file infra/.env -f infra/compose.yaml logs --tail 30 api worker
docker compose --env-file infra/.env -f infra/compose.yaml exec worker /bin/sh -c 'ffmpeg -version | head -1'
```

前端打开 `http://localhost:5173`，API 绑定 `127.0.0.1:8080`。PostgreSQL 绑定 `127.0.0.1:54328`，Silo S3 绑定 `127.0.0.1:19000`，控制台绑定 `127.0.0.1:19001`。`silo-init` 在 Silo 健康后创建私有 bucket 和应用身份，成功后 API/Worker 才启动；可以重复执行，管理员凭证仅注入 Silo/初始化容器。应用身份获得单 bucket 的 S3 操作权限。浏览器 CORS 来源当前为 `http://localhost:5173`；部署前按实际来源调整并重新验证。当前只允许本机开发使用 HTTP。

测试使用独立项目名 `agenticvideo-m1-test` 和忽略的 `.runtime/test.env`，避免触碰开发卷。签名夹具包含临时授权，保存在忽略目录且不输出 URL。SDK 实验允许创建独立测试 bucket，需要测试管理员权限；业务应用身份不需要创建 bucket 权限。

Silo 固定 RELEASE.2026-09-16T00-00-00Z 和多架构 index digest；已实际拉取 arm64 并检查版本。基础镜像 digest 见 image-lock.json，媒体版本强制检查为 FFmpeg 6.1.1-3ubuntu5。空测试卷启动及容器浏览器闭环已通过，详细证据见 [验收记录](../docs/milestone-1-acceptance.md)。

端口可通过 `DATABASE_PORT`、`SILO_PORT`、`SILO_CONSOLE_PORT`、`API_PORT`、`FRONTEND_PORT` 覆盖；修改浏览器端口时同时设置 `BROWSER_ORIGIN`，并重新验证 CORS。Worker 限制 2 CPU、2 GiB、256 PID，磁盘预算默认 32 GB、渲染期限 1800 秒，可用 `WORKER_DISK_BYTES` / `RENDER_TIMEOUT_SECONDS` 覆盖。临时数据独立保存在 worker-scratch 卷。

停止服务使用 `docker compose --env-file infra/.env -f infra/compose.yaml down`，保留卷和素材；不要为重启删除卷。开发配置只支持本机 HTTP；外部部署不属于本次授权范围。

## 可重复集成验收

仓库根目录执行 `scripts/test-m1.sh`；需要 JDK 21、Node 24、pnpm 11.24.0、Docker Compose 和已安装 Playwright Chromium。脚本只接受 `agenticvideo-m1-*` 项目，默认生成忽略目录内的随机测试凭证，构建/初始化服务并运行真实集成与浏览器检查，保留测试卷便于调查。可用 `ENV_FILE` 指定已有测试配置；不要指定开发/生产配置。小型媒体内容矩阵使用 `scripts/test-media.sh`。真实 4 GB / 120 分钟素材测试按用户要求不执行。

恢复操作的低成本测试入口为 `ENV_FILE=.runtime/test.env COMPOSE_PROJECT_NAME=agenticvideo-m1-test scripts/test-recovery.sh`：暂停该测试项目 API/Worker，使用 pg_dump/pg_restore 在独立临时数据库验证记录一致，然后恢复服务。只操作隔离测试项目；本地 dump 包含测试密码哈希/会话，mode 0600，禁止提交或上传。该入口验证 PostgreSQL；Silo 恢复使用下面的独立入口。

Silo 的低成本恢复检查：`ENV_FILE=.runtime/test.env COMPOSE_PROJECT_NAME=agenticvideo-m1-test scripts/test-silo-recovery.sh`。只接受隔离项目，测试数据上限 128 MiB；暂停服务，将数据恢复到临时新卷并逐对象比较 SHA-256，然后删除临时恢复资源并恢复原服务。备份仅保存本地忽略目录、mode 0600，不上传或提交。

`CLEANUP_TTL_SECONDS` 默认 3600、最小 60；只回收数据库确认属于任务、已过期且没有有效执行租约的临时目录。`MEDIA_STDERR_BYTES` 默认 8192，范围 1024–65536；stdout 保留上限 1 MiB、进度单行上限 4096 字节。`UPLOAD_SIGNATURE_TTL_SECONDS` 默认 300；媒体播放/下载签名固定 300 秒。

Worker 并发可通过 `WORKER_CONCURRENCY` 设置为 1–4（默认 1）；同进程校验与渲染共享磁盘预留。时长配置使用 `ASSET_DURATION_MS` / `PROJECT_DURATION_MS`。故障脚本、恢复与完整命令见 [scripts](../scripts/README.md)，故障码和配置边界见 [运行指南](../docs/m1-operations.md)。
