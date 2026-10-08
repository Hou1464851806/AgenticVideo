CREATE TABLE edit_plans (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id),
  name VARCHAR(200) NOT NULL,
  current_revision INTEGER NOT NULL CHECK (current_revision>=1),
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(project_id,idempotency_key)
);
CREATE TABLE edit_plan_revisions (
  plan_id UUID NOT NULL REFERENCES edit_plans(id),
  revision INTEGER NOT NULL CHECK (revision>=1),
  plan_data JSONB NOT NULL,
  plan_hash CHAR(64) NOT NULL,
  canonical_version VARCHAR(16) NOT NULL DEFAULT 'v1',
  created_by UUID NOT NULL REFERENCES users(id),
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(plan_id,revision),
  UNIQUE(plan_id,idempotency_key)
);
CREATE FUNCTION immutable_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Edit plan revisions are immutable';
END;
$$;
CREATE TRIGGER revisions_are_immutable BEFORE UPDATE OR DELETE ON edit_plan_revisions FOR EACH ROW EXECUTE FUNCTION immutable_revision();
CREATE TABLE revision_asset_refs (
  plan_id UUID NOT NULL,
  revision INTEGER NOT NULL,
  asset_id UUID NOT NULL REFERENCES assets(id),
  asset_version INTEGER NOT NULL CHECK (asset_version=1),
  PRIMARY KEY(plan_id,revision,asset_id),
  FOREIGN KEY(plan_id,revision) REFERENCES edit_plan_revisions(plan_id,revision)
);
CREATE INDEX revision_refs_asset ON revision_asset_refs(asset_id);
CREATE TABLE plan_approvals (
  plan_id UUID NOT NULL,
  revision INTEGER NOT NULL,
  plan_hash CHAR(64) NOT NULL,
  asset_versions JSONB NOT NULL,
  final_profile VARCHAR(32) NOT NULL CHECK (final_profile='FINAL_1080P'),
  approved_by UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(plan_id,revision,final_profile),
  FOREIGN KEY(plan_id,revision) REFERENCES edit_plan_revisions(plan_id,revision)
);
CREATE TABLE render_jobs (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id),
  owner_id UUID NOT NULL REFERENCES users(id),
  plan_id UUID NOT NULL,
  revision INTEGER NOT NULL,
  plan_hash CHAR(64) NOT NULL,
  render_profile VARCHAR(32) NOT NULL CHECK (render_profile IN ('PREVIEW_720P','FINAL_1080P')),
  state VARCHAR(32) NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','CANCEL_REQUESTED','CANCELLED','FAILED','SUCCEEDED')),
  progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  attempt_token UUID,
  lease_until TIMESTAMPTZ,
  retry_of UUID REFERENCES render_jobs(id),
  output_key VARCHAR(512),
  output_hash CHAR(64),
  output_bytes BIGINT,
  ffmpeg_version TEXT,
  compiler_hash CHAR(64),
  error_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  FOREIGN KEY(plan_id,revision) REFERENCES edit_plan_revisions(plan_id,revision),
  UNIQUE(owner_id,idempotency_key)
);
CREATE INDEX render_claim ON render_jobs(state,created_at,id);
CREATE INDEX render_project ON render_jobs(project_id,created_at,id);
