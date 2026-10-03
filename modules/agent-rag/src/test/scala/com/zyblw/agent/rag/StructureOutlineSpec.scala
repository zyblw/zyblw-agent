package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import com.zyblw.agent.model.ChatModel
import zio.*
import zio.test.*

object StructureOutlineSpec extends ZIOSpecDefault:
  private def node(
      id: String,
      parent: Option[String],
      ordinal: Int,
      level: Int,
      title: String,
      summary: Option[String] = None
  ) =
    StructureNode(
      DocumentSection(id, parent, ordinal, level, title, Some(ordinal + 1), Some(ordinal + 2)),
      Chunk(s"b-$id"),
      Chunk(s"c-$id"),
      summary
    )

  private val binding  = StructureBinding("t", "s", "doc", "profile", 1, "ing", "rev", "a" * 64, "b" * 64)
  private val snapshot = StructureSnapshot(
    binding,
    StructureBuildSpec(),
    Chunk(
      node("book", None, 0, 1, "中医之逻辑方法论", Some("中医之逻辑方法论：包含 9 处论述。主要涵盖：插图")),
      node("ch1", Some("book"), 1, 2, "第一章 人体逻辑的构建", Some("第一章 人体逻辑的构建：包含 4 处论述。主要涵盖：在现代医学的版图上")),
      node("light", Some("ch1"), 2, 3, "（一）光能联系：太阳与人体的直接能量对话"),
      node("steam", Some("ch1"), 3, 3, "（二）蒸汽能联系：湿度与人体水液代谢"),
      node("ch2", Some("book"), 4, 2, "第二章 人体系统的构成")
    )
  )
  private def reply(text: String): ChatModel = new ChatModel:
    val provider                       = "test"
    def complete(request: ChatRequest) =
      ZIO.succeed(ChatResponse(AgentMessage.assistant(text), FinishReason.Stop, TokenUsage(1, 1)))

  def spec = suite("StructureOutline")(
    test("完整目录按原书顺序编号，带页码和规则摘要的正文提示") {
      val outline = StructureOutline.render(snapshot, 4000)
      assertTrue(
        outline.folded == 0,
        outline.text.linesIterator.next().startsWith("s1 中医之逻辑方法论 [p1-2]"),
        outline.text.contains("    s3 （一）光能联系"),
        outline.text.contains("— 在现代医学的版图上"),
        !outline.text.contains("包含 4 处论述"),
        outline.nodeId("s3").contains("light")
      )
    },
    test("预算不够时先去提示，再折叠深层，编号保持不变") {
      val outline = StructureOutline.render(snapshot, 80)
      assertTrue(
        outline.text.length <= 80,
        outline.folded == 2,
        outline.text.contains("s2 第一章 人体逻辑的构建 [p2-3] (+2)"),
        outline.nodeId("s5").contains("ch2"),
        !outline.text.contains("—")
      )
    },
    test("选章只接受目录内引用，空列表表示没有相关章节") {
      val outline = StructureOutline.render(snapshot, 4000)
      assertTrue(
        StructureOutline.parseSelection("""好的 {"sections":["s4","s3","s4","s99"]}""", outline, 3) == Right(
          Chunk("steam", "light")
        ),
        StructureOutline.parseSelection("""{"ids":["s5"]}""", outline, 3) == Right(Chunk("ch2")),
        StructureOutline.parseSelection("""{"sections":[]}""", outline, 3) == Right(Chunk.empty),
        StructureOutline.parseSelection("""{"sections":["s99"]}""", outline, 3).isLeft,
        StructureOutline.parseSelection("没有 JSON", outline, 3).isLeft
      )
    },
    test("模型一次读目录选章，返回原书节点 ID") {
      for
        chosen <- ModelStructureNavigation
          .outlineSelect(reply("""{"sections":["s3","s4"]}"""), "光能和蒸汽能的关系", snapshot, 2)
        bad <- ModelStructureNavigation.outlineSelect(reply("""{"sections":["x"]}"""), "光能", snapshot, 2).exit
      yield assertTrue(chosen == Chunk("light", "steam"), bad.isFailure)
    }
  )
