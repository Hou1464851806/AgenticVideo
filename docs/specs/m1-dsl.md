# M1 DSL 1.0

状态：S1 正在实现与媒体验证；不代表业务 API 已运行。权威输入契约为 [JSON Schema](../../contracts/m1-dsl.schema.json)。

输入只包含 `schema_version`、`output`、`tracks`、可选 `constraints`；`plan_id`、`revision` 由服务端放在 Revision 响应中，客户端不赋值。`output` 固定 FINAL 尺寸 1920×1080 或 1080×1920、30/1 fps、h264。PREVIEW 按相同比例派生 1280×720 或 720×1280。

仅接受一个 video 轨道，clip 数组按时间线顺序排列，从 0 连续无空隙。clip ID 唯一，引用 UUID asset ID 和 version=1。时间为整数毫秒、半开区间；业务校验源区间不能超过已探测素材 duration_ms，总时长不超过配置上限（默认 600000ms）。如果指定 target_duration_ms，必须等于累计时长；locked_clip_ids 只能为空数组。未知字段、路径、URL、滤镜、其他轨道均拒绝。

Schema 负责结构、枚举和基本数字约束；Java 进一步检查时间连续性、ID 唯一、素材项目/版本/就绪、源时长和权限。失败返回稳定错误码及字段路径，合法结构不能绕过业务校验。

Compiler 是纯 Java，不访问 DB、网络或进程。其输出包含受控片段参数、profile、目标尺寸和预期时长，执行器负责下载素材、真实 ffprobe/FFmpeg 和输出校验。归一化源 PTS 后按播放时间裁切，先旋转再 scale/crop/pad；视频 CFR 30、SAR=1、yuv420p，音频 48k 双声道，静音及短音轨补齐。实验采用中间片段后再统一 concat/编码，实际媒体矩阵和阈值以验收记录为准。

运行验证：仓库根目录执行 `scripts/test-media.sh`，后端 `./mvnw verify` 与前端 `pnpm test`（各自工程目录）消费同一 Schema/夹具。OpenAPI 包括 M1 REST 端点，新增项目任务列表用于刷新恢复；业务接口尚未实现。

固定 30 fps 使用累计时间边界四舍五入分配帧，单个 sub-frame 片段可能不占输出采样帧；整部非空作品至少输出一帧。源视频归一化后重采样到 30 fps，再在相邻帧边界取样，源边界误差小于一输出帧。音轨按原始毫秒裁切，输出补齐到分配帧时长；整部只进行一次 AAC 编码。该规则保留任意整数毫秒输入，避免逐片段取整累计偏差。
