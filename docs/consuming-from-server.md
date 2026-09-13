# 业务项目接入 zyblw-agent 0.9

> 状态：当前运行手册
> 最后核验：2026-09-13
> 事实来源：`build.sbt`、`integration-tests/maven-consumer`、公开 CI consumer job

## 当前业务接入方式

`zyblw-platform` 只消费 sibling `zyblw-agent` 源码，并在 `.github/zyblw-agent.sha` 固定精确 commit。业务构建、
本地 Compose 与 CI 必须读取同一个 checkout；路径缺失、commit 不匹配或工作树不干净时立即失败。这样可以在 `0.9.0`
正式发布前验证最新框架，同时避免动态分支或开发机 Maven 缓存造成漂移。

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
