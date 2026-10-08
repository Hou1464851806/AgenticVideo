import type { Dsl } from "./dsl";
export interface Identity {
  id: string;
  email: string;
}
export interface Project {
  id: string;
  name: string;
}
export interface Page<T> {
  items: T[];
  total: number;
  offset: number;
  limit: number;
}
export interface Asset {
  id: string;
  filename: string;
  status: string;
  version: 1;
  duration_ms: number | null;
  width: number | null;
  height: number | null;
  error_code: string | null;
  analysis_status: string;
}
export interface Revision {
  plan_id: string;
  project_id: string;
  name: string;
  revision: number;
  plan_hash: string;
  dsl: Dsl;
}
export interface Job {
  id: string;
  plan_id: string;
  revision: number;
  render_profile: string;
  status: string;
  progress: number;
  error_code: string | null;
  retry_of: string | null;
}
export const activeJob = (job: Job) =>
  ["QUEUED", "RUNNING", "CANCEL_REQUESTED"].includes(job.status);
