<script setup lang="ts">
import { ref } from "vue";
import { uploadFile } from "./uploader";
const props = defineProps<{ projectId: string }>();
const emit = defineEmits<{ uploaded: [assetId: string] }>();
const busy = ref(false),
  progress = ref(0),
  message = ref(""),
  uploadId = ref("");
let cancellation: AbortController | undefined;
async function select(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = [...(input.files ?? [])];
  if (!files.length) return;
  busy.value = true;
  message.value = "";
  cancellation = new AbortController();
  try {
    for (const file of files) {
      message.value = `正在上传 ${file.name}`;
      const result = await uploadFile(
        props.projectId,
        file,
        cancellation.signal,
        (p, id) => {
          progress.value = p;
          uploadId.value = id;
        },
      );
      if (result.asset_id) emit("uploaded", result.asset_id);
    }
    message.value = "上传完成，正在校验媒体";
  } catch (error) {
    message.value = cancellation.signal.aborted
      ? "上传已取消"
      : error instanceof Error
        ? error.message
        : "上传失败";
  } finally {
    busy.value = false;
    input.value = "";
  }
}
</script>
<template>
  <section aria-label="视频上传">
    <label
      >选择 MP4 / MOV
      <input
        data-testid="upload-input"
        type="file"
        accept="video/mp4,video/quicktime,.mp4,.mov"
        multiple
        :disabled="busy"
        @change="select"
    /></label>
    <button v-if="busy" type="button" @click="cancellation?.abort()">
      取消上传
    </button>
    <progress
      v-if="busy"
      :value="progress"
      max="100"
      :aria-label="`上传进度 ${progress}%`"
    />
    <p role="status">{{ message }}</p>
    <small v-if="uploadId">上传会话 {{ uploadId }}</small>
  </section>
</template>
