
### 缓存作为架构约束[¶](https://bojieli.github.io/ai-agent-book/book/chapter2/#%E7%BC%93%E5%AD%98%E4%BD%9C%E4%B8%BA%E6%9E%B6%E6%9E%84%E7%BA%A6%E6%9D%9F "Permanent link")

在生产级的 Agent 系统中，缓存不仅仅是性能优化手段——它是一个**架构约束**，决定了系统中许多看似无关的设计决策。

Claude Code 的实践揭示了一个深层的模式：当 Prompt Cache 的经济效益足够显著时，缓存一致性会反过来主导系统的架构选择。以下是几个体现这种约束的设计决策：

**提示词的结构由缓存边界决定**。系统提示词在物理上被一个缓存边界标记分为两部分，标记之前的内容可以跨用户、跨会话进行全局缓存，标记之后的内容则包含用户和会话的特定信息。这意味着提示词的排列顺序首先由缓存的经济性决定，其次才是语义逻辑。每个运行时条件（操作系统类型、当前模式、用户偏好等）如果被放在缓存边界之前，就会把缓存键的变体数量翻一倍（若每个条件都是二值的，N 个条件就会产生 2^N 种组合），因此所有的动态元素都需要放到边界之后。例如，如果有 3 个条件（macOS/Linux、普通/调试模式、中文/英文），就会产生 2×2×2 = 8 种不同的缓存键。

**子 Agent 必须与父 Agent 字节级对齐**。当主 Agent 派生子 Agent 或进行旁路查询时，如果子 Agent 继承父 Agent 的上下文，子 Agent 的提示词、工具定义、模型配置、消息前缀和思考配置必须与父 Agent 逐字节匹配。这样可以命中 API 服务商的 Prompt Cache，减少费用和延迟。当然，一些 Agent 框架在派生子 Agent 时，使用不同的上下文或提示词，这样就不要求字节级对齐。

**工具结果的替换字符串在首次出现时就被冻结**。当大型工具输出被替换为摘要预览时，替换后的字符串会被持久化保存。即使后续会话重启，系统也会使用完全相同的替换字符串——以保证恢复后的消息序列与缓存中的字节流一致，避免缓存失效。

这些设计选择的核心启示是：**在设计 Agent 架构时，缓存经济性不是事后优化，而是前置约束**。越早将这个约束纳入架构设计，后续的工程代价越小。

zyblw-agent这个框架中的缓存方面的有考虑吗，能够符合这方面的问题吗



是的，这一段对 zyblw-agent 很有启发，而且我认为它应该被纳入我们前面那套 Runtime Kernel 架构里。

先给结论：

> **当前 zyblw-agent 已经有 Prompt Cache 的“数据模型和计费基础”，但还没有真正做到“Cache-aware Agent Runtime”。**
>
> 换句话说，目前大概是“知道缓存发生了、能统计它”，但还没有做到“为了缓存命中率主动约束 Prompt 构造、Capability 加载、ChildRun、Context 排列和 Recovery”。

如果按照你引用 Claude Code 的这个思想继续完善，我认为非常值得，而且它和我们前面设计的 **CapabilityGraph / CompositionManifest / ContextAssembler / ModelInvoker / ChildRun** 是高度契合的，不需要再造一个独立“大缓存模块”。

---

# 一、先看 zyblw-agent 现在已经有什么

其实基础比我一开始预期的要好。

当前 `ModelCapabilities` 已经明确存在：

```scala
promptCache: Boolean = false
```

也就是说 zyblw-agent 已经认为：

> Prompt Cache 是 Provider/Model 的正式能力之一。

而不是某个 HTTP Adapter 偷偷实现的小优化。

更重要的是，当前 `TokenUsage` 已经明确区分：

```scala
inputTokens
outputTokens
cachedInputTokens
reasoningOutputTokens
```

并且代码明确规定：

```text
cachedInputTokens
属于 inputTokens 的子集
```

这实际上是比较好的 Provider-neutral usage 建模。

成本层甚至已经考虑：

```scala
cachedInputPerMillionTokens
```

成本计算时将：

```text
freshInputTokens
和
cachedInputTokens
```

分开计价。

所以：

```text
Prompt Cache
       ↓
Token Usage
       ↓
Cost Accounting
```

这条链已经存在。

---

# 二、OpenAI 这一侧已经真正读取 Cached Token

当前 OpenAI Responses Adapter 会读取：

```text
usage.input_tokens_details.cached_tokens
```

然后写入：

```scala
TokenUsage.cachedInputTokens
```

所以运行完一次 Agent 后，框架已经能够知道：

> 这次 10000 个输入 token 中，到底多少是缓存命中的。

当前 OpenAI API 本身也已经提供 `prompt_cache_key` 和 `prompt_cache_retention` 等缓存控制参数。([OpenAI平台][1])

而 zyblw-agent 当前的 `providerOptions` 能够作为低层 escape hatch 传递一些非保留的厂商参数。

所以：

**Provider 层并不是完全没准备。**

---

# 三、但是 Anthropic 这一侧目前明显还没做完整

这个差异比较明显。

当前：

```scala
AnthropicMessagesWire.UsageDto
```

只有：

```scala
input_tokens
output_tokens
```

没有：

```text
cache_read_input_tokens
cache_creation_input_tokens
```

而现在 Anthropic 的实际 Usage 已经会报告：

```text
cache_read_input_tokens
cache_creation_input_tokens
```

这样的缓存信息。([Claude Platform Docs][2])

所以这里应该完善成例如：

```scala
TokenUsage(
  inputTokens,
  outputTokens,
  cachedInputTokens,
  cacheWriteInputTokens,
  reasoningOutputTokens
)
```

甚至：

```text
Cache Read
Cache Write
```

最好分开。

因为 Anthropic 这类 Prompt Cache：

```text
第一次建立缓存
```

和：

```text
以后读取缓存
```

经济含义并不相同。

---

# 四、但这些仍然只是“缓存统计”

真正重要的是你引用文章的第二层含义：

> **Cache-aware architecture。**

当前 zyblw-agent 还没有完全到这一层。

区别大概是：

```text
现在：

Prompt
 ↓
Provider
 ↓
缓存碰巧命中
 ↓
统计 cachedTokens


应该：

Cache Policy
 ↓
约束 Prompt Layout
 ↓
约束 Context 排序
 ↓
约束 Tool Definitions
 ↓
约束 ChildRun
 ↓
约束 Recovery
 ↓
Provider
 ↓
高概率稳定命中 Cache
```

这两个等级完全不一样。

---

# 五、最值得新增的不是 `CacheManager`

这一点非常重要。

我**不建议**增加：

```text
agent-cache
CacheManager
PromptCacheService
```

然后把所有缓存都往里面塞。

这又会变成一个大杂烩。

真正应该增加的东西，我倾向叫：

# `CachePolicy`

以及：

# `PromptLayout / PromptPlan`

它应该进入我们前面架构中的：

```text
AgentCompiler
        ↓
CompositionManifest
        ↓
ContextAssembler
        ↓
ModelInvoker
```

而不是：

```text
AgentKernel
```

---

# 六、为什么不应该进入 AgentKernel？

因为 Kernel 关心的是：

```text
State
Budget
Authorization
Suspension
Ledger
Recovery
```

它不应该关心：

> Anthropic cache breakpoint 应该放在哪里。

也不应该关心：

> OpenAI prompt_cache_key 是什么。

这些属于：

```text
Prompt materialization / Provider invocation
```

所以完整架构应该稍微变成：

```text
AgentDefinition
      │
      ▼
AgentCompiler
      │
      ├── CapabilityGraph
      │
      ├── PromptLayout
      │
      └── CachePolicy
      │
      ▼
CompositionManifest
      │
      ▼
AgentKernel
      │
 KernelDecision
      ▼
RuntimeDriver
      │
      ├── ContextAssembler
      │       │
      │       └── Cache-aware PromptAssembler
      │
      ├── ModelInvoker
      │       │
      │       └── Provider Cache Strategy
      │
      └── ToolExecutor
```

这样最合理。

---

# 七、Prompt 不应该再被简单理解成 `messages`

目前：

```scala
ChatRequest(
  messages,
  tools,
  settings
)
```

这个 Provider-neutral API 非常简单。

长期来看，我认为应该在 **ChatRequest 上游** 引入：

```text
PromptPlan
```

例如：

```text
PromptPlan

┌──────────────────────────────┐
│ STATIC PREFIX                │
│                              │
│ Framework instructions       │
│ Agent instructions           │
│ Stable tool definitions      │
│ Stable skills                │
│ Stable policy                │
└──────── CACHE BOUNDARY ──────┘
│
│ SEMI-STABLE
│
│ Capability context
│ Stable workspace metadata
│ Stable world state
├──────── CACHE BOUNDARY ──────
│
│ DYNAMIC
│
│ User
│ RAG
│ Memory
│ Current tool results
│ Current run state
│ Time
└──────────────────────────────
```

最后：

```text
PromptPlan
       ↓
Provider-specific renderer
       ↓
ChatRequest / Wire JSON
```

---

# 八、这里真正重要的是 Static / Dynamic 分离

你引用文章里的核心其实就是这个。

假设你的 System Prompt 现在这样：

```text
你是中医知识助手。

当前用户：张三
当前日期：2026-09-15
语言：中文

你必须遵守以下 6000 token 中医业务规则……
```

那么：

```text
用户
日期
语言
```

都在稳定大块内容之前。

意味着：

```text
张三
李四
王五
```

三个用户：

```text
前缀都不同
```

缓存利用率很差。

---

正确结构应该是：

```text
你是中医知识助手。

6000 token 稳定业务规则
稳定工具定义
稳定安全规则
稳定 Skills
────────────────── CACHE BOUNDARY

当前用户：张三
当前日期：2026-09-15
语言：中文

当前问题……
```

于是很多 Run 都共享：

```text
前 6000 token
```

这个思想非常值得 zyblw-agent 正式建模。

---

# 九、所以我建议 ContextContribution 未来增加 Cache Stability

我们前面讨论过：

```scala
ContextContribution(
  source,
  trust,
  sensitivity,
  priority,
  tokenBudget,
  ...
)
```

现在还应该再增加一个：

```scala
cacheStability
```

例如：

```scala
enum CacheStability:
  case GlobalStable
  case AgentStable
  case SessionStable
  case RunStable
  case Dynamic
  case NeverCache
```

于是：

```text
Framework Instructions
GlobalStable

TCM Agent Instructions
AgentStable

Tool definitions
AgentStable

Skill catalog
AgentStable

用户偏好
SessionStable

当前订单
RunStable / Dynamic

RAG result
Dynamic

当前时间
Dynamic
```

这就非常漂亮。

---

# 十、然后 ContextAssembler 不再只是考虑 Token Budget

现在 ContextManager 已经有比较好的治理基础。

它已经区分：

```text
SystemAndSafety
Memory
Retrieval
HistorySummary
RecentMessages
```

并且会：

```text
预算
截断
summary
Context Rot 检测
```

未来排序应该同时考虑两个维度：

```text
Semantic Priority
+
Cache Stability
```

也就是说：

```text
现在：

什么最重要？
什么放得下？


以后：

什么最重要？
什么放得下？
什么应该保持稳定前缀？
什么必须放在动态尾部？
```

这才是真正：

# Cache-aware Context Governance

---

# 十一、你当前的 World Section 其实已经非常接近这个方向

现在：

```scala
ContextSectionSnapshot
```

已经有：

```text
id
version
fingerprint
sensitivity
payload
```

并且每一轮可以比较：

```text
上一轮 fingerprint
vs
当前 fingerprint
```

从而得到：

```text
Rendered
Unchanged
Suppressed
```

这其实非常有价值。

说明 zyblw-agent 已经有：

> **内容稳定性识别。**

例如：

```text
用户工作空间配置
fingerprint = AAA
```

下一轮还是：

```text
AAA
```

框架已经知道：

> 没变化。

---

# 十二、但是要注意：`omitUnchanged` 和 Prompt Cache 不是一回事

这是一个值得特别注意的架构问题。

现在的 World Section 可以做：

```text
内容没变
 ↓
本轮不重新发送
```

这叫：

> **Context delta optimization。**

而 Prompt Cache 是：

```text
内容没变
 ↓
仍然发送完全相同的 prefix
 ↓
Provider 命中 KV cache
```

这叫：

> **Provider prefix reuse。**

有时候这两个目标甚至冲突。

例如：

### 方案 A

上一轮 5000 token 世界状态。

本轮：

```text
不发送
```

节省 5000 输入 token。

可能很好。

### 方案 B

继续发送相同 5000 token。

但是：

```text
cache read price = 很低
```

而且模型语义更加完整。

也可能更划算。

所以：

```text
omitUnchanged = true
```

不能永远作为默认优化。

以后应该交给：

```text
CachePolicy
```

判断：

```text
Omit
RepeatCacheable
Summarize
Reference
```

这就是为什么缓存确实开始成为**架构约束**。

---

# 十三、我建议正式引入 `PromptSegment`

例如：

```scala
final case class PromptSegment(
    id: PromptSegmentId,
    content: PromptContent,
    stability: CacheStability,
    sensitivity: DataSensitivity,
    fingerprint: String,
    cacheHint: CacheHint
)
```

然后：

```scala
enum CacheHint:
  case PreferCache
  case ProviderDefault
  case NoCache
```

最终 ContextAssembler 输出：

```text
PreparedPrompt
```

而不是直接只输出：

```text
Chunk[AgentMessage]
```

例如：

```text
PreparedPrompt
 ├─ stablePrefix
 ├─ stableTools
 ├─ sessionPrefix
 ├─ conversation
 ├─ dynamicContext
 └─ userTurn
```

---

# 十四、然后 ModelInvoker 负责翻译成 Provider 语义

因为各厂商缓存机制不会完全一致。

例如：

```text
PreparedPrompt
```

到了 Anthropic：

```text
cache_control
cache breakpoints
```

到了 OpenAI：

```text
prefix stability
prompt_cache_key
prompt_cache_retention
```

当前 OpenAI API 已经公开这些缓存相关参数。([OpenAI平台][1])

所以 Kernel 永远不应该出现：

```text
if Anthropic
if OpenAI
```

而应该：

```text
PromptPlan
      ↓
CacheStrategy
      ↓
Provider Adapter
```

---

# 十五、这时 `ModelCapabilities.promptCache` 也应该升级

现在只是：

```scala
promptCache: Boolean
```

未来可能不够。

可以考虑：

```scala
final case class PromptCacheCapabilities(
    supported: Boolean,
    automaticPrefix: Boolean,
    explicitBreakpoints: Boolean,
    explicitCacheKey: Boolean,
    extendedRetention: Boolean,
    reportsCacheReadTokens: Boolean,
    reportsCacheWriteTokens: Boolean
)
```

然后：

```text
OpenAI
Anthropic direct
Anthropic Bedrock
Gemini
Qwen
```

分别声明自己的能力。

这比：

```text
supportsCache = true
```

可靠得多。

现在 Anthropic 在不同接入方式下缓存能力就并不完全一样；例如官方文档明确说 Bedrock 支持 Prompt Caching，但不支持 Claude API 的 top-level automatic caching，需要显式 breakpoint。([Claude Platform Docs][3])

所以这个抽象非常有现实价值。

---

# 十六、当前 ModelRouter 还有一个值得完善的地方

当前：

```scala
ModelRequirement
```

有：

```text
profile
sensitivity
vision
toolCalling
strictToolSchema
```

但没有：

```text
promptCache
```

而 `ModelRouter.rejectionCodes()` 目前也不会因为：

```text
这个任务高度依赖 Prompt Cache
```

过滤掉不支持 Cache 的 Provider。

未来可以支持：

```scala
cacheRequirement =
  Required
  Preferred
  Irrelevant
```

例如：

### 很短的一次性请求

```text
Irrelevant
```

### 50k system/tool prefix 的长期 Coding Agent

```text
Required / Preferred
```

### 长时间重复使用同一医学知识说明的 Agent

```text
Preferred
```

Router 就可以把 Cache 纳入模型选择。

---

# 十七、甚至模型路由成本也应该 Cache-aware

现在路由成本估算大致：

```text
inputTokens × input price
+
outputTokens × output price
```

虽然真正结算时已经支持：

```text
cached input price
```

但路由阶段并不知道：

```text
预计多少输入会命中缓存。
```

以后可以有：

```text
CacheExpectation
```

例如：

```text
staticPrefix = 30k
dynamic = 5k
historical hitRate = 90%
```

于是：

```text
预计 cached = 27k
fresh = 8k
```

然后模型 Router 才能真正比较：

```text
OpenAI
Claude
Qwen
```

哪个便宜。

这对于长期 Agent 非常有价值。

---

# 十八、你引用的“子 Agent 字节级对齐”也值得支持，但不要强制所有 ChildRun 都这样

以后我们设计：

```text
ChildRun
```

时最好明确两种模式：

```text
ChildContextMode
```

例如：

```text
SharedPrefix
Isolated
Derived
```

### SharedPrefix

```text
Parent
│
│ System
│ Tools
│ Instructions
│ Stable Context
│
├──────── exact same prefix ────────
│
├── Child A dynamic task
└── Child B dynamic task
```

这样可以利用 Provider Prompt Cache。

---

但是：

### Isolated

某些 Child Agent：

```text
不同模型
不同工具
不同 System
```

完全不用强求字节一致。

所以不应该写：

> ChildRun 必须和 Parent 一样。

而应该写：

> **如果声明 `SharedPrefix`，Runtime 必须保证 Prefix Fingerprint 一致。**

这才是类型安全的架构。

---

# 十九、这里可以增加一个非常漂亮的东西：`PromptFingerprint`

类似你现在：

```text
CompositionFingerprint
ToolContractFingerprint
ContextSectionFingerprint
```

已经大量使用 SHA-256。

完全可以进一步有：

```text
PromptPrefixFingerprint
```

例如：

```text
prefixFingerprint =
SHA256(
  model
  + instructions bytes
  + tools bytes
  + stable context bytes
  + thinking config
)
```

Parent：

```text
ABC123
```

Child：

```text
ABC123
```

Runtime 就知道：

> 可共享缓存前缀。

如果：

```text
ABC124
```

就知道：

> 已经 drift。

---

# 二十、而 CompositionManifest 正好可以保存这个

这和我们前面讨论的架构高度吻合。

未来：

```text
CompositionManifest
```

可以增加：

```text
PromptLayoutVersion
StablePrefixFingerprint
ToolsetFingerprint
CachePolicyVersion
ProviderCacheMode
```

于是 Run 恢复的时候：

```text
旧 Prefix = ABC
新 Prefix = DEF
```

Runtime 可以明确知道：

> Prompt materialization 已经发生变化。

而不是神不知鬼不觉地产生 Cache Miss。

---

# 二十一、工具大结果的“替换字符串冻结”也非常值得做

当前 ContextManager 已经有：

```text
truncatedToolResults
summary checkpoint
compression
```

这样的上下文控制机制。

但是，如果要做到你引用的那种严格 Cache Architecture：

> **只做 deterministic truncation 还不够。**

应该把：

```text
ToolResultMaterialization
```

作为 durable fact。

例如第一次：

```text
原始 Tool Result
2 MB
```

生成：

```text
"[tool-result artifact=sha256:abc size=2097152 preview=...]"
```

应该保存：

```text
materializedRepresentation
```

---

恢复后不要重新生成：

```text
"[tool-result size=2.0MB artifact=abc ...]"
```

哪怕语义一样，

字节不一样：

```text
Cache Miss
```

所以应该：

```text
第一次 materialize
        ↓
Persist exact representation
        ↓
Recovery
        ↓
Replay exact representation
```

这一点和 zyblw-agent 的：

```text
Artifact
Ledger
Durable State
```

理念非常契合。

---

# 二十二、其实当前 Provider Adapter 已经有类似“原样重放”的正确意识

这是我比较喜欢当前代码的一点。

OpenAI Responses 现在会保存完整：

```text
raw output items
```

因为 reasoning/tool interaction 后续回填时需要原样重放。

Anthropic 也会保存完整：

```text
assistant content blocks
```

包括 thinking/signature/tool_use，以便下一轮原样 replay。

这个思想其实和：

> 字节稳定 / 协议稳定 / 恢复一致

已经是同一路线了。

所以扩展到：

```text
Prompt Materialization
```

非常自然。

---

# 二十三、但不要追求“整个 Prompt 永远字节稳定”

这点也很重要。

不是所有内容都应该稳定。

例如：

```text
当前时间
用户输入
RAG结果
Memory
实时库存
搜索结果
```

本来就是动态的。

真正应该追求：

```text
最大化稳定前缀
```

而不是：

```text
让整个 Prompt immutable
```

所以最终结构应该类似：

```text
CACHE-FRIENDLY PREFIX

[Framework Instructions]
[Agent Instructions]
[Stable Capability Instructions]
[Stable Tool Schemas]
[Stable Safety Policy]
[Stable Skills]

──────── Cache Boundary ────────

SESSION-STABLE

[User preferences]
[Workspace configuration]

──────── Cache Boundary ────────

DYNAMIC

[Current world state]
[RAG]
[Memory]
[Recent turns]
[Tool results]
[Current user request]
```

这是我认为 zyblw-agent 最应该采用的形态。

---

# 二十四、但是缓存不能凌驾于语义、安全和隐私

这里必须加一个非常重要的限制。

你引用文章讲的是：

> 缓存经济性影响架构。

对。

但顺序应该是：

```text
Correctness
   >
Security / Privacy
   >
Semantic Quality
   >
Durability
   >
Cache Economics
```

而不是：

```text
为了 Cache
牺牲安全边界
```

例如：

```text
Tenant A
```

和：

```text
Tenant B
```

即使 Prompt 一模一样，也不能因为想共享 Cache，就破坏 Provider 的数据隔离策略。

而且 OpenAI 当前明确指出 Extended Prompt Caching 与某些 Zero Data Retention 条件存在数据控制上的关系。([OpenAI平台][4])

所以：

```text
CachePolicy
```

必须受到：

```text
DataSensitivity
TenantPolicy
ProviderPolicy
RetentionPolicy
```

约束。

这和 zyblw-agent 当前已经存在的 `DataSensitivity`、Provider Routing 非常匹配。

---

# 二十五、因此我会把缓存放在前面架构里的这个位置

最终可以变成：

```text
                     AgentDefinition
                           │
                           ▼
                    AgentCompiler
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
   CapabilityGraph    PromptLayout      CachePolicy
          │                │                │
          └────────────────┼────────────────┘
                           ▼
                 CompositionManifest
                           │
                           ▼
┌─────────────────────────────────────────────┐
│                AgentKernel                  │
│                                             │
│ State / Budget / Auth / Suspension          │
│ ToolPlan / Ledger / Recovery                │
└──────────────────────┬──────────────────────┘
                       │
                KernelDecision
                       ▼
┌─────────────────────────────────────────────┐
│              RuntimeDriver                  │
│                                             │
│ ContextAssembler                            │
│      ↓                                      │
│ Cache-aware PromptAssembler                 │
│      ↓                                      │
│ ModelInvoker                                │
│      ↓                                      │
│ Provider Cache Strategy                     │
└──────────────────────┬──────────────────────┘
                       │
          OpenAI / Anthropic / Qwen / ...
```

注意：

# Cache 不进入 AgentKernel。

这一点我非常确定。

---

# 二十六、我对当前 zyblw-agent 缓存成熟度的判断

如果严格按照你引用的“缓存作为架构约束”标准：

| 能力                                     | 当前情况              |
| ---------------------------------------- | --------------------- |
| Provider 声明 Prompt Cache 能力          | ✅ 已有               |
| Cached Token 统一模型                    | ✅ 已有               |
| OpenAI cached token 读取                 | ✅ 已有               |
| Cached Token 成本模型                    | ✅ 已有               |
| Context fingerprint                      | ✅ 已有               |
| Stable world-section detection           | ✅ 已有               |
| Raw Provider block durable replay        | ✅ 部分很好           |
| Anthropic cache usage                    | ⚠️ 不完整           |
| Typed Provider Cache Policy              | ❌                    |
| Prompt Cache Boundary                    | ❌                    |
| Static/Dynamic Prompt Segmentation       | ❌                    |
| Prompt Prefix Fingerprint                | ❌                    |
| Cache-aware Context Ordering             | ❌                    |
| Cache-aware Model Routing                | ❌                    |
| ChildRun Prefix Sharing Contract         | ❌                    |
| Exact Tool-result materialization replay | ⚠️ 需要强化         |
| Cache hit-rate / cache economics SLO     | ⚠️ 基础有、体系不足 |

所以我会给现在：

**缓存基础设施：7/10**

但是：

**缓存作为架构约束：4～5/10。**

不是“没考虑”。

而是：

> **已经把地基做了不少，但还没有把它上升为 Prompt/Context Runtime 的一等设计原则。**

---

# 二十七、这件事值不值得现在做？

我的判断是：

## 非常值得。

但不要现在做一个“大 Cache 系统”。

最合理的推进顺序只有这几个核心动作：

1. 把 `promptCache: Boolean` 升级为结构化 `PromptCacheCapabilities`，补全 Anthropic/OpenAI 等 Adapter 的 cache usage 与 typed 参数。
2. 在 `ContextAssembler` 上面形成 `PromptPlan`，正式区分 Stable / Session / Dynamic segment。
3. 为每个 Segment 引入 `CacheStability + fingerprint`，Compiler 根据 Provider 能力生成 CachePlan。
4. 把 `PromptLayoutVersion / StablePrefixFingerprint / CachePolicyVersion` 纳入 `CompositionManifest`。
5. 等 ChildRun 真正建立后，再支持 `SharedPrefix / Derived / Isolated` 三种继承方式，而不是为了缓存强迫所有子 Agent 完全相同。
6. 把 Tool 大结果/压缩结果的最终模型可见 representation 固化成 durable materialization，Recovery 重用第一次的精确表示。

---

做到这里以后，zyblw-agent 的 Prompt Cache 就不再是：

```text
Provider 恰好给我省了一些钱
```

而会变成：

```text
AgentCompiler
       ↓
有意识地产生稳定 Prompt 结构

ContextAssembler
       ↓
保护稳定前缀

CompositionManifest
       ↓
保护跨恢复一致性

ChildRun
       ↓
有意识地复用父前缀

ModelInvoker
       ↓
映射 Provider cache semantics

Telemetry
       ↓
测量 cache hit / latency / cost
```

**这才是真正的“缓存作为架构约束”。**

而且我认为它非常适合我们前面为 zyblw-agent 设计的方向：不是增加一个新大模块，而是让 **Compiler、Manifest、Context、ModelInvoker、ChildRun** 都认识一个统一的 `CachePolicy`。这样既吸收 Claude Code 这类系统的实践，又不会让 Kernel 被 Provider 缓存细节污染。

这一个改进，我认为确实属于前面我们所说的那类：**不是时髦 Agent Feature，而是长期运行 Agent 系统里很可能经得起时间考验的基础能力。**

[1]: https://platform.openai.com/docs/api-reference/responses-streaming/response/refusal?lang=python&utm_source=chatgpt.com
[2]: https://docs.anthropic.com/zh-CN/docs/agents-and-tools/tool-use/web-search-tool?utm_source=chatgpt.com
[3]: https://docs.anthropic.com/en/api/claude-on-amazon-bedrock?utm_source=chatgpt.com
[4]: https://platform.openai.com/docs/models/default-usage-policies-by-endpoint?utm_source=chatgpt.com
