# Docker + 自管 PostgreSQL

当前支持的业务接入方式：用 Docker 启动 Agent，JDBC 直连你已经部署的 PostgreSQL。
不要求 PgBouncer、Kubernetes 或主备。那 7 项宿主证据已延期，不影响把框架引进业务仓库。

## 1. 准备数据库

PostgreSQL 16+，一个空库即可。当前单用户部署可以把 migrate 和 serve 配成同一个账号。

容器要连到宿主机上的库时：

- JDBC 使用 `jdbc:postgresql://host.docker.internal:5432/<db>`
- PostgreSQL `listen_addresses` 不能只绑 `127.0.0.1`
- `pg_hba.conf` 允许 Docker 网桥网段或宿主机网关

## 2. 启动

```bash
cd deploy/docker
cp env.example .env
# 填入 JDBC、数据库账号、Provider 地址和密钥
./preflight.sh
docker compose -f compose.business.yml --env-file .env up --build
```

`migrate` 成功后才会启动 `serve`。检查：

```bash
curl -fsS http://127.0.0.1:8080/health/live
curl -fsS http://127.0.0.1:8080/health/ready
```

创建 Run 仍返回 202，并要求 `Idempotency-Key`、`X-Tenant-Id`、`X-User-Id`。

## 3. 业务仓库怎么用

两种等价路径，选一种即可：

1. **独立 Agent 容器**：本 Compose 只跑参考宿主，业务服务通过 HTTP `/api/v1` 调用。
2. **嵌入业务镜像**：业务 Dockerfile 引入 `zyblw-agent-core` / `postgres` / `zio-http` / `providers`，自己装配
   `AgentApplication.durable`，同样直连这台 PostgreSQL。参考装配见
   `ProductionSupportLayers`。

本地联调可先发布到 Maven Local：

```bash
sbt -batch 'set ThisBuild / version := "0.9.0-local"; publishM2'
```

业务项目使用精确版本 `0.9.0-local`，不要写版本范围。

## 4. 其他 Compose

选哪一份：

- `compose.business.yml`：默认业务路径。本地构建，JDBC 连你自己的外部库，和生产同构。
- `compose.external-db.yml`：已经有发布镜像摘要、仍连外部库时使用。
- `compose.staging.yml`：bundled Postgres 本机演练，不是产品 Test 站，也不是生产库。知识表里的 `staging` 与此无关。
