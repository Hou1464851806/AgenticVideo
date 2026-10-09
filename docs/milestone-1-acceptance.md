# Milestone 1 验收记录

M1 手动确定性剪辑闭环已实现，S0–S7 及 AC01–AC12 在下述本地范围内通过。支持注册登录、私有项目、浏览器直传、媒体校验/MOV 代理、片段编排、严格 DSL、不可变版本与确认、预览/正式导出、取消/失败重试、播放下载和持久状态恢复。Python Agent、自然语言规划、字幕、多轨道及 RabbitMQ 留到后续阶段。

按用户要求不生成、不实测真实 **4 GB / 120 分钟**素材。声明大小、并发配额及降低时长门槛的小夹具测试只证明规则执行，不能推断大文件、长视频、生产容量或性能 SLO。远端 CI 已配置但未运行；无 push、远端合并或部署。

## 环境与实际执行

2026-10-09，macOS arm64 / Docker Desktop linux/arm64（8 CPU、7.65 GiB），JDK Temurin 21.0.12.1+1、Maven Wrapper 3.9.11、Node 24.21.0、pnpm 11.24.0、Chromium。隔离项目 `agenticvideo-m1-test` 和 `agenticvideo-m1-empty`；凭证、媒体、截图、签名夹具和日志仅保存在忽略目录。

PostgreSQL 17.6、PGSTY Silo RELEASE.2026-09-16T00-00-00Z 固定 digest；Worker 断言 FFmpeg 包 `7:6.1.1-3ubuntu5`，实际输出记录 6.1.1 版本。镜像锁见 [infra](../infra/image-lock.json)。未以本机 FFmpeg 8.1 的结果代替固定 Worker 媒体验收。

| 实际命令（仓库根目录） | 结果 |
|---|---|
| `ENV_FILE=.runtime/test.env scripts/test-m1.sh` | 全服务构建/初始化、Java verify、真实业务/权限/清理/提交集成、两项浏览器、文档检查通过；Java 31 项中 21 项运行，10 项专用测试在下列命令单独运行 |
| `pnpm --dir frontend format:check`、`lint`、`typecheck`、`test`、`build` | 全部通过，Vitest 2 项；冻结锁安装此前执行通过 |
| `scripts/test-media.sh` | 固定媒体运行时矩阵全部通过，ffprobe 与实际解码/内容断言 |
| `ENV_FILE=.runtime/test.env scripts/test-silo.sh` | 两项 SDK + 一项浏览器存储契约通过；该浏览器命令的两项业务测试未启用，已由 test-m1 单独执行 |
| `scripts/test-faults.sh` | 7 项进程/服务/磁盘/时长故障 + 1 项两秒超时通过，专用测试无跳过；默认 Worker 恢复 |
| `ENV_FILE=.runtime/test.env scripts/test-api-restart.sh` | 占用旧 API IP，API 换地址后原前端自动恢复，前端未重启 |
| `ENV_FILE=.runtime/test.env COMPOSE_PROJECT_NAME=agenticvideo-m1-test scripts/test-recovery.sh` | PG 快照恢复到新临时数据库，项目/素材/版本数量、方案和输出哈希聚合一致；临时库清理、服务恢复 |
| `ENV_FILE=.runtime/test.env COMPOSE_PROJECT_NAME=agenticvideo-m1-test scripts/test-silo-recovery.sh` | Silo 恢复到新临时卷，应用身份读取 79 个对象，key/size/ETag/内容 SHA-256 全部一致；临时资源清理、服务恢复 |
| `M1_COMMIT_INTEGRATION=true ... -Dtest=OutputCommitIntegrationTest verify`（注入隔离测试 PG/Silo 配置） | 最新真实提交回归通过，含实际 SQL 写入后回执丢失、写入前失败、取消/失租拒绝，以及 8 轮两连接取消/成功竞争 |
| `bash -n scripts/*.sh`、`python3 -m py_compile scripts/*.py`、Compose `config --quiet`、`python3 scripts/verify-docs.py`、`git diff --check` | 语法、配置、文档链接/围栏/JSON 和差异格式通过 |

可重复命令、环境准备与测试隔离约束维护在 [scripts](../scripts/README.md)，运行配置及故障码见 [操作指南](m1-operations.md)。CI 运行相同小夹具/真实集成/故障/恢复脚本；本地执行通过不代表远端 CI 已通过。

## CI 删除状态码回归补充（2026-10-09）

首次 GitHub integration 日志显示，保存方案与删除素材竞争时返回合法的 `422/202`，但 `RenderIntegrationTest` 错误期待 `422/200`。删除接口与 OpenAPI 均约定异步删除返回 202；本地针对性执行已复现同一失败。现已修正断言，保留原并发检查，并增加顺序执行回归，固定覆盖保存先完成的 `201/409` 和删除先完成的 `422/202`，检查素材状态、方案数量及素材引用。

修复后本地执行（JDK 21、Node 24，隔离 `.runtime/test.env`）：

- `M1_INTEGRATION=true ... backend/mvnw -B -ntp -f backend/pom.xml test -Dtest=RenderIntegrationTest`：4 项运行，全部通过，无跳过；省略部分为隔离测试数据库与存储环境变量。
- `ENV_FILE=.runtime/test.env scripts/test-m1.sh`：Java verify 的 32 项中 22 项运行、10 项专用测试按条件跳过，无失败；格式、Checkstyle、编译与打包通过，2 项上传/工作台浏览器测试通过，文档检查与 `git diff --check` 通过。

此次改动仅涉及测试和本记录，不改变删除接口或业务行为；未重新执行后续媒体、故障和恢复脚本，也未推送或重跑 GitHub CI。本地通过不能代替修复提交的远端 CI 结果。

## AC01–AC12 对照

| 条件 | 证据与实际行为 |
|---|---|
| AC01 | 新测试卷完整 Compose 初始化通过，PG/Silo 健康后初始化私有 bucket/应用身份，API/Worker/前端启动；持久卷恢复后真实工作台闭环通过；无模型账号 |
| AC02 | 真实 HTTP 和浏览器双用户测试覆盖项目、上传、素材/内容地址、方案、任务/产物；无权限返回 404，CSRF/登出/会话恢复回归通过 |
| AC03 | 浏览器上传 MP4/MOV，9 MiB+ 双片 MP4 单片重传与重复 complete；真实大小、SHA-256/媒体元数据、封存 key、代理、Range、取消与删除验证；封存后旧 part 无法覆写 |
| AC04 | API/工作台 DSL 到 PREVIEW/FINAL；色块/音调断言选段和顺序，旋转、crop/fit、横竖比例、不同帧率与输入时间戳通过 |
| AC05 | Java/前端共享严格 Schema 正反例；非法时长返回 EDIT_PLAN_INVALID/字段路径，不生成方案或任务；未知字段、类型、项目/版本/就绪引用与不支持操作拒绝 |
| AC06 | 两个并发 If-Match 更新一成功一 409，旧 Revision 与 hash 不变，数据库不可变触发器；浏览器冲突保留草稿 |
| AC07 | 未审批 FINAL 拒绝；审批 R1 后保存 R2，旧审批不能授权 R2，R1 正式导出仍绑定 R1 的 hash/素材版本/profile |
| AC08 | init/complete、保存方案、创建/重试任务同键重放返回同一持久 ID；不同请求同键冲突；对象上传后 DB 提交可重试且回执丢失不会生成第二个产物 |
| AC09 | 单调进度、稳定失败码；真实 RUNNING 取消和两秒超时，终态产物拒绝访问；取消与成功真实两连接竞争只有一个结果；刷新恢复、隐藏暂停/退避通过 |
| AC10 | 实际 kill/pause Worker、替代 Worker 领取 QUEUED；租约到期 WORKER_LOST，恢复旧 Worker 不得成功；两个正常 Worker 同时运行，4 个任务各只有一次 render_claimed，两者均领取任务 |
| AC11 | 成片 ffprobe/解码检查 H.264/AAC、30fps、尺寸/时长；浏览器播放时间实际推进、Range 跳转、下载；音视频同步误差约 0.67ms，满足 54.7ms 阈值 |
| AC12 | 后端格式/Checkstyle/编译/打包、前端完整检查、真实 PG/Silo、浏览器、媒体、故障及恢复通过；共享契约包含实际响应字段；文档入口与限制同步，CI 已配置 |

## 边界与可靠性补充

- 31 个并发上传预留只有 30 个成功，取消释放数量；4 GB 声明边界及超限拒绝，不上传 4 GB。缺片、空完成、实际大小不符、过期签名、损坏媒体、私有对象和错误签名都有失败断言。
- 3 秒素材在单素材门槛 1 秒时 INVALID/ASSET_DURATION_EXCEEDED；门槛 4 秒时首个就绪，累计超过项目 4 秒时第二个 INVALID/PROJECT_DURATION_EXCEEDED；配置最终恢复默认。
- 素材删除与新 Revision 保存并发，只允许保存成功/删除拒绝或删除成功/保存拒绝；成功版本引用不会被删除。清理代数回归使用真实 PG 和受控删除屏障，旧清理回执不能吞掉新请求。
- Worker 执行槽默认 1、可配置 1–4；两槽测试验证同时执行及不积累队列，预算预留/释放幂等。双进程真实领取验证不替代更高并发容量测试。
- 一字节磁盘预算在编码前 DISK_INSUFFICIENT，恢复预算后新 retry 成功；Silo 中断恢复同一上传意图，PG 中断返回 503 后原会话和项目仍可读。
- JSON 请求体固定/分块超过 1 MiB 返回 413；字符串 revision 不做标量强制转换。进程 2 MiB 无换行输出只保留 stdout 1 MiB/stderr 8 KiB，进度行最多 4096 字节；取消与有界存储传输检查通过。
- 本轮回归发现 nginx 静态解析导致 API 重建后 502，已改 Docker DNS 每 5 秒解析并用实际 IP 变化回归。上传浏览器测试先建立会话再加载 SPA，避免测试控制器与页面同时创建初始 Session；重跑全部断言通过。

## 小型基准与限制

完整 test-m1 本地采样耗时 61.78 秒；16 次约 2–5 秒采样中，API 内存最大约 218.4 MiB、Worker 203.0 MiB，`/work` 最大 67777 字节。快速任务可能在采样之间完成，这些是采样最大值，**不是瞬时峰值或足够的磁盘预算**。固定媒体矩阵保留约 5 MiB 小夹具，不声称生产 P95/P99、4 GB/120 分钟或高并发能力。

M1 支持 MP4/MOV、H.264、可选 AAC；旋转、VFR、非零 PTS、无音频和短音轨已验证。多流、多音轨、HDR、其他 codec 明确拒绝。输出固定 30fps、yuv420p、H.264/AAC、MP4 faststart、16:9/9:16；PREVIEW 720p、FINAL 1080p。毫秒编辑按输出帧边界量化，整计划至少一个输出帧。

上传支持当前会话分片重试，不承诺刷新后自动重选本地文件续传。FAILED 重试创建新任务；Worker 丢失后不自动续编码。清理仅回收已归属对象和任务临时目录，不自动删除已引用素材或成功产物。恢复仅为隔离本地测试，生产 HTTPS、异地备份与部署仍需独立工程与验证。
