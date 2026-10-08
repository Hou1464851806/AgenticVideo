# M1 本地运行与故障处理

M1 为手动确定性剪辑闭环，不包含 Python Agent、自然语言规划、字幕、多轨道、RabbitMQ 或 Redis。启动配置见 [infra](../infra/README.md)，可重复验收命令见 [scripts](../scripts/README.md)。仅支持本机 HTTP 开发；远端发布及生产部署未验证。

## 资源配置

| 配置 | 默认值 / 作用 |
|---|---|
| `WORKER_CONCURRENCY` | 1，允许 1–4；校验/渲染共享有界执行槽，不积累内存任务队列 |
| `WORKER_DISK_BYTES` | 32000000000；同一 Worker 进程的全部执行共享预留预算，同时检查实际可用磁盘；多进程不是集群预算 |
| `RENDER_TIMEOUT_SECONDS` | 1800，渲染包括下载/编码/上传的期限 |
| `ASSET_DURATION_MS` / `PROJECT_DURATION_MS` | 7200000 / 10800000；Worker 真实探测及事务累计时长门槛 |
| `UPLOAD_SIGNATURE_TTL_SECONDS` | 300，分片授权期限；播放/下载签名固定 300 秒 |
| `CLEANUP_TTL_SECONDS` | 3600，至少 60；任务专属临时目录无有效租约且过期后回收 |
| `MEDIA_STDERR_BYTES` | 8192，允许 1024–65536；stdout 最多 1 MiB，进度单行最多 4096 字节 |

Spring 的 `app.limits.project-pending-jobs` / `app.limits.user-pending-jobs` 默认 5 / 10，可通过 `APP_LIMITS_PROJECT_PENDING_JOBS` / `APP_LIMITS_USER_PENDING_JOBS` 设置（自行注入 API 进程）。默认执行槽 1 已验证，更多槽是可配置能力，不代表 CPU/内存已完成容量测试。

## 故障与恢复

- `STORAGE_UNAVAILABLE` / 503：检查 Silo 健康与网络；恢复后以相同幂等键及相同请求重试上传初始化/完成，不生成新键规避已有意图。
- `DATABASE_UNAVAILABLE` / 503：恢复 PG 后 Cookie 会话及业务记录可继续读取；不要删库重建。nginx 每 5 秒重新解析 Docker API 服务名，API 重建后地址自动恢复。
- `WORKER_LOST`：租约过期持久失败。旧 attempt 不能成功，QUEUED 可由替代 Worker 领取；对 FAILED 由用户发起新 retry 任务，M1 不自动续跑。
- `RENDER_TIMEOUT` / `DISK_INSUFFICIENT`：没有可交付产物，调整独立 Worker 资源后发起新 retry；成功任务不可重试。
- `CANCEL_REQUESTED`：Worker 停止下载/编码/上传，终态条件更新裁决与成功提交的竞争；成功先提交时取消返回 409，取消先接受时不能成功。
- `ASSET_DURATION_EXCEEDED` / `PROJECT_DURATION_EXCEEDED`：探测失败素材进入 INVALID，不能被方案引用；项目累计检查在项目事务锁内执行。
- 输出已上传而数据库写入失败：固定 job/attempt key 的 HEAD 校验和至多三次提交重试；提交回执丢失时核对同 token/key/hash 的 SUCCEEDED，不创建第二个产物。
- 对象清理任务有代数条件，延迟的旧执行不能消耗新的清理请求。清理失败最多五次，检查 `object_cleanup_tasks` 的 state/error_code；仅重新排队明确归属的 key，不扫描删除无归属对象。

Worker 日志仅输出受控 event/code 和 job/attempt 标识，不输出素材、凭证或签名地址。请求错误含 trace_id；本地日志、短期签名夹具、dump 和恢复快照位于忽略目录，不提交或上传。

## 迁移与备份

Flyway V1–V4 与 Session 迁移建立业务表；空卷初始化、已有 schema 升级及重复启动校验均使用相同迁移。不可变 Revision 禁止 UPDATE/DELETE。变更前对 PG 和 Silo 分别备份；两者没有原子事务，M1 的恢复脚本通过暂停 API/Worker 获得一致测试快照。

低成本恢复已在隔离测试环境验证；不是生产备份方案。代码 `git revert` 不恢复业务数据库或素材；数据库变更采用新增迁移，不改写已应用迁移。真实 4 GB / 120 分钟处理、容量 SLO、收费模型和远端 CI 未验证。
