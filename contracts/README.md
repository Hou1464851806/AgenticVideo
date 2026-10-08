# M1 共享契约

维护 DSL JSON Schema、OpenAPI 和合法/非法夹具，Java 与前端消费同一来源。M1 Schema 为严格子集，完整说明见 [规范](../docs/specs/m1-dsl.md)。Schema、正反例和响应校验已在 Java/前端共享使用，实际结果见 [验收记录](../docs/milestone-1-acceptance.md)。

运行验证：仓库根目录执行 `scripts/test-media.sh`，后端 `./mvnw verify` 与前端 `pnpm test`（各自工程目录）消费同一 Schema/夹具。OpenAPI 包括 M1 REST 端点，新增项目任务列表用于刷新恢复；业务接口已实现，浏览器运行时响应也按 Schema 校验。
