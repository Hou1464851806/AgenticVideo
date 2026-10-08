import { test, expect } from "@playwright/test";
import path from "node:path";
import fs from "node:fs";
import { captureContracts } from "./contract";

test.skip(
  process.env.M1_E2E !== "true",
  "Requires real API, PostgreSQL, Silo and fixed media Worker",
);
test("workspace upload, trim, conflict, saved version, preview and approved export", async ({
  page,
  context,
}) => {
  test.setTimeout(120000);
  const contractErrors = await captureContracts(context);
  await page.goto("/login");
  await page.getByRole("button", { name: "还没有账号？创建账号" }).click();
  await page
    .getByLabel("邮箱", { exact: true })
    .fill(`workspace-${crypto.randomUUID()}@example.test`);
  await page
    .getByLabel("密码", { exact: true })
    .fill("Browser-test-long-password-2026");
  await page.getByRole("button", { name: "注册并进入" }).click();
  await expect(
    page.getByRole("heading", { name: "我的项目", exact: true }),
  ).toBeVisible();
  await page.getByLabel("开始一个新项目").fill("浏览器剪辑验收");
  await page.getByRole("button", { name: "创建项目", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "浏览器剪辑验收" }),
  ).toBeVisible();
  await page
    .getByTestId("upload-input")
    .setInputFiles([
      path.resolve("../.runtime/media/red.mp4"),
      path.resolve("../.runtime/media/rotated.mov"),
    ]);
  await expect(
    page.getByRole("button", { name: "＋ 添加片段", exact: true }),
  ).toHaveCount(2, { timeout: 30000 });
  // MOV plays through the generated MP4 proxy; browser must actually load media metadata.
  await page
    .locator(".asset-item")
    .filter({ hasText: "rotated.mov" })
    .getByRole("button", { name: "播放", exact: true })
    .click();
  await expect
    .poll(async () =>
      page.locator("video").evaluate((v: HTMLVideoElement) => v.readyState),
    )
    .toBeGreaterThan(0);
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await page
    .locator(".asset-item")
    .filter({ hasText: "red.mp4" })
    .getByRole("button", { name: "＋ 添加片段" })
    .click();
  await page
    .locator(".asset-item")
    .filter({ hasText: "rotated.mov" })
    .getByRole("button", { name: "＋ 添加片段" })
    .click();
  await page.getByTestId("clip-0").getByLabel("开始（ms）").fill("500");
  await page.getByTestId("clip-0").getByLabel("结束（ms）").fill("1500");
  await page.getByTestId("clip-1").getByLabel("结束（ms）").fill("1000");
  await page
    .getByTestId("clip-1")
    .getByLabel("适配方式")
    .selectOption("center");
  await page.getByRole("button", { name: "上移片段 2" }).click();
  await expect(page.getByTestId("clip-0")).toContainText("rotated.mov");
  await page.getByLabel("画面比例").selectOption("true");
  await expect(
    page.getByRole("button", { name: "生成预览", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "保存方案", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "版本 R1 已保存" }),
  ).toBeVisible();
  const workspaceUrl = page.url();
  const tab = await context.newPage();
  await tab.goto(workspaceUrl);
  await expect(tab.getByTestId("clip-0").getByLabel("结束（ms）")).toHaveValue(
    "1000",
  );
  await tab.getByTestId("clip-0").getByLabel("结束（ms）").fill("900");
  await tab.getByRole("button", { name: "保存新版本", exact: true }).click();
  await expect(
    tab.getByRole("status").filter({ hasText: "版本 R2 已保存" }),
  ).toBeVisible();
  await page.getByTestId("clip-0").getByLabel("结束（ms）").fill("800");
  await page.getByRole("button", { name: "保存新版本", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "EDIT_PLAN_VERSION_CONFLICT",
  );
  await expect(page.getByTestId("clip-0").getByLabel("结束（ms）")).toHaveValue(
    "800",
  );
  await page.getByRole("button", { name: "读取最新版本并替换草稿" }).click();
  await expect(page.getByTestId("clip-0").getByLabel("结束（ms）")).toHaveValue(
    "900",
  );
  await page.getByText("DSL 导入 / 检查", { exact: true }).click();
  const json = JSON.parse(await page.getByLabel("方案 JSON").inputValue());
  await page
    .getByLabel("方案 JSON")
    .fill(JSON.stringify({ ...json, subtitles: [] }));
  await page.getByRole("button", { name: "导入为草稿", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("additional properties");
  await page.getByLabel("方案 JSON").fill(JSON.stringify(json));
  await page.getByRole("button", { name: "导入为草稿", exact: true }).click();
  await page.getByRole("button", { name: "保存新版本", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "版本 R3 已保存" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "生成预览", exact: true }).click();
  await expect(page.getByTestId("job-PREVIEW_720P")).toContainText("已完成", {
    timeout: 30000,
  });
  await page.reload();
  await expect(page.getByTestId("job-PREVIEW_720P")).toContainText("已完成");
  await expect(page.getByTestId("clip-0").getByLabel("结束（ms）")).toHaveValue(
    "900",
  );
  await page
    .getByTestId("job-PREVIEW_720P")
    .getByRole("button", { name: "播放成片" })
    .click();
  await expect
    .poll(async () =>
      page.locator("video").evaluate((v: HTMLVideoElement) => v.videoHeight),
    )
    .toBe(1280);
  await page.getByRole("button", { name: "确认并导出", exact: true }).click();
  await expect(page.getByRole("region", { name: "导出确认" })).toContainText(
    "1080 × 1920",
  );
  await page.getByRole("button", { name: "确认此版本并开始导出" }).click();
  await expect(page.getByTestId("job-FINAL_1080P")).toContainText("已完成", {
    timeout: 30000,
  });
  await page
    .getByTestId("job-FINAL_1080P")
    .getByRole("button", { name: "播放成片" })
    .click();
  await expect
    .poll(async () =>
      page.locator("video").evaluate((v: HTMLVideoElement) => v.videoHeight),
    )
    .toBe(1920);
  await page.locator("video").evaluate(async (v: HTMLVideoElement) => {
    await v.play();
  });
  await expect
    .poll(async () =>
      page.locator("video").evaluate((v: HTMLVideoElement) => v.currentTime),
    )
    .toBeGreaterThan(0.1);
  const downloaded = page.waitForEvent("download");
  await page
    .getByTestId("job-FINAL_1080P")
    .getByRole("button", { name: "下载", exact: true })
    .click();
  const file = await downloaded;
  await file.saveAs(path.resolve("../.runtime/browser-export.mp4"));
  expect(
    fs.statSync(path.resolve("../.runtime/browser-export.mp4")).size,
  ).toBeGreaterThan(1000);
  await page.screenshot({
    path: path.resolve("../.runtime/workspace.png"),
    fullPage: true,
  });
  await tab.close();
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  await page.goto(workspaceUrl);
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  expect(await contractErrors()).toEqual([]);
});
