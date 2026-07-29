package com.zyblw.agent.loaders

import com.zyblw.agent.rag.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*
import zio.test.*

object DoclingDocumentLoaderSpec extends ZIOSpecDefault:

  final private case class ObservedRequest(
      apiKey: Option[String],
      fileName: Option[String],
      fileBytes: Chunk[Byte],
      fields: Map[String, Chunk[String]]
  )

  private def bytes(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))

  private def input(content: Chunk[Byte] = bytes("%PDF-stub")): DocumentInput =
    DocumentInput.fromBytes(
      "pdf-guide",
      "knowledge://pdf-guide",
      "guide.pdf",
      "application/pdf",
      content,
      Map("title" -> "ZIO Guide")
    )

  private def config(
      port: Int,
      responseLimit: Int = 4096,
      inputLimit: Int = 4096
  ): DoclingDocumentLoaderConfig = DoclingDocumentLoaderConfig(
    baseUrl = s"http://127.0.0.1:$port",
    apiKey = Some("docling-test-secret"),
    maxInputBytes = inputLimit,
    maxResponseBytes = responseLimit,
    maxMarkdownCodePoints = 2000,
    doOcr = true,
    tableMode = DoclingTableMode.Accurate,
    pdfBackend = DoclingPdfBackend.DoclingParse,
    ocrLanguages = Chunk("zh", "en"),
    allowInsecureHttp = true
  )

  private def success(markdown: String): Response =
    Response.json(
      s"""{"document":{"md_content":${markdown.toJson}},"status":"success","processing_time":0.1,"errors":[]}"""
    )

  private def route(observed: Ref[Option[ObservedRequest]], response: UIO[Response]): Routes[Any, Response] =
    Routes(
      Method.POST / "v1" / "convert" / "file" -> handler { (request: Request) =>
        request.body.asMultipartForm
          .flatMap { form =>
            val file = form.get("files").collect { case FormField.Binary(_, data, _, _, fileName) =>
              fileName -> data
            }
            val textFields = form.formData.collect {
              case FormField.Simple(name, value)     => name -> value
              case FormField.Text(name, value, _, _) => name -> value
            }
            val grouped = textFields
              .groupBy(_._1)
              .view
              .mapValues(values => values.map(_._2))
              .toMap
            observed.set(
              Some(
                ObservedRequest(
                  request.rawHeader("X-Api-Key"),
                  file.flatMap(_._1),
                  file.fold(Chunk.empty[Byte])(_._2),
                  grouped
                )
              )
            ) *> response
          }
          .mapError(error => Response.internalServerError(error.getClass.getSimpleName))
      }
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DoclingDocumentLoader")(
    test("按 Docling v1 multipart 契约把 PDF 转成 Markdown 并保持身份") {
      for
        observed <- Ref.make(Option.empty[ObservedRequest])
        result   <- (for
          _    <- TestServer.addRoutes(route(observed, ZIO.succeed(success("# ZIO\n\n## Runtime\n\nFiber"))))
          port <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          loader = DoclingDocumentLoader(client, config(port))
          loaded <- loader.load(input())
          sent   <- observed.get
        yield (loaded, sent, loader)).provide(Client.default, TestServer.default)
        (loaded, sent, loader) = result
      yield assertTrue(
        loaded.id == "pdf-guide",
        loaded.sourceUri == "knowledge://pdf-guide",
        loaded.representation == DocumentRepresentation.Markdown,
        loaded.text.contains("## Runtime"),
        loaded.metadata("contentConverterId") == "docling-serve-v1-markdown",
        sent.flatMap(_.apiKey).contains("docling-test-secret"),
        sent.flatMap(_.fileName).contains("guide.pdf"),
        sent.exists(_.fileBytes == bytes("%PDF-stub")),
        sent.exists(_.fields.get("to_formats").contains(Chunk("md"))),
        sent.exists(_.fields.get("ocr_lang").contains(Chunk("zh", "en"))),
        !loader.toString.contains("docling-test-secret")
      )
    } @@ TestAspect.sequential,
    test("HTTP 失败只暴露状态和可重试分类，不泄漏响应正文、API Key 或 PDF 内容") {
      val providerBody = "docling-test-secret %PDF-stub provider-internal-detail"
      for
        observed <- Ref.make(Option.empty[ObservedRequest])
        exit     <- (for
          _ <- TestServer.addRoutes(
            route(
              observed,
              ZIO.succeed(Response.text(providerBody).copy(status = Status.InternalServerError))
            )
          )
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          exit   <- DoclingDocumentLoader(client, config(port)).load(input()).exit
        yield exit).provide(Client.default, TestServer.default)
        error   = exit.causeOption.flatMap(_.failureOption)
        message = error.map(_.message).getOrElse("")
      yield assertTrue(
        exit.isFailure,
        error.exists(_.retryable),
        message.contains("HTTP 500"),
        !message.contains("provider-internal-detail"),
        !message.contains("docling-test-secret"),
        !message.contains("%PDF-stub")
      )
    } @@ TestAspect.sequential,
    test("声明超限在消费一次性 PDF 流和访问网络之前失败") {
      for
        consumed <- Ref.make(false)
        observed <- Ref.make(Option.empty[ObservedRequest])
        result   <- (for
          _      <- TestServer.addRoutes(route(observed, ZIO.succeed(success("# unused"))))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          source = DocumentInput(
            "large",
            "knowledge://large",
            "large.pdf",
            "application/pdf",
            Some(5L),
            Map.empty,
            ZStream.fromZIO(consumed.set(true).as('%'.toByte))
          )
          exit <- DoclingDocumentLoader(client, config(port, inputLimit = 4)).load(source).exit
          read <- consumed.get
          sent <- observed.get
        yield (exit, read, sent)).provide(Client.default, TestServer.default)
      yield assertTrue(result._1.isFailure, !result._2, result._3.isEmpty)
    } @@ TestAspect.sequential,
    test("超大 JSON 响应和非 success wire 状态均 fail-closed") {
      for
        observed <- Ref.make(Option.empty[ObservedRequest])
        response <- Ref.make(
          Response.json(
            s"""{"document":{"md_content":"ok"},"status":"success","padding":"${"x" * 2000}"}"""
          )
        )
        exits <- (for
          _         <- TestServer.addRoutes(route(observed, response.get))
          port      <- ZIO.serviceWithZIO[Server](_.port)
          client    <- ZIO.service[Client]
          oversized <- DoclingDocumentLoader(client, config(port, responseLimit = 256))
            .load(input())
            .exit
          _ <- response.set(
            Response.json("""{"document":{"md_content":"partial"},"status":"partial_success"}""")
          )
          partial <- DoclingDocumentLoader(client, config(port)).load(input()).exit
        yield (oversized, partial)).provide(Client.default, TestServer.default)
      yield assertTrue(exits._1.isFailure, exits._2.isFailure)
    } @@ TestAspect.sequential
  )
