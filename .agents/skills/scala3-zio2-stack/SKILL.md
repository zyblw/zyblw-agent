---
name: scala3-zio2-stack
description: Scala 3/ZIO 2 engineering guidance for implementation, refactoring, debugging, testing, and code review in this repository, including backend services, agent runtime, JDBC/pgvector, and resource lifecycle. Use for Scala changes or explicit Scala engineering questions; not for unrelated frontend, content, or deployment-only work.
---

# Scala 3 + ZIO 2 工程实践

将类型、效应与确定性验证用于本次任务；先复用仓库契约，再选择必要的深入资料。
本 skill 是开发指导，不是业务运行时，也不授予联网、生产操作、发布或多智能体权限。

## 进入任务

1. 读目标仓库的 `AGENTS.md`、相关源码/测试和实际 `build.sbt`；从当前任务所在仓库选择这一份 skill，不同时加载同名副本。
2. 明确要改变的行为与保留的不变量。小修复直接处理；公共 API、持久化格式、跨模块设计按仓库要求写短变更规范。
3. 用 `rg` / `rg --files` 定位文本；可用且有帮助时再用 Metals 的符号/引用能力。不假定某个 MCP 已安装，也不因 IDE 可用而禁止 CLI。
4. 对版本敏感的 ZIO API 使用可用的 `zio-knowledge`，ZIO HTTP 使用 `zio-http-knowledge`；缺少对应 skill 时查官方具体页面与本地固定版本源码。文档不可用时说明限制，用源码和编译验证，不猜签名。
5. 按下表只读与改动有关的参考。通常从 1–2 份开始，触及新风险再增读；不要默认读完九份。

## 按风险决定深度

| 当前任务 | 本次做到什么 |
| --- | --- |
| 小修复、报错定位、已有逻辑调整 | 相关实现与调用方、必要的领域/效应参考、能暴露问题的最小检查 |
| 新状态、领域服务、并发或资源生命周期 | 相关参考 + 非法状态/失败/中断不变量，增加能否定错误实现的测试 |
| 公共契约、持久化、跨模块或发布准备 | 按 AGENTS 写变更规范，核对兼容/迁移/回滚，运行相应集成或消费者门禁 |
| 只读审查 | 阅读连接的实现与契约，给出文件/行号/影响；不因审查自动改代码，也不要求先修绿才能诊断失败 |

## 始终保留的约束

- **编译器事实**：IDE 爆红先对照目标模块 CLI 编译；编译通过后处理索引/构建服务器，不盲改依赖。通过一次编译不等于全部运行行为正确。
- **领域建模**：领域 ID 复用现有 opaque types，互斥状态用 ADT/enum；边界 DTO、SQL 参数和兼容字段不为追求形式统一而全量包装。普通谓词保留 Boolean，互斥状态或难辨含义的开关才改 ADT。
- **效应与错误**：业务核心保持不可变、失败使用所属模块的领域错误；阻塞/异常在适配器处理。不要把平台错误强行继承框架 AgentError。允许受控 JDBC/解析器内部局部可变实现，不让共享可变状态逃逸。
- **执行安全**：组合并执行 ZIO effects，遵守现有编译警告；动态外部调用有明确并发上限，资源与子 fiber 的生命周期可收口，权限与租户过滤不能降级。
- **最小验证**：按改动选择格式、编译、定向测试与真实适配器测试；合并/发布再跑仓库完整门禁。纯 skill/文档修改检查元数据、链接和指令一致性，不机械启动数据库或全仓测试。
- **五视角自查**：交付前快速检查安全、性能/并发、类型/效应、最小改动和测试信号；无关项可略过。五个视角不等于五个 Agent，默认自己完成；有明确授权时才委派。

## 按需参考

| 改动触及 | 加载 |
| --- | --- |
| 新 ID、状态、边界解析、Schema/JSON 兼容 | [领域建模](references/01-domain-modeling.md) |
| ZIO 错误、阻塞、并发、取消与资源 | [效应与错误](references/02-zio-effects-errors.md) |
| 服务组装、依赖与 Scope | [ZLayer 与生命周期](references/03-zlayer-di.md) |
| JDBC、租户/权限、pgvector、事务与 fencing | [PostgreSQL](references/04-postgres-storage.md) |
| 测试 oracle、TestClock、集成测试 | [测试](references/05-testing.md) |
| 代码审查或交付前发现较高风险 | [五视角检查](references/06-review-loop-guardrails.md) |
| 长任务、阻塞决策或已授权的多 Agent 协作 | [质量流水线](references/07-quality-pipeline.md) |
| 新行为验收、状态机、关键不变量 | [验证层次与 EARS](references/08-validation-rings-and-intent.md) |
| 复杂业务规则、可回放核心、架构边界 | [函数式架构](references/09-functional-architecture-and-refinement.md) |

## 结束任务

报告行为变化、关键不变量、实际检查及未验证边界；只读诊断说明发现及依据。
不把编译成功说成业务准确率，不把未执行的门禁说成通过，不为小任务额外生成流程文件、依赖或提交。
