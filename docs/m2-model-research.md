# M2 候选模型与向量检索核查

核查日期：2026-10-09。状态：**官方资料核查完成；候选未最终选定，未调用收费模型，未上传素材，未做效果、账单或账号可用性实测**。本文件为 M2 计划提供选型依据，不表示相应功能已实现。

用户已确定：中文口播为主，同时验收基本视觉检索；优先云 API；仅使用允许外传的测试素材；分析由用户看到估算后点击启动；单素材模型费用上限初始为 5 元、首轮真实模型验收累计上限初始为 50 元，均允许配置调整。预算是停止门槛，不是服务价格或分析完成的承诺。

## 1. 首轮适配候选

| 能力 | 候选 | 官方资料支持 | M2 接入建议与剩余核验 |
|---|---|---|---|
| 中文 ASR | `paraformer-realtime-v2` | Python SDK 支持 `send_audio_frame(bytes)` 发送本地二进制音频；句与字均有 `begin_time` / `end_time`，单位 ms | 先做 16 kHz 单声道 PCM 适配实验；仅持久化终句结果，将块内时间映射回源视频时间轴。需测分块边界、静音、发送节奏和断连；不承诺比实时更快 |
| 视觉描述 | `qwen3-vl-flash-2026-01-22` | 官方列出该快照，输入包含图像；通用视觉 API 支持多图与 Base64 Data URI | 固定快照优于使用可能更新的别名。传少量带真实帧 ID 的缩放 JPEG；本地严格校验输出，不依赖模型自行给出时间 |
| 中文文本向量 | `text-embedding-v4` | 支持中文，默认 1024 维，可显式选择维度；最多 10 条/批，单条最多 8192 tokens | 首轮固定 1024 维，并分别保存 ASR 文本和视觉描述向量；查询与索引必须使用同一模型、维度和配置 |

ASR 事实见 [Paraformer Python SDK](https://help.aliyun.com/zh/model-studio/paraformer-real-time-speech-recognition-python-sdk)。视觉事实见 [Qwen3-VL-Flash 模型页](https://help.aliyun.com/zh/model-studio/qwen3-vl-flash)与[视觉 API](https://help.aliyun.com/zh/model-studio/vision)。向量事实见[文本向量同步 API](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)。

官方 ASR 总览将 Paraformer 列为较早一代，并建议条件允许时迁移 Fun-ASR 或 Qwen-ASR。上述 ASR 候选的理由是上传路径和时间戳接口清晰，不能据此宣称中文质量最佳。资格验证阶段应加入一个新版 ASR 候选，以同一标注小样本比较识别、时间定位和费用；新版是否提供满足 M2 要求的句级时间戳须先核查。[ASR 总览](https://help.aliyun.com/zh/model-studio/asr-model)

## 2. 接入边界与文档差异

### ASR 不需要公开本地 Silo

实时 SDK 可以从本地文件分段读取并发送音频。文档建议每包约 100 ms、1–16 KB；模型支持任意采样率，但 `format` 与实际编码必须一致，WAV 必须为 PCM。华北 2（北京）提供工作空间专属域名，应把 endpoint、Workspace ID、模型和密钥作为服务端配置。静音心跳与终句判断遵循 SDK 协议，不应直接把每次中间回调当成新的转录句。[SDK 参数与调用](https://help.aliyun.com/zh/model-studio/paraformer-real-time-speech-recognition-python-sdk)

工程推论：Python Worker 从 Silo 读取授权素材、提取有界音频块后主动向云端发送，不把 `localhost`、Compose 主机名或仅内网可访问的签名 URL 交给云服务。通过分块限制内存、取消响应和重试范围；块偏移与源音视频起点差值必须由本地计算，不由模型猜测。实时上传没有已核查的离线加速 SLA，需实测发送速度、总耗时及连接恢复。

备选 `paraformer-v2` 文件识别采用异步提交/查询，`file_urls` 支持 HTTP/HTTPS，当前 REST 文档写明单次 1 个 URL，也有 OSS 临时上传途径。临时 URL 有效期 48 小时，官方不建议用于生产，上传凭证接口还有独立限流。每个请求音轨独立计费，最终任务成功仍须检查子任务状态。[文件 ASR API](https://help.aliyun.com/zh/model-studio/paraformer-recorded-speech-recognition-restful-api)

因此文件 ASR 需要服务方实际可下载的 URL，不能复用本地浏览器可访问就认为云端可达。若启用此备选，需另行建立仅存有界测试音频的短期云暂存、访问有效期和删除验证，明确存储/传输费用及数据外传范围。M2 默认优先验证直接二进制上传路径，不要求将本地 Silo 暴露到公网。

### VLM 的多图与视频帧列表不同

视觉 API 支持多图；Base64 图像使用 `data:image/jpeg;base64,...` 等 Data URI。当前文档中，Qwen3-VL 原图与编码后的 Data URI 均有 20 MB 上限，工作空间域名整体请求体有 64 MB 上限，旧 DashScope 域名为 16 MiB；多图共享总额度。普通 Base64 多图最多 250 张，还受图文总 tokens 约束。**`video` 图像列表的至少 4 帧限制属于另一输入方式**，不应用它解释普通多图。M2 应主动设置远低于上限的图片数、分辨率和请求体预算，并在适配测试中核验实际 endpoint 的限制。[图像与视频输入限制](https://help.aliyun.com/zh/model-studio/vision)

官方模型页当前把 `qwen3-vl-flash` 别名描述为对应 2026-01-22 快照，但能力表存在差异：别名列支持结构化输出，而该快照列不支持。不能推断 JSON Schema 模式在所选快照上必然可用。M2 采用提示要求 JSON、解析和 Pydantic 严格校验，拒绝未知帧 ID、越界字段和虚构时间；是否启用服务商结构化输出须通过小样本 API 资格测试。[别名与快照能力表](https://help.aliyun.com/zh/model-studio/qwen3-vl-flash)

### Embedding 的参数名取决于 API

同步 API 明确支持 1024 维；兼容接口使用 `dimensions`，DashScope 使用 `dimension`，后者还提供 `text_type=query/document`。适配器需匹配所选协议，校验返回维数、有限浮点数、条目序号和 usage。不要把别的模型、维度或文本配置生成的向量放入同一版本索引。[同步 API 参数](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)

## 3. 价格参考与预算门槛

以下仅记录 **2026-10-09 核查的华北 2（北京）公开原价**，不包含免费额度、优惠、存储、网络、重试或其他计费项，也不保证用户账号获得相同结算。启动真实调用前必须在账号对应地域再次核查价格、模型权限和账单单位；国际站 USD 价格不能直接当成人民币价格。

| 候选 | 本次核查的北京公开原价 | 来源 |
|---|---|---|
| `paraformer-realtime-v2` | 音频输入 0.00024 元/秒 | [模型价格](https://help.aliyun.com/zh/model-studio/paraformer-realtime-v2) |
| `qwen3-vl-flash-2026-01-22` | 每次输入 ≤32k tokens：输入 0.15 元/百万 tokens、输出 1.5 元/百万 tokens；较大输入另有阶梯 | [快照价格](https://help.aliyun.com/zh/model-studio/qwen3-vl-flash) |
| `text-embedding-v4` | 文本输入 0.5 元/百万 tokens，Batch File 0.25 元/百万 tokens；首轮不依赖 Batch 折扣 | [模型价格](https://help.aliyun.com/zh/model-studio/text-embedding-v4) |

算术参考：若单次转录输入 10 分钟音频，按上述 ASR 原价计算为 0.144 元；120 分钟为 1.728 元。后者仅用于预算公式，不代表本仓库执行过 120 分钟素材测试，更不代表全部分析可在 5 元内完成。VLM 图像 tokens、描述输出量、向量化和重复调用仍需计入；真实大素材/容量验收不在本次研究范围。

预算设计应在调用前保守预留输入与最大输出费用，把 usage、provider request ID、价格版本和结算状态持久化。未知是否已收费的超时不直接无限重试，也不立即释放全部预留额度。单素材和累计验收预算需原子扣减，失败重试同样计入。免费额度只影响实际结算，不作为准许无限调用的依据。

## 4. pgvector 检索路径

pgvector 支持精确检索，也支持 HNSW、IVFFlat 近似检索。官方建议在过滤列建立普通索引；对于近似索引，过滤发生在索引扫描后，可能不足 Top K。0.8.0 起可启用迭代扫描缓解，但仍受扫描上限约束，共享 ANN 索引还会使不同租户影响彼此召回。官方提供用精确搜索对照近似搜索测召回的方法。[pgvector 官方说明：过滤、租户与迭代扫描](https://github.com/pgvector/pgvector#filtering)

M2 建议先以项目、素材版本、已发布分析版本筛选，再在合法集合内精确计算距离并排序，建立项目/版本过滤索引。把精确检索作为正确性基线；仅当数据量与延迟实测证明需要时引入 ANN，并比较过滤后 Recall@K 与跨项目隔离。1024 维适合当前 `vector` ANN 的 2000 维上限，但无需为未来 ANN 提前建立索引。[pgvector 索引与监测](https://github.com/pgvector/pgvector)

## 5. 最终选型资格门槛

1. 核查账号地域、模型权限、真实 endpoint、精确模型 ID、SDK 版本、请求限额、当前价格及素材许可；不把多个地域的能力表拼成一个虚构配置。
2. 在许可的小样本上验证中文识别和句级定位，含静音、无音轨、短句、块边界和非零音视频起点。记录标注与实测结果，再决定 ASR 是否需要更换；流式与文件式协议不可混用。
3. 在少量 JPEG 上验证指定 VLM 快照的多图、帧 ID、输出校验和实际 usage；检索结果只能说明采样画面证据，不承诺帧级动作边界或已发现未采样事件。
4. 验证中文向量、1024 维与批次顺序，建立独立检索标注集，比较 ASR/视觉单路、融合与精确搜索基线；模型“支持中文”不等于已证明本项目 Recall@K 达标。
5. 在 5 元单素材、50 元累计门槛内执行；无足够预算时停止并报告已覆盖范围，不能通过缩小标注集或改写目标宣称验收通过。付费调用与真实数据验收记录在后续实现阶段补充，本文件只完成官方资料研究。
