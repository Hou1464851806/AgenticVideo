import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";
import schema from "../../contracts/m1-dsl.schema.json";

export interface Clip {
  clip_id: string;
  asset_id: string;
  asset_version: 1;
  source_in_ms: number;
  source_out_ms: number;
  timeline_start_ms: number;
  crop_mode: "fit" | "center";
}
export interface Dsl {
  schema_version: "1.0";
  output: {
    width: number;
    height: number;
    fps_num: 30;
    fps_den: 1;
    video_codec: "h264";
  };
  tracks: [{ track_id: string; type: "video"; clips: Clip[] }];
  constraints?: { target_duration_ms?: number; locked_clip_ids?: [] };
}
export interface Violation {
  code: string;
  path: string;
  message: string;
}
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
const schemaValidator = ajv.compile<Dsl>(schema);
export function validateDsl(value: unknown): Violation[] {
  if (!schemaValidator(value))
    return (schemaValidator.errors ?? []).map((e) => ({
      code: "DSL_SCHEMA_INVALID",
      path: e.instancePath || "/",
      message: e.message || "Invalid DSL",
    }));
  const errors: Violation[] = [];
  const ids = new Set<string>();
  let elapsed = 0;
  value.tracks[0].clips.forEach((clip, index) => {
    const path = `/tracks/0/clips/${index}`;
    if (ids.has(clip.clip_id))
      errors.push({
        code: "DUPLICATE_CLIP_ID",
        path: path + "/clip_id",
        message: "片段 ID 重复",
      });
    ids.add(clip.clip_id);
    if (clip.source_out_ms <= clip.source_in_ms)
      errors.push({
        code: "SOURCE_RANGE_INVALID",
        path: path + "/source_out_ms",
        message: "结束时间必须晚于开始时间",
      });
    if (clip.timeline_start_ms !== elapsed)
      errors.push({
        code: "TIMELINE_NOT_CONTIGUOUS",
        path: path + "/timeline_start_ms",
        message: "时间线必须连续",
      });
    elapsed += clip.source_out_ms - clip.source_in_ms;
  });
  if (elapsed > 600000)
    errors.push({
      code: "OUTPUT_DURATION_EXCEEDED",
      path: "/tracks",
      message: "输出不能超过 10 分钟",
    });
  if (
    value.constraints?.target_duration_ms !== undefined &&
    value.constraints.target_duration_ms !== elapsed
  )
    errors.push({
      code: "TARGET_DURATION_MISMATCH",
      path: "/constraints/target_duration_ms",
      message: "目标时长与片段累计时长不一致",
    });
  return errors;
}
