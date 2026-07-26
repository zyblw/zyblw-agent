package com.zyblw.agent.core

import zio.json.*
import zio.json.ast.Json

/** 结构化输出错误包含可定位的 JSON 路径，不向调用方只返回“解析失败”。 */
final case class StructuredOutputError(path: String, message: String, rawRetained: Boolean)

/** 将 JSON Schema 与 Scala 解码器绑定，避免 core 依赖运行时反射。 */
trait StructuredDecoder[A]:
  /** 返回提交给模型或本地校验器的 JSON Schema。 */
  def schema: Json.Obj

  /** 将模型原始文本解码为业务类型 `A`。
    * @param value
    *   模型返回的完整 JSON 文本，而不是流式增量片段
    * @return
    *   成功值或包含安全错误和可选原文的 `StructuredOutputError`
    */
  def decode(value: String): Either[StructuredOutputError, A]

object StructuredDecoder:
  /** 使用 zio-json 创建无反射结构化解码器。
    *
    * @param jsonSchema
    *   描述期望对象的 JSON Schema
    * @param retainRawOnError
    *   是否在错误中保留原文；原文可能包含隐私，生产默认关闭
    * @tparam A
    *   目标 Scala 类型，调用点必须提供 `JsonDecoder[A]`
    */
  def json[A: JsonDecoder](jsonSchema: Json.Obj, retainRawOnError: Boolean = false): StructuredDecoder[A] =
    new StructuredDecoder[A]:
      val schema: Json.Obj = jsonSchema

      /** 调用 zio-json 解码，并把字符串错误转换为框架稳定错误类型。 */
      def decode(value: String): Either[StructuredOutputError, A] =
        value.fromJson[A].left.map(details => StructuredOutputError("$", details, retainRawOnError))

final case class StructuredRepairPolicy(maxRepairs: Int = 1, retainRawOutput: Boolean = false):
  require(maxRepairs >= 0, "修复次数不能为负数")
