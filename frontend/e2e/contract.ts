import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import SwaggerParser from "@apidevtools/swagger-parser";
import path from "node:path";
import type { BrowserContext } from "@playwright/test";

export async function captureContracts(context: BrowserContext) {
  const document = await SwaggerParser.dereference(
    path.resolve("../contracts/openapi.json"),
  );
  const ajv = new Ajv2020({ strict: false, allErrors: true });
  addFormats(ajv);
  const paths = document.paths as Record<
    string,
    Record<
      string,
      {
        responses: Record<
          string,
          { content?: Record<string, { schema: object }> }
        >;
      }
    >
  >;
  const errors: string[] = [],
    pending: Promise<void>[] = [];
  const validators = new Map<string, ReturnType<typeof ajv.compile>>();
  function removeIds(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(removeIds);
    if (value && typeof value === "object")
      return Object.fromEntries(
        Object.entries(value)
          .filter(([key]) => key !== "$id")
          .map(([key, v]) => [key, removeIds(v)]),
      );
    return value;
  }
  context.on("response", (response) => {
    const pathname = new URL(response.url()).pathname;
    if (!pathname.startsWith("/api/v1/")) return;
    pending.push(
      (async () => {
        const method = response.request().method().toLowerCase();
        const template = Object.keys(paths).find((p) =>
          new RegExp("^" + p.replace(/\{[^}]+\}/g, "[^/]+") + "$").test(
            pathname,
          ),
        );
        const schema = template
          ? paths[template]?.[method]?.responses[String(response.status())]
              ?.content?.["application/json"]?.schema
          : undefined;
        if (!schema) {
          if (response.status() !== 204)
            errors.push(
              `${method} ${pathname}: undocumented ${response.status()}`,
            );
          return;
        }
        const key = `${template}:${method}:${response.status()}`;
        let validate = validators.get(key);
        if (!validate) {
          validate = ajv.compile(removeIds(schema) as object);
          validators.set(key, validate);
        }
        if (!validate(await response.json()))
          errors.push(
            `${method} ${pathname}: ${ajv.errorsText(validate.errors)}`,
          );
      })().catch((e) => {
        errors.push(
          `Contract check failed: ${e instanceof Error ? e.message : "unknown"}`,
        );
      }),
    );
  });
  return async () => {
    await Promise.all(pending);
    return errors;
  };
}
