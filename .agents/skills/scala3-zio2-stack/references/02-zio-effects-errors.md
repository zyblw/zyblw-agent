# 02. 效应、错误、并发与中断

用于编写核心业务效应、适配器调用及异步任务。具体 API 签名以当前固定的 ZIO 2.1.x 版本为准。

## 一、 精确双通道错误模型 (`ZIO[R, E, A]`)

ZIO 的核心价值在于将可预测的业务错误与不可预测的系统缺陷严格分离：

1. **类型化错误通道 (`E`)**：
   - 领域层服务必须使用所属模块现有的错误 ADT（如 `E <: DomainError` 或 `E <: AgentError`）。
   - 业务失败必须具备明确的诊断标识、低敏感度信息及可操作建议，**严禁将数据库异常全文或敏感请求体直接抛给上游用户**。
   - 适配器边界（JDBC、外部 SDK、网络通信）产生的 Java `Throwable`，必须在边界使用 `ZIO.attempt` 或 `ZIO.attemptBlocking` 捕获，并立即映射为领域错误：
     ```scala
     ZIO.attemptBlocking(jdbcCall())
       .mapError(e => DomainError.DatabaseFailure(e.getMessage))
     ```
2. **系统缺陷与致命异常 (`Die / Cause.Die`)**：
   - 仅用于真正的致命缺陷（JVM `VirtualMachineError`、严重的内存溢出、不可恢复的内部断言失败）。
   - **严禁滥用 `catchAll`**：`catchAll` 仅用于处理类型化的 `E`，不要误以为其能捕获致命缺陷；若需使用更宽的 `catchAllCause` 或 `sandbox`，必须严格核验中断（Interruption）信号的向上传播，切勿无脑吞掉中断信号。

## 二、 严格的副作用丢弃纪律 (`-Wnonunit-statement`)

ZIO 中的值是**执行的纯数据描述**，本身不产生任何副作用。丢弃一个 ZIO effect 意味着该操作**永远不会被执行**。

- **编译器守卫**：构建系统已启用 `-Wnonunit-statement` 与 `-Wvalue-discard`。在 `for`-comprehensions 或代码块中，每一个 ZIO 表达式都必须被明确绑定或求值：
  ```scala
  // 错误：悬空 effect 被丢弃，实际上不会执行
  for
    user <- getUser(id)
    auditLog(user) // 编译器直接拦截报错！
  yield user

  // 正确：显式绑定执行
  for
    user <- getUser(id)
    _    <- auditLog(user)
  yield user
  ```
- **Java 互操作返回值**：当调用 Java 返回具体数值（如 `stmt.execute()` 或 `preparedStatement.executeUpdate()`）且其值确实无需使用时，显式标明忽略：`val _ = stmt.execute()`。

## 三、 受限并发与速率熔断 (Bounded Parallelism)

- **并发铁律**：**严禁在动态数据集合上直接使用裸的 `ZIO.foreachPar` 或 `ZIO.collectAllPar`**。无节制的并发会导致数据库连接池耗尽、第三方大模型 API 触发 429 限流或线程饥饿。
- **显式绑定上限**：所有并行集合计算必须根据下游承受能力绑定并发度：
  ```scala
  ZIO.foreachPar(chunks)(processChunk).withParallelism(8)
  ```
- **限流考量**：
  - 涉及数据库读写时，并发上限不得超过 HikariCP 连接池最大活跃连接数的一半；
  - 涉及 LLM 推理调用时，并发上限需与供应商 TPM/RPM 配额严格匹配。

## 四、 结构化并发、中断与资源生命周期 (Structured Concurrency)

1. **避免无主 Fiber**：严禁随意使用 `forkDaemon` 创建脱离父子上下文生命周期的“孤儿任务”。若必须在后台运行，必须明确指定 Fiber 的所有者、监督机制（Supervision）以及优雅关闭的停止条件。
2. **响应中断信号**：当父 Fiber 被取消或超时中断时，子 Fiber 必须能迅速响应中断。在执行长耗时的轮询或外部等待时，使用 `ZIO.sleep`（受虚拟时钟和中断控制）而非阻塞式 `Thread.sleep`。
3. **安全释放资源**：利用 `acquireRelease` 或 `ZIO.scoped` 确保资源（连接、临时文件、锁）在成功、失败或被中断（Interrupted）的所有情况下均能得到可靠释放。

## 五、 流式响应与客户端断开清理 (ZStream & Streaming Safety)

在大模型生成内容（SSE / 分块流式传输）或大数据读取场景中，使用 `ZStream` 时必须保障断开收口：

1. **客户端中断传播**：当浏览器关闭标签页或网络断开时，HTTP 服务器中断下游 Fiber。`ZStream` 必须能向上将中断信号传递给大模型 HTTP 客户端，立即终止远程网络读取，避免继续空耗 Token 费用。
2. **用量最终收口 (`ensuring` / `onExit`)**：即使流在半途被用户中断或失败，也必须在退出钩子中记录已实际消费的输入与部分输出 Token，保证计费审计数据的单调自洽。
