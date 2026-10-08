CREATE TABLE upload_sessions (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id),
  filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(64) NOT NULL,
  file_size BIGINT NOT NULL CHECK (file_size > 0),
  part_size INTEGER NOT NULL,
  total_parts INTEGER NOT NULL,
  staging_key VARCHAR(512) NOT NULL UNIQUE,
  multipart_id TEXT,
  state VARCHAR(24) NOT NULL CHECK (state IN ('INITIATED','COMPLETING','COMPLETED','ABORTED','EXPIRED','FAILED')),
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  completion_hash CHAR(64),
  completion_parts JSONB,
  asset_id UUID,
  expires_at TIMESTAMPTZ NOT NULL,
  error_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(project_id,idempotency_key)
);
CREATE INDEX uploads_reservation ON upload_sessions(project_id,state);
CREATE TABLE assets (
  id UUID PRIMARY KEY,
  upload_id UUID NOT NULL UNIQUE REFERENCES upload_sessions(id),
  project_id UUID NOT NULL REFERENCES projects(id),
  filename VARCHAR(255) NOT NULL,
  file_size BIGINT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version=1),
  state VARCHAR(24) NOT NULL CHECK (state IN ('VALIDATING','MEDIA_READY','INVALID','DELETING','DELETED')),
  storage_key VARCHAR(512) NOT NULL UNIQUE,
  proxy_key VARCHAR(512),
  content_hash CHAR(64),
  duration_ms BIGINT,
  width INTEGER,
  height INTEGER,
  fps VARCHAR(64),
  rotation INTEGER,
  has_audio BOOLEAN,
  video_start DOUBLE PRECISION,
  audio_start DOUBLE PRECISION,
  analysis_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
  error_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE upload_sessions ADD CONSTRAINT upload_asset_fk FOREIGN KEY (asset_id) REFERENCES assets(id);
CREATE INDEX assets_project_state ON assets(project_id,state);
CREATE TABLE media_probe_jobs (
  id UUID PRIMARY KEY,
  asset_id UUID NOT NULL UNIQUE REFERENCES assets(id),
  state VARCHAR(32) NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  attempt_token UUID,
  lease_until TIMESTAMPTZ,
  progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  error_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);
CREATE INDEX probe_claim ON media_probe_jobs(state,created_at);
CREATE TABLE object_cleanup_tasks (
  id UUID PRIMARY KEY,
  object_key VARCHAR(512) NOT NULL,
  multipart_id TEXT,
  asset_id UUID REFERENCES assets(id),
  state VARCHAR(24) NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  error_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(object_key)
);
