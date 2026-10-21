---
name: scala3-zio2-stack
description: Scala 3/ZIO 2 engineering guidance for implementation, refactoring, debugging, testing, and code review in this repository, including backend services, agent runtime, JDBC/pgvector, and resource lifecycle. Use for Scala changes or explicit Scala engineering questions; not for unrelated frontend, content, or deployment-only work.
---

# Scala 3 + ZIO 2 现代 AI 工程实践指南

将强类型、纯函数式效应与确定性验证流水线应用于本次任务。融合现代工程六大支柱（Adam Warski、Bill Venners、Giovanni Ruggiero、Krzysztof Romanowski、Matt Bovel、Michael Sperber），遵循“按风险递进加载（Progressive Disclosure）”原则。

本 skill 是工程规范指导，不是业务运行时，也不授予联网、生产操作、私自发布或多智能体权限。

## 任务执行四步法

1. **确立基准**：读目标仓库的 `AGENTS.md`、相关源码/测试与 `build.sbt`；从当前任务所在仓库选择这一份 skill，不同时加载同名副本。
2. **意图左移**：先明确要改变的业务行为与必须保留的领域不变量；普通小修复直接处理，公共契约、持久化存储或跨模块设计按要求先写短变更规范（EARS 规范）。
3. **精准定位与检索**：优先用 `rg` / `rg --files` 检索；可用且有帮助时再用 Metals 符号跳转。不假定外部 MCP 可用，也不猜测未固定的第三方 API 签名。
4. **按需加载参考**：按下表仅阅读与当前改动直接相关的 1–2 篇参考；触及新边界再按需展开，严禁一次性无脑通读全部参考。

## 按风险决定深度

| 当前任务类型 | 本次交付要求与验证深度 |
| :--- | :--- |
| **小修复 / 报错诊断 / 局部调整** | 针对相关实现与调用方；阅读必要参考；提供能暴露该问题的最小定向测试。 |
| **新状态 / 领域服务 / 并发与生命周期** | 严格阅读领域建模与效应参考；定义非法状态与失败/中断不变量；提供能否定错误实现的独立测试。 |
| **智能体循环 / 工具开发 / 安全守卫** | 严格阅读智能体运行时与工具契约参考；核查 Prompt 隔离、工具入参 Parse、输出外置与脱敏。 |
| **公共契约 / 存储结构 / 跨模块协作** | 编写短变更规范；核对序列化兼容与回滚路径；执行完整编译与测试套件（Green Gate）。 |
| **只读审查 / 代码诊断** | 仅阅读被审查代码与契约；给出文件、行号、触发条件与影响；审查中**严禁自动改动代码**。 |

## 始终坚守的六大铁律

- **编译器真理（Bill Venners 铁律 1）**：IDE 爆红先对照目标模块 CLI 编译；编译能做的事绝不交给 AI 猜测。通过编译不代表业务正确，但编译失败绝不交付。
- **Parse, Don't Validate（Matt Bovel）**：外部不可信数据在系统入口一次性解析为强类型，内部核心只接收已验证值。领域 ID 采用 `opaque type`，互斥状态采用 `enum`，彻底消除布尔盲区。
- **效应纪律与无悬空副作用（ZIO Effect Discipline）**：遵守 `-Wnonunit-statement` 和 `-Wvalue-discard`。ZIO effect 必须显式绑定（`_ <- effect`），严禁丢弃副作用。
- **受限并发与生命周期收口（Adam Warski & Capture Checking 精神）**：任何外部并行操作严禁裸 `foreachPar`，必须使用 `.withParallelism(n)` 显式限流；所有有状态资源通过 `ZLayer.scoped` / `acquireRelease` 保证释放。
- **反 Vibe 编码与 R2 铁腕约束（Krzysztof Romanowski）**：“AI 是倍增器，不是修复器”。改动规模越小越好，不搞过度设计，不引入未经批准的大型依赖，不产生与任务无关的冗余重构。
- **五视角自查与单一修改者（Single Adjudicator）**：交付前由同一开发者快速执行安全、并发、类型、最小改动、测试信号五视角自查。多 Agent 协作时评审者只读，修改归单一实施者。

## 按需参考索引

| 改动触及的领域 | 按需加载的参考文档 |
| :--- | :--- |
| 新 ID、状态机、边界解析、Schema/JSON 兼容 | [01. 领域类型与边界解析](references/01-domain-modeling.md) |
| ZIO 错误通道、阻塞隔离、并发上限、流式安全 | [02. 效应、错误、并发与中断](references/02-zio-effects-errors.md) |
| 服务依赖注入、Scoped 资源管理与确定性环境 | [03. 依赖组装与生命周期](references/03-zlayer-di.md) |
| 参数化 SQL、pgvector 检索、CAS 乐观锁与事务 | [04. PostgreSQL 与持久化边界](references/04-postgres-storage.md) |
| 独立测试预言机、虚拟时钟 TestClock 与架构测试 | [05. 测试与验证的信号](references/05-testing.md) |
| 代码交付前五视角审查清单与标准汇报模板 | [06. 五视角检查：按风险取证](references/06-review-loop-guardrails.md) |
| 确定性质量流水线、绿门门禁与决策账本规范 | [07. 确定性质量流水线](references/07-quality-pipeline.md) |
| 六层验证环 (R0-R5)、EARS 需求语法与概念盘点 | [08. 验收意图与六层验证](references/08-validation-rings-and-intent.md) |
| 函数式架构四大支柱、Command/Event 与组合子 | [09. 函数式核心与适度求精](references/09-functional-architecture-and-refinement.md) |
| 智能体循环、工具契约、Prompt 编译与安全守卫 | [10. 智能体运行时与工具契约](references/10-agent-runtime-tools-guardrails.md) |

## 结束任务交付准则

报告行为变化、关键不变量、实际执行的命令及未验证的边界。
**不把编译成功说成业务准确率，不把未执行的门禁说成通过，不为小任务额外生成流程文件、未授权依赖或无关 Git 提交。**
