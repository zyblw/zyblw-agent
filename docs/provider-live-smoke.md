# 真实 Provider 小流量 Smoke、Context 压缩与 MemoryExtractor Eval

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

更新时间：2026-07-17。

## 1. 为什么 stub contract 之外还需要真实 smoke

本地 ProviderContract 2.0 可以确定性覆盖 wire schema、SSE 任意分块、429/5xx、负 usage、断流、慢流和取消传播，但不能
证明当前 API Key、区域 endpoint、模型 ID、TLS、DNS、出口白名单在真实部署中仍然有效。因此发布门禁分成三层：

```text
每次提交：stub ProviderContract 2.0（无费用、完整故障注入）
        ↓
预发布/定时：LiveProviderSmokeRunner（真实网络、固定假数据、低调用量）
        ↓
业务发布：真实数据集 eval（质量、引用、安全、成本趋势）
```

Live smoke 不能替代业务 eval，也不能用生产用户 prompt 作为测试输入。

## 2. 通用 Provider smoke

`LiveProviderSmokeRunner` 对固定 ASCII marker 分别执行一次 `complete` 和 `stream`，默认总计两个计费请求。门禁包括：

- 能力矩阵可以加载且声明 streaming；
- complete/stream 全部成功并命中 marker；
- stream 恰好一个 `Completed`；
- 声明 usageReporting 时返回正 token 数；
- 单次 token 与延迟不超过显式预算。

报告只保存调用类型、延迟、usage、Completed 数量、marker 是否命中及错误 category/retryable。不保存 prompt、答案、
HTTP body、endpoint、request ID 或 API Key。

### 2.1 支持的真实目标

| `ZYBLW_SMOKE_PROVIDER` | 协议 | 必需变量 |
|---|---|---|
| `deepseek` | OpenAI-compatible + DeepSeek profile | `DEEPSEEK_API_KEY`；模型可选 |
| `glm` | OpenAI-compatible + GLM profile | `GLM_API_KEY`；模型可选 |
| `openai-chat` | OpenAI Chat Completions | `OPENAI_API_KEY`、`OPENAI_MODEL` |
| `openai-responses` | OpenAI Responses 原生 | `OPENAI_API_KEY`、`OPENAI_MODEL` |
| `anthropic` | Anthropic Messages 原生 | `ANTHROPIC_API_KEY`、`ANTHROPIC_MODEL` |
| `gemini` | Gemini Interactions 原生 | `GEMINI_API_KEY`、`GEMINI_MODEL` |

密钥只能放 Secret Manager、CI secret 或本机未提交环境文件，不要写进命令历史、文档或测试报告。

### 2.2 运行命令

安全注入环境变量后执行：

```bash
ZYBLW_SMOKE_PROVIDER=deepseek \
  mise exec -- sbt "examples/runMain com.zyblw.agent.examples.ProviderSmokeExample"
```

可选预算：

| 变量 | 默认 | 说明 |
|---|---:|---|
| `ZYBLW_SMOKE_REPETITIONS` | 1 | complete/stream 各执行次数，限制 1..5 |
| `ZYBLW_SMOKE_CALL_TIMEOUT_SECONDS` | 120 | 单请求外层硬超时 |
| `ZYBLW_SMOKE_MAX_LATENCY_MILLIS` | 60000 | 单调用发布 SLO |
| `ZYBLW_SMOKE_MAX_TOTAL_TOKENS` | 2000 | 单调用 input+output 上限 |
| `ZYBLW_SMOKE_MAX_OUTPUT_TOKENS` | 64 | 发送给 Provider 的输出限制 |

Runner 不自动重试。真实 smoke 的目标之一就是暴露首次请求失败率；自动重试会掩盖故障并放大费用。

## 3. LLM MemoryExtractor 真实工具调用 smoke

通用 marker smoke 不验证工具 schema。`MemoryExtractorSmokeExample` 提交一条固定的非医疗学习偏好，验证唯一工具调用、
sourceMessageIndex、逐字 evidenceQuote、至少一个候选、全部 Upsert/UserStated/非 Sensitive。报告不输出候选正文。

```bash
ZYBLW_SMOKE_PROVIDER=deepseek \
  mise exec -- sbt "examples/runMain com.zyblw.agent.examples.MemoryExtractorSmokeExample"
```

该命令通常产生一次调用；第一次 schema/证据无效时允许一次安全 repair，最坏可能两次。它不会写数据库。

## 4. Context 压缩真实 Provider smoke

通用 marker smoke 只能证明文本 complete/stream；它不能证明 Provider 对压缩器唯一 strict tool、逐字 evidence、
引用数组和 injection 数据边界的支持。运行：

```bash
ZYBLW_SMOKE_PROVIDER=deepseek \
  mise exec -- sbt "examples/runMain com.zyblw.agent.examples.ContextCompressionLiveSmokeExample"
```

同一个入口支持 `deepseek`、`glm`、`openai-chat`、`openai-responses`、`anthropic` 和 `gemini`。Runner 会：

1. 在任何计费调用前查询模型能力，要求声明 tool calling；
2. 要求传入的压缩器明确声明 `supportsModelAssisted=true`；
3. 使用固定假数据测试“中文约束 + 可信引用 + 提示注入诱饵”；
4. 关闭 validation 的确定性 fallback，Provider 不遵守工具/证据协议时必须失败；
5. 顺序重复运行，检查证据、引用、禁止内容、摘要哈希和 compressorVersion；
6. 把最大延迟同时作为 SLO 与主动 Fiber timeout，取消会传播到 HTTP 请求；
7. 只输出低敏 Provider 标签、模型、错误分类和 Context Eval 报告。

质量和资源变量：

| 变量 | 默认 | 说明 |
|---|---:|---|
| `ZYBLW_CONTEXT_SMOKE_REPETITIONS` | 3 | 相同输入重复次数，限制 1..5 |
| `ZYBLW_CONTEXT_SMOKE_TARGET_TOKENS` | 256 | 摘要目标 token |
| `ZYBLW_CONTEXT_SMOKE_MAX_MODEL_CALLS` | 2 | 单次调用数，含 repair，限制 1..4 |
| `ZYBLW_CONTEXT_SMOKE_MAX_LATENCY_MILLIS` | 60000 | 单次硬超时与延迟门禁 |
| `ZYBLW_CONTEXT_SMOKE_MAX_INPUT_TOKENS` | 4000 | 单次输入 token 上限 |
| `ZYBLW_CONTEXT_SMOKE_MAX_OUTPUT_TOKENS` | 800 | 单次输出 token 上限 |
| `ZYBLW_CONTEXT_SMOKE_MAX_SUMMARY_CODE_POINTS` | 2000 | 摘要 Unicode 长度上限 |
| `ZYBLW_CONTEXT_SMOKE_MIN_STABILITY_PERCENT` | 100 | 相同输出摘要哈希最低百分比 |

成本价格表不会硬编码在框架中，因为供应商、模型、区域和缓存折扣会变化。以下五项必须全部存在或全部缺失：

```bash
ZYBLW_CONTEXT_SMOKE_PRICING_CURRENCY=USD
ZYBLW_CONTEXT_SMOKE_PRICING_VERSION=provider-model-price-2026-07
ZYBLW_CONTEXT_SMOKE_INPUT_COST_PER_MILLION_MICROUNITS=500000
ZYBLW_CONTEXT_SMOKE_OUTPUT_COST_PER_MILLION_MICROUNITS=1000000
ZYBLW_CONTEXT_SMOKE_MAX_COST_MICROUNITS=500
```

`1 USD = 1,000,000 microunits`。全部缺失时仍可验证协议与质量，但成本门禁明确关闭；预发布和定时 CI 应成组配置真实
价格。错误不会回显变量值。

真实 smoke 最坏调用数是：

```text
repetitions × maxModelCallsPerAttempt
```

默认最多 6 次，但只有 schema/证据 repair 时才会使用第二次调用。运行前应确认账号额度和模型价格。

## 5. CI 门禁建议

1. PR 必跑 `testFull` 和 stub contract，不需要真实密钥；
2. 预发布分支对实际启用的 Provider 运行通用 smoke；
3. 启用模型辅助 Context 压缩前，对每个实际模型运行 Context smoke，并配置价格门禁；
4. Memory 功能发布前额外运行 MemoryExtractor smoke；
5. JSON 报告作为受控 CI artifact，不公开模型部署信息；
6. smoke 失败禁止自动切换到能力未知的 Provider；
7. 模型、endpoint、代理、Prompt/schema、价格或凭据变化后重新运行；
8. 用户流量上线仍需业务 eval、金丝雀、成本告警和 kill switch。

## 6. 当前诚实边界

框架已提供统一 Runner、六个 Provider 选择目标、低敏报告、确定性测试、MemoryExtractor 和 Context 压缩专项入口。
本地没有真实厂商密钥，本轮没有实际产生公网请求，也没有声称任何具体账号/模型已经通过 smoke。部署团队在对应网络、
账号、模型和价格版本上执行后的报告，才是该环境的证据。
