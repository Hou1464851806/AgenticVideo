import { createRouter, createWebHistory } from "vue-router";
import AuthView from "./AuthView.vue";
import ProjectsView from "./ProjectsView.vue";
import WorkspaceView from "./WorkspaceView.vue";
import { api, ApiError } from "./api";
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", component: AuthView },
    { path: "/", component: ProjectsView },
    { path: "/projects/:id", component: WorkspaceView },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  if (to.path === "/login") return true;
  try {
    await api("/auth/me");
    return true;
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return "/login";
    return true;
  }
});
