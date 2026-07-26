package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

enum SideEffect:
  /** 不修改任何业务状态。 */
  case None

  /** 工具自身已经通过业务唯一约束或幂等协议保证重复执行得到同一效果。 */
  case IdempotentWrite

  /** 业务 mutation、幂等记录和 outbox 在同一个本地数据库事务中提交。 */
  case TransactionalOutboxWrite

  /** 没有可机械验证幂等边界的写入；崩溃后的 Running 必须视为 Unknown。 */
  case NonIdempotentWrite

  /** 删除、覆盖或其他难以恢复的操作；除审批外还必须由业务定义恢复或补偿路径。 */
  case Destructive

/** 工具对某一业务资源冲突组的访问方式。读取之间可并行，任一写入都会与同组访问冲突。 */
enum ToolAccessMode:
  case Read, Write

/** 工具的静态读写冲突声明。
  *
  * @param group
  *   稳定资源组，例如 `knowledge.documents` 或 `user.profile`；不得使用数据库表名拼接用户输入
  * @param mode
  *   对该资源组只读还是写入
  */
final case class ToolConflictAccess(group: String, mode: ToolAccessMode):
  require(group.trim.nonEmpty, "工具冲突组不能为空")

/** 工具是否允许冲突感知并行。
  *
  * SequentialOnly 是安全默认值；只有工具作者完成幂等、线程安全和冲突组审查后才可声明 ConflictAware。
  */
enum ToolParallelism:
  case SequentialOnly, ConflictAware

/** 工具的安全、重试、脱敏与并发元数据；所有字段由工具作者显式声明，Runtime 不根据名称猜测。
  *
  * @param risk
  *   权限和审批使用的风险等级
  * @param sideEffect
  *   副作用类型，决定是否允许自动重试
  * @param requiredScopes
  *   调用者必须同时拥有的业务 scope
  * @param sensitiveInputFields
  *   进入 trace 前需要脱敏的输入字段名
  * @param sensitiveOutputFields
  *   进入模型上下文或 trace 前需要脱敏的输出字段名
  * @param parallelism
  *   安全默认 SequentialOnly；审查通过后才能声明 ConflictAware
  * @param conflictAccesses
  *   工具访问的静态业务资源组及读写方式；空集合不会被解释成无冲突
  */
final case class ToolMetadata(
    risk: ToolRisk,
    sideEffect: SideEffect,
    requiredScopes: Set[String] = Set.empty,
    sensitiveInputFields: Set[String] = Set.empty,
    sensitiveOutputFields: Set[String] = Set.empty,
    parallelism: ToolParallelism = ToolParallelism.SequentialOnly,
    conflictAccesses: Set[ToolConflictAccess] = Set.empty
):
  /** 只有纯读取、显式业务幂等写入或经过事务 outbox 包装的写入可自动重试。 `TransactionalOutboxWrite` 的可重试性来自业务幂等记录，而不是 outbox
    * 本身；普通工具不得只修改枚举值来冒充可靠写入。
    */
  def automaticallyRetryable: Boolean =
    Set(SideEffect.None, SideEffect.IdempotentWrite, SideEffect.TransactionalOutboxWrite).contains(sideEffect)

  /** 只有显式声明 ConflictAware 且至少给出一个冲突组的工具才可进入并行批次。 空冲突组不能理解为“与任何资源都不冲突”，因为那会把漏声明静默升级成并发安全承诺。
    */
  def conflictAwareParallel: Boolean =
    parallelism == ToolParallelism.ConflictAware && conflictAccesses.nonEmpty

/** 类型安全工具定义。
  *
  * @tparam R
  *   执行工具所需的 ZIO 环境
  * @tparam I
  *   已校验的输入类型
  * @tparam E
  *   工具业务错误类型
  * @tparam O
  *   输出类型
  */
trait Tool[-R, I, E <: AgentError, O]:
  /** 工具稳定名称，Provider 调用和审计记录都依赖该值。 */
  def name: ToolName

  /** 给模型看的简明能力描述，不应包含秘密或内部实现。 */
  def description: String

  /** 输入 JSON Schema；Runtime 在执行前按它约束模型参数。 */
  def inputSchema: Json.Obj

  /** 可选输出 Schema，用于 MCP/文档和结果校验。 */
  def outputSchema: Option[Json.Obj]

  /** 权限、风险、副作用和脱敏元数据。 */
  def metadata: ToolMetadata

  /** 将不可信 JSON 参数解码为类型 `I`，失败时不得进入业务执行。 */
  def decodeInput(json: Json): Either[AgentError.ToolInputInvalid, I]

  /** 执行工具业务逻辑。
    * @param input
    *   已通过本地解码和校验的输入
    * @param context
    *   可信运行上下文，包含 run/thread/call 与业务权限
    * @return
    *   需要环境 `R`、可能以 `E` 失败的 ZIO effect
    */
  def execute(input: I, context: ToolExecutionContext): ZIO[R, E, O]

  /** 把业务输出编码成跨 Provider 的 JSON 边界。 */
  def encodeOutput(output: O): Either[AgentError.ToolExecutionFailed, Json]

object Tool:
  /** 使用 zio-json 构造无反射工具；服务依赖仍通过 `R` 显式表达。 */
  def json[R, I: JsonDecoder, E <: AgentError, O: JsonEncoder](
      toolName: ToolName,
      toolDescription: String,
      input: Json.Obj,
      output: Option[Json.Obj],
      toolMetadata: ToolMetadata
  )(run: (I, ToolExecutionContext) => ZIO[R, E, O]): Tool[R, I, E, O] =
    new Tool[R, I, E, O]:
      val name         = toolName
      val description  = toolDescription
      val inputSchema  = input
      val outputSchema = output
      val metadata     = toolMetadata

      def decodeInput(json: Json): Either[AgentError.ToolInputInvalid, I] =
        json.toJson.fromJson[I].left.map(AgentError.ToolInputInvalid(name.value, _))

      def execute(value: I, context: ToolExecutionContext): ZIO[R, E, O] = run(value, context)

      def encodeOutput(value: O): Either[AgentError.ToolExecutionFailed, Json] =
        value.toJsonAST.left.map(AgentError.ToolExecutionFailed(name.value, _))

/** 异构工具注册表中的受控擦除形式，不暴露 `Any` 或强制类型转换。 */
trait RegisteredTool:
  /** 返回擦除类型参数后仍可安全公开的模型工具定义。 */
  def definition: ToolDefinition

  /** 返回 Runtime 授权和重试所需的元数据。 */
  def metadata: ToolMetadata

  /** 在统一 JSON 边界完成“解码→执行→编码”。
    * @param arguments
    *   模型生成的不可信 JSON 参数
    * @param context
    *   Runtime 生成的可信调用上下文
    */
  def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult]

object RegisteredTool:
  /** 在装配阶段捕获工具的 ZIO 环境，运行阶段只处理统一 JSON 边界。 这让工具实现仍然保持 `ZIO[R, E, O]`，同时允许异构注册。
    */
  def make[R, I, E <: AgentError, O](tool: Tool[R, I, E, O]): ZIO[R, Nothing, RegisteredTool] =
    ZIO.environment[R].map { environment =>
      new RegisteredTool:
        val definition =
          ToolDefinition(tool.name.value, tool.description, tool.inputSchema, tool.outputSchema)
        val metadata = tool.metadata

        def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
          for
            input  <- ZIO.fromEither(tool.decodeInput(arguments))
            output <- tool.execute(input, context).provideEnvironment(environment)
            json   <- ZIO.fromEither(tool.encodeOutput(output))
          yield ToolResult(json)
    }

trait RegisteredToolRegistry:
  /** 按显式白名单返回定义；空集合必须返回空，体现默认拒绝。 */
  def definitions(allowed: Set[ToolName]): UIO[Chunk[ToolDefinition]]

  /** 按类型化名称查找工具；未知名称返回稳定 ToolNotFound。 */
  def get(name: ToolName): IO[AgentError.ToolNotFound, RegisteredTool]

object RegisteredToolRegistry:
  /** 从已捕获环境的工具构建只读注册表。
    *
    * 重复名称必须在应用装配阶段失败，不能依赖 `Map.toMap` 的最后写入覆盖：否则部署顺序会悄悄改变实际执行的工具实现， 审批策略、Schema 与审计记录也可能指向不同代码。
    *
    * @param tools
    *   工具集合
    * @return
    *   名称唯一的注册表；重复时返回不包含工具参数或业务正文的 typed configuration error
    */
  def make(tools: Iterable[RegisteredTool]): IO[AgentError.InvalidConfiguration, RegisteredToolRegistry] =
    val materialized = tools.toList
    val duplicates   = materialized
      .groupMapReduce(_.definition.name)(_ => 1)(_ + _)
      .collect { case (name, count) if count > 1 => name }
      .toList
      .sorted
    if duplicates.nonEmpty then
      ZIO.fail(AgentError.InvalidConfiguration(s"重复工具名称: ${duplicates.mkString(",")}"))
    else
      val byName = materialized.iterator.map(tool => ToolName(tool.definition.name) -> tool).toMap
      ZIO.succeed(new RegisteredToolRegistry:
        def definitions(allowed: Set[ToolName]): UIO[Chunk[ToolDefinition]] =
          ZIO.succeed {
            val selected =
              if allowed.isEmpty then Map.empty else byName.filter((name, _) => allowed.contains(name))
            Chunk.fromIterable(selected.toList.sortBy(_._1.value).map(_._2.definition))
          }

        def get(name: ToolName): IO[AgentError.ToolNotFound, RegisteredTool] =
          ZIO.fromOption(byName.get(name)).orElseFail(AgentError.ToolNotFound(name.value)))

  /** 把启动期校验提升为 ZLayer，供 `ZLayer.make` 和应用入口直接组合。 */
  def fromTools(
      tools: Iterable[RegisteredTool]
  ): Layer[AgentError.InvalidConfiguration, RegisteredToolRegistry] =
    ZLayer.fromZIO(make(tools))
