# M1 验证脚本

从仓库根目录执行；依赖 JDK 21、Node 24、pnpm 11.24.0、Docker Compose、Python 3 和 Playwright Chromium。安装/格式/静态/构建入口见 [Backend](../backend/README.md) 与 [Frontend](../frontend/README.md)，结果见 [验收记录](../docs/milestone-1-acceptance.md)。脚本使用小型合成素材，不需要模型账号。

| 命令 | 验证内容 |
|---|---|
| `scripts/test-m1.sh` | 隔离 Compose 构建、Flyway、真实 PG/Silo 业务及权限、清理/输出提交回归、上传与工作台浏览器流程；默认配置 `.runtime/m1-ci.env` |
| `ENV_FILE=.runtime/test.env scripts/test-m1.sh` | 使用已有隔离测试凭证，默认项目 `agenticvideo-m1-test` |
| `scripts/test-media.sh` | 固定 FFmpeg 容器中的颜色/音调、旋转、VFR、PTS、静音/短音轨、比例、亚帧/损坏媒体矩阵 |
| `ENV_FILE=.runtime/test.env scripts/test-silo.sh` | 测试管理员身份的 SDK 与浏览器分片/CORS/Range/签名过期实验；固定 `agenticvideo-m1-test`，19000 端口 |
| `ENV_FILE=.runtime/test.env scripts/test-api-restart.sh` | 占用旧测试 API 地址，重建到新地址，验证不重启前端即可恢复代理；不删除数据卷 |
| `scripts/test-faults.sh` | 专用 `agenticvideo-m1-empty`、`.runtime/empty.env`，真实 Worker kill/pause、双 Worker、PG/Silo 中断、磁盘门槛、缩小时长门槛和两秒超时；结束恢复默认 Worker |
| `ENV_FILE=.runtime/test.env COMPOSE_PROJECT_NAME=agenticvideo-m1-test scripts/test-recovery.sh` | 暂停测试 API/Worker，PG dump 恢复到新临时数据库，比较数量与哈希聚合，恢复服务 |
| `ENV_FILE=.runtime/test.env COMPOSE_PROJECT_NAME=agenticvideo-m1-test scripts/test-silo-recovery.sh` | 测试数据不超过 128 MiB 时，将 Silo 快照恢复到新临时卷，逐对象读取比较 SHA-256，清理临时恢复资源并恢复服务 |
| `python3 scripts/verify-docs.py` | 仓库文档相对链接、代码围栏与 JSON 示例 |

`test-m1.sh`/`test-faults.sh` 在配置缺失时生成随机测试凭证，保存在忽略目录，mode 0600。不要传入开发/生产配置。测试卷保留；停止服务使用对应 `docker compose ... down`，不删除卷。故障脚本会中断专用测试服务，避免与其他验收并行运行。

可用 `python3 scripts/observe-resources.py --container <API容器> --container <Worker容器> --worker <Worker容器> --output .runtime/resources.json -- <验证命令>` 采样内存与 `/work` 占用；约 2–5 秒采样间隔，结果为采样最大值，不能当作瞬时峰值或容量承诺。

按用户要求不生成、不实测真实 4 GB / 120 分钟素材。声明字节数、并发数量和缩小后真实探测时长配额验证不证明大规模处理能力。
