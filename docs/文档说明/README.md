# 日期式输入稿（非当前契约）

> 状态：历史输入
> 最后核验：2026-09-23
> 当前契约入口：[文档地图](../README.md)

本目录保存 2026-09-14–16 的咨询与演进输入。它们解释当时为什么要改 Kernel、Context、Prompt Cache 与模型能力控制面，**不是**当前实现说明。

| 输入 | 对应的当前文档 |
| --- | --- |
| [0914 架构说明](0914架构说明.md) | [0914 审查与演进裁决](../0914-architecture-review.md)、[ADR-0028](../architecture/0028-functional-kernel-runtime-driver.md) |
| [0915 架构说明](0915架构说明.md) | [一体化演进方案](../architecture/runtime-context-evolution-plan.md) |
| [0916 核心抽象](0916核心抽象.md) / [0916 框架优化](0916框架优化.md) | [ADR-0030 强模型时代的 Control Runtime](../architecture/0030-model-capability-control-runtime.md) |
| [0915 上下文架构说明](0915上下文架构说明.md) | [ADR-0029](../architecture/0029-context-authority-prompt-lineage.md)、[Prompt Runtime](../prompt-runtime.md) |
| [0915 KV Cache 缓存架构](0915kv-cache缓存架构.md) | 同上；显式 Provider cache dialect 仍待 |

输入稿仍会出现 `AgentRuntimeLive`、Memory/RAG 走 System、Boolean `promptCache` 等过时描述。以源码和 canonical 文档为准。
