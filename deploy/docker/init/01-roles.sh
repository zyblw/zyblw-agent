#!/usr/bin/env bash
set -euo pipefail

runtime_password="${ZYBLW_AGENT_DB_PASSWORD:?ZYBLW_AGENT_DB_PASSWORD must be provided to the postgres container}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --variable=runtime_password="$runtime_password" \
  --variable=db="$POSTGRES_DB" <<'SQL'
SELECT format('CREATE ROLE zyblw_runtime LOGIN PASSWORD %L', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'zyblw_runtime')
\gexec

GRANT CONNECT ON DATABASE :"db" TO zyblw_runtime;
CREATE SCHEMA IF NOT EXISTS zyblw_agent_core;
CREATE SCHEMA IF NOT EXISTS zyblw_agent_knowledge;
CREATE SCHEMA IF NOT EXISTS zyblw_extensions;
CREATE SCHEMA IF NOT EXISTS support_app;
COMMENT ON SCHEMA zyblw_extensions IS '数据库扩展对象：向量类型、距离操作符与文本检索索引支持；不保存业务事实';
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM pg_database_owner;
GRANT USAGE ON SCHEMA zyblw_agent_core, zyblw_agent_knowledge, zyblw_extensions, support_app TO zyblw_runtime;
ALTER ROLE zyblw_runtime IN DATABASE :"db" SET search_path = pg_catalog;
ALTER DEFAULT PRIVILEGES IN SCHEMA zyblw_agent_core, zyblw_agent_knowledge, support_app
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO zyblw_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA zyblw_agent_core, zyblw_agent_knowledge, support_app
  GRANT USAGE, SELECT ON SEQUENCES TO zyblw_runtime;
SQL
