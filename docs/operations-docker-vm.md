# Docker / 自管 PostgreSQL 接入

> 状态：0.8.0 业务接入手册
> 最后核验：2026-08-23
> 事实来源：`deploy/docker/compose.business.yml`、`ProductionSupportHost`、`KnowledgeQaHost`、`AgentPostgresMigrations`

当前可靠拓扑是 **Docker 启动应用 + 你自己部署的 PostgreSQL**。这足够支撑业务智能体接入。
长时 soak、节点丢失、主备切换、PgBouncer 饱和、滚动发布、备份 RPO/RTO 和 SLO owner 已延期，等有独立上线环境再补。

## 安装

1. 准备 PostgreSQL 16+ 和一个空库。单用户账号可以同时执行 migrate 与 serve。
2. 复制 [`deploy/docker/env.example`](../deploy/docker/env.example)。容器访问宿主机库时用
   `jdbc:postgresql://host.docker.internal:5432/<db>`，并让 PostgreSQL 监听该地址。
3. 准备 OpenAI-compatible Provider 环境变量。书籍问答 live 路径还需要 `EMBEDDING_API_KEY`、
   `EMBEDDING_MODEL` 与 `EMBEDDING_DIMENSION=1024`，不会回退到哈希向量。
4. 由业务反代写入 `X-Tenant-Id`、`X-User-Id`。
5. 启动：

```bash
cd deploy/docker
./preflight.sh
docker compose -f compose.business.yml --env-file .env up --build
```

源码方式：

```bash
sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost migrate"
sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost serve"
```

## 升级

1. 先跑 `ProductionSupportHost status`（或对生产库的副本跑同一命令）。确认 Flyway 版本、pending 列表和
   进行中 Run/命令计数。
2. 停止提交新 Run，等待 `activeRuns`、`queued`、`leased` 归零。
3. 备份当前库。
4. 新镜像或新制品先跑 `migrate`。已发布 Flyway 只读。加法 migration 在仍有进行中工作时报警告，但切换
   0.8 进程前必须已经 drain。
5. 再启动或替换 `serve`。每个副本使用自己的 `ZYBLW_AGENT_WORKER_ID`。不要让 0.6.2 与 0.8 Worker 同时领
   同一批 Run。旧库不能原地升级，必须按 [升级到 0.8.0](upgrading-to-0.8.0.md) 重建。
6. 失败时停止扩流并向前修复。没有 down migration。

## 备份

继续使用你现有的 PostgreSQL 备份习惯即可。恢复后先 `migrate`（应已是最新），再 `serve`。
量化 RPO/RTO 属于已延期的宿主证据，不阻塞当前接入。
