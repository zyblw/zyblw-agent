# ADR-0014：收敛公共模块，而不是删除内部能力边界

> 状态：Accepted  
> 日期：2026-07-25  
> 决策范围：`zyblw-agent/build.sbt`、Maven 坐标、业务接入

## 背景

早期代码按概念拆出了 core、model、tools、memory、context、guardrails、runtime、app、scheduler、workflow、
multimodal 等三十多个可发布 sbt 子项目。这个结构证明了依赖方向，却把“源码职责边界”和“对外发布边界”混为一谈。

审计时发现多个 artifact 只有一个源码文件，而且 zyblw-server 为获得一个完整 Agent 应用需要显式选择六个直接依赖，并通过
传递依赖再拼出十余个基础 artifact。对尚未正式公开发布的 0.1 框架，这会带来以下成本：

- 新用户不知道从哪个坐标开始；
- 每个薄 artifact 都增加 POM、源码/文档 JAR、签名、Central Portal 和兼容性矩阵；
- 高频共同变更的基础抽象被迫独立版本化；
- 本地 sbt 需要追踪大量内部 project，增加加载和增量构建 I/O；
- 文档大量篇幅用于解释模块选择，而不是解释如何构建可靠 Agent。

## 决策

采用“两级边界”：

1. Scala package 表达内部职责和依赖方向；
2. Maven artifact 只表达使用方真正需要独立选择的交付边界。

稳定内核合并到 `zyblw-agent-core`。Provider 协议统一到 `zyblw-agent-providers`。HTTP contract、routes 与 host
统一到 `zyblw-agent-zio-http`。MCP 与受控 workspace 统一到 `zyblw-agent-mcp`。

PostgreSQL、Tika 文档解析、外部 rerank、OTLP SDK、RAG、评测和 testkit 继续独立，因为它们分别具有重依赖、数据边界、
资源生命周期或不同使用阶段。

结果是：

- 公共 artifact：11 个；
- 业务最小接入：`core + providers`；
- zyblw-server 当前生产组合：`core + providers + zio-http + postgres + opentelemetry + evals`；
- 仓库内不发布项目：examples、benchmarks、eval CLI、root。

## 依赖方向

```text
                         ┌──────────────┐
                         │  providers   │
                         └──────┬───────┘
                                │
┌──────────────┐          ┌────────────┐          ┌──────────────────┐
│ zio-http     │─────────▶│    core    │◀─────────│ opentelemetry    │
└──────────────┘          └──────▲─────┘          └──────────────────┘
                                │
                         ┌──────┴───────┐
                         │     rag      │
                         └──────▲───────┘
                                │
                  ┌─────────────┼─────────────┐
                  │             │             │
             ┌────┴─────┐ ┌─────┴─────┐ ┌─────┴────┐
             │ postgres │ │  loaders  │ │  rerank  │
             └──────────┘ └───────────┘ └──────────┘
```

箭头指向被依赖的 artifact。图只表达主要选择关系，省略 evals/testkit/MCP 的辅助边；具体服务仍由构造器和 `ZLayer`
组合。模型只提出工具调用，runtime 负责校验、授权、执行、追踪和终止。

## 被舍弃的方案

### 保留三十多个 artifact，再提供一个聚合 starter

starter 能隐藏用户选择，却不能消除 Central 发布、签名、漏洞扫描和兼容矩阵成本；其 POM 仍需引用全部内部 artifact。

### 合并为一个全量 JAR

这会把 Tika、JDBC/Flyway、OTLP SDK、ZIO HTTP 和所有可选协议强制带给每个用户，增加依赖冲突、镜像体积和攻击面。

### 按每个 Provider 发布一个 artifact

当前三个实现都基于同一 ZIO HTTP transport，没有厂商 SDK 或许可证差异。拆分只增加坐标；若未来某 Provider 引入重型 SDK
或独立版本压力，可用新的 ADR 再拆出。

## 代价与不足

- `zyblw-agent-core` 的 JAR 比旧 core 大，但仍不含数据库、Server、Tika、OTLP SDK 或厂商 SDK；
- 内核内部 package 的二进制兼容要共同维护，不能再单独发布 patch；
- 为避免无价值的大规模 package 改名，源码已按 11 个真实 artifact 目录归并；内部职责继续由 package 表达；
- 0.1 尚缺公开用户反馈，模块边界必须由 zyblw-server 和至少一个独立示例应用持续验证。

## 验证

1. `sbt clean testFull publishM2`；
2. 检查发布 POM 只有 11 个公共 artifact；
3. zyblw-server 分别以源码 `ProjectRef` 和 Maven 本地制品编译测试；
4. quickstart 只用 `core + providers` 完成一次带 tool 的对话；
5. PostgreSQL、HTTP、OTLP 和 RAG 各有独立契约测试；
6. 发布前对公共 API 运行二进制兼容检查。

## 成功指标

- 新用户在十分钟内完成第一个 Agent；
- 普通用户直接依赖不超过 2 个，生产扩展按需增加；
- 无可发布 artifact 仅因“一个概念/一个 trait”存在；
- 新 artifact 必须有真实依赖隔离证据和 ADR；
- zyblw-server 在源码模式与发布物模式行为一致。
