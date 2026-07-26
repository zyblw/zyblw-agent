# ADR-0001：ZIO Native 核心运行时

## 背景与问题

框架既要支持模型的非确定性工具循环，又要保证取消、资源释放、预算和错误可预测。把基于 `Future` 的 SDK 包一层 ZIO 无法自动获得结构化并发语义。

## 候选方案

1. 直接复用第三方 Agent runtime。
2. 一个包含 Provider、数据库和业务代码的单体模块。
3. 小型领域内核 + ZIO 原生 runtime + 外围适配器。

## 决定

采用方案 3。核心 API 使用 `ZIO`、`ZStream`、`Scope` 和 `ZLayer`；一次 Run 是受 Scope 管理的生命周期，模型流和工具调用都是其子任务。运行限制集中在 `RunPolicy`，不得散落为魔法数字。

## 未选择原因

方案 1 无法保证 ZIO Fiber 取消和类型化错误贯穿全链路；方案 2 会让 core 依赖 HTTP、数据库和观测 SDK，破坏可替换性。

## 风险与演化

ZIO 原生实现维护成本较高。通过稳定 SPI、契约测试和小内核控制风险；高级调度器放入 experimental 模块。
