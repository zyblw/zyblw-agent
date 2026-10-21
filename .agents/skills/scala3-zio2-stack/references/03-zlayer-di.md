# 03. 依赖组装与生命周期

用于新增业务服务、替换底层基础设施组件或调整生命周期。基于纯函数式内核与延迟架构（Late Architecture）理念。

## 一、 领域抽象与组装根解耦 (Inversion of Control)

1. **核心纯度（Late Architecture）**：
   - 领域核心模块（如 `agent-core` 或 `zyblw-server/domain`）只定义业务 Trait 和纯函数，对具体的数据库驱动、网络客户端或云端 SDK **零依赖、零感知**。
   - 所有外部技术依赖通过抽象 Trait 表达（如 `RunStore`、`EmbeddingClient`、`UserRepository`）。
2. **延迟组装（Composition Root）**：
   - 具体实现（如 `PostgresRunStoreLive`、`OpenAiEmbeddingClientLive`）统一在系统启动入口（`Main.scala` 或 `bootstrap/` 模块）通过 `ZLayer` 进行组装，不让具体类渗透进业务领域。

## 二、 Scoped 资源管理与优雅停机 (Capture Checking 精神)

具备物理生命周期的外部资源（如数据库连接池 HikariCP、HTTP 客户端、文件流通道），必须通过 `ZLayer.scoped` 与 `ZIO.acquireRelease` 管理生命周期：

```scala
val live: ZLayer[DbConfig, Throwable, DataSource] =
  ZLayer.scoped {
    ZIO.acquireRelease(
      ZIO.attemptBlocking(createHikariPool(config))
    )(ds => ZIO.attemptBlocking(ds.close()).ignoreLogged)
  }
```

- **生命周期收口**：应用关闭、测试退出或发生未捕获异常时，ZIO 运行时会依据依赖图的拓扑逆序严格执行释放逻辑，杜绝连接泄露与挂死。
- **严禁重复关闭**：已被上层 Scope 托管的对象，内部调用方严禁私自二次执行 `close()`。

## 三、 确定性环境注入 (Deterministic Testing)

- **避免环境污染**：严禁在业务逻辑中直接调用 `java.time.Instant.now()`、`System.currentTimeMillis()`、`scala.util.Random` 或 `sys.env`。
- **环境显式注入**：
  - 时间相关的逻辑依赖 ZIO 内置的 `Clock` 或显式传入的时钟服务；
  - 随机行为依赖 `Random`；
  - 配置通过 `ZLayer` 注入强类型配置对象。
- **单测确定性**：在单元测试中，可通过 `TestClock.adjust(...)` 或固定随机种子，在毫秒级时间内重现复杂的超时、退避和抖动逻辑，无需等待真实世界的时间流逝。

## 四、 严禁的反模式

1. **全局单例（Global Singleton）**：严禁使用 `object GlobalCache` 或静态 `var` 共享状态，这会直接破坏并发隔离并导致单元测试间互相污染。
2. **上帝 Layer（God Layer）**：严禁将数十个互不相关的服务揉成一个庞大的万能 Layer，导致细粒度测试无法单独替换某一桩模块。
3. **嵌套 Unsafe Run**：严禁在服务内部调用 `Unsafe.unsafe { ... runtime.unsafe.run(...) }`，这会打断 ZIO 的纤程调度与 FiberRef 传递。
