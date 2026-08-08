package com.zyblw.agent.integrations

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.openai.ProviderPresets
import com.zyblw.agent.model.*
import com.zyblw.agent.rag.EmbeddingProviderDescriptor
import zio.*
import zio.json.*
import zio.test.*

/** 验证目录严格从已装配 Provider 派生,并且凭据只以引用形式出现。 */
object ModelCatalogLiveSpec extends ZIOSpecDefault:
  import ModelGovernanceFixtures.*

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelCatalogLive")(
    test("声明了模型清单的 Provider 逐模型展开,能力取各模型独立声明") {
      for
        catalog <- registry.map(ModelCatalogLive.make(_))
        options <- catalog.options
        declared = options.filter(_.provider == "stub-declared")
      yield assertTrue(
        declared.map(_.model) == Chunk("model-basic", "model-vision"),
        declared.forall(_.declaredModel),
        declared.forall(_.protocol == "stub-protocol"),
        declared.forall(_.displayName == "Stub Declared"),
        declared.find(_.model == "model-vision").exists(_.capabilities.vision),
        declared.find(_.model == "model-basic").exists(!_.capabilities.vision)
      )
    },
    test("未声明模型清单的 Provider 只产出部署默认模型且能力回退到 Provider 级") {
      for
        catalog <- registry.map(ModelCatalogLive.make(_))
        options <- catalog.options
        undeclared = options.filter(_.provider == "stub-undeclared")
      yield assertTrue(
        undeclared.length == 1,
        undeclared.head.model == "deployment-default",
        !undeclared.head.declaredModel,
        undeclared.head.capabilities.vision,
        undeclared.head.capabilities.maxOutputTokens.contains(4096L)
      )
    },
    test("默认 Provider 标记只落在路由默认 Provider 上") {
      for
        catalog <- registry.map(ModelCatalogLive.make(_))
        options <- catalog.options
        default <- catalog.defaultProvider
      yield assertTrue(
        default == "stub-declared",
        options.filter(_.isDefaultProvider).map(_.provider).toSet == Set("stub-declared"),
        options.filter(!_.isDefaultProvider).map(_.provider).toSet == Set("stub-undeclared")
      )
    },
    test("凭据缺失时 present 为 false,且序列化输出不含 Key 值") {
      for
        catalog <- registry.map(ModelCatalogLive.make(_))
        options <- catalog.options
        declared   = options.filter(_.provider == "stub-declared")
        undeclared = options.filter(_.provider == "stub-undeclared")
        rendered   = options.toJson
      yield assertTrue(
        declared.forall(_.credential.present),
        declared.forall(_.credential.reference == "env:STUB_DECLARED_API_KEY"),
        undeclared.forall(!_.credential.present),
        undeclared.forall(_.credential.reference == "env:STUB_UNDECLARED_API_KEY"),
        !rendered.contains(FakeApiKey),
        rendered.contains("env:STUB_DECLARED_API_KEY")
      )
    },
    test("从真实 Provider 配置派生的凭据状态只保留变量名") {
      val config = ProviderPresets.deepSeek(FakeApiKey, "deepseek-test")
      for
        model <- stub(deepSeekDescriptor, ZIO.succeed(response(1L, 1L)))
        built <- ProviderRegistry.make(
          model,
          List(
            ProviderRegistration
              .openAICompatible(model, config, ProviderPresets.deepSeekCredentialReference)
          )
        )
        options <- ModelCatalogLive.make(built).options
      yield assertTrue(
        options.length == 1,
        options.head.model == "deepseek-test",
        options.head.credential == ModelCredentialStatus(true, "env:DEEPSEEK_API_KEY"),
        !options.toJson.contains(FakeApiKey),
        !config.toString.contains(FakeApiKey)
      )
    },
    test("价格表只覆盖被声明的组合,金额保持十进制字符串") {
      for
        catalog <- registry.map(ModelCatalogLive.make(_, priceBook))
        options <- catalog.options
        priced = options.filter(_.price.isDefined)
      yield assertTrue(
        priced.map(option => option.provider -> option.model) ==
          Chunk("stub-declared" -> "model-vision"),
        priced.head.price.exists(_.inputPerMillionTokens == "0.15"),
        priced.head.price.exists(_.outputPerMillionTokens == "0.60"),
        priced.head.price.exists(_.cachedInputPerMillionTokens.contains("0.03")),
        priced.head.price.exists(_.currency == "USD")
      )
    },
    test("Embedding 视图只读且携带框架统一的不可切换说明") {
      val view = ModelCatalogLive.embeddingView(
        EmbeddingProviderDescriptor("stub-embeddings", "embed-1", 1536, 64, supportsDimensions = true),
        indexDimension = Some(1536)
      )
      for
        catalog   <- registry.map(ModelCatalogLive.make(_, ModelPriceBook.empty, Some(view)))
        embedding <- catalog.embedding
      yield assertTrue(
        embedding.exists(!_.switchable),
        embedding.exists(_.dimension == 1536),
        embedding.exists(_.indexDimension.contains(1536)),
        embedding.exists(_.immutableReason == EmbeddingModelView.DimensionLockedReason)
      )
    },
    test("声明与真实路由拓扑不一致时在装配期失败") {
      for
        declared <- stub(declaredDescriptor, ZIO.succeed(response(1L, 1L)))
        other    <- stub(undeclaredDescriptor, ZIO.succeed(response(1L, 1L)))
        router   <- RoutedChatModel.make("stub-declared", List(declared, other))
        exit     <- ProviderRegistry
          .make(router, List(registration(declared, "model-vision", "STUB_DECLARED_API_KEY")))
          .exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption
          .flatMap(_.failureOption)
          .exists(_.message.contains("可路由但未声明的 Provider: stub-undeclared"))
      )
    }
  )

  /** 一个默认 Provider 声明了模型清单、另一个没有的两 Provider 路由。 */
  private def registry: IO[AgentError, ProviderRegistry] =
    for
      declared   <- stub(declaredDescriptor, ZIO.succeed(response(1L, 1L)))
      undeclared <- stub(undeclaredDescriptor, ZIO.succeed(response(1L, 1L)), apiKey = "")
      router     <- RoutedChatModel.make("stub-declared", List(declared, undeclared))
      built      <- ProviderRegistry.make(
        router,
        List(
          registration(declared, "model-vision", "STUB_DECLARED_API_KEY"),
          registration(undeclared, "deployment-default", "STUB_UNDECLARED_API_KEY")
        )
      )
    yield built

  private val priceBook: ModelPriceBook = ModelPriceBook.of(
    (
      "stub-declared",
      "model-vision",
      ModelPrice(BigDecimal("0.15"), BigDecimal("0.60"), Some(BigDecimal("0.03")))
    )
  )
