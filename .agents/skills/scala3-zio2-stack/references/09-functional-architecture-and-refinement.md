# 09. 函数式核心与适度类型求精

用于指导复杂业务规则设计、状态机演进、规则引擎组合子设计以及纯内核与驱动的边界切分。

## 一、 Michael Sperber 函数式架构四大支柱

### 支柱 1：数据建模优先于控制流 (Data Modeling First)
在编写任何逻辑处理函数之前，先定义好数据的代数结构（Sum / Product ADTs）。程序本质上是“合法数据之间的转换函数”。状态的合法性应当由数据结构本身自然表达，而不是依靠在代码各处穿插的条件判断。

### 支柱 2：一切皆数据——命令与事件的镜面对偶 (Command vs Event Mirror)
在 Agent 状态机、工作流流转或会话治理中，将外部控制与历史事实明确解耦：

- **Command（命令）**：表达**外部主体的意图**。它可能合法，也可能非法；必须经过权限核验和不变量检查，最终被**接受或拒绝**。
- **Event（事件）**：表达**已经不可逆转地发生的历史事实**。事件必定带有单调自增版本号（Sequence）与产生时间戳，且**必须能被状态机无副作用地折叠**。
- **纯状态折叠函数 (State Reducer)**：
  ```scala
  // 纯函数，无任何 I/O 副作用，100% 确定性回放
  def transition(state: AgentState, event: AgentEvent): AgentState
  ```
  状态的流转完全由纯数据驱动，适配器只负责持久化事件和分发消息。

### 支柱 3：组合子超越面条代码 (Combinators Over Spaghetti)
对于涉及安全审核规则、检索召回融合、计费策略等多重组合的复杂逻辑，将其建模为声明式的代数规则树（AST）：

```scala
sealed trait GuardRule:
  def and(other: GuardRule): GuardRule = GuardRule.And(this, other)
  def or(other: GuardRule): GuardRule  = GuardRule.Or(this, other)
  def not: GuardRule                  = GuardRule.Not(this)

object GuardRule:
  case class ExactMatch(term: String) extends GuardRule
  case class RegexMatch(pattern: String) extends GuardRule
  case class And(left: GuardRule, right: GuardRule) extends GuardRule
  case class Or(left: GuardRule, right: GuardRule) extends GuardRule
  case class Not(rule: GuardRule) extends GuardRule
```

通过一个纯求值解释器（Interpreter）对数据树进行求值，消除深达数十层的嵌套 `if-else`。

### 支柱 4：延迟架构与纯内核解耦 (Late Architecture / ADR-0028)
- **纯内核（Core）**：只包含业务模型、不变量验证与纯状态转移。坚决不引入 JDBC、HTTP、Kafka、Aliyun OSS 等底层依赖。
- **外部驱动（Drivers / Adapters）**：作为薄薄的桥接层，负责将外部世界（数据库行、HTTP 字节流）映射为内核强类型，或者将内核输出写入外部存储。
- **收益**：核心业务逻辑无需启动数据库或网络即可实现毫秒级纯内存单测。

## 二、 Matt Bovel 逻辑求精与边界解析 (Parse, Don't Validate)

1. **入口处强制求精**：
   对于数组长度、切片区间、归一化浮点数等算术不变量，在输入边界解析为经过求精的强类型。例如向量检索中的 Embedding：
   ```scala
   opaque type NormalizedEmbedding = Array[Float]

   object NormalizedEmbedding:
     def from(raw: Array[Float], expectedDim: Int = 1536): Either[DomainError, NormalizedEmbedding] =
       if raw.length != expectedDim then
         Left(DomainError.ValidationError(s"向量维度必须为 $expectedDim，实际为 ${raw.length}"))
       else
         // 防御性拷贝，防止外部调用者修改原可变数组别名
         Right(raw.clone())
   ```
2. **防可变别名污染**：
   Scala 3 的 `opaque type` 隐藏了类型表示，但不会自动将底层的 Java `Array` 变成不可变对象。因此在构造和暴露时必须做好防御性拷贝，或者复用 ZIO 的不可变结构（如 `Chunk[Float]`）。

## 三、 架构自检与反模式

- [ ] 核心模型是否被数据库驱动（JDBC）或外部云 SDK 类型所污染？
- [ ] 状态流转是否依赖难以预测的外部时钟或全局随机源，而非可重放的纯数据事件？
- [ ] 复杂规则是否堆砌了过程式 `if-else` 面条代码，而非采用清晰的数据组合子？
- [ ] 数组或切片边界是否可能由外部别名并发篡改？
