#!/bin/sh
# 仅为本地主备演练创建复制账号，并允许 standby 用密码做流复制。
# 不得把本脚本、密码或 pg_hba 规则复用到共享/生产环境。
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'replicator') THEN
    CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'local-ha-only';
  END IF;
END
\$\$;
SQL

# docker 官方镜像默认 scram-sha-256；同时写 md5 以免本机覆盖认证方法后连不上。
if ! grep -q "host replication replicator" "$PGDATA/pg_hba.conf"; then
  {
    echo "host replication replicator all scram-sha-256"
    echo "host replication replicator all md5"
  } >> "$PGDATA/pg_hba.conf"
fi
