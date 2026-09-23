# Docker / 自管 PostgreSQL 接入

> 状态：0.9.0 业务接入手册
> 最后核验：2026-09-23
>
> 2026-09-23 对照：现行安装是 0.9 空库（核心与 1024 知识各一份 V001）。执行内核是 `AgentKernel` + `AgentRuntimeDriver`。Memory、RAG 与摘要走 User envelope。等待使用 `Suspension`。稳定 HTTP 是 OpenAPI `1.2.0`。本页不提升 Experimental 能力的成熟度。
>
> 事实来源：`deploy/docker/compose.business.yml`、`ProductionSupportHost`、`KnowledgeQaHost`、`AgentPostgresMigrations`

当前可靠拓扑是 **Docker 启动应用 + 你自己部署的 PostgreSQL**。这足够支撑业务智能体接入。
长时 soak、节点丢失、主备切换、PgBouncer 饱和、滚动发布、备份 RPO/RTO 和 SLO owner 已延期，在约定窗口的生产流量上测量后再补。
环境变量逐项说明、三种 Compose 的选择、完整启动/检查/停止命令以
[Docker 可执行配置](../deploy/docker/README.md)为唯一入口；本文只维护安装、升级和恢复策略。

## 安装

1. 准备 PostgreSQL 18 和一个空库。单用户账号可以同时执行 migrate 与 serve。
2. 复制 [`deploy/docker/env.example`](../deploy/docker/env.example)。容器访问宿主机库时用
   `jdbc:postgresql://host.docker.internal:5432/<db>`，并让 PostgreSQL 监听该地址。
3. 准备单一 `OPENAI_*`，或者使用非空 `ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON` + 对应 `apiKeyEnv` Secret 装配多个官方端点/
   中转站。非空 JSON 非法时启动失败，不回落到单一 OpenAI。书籍问答 live 路径还需要 `EMBEDDING_API_KEY`、
   `EMBEDDING_MODEL`、`EMBEDDING_DIMENSION=1024` 与 `EMBEDDING_TOKENIZER`，不会回退到哈希向量。
4. 由业务反代写入 `X-Tenant-Id`、`X-User-Id`。
5. 启动：

```bash
cd deploy/docker
./preflight.sh .env
docker compose -f compose.business.yml --env-file .env up -d --build --wait
```

示例默认只绑定 `127.0.0.1`。`trusted-headers` 入口不能直接暴露公网；跨机反代必须显式设置受控私网
`ZYBLW_AGENT_BIND_ADDRESS`，并让网络 ACL 拒绝其他来源。

`preflight.sh .env` 只接受运维人员维护的受信环境文件（内部使用 shell `source`），不能用于下载内容或用户上传内容。配置
示例 Compose 会显式注入 `RELAY_API_KEY`、DeepSeek、GLM、Qwen、Moonshot/Kimi、OpenAI、Anthropic、Gemini 的标准
Secret 变量；使用自定义变量名时，必须同步加入 `support.environment` 与 `preflight.sh` 允许列表，避免 Secret 只存在于
宿主机却未进入容器。

源码方式：

```bash
sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost migrate"
sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost serve"
```

## 升级

1. 先跑 `ProductionSupportHost status`（或对迁移前的数据库副本跑同一命令，不是产品 Test 环境）。确认 Flyway 版本、pending 列表和
   进行中 Run/命令计数。
2. 停止提交新 Run，等待 `activeRuns`、`queued`、`leased` 归零。
3. 备份当前库。
4. 新镜像或新制品先跑 `migrate`。已发布 Flyway 只读。加法 migration 在仍有进行中工作时报警告，但切换
   新进程前必须已经 drain。
5. 再启动或替换 `serve`。每个副本使用自己的 `ZYBLW_AGENT_WORKER_ID`。不要让新旧 Worker 同时领取同一批 Run。
   旧库不能原地升级，必须按 [升级到 0.9.0](fresh-install-0.9.0.md) 重建。
6. 失败时停止扩流并向前修复。没有 down migration。

## 备份

继续使用你现有的 PostgreSQL 备份习惯即可。恢复后先 `migrate`（应已是最新），再 `serve`。
量化 RPO/RTO 属于已延期的宿主证据，不阻塞当前接入。
