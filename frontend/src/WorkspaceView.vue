<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import UploadPanel from "./UploadPanel.vue";
import { api, ApiError } from "./api";
import { validateDsl, type Dsl, type Violation } from "./dsl";
import {
  activeJob,
  type Asset,
  type Project,
  type Page,
  type Revision,
  type Job,
} from "./models";
const route = useRoute(),
  router = useRouter();
const projectId = String(route.params.id);
const project = ref<Project>(),
  assets = ref<Asset[]>([]),
  plans = ref<Revision[]>([]),
  history = ref<Revision[]>([]),
  jobs = ref<Job[]>([]);
const selected = ref<Revision>(),
  name = ref("我的剪辑"),
  dirty = ref(false),
  busy = ref(false),
  error = ref(""),
  message = ref(""),
  details = ref<Violation[]>([]),
  conflict = ref(false);
const mediaUrl = ref(""),
  mediaTitle = ref(""),
  jsonText = ref(""),
  showJson = ref(false),
  historyOffset = ref(0),
  historyTotal = ref(0),
  jobsLimit = ref(30),
  jobsTotal = ref(0);
const approvalOpen = ref(false),
  approvalRevision = ref<Revision>();
const freshDsl = (): Dsl => ({
  schema_version: "1.0",
  output: {
    width: 1920,
    height: 1080,
    fps_num: 30,
    fps_den: 1,
    video_codec: "h264",
  },
  tracks: [{ track_id: "video", type: "video", clips: [] }],
});
const draft = ref<Dsl>(freshDsl());
const clips = computed(() => draft.value.tracks[0].clips);
const duration = computed(() =>
  clips.value.reduce(
    (sum, clip) => sum + clip.source_out_ms - clip.source_in_ms,
    0,
  ),
);
const portrait = computed({
  get: () => draft.value.output.height > draft.value.output.width,
  set: (value) => {
    draft.value.output.width = value ? 1080 : 1920;
    draft.value.output.height = value ? 1920 : 1080;
    changed();
  },
});
const canRender = computed(() => selected.value && !dirty.value && !busy.value);
const statusNames: Record<string, string> = {
  MEDIA_READY: "可用",
  VALIDATING: "校验中",
  INVALID: "不可用",
  DELETING: "删除中",
  QUEUED: "排队中",
  RUNNING: "处理中",
  CANCEL_REQUESTED: "取消中",
  CANCELLED: "已取消",
  FAILED: "失败",
  SUCCEEDED: "已完成",
};
function report(e: unknown) {
  if (e instanceof ApiError) {
    error.value = `${e.message} · ${e.code}${e.traceId ? ` · 请求 ${e.traceId}` : ""}`;
    details.value = e.details;
    conflict.value = e.code === "EDIT_PLAN_VERSION_CONFLICT";
    if (e.status === 401) void router.push("/login");
  } else error.value = e instanceof Error ? e.message : "连接失败，请重试";
}
async function action(work: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  details.value = [];
  message.value = "";
  try {
    await work();
  } catch (e) {
    report(e);
  } finally {
    busy.value = false;
  }
}
async function refreshAssets() {
  assets.value = (
    await api<Page<Asset>>(`/projects/${projectId}/assets?limit=30`)
  ).items;
}
async function refreshJobs() {
  const page = await api<Page<Job>>(
    `/projects/${projectId}/render-jobs?limit=${jobsLimit.value}`,
  );
  jobs.value = page.items;
  jobsTotal.value = page.total;
}
async function refreshPlans() {
  plans.value = (
    await api<Page<Revision>>(`/projects/${projectId}/edit-plans?limit=100`)
  ).items;
}
async function loadHistory() {
  if (!selected.value) return;
  const page = await api<Page<Revision>>(
    `/edit-plans/${selected.value.plan_id}/revisions?offset=${historyOffset.value}&limit=10`,
  );
  history.value = page.items;
  historyTotal.value = page.total;
}
async function choose(revision: Revision) {
  selected.value = revision;
  draft.value = JSON.parse(JSON.stringify(revision.dsl));
  name.value = revision.name;
  dirty.value = false;
  conflict.value = false;
  details.value = [];
  approvalOpen.value = false;
  jsonText.value = JSON.stringify(draft.value, null, 2);
  await router.replace({
    query: { plan: revision.plan_id, revision: revision.revision },
  });
  await loadHistory();
}
function changed() {
  dirty.value = true;
  approvalOpen.value = false;
  let start = 0;
  clips.value.forEach((clip) => {
    clip.timeline_start_ms = start;
    start += clip.source_out_ms - clip.source_in_ms;
  });
  if (draft.value.constraints?.target_duration_ms !== undefined)
    draft.value.constraints.target_duration_ms = start;
  jsonText.value = JSON.stringify(draft.value, null, 2);
}
function add(asset: Asset) {
  clips.value.push({
    clip_id: `clip_${crypto.randomUUID()}`,
    asset_id: asset.id,
    asset_version: 1,
    source_in_ms: 0,
    source_out_ms: Math.min(asset.duration_ms ?? 0, 10000),
    timeline_start_ms: duration.value,
    crop_mode: "fit",
  });
  changed();
}
function move(index: number, delta: number) {
  const target = index + delta;
  if (target < 0 || target >= clips.value.length) return;
  const [clip] = clips.value.splice(index, 1);
  clips.value.splice(target, 0, clip!);
  changed();
}
function remove(index: number) {
  clips.value.splice(index, 1);
  changed();
}
function assetName(id: string) {
  return assets.value.find((asset) => asset.id === id)?.filename ?? id;
}
function seconds(ms: number | null) {
  return ms === null ? "—" : (ms / 1000).toFixed(2) + "s";
}
function validate(): boolean {
  details.value = validateDsl(draft.value);
  clips.value.forEach((clip, index) => {
    const asset = assets.value.find((a) => a.id === clip.asset_id);
    if (
      !asset ||
      asset.status !== "MEDIA_READY" ||
      clip.source_out_ms > (asset.duration_ms ?? 0)
    )
      details.value.push({
        code: "SOURCE_RANGE_INVALID",
        path: `/tracks/0/clips/${index}`,
        message: "素材不可用或结束时间超出素材时长",
      });
  });
  if (details.value.length) {
    error.value = "请修正剪辑方案中的问题";
    return false;
  }
  return true;
}
async function save() {
  await action(async () => {
    if (!validate()) return;
    const key = crypto.randomUUID();
    const saved = selected.value
      ? await api<Revision>(`/edit-plans/${selected.value.plan_id}`, {
          method: "PUT",
          key,
          revision: selected.value.revision,
          body: { dsl: draft.value },
        })
      : await api<Revision>(`/projects/${projectId}/edit-plans`, {
          method: "POST",
          key,
          body: { name: name.value, dsl: draft.value },
        });
    await choose(saved);
    await refreshPlans();
    message.value = `版本 R${saved.revision} 已保存`;
  });
}
async function reloadCurrent() {
  await action(async () => {
    if (selected.value)
      await choose(
        await api<Revision>(`/edit-plans/${selected.value.plan_id}`),
      );
  });
}
async function newPlan() {
  selected.value = undefined;
  draft.value = freshDsl();
  name.value = "我的剪辑";
  dirty.value = true;
  history.value = [];
  approvalOpen.value = false;
  jsonText.value = "";
  await router.replace({ query: {} });
}
async function preview() {
  await action(async () => {
    if (dirty.value || !selected.value) return;
    await submitRender(selected.value, "PREVIEW_720P");
  });
}
async function submitRender(revision: Revision, profile: string) {
  await api<Job>("/render-jobs", {
    method: "POST",
    key: crypto.randomUUID(),
    body: {
      plan_id: revision.plan_id,
      revision: revision.revision,
      render_profile: profile,
    },
  });
  await refreshJobs();
  schedule();
  message.value = "任务已提交，可继续编辑或稍后回来查看";
}
function openApproval() {
  if (!canRender.value || !selected.value) return;
  approvalRevision.value = JSON.parse(JSON.stringify(selected.value));
  approvalOpen.value = true;
}
async function approveExport() {
  await action(async () => {
    const revision = approvalRevision.value;
    if (!revision) return;
    await api(`/edit-plans/${revision.plan_id}/approve`, {
      method: "POST",
      body: { revision: revision.revision, render_profile: "FINAL_1080P" },
    });
    await submitRender(revision, "FINAL_1080P");
    approvalOpen.value = false;
  });
}
async function playAsset(asset: Asset) {
  await action(async () => {
    const result = await api<{ url: string }>(`/assets/${asset.id}/content`);
    mediaUrl.value = result.url;
    mediaTitle.value = asset.filename;
  });
}
async function playJob(job: Job) {
  await action(async () => {
    const result = await api<{ url: string }>(`/render-jobs/${job.id}/output`);
    mediaUrl.value = result.url;
    mediaTitle.value = `R${job.revision} · ${job.render_profile === "FINAL_1080P" ? "正式成片" : "预览"}`;
  });
}
async function download(job: Job) {
  await action(async () => {
    const result = await api<{ url: string }>(
      `/render-jobs/${job.id}/output?download=true`,
    );
    const anchor = document.createElement("a");
    anchor.href = result.url;
    anchor.download = "export.mp4";
    anchor.click();
  });
}
async function cancel(job: Job) {
  await action(async () => {
    await api(`/render-jobs/${job.id}/cancel`, { method: "POST" });
    await refreshJobs();
    schedule();
  });
}
async function retry(job: Job) {
  await action(async () => {
    await api(`/render-jobs/${job.id}/retry`, {
      method: "POST",
      key: crypto.randomUUID(),
    });
    await refreshJobs();
    schedule();
  });
}
async function deleteAsset(asset: Asset) {
  await action(async () => {
    await api(`/assets/${asset.id}`, { method: "DELETE" });
    await refreshAssets();
    schedule();
  });
}
async function uploaded() {
  try {
    await refreshAssets();
    schedule();
  } catch (e) {
    report(e);
  }
}
function importJson() {
  error.value = "";
  try {
    const candidate: unknown = JSON.parse(jsonText.value);
    const violations = validateDsl(candidate);
    if (violations.length) {
      details.value = violations;
      error.value = "导入的方案无效";
      return;
    }
    draft.value = candidate as Dsl;
    dirty.value = true;
    approvalOpen.value = false;
    details.value = [];
    message.value = "DSL 已导入草稿，请保存新版本";
  } catch {
    error.value = "JSON 格式无效，草稿未替换";
  }
}
let timer: ReturnType<typeof setTimeout> | undefined,
  failures = 0,
  disposed = false,
  polling = false;
function schedule(delay = 2000) {
  clearTimeout(timer);
  if (!disposed && !document.hidden) timer = setTimeout(poll, delay);
}
async function poll() {
  if (disposed || document.hidden || polling) return;
  polling = true;
  try {
    await Promise.all([refreshAssets(), refreshJobs()]);
    failures = 0;
  } catch (e) {
    failures++;
    report(e);
  } finally {
    polling = false;
    if (
      failures ||
      assets.value.some((a) => ["VALIDATING", "DELETING"].includes(a.status)) ||
      jobs.value.some(activeJob)
    )
      schedule(Math.min(30000, 2000 * 2 ** Math.min(failures, 4)));
  }
}
function visibility() {
  clearTimeout(timer);
  if (!document.hidden) schedule(1);
}
onMounted(async () => {
  document.addEventListener("visibilitychange", visibility);
  await action(async () => {
    project.value = await api<Project>(`/projects/${projectId}`);
    await Promise.all([refreshAssets(), refreshPlans(), refreshJobs()]);
    const plan =
      typeof route.query.plan === "string"
        ? route.query.plan
        : plans.value[0]?.plan_id;
    if (plan) {
      const rev = Number(route.query.revision);
      await choose(
        await api<Revision>(
          `/edit-plans/${plan}${Number.isInteger(rev) && rev > 0 ? `/revisions/${rev}` : ""}`,
        ),
      );
    }
  });
  schedule();
});
onUnmounted(() => {
  disposed = true;
  clearTimeout(timer);
  document.removeEventListener("visibilitychange", visibility);
});
</script>
<template>
  <main class="workspace">
    <div class="page-heading">
      <div>
        <RouterLink to="/" class="back-link">← 我的项目</RouterLink>
        <h1>{{ project?.name ?? "剪辑工作台" }}</h1>
      </div>
      <span class="pill">M1 · 手动剪辑</span>
    </div>
    <div v-if="error" class="notice error" role="alert">
      <p>{{ error }}</p>
      <ul v-if="details.length">
        <li v-for="(detail, index) in details" :key="index">
          {{ detail.path }}：{{ detail.message }}
        </li>
      </ul>
      <button v-if="conflict" @click="reloadCurrent">
        读取最新版本并替换草稿</button
      ><small v-if="conflict"
        >你的草稿仍保留；复制 DSL 后再读取最新版本。</small
      >
    </div>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <div class="workspace-grid">
      <aside class="panel asset-panel">
        <div class="section-heading">
          <h2>素材库</h2>
          <span>{{ assets.length }} / 30</span>
        </div>
        <UploadPanel :project-id="projectId" @uploaded="uploaded" />
        <p class="muted small">
          MP4 / MOV · H.264 / AAC · 单文件 ≤ 4 GB、120 分钟<br />项目累计 ≤ 180
          分钟，支持直传与取消。
        </p>
        <p v-if="!assets.length" class="empty">上传视频，开始第一段剪辑。</p>
        <article v-for="asset in assets" :key="asset.id" class="asset-item">
          <div class="asset-thumbnail">▶</div>
          <div class="asset-info">
            <strong>{{ asset.filename }}</strong
            ><small
              >{{ seconds(asset.duration_ms) }} · {{ asset.width ?? "—" }} ×
              {{ asset.height ?? "—" }}</small
            ><span class="state" :class="asset.status.toLowerCase()">{{
              statusNames[asset.status] ?? asset.status
            }}</span
            ><small v-if="asset.error_code" class="error">{{
              asset.error_code
            }}</small>
            <div class="button-row">
              <button
                v-if="asset.status === 'MEDIA_READY'"
                :disabled="busy"
                @click="add(asset)"
              >
                ＋ 添加片段</button
              ><button
                v-if="asset.status === 'MEDIA_READY'"
                class="quiet"
                @click="playAsset(asset)"
              >
                播放</button
              ><button
                :disabled="
                  busy ||
                  asset.status === 'VALIDATING' ||
                  asset.status === 'DELETING'
                "
                class="quiet danger"
                @click="deleteAsset(asset)"
              >
                删除
              </button>
            </div>
          </div>
        </article>
      </aside>
      <section class="editing-column">
        <div class="panel editor">
          <div class="section-heading">
            <h2>
              剪辑方案
              <span v-if="selected" class="pill">R{{ selected.revision }}</span>
            </h2>
            <button class="quiet" @click="newPlan">新建方案</button>
          </div>
          <div class="plan-settings">
            <label
              >方案名称<input
                v-model="name"
                :disabled="!!selected"
                maxlength="200"
                @input="changed" /></label
            ><label
              >画面比例<select v-model="portrait">
                <option :value="false">横屏 16:9</option>
                <option :value="true">竖屏 9:16</option>
              </select></label
            ><label v-if="plans.length"
              >已保存方案<select
                :value="selected?.plan_id ?? ''"
                @change="
                  action(async () => {
                    const value = ($event.target as HTMLSelectElement).value;
                    historyOffset = 0;
                    if (value)
                      await choose(await api<Revision>(`/edit-plans/${value}`));
                  })
                "
              >
                <option value="" disabled>选择方案</option>
                <option
                  v-for="plan in plans"
                  :key="plan.plan_id"
                  :value="plan.plan_id"
                >
                  {{ plan.name }} · R{{ plan.revision }}
                </option>
              </select></label
            >
          </div>
          <p class="muted small">
            添加素材后，用毫秒设置选段；移动片段调整顺序。单视频轨、连续拼接，输出上限
            10 分钟。
          </p>
          <div class="clip-list">
            <article
              v-for="(clip, index) in clips"
              :key="clip.clip_id"
              class="clip-row"
              :data-testid="`clip-${index}`"
            >
              <span class="clip-number">{{
                String(index + 1).padStart(2, "0")
              }}</span>
              <div class="clip-fields">
                <strong>{{ assetName(clip.asset_id) }}</strong>
                <div class="clip-controls">
                  <label
                    >开始（ms）<input
                      v-model.number="clip.source_in_ms"
                      type="number"
                      min="0"
                      step="1"
                      @input="changed" /></label
                  ><label
                    >结束（ms）<input
                      v-model.number="clip.source_out_ms"
                      type="number"
                      min="1"
                      step="1"
                      @input="changed" /></label
                  ><label
                    >适配方式<select v-model="clip.crop_mode" @change="changed">
                      <option value="fit">完整画面（留黑边）</option>
                      <option value="center">居中裁切（填满）</option>
                    </select></label
                  >
                </div>
              </div>
              <div class="clip-actions">
                <button
                  :disabled="index === 0"
                  :aria-label="`上移片段 ${index + 1}`"
                  @click="move(index, -1)"
                >
                  ↑</button
                ><button
                  :disabled="index === clips.length - 1"
                  :aria-label="`下移片段 ${index + 1}`"
                  @click="move(index, 1)"
                >
                  ↓</button
                ><button
                  :aria-label="`删除片段 ${index + 1}`"
                  @click="remove(index)"
                >
                  ×
                </button>
              </div>
            </article>
            <p v-if="!clips.length" class="empty">从左侧素材库添加片段。</p>
          </div>
          <div class="timeline-heading">
            <span>时间线 · 只读预览</span
            ><strong>{{ seconds(duration) }}</strong>
          </div>
          <div class="timeline" aria-label="只读时间线">
            <div
              v-for="(clip, index) in clips"
              :key="clip.clip_id"
              class="timeline-block"
              :style="{
                flexGrow: Math.max(1, clip.source_out_ms - clip.source_in_ms),
              }"
              :title="`${assetName(clip.asset_id)} ${seconds(clip.timeline_start_ms)} → ${seconds(clip.timeline_start_ms + clip.source_out_ms - clip.source_in_ms)}`"
            >
              <span>{{ index + 1 }}</span
              ><small>{{
                seconds(clip.source_out_ms - clip.source_in_ms)
              }}</small>
            </div>
          </div>
          <div class="editor-footer">
            <span class="muted small">{{
              dirty
                ? "草稿有未保存修改"
                : selected
                  ? `已保存 R${selected.revision}`
                  : "尚未保存"
            }}</span>
            <div class="button-row">
              <button :disabled="busy || !clips.length" @click="save">
                {{ selected ? "保存新版本" : "保存方案" }}</button
              ><button :disabled="!canRender" @click="preview">生成预览</button
              ><button
                class="primary"
                :disabled="!canRender"
                @click="openApproval"
              >
                确认并导出
              </button>
            </div>
          </div>
          <details
            @toggle="showJson = ($event.target as HTMLDetailsElement).open"
          >
            <summary>DSL 导入 / 检查</summary>
            <label
              >方案 JSON<textarea
                v-model="jsonText"
                rows="10"
                spellcheck="false"
                aria-label="方案 JSON"
              /></label
            ><button :disabled="busy" @click="importJson">导入为草稿</button>
            <p class="muted small">
              只接受当前 DSL；未知字段和不支持的操作会显示错误。
            </p>
          </details>
        </div>
        <section
          v-if="approvalOpen && approvalRevision"
          class="panel approval"
          aria-label="导出确认"
        >
          <h2>确认正式导出</h2>
          <p>
            {{ approvalRevision.name }} · 版本 R{{ approvalRevision.revision }}
          </p>
          <p>
            {{ approvalRevision.dsl.output.width }} ×
            {{ approvalRevision.dsl.output.height }} · 30 fps · H.264 + AAC ·
            MP4
          </p>
          <p class="muted small">
            确认绑定这个版本及其素材版本。后续修改需另存版本并重新确认。
          </p>
          <div class="button-row">
            <button class="primary" :disabled="busy" @click="approveExport">
              确认此版本并开始导出</button
            ><button @click="approvalOpen = false">返回编辑</button>
          </div>
        </section>
        <section v-if="selected" class="panel version-panel">
          <div class="section-heading">
            <h2>版本记录</h2>
            <span>{{ historyTotal }} 个版本</span>
          </div>
          <div class="version-list">
            <button
              v-for="revision in history"
              :key="revision.revision"
              :class="{ selected: selected.revision === revision.revision }"
              @click="action(async () => choose(revision))"
            >
              R{{ revision.revision }}
              <small>{{ revision.plan_hash.slice(0, 8) }}</small>
            </button>
          </div>
          <div class="pagination">
            <button
              :disabled="historyOffset === 0"
              @click="
                action(async () => {
                  historyOffset -= 10;
                  await loadHistory();
                })
              "
            >
              更新版本</button
            ><button
              :disabled="historyOffset + 10 >= historyTotal"
              @click="
                action(async () => {
                  historyOffset += 10;
                  await loadHistory();
                })
              "
            >
              更早版本
            </button>
          </div>
        </section>
        <section class="panel jobs-panel">
          <div class="section-heading">
            <h2>预览与成片</h2>
            <button class="quiet" @click="action(async () => refreshJobs())">
              刷新任务
            </button>
          </div>
          <p v-if="!jobs.length" class="empty">
            保存方案后，生成预览或确认导出。
          </p>
          <article
            v-for="job in jobs"
            :key="job.id"
            class="job-row"
            :data-testid="`job-${job.render_profile}`"
          >
            <div>
              <strong>{{
                job.render_profile === "FINAL_1080P"
                  ? "正式导出 · 1080p"
                  : "预览 · 720p"
              }}</strong
              ><small
                >R{{ job.revision }} ·
                {{ statusNames[job.status] ?? job.status }} ·
                {{ job.progress }}%</small
              ><progress
                :value="job.progress"
                max="100"
                :aria-label="`任务进度 ${job.progress}%`"
              /><small v-if="job.error_code" class="error">{{
                job.error_code
              }}</small
              ><small v-if="job.retry_of" class="muted">失败任务的重试</small>
            </div>
            <div class="button-row">
              <template v-if="job.status === 'SUCCEEDED'"
                ><button @click="playJob(job)">播放成片</button
                ><button @click="download(job)">下载</button></template
              ><button
                v-if="activeJob(job)"
                :disabled="busy || job.status === 'CANCEL_REQUESTED'"
                @click="cancel(job)"
              >
                取消任务</button
              ><button
                v-if="job.status === 'FAILED'"
                :disabled="busy"
                @click="retry(job)"
              >
                重试
              </button>
            </div>
          </article>
          <button
            v-if="jobsLimit < jobsTotal && jobsLimit < 100"
            @click="
              action(async () => {
                jobsLimit = Math.min(100, jobsLimit + 30);
                await refreshJobs();
              })
            "
          >
            加载更多任务
          </button>
        </section>
        <section v-if="mediaUrl" class="panel player-panel">
          <div class="section-heading">
            <h2>{{ mediaTitle }}</h2>
            <button class="quiet" @click="mediaUrl = ''">关闭</button>
          </div>
          <video
            :key="mediaUrl"
            :src="mediaUrl"
            controls
            preload="metadata"
            @error="error = '播放地址可能已过期，请重新点击播放获取新地址'"
          />
          <p class="muted small">播放地址短期有效，过期后再次点击播放即可。</p>
        </section>
      </section>
    </div>
  </main>
</template>
