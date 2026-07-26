# ADR-0007：安全可观测性

## 背景与问题

生产 Agent 必须解释模型、工具、预算、审批和失败过程，但原始 prompt、密钥和工具结果可能包含敏感信息。

## 候选方案

1. 记录所有原始交换。
2. 只打印日志。
3. 厂商无关 Telemetry SPI + 内容记录策略 + 可选 OpenTelemetry Adapter。

## 决定

采用方案 3。默认只记录 ID、分类、真实持续时间、用量、大小和状态；内容记录默认关闭并经过脱敏器。Trace 使用安全
`TelemetryEvent`，Metrics 使用封闭 `AgentMetric` ADT，禁止任意 label Map。模型、工具和 evaluator 等潜在动态维度必须
经过 allow-list，run/session/tenant/user/call ID 永不进入 Metrics。关键审计事件直接写 Store，不依赖可采样或丢失的遥测通道。

OpenTelemetry SDK、BatchSpanProcessor 和 PeriodicMetricReader 由同一 scoped ZLayer 管理。Trace 可发往 Langfuse，Metrics
通过 OTLP Collector 进入 Prometheus；两类 endpoint 使用独立 header，避免把 Langfuse Authorization 发给 Metrics Collector。

## 未选择原因

方案 1 有隐私和密钥泄漏风险；方案 2 无法统一 metrics、trace 和测试断言。

## 风险与演化

异步 exporter 仍可能丢失非关键事件，collector 宕机也会造成时间窗口空洞；关键审计路径保持同步持久化。当前已经验证
exporter 不可达不会污染业务错误通道，后续仍需增加 OTel SDK 自监控指标、Collector 队列/丢弃告警和生产容量演练。
