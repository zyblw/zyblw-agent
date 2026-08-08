# 业务项目接入发布版 zyblw-agent

> 状态：运行手册  
> 最后核验：2026-08-08
> 事实来源：`build.sbt`、`integration-tests/maven-consumer`、公开 CI consumer job

## 推荐模式

业务项目的 CI 和生产构建固定 Maven Central 精确版本：

```scala
val zyblwAgentVersion = "0.5.0"

libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"      % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-providers" % zyblwAgentVersion
)
```

不要使用 `latest.release`、版本范围、Git branch 或 mutable SNAPSHOT 作为生产依赖。需要 PostgreSQL、ZIO HTTP、RAG、
MCP 或 OpenTelemetry 时按 [模块说明](modules.md) 追加对应 artifact。

## Maven-local 联调

框架发布前，维护者可以创建唯一的本地测试版本：

```bash
cd /path/to/zyblw-agent
sbt -batch 'set ThisBuild / version := "0.5.0-local.1"; publishM2'

cd /path/to/business-server
ZYBLW_AGENT_VERSION=0.5.0-local.1 sbt -batch test
```

业务 build 只有在显式本地开关下才添加 `Resolver.mavenLocal`。生产和普通 CI 不启用该 resolver，避免开发机
`~/.m2` 中的同名内容覆盖可信 Central 制品。任何 `0.5.0-local.*` 都不得上传 Maven Central。

## 可选 sibling checkout

维护者如果确实需要逐行调试框架，可以让业务 build 在显式环境变量指定且路径中存在 `build.sbt` 时使用
`ProjectRef(file(path), "core")` 等源码项目。默认值仍必须是 Maven 精确版本；生产、Docker 和 CI 不设置这个变量。

同一次构建不能混用同一模块的源码 `ProjectRef` 和 Maven 坐标。sibling 模式只是本地反馈优化，不是交付方式，也不能替代
Maven consumer contract。

## 双向促进机制

业务不是框架的特殊分支，而是 reference consumer：

1. 业务 server 发现可复现问题。
2. 先在 Agent 中抽象最小、业务无关的契约并加入测试或 eval。
3. `publishM2` 验证 artifact。
4. 业务 server 用本地唯一版本验证真实消费。
5. 合并后由 release tag 发布。
6. 业务 server 升级固定版本，并记录行为、数据库和 HTTP 兼容变化。

只有至少两个可信消费者需要的抽象才进入通用框架。用户、内容、问答投影、医疗安全文案和业务 Repository 留在业务
server；Run、工具协议、Provider、权限、持久化 SPI、通用 RAG/Memory 和运维契约留在 Agent。

## 升级纪律

- 业务项目固定精确版本，不使用动态范围。
- 先在分支升级并运行数据库、Agent 和端到端验收，再进入生产。
- 不用 `dependencyOverrides` 长期掩盖不兼容；版本冲突需要在框架 POM 或发布说明中解决。
- 框架 patch 不得破坏同一 minor 的二进制兼容。
- Scala API、Schema、HTTP wire contract、状态 JSON 和 migration 分别检查兼容性。
- Maven Central 制品不可覆盖；回滚是在业务 build 中恢复上一精确版本。

## 发布后的下游证明

框架 CI 的 Maven consumer 证明公开制品可被独立、最小的 sbt 项目消费；它不替代真实业务宿主的回归。每次 Agent 版本在
Central Portal 显示 **Published** 后，维护者应在私有 `zyblw-platform` 仓库的 **Actions → zyblw-server CI → Run
workflow** 中输入该精确版本。该 job 通过 Maven Central 解析依赖并运行 PostgreSQL 集成测试。

平台仓库还提供 `scripts/verify-agent-integration.sh`，统一了本地 `central`、明确 sibling `source` 和候选
`maven-local` 三种模式。源码模式用于快速反馈，Maven-local 用于发布前二进制验证，Central 下游 CI 是发布后的真实
分发验证；三者不能互相替代。
