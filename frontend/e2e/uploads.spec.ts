import { test, expect, type BrowserContext, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { createHash, randomUUID } from "node:crypto";

test.setTimeout(120000);
async function session(context: BrowserContext) {
  const csrf = async () =>
    (await (await context.request.get("/api/v1/auth/csrf")).json()) as {
      header_name: string;
      token: string;
    };
  const credentials = {
    email: `m1-browser-${randomUUID()}@example.test`,
    password: "Test-only-long-password-2026",
  };
  let token = await csrf();
  expect(
    (
      await context.request.post("/api/v1/auth/register", {
        data: credentials,
        headers: { [token.header_name]: token.token },
      })
    ).status(),
  ).toBe(201);
  expect(
    (
      await context.request.post("/api/v1/auth/login", {
        data: credentials,
        headers: { [token.header_name]: token.token },
      })
    ).status(),
  ).toBe(200);
  token = await csrf();
  return {
    post: (path: string, data: unknown, key?: string) =>
      context.request.post("/api/v1" + path, {
        data,
        headers: {
          [token.header_name]: token.token,
          ...(key ? { "Idempotency-Key": key } : {}),
        },
      }),
    token,
  };
}
async function directPart(page: Page, url: string, bytes: Buffer) {
  return page.evaluate(
    async ({ url, base64 }) => {
      const data = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
      const result = await fetch(url, { method: "PUT", body: data });
      return { status: result.status, etag: result.headers.get("etag") };
    },
    { url, base64: bytes.toString("base64") },
  );
}

test("浏览器真实分片、重传、幂等完成、媒体校验、封存与取消", async ({
  page,
  context,
  browser,
}) => {
  test.skip(!process.env.M1_E2E, "需要隔离 API/PG/Silo/Worker");
  const user = await session(context);
  await page.goto("/");
  const project = await (
    await user.post("/projects", { name: "Browser multipart test" })
  ).json();
  const small = await readFile("../.runtime/media/red.mp4");
  const paddingSize = 9 * 1024 * 1024;
  const padding = Buffer.alloc(paddingSize);
  padding.writeUInt32BE(paddingSize, 0);
  padding.write("free", 4, "ascii");
  const media = Buffer.concat([small, padding]);
  const body = {
    filename: "untrusted $(shell).mp4",
    file_size: media.length,
    content_type: "video/mp4",
  };
  const key = randomUUID();
  const initialized = await user.post(
    `/projects/${project.id}/uploads/init`,
    body,
    key,
  );
  expect(initialized.status()).toBe(201);
  const upload = await initialized.json();
  expect(upload.total_parts).toBe(2);
  expect(
    (
      await (
        await user.post(`/projects/${project.id}/uploads/init`, body, key)
      ).json()
    ).id,
  ).toBe(upload.id);
  expect(
    (
      await user.post(
        `/projects/${project.id}/uploads/init`,
        { ...body, filename: "different.mp4" },
        key,
      )
    ).status(),
  ).toBe(409);
  const signed = await (
    await user.post(`/uploads/${upload.id}/parts/presign`, {
      part_numbers: [1, 2],
    })
  ).json();
  const parts = [];
  for (const part of signed.parts) {
    const bytes = media.subarray(
      (part.part_number - 1) * upload.part_size,
      part.part_number * upload.part_size,
    );
    // A failed PUT can retry just this numbered part using the same short-lived capability.
    const result = await directPart(page, part.url, bytes);
    expect(result.status).toBe(200);
    if (part.part_number === 1)
      expect((await directPart(page, part.url, bytes)).etag).toBe(result.etag);
    parts.push({ part_number: part.part_number, etag: result.etag });
  }
  const complete = await user.post(`/uploads/${upload.id}/complete`, { parts });
  expect(complete.status()).toBe(202);
  const asset = (await complete.json()).asset_id;
  expect(
    (
      await (
        await user.post(`/uploads/${upload.id}/complete`, { parts })
      ).json()
    ).asset_id,
  ).toBe(asset);
  expect(
    (
      await user.post(`/uploads/${upload.id}/complete`, {
        parts: [{ part_number: 1, etag: parts[0]?.etag }],
      })
    ).status(),
  ).toBe(409);
  await expect
    .poll(
      async () =>
        (await (await context.request.get(`/api/v1/assets/${asset}`)).json())
          .status,
      { timeout: 60000 },
    )
    .toBe("MEDIA_READY");
  const metadata = await (
    await context.request.get(`/api/v1/assets/${asset}`)
  ).json();
  expect(metadata.duration_ms).toBe(3000);
  expect(metadata.content_hash).toBe(
    createHash("sha256").update(media).digest("hex"),
  );
  expect(metadata.analysis_status).toBe("NOT_STARTED");
  expect(
    (
      await directPart(
        page,
        signed.parts[0].url,
        media.subarray(0, upload.part_size),
      )
    ).status,
  ).toBe(404);
  const address = await (
    await context.request.get(`/api/v1/assets/${asset}/content`)
  ).json();
  const range = await page.evaluate(async (url) => {
    const result = await fetch(url, { headers: { Range: "bytes=0-31" } });
    return {
      status: result.status,
      size: (await result.arrayBuffer()).byteLength,
    };
  }, address.url);
  expect(range).toEqual({ status: 206, size: 32 });
  const other = await browser.newContext({ baseURL: "http://localhost:5173" });
  await session(other);
  for (const path of [
    `/uploads/${upload.id}`,
    `/assets/${asset}`,
    `/assets/${asset}/content`,
  ])
    expect((await other.request.get("/api/v1" + path)).status()).toBe(404);
  await other.close();
  const cancelled = await (
    await user.post(`/projects/${project.id}/uploads/init`, body, randomUUID())
  ).json();
  expect(
    (await (await user.post(`/uploads/${cancelled.id}/abort`, {})).json())
      .status,
  ).toBe("ABORTED");
  expect(
    (await (await user.post(`/uploads/${cancelled.id}/abort`, {})).json())
      .status,
  ).toBe("ABORTED");
  expect(
    (
      await user.post(`/uploads/${cancelled.id}/parts/presign`, {
        part_numbers: [1],
      })
    ).status(),
  ).toBe(409);
  const removed = await context.request.delete(`/api/v1/assets/${asset}`, {
    headers: { [user.token.header_name]: user.token.token },
  });
  expect(removed.status()).toBe(202);
  await expect
    .poll(
      async () =>
        (await (await context.request.get(`/api/v1/assets/${asset}`)).json())
          .status,
      { timeout: 30000 },
    )
    .toBe("DELETED");
});
