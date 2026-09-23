# 业务项目接入 zyblw-agent 0.9

> 状态：当前运行手册
> 最后核验：2026-09-23
>
> 2026-09-23 对照：现行安装是 0.9 空库（核心与 1024 知识各一份 V001）。执行内核是 `AgentKernel` + `AgentRuntimeDriver`。Memory、RAG 与摘要走 User envelope。等待使用 `Suspension`。稳定 HTTP 是 OpenAPI `1.2.0`。本页不提升 Experimental 能力的成熟度。
>
> 事实来源：`build.sbt`、`integration-tests/maven-consumer`、公开 CI consumer job

## 当前业务接入方式

`zyblw-platform` 只消费 sibling `zyblw-agent` 源码，并在 `.github/zyblw-agent.sha` 固定精确 commit。业务构建、
本地 Compose 与 CI 使用同一种源码 ProjectRef/镜像构建路径，不提供 Maven-local、Central 或历史版本旁路。开发中允许直接消费 sibling 未提交变更；发布门禁必须以 `VERIFY_AGENT_CLEAN=1` 拒绝未提交工作树，并要求 pin 指向包含这些变更的不可变 commit。路径缺失则构建失败。

当前平台已按最新框架契约接入：

- `QaProviderConfigLoader` 从同一次装配生成 `ChatModel`、`ProviderRegistry`、默认 provider/model 身份和计价声明；
- 命名档案支持 DeepSeek、Qwen、GLM、Kimi、OpenAI、Anthropic 和 Gemini；`ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON` 支持多个 OpenAI-compatible 官方端点或中转站；
- 平台消费必填 `AgentState.threadId`、冻结 `definition` / `composition`、`suspension`、citations 与 retrieval evidence，不保留旧状态形状的兼容分支；
- 命名档案的 additional provider 仅是显式路由目标，不得宣称为自动 failover。

公开制品发布后，独立消费者可固定精确坐标：

```scala
val zyblwAgentVersion = "0.9.0"

libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"      % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-providers" % zyblwAgentVersion
)
```

不要使用 `latest.release`、版本范围、Git branch 或 mutable SNAPSHOT 作为生产依赖。需要 PostgreSQL、ZIO HTTP、RAG、
MCP 或 OpenTelemetry 时按[模块说明](modules.md)追加对应 artifact。数据库必须按
[0.9.0 全新安装](fresh-install-0.9.0.md)从空库创建。

## 发布制品验证

Maven-local 仅验证将要发布的二进制和 POM，不是 `zyblw-platform` 的运行入口：

```bash
cd /path/to/zyblw-agent
sbt -batch 'set ThisBuild / version := "0.9.0-local"; publishM2'

cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.9.0-local sbt -batch compile
```

`0.9.0-local` 不得上传 Maven Central。正式发布由 `v0.9.0` annotated tag 触发，并在 Central 显示 Published 后用精确
`0.9.0` 再跑一次独立 consumer。

## 双向促进机制

业务是框架的 reference consumer：

1. 业务 server 发现可复现问题。
2. 在 Agent 中抽象业务无关的最小契约，加入测试或 eval。
3. 平台更新 `.github/zyblw-agent.sha`，用同一源码 commit 跑空库全栈回归。
4. Agent 发布前用 Maven consumer 验证二进制边界。
5. 发布 tag 后再验证 Central 精确制品。

用户、内容、问答投影、医疗安全文案和业务 Repository 留在业务 server；Run、工具协议、Provider、权限、持久化 SPI、
通用 RAG/Memory 和运维契约留在 Agent。只有能由通用失败样本和评测证明价值的能力才进入框架主线。
