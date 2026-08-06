package com.zyblw.agent.integrations

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.json.*
import zio.test.*

/** 验证目录快照与探活的可观测性边界:探活证明连通性,但不外泄凭据、模型输出或 Provider 原始响应。 */
object ModelAdminLiveSpec extends ZIOSpecDefault:
  import ModelGovernanceFixtures.*

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelAdminLive")(
    test("目录视图带上生效工作点与价格覆盖统计") {
      val policy = ModelPolicy(provider = Some("stub-undeclared"), model = Some("deployment-default"))
      for
        fixture <- fixture(ZIO.succeed(response(1L, 1L)))
        service = admin(fixture, ModelPolicySource.static(policy, priceBook), priceBook)
        view <- service.catalog
      yield assertTrue(
        view.defaultProvider == "stub-declared",
        view.effectiveProvider.contains("stub-undeclared"),
        view.effectiveModel.contains("deployment-default"),
        view.options.length == 3,
        view.pricedOptionCount == 2,
        view.priceCurrency.contains("USD")
      )
    },
    test("未声明价格表时货币为空且覆盖数为零") {
      for
        fixture <- fixture(ZIO.succeed(response(1L, 1L)))
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        view <- service.catalog
      yield assertTrue(
        view.priceCurrency.isEmpty,
        view.pricedOptionCount == 0,
        view.effectiveProvider.isEmpty,
        view.effectiveModel.isEmpty
      )
    },
    test("未注册组合在任何网络请求之前失败") {
      for
        fixture <- fixture(ZIO.succeed(response(1L, 1L)))
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        unknownProvider <- service.probe(ModelProbeRequest("not-registered", Some("model-vision")))
        unknownModel    <- service.probe(ModelProbeRequest("stub-declared", Some("not-registered")))
        calls           <- fixture.declared.callCount
      yield assertTrue(
        calls == 0,
        !unknownProvider.succeeded,
        unknownProvider.failureCode.contains(ModelAdminLive.ProviderNotFound),
        unknownProvider.latencyMillis == 0L,
        !unknownModel.succeeded,
        unknownModel.failureCode.contains(ModelAdminLive.ModelNotFound)
      )
    },
    test("未指定模型时回落到该 Provider 的部署默认模型") {
      for
        fixture <- fixture(ZIO.succeed(response(11L, 5L)))
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        result <- service.probe(ModelProbeRequest("stub-undeclared"))
      yield assertTrue(
        result.model == "deployment-default",
        result.succeeded
      )
    },
    test("成功探活回填 token 用量但不返回模型输出正文") {
      for
        fixture <- fixture(ZIO.succeed(response(17L, 3L)))
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        result <- service.probe(ModelProbeRequest("stub-declared", Some("model-vision")))
        calls  <- fixture.declared.callCount
      yield assertTrue(
        calls == 1,
        result.succeeded,
        result.provider == "stub-declared",
        result.model == "model-vision",
        result.inputTokens == 17L,
        result.outputTokens == 3L,
        result.latencyMillis >= 0L,
        result.failureCode.isEmpty,
        !result.toJson.contains(FakeModelOutput)
      )
    },
    test("探活请求本身是最小请求:无工具、零温度、极小输出上限") {
      for
        fixture <- fixture(ZIO.succeed(response(1L, 1L)))
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        _       <- service.probe(ModelProbeRequest("stub-declared", Some("model-basic")))
        request <- fixture.declared.lastRequest
      yield assertTrue(
        request.exists(_.tools.isEmpty),
        request.exists(_.messages.length == 1),
        request.exists(_.settings.toolChoice == ToolChoice.None),
        request.exists(_.settings.temperature.contains(0.0)),
        request.exists(_.settings.maxOutputTokens.contains(4)),
        request.exists(_.settings.model.contains("model-basic"))
      )
    },
    test("失败探活只返回稳定分类码,不含 Key 也不含 Provider 原始响应") {
      val failure =
        AgentError.ModelHttpFailure("stub-declared", 401, Some(s"$FakeProviderBody-$FakeApiKey"))
      for
        fixture <- fixture(ZIO.fail(failure))
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        result <- service.probe(ModelProbeRequest("stub-declared", Some("model-vision")))
        rendered = result.toJson
      yield assertTrue(
        !result.succeeded,
        result.failureCode.contains(ModelAdminLive.Unauthorized),
        !rendered.contains(FakeApiKey),
        !rendered.contains("PROVIDER-BODY-MUST-NOT-APPEAR"),
        !rendered.contains("401")
      )
    },
    test("能力协商失败与限流各自归入稳定分类码") {
      val capability = AgentError.UnsupportedModelCapability("stub-declared", "vision", "请求包含图片")
      val throttled  = AgentError.EmbeddingQuotaExceeded("requests", 100L)
      for
        capabilityFixture <- fixture(ZIO.fail(capability))
        throttledFixture  <- fixture(ZIO.fail(throttled))
        capabilityResult  <- admin(capabilityFixture, ModelPolicySource.default, ModelPriceBook.empty)
          .probe(ModelProbeRequest("stub-declared", Some("model-vision")))
        throttledResult <- admin(throttledFixture, ModelPolicySource.default, ModelPriceBook.empty)
          .probe(ModelProbeRequest("stub-declared", Some("model-vision")))
      yield assertTrue(
        capabilityResult.failureCode.contains(ModelAdminLive.Capability),
        throttledResult.failureCode.contains(ModelAdminLive.RateLimited)
      )
    },
    test("挂住的 Provider 在硬超时后被中断并归入 timeout") {
      for
        fixture <- fixture(ZIO.never)
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        probe  <- service.probe(ModelProbeRequest("stub-declared", Some("model-vision"))).fork
        _      <- TestClock.adjust(21.seconds)
        result <- probe.join
      yield assertTrue(
        !result.succeeded,
        result.failureCode.contains(ModelAdminLive.Timeout),
        result.inputTokens == 0L,
        result.outputTokens == 0L
      )
    },
    test("调用方取消不被伪装成一次探活失败") {
      // 关掉浏览器标签或点「取消」会中断这个请求。若中断被当成 Provider 故障归类,管理台会显示一个从未发生过的
      // 故障;运维随后会去排查一个健康的 Provider。因此中断必须原样向上传播,而不是变成一条 result。
      for
        fixture <- fixture(ZIO.never)
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        probe <- service.probe(ModelProbeRequest("stub-declared", Some("model-vision"))).fork
        _     <- TestClock.adjust(1.second)
        _     <- probe.interrupt
        exit  <- probe.await
      yield assertTrue(exit.isInterrupted)
    },
    test("Defect 不被猜成更具体的分类") {
      for
        fixture <- fixture(ZIO.die(RuntimeException(FakeApiKey)))
        service = admin(fixture, ModelPolicySource.default, ModelPriceBook.empty)
        result <- service.probe(ModelProbeRequest("stub-declared", Some("model-vision")))
      yield assertTrue(
        !result.succeeded,
        result.failureCode.contains(ModelAdminLive.Unexpected),
        !result.toJson.contains(FakeApiKey)
      )
    }
  )

  /** 已装配的两 Provider 路由及其注册表。 */
  final private case class Fixture(
      registry: ProviderRegistry,
      declared: StubChatModel,
      undeclared: StubChatModel
  )

  /** 构造两 Provider 路由与其注册表。 */
  private def fixture(reply: IO[AgentError, ChatResponse]): IO[AgentError, Fixture] =
    for
      declared   <- stub(declaredDescriptor, reply)
      undeclared <- stub(undeclaredDescriptor, reply)
      router     <- RoutedChatModel.make("stub-declared", List(declared, undeclared))
      built      <- ProviderRegistry.make(
        router,
        List(
          registration(declared, "model-vision", "STUB_DECLARED_API_KEY"),
          registration(undeclared, "deployment-default", "STUB_UNDECLARED_API_KEY")
        )
      )
    yield Fixture(built, declared, undeclared)

  private def admin(
      fixture: Fixture,
      policies: ModelPolicySource,
      priceBook: ModelPriceBook
  ): ModelAdminService = ModelAdminLive.make(
    fixture.registry,
    ModelCatalogLive.make(fixture.registry, priceBook),
    policies,
    ModelProbeConfig(maxOutputTokens = 4)
  )

  private val priceBook: ModelPriceBook = ModelPriceBook.of(
    ("stub-declared", "model-vision", ModelPrice(BigDecimal("1.00"), BigDecimal("2.00"))),
    ("stub-undeclared", "deployment-default", ModelPrice(BigDecimal("0.50"), BigDecimal("1.50")))
  )
