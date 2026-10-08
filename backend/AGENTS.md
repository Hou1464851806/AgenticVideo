# Backend 开发入口

遵循[根规则](../AGENTS.md)。修改业务模块前阅读对应 api、timeline、rendering README 和 [M1 计划](../docs/milestone-1-plan.md)。

工作目录 `backend/`，需要 JDK 21：

- `./mvnw -B -ntp verify`：格式、静态检查、编译、单测及打包。
- `./mvnw -B -ntp spotless:apply`：仅在本任务 Java 文件需要格式化时使用，审查全部差异。
- `PG_INTEGRATION=true ./mvnw -B -ntp verify`：真实 PG 身份/项目集成；需要隔离测试库和 DATABASE_PASSWORD。
- `./mvnw spring-boot:run`：启动 API，需要 PostgreSQL/Silo 环境变量。
- `SILO_CONTRACT=true ./mvnw -B -ntp test -Dtest=StorageContractTest`：真实 Silo SDK 契约；测试凭证仅用于隔离测试服务。
- `SILO_BROWSER_FIXTURE=/absolute/ignored/path.json ./mvnw -B -ntp test -Dtest=BrowserStorageFixtureTest`：生成短期签名夹具，交给前端浏览器测试；生成文件必须在忽略目录。

集成测试未启用时 JUnit 会跳过，不能报告集成通过。上传、方案、Worker 改动运行相应真实集成；全链路与故障入口见 [验证脚本](../scripts/README.md)，执行结果见验收文档。
