#!/usr/bin/env bash
set -euo pipefail

runtime_password="${ZYBLW_AGENT_DB_PASSWORD:?ZYBLW_AGENT_DB_PASSWORD must be provided to the postgres container}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --variable=runtime_password="$runtime_password" \
  --variable=db="$POSTGRES_DB" <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'zyblw_runtime') THEN
    EXECUTE format('CREATE ROLE zyblw_runtime LOGIN PASSWORD %L', :'runtime_password');
  END IF;
END
$$;

GRANT CONNECT ON DATABASE :"db" TO zyblw_runtime;
GRANT USAGE ON SCHEMA public TO zyblw_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO zyblw_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO zyblw_runtime;
SQL
