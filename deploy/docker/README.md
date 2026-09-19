# Docker + 自管 PostgreSQL

> 状态：0.9.0 当前可执行参考
> 最后核验：2026-09-18
> 事实来源：本目录 Compose、`preflight.sh`、`ProductionSupportHost` 与启动契约测试

这是 `zyblw-agent` 的最小生产参考宿主：Docker 运行 Agent，JDBC 直连业务方已经运维的 PostgreSQL。它提供可恢复 Run、Worker、HTTP/SSE、可信身份头和类型化工具的完整示例，但不替业务方承诺数据库高可用、TLS 终止、限流、Secret Manager、备份或 SLO。

## 1. 选择 Compose

| 文件 | 用途 | 数据库 | 镜像 |
| --- | --- | --- | --- |
| `compose.business.yml` | 默认业务接入和本机构建 | 外部 PostgreSQL | 默认从当前仓库构建，也可设 `ZYBLW_AGENT_IMAGE` |
| `compose.external-db.yml` | 已有不可变发布镜像 | 外部 PostgreSQL | 必须设置 `ZYBLW_AGENT_IMAGE`，迁移/运行账号分离 |
| `compose.staging.yml` | 单机隔离演练 | 内置 PostgreSQL 18.6 | 从当前仓库构建；不是生产 HA 数据库 |

业务首次接入从 `compose.business.yml` 开始；发布系统已经能提供精确镜像时再换 `compose.external-db.yml`。不要把 staging 数据卷当作生产库。

## 2. 前置条件

- Docker Engine 与 Compose v2；
- 外部路径以 PostgreSQL 18 为发布验证基线，并需要一个空数据库；
- 一个运行账号，或分开的 migration DDL 账号与 runtime DML 账号；
- 至少一个真实聊天端点；
- 一个会验签用户身份、执行租户限流并覆盖身份头的可信反向代理。

容器连接同机 PostgreSQL 时使用 `host.docker.internal`。Linux Compose 已配置 `host-gateway`，但 PostgreSQL 仍需监听对应地址，`pg_hba.conf` 只允许必要网段。跨机连接应在 JDBC URL 启用与证书策略相符的 SSL，并由网络 ACL 限制来源。

## 3. 创建配置

```bash
cd deploy/docker
cp env.example .env
chmod 600 .env
```

### 3.1 运行与身份

| 参数 | 默认/要求 | 含义 |
| --- | --- | --- |
| `ZYBLW_AGENT_RUNTIME_MODE` | 生产必须 `live` | `contract` 只用于脚本化测试，不能部署。 |
| `ZYBLW_AGENT_AUTH_MODE` | 生产必须 `trusted-headers` | 由可信反代提供身份；匿名模式只属于 contract。 |
| `ZYBLW_AGENT_BIND_ADDRESS` | `127.0.0.1` | 宿主监听地址。不要用 `0.0.0.0` 暴露可信头入口；跨机反代只能绑定受控私网 IP。 |
| `ZYBLW_AGENT_HTTP_PORT` | `8080` | 宿主端口。容器内固定 8080。 |
| `ZYBLW_AGENT_WORKER_ID` | 单副本示例值 | 每个运行副本必须唯一，进入 lease/fencing 诊断。 |
| `ZYBLW_AGENT_IMAGE` | business 可选，external 必填 | 应使用不可变版本或 digest，不使用 `latest`。 |

反向代理必须删除客户端传入的 `X-Tenant-Id`、`X-User-Id`、`X-Scopes`，再根据已经验签的 session/JWT/mTLS 写入可信值。直接转发客户端原始头会形成越权。

### 3.2 数据库

| 参数 | 要求 | 含义 |
| --- | --- | --- |
| `ZYBLW_AGENT_JDBC_URL` | 必填 | `jdbc:postgresql://host:5432/database`；不要内嵌用户名或密码。 |
| `ZYBLW_AGENT_DB_USER` | 必填 | 常驻 `serve` 的运行账号。 |
| `ZYBLW_AGENT_DB_PASSWORD_ENV` | 默认 `ZYBLW_AGENT_DB_PASSWORD` | 告诉应用从哪个环境变量读取密码；对象本身不保存密码。 |
| `ZYBLW_AGENT_DB_PASSWORD` | 必填 Secret | 运行账号密码。 |
| `ZYBLW_AGENT_MIGRATION_USER` / `PASSWORD` | business 可选，external 必填 | 只由一次性 `migrate` 使用。未分账号时 business 回退到运行账号。 |
| `ZYBLW_AGENT_PSQL_URL` | 可选 | `preflight.sh` 的连接探针地址；不含密码，密码仍从 Secret 读取。 |

0.9.0 只支持空库执行当前 V001。旧 schema、旧 state 或旧向量维度不在原地升级范围；不要用 Flyway repair、baseline 或手工改 history 绕过探针。

### 3.3 模型：单端点

最小模式使用一个 OpenAI-compatible Chat Completions 端点：

```dotenv
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_KEY=<secret>
OPENAI_MODEL=<真实部署模型ID>
```

`OPENAI_BASE_URL` 可以是经验证的中转站，但这只表示 wire 协议兼容。模型 ID、工具、流式、usage、推理字段和错误语义必须用该实际端点执行 smoke。

### 3.4 模型：多端点/中转站

非空 `ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON` 优先于 `OPENAI_*`，非法时应用启动失败，不静默回退。JSON 只存 key 的变量名：

```dotenv
ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON={"defaultProvider":"relay","endpoints":[{"providerId":"relay","baseUrl":"https://gateway.example/v1","apiKeyEnv":"RELAY_API_KEY","defaultModel":"deployment-model-id","protocol":"relay","compatibilityProfile":"openai","models":[{"name":"deployment-model-id","capabilities":{"toolCalls":true,"streaming":true,"usageReporting":true},"priceInputPerMillion":"0.10","priceOutputPerMillion":"0.40"}]}]}
RELAY_API_KEY=<secret>
```

约束：

- `providerId` 是稳定的小写逻辑身份，同一 URL/Key 上的不同 wire 方言也要拆成不同 id；
- 远端 `baseUrl` 必须是 HTTPS，不能含 user-info、query 或 fragment；
- `protocol` 只允许 `openai-compatible` / `relay`；
- `compatibilityProfile` 支持 `openai`、`deepseek`、`glm`、`qwen`、`kimi`、`generic`；
- `models` 非空时必须包含 `defaultModel`；生产成本门禁需要逐模型价格；
- 能力只声明实际 smoke 通过的部分，未知能力保持关闭；
- `apiKeyEnv` 对应变量必须进入 `support` 容器。示例 Compose 已传
  `RELAY_API_KEY`、`DEEPSEEK_API_KEY`、`GLM_API_KEY`、`QWEN_API_KEY`、`MOONSHOT_API_KEY`、
  `OPENAI_API_KEY`、`ANTHROPIC_API_KEY`、`GEMINI_API_KEY`；使用自定义变量名时必须显式编辑 Compose 或由部署平台注入
  Secret，随后同步扩展 `preflight.sh` 的允许列表。

完整 Schema 与适配器能力见 [`docs/providers.md`](../../docs/providers.md)，真实验证命令见
[`docs/provider-live-smoke.md`](../../docs/provider-live-smoke.md)。

### 3.5 Embedding

`ProductionSupportHost` 客服示例不使用 RAG，因此不读取 `EMBEDDING_*`。`KnowledgeQaHost` 或业务 RAG 装配才需要：

```dotenv
EMBEDDING_BASE_URL=https://provider.example/v1
EMBEDDING_API_KEY=<secret>
EMBEDDING_MODEL=<model-id>
EMBEDDING_DIMENSION=1024
```

聊天兼容不等于有 `/embeddings`。模型、维度、索引 Space/Profile 必须一致；0.9 knowledge schema 固定为 1024 维，改变它需要新索引与重新摄取。

### 3.6 可观测性

`OTEL_EXPORTER_OTLP_ENDPOINT` 可选。留空时使用 noop observer，不影响 Run 正确性。启用时只把 Collector 的受控内网 HTTPS/HTTP 地址写入配置；Prompt、工具正文和 Secret 仍不得进入日志或 Trace。

## 4. 配置体检与启动

默认业务路径：

```bash
./preflight.sh .env
docker compose -f compose.business.yml --env-file .env config --quiet
docker compose -f compose.business.yml --env-file .env up -d --build --wait
docker compose -f compose.business.yml --env-file .env ps
```

`preflight.sh` 会 `source` 参数文件，因此只可传运维人员自己维护的可信文件。它校验 live/trusted-headers、JDBC、占位符、模型模式、所有 JSON `apiKeyEnv` 和监听地址；完整 JSON、URL、Secret 和数据库结构仍由应用启动校验。

仓库内的回归契约可独立执行：

```bash
./test-preflight.sh
docker compose --env-file env.example -f compose.business.yml config --quiet
ZYBLW_AGENT_IMAGE=ghcr.io/example/zyblw-agent:test \
  ZYBLW_AGENT_MIGRATION_USER=zyblw_migrate \
  ZYBLW_AGENT_MIGRATION_PASSWORD=test-only-migration-password \
  docker compose --env-file env.example -f compose.external-db.yml config --quiet
ZYBLW_AGENT_MIGRATION_PASSWORD=test-only-migration-password \
  docker compose --env-file env.example -f compose.staging.yml config --quiet
```

这组检查覆盖单端点、多端点、缺少 Secret、未注入的自定义 `apiKeyEnv`、公网监听拒绝和三个 Compose 变体；它不访问真实模型或数据库。

`migrate` 成功并退出后，Compose 才启动 `support`。检查：

```bash
curl -fsS http://127.0.0.1:8080/health/live
curl -fsS http://127.0.0.1:8080/health/ready
docker compose -f compose.business.yml --env-file .env logs --tail=200 support
```

查看升级状态（不会迁移）：

```bash
docker compose -f compose.business.yml --env-file .env \
  run --rm --no-deps support status
```

使用已发布镜像：

```bash
./preflight.sh .env
docker compose -f compose.external-db.yml --env-file .env pull
docker compose -f compose.external-db.yml --env-file .env up -d --wait
```

隔离 staging 演练：

```bash
docker compose -f compose.staging.yml --env-file .env up -d --build --wait
docker compose -f compose.staging.yml --env-file .env ps
```

## 5. 调用契约

创建 Run 返回 `202 Accepted`，调用方需要：

- `Idempotency-Key`：客户端生成且重试复用；
- `X-Tenant-Id`、`X-User-Id`：只能由可信反代写入；
- `X-Scopes`：按业务授权映射，不能接受模型或正文声明；
- 返回的 `runId`：用于状态查询与 SSE；断线续传保留 `Last-Event-ID`。

退款等高风险工具进入 `WaitingForApproval`，只有可信身份可调用批准/拒绝端点。不要把 `support_*` 示例表当作通用业务模型；业务应实现自己的类型化端口、幂等键、事务与 outbox。

## 6. 升级、停止与回滚

升级前：

1. 运行 `status`，停止接收新 Run；
2. 等 `activeRuns`、`queuedCommands`、`leasedCommands` 归零；
3. 备份数据库并验证恢复可读；
4. 用新镜像单独执行 `migrate`；
5. 再替换 `serve`，避免新旧 Worker 同时争领；
6. 验证 readiness、真实 Provider smoke 和业务金丝雀。

安全停止不会删除外部数据库：

```bash
docker compose -f compose.business.yml --env-file .env down
```

不要给 staging 的 `down` 随意增加 `-v`；`-v` 会删除本目录的演练数据库卷。回滚应用只使用上一条已验证的不可变镜像；框架没有 down migration，不兼容数据库变更必须按该版本专门恢复方案处理。

## 7. 生产签署边界

这套 Compose 的仓库测试能够证明配置失败关闭、迁移顺序、健康检查和安全默认值，但不能替真实环境证明：

- 每个实际 Provider/模型/中转 profile 的 complete、stream、tool、usage、timeout；
- 身份反代无法被绕过，租户限流与 TLS 正确；
- PostgreSQL 备份恢复、RPO/RTO、主备切换和连接池容量；
- Worker kill/restart、Provider 断流、长时 soak、费用告警与值班 owner；
- 固定业务数据集的质量、安全、延迟和成本。

这些证据完成前，正确标记是“可进入预生产/受限生产验收”，不是通用 production GA。完整判定见
[`docs/production-readiness.md`](../../docs/production-readiness.md) 与
[`docs/evidence/wave-0-checklist.md`](../../docs/evidence/wave-0-checklist.md)。
