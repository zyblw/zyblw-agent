# 代码注释与源码阅读约定

> 状态：当前规范
> 最后核验：2026-07-30
> 适用范围：所有公开模块的 Scala 源码、迁移和关键测试

注释的目标不是把 Scala 语法逐句翻译成中文，而是让维护者理解契约、边界、不变量、失败语义和设计理由。机械地为每个
getter、局部变量和显然的参数重复类型信息，会掩盖真正重要的约束；公共 API、耐久状态、安全边界和非直观算法则必须有
足够细致的中文 Scaladoc。

## 1. 必须注释什么

以下声明在新增或实质修改时必须补中文说明：

| 对象 | 注释至少回答 |
|---|---|
| `opaque type` / 领域 ADT | 它代表什么、在哪个信任边界构造、稳定性要求 |
| 公共 `trait` / `class` / `enum` | 职责、明确不负责什么、线程/并发/资源语义 |
| 公共 effect 方法 | 成功结果、typed error、幂等性、取消/超时、授权责任 |
| 公共 case class | 字段含义、敏感性、取值不变量、序列化兼容面 |
| Store / Adapter | 事务边界、一致性、claim/lease/fencing、容量和损坏处理 |
| ZLayer 构造器 | 输入依赖、产出服务、资源生命周期、是否允许内存 fallback |
| Workflow / Tool / RAG 扩展点 | 谁执行副作用、如何重试、如何恢复、如何验证输出 |
| 迁移与 SQL | 表承担的领域事实、唯一键/索引对应的不变量、保留策略 |
| 关键测试 suite | 它证明哪条生产不变量，而不只是“测试某方法” |

参数若名称不足以表达业务语义，使用 `@param`；返回值具有特殊幂等、分页、低敏或所有权语义时使用 `@return`；错误通道有
调用方必须处理的分支时在正文列出，不把 `IO[E, A]` 当作全部说明。

## 2. 不应添加什么

- 不写“给字段赋值”“调用 save 方法”这类逐行翻译；
- 不重复类型签名已经完整表达的事实；
- 不在注释中保存过期的版本状态、开发进度或 TODO 路线；
- 不粘贴 Prompt、真实 Provider 响应、凭据、生产 Trace 或用户数据；
- 不用注释掩盖过长方法、模糊命名或错误抽象；应先重构；
- 不承诺源码和测试尚未证明的 exactly-once、生产就绪、任意 Provider 等价或完全安全。

局部私有纯函数可以不写 Scaladoc；当算法或边界非直观时，用一段“为什么”注释说明即可。编译器生成的
`apply/unapply/copy` 和简单 extension accessor 不需要逐项解释。

## 3. 推荐模板

```scala
/** 一句话说明领域职责。
  *
  * 说明调用顺序、信任边界、持久化/并发不变量，以及明确不保证的行为。
  *
  * @param runId
  *   已经通过宿主 tenant/user 授权的 Run 身份
  * @param limit
  *   单页上限；超界以 typed configuration/persistence error 失败
  * @return
  *   只含低敏投影的稳定有序页面，不含 Prompt、状态正文或 lease token
  */
def list(runId: RunId, limit: Int): IO[StoreError, Chunk[View]]
```

对复杂实现，正文优先描述不变量：

```scala
/** 只有 owner、token、generation 和未过期时间同时匹配时才能提交。
  *
  * 该检查阻止被抢占的旧 worker 覆盖新 owner；它不保证外部系统副作用 exactly-once，
  * 写工具仍需业务幂等键或 outbox/inbox。
  */
```

## 4. 文件头与 package 说明

不要求每个文件复制许可证、模块介绍或目录结构。一个文件包含多个紧密相关的领域对象时，在第一个公共对象前解释整体
协议；职责跨度过大时优先拆文件。package 级学习入口由 [源码阅读路线](source-tour.md) 统一维护，避免每个文件保存一份
容易漂移的路线图。

## 5. 注释与事实同步

代码变更的完成条件同时包括：

1. 类型、实现与测试通过；
2. 公共契约 Scaladoc 与当前行为一致；
3. canonical 文档不把 Planned 写成 Implemented；
4. migration、wire schema 和 JSON state 的兼容影响已说明；
5. 示例仍走生产主路径，没有另造简化 Runtime。

发现注释与测试冲突时，优先以构建、实现、迁移和测试确定事实，再修正文档。不能为了保留旧注释而维持不合理实现。

## 6. 渐进覆盖顺序

仓库已有大量源码，注释完善按风险而不是文件名机械推进：

1. `core` 的 ID、State、Event、Error 与公开 Builder；
2. `runtime`、`tools`、`permissions`、`sideeffects`；
3. `workflow`、command queue、lease/fencing 与持久化 Adapter；
4. Context、Memory、RAG、document loader 与 Artifact；
5. Provider、HTTP/MCP、Telemetry 与 Testkit；
6. 示例和关键测试。

每个后续功能切片都必须同步覆盖被触碰的公共 API；单独的注释治理 PR 可以补未修改但高风险的历史文件。这样可以逐步做到
完整可读，同时避免一次性数千条低价值注释导致难以审查、难以发现行为变化。
