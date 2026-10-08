<script setup lang="ts">
import { useRouter, useRoute } from "vue-router";
import { api, resetCsrf } from "./api";
const router = useRouter(),
  route = useRoute();
async function logout() {
  await api("/auth/logout", { method: "POST" });
  resetCsrf();
  await router.push("/login");
}
</script>
<template>
  <header class="app-header">
    <RouterLink to="/" class="brand"
      ><span class="brand-mark">AV</span> AgenticVideo
      <small>剪辑工作台</small></RouterLink
    >
    <nav v-if="route.path !== '/login'">
      <RouterLink to="/">我的项目</RouterLink
      ><button class="quiet" @click="logout">退出登录</button>
    </nav>
  </header>
  <RouterView :key="route.params.id?.toString() ?? route.path" />
</template>
