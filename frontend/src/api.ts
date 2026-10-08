import type { Violation } from "./dsl";
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public traceId = "",
    public details: Violation[] = [],
  ) {
    super(message);
  }
}
let csrf: { header_name: string; token: string } | undefined;
export function resetCsrf() {
  csrf = undefined;
}
export async function api<T>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    key?: string;
    revision?: number;
    replaySafe?: boolean;
  } = {},
): Promise<T> {
  const method = options.method ?? "GET";
  if (method !== "GET" && !csrf) csrf = await api("/auth/csrf");
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET" && csrf) headers[csrf.header_name] = csrf.token;
  if (options.key) headers["Idempotency-Key"] = options.key;
  if (options.revision) headers["If-Match"] = `"revision-${options.revision}"`;
  const payload =
    options.body === undefined ? undefined : JSON.stringify(options.body);
  for (let attempt = 0; ; attempt++) {
    try {
      const response = await fetch("/api/v1" + path, {
        method,
        credentials: "same-origin",
        headers,
        body: payload,
      });
      if (response.status === 204) return undefined as T;
      const body = await response.json();
      if (!response.ok) {
        if (response.status === 401 || response.status === 403) resetCsrf();
        throw new ApiError(
          response.status,
          body.code ?? "REQUEST_FAILED",
          body.message ?? "请求失败",
          body.trace_id,
          body.details,
        );
      }
      return body;
    } catch (error) {
      const transient =
        error instanceof TypeError ||
        (error instanceof ApiError && [502, 503, 504].includes(error.status));
      if (!(options.key || options.replaySafe) || !transient || attempt >= 2)
        throw error;
      await new Promise((resolve) => setTimeout(resolve, 250 * 2 ** attempt));
    }
  }
}
