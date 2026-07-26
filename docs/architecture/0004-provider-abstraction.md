# ADR-0004：Provider 能力协商与统一契约

## 背景与问题

OpenAI、DeepSeek、GLM 等接口相似但能力、字段和流事件并不完全一致，静默忽略参数会产生不可诊断行为。

## 候选方案

1. 以 OpenAI 请求类型作为内部模型。
2. 所有 Provider 宣称相同能力。
3. 厂商无关请求 + 每模型能力描述 + Adapter 显式降级或失败。

## 决定

采用方案 3。Provider 必须提供 `ModelCapabilities`；运行前校验 tool calling、structured output、streaming、vision/audio/reasoning 等要求。首个网络适配器为 OpenAI-compatible，DeepSeek/GLM 通过明确兼容档案接入。每个 Adapter 运行同一套 Provider contract tests。

## 未选择原因

方案 1 污染核心；方案 2 会把兼容错误推迟到生产请求。

## 风险与演化

能力会随模型版本变化，因此能力表属于配置和可测试注册表，不把模型名称硬编码进 runtime。
