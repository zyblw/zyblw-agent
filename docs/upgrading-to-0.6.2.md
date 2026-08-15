# 升级到 0.6.2：可观察的 PDF 提取级联

> 状态：0.6.2 patch 升级指南。

0.6.2 不新增或修改 Flyway migration，不改变稳定业务 HTTP、状态 JSON、向量维度或 1024 知识 schema。既有 0.6.0/0.6.1
宿主可以只替换 Maven 坐标并保持原装配。

## 行为变化

- PDF 应由一个 `CascadingDocumentLoader` 独占 `application/pdf`。默认 `extractionMode=auto`：文字层质量不足时升到 OCR，再升到可选视觉转录。
- 业务可在 `DocumentInput.metadata("extractionMode")` 或管理面摄入查询参数写入 `text|ocr|vision` 强制只走对应档。未装配该档时 fail-closed，不会静默改走别的档。
- `KnowledgeIndexResult` 增加可选 `extraction`、`extractedMarkdown`、`extractedOutline`。既有 `KnowledgeIndexResult(manifest, usage)` 调用仍然编译。全文不要写入 manifest metadata。
- OpenAI-compatible Chat Completions 在消息含图片时发送标准 `image_url` content 数组。DeepSeek/GLM 配置文件仍拒绝图片。
- 管理面文档视图新增可选 `extractionMode` / `extractionMethod` / `extractionQuality` / `extractionFallbackUsed`。旧客户端忽略未知字段即可。

## 宿主装配

```scala
val pdf = CascadingDocumentLoader(
  Chunk(
    ExtractionStage.text(TikaDocumentLoader(TikaDocumentLoaderConfig(enabledMediaTypes = Set("application/pdf")))),
    ExtractionStage.ocr(docling), // 可选
    ExtractionStage.vision(vision) // 可选；需要声明 vision 能力的 ChatModel
  )
)
```

Markdown/HTML 继续由去掉 PDF 的 Tika 处理。视觉阶段有硬页数上限，超过则失败而不是只转录前几页。

升级验证至少包括：`scalafmtCheckAll`、`testFull`、真实 PostgreSQL 回归、Dashboard typecheck/lint/build，以及宿主侧一次书籍摄入：数字 PDF 走文字层、扫描件在配置 OCR/视觉后能得到 Markdown 与提取方法。
