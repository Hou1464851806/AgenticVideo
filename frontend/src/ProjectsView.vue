<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { api, ApiError } from "./api";
import type { Project, Page } from "./models";
const router = useRouter();
const projects = ref<Project[]>([]),
  name = ref(""),
  total = ref(0),
  offset = ref(0),
  error = ref(""),
  busy = ref(false);
async function load() {
  try {
    const result = await api<Page<Project>>(
      `/projects?offset=${offset.value}&limit=30`,
    );
    projects.value = result.items;
    total.value = result.total;
    error.value = "";
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "无法读取项目";
  }
}
async function create() {
  busy.value = true;
  try {
    const project = await api<Project>("/projects", {
      method: "POST",
      body: { name: name.value },
    });
    await router.push(`/projects/${project.id}`);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "创建失败";
  } finally {
    busy.value = false;
  }
}
async function page(delta: number) {
  offset.value += delta;
  await load();
}
onMounted(load);
</script>
<template>
  <main class="projects-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">YOUR WORKSPACE</p>
        <h1>我的项目</h1>
        <p class="muted">每个故事都有自己的素材、版本与成片。</p>
      </div>
      <span class="pill">{{ total }} 个项目</span>
    </div>
    <form class="panel create-project" @submit.prevent="create">
      <label for="project-name">开始一个新项目</label
      ><input
        id="project-name"
        v-model="name"
        placeholder="例如：周末旅行"
        required
        maxlength="200"
      /><button class="primary" :disabled="busy">创建项目</button>
    </form>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button class="quiet" @click="load">重新加载</button>
    </p>
    <section class="project-grid" aria-label="项目列表">
      <RouterLink
        v-for="project in projects"
        :key="project.id"
        :to="`/projects/${project.id}`"
        class="panel project-card"
        ><span class="project-icon">▶</span>
        <h2>{{ project.name }}</h2>
        <p>打开剪辑工作台 <span>↗</span></p></RouterLink
      >
    </section>
    <p v-if="!projects.length && !error" class="empty">
      创建项目后，从上传第一段视频开始。
    </p>
    <div class="pagination">
      <button :disabled="offset === 0" @click="page(-30)">上一页</button
      ><button :disabled="offset + 30 >= total" @click="page(30)">
        下一页
      </button>
    </div>
  </main>
</template>
