CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(512) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE projects (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES users(id),
  name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX projects_owner_id ON projects(owner_id, created_at, id);
-- Spring Session JDBC 4.1.1 PostgreSQL schema, maintained by Flyway.
CREATE TABLE spring_session (
  primary_id CHAR(36) NOT NULL PRIMARY KEY,
  session_id CHAR(36) NOT NULL UNIQUE,
  creation_time BIGINT NOT NULL,
  last_access_time BIGINT NOT NULL,
  max_inactive_interval INT NOT NULL,
  expiry_time BIGINT NOT NULL,
  principal_name VARCHAR(100)
);
CREATE INDEX spring_session_expiry ON spring_session(expiry_time);
CREATE INDEX spring_session_principal ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
  session_primary_id CHAR(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
  attribute_name VARCHAR(200) NOT NULL,
  attribute_bytes BYTEA NOT NULL,
  PRIMARY KEY (session_primary_id, attribute_name)
);
