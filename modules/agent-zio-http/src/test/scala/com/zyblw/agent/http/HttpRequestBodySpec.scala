package com.zyblw.agent.http

import zio.*
import zio.http.*
import zio.test.*

/** 验证共享请求体读取器的字节上限、边界包含关系与严格 UTF-8 语义。 */
object HttpRequestBodySpec extends ZIOSpecDefault:

  /** 构造只用于读取 Body 的请求；路径和方法不会影响本组件。 */
  private def request(bytes: Chunk[Byte]): Request =
    Request.post(URL.root, Body.fromChunk(bytes))

  def spec = suite("HttpRequestBody")(
    test("刚好达到上限成功，多一个字节在 DTO 解码前失败") {
      val exact = Chunk.fromArray("12345678".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val over  = exact ++ Chunk.single('9'.toByte)
      for
        accepted <- HttpRequestBody.readJson(request(exact), maxBytes = 8L).either
        rejected <- HttpRequestBody.readJson(request(over), maxBytes = 8L).either
      yield assertTrue(
        accepted == Right("12345678"),
        rejected.left.exists(_.message.contains("不能超过 8 字节"))
      )
    },
    test("畸形 UTF-8 不会被替换字符静默修复") {
      val malformed = Chunk(0xc3.toByte, 0x28.toByte)
      for result <- HttpRequestBody.readJson(request(malformed), maxBytes = 8L).either
      yield assertTrue(result.left.exists(_.message == "JSON 请求体必须是合法 UTF-8"))
    },
    test("非法非正上限返回 typed 配置错误") {
      for result <- HttpRequestBody.readJson(request(Chunk.empty), maxBytes = 0L).either
      yield assertTrue(result.left.exists(_.message.contains("上限必须为正数")))
    }
  )
