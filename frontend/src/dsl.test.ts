import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { validateDsl } from "./dsl";
import SwaggerParser from "@apidevtools/swagger-parser";

describe("共享 M1 契约", () => {
  it("接受合法夹具并拒绝所有结构和时间线非法夹具", () => {
    const directory = new URL("../../contracts/fixtures/", import.meta.url);
    expect(
      validateDsl(
        JSON.parse(readFileSync(new URL("valid.json", directory), "utf8")),
      ),
    ).toEqual([]);
    for (const file of readdirSync(directory).filter((f) =>
      f.startsWith("invalid-"),
    )) {
      const errors = validateDsl(
        JSON.parse(readFileSync(new URL(file, directory), "utf8")),
      );
      expect(errors, file).not.toHaveLength(0);
      expect(errors.every((e) => !!e.code && !!e.path)).toBe(true);
    }
  });
  it("OpenAPI 和外部 Schema 可以完整解析", async () => {
    const result = await SwaggerParser.validate(
      new URL("../../contracts/openapi.json", import.meta.url).pathname,
    );
    expect("openapi" in result && result.openapi).toBe("3.1.0");
    expect(result.paths?.["/api/v1/render-jobs"]).toBeDefined();
  });
});
