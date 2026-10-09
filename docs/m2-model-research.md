# M2 候选模型与向量检索核查

核查日期：2026-10-09。状态：**官方资料核查完成；候选未最终选定，未调用收费模型，未上传素材，未做效果、账单或账号可用性实测**。本文件为 M2 计划提供选型依据，不表示相应功能已实现。

用户已确定：中文口播为主，同时验收基本视觉检索；优先云 API；仅使用允许外传的测试素材；分析由用户看到估算后点击启动；单素材模型费用上限初始为 5 元、首轮真实模型验收累计上限初始为 50 元，均允许配置调整。预算是停止门槛，不是服务价格或分析完成的承诺。

后续讨论将模型方向收敛为阿里云 Qwen ASR、Qwen3-VL 与 Qwen3-Embedding；本次据此修订原 Paraformer 首选。供应商方向已明确，接口/精确版本仍属资格候选。时间与音画关联、产物和检索编码的实施边界统一维护在[M2 计划第 6、7 章](milestone-2-plan.md#6-分析流水线与统一时间轴)。

## 1. 首轮适配候选

| 能力 | 候选 | 官方资料支持 | M2 接入建议与剩余核验 |
|---|---|---|---|
| 中文 ASR 优先 | `qwen-audio-3.1-asr-flash-streaming` | 直接发送音频流；Qwen-Audio-3.x 默认返回句与词时间戳，官方包含本地文件发送示例 | 先做 16 kHz 单声道 PCM 适配实验；只持久化终句，将块内时间映射回来源。需测边界、静音、发送节奏、断连、token usage 与费用上界；不承诺比实时更快 |
| 文件 ASR 条件备选 | `qwen3-asr-flash-filetrans` | 异步 DashScope 文件识别，云端可访问 file_url，句级时间；enable_words 可增加字/词级时间 | 仅在私有云暂存与删除/费用方案明确后启用；更细时间不自动代表更高定位精度，分句变化需验证 |
| 视觉描述 | `qwen3-vl-flash-2026-01-22` | 官方列出该快照，输入包含图像；通用视觉 API 支持多图与 Base64 Data URI | 固定快照优于使用可能更新的别名。传少量带真实帧 ID 的缩放 JPEG；本地严格校验输出，不依赖模型自行给出时间 |
| 中文文本向量 | `text-embedding-v4`（Qwen3-Embedding 系列） | 支持中文，默认 1024 维，可显式选择维度；最多 10 条/批，单条最多 8192 tokens | 首轮固定 1024 维，分别保存 ASR 文本和视觉描述向量；同一模型/维度，查询与索引使用兼容的 query/document 编码策略 |

ASR 事实见[实时识别与时间戳说明](https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide)及[Qwen-ASR 文件接口](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)。视觉事实见 [Qwen3-VL-Flash 模型页](https://help.aliyun.com/zh/model-studio/qwen3-vl-flash)与[视觉 API](https://help.aliyun.com/zh/model-studio/vision)。向量系列归属见[Embedding 模型总览](https://help.aliyun.com/zh/model-studio/embedding)，参数见[文本向量同步 API](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)。

上述 ASR 优先级是基于直接上传和时间戳契约的工程判断，不能据此宣称中文质量最佳。原 `paraformer-realtime-v2` 保留为历史比较/必要时回退方向，不默认同时接入多套模型；资格阶段用同一标注小样本比较时间定位、质量与费用后锁定。

## 2. 接入边界与文档差异

### ASR 不需要公开本地 Silo

Qwen-Audio-3.1 streaming 可通过 WebSocket 发送本地录制音频，实时识别文档给出终句与中间结果区分。默认句/词时间戳能力与 `qwen3-asr-flash-realtime` 不同，后者当前官方明确无时间戳；不能仅凭名称含 Qwen 或 realtime 认为可互换。[实时识别与时间戳说明](https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide)

具体 endpoint、Workspace ID、模型、SDK 与密钥均为服务端配置。按所选协议校验编码/采样率、发送节奏、终句和 usage，不照搬其他 ASR 的参数；M2 初始提取 mono 16 kHz PCM。普通 `qwen3-asr-flash` 短音频文本识别不直接视为满足句级定位契约，若使用必须另行验证所需时间信息。[ASR 模型与接口差异](https://help.aliyun.com/zh/model-studio/asr-model)

工程推论：Python Worker 从 Silo 读取授权素材、提取有界音频块后主动向云端发送，不把 `localhost`、Compose 主机名或仅内网可访问的签名 URL 交给云服务。通过分块限制内存、取消响应和重试范围；块偏移与源音视频起点差值必须由本地计算，不由模型猜测。实时上传没有已核查的离线加速 SLA，需实测发送速度、总耗时及连接恢复。

备选 `qwen3-asr-flash-filetrans` 使用异步 DashScope 提交/查询，输入为云端可下载的 `file_url`。返回句级时间，`enable_words=true` 可增加字/词级时间，并影响分句策略；适配器应校验毫秒单位、任务状态及结果 URL。其支持长文件的服务限制不等于本仓库已验证相同容量。[文件 ASR API](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)

因此文件 ASR 需要服务方实际可下载的 URL，不能复用本地浏览器可访问就认为云端可达。若启用此备选，需另行建立仅存有界测试音频的短期云暂存、访问有效期和删除验证，明确存储/传输费用及数据外传范围。M2 默认优先验证直接二进制上传路径，不要求将本地 Silo 暴露到公网。

### VLM 的多图与视频帧列表不同

视觉 API 支持多图；Base64 图像使用 `data:image/jpeg;base64,...` 等 Data URI。当前文档中，Qwen3-VL 原图与编码后的 Data URI 均有 20 MB 上限，工作空间域名整体请求体有 64 MB 上限，旧 DashScope 域名为 16 MiB；多图共享总额度。普通 Base64 多图最多 250 张，还受图文总 tokens 约束。**`video` 图像列表的至少 4 帧限制属于另一输入方式**，不应用它解释普通多图。M2 应主动设置远低于上限的图片数、分辨率和请求体预算，并在适配测试中核验实际 endpoint 的限制。[图像与视频输入限制](https://help.aliyun.com/zh/model-studio/vision)

官方模型页当前把 `qwen3-vl-flash` 别名描述为对应 2026-01-22 快照，但能力表存在差异：别名列支持结构化输出，而该快照列不支持。不能推断 JSON Schema 模式在所选快照上必然可用。M2 采用提示要求 JSON、解析和 Pydantic 严格校验，拒绝未知帧 ID、越界字段和虚构时间；是否启用服务商结构化输出须通过小样本 API 资格测试。[别名与快照能力表](https://help.aliyun.com/zh/model-studio/qwen3-vl-flash)

### Embedding 的参数名取决于 API

官方把 `text-embedding-v4` 归入 Qwen3-Embedding 系列，但不能进一步推断云 API 与某个开源权重/参数规模完全相同。`qwen3-vl-embedding` 是另一种直接图像/视频等多模态向量路线，不是文本 v4 的别名。[Embedding 模型总览](https://help.aliyun.com/zh/model-studio/embedding)

同步 API 明确支持 1024 维；兼容接口使用 `dimensions`，DashScope 使用 `dimension`，后者还提供 `text_type=query/document`。索引用 document、查询用 query；`instruct` 仅适用于支持它的 query 调用，内容/版本需固定并评测。适配器匹配所选协议，校验维数、有限浮点数、条目序号和 usage。两种角色的配置组合需兼容，不要求参数完全相同；不同模型/空间即使维数相同也不能混索引。[同步 API 参数](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)

工程取舍：M2 用 ASR 文本和独立视觉描述做文本 Embedding，原始音画证据另外保存。它能支持文本语义相似性检索，不能补回取帧或描述阶段遗漏的信息，也不能仅靠向量融合证明音画语义关系。直接多模态 Embedding 留到质量评测定位出具体缺口后再考虑。

### 模型协议统一的范围

本轮讨论决定 **OpenAI 兼容优先，生成类优先 Responses**。OpenAI 官方文档将 Responses 定义为生成接口，采用类型化 `input/output`，结构化输出使用 `text.format`；Embedding 与语音转写各有独立端点。统一 SDK 不意味着这三类请求可以使用同一请求体。[OpenAI Responses 迁移说明](https://developers.openai.com/api/docs/guides/migrate-to-responses)、[Embedding API](https://developers.openai.com/api/reference/resources/embeddings/methods/create)、[转写 API](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)

阿里云提供 OpenAI 兼容 `/responses`，但官方明确兼容性存在参数和行为差异：未列出的参数会被忽略，目前不支持 `background`；`store` 默认开启，可显式关闭。文档未明确列出所选 `qwen3-vl-flash-2026-01-22` 的完整支持情况，通用图像参数也不能证明该快照已通过。应在 P0 验证多图、实际输出、usage 和限制；未验证前不称“Responses 已可用”，也不因未列出就断言模型完全不可调用。[阿里云 Responses 兼容范围](https://help.aliyun.com/en/model-studio/qwen-api-via-openai-responses)

| 能力 | M2 协议决定 | 资格核查重点 |
|---|---|---|
| VLM / 后续生成 | Responses 优先；明确配置的 Chat Completions 为回退 | 所选快照的多图/帧引用、结束状态、输出上限、usage、结构化模式及参数实际生效；无状态调用，关闭存储不等于零保留 |
| 文本向量 | 专用 OpenAI 兼容 `/embeddings` 优先；必要时 DashScope | 1024 维与批次顺序；兼容端点是否能保留 query/document 和可选 instruct，不假设原生参数自动生效 |
| ASR | 原生流式 WebSocket，条件备选为异步文件 HTTP | 终句、句/词时间与单位、provider ID、usage、断连和取消；保留来源映射，不为了格式统一换成无时间戳的识别路径 |

ASR 协议事实见[Qwen-Audio 流式客户端事件](https://help.aliyun.com/en/model-studio/qwen-audio-asr-streaming-client-events)；向量参数见[Embedding API 参数](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)。工程上在 Python 内部统一 generate/transcribe/embed 的类型契约、预算与错误分类，供应商协议留在适配器中；不增加独立代理服务。收费重试、SDK 自动重试、usage 缺失和未决账单的行为按[M2 计划 3.3](milestone-2-plan.md#33-模型调用协议与统一适配边界)核验，协议回退不能触发不确定请求的重复收费。

## 3. 价格参考与预算门槛

以下仅记录 **2026-10-09 核查的华北 2（北京）公开原价**，不包含免费额度、优惠、存储、网络、重试或其他计费项，也不保证用户账号获得相同结算。启动真实调用前必须在账号对应地域再次核查价格、模型权限和账单单位；国际站 USD 价格不能直接当成人民币价格。

| 候选 | 本次核查的北京公开原价 | 来源 |
|---|---|---|
| `qwen-audio-3.1-asr-flash-streaming` | 输入 6 元/百万 tokens、输出 4.5 元/百万 tokens | [模型价格](https://help.aliyun.com/zh/model-studio/qwen-audio-3-1-asr-flash-streaming) |
| `qwen3-asr-flash-filetrans` | 音频输入 0.00022 元/秒 | [模型价格](https://help.aliyun.com/zh/model-studio/qwen3-asr-flash-filetrans) |
| `qwen3-vl-flash-2026-01-22` | 每次输入 ≤32k tokens：输入 0.15 元/百万 tokens、输出 1.5 元/百万 tokens；较大输入另有阶梯 | [快照价格](https://help.aliyun.com/zh/model-studio/qwen3-vl-flash) |
| `text-embedding-v4` | 文本输入 0.5 元/百万 tokens，Batch File 0.25 元/百万 tokens；首轮不依赖 Batch 折扣 | [模型价格](https://help.aliyun.com/zh/model-studio/text-embedding-v4) |

算术参考：仅对上述文件 ASR，10 分钟音频按 600 × 0.00022 计算为 0.132 元。该秒价不能用于 Qwen-Audio-3.1 streaming；后者需核验音频 token 换算、输出上界与 usage 后才能形成预算公式，不能把短样本均价当硬上界。VLM 图像 tokens、描述输出量、向量化、重复调用及文件暂存费用仍需计入；不代表全部分析可在 5 元内完成。真实大素材/容量验收不在本次研究范围。

预算设计应在调用前保守预留输入与最大输出费用，把 usage、provider request ID、价格版本和结算状态持久化。未知是否已收费的超时不直接无限重试，也不立即释放全部预留额度。单素材和累计验收预算需原子扣减，失败重试同样计入。免费额度只影响实际结算，不作为准许无限调用的依据。

## 4. pgvector 检索路径

pgvector 支持精确检索，也支持 HNSW、IVFFlat 近似检索。官方建议在过滤列建立普通索引；对于近似索引，过滤发生在索引扫描后，可能不足 Top K。0.8.0 起可启用迭代扫描缓解，但仍受扫描上限约束，共享 ANN 索引还会使不同租户影响彼此召回。官方提供用精确搜索对照近似搜索测召回的方法。[pgvector 官方说明：过滤、租户与迭代扫描](https://github.com/pgvector/pgvector#filtering)

M2 建议先以项目、素材版本、已发布分析版本筛选，再在合法集合内精确计算距离并排序，建立项目/版本过滤索引。把精确检索作为正确性基线；仅当数据量与延迟实测证明需要时引入 ANN，并比较过滤后 Recall@K 与跨项目隔离。1024 维适合当前 `vector` ANN 的 2000 维上限，但无需为未来 ANN 提前建立索引。[pgvector 索引与监测](https://github.com/pgvector/pgvector)

## 5. 最终选型资格门槛

1. 核查账号地域、模型权限、真实 endpoint、精确模型 ID、SDK 版本、请求限额、当前价格及素材许可；建立模型/协议/参数能力矩阵，不把多个地域的能力表拼成一个虚构配置。未列出的参数不默认有效，回退协议显式锁定配置。
2. 在许可的小样本上验证 Qwen ASR 中文识别、终句和句级定位，含静音、无音轨、短句、块边界和非零音视频起点；将时间映射回源视频，不用响应到达时间。核验 token 计费的可计算上界，无法满足预算契约时再评估文件备选；流式与文件式协议不可混用。
3. 在少量 JPEG 上验证指定 VLM 快照的 Responses 多图、帧 ID、输出校验、结束状态和实际 usage；不通过时核验 Chat Completions 回退，记录最终协议。检索结果只能说明采样画面证据，不承诺帧级动作边界或已发现未采样事件。
4. 验证中文向量、1024 维、query/document 角色与批次顺序，必要时采用 DashScope 保留编码策略；建立独立检索标注集，比较 ASR/视觉单路、融合与精确搜索基线，覆盖同项目不兼容模型版本；另报复合条件诊断，不将时间共现视作语义关系。模型“支持中文”不等于已证明本项目 Recall@K 达标。
5. 在 5 元单素材、50 元累计门槛内执行；无足够预算时停止并报告已覆盖范围，不能通过缩小标注集或改写目标宣称验收通过。付费调用与真实数据验收记录在后续实现阶段补充，本文件只完成官方资料研究。
