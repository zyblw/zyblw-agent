# 01. 领域类型与边界解析

用于新增或修改领域 ID、实体状态、边界 DTO 或序列化契约。以现有领域模型为基准，严禁凭空创造平行概念。

## 一、 Scala 3 Opaque Types 与智能构造器 (Smart Constructor)

对于容易在方法调用中混淆位置的同构 ID（例如 `UserId`、`DocumentId`、`TenantId` 均为 String），必须使用 `opaque type` 配合私有伴生对象构造器进行强类型封装，实现零运行时开销的编译期安全。

```scala
opaque type DocumentId = String

object DocumentId:
  def from(raw: String): Either[DomainError.ValidationError, DocumentId] =
    val trimmed = raw.trim
    if trimmed.nonEmpty && trimmed.length <= 64 then Right(trimmed)
    else Left(DomainError.ValidationError("DocumentId 必须为 1-64 位的非空字符"))

  def unsafe(raw: String): DocumentId =
    from(raw).fold(err => throw new IllegalArgumentException(err.message), identity)

  extension (id: DocumentId)
    def value: String = id
```

- **严禁的反模式**：在业务逻辑层大量传递裸 `String`，依赖命名约定（如 `def link(docId: String, userId: String)`），这极易在调用端发生参数颠倒（Transposition Bug）。
- **受控例外**：普通展示用文本、内部临时格式化字符串、SQL 静态表名等不需要过度包装；只对具有明确业务身份的标识符使用 `opaque type`。

## 二、 Parse, Don't Validate 与消除布尔盲区 (Boolean Blindness)

- **核心准则**：在不可信边界（HTTP 请求、外部 JSON、数据库读取、CLI 参数）**一次性解析（Parse）**为强类型领域对象；核心业务逻辑只接受已合法的强类型，不再进行防御性的布尔检查。
- **消除布尔盲区**：严禁使用多个离散的 Boolean 字段表达互斥状态（例如 `isCompleted: Boolean, isFailed: Boolean, isCancelled: Boolean` 会产生 $2^3 = 8$ 种组合，其中绝大部分是非法状态）。
- **代数数据类型建模（ADTs）**：
  ```scala
  enum RunStatus:
    case Pending
    case Running(startedAt: java.time.Instant)
    case Succeeded(output: String, completedAt: java.time.Instant)
    case Failed(reason: String, error: Option[DomainError])
    case Cancelled(reason: String)
  ```
  通过模式匹配（Pattern Matching）强制覆盖所有合法状态，利用编译器的 `-Werror` 或穷尽性检查防止遗漏分支。

## 三、 ZIO Schema 与 ZIO JSON 序列化契约

1. **派生优先**：优先利用 `DeriveJsonCodec.gen` 或 `DeriveSchema.gen` 自动生成 Codec，保持与已有协议严格对齐。
2. **严禁静默兜底**：反序列化缺少必填字段或格式非法时，必须明确 Fail-Closed 并抛出类型化错误；**严禁使用默认值伪造成功**（如将缺失的 `tenantId` 默认为空字符串），这会引发严重的安全穿透漏洞。
3. **向后兼容性**：新增字段必须为 `Option[T]` 并提供合理的演进策略；修改已有枚举值时必须核验历史数据库记录与外部调用方的兼容性。

## 四、 交付前自检清单

- [ ] 是否存在由于同形裸类型（String/Long）导致的参数混淆风险？
- [ ] 系统中是否存在可被构造出来的“非法状态组合”（如同时成功且失败）？
- [ ] 外部输入是否在入口边界即完成了合法性解析，而非深入到业务核心后才报空指针或格式错误？
- [ ] 新增或变更的字段是否提供了针对旧格式数据的兼容性与回滚测试？
