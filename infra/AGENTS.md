# 基础设施规则

阅读本目录 README 和[根规则](../AGENTS.md)。Compose 命令从仓库根目录执行：`docker compose --env-file infra/.env -f infra/compose.yaml config --quiet` 检查配置，`up -d` 启动开发服务。

测试使用独立项目名、卷、凭证和对象前缀。只有确认属于本次测试的资源才可清理。固定镜像摘要及真实 Silo SDK/浏览器验证是 S0 放行条件，配置解析不能替代运行验证。
