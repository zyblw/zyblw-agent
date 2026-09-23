package com.zyblw.agent.rag

import zio.test.*

object PaddleOcrVlDocumentSpec extends ZIOSpecDefault:

  private val layoutJson =
    """[
      |  {
      |    "prunedResult": {
      |      "parsing_res_list": [
      |        {"block_label":"header","block_content":"脏腑经络学说","block_bbox":[1,2,30,12],"block_id":1,"block_order":1},
      |        {"block_label":"number","block_content":"12","block_bbox":[80,90,100,100],"block_id":2,"block_order":2},
      |        {"block_label":"doc_title","block_content":"# 脏腑经络学说","block_bbox":[10,20,80,40],"block_id":3,"block_order":3},
      |        {"block_label":"content","block_content":"目录 …… 1","block_bbox":[10,50,80,70],"block_id":4,"block_order":4},
      |        {"block_label":"paragraph_title","block_content":"#### 疟与经络","block_bbox":[10,80,90,100],"block_id":5,"block_order":5},
      |        {"block_label":"text","block_content":"疟邪客于少阳。","block_bbox":[10,110,120,140],"block_id":6,"block_order":6},
      |        {"block_label":"image","block_content":"<img src=\"https://example.bcebos.com/a.png\" />","block_bbox":[1,1,2,2],"block_id":7,"block_order":7},
      |        {"block_label":"figure_title","block_content":"图 1 经络示意","block_bbox":[10,150,80,160],"block_id":8,"block_order":8}
      |      ]
      |    }
      |  },
      |  {
      |    "prunedResult": {
      |      "parsing_res_list": [
      |        {"block_label":"table","block_content":"| 穴 | 归经 |\n| --- | --- |\n| 大椎 | 督脉 |","block_bbox":[4,8,90,40],"block_id":1,"block_order":1}
      |      ]
      |    }
      |  }
      |]""".stripMargin

  private val markdown =
    """# 脏腑经络学说
      |
      |## 疟与经络
      |
      |疟邪客于少阳。
      |""".stripMargin

  private val baiduJson =
    """{
      |  "file_name": "示例.pdf",
      |  "pages": [
      |    {
      |      "page_num": 0,
      |      "meta": {"page_width": 600, "page_height": 800},
      |      "layouts": [
      |        {"layout_id": "L1", "text": "买卖合同", "position": [10, 20, 30, 12], "type": "title"},
      |        {"layout_id": "L2", "text": "", "position": [8, 40, 100, 60], "type": "table"},
      |        {"layout_id": "L3", "text": "1", "position": [1, 1, 8, 8], "type": "number"}
      |      ],
      |      "tables": [
      |        {"layout_id": "L2", "markdown": "| 商品 | 数量 |\n| --- | --- |\n| 服务器 | 1 |"}
      |      ]
      |    }
      |  ]
      |}""".stripMargin

  private val numberedHeadingsJson =
    """[{
      |  "prunedResult": {
      |    "parsing_res_list": [
      |      {"block_label":"doc_title","block_content":"中医之逻辑方法论","block_order":1},
      |      {"block_label":"paragraph_title","block_content":"第一章 人体逻辑的构建","block_order":2},
      |      {"block_label":"paragraph_title","block_content":"第一节 孤立主义与系统的建立。","block_order":3},
      |      {"block_label":"paragraph_title","block_content":"一、西医的孤立主义：","block_order":4},
      |      {"block_label":"paragraph_title","block_content":"（一）器官切割","block_order":5},
      |      {"block_label":"text","block_content":"正文","block_order":6}
      |    ]
      |  }
      |}]""".stripMargin

  def spec = suite("PaddleOCR-VL 文档解码")(
    test("官网页数组保留页码坐标，并用 Markdown 纠正标题层级") {
      val parsed = PaddleOcrVlDocument.decode(layoutJson, Some(markdown)).toOption.get
      val titles = parsed.sections.map(section => section.level -> section.title)
      val body   = parsed.blocks.find(_.text.contains("疟邪")).get
      val table  = parsed.blocks.find(_.kind == DocumentBlockKind.Table).get
      assertTrue(
        parsed.pageCount == 2,
        titles == zio.Chunk(1 -> "脏腑经络学说", 2 -> "目录", 2 -> "疟与经络"),
        parsed.sections
          .find(_.title == "疟与经络")
          .get
          .parentId
          .contains(
            parsed.sections.find(_.title == "脏腑经络学说").get.id
          ),
        body.origins.head.pageNumber == 1,
        body.origins.head.boundingBox.exists(box =>
          box.left == 10 && box.right == 120 && box.origin == DocumentCoordinateOrigin.TopLeft
        ),
        table.origins.head.pageNumber == 2,
        !parsed.blocks.exists(_.text == "12"),
        !parsed.blocks.exists(_.text.contains("bcebos.com")),
        parsed.blocks.exists(block => block.kind == DocumentBlockKind.Picture && block.text == "图 1 经络示意"),
        parsed.figures.map(_.sourceUrl) == zio.Chunk("https://example.bcebos.com/a.png"),
        parsed.sections.find(_.title == "疟与经络").get.pageEnd.contains(2)
      )
    },
    test("百度 parse_result 使用 0 基页码和宽高坐标，表格取 markdown") {
      val parsed = PaddleOcrVlDocument.decode(baiduJson, None).toOption.get
      val box    = parsed.blocks.head.origins.head.boundingBox.get
      assertTrue(
        parsed.sections.head.title == "买卖合同",
        parsed.sections.head.pageStart.contains(1),
        parsed.blocks.exists(_.text.contains("服务器")),
        box.left == 8,
        box.top == 40,
        box.right == 108,
        box.bottom == 100,
        box.pageWidth.contains(600)
      )
    },
    test("Markdown 标题标点不一致时仍恢复层级，缺失时按中文编号兜底") {
      val outline =
        """# 中医之逻辑方法论
          |## 第一章 人体逻辑的构建
          |### 第一节 孤立主义与系统的建立
          |""".stripMargin
      val parsed = PaddleOcrVlDocument.decode(numberedHeadingsJson, Some(outline)).toOption.get
      assertTrue(
        parsed.sections.map(section => section.level -> section.title) == zio.Chunk(
          1 -> "中医之逻辑方法论",
          2 -> "第一章 人体逻辑的构建",
          3 -> "第一节 孤立主义与系统的建立。",
          4 -> "一、西医的孤立主义：",
          5 -> "（一）器官切割"
        ),
        parsed.sections(2).parentId.contains(parsed.sections(1).id),
        parsed.sections(3).parentId.contains(parsed.sections(2).id),
        parsed.sections(4).parentId.contains(parsed.sections(3).id)
      )
    },
    test("空行后的短文本并进标题，编号小节仍单独成块") {
      val json =
        """[{
          |  "prunedResult": {
          |    "parsing_res_list": [
          |      {"block_label":"paragraph_title","block_content":"## 一、脏腑经络学说是中医学的","block_order":1},
          |      {"block_label":"text","block_content":"理论核心","block_order":2},
          |      {"block_label":"text","block_content":"脏腑与经络相互为用。","block_order":3},
          |      {"block_label":"paragraph_title","block_content":"## （九）心包与手厥阴经（附：膻中）","block_order":4},
          |      {"block_label":"text","block_content":"1. 心包的解剖及生理","block_order":5}
          |    ]
          |  }
          |}]""".stripMargin
      val markdown =
        """## 一、脏腑经络学说是中医学的
          |
          |理论核心
          |
          |脏腑与经络相互为用。
          |
          |## （九）心包与手厥阴经（附：膻中）
          |
          |1. 心包的解剖及生理
          |""".stripMargin
      val parsed = PaddleOcrVlDocument.decode(json, Some(markdown)).toOption.get
      assertTrue(
        parsed.sections.map(section => section.level -> section.title) == zio.Chunk(
          2 -> "一、脏腑经络学说是中医学的理论核心",
          2 -> "（九）心包与手厥阴经（附：膻中）"
        ),
        !parsed.blocks.exists(_.text == "理论核心"),
        parsed.blocks.exists(_.text == "脏腑与经络相互为用。"),
        parsed.blocks.exists(_.text == "1. 心包的解剖及生理")
      )
    },
    test("折行标题在 JSON 能盖住两行时合成一条") {
      val json =
        """[{
          |  "prunedResult": {
          |    "parsing_res_list": [
          |      {"block_label":"paragraph_title","block_content":"一、脏腑经络学说是中医学的理论核心","block_order":1},
          |      {"block_label":"text","block_content":"脏腑与经络相互为用。","block_order":2}
          |    ]
          |  }
          |}]""".stripMargin
      val markdown =
        """## 一、脏腑经络学说是中医学的
          |理论核心
          |
          |脏腑与经络相互为用。
          |""".stripMargin
      val parsed = PaddleOcrVlDocument.decode(json, Some(markdown)).toOption.get
      assertTrue(
        parsed.sections.map(section => section.level -> section.title) == zio.Chunk(
          2 -> "一、脏腑经络学说是中医学的理论核心"
        )
      )
    },
    test("无法识别的 JSON 不会被当成正文") {
      assertTrue(PaddleOcrVlDocument.decode("{\"error_code\":0}", None).isLeft)
    }
  )
