<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { api, resetCsrf, ApiError } from "./api";
const router = useRouter();
const register = ref(false),
  email = ref(""),
  password = ref(""),
  busy = ref(false),
  error = ref("");
async function submit() {
  busy.value = true;
  error.value = "";
  try {
    const credentials = { email: email.value, password: password.value };
    if (register.value)
      await api("/auth/register", { method: "POST", body: credentials });
    await api("/auth/login", { method: "POST", body: credentials });
    resetCsrf();
    await router.push("/");
  } catch (e) {
    error.value =
      e instanceof ApiError
        ? `${e.message} · ${e.code}`
        : "无法连接服务，请稍后重试";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="auth-layout">
    <section class="auth-story">
      <p class="eyebrow">FROM FOOTAGE TO STORY</p>
      <h1>把素材，<br />剪成你的故事。</h1>
      <p>
        上传视频，安排片段，预览每一次调整。<br />确认版本后，输出可下载的高清成片。
      </p>
      <div class="story-line"><span></span><span></span><span></span></div>
      <small>MP4 / MOV · 可追溯版本 · 1080p 导出</small>
    </section>
    <form class="panel auth-form" @submit.prevent="submit">
      <p class="eyebrow">你的工作空间</p>
      <h2>{{ register ? "创建账号" : "欢迎回来" }}</h2>
      <label
        >邮箱<input
          v-model="email"
          type="email"
          autocomplete="username"
          required
          maxlength="254" /></label
      ><label
        >密码<input
          v-model="password"
          type="password"
          :autocomplete="register ? 'new-password' : 'current-password'"
          required
          minlength="12"
          maxlength="128" /></label
      ><small>密码需 12–128 个字符</small>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <button class="primary" :disabled="busy">
        {{ busy ? "正在连接…" : register ? "注册并进入" : "登录" }}</button
      ><button
        type="button"
        class="quiet"
        @click="
          register = !register;
          error = '';
        "
      >
        {{ register ? "已有账号，登录" : "还没有账号？创建账号" }}
      </button>
    </form>
  </main>
</template>
