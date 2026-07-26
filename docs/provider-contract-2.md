# ProviderContract 2.0

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 目标

Provider 数量不能代替协议质量。每个原生或兼容 Adapter 都必须在本地 stub server 上通过同一套门禁：

- `complete` 与 `stream` 均可用；
- 流恰好产生一个且最后产生 `Completed`；
- usage 非负，声明支持 usage 时必须报告；
- 工具 call ID/名称合法，工具结果能够进入下一轮模型调用；
- 429、5xx、断流、非法 usage 映射为正确 `ErrorCategory/retryable`；
- 慢流不依赖单次网络分块；
- ZIO Fiber 中断真正关闭 HTTP Transport；
- cassette 不保存消息、工具参数、答案、异常原文或认证信息。

## 2. 公共契约与 Provider stub 的分工

`ProviderContract.verifySuite` 统一判断结果，但不会假装自己能制造厂商网络故障。每个 Adapter 的 ZIO HTTP stub 负责返回
真实协议 envelope/SSE，然后用 `ProviderFailureProbe` 声明期望分类。

```scala
val report <- ProviderContract.verifySuite(
  model,
  successRequest,
  failureProbes = Chunk(
    ProviderFailureProbe(
      "http-429",
      ErrorCategory.Unavailable,
      expectedRetryable = true,
      model.complete(rateLimitedRequest)
    ),
    ProviderFailureProbe(
      "truncated-sse",
      ErrorCategory.Validation,
      expectedRetryable = false,
      model.stream(truncatedRequest).runDrain
    )
  ),
  cancellationProbe = Some(...),
  cassette
)

assert(report.passed)
```

取消探针同时检查两件事：调用 Fiber 是 interrupted，stub server 的 Body finalizer 也已经运行。只满足前者不代表 socket、
连接池 slot 或 Provider 请求已释放。

## 3. 脱敏 cassette

`ProviderCassettePolicy.Redacted` 只保存：

- operation、Provider ID；
- message 数量；
- 请求规范 JSON 的 SHA-256 指纹；
- succeeded/failed/interrupted；
- 稳定错误分类。

模型名被替换，工具名为空，正文和响应对象从未进入 entry。cassette 用于 CI 复现“哪种请求形状失败”，不是生产流量
录制器。真实 Provider wire 排障应使用已知假数据的 stub，不能录制用户请求。

## 4. 当前覆盖

- OpenAI compatible/Responses 已有本地 HTTP 与 SSE 契约测试；需要逐步迁移到统一 2.0 报告结构。
- Anthropic Messages 已直接使用 ProviderContract 2.0，覆盖成功、工具回填、429/500、负 usage、断流、慢流、取消和
  Redacted cassette。
- Gemini Interactions 已直接使用 ProviderContract 2.0，覆盖 2026 steps schema、无状态工具回填、任意 UTF-8 分块、
  429/500、负 usage、断流、慢流、Transport 取消、`store=false` 和 Redacted cassette。

## 5. 真实网络门禁

`ProviderContract.verifySuite` 负责无费用、可故障注入的提交级测试；`LiveProviderSmokeRunner` 负责预发布环境中真实
endpoint/密钥/模型的 complete、stream、usage、token 和延迟检查。两者不能互相替代。运行方式见
[真实 Provider 小流量 Smoke](provider-live-smoke.md)。
