# 安全模型

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-08-08
>
> 事实来源：对应模块源码、测试与构建定义

## 信任边界

- 用户输入、RAG 文档、网页、MCP 描述和工具输出都属于不可信数据。
- 模型输出只是提议，后端 runtime 才能执行动作。
- Provider API Key 只来自环境变量或 Secret Manager。
- Trace/Telemetry 默认不记录 Authorization、原始隐私正文和完整工具结果。

## Guardrail

提供 Input、Output、Tool、Run 四个阶段，支持 Blocking/Monitoring 和 FailClosed/FailOpen。关键授权必须由代码策略完成，不能仅靠 prompt。

## HTTP 协议边界

- 公共 Run/Command/Event DTO 位于 `zyblw-agent-zio-http` 的独立 `http.contract` package；禁止直接序列化
  `AgentState`、`AgentEvent` 或数据库 JSONB。
- 工具参数、工具结果、Provider 原文、隐藏推理、认证上下文和 lease token 不进入公共 JSON/SSE。
- v1 对 thread/input/idempotency/reason 设置字符上限，并把空白 opaque ID 解析成 typed 400，而不是 Fiber defect。
- Agent 控制面使用 ZStream 有界读取 256 KiB JSON，不会先无界调用 `Body.asString`。生产反向代理和 ZIO HTTP Server 仍必须
  配置更早的请求体上限、连接/读取超时、并发限制和租户速率限制。
- `AgentRequestContextResolver` 只能读取已经验签的身份；正文和普通自定义 header 不能授予 tenant/user/tool scope。

协议版本、OpenAPI 与兼容门禁见 [HTTP API、OpenAPI 与 Schema 演进](http-api-versioning.md)。

## 管理子面

`/api/v1/admin/**` 能读取跨租户聚合并改变部署行为，因此按独立信任级别处理：

- **默认不存在。** 宿主不装配就不挂载任何管理路由，全部 404。不要为了“先留个口子”而提前装配。
- **管理 scope 独立判定。** 管理端点绝不复用“租户/用户拥有该 Run”这条业务归属规则；两者是不同的授权问题，
  混用会让普通业务身份获得跨租户读取能力。
- **凭据仍然不落地。** 模型治理只保存 `env:VARIABLE` 引用和一个存在性标志。框架不接收、不返回、不存储 Provider API Key，
  管理面也不例外。
- **配置覆盖是有界白名单。** 只有显式登记的键可被覆盖，写入走 CAS 并留 append-only 审计，避免管理面变成任意远程配置注入点。

装配方式与能力清单见 [管理 API 与运维控制台](admin-console.md)。

## 中医业务

本框架不包含诊断和处方能力。业务应用涉及症状、疾病、方药、剂量或健康建议时必须增加：来源引用、不替代医生诊疗声明、风险分级、人工复核和审计；不得让 Agent 自动发布医疗建议。

## Workspace/Sandbox

`LocalWorkspace` 拒绝绝对路径、`.`、`..`、空段与 symlink，提供原子写入和容量配额；它仍不是代码沙箱。
`SandboxExecutor` 默认关闭，显式装配的 `OciSandboxExecutor` 使用摘要固定镜像、`--network none`、只读根文件系统、
非 root 用户、capability/提权限制和 CPU/内存/PID/时间/输出预算。命令不经过 shell，secret 不进入 argv，Fiber 取消会
终止进程。联网白名单、镜像签名、真实 rootless/cgroup 部署验证和微虚拟机仍是生产方责任，详见 [Workspace 与 OCI Sandbox](sandbox.md)。
