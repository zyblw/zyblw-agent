# 升级到 0.6.1：宿主管理台安全嵌入与动态治理装配

> 状态：0.6.1 patch 升级指南。

0.6.1 不新增或修改 Flyway migration，不改变稳定业务 HTTP、状态 JSON 或 Provider SPI。既有 0.6.0 宿主可以只替换
Maven 坐标并保持原装配；`AgentApplication.durable` 的行为不变。

需要把运行时配置管理真正接入执行链的宿主应改用：

```scala
AgentApplication.durableGoverned(owner, config)
```

并显式提供由同一个 `RuntimeSettingsService` 派生的 `ToolPolicySource` 与 `ModelPolicySource`。RAG 同时使用
`DefaultRetriever.governedLayer`、`RagApplication.governed` 和该服务的 `RetrievalPolicySource`。只接管理 API 而不接这些
Source 会造成配置可保存但执行不生效，生产部署不得采用这种半装配。

控制台新增两种构建模式：

- `bearer`（默认）：兼容原有独立管理台，token 仅保存在 sessionStorage；
- `host-session`：控制台部署在宿主同域子路径，通过 BFF/HttpOnly Cookie 认证，不向 JavaScript 暴露 JWT。

嵌入模式同时设置 `NEXT_PUBLIC_AGENT_BASE_URL`、`NEXT_PUBLIC_AGENT_AUTH_MODE=host-session` 和
`NEXT_PUBLIC_AGENT_BASE_PATH`。宿主仍必须用 `AgentRequestContextResolver` 验证身份与 `agent:admin:*` scope；前端模式
不是授权替代品。

升级验证至少包括：`scalafmtCheckAll`、`testFull`、真实 PostgreSQL 回归、Dashboard typecheck/lint/build/Playwright，以及
一个下游宿主测试，证明模型/工具/RAG 覆盖进入真实调用路径。
