# 兼容性契约与版本边界

> 状态：当前公开契约
>
> 最后核验：2026-07-30
>
> 事实来源：`build.sbt`、公开源码、HTTP Schema、数据库迁移、发布工作流与 Maven consumer

`zyblw-agent` 仍处于 `0.x` Early SemVer。版本号、能力成熟度和生产证据是三件不同的事：一个 API 可以在 patch
版本内保持兼容，但它所在的 Experimental 能力仍可能缺少大规模运行证据。

## 兼容性矩阵

| 表面 | `0.2.x` patch 承诺 | 允许变化 | 消费方必须验证 |
|---|---|---|---|
| 公共 Scala API | 不删除或改变已公开签名；新增类型、方法和有默认实现的方法 | 实现修复、性能优化、兼容性新增 | 重新编译、业务契约测试 |
| Maven 坐标与 POM | 11 个公开 artifact 和 `io.github.zyblw` group 保持不变 | 兼容依赖升级、元数据修复 | 只从候选 Maven 制品编译独立消费者 |
| HTTP `/api/v1` | 已发布字段、状态语义和 Endpoint 保持兼容 | 新增可选字段或 Endpoint | OpenAPI/route contract、真实客户端回归 |
| 持久化 State/Event JSON | 已有字段与恢复语义不做不兼容重解释 | 新增有安全默认值的字段、修复拒绝非法状态 | 代表性旧快照读取与恢复 |
| Workflow checkpoint/ledger | definition/session identity、单调 step、fencing 与原子提交不弱化 | 新增低敏只读投影和兼容执行能力 | 活跃 Run 升级、暂停/恢复、重复副作用风险 |
| Flyway migration | 已发布 migration 永不修改；只追加更高版本 | 新表、索引、约束和向前修复 | 空库与代表性升级库、回滚运行手册 |
| Provider 协议 | Provider-neutral 事件和错误类别保持可消费 | capability、新字段解析、厂商修复 | 所用 Provider 的 stub contract 与 opt-in live smoke |
| Trace/Eval/Inspector | 不新增敏感正文，不把投影变成恢复事实源 | 新增低基数指标、低敏字段和诊断 | 脱敏、授权、基数和下游 dashboard |

## 公共 Scala API

`0.2.x` patch 采用兼容增强：可以增加新类型、新方法和带具体默认实现的 trait 方法，但不能删除、重命名或改变
`0.2.0` 已发布签名的含义。Experimental 不等于“patch 可以任意破坏”；需要破坏性改进时使用新的 minor，并提供迁移指南。

当前 sbt 2 生态尚未接入经过真实历史 artifact 验证的 MiMa/version-policy 组合，因此仓库不宣称拥有完整二进制兼容证明。
发布门禁使用源码重新编译、独立 Maven consumer、HTTP/JSON/数据库契约和下游回归提供实际证据。将来接入 MiMa 后，这些
门禁仍不能删除，因为它们覆盖不同兼容表面。

## HTTP 与 Schema

`/api/v1` 是独立公共协议，不直接序列化内部 `AgentState`。内部恢复 Schema、HTTP DTO 和 OpenAPI 版本分别演进：

- 修复内部状态不能顺带改变外部 JSON；
- 新字段必须有兼容读取语义；
- Inspector 只能返回授权后的低敏投影；
- Prompt、消息正文、工具参数/结果、pending outcome、lease token 和隐藏推理不得进入公共诊断协议。

HTTP 兼容细节见 [HTTP 公共协议与版本](http-api-versioning.md)。

## 数据库迁移

框架 migration 位于独立 Flyway resource path。已经随 `v0.1.0`、`v0.2.0` 或后续版本发布的 migration 只能读取，不能
修改、重排或复用版本号。数据库回滚采用向前修复，不依赖自动 down migration。

宿主如果有自己的 Flyway 历史，必须按 [宿主数据库迁移](database-migrations.md) 选择隔离或受控复制方案，不能把框架
V00x 文件无条件混入已有同版本历史。

## Workflow 升级与降级

Workflow definition 的 `WorkflowId`、`WorkflowVersion` 和 `SessionId` 在 Run 创建时冻结。恢复时不允许静默换定义。
execution ledger 与 checkpoint 是一个原子推进边界；timeline 只是只读投影。

从 checkpoint-only 引擎升级到 durable execution 前：

1. 应用对应 append-only migration；
2. 暂停新 Workflow 提交并等待在途节点进入稳定边界；
3. 使用同一个 definition/version/session 恢复；
4. 对外部副作用继续使用业务幂等键或 outbox/inbox；
5. 验证过期 lease、Prepared outcome 和 stale worker 拒绝路径。

已经由 durable execution 处理的活跃 Run 不应直接降级给忽略 ledger 的 checkpoint-only 引擎，否则可能失去 Prepared
outcome 的防重复执行保护。

## 发布与消费检查

维护者发布 patch 前必须：

1. 让标签指向已经合入 `main` 的 commit；
2. 使用 annotated tag，且标签、CHANGELOG 顶部版本和升级指南一致；
3. 运行完整框架、PostgreSQL、`publishM2` 和独立 consumer 门禁；
4. 确认 11 个 POM、binary、sources 和 Scaladoc JAR 完整；
5. 发布后从 Maven Central 重新解析精确版本并执行下游回归。

业务升级时应固定精确版本，不使用动态范围；在预发布环境验证公共 API、数据库 migration、HTTP Schema、Provider
协议和代表性恢复路径后再扩大流量。
