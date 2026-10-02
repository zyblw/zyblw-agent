package com.zyblw.agent.loaders

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

object PaddleOcrPipelineSpec extends ZIOSpecDefault:
  private val json =
    """[{"page_index":0,"page_count":2,"prunedResult":{"parsing_res_list":[{"block_id":7,"block_label":"doc_title","block_content":"甲书","block_order":0},{"block_id":8,"block_label":"paragraph_title","block_content":"太阳病","block_order":1},{"block_id":9,"block_label":"text","block_content":"太阳病的原文论述。","block_order":2,"block_bbox":[1,2,80,40]}]}},{"page_index":1,"page_count":2,"prunedResult":{"parsing_res_list":[{"block_id":10,"block_label":"table","block_content":"<table><tr><th>名称</th><th>归属</th></tr><tr><td rowspan='2'>甲</td><td>太阳</td></tr><tr><td>少阴</td></tr></table>","block_order":0,"block_bbox":[1,2,80,50]}]}}]"""
  private def temp[A](use: Path => Task[A]): Task[A] = ZIO.scoped {
    ZIO
      .acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("paddle-pipeline"))) { path =>
        ZIO.attemptBlocking {
          val stream = Files.walk(path)
          try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
          finally stream.close()
        }.orDie
      }
      .flatMap(use)
  }

  def spec = suite("Paddle artifact pipeline")(
    test("JSON restores zero-based pages, numeric IDs and HTML table spans; incomplete pages fail") {
      val result = PaddleOcrVlDocument.decode(json, Some("# 甲书\n## 太阳病"), Some(2)).toOption.get
      val table  = result.blocks.find(_.kind == DocumentBlockKind.Table).get
      assertTrue(
        result.pageCount == 2,
        result.blocks.flatMap(_.origins.map(_.pageNumber)).toSet == Set(1, 2),
        result.blocks.exists(_.origins.exists(_.blockId.exists(_.contains("9")))),
        table.text.contains("| 甲 | 太阳 |"),
        table.text.contains("| 甲 | 少阴 |"),
        PaddleOcrVlDocument.decode(json, expectedPageCount = Some(3)).isLeft,
        PaddleOcrVlDocument.decode(json.replace("\"page_index\":1", "\"page_index\":0")).isLeft,
        PaddleOcrVlDocument.decode(json.replace("\"page_count\":2", "\"page_count\":3")).isLeft
      )
    },
    test(
      "directory pairs original PDF/JSON/Markdown once, publishes both books, retrieves fair cited evidence and replays"
    ) {
      temp { root =>
        for
          _ <- ZIO.attemptBlocking {
            val pdf = new org.apache.pdfbox.pdmodel.PDDocument()
            try
              pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage())
              pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage())
              pdf.save(root.resolve("a.pdf").toFile)
              pdf.save(root.resolve("b.pdf").toFile)
            finally pdf.close()
            Files.writeString(root.resolve("a.paddle.json"), json)
            Files.writeString(root.resolve("b.paddle.json"), json.replace("甲书", "乙书"))
            Files.writeString(root.resolve("a.md"), "# 甲书\n## 太阳病")
            Files.writeString(root.resolve("b.md"), "# 乙书\n## 太阳病")
          }
          source = LocalDocumentDirectorySource(LocalDocumentDirectoryConfig(root))
          inputs   <- source.inputs.runCollect
          registry <- DocumentLoaderRegistry.make(Chunk(new PaddleOcrDocumentLoader))
          parsed   <- registry.load(inputs.head)
          index    <- InMemoryKnowledgeIndexStore.make
          trees    <- InMemoryStructureStore.make(index)
          model         = HashEmbedding(64)
          structureSpec = StructureBuildSpec(summary = Some(NodeSummarySpec("internal", "rule-v1")))
          indexer       = KnowledgeIndexer(
            DocumentStructureChunker(),
            model,
            index,
            structureIndexing = Some(StructureIndexing(trees, spec = structureSpec))
          )
          ingestion = DocumentIngestionService(registry, indexer)
          rerank    = new Reranker:
            def rerank(q: String, hits: Chunk[RetrievalHit], limit: Int) = ZIO.succeed(hits.take(limit))
          retriever = DefaultRetriever(
            model,
            index,
            rerank,
            structural = Some(StructuralRetrieval(trees, structureSpec))
          )
          app = RagApplication(ingestion, retriever, catalog = Some(KnowledgeIndexDirectory.inMemory(index)))
          outcomes <- app
            .ingestInputs(zio.stream.ZStream.fromChunk(inputs), TenantId("paddle"), Set("read"), "")
            .runCollect
          result <- app.retrieve(
            RagQuery(
              "甲书与乙书的太阳病论述",
              RetrievalScope(TenantId("paddle"), Set("read")),
              Some(4),
              recipe = Some(RetrievalRecipe.BookGrounded)
            )
          )
          denied <- app.retrieve(RagQuery("太阳病", RetrievalScope(TenantId("paddle"), Set("other")), Some(4)))
          replayInputs <- source.inputs.runCollect
          replay       <- app
            .ingestInputs(
              zio.stream.ZStream.fromChunk(replayInputs),
              TenantId("paddle"),
              Set("read"),
              ""
            )
            .runCollect
          _ <- ZIO.attemptBlocking(
            Files.writeString(root.resolve("a.paddle.json"), json.replace("原文论述", "更新的原文论述"))
          )
          updatedInput <- source.loadById(inputs.head.id).someOrFailException
          updated      <- app.ingestOne(
            DocumentIngestionRequest(updatedInput, TenantId("paddle"), Set("read"), "")
          )
          updatedResult = updated match
            case DocumentIngestionOutcome.Indexed(_, result) => Some(result)
            case _                                           => None
        yield assertTrue(
          inputs.length == 2,
          updatedResult.exists(_.manifest.build.version == 2),
          parsed.metadata.contains("originalSha256"),
          inputs.head.id == "local-" + KnowledgeIndexer.sha256("a.pdf").take(32),
          inputs.head.sourceRevision.exists(_.sha256.contains(parsed.metadata("originalSha256"))),
          outcomes
            .collect { case DocumentIngestionOutcome.Indexed(_, result) => result }
            .forall(r =>
              r.manifest.build.lineage.sourceMediaType == "application/pdf" && r.manifest.build.lineage.sourceSha256 != r.manifest.build.lineage.artifactSha256
            ),
          parsed.sourceUri.endsWith("a.pdf"),
          parsed.structure.exists(_.sections.exists(_.title == "太阳病")),
          outcomes.forall(_.isInstanceOf[DocumentIngestionOutcome.Indexed]),
          replay.forall(_.isInstanceOf[DocumentIngestionOutcome.Indexed]),
          result.hits.map(_.chunk.documentId).toSet == inputs.map(_.id).toSet,
          result.citations.map(_.id).distinct.length == result.citations.length,
          result.citations.forall(c =>
            c.chunkId.exists(id =>
              result.hits.exists(h =>
                h.chunk.id == id && h.chunk.sourceUri == c.sourceUri && h.chunk.displayText
                  .contains(c.excerpt)
              )
            )
          ),
          result.citations.exists(_.pageNumbers.contains(2)),
          denied.hits.isEmpty,
          result.diagnostics.structureSelections.nonEmpty
        )
      }
    },
    test("original PDF page count rejects truncated OCR before any index activation") {
      temp { root =>
        for
          _ <- ZIO.attemptBlocking {
            val pdf = new org.apache.pdfbox.pdmodel.PDDocument()
            try
              (1 to 3).foreach(_ => pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage()))
              pdf.save(root.resolve("a.pdf").toFile)
            finally pdf.close()
            Files.writeString(root.resolve("a.paddle.json"), json)
          }
          inputs <- LocalDocumentDirectorySource(LocalDocumentDirectoryConfig(root)).inputs.runCollect
          exit   <- (new PaddleOcrDocumentLoader).load(inputs.head).exit
        yield assertTrue(exit.isFailure)
      }
    },
    test("paired Markdown symlink and malformed UTF-8 are rejected") {
      temp { root =>
        for
          _ <- ZIO.attemptBlocking {
            Files.writeString(root.resolve("a.paddle.json"), json)
            Files.createSymbolicLink(root.resolve("a.md"), root.resolve("a.paddle.json"))
          }
          exit <- LocalDocumentDirectorySource(LocalDocumentDirectoryConfig(root)).inputs.runCollect.exit
          invalid = DocumentInput.fromBytes(
            "x",
            "book://x",
            "x.paddle.json",
            PaddleOcrDocumentLoader.MediaType,
            Chunk(0xc3.toByte, 0x28.toByte)
          )
          utf8 <- (new PaddleOcrDocumentLoader).load(invalid).exit
        yield assertTrue(exit.isFailure, utf8.isFailure)
      }
    }
  )
