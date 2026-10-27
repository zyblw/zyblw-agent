package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

enum SideEffect derives JsonCodec:
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

/** 进程死后对未结算工具账本的恢复策略。与部署侧 [[ToolRetryPolicy]]（同一次在线执行的 429/timeout）正交。 */
enum ToolRecoveryPolicy:
  /** 只读；崩溃后可用同一 callId 再执行。 */
  case ReplaySafe

  /** 业务已保证幂等；恢复可重放同一意图。 */
  case Idempotent

  /** 崩溃后不得自动重放；Running 转为 Unknown 并暂停。人工 `forceRetry` 仍可显式重放。 */
  case NeverReplay

  /** 破坏性操作；即使历史上批准过，恢复仍须再次人工确认。 */
  case RequiresApproval

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
  *   副作用类型；同时推导在线可重试性与崩溃恢复策略，二者不可互相替代
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
  /** 同一次在线执行是否允许 `ToolRetryPolicy.IdempotentOnly` 对 429/timeout 热重试，不决定进程死后能否重放。
    *
    * `TransactionalOutboxWrite` 的可重试性来自业务幂等记录，而不是 outbox 本身；普通工具不得只修改枚举值来冒充可靠写入。
    */
  def onlineRetryable: Boolean =
    Set(SideEffect.None, SideEffect.IdempotentWrite, SideEffect.TransactionalOutboxWrite).contains(sideEffect)

  /** 进程死后对 Running/Unknown 账本的恢复策略，由 [[sideEffect]] 推导，不受部署 `ToolRetryPolicy` 影响。 */
  def recoveryPolicy: ToolRecoveryPolicy = sideEffect match
    case SideEffect.None                                                  => ToolRecoveryPolicy.ReplaySafe
    case SideEffect.IdempotentWrite | SideEffect.TransactionalOutboxWrite => ToolRecoveryPolicy.Idempotent
    case SideEffect.NonIdempotentWrite                                    => ToolRecoveryPolicy.NeverReplay
    case SideEffect.Destructive => ToolRecoveryPolicy.RequiresApproval

  /** ReplaySafe / Idempotent 才允许恢复时自动再执行；NeverReplay / RequiresApproval 必须暂停。 */
  def mayReplayAfterCrash: Boolean = recoveryPolicy match
    case ToolRecoveryPolicy.ReplaySafe | ToolRecoveryPolicy.Idempotent        => true
    case ToolRecoveryPolicy.NeverReplay | ToolRecoveryPolicy.RequiresApproval =>
      false

  /** 只有显式声明 ConflictAware 且至少给出一个冲突组的工具才可进入并行批次。 空冲突组不能理解为“与任何资源都不冲突”，因为那会把漏声明静默升级成并发安全承诺。
    */
  def conflictAwareParallel: Boolean =
    parallelism == ToolParallelism.ConflictAware && conflictAccesses.nonEmpty

object ToolMetadata:
  val RedactedPlaceholder: String = "[REDACTED]"

  /** 递归脱敏 JSON 对象中的敏感字段名，供输入轨迹记录和工具输出上下文共用。 */
  def redactJson(json: Json, sensitiveFields: Set[String]): Json =
    if sensitiveFields.isEmpty then json
    else
      json match
        case Json.Obj(fields) =>
          Json.Obj(
            fields.map { case (key, value) =>
              if sensitiveFields.contains(key) then key -> Json.Str(RedactedPlaceholder)
              else key                                  -> redactJson(value, sensitiveFields)
            }
          )
        case Json.Arr(elements) =>
          Json.Arr(elements.map(redactJson(_, sensitiveFields)))
        case other => other

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

  /** 编码尚未执行敏感字段脱敏的原始 JSON 输出，供 RegisteredTool 先执行 outputSchema 校验再统一脱敏。 */
  private[agent] def encodeRawOutput(output: O): Either[AgentError.ToolExecutionFailed, Json] =
    encodeOutput(output)

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
        for
          _ <- JsonSchemaInstanceValidator
            .validate(inputSchema, json)
            .left
            .map(AgentError.ToolInputInvalid(name.value, _))
          decoded <- json.toJson.fromJson[I].left.map(AgentError.ToolInputInvalid(name.value, _))
        yield decoded

      def execute(value: I, context: ToolExecutionContext): ZIO[R, E, O] = run(value, context)

      override private[agent] def encodeRawOutput(value: O): Either[AgentError.ToolExecutionFailed, Json] =
        for
          ast <- value.toJsonAST.left.map(AgentError.ToolExecutionFailed(name.value, _))
          _   <- outputSchema match
            case Some(schema) =>
              JsonSchemaInstanceValidator
                .validate(schema, ast)
                .left
                .map(AgentError.ToolExecutionFailed(name.value, _))
            case None => Right(())
        yield ast

      def encodeOutput(value: O): Either[AgentError.ToolExecutionFailed, Json] =
        encodeRawOutput(value).map(ToolMetadata.redactJson(_, metadata.sensitiveOutputFields))

/** 轻量级确定性 JSON Schema 实例约束校验器，在类型解码前后强制执行数值、长度、枚举、正则、组合约束和附加属性边界； 遇到未实现的关键字（如 `$ref`）时 fail-closed
  * 返回错误，绝不把未执行校验误判为通过。
  */
private[agent] object JsonSchemaInstanceValidator:
  private val SupportedKeywords: Set[String] = Set(
    "$schema",
    "$id",
    "title",
    "description",
    "default",
    "examples",
    "readOnly",
    "writeOnly",
    "deprecated",
    "format",
    "$defs",
    "definitions",
    "type",
    "enum",
    "const",
    "properties",
    "required",
    "additionalProperties",
    "minProperties",
    "maxProperties",
    "items",
    "minItems",
    "maxItems",
    "uniqueItems",
    "minLength",
    "maxLength",
    "pattern",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "multipleOf",
    "anyOf",
    "oneOf",
    "allOf",
    "not"
  )

  def validate(schema: Json.Obj, instance: Json, path: String = "$"): Either[String, Unit] =
    for
      _ <- validateSchemaSupport(schema, path)
      _ <- validateChecked(schema, instance, path)
    yield ()

  private def validateSchemaSupport(schema: Json.Obj, path: String): Either[String, Unit] =
    val fields      = schema.fields.toMap
    val unsupported = fields.keySet -- SupportedKeywords
    if unsupported.nonEmpty then
      Left(s"$path 包含不受支持的 JSON Schema 关键字: ${unsupported.toList.sorted.mkString(",")}")
    else
      def checkBranches(keyword: String): Either[String, Unit] =
        fields.get(keyword) match
          case Some(Json.Arr(branches)) =>
            val subSchemas = branches.collect { case obj: Json.Obj => obj }
            if subSchemas.isEmpty || subSchemas.length != branches.length then
              Left(s"$path $keyword 分支必须是有效的 JSON Schema 对象")
            else
              subSchemas.zipWithIndex.foldLeft[Either[String, Unit]](Right(())) { case (acc, (sub, idx)) =>
                acc.flatMap(_ => validateSchemaSupport(sub, s"$path.$keyword[$idx]"))
              }
          case Some(_) => Left(s"$path $keyword 必须是数组")
          case None    => Right(())

      def checkDefsMap(keyword: String): Either[String, Unit] =
        fields.get(keyword) match
          case Some(Json.Obj(defs)) =>
            defs.foldLeft[Either[String, Unit]](Right(())) {
              case (acc, (defName, sub: Json.Obj)) =>
                acc.flatMap(_ => validateSchemaSupport(sub, s"$path.$keyword.$defName"))
              case (acc, _) => acc
            }
          case _ => Right(())

      for
        _ <- checkBranches("anyOf")
        _ <- checkBranches("oneOf")
        _ <- checkBranches("allOf")
        _ <- fields.get("not") match
          case Some(sub: Json.Obj) => validateSchemaSupport(sub, s"$path.not")
          case Some(_)             => Left(s"$path not 必须是 JSON Schema 对象")
          case None                => Right(())
        _ <- fields.get("properties") match
          case Some(Json.Obj(props)) =>
            props.foldLeft[Either[String, Unit]](Right(())) {
              case (acc, (propName, sub: Json.Obj)) =>
                acc.flatMap(_ => validateSchemaSupport(sub, s"$path.$propName"))
              case (_, (propName, _)) =>
                Left(s"$path.properties.$propName 必须是 JSON Schema 对象")
            }
          case Some(_) => Left(s"$path properties 必须是对象")
          case None    => Right(())
        _ <- fields.get("additionalProperties") match
          case Some(sub: Json.Obj) => validateSchemaSupport(sub, s"$path.additionalProperties")
          case Some(Json.Bool(_))  => Right(())
          case Some(_)             => Left(s"$path additionalProperties 必须是布尔值或 JSON Schema 对象")
          case None                => Right(())
        _ <- fields.get("items") match
          case Some(sub: Json.Obj) => validateSchemaSupport(sub, s"$path.items")
          case Some(_)             => Left(s"$path items 必须是 JSON Schema 对象")
          case None                => Right(())
        _ <- checkDefsMap("$defs")
        _ <- checkDefsMap("definitions")
      yield ()

  private def validateChecked(schema: Json.Obj, instance: Json, path: String): Either[String, Unit] =
    val fields = schema.fields.toMap
    for
      _ <- validateType(fields, instance, path)
      _ <- validateEnum(fields, instance, path)
      _ <- validateConst(fields, instance, path)
      _ <- validateCombinators(fields, instance, path)
      _ <- instance match
        case obj: Json.Obj => validateObject(fields, obj, path)
        case arr: Json.Arr => validateArray(fields, arr, path)
        case str: Json.Str => validateString(fields, str.value, path)
        case num: Json.Num => validateNumber(fields, BigDecimal(num.value), path)
        case _             => Right(())
    yield ()

  private def validateType(
      fields: Map[String, Json],
      instance: Json,
      path: String
  ): Either[String, Unit] =
    val declaredTypes = fields.get("type") match
      case Some(Json.Str(single)) => Set(single)
      case Some(Json.Arr(many))   => many.collect { case Json.Str(value) => value }.toSet
      case _                      => Set.empty[String]
    if declaredTypes.isEmpty then Right(())
    else
      val matches = declaredTypes.exists(matchesSingleType(_, instance))
      Either.cond(matches, (), s"$path 类型不匹配，期望 ${declaredTypes.mkString("|")}")

  private def matchesSingleType(expected: String, instance: Json): Boolean =
    (expected, instance) match
      case ("object", _: Json.Obj)   => true
      case ("array", _: Json.Arr)    => true
      case ("string", _: Json.Str)   => true
      case ("boolean", _: Json.Bool) => true
      case ("null", Json.Null)       => true
      case ("number", _: Json.Num)   => true
      case ("integer", Json.Num(v))  =>
        try BigDecimal(v).isWhole
        catch case _: NumberFormatException => false
      case _ => false

  private def validateEnum(
      fields: Map[String, Json],
      instance: Json,
      path: String
  ): Either[String, Unit] =
    fields.get("enum") match
      case Some(Json.Arr(allowed)) if !allowed.contains(instance) =>
        Left(s"$path 值不在允许的 enum 集合中")
      case _ => Right(())

  private def validateConst(
      fields: Map[String, Json],
      instance: Json,
      path: String
  ): Either[String, Unit] =
    fields.get("const") match
      case Some(expected) if instance != expected =>
        Left(s"$path 值不满足 const 约束")
      case _ => Right(())

  private def validateCombinators(
      fields: Map[String, Json],
      instance: Json,
      path: String
  ): Either[String, Unit] =
    for
      _ <- fields.get("anyOf") match
        case Some(Json.Arr(branches)) =>
          val subSchemas = branches.collect { case obj: Json.Obj => obj }
          if subSchemas.exists(sub => validateChecked(sub, instance, path).isRight) then Right(())
          else Left(s"$path 不满足 anyOf 中的任何分支约束")
        case _ => Right(())
      _ <- fields.get("oneOf") match
        case Some(Json.Arr(branches)) =>
          val subSchemas = branches.collect { case obj: Json.Obj => obj }
          val matched    = subSchemas.count(sub => validateChecked(sub, instance, path).isRight)
          if matched == 1 then Right(())
          else Left(s"$path 不满足 oneOf 唯一分支约束")
        case _ => Right(())
      _ <- fields.get("allOf") match
        case Some(Json.Arr(branches)) =>
          val subSchemas = branches.collect { case obj: Json.Obj => obj }
          subSchemas.foldLeft[Either[String, Unit]](Right(())) { (acc, sub) =>
            acc.flatMap(_ => validateChecked(sub, instance, path))
          }
        case _ => Right(())
      _ <- fields.get("not") match
        case Some(sub: Json.Obj) =>
          if validateChecked(sub, instance, path).isLeft then Right(())
          else Left(s"$path 命中了 not 禁止的约束")
        case _ => Right(())
    yield ()

  private def allowsExplicitNull(schema: Json.Obj): Boolean =
    val fields = schema.fields.toMap
    val byType = fields.get("type").exists {
      case Json.Str("null") => true
      case Json.Arr(types)  => types.contains(Json.Str("null"))
      case _                => false
    }
    val byEnum =
      fields.get("enum").exists { case Json.Arr(items) => items.contains(Json.Null); case _ => false }
    val byConst = fields.get("const").contains(Json.Null)
    val byAnyOf = fields.get("anyOf").exists {
      case Json.Arr(branches) => branches.collect { case o: Json.Obj => o }.exists(allowsExplicitNull)
      case _                  => false
    }
    val byOneOf = fields.get("oneOf").exists {
      case Json.Arr(branches) => branches.collect { case o: Json.Obj => o }.count(allowsExplicitNull) == 1
      case _                  => false
    }
    val byAllOf = fields.get("allOf").exists {
      case Json.Arr(branches) =>
        val subSchemas = branches.collect { case o: Json.Obj => o }
        subSchemas.nonEmpty && subSchemas.exists(allowsExplicitNull)
      case _ => false
    }
    (byType || byEnum || byConst || byAnyOf || byOneOf || byAllOf) && validateChecked(
      schema,
      Json.Null,
      "$"
    ).isRight

  private def validateObject(
      fields: Map[String, Json],
      instance: Json.Obj,
      path: String
  ): Either[String, Unit] =
    val instanceMap = instance.fields.toMap
    val minPropsOk  = numericInt(fields.get("minProperties")).forall(instanceMap.size >= _)
    val maxPropsOk  = numericInt(fields.get("maxProperties")).forall(instanceMap.size <= _)
    if !minPropsOk then Left(s"$path 对象属性数小于 minProperties 约束")
    else if !maxPropsOk then Left(s"$path 对象属性数大于 maxProperties 约束")
    else
      val requiredSet = fields.get("required") match
        case Some(Json.Arr(items)) => items.collect { case Json.Str(name) => name }.toSet
        case _                     => Set.empty[String]
      val propertiesMap = fields.get("properties") match
        case Some(Json.Obj(props)) =>
          props.collect { case (name, sub: Json.Obj) => name -> sub }.toMap
        case _ => Map.empty[String, Json.Obj]
      val missingRequired = requiredSet.filter { reqName =>
        instanceMap.get(reqName) match
          case None            => true
          case Some(Json.Null) => !propertiesMap.get(reqName).exists(allowsExplicitNull)
          case Some(_)         => false
      }
      if missingRequired.nonEmpty then Left(s"$path 缺少必填字段: ${missingRequired.toList.sorted.mkString(",")}")
      else
        val disallowAdditional = fields.get("additionalProperties").contains(Json.Bool(false))
        val additionalSchema   = fields.get("additionalProperties").collect { case obj: Json.Obj => obj }
        val unexpectedFields   =
          if disallowAdditional then instanceMap.keySet -- propertiesMap.keySet
          else Set.empty[String]
        if unexpectedFields.nonEmpty then
          Left(s"$path 包含未声明字段: ${unexpectedFields.toList.sorted.mkString(",")}")
        else
          instance.fields.foldLeft[Either[String, Unit]](Right(())) { case (acc, (fieldName, fieldValue)) =>
            acc.flatMap { _ =>
              propertiesMap.get(fieldName) match
                case Some(subSchema) =>
                  validate(subSchema, fieldValue, s"$path.$fieldName")
                case None =>
                  additionalSchema match
                    case Some(addSchema) => validate(addSchema, fieldValue, s"$path.$fieldName")
                    case None            => Right(())
            }
          }

  private def validateArray(
      fields: Map[String, Json],
      instance: Json.Arr,
      path: String
  ): Either[String, Unit] =
    val length   = instance.elements.length
    val minOk    = numericInt(fields.get("minItems")).forall(length >= _)
    val maxOk    = numericInt(fields.get("maxItems")).forall(length <= _)
    val uniqueOk = !fields.get("uniqueItems").contains(Json.Bool(true)) ||
      instance.elements.distinct.length == length
    if !minOk then Left(s"$path 数组长度小于 minItems 约束")
    else if !maxOk then Left(s"$path 数组长度大于 maxItems 约束")
    else if !uniqueOk then Left(s"$path 数组元素不满足 uniqueItems 约束")
    else
      fields.get("items") match
        case Some(itemSchema: Json.Obj) =>
          instance.elements.zipWithIndex.foldLeft[Either[String, Unit]](Right(())) {
            case (acc, (element, idx)) =>
              acc.flatMap(_ => validate(itemSchema, element, s"$path[$idx]"))
          }
        case _ => Right(())

  private def validateString(
      fields: Map[String, Json],
      value: String,
      path: String
  ): Either[String, Unit] =
    val length = value.codePointCount(0, value.length)
    val minOk  = numericInt(fields.get("minLength")).forall(length >= _)
    val maxOk  = numericInt(fields.get("maxLength")).forall(length <= _)
    if !minOk then Left(s"$path 字符串长度小于 minLength 约束")
    else if !maxOk then Left(s"$path 字符串长度大于 maxLength 约束")
    else
      fields.get("pattern") match
        case Some(Json.Str(regex)) =>
          val matched =
            try java.util.regex.Pattern.compile(regex).matcher(value).find()
            catch case _: Throwable => false
          Either.cond(matched, (), s"$path 字符串不匹配 pattern 约束")
        case _ => Right(())

  private def validateNumber(
      fields: Map[String, Json],
      value: BigDecimal,
      path: String
  ): Either[String, Unit] =
    val minOk        = numericDecimal(fields.get("minimum")).forall(value >= _)
    val maxOk        = numericDecimal(fields.get("maximum")).forall(value <= _)
    val exMinOk      = numericDecimal(fields.get("exclusiveMinimum")).forall(value > _)
    val exMaxOk      = numericDecimal(fields.get("exclusiveMaximum")).forall(value < _)
    val multipleOfOk =
      numericDecimal(fields.get("multipleOf")).forall(step => step > 0 && (value % step) == 0)
    if !minOk then Left(s"$path 数值小于 minimum 约束")
    else if !maxOk then Left(s"$path 数值大于 maximum 约束")
    else if !exMinOk then Left(s"$path 数值不满足 exclusiveMinimum 约束")
    else if !exMaxOk then Left(s"$path 数值不满足 exclusiveMaximum 约束")
    else if !multipleOfOk then Left(s"$path 数值不满足 multipleOf 约束")
    else Right(())

  private def numericInt(json: Option[Json]): Option[Int] =
    numericDecimal(json).flatMap(d => if d.isValidInt then Some(d.toIntExact) else None)

  private def numericDecimal(json: Option[Json]): Option[BigDecimal] =
    json match
      case Some(Json.Num(v)) =>
        try Some(BigDecimal(v))
        catch case _: NumberFormatException => None
      case _ => None

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
            _ <- ZIO.fromEither(
              JsonSchemaInstanceValidator
                .validate(tool.inputSchema, arguments)
                .left
                .map(AgentError.ToolInputInvalid(tool.name.value, _))
            )
            input   <- ZIO.fromEither(tool.decodeInput(arguments))
            output  <- tool.execute(input, context).provideEnvironment(environment)
            rawJson <- ZIO.fromEither(tool.encodeRawOutput(output))
            _       <- tool.outputSchema match
              case Some(schema) =>
                ZIO.fromEither(
                  JsonSchemaInstanceValidator
                    .validate(schema, rawJson)
                    .left
                    .map(AgentError.ToolExecutionFailed(tool.name.value, _))
                )
              case None => ZIO.unit
            redacted = ToolMetadata.redactJson(rawJson, tool.metadata.sensitiveOutputFields)
          yield ToolResult(redacted)
    }

trait RegisteredToolRegistry:
  /** 按显式白名单返回定义；空集合必须返回空，体现默认拒绝。 */
  def definitions(allowed: Set[ToolName]): UIO[Chunk[ToolDefinition]]

  /** 按类型化名称查找工具；未知名称返回稳定 ToolNotFound。 */
  def get(name: ToolName): IO[AgentError.ToolNotFound, RegisteredTool]

  /** 在任何模型调用或命令落库前，确认 Agent 白名单中的每个工具都已注册。
    *
    * 全局 `ToolPolicyConfig` 只能证明策略允许该名称；未注册的名称会让模型提出调用后才失败，并浪费一次付费请求。
    */
  def requireRegistered(allowed: Iterable[String]): IO[AgentError.InvalidConfiguration, Unit] =
    val names = allowed.iterator.map(ToolName(_)).toSet
    definitions(names).flatMap { visible =>
      val missing = names.map(_.value) -- visible.map(_.name).toSet
      ZIO
        .fail(
          AgentError.InvalidConfiguration(
            s"Agent 白名单包含未注册工具: ${missing.toList.sorted.mkString(",")}"
          )
        )
        .when(missing.nonEmpty)
        .unit
    }

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
