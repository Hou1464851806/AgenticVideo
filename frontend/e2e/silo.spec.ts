import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("真实浏览器 Silo CORS、UploadPart、ETag 和 Range", async ({ page }) => {
  test.skip(!process.env.SILO_BROWSER_FIXTURE, "必须先生成真实 Silo 签名夹具");
  const fixture = JSON.parse(
    await readFile(process.env.SILO_BROWSER_FIXTURE!, "utf8"),
  );
  await page.goto("/");
  const result = await page.evaluate(async ({ putUrl, getUrl }) => {
    const put = await fetch(putUrl, {
      method: "PUT",
      headers: { "Content-Type": "application/octet-stream" },
      body: "browser-contract",
    });
    const range = await fetch(getUrl, { headers: { Range: "bytes=0-4" } });
    return {
      put: put.status,
      etag: put.headers.get("etag"),
      get: range.status,
      content: await range.text(),
    };
  }, fixture);
  expect(result.put).toBe(200);
  expect(result.etag).toBeTruthy();
  expect(result.get).toBe(206);
  expect(result.content).toBe("known");
});
