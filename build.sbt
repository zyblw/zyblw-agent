import sbt.*
import sbt.Keys.*

// ---------------------------------------------------------------------------
// 发布元数据
// ---------------------------------------------------------------------------

ThisBuild / organization  := "io.github.zyblw"
ThisBuild / scalaVersion  := "3.8.4"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage      := Some(url("https://github.com/zyblw/zyblw-agent"))
ThisBuild / licenses      := List(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")
)
ThisBuild / developers := List(
  Developer(
    id = "zyblw",
    name = "zyblw",
    email = "zyblw@users.noreply.github.com",
    url = url("https://github.com/zyblw")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    browseUrl = url("https://github.com/zyblw/zyblw-agent"),
    connection = "scm:git:https://github.com/zyblw/zyblw-agent.git",
    devConnection = Some("scm:git:ssh://git@github.com/zyblw/zyblw-agent.git")
  )
)
ThisBuild / pomIncludeRepository := (_ => false)
ThisBuild / javacOptions ++= Seq("-source", "21", "-target", "21")
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Xkind-projector"
)
ThisBuild / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
ThisBuild / Test / parallelExecution := true

/** Provider、OTLP、MCP 等测试会创建真实 Netty stub server。限制跨项目 Test 并发可以避免 CI 因 native thread 上限产生与断言无关的随机失败；单项目内部仍由
  * ZIO Test 并行。
  */
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

// ---------------------------------------------------------------------------
// 依赖版本
// ---------------------------------------------------------------------------

lazy val zioVersion            = "2.1.26"
lazy val zioJsonVersion        = "0.9.2"
lazy val zioSchemaVersion      = "1.8.5"
lazy val zioHttpVersion        = "3.11.3"
lazy val postgresVersion       = "42.7.13"
lazy val openTelemetryVersion  = "1.63.0"
lazy val testContainersVersion = "0.44.1"
lazy val flywayVersion         = "13.0.0"
lazy val tikaVersion           = "3.3.1"

lazy val commonSettings = Seq(
  description := s"Provider-neutral Scala 3 and ZIO 2 agent framework module: ${name.value}",
  Compile / packageBin / packageOptions ++= Seq(
    Package.ManifestAttributes(
      "Implementation-Title"   -> name.value,
      "Implementation-Version" -> version.value,
      "Implementation-Vendor"  -> "zyblw-agent contributors",
      "Automatic-Module-Name"  -> name.value.replace('-', '.')
    )
  ),
  Compile / packageSrc / publishArtifact := true,
  Compile / packageDoc / publishArtifact := true,
  Test / publishArtifact                 := false,
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"          % zioVersion,
    "dev.zio" %% "zio-json"     % zioJsonVersion,
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test
  )
)

// ---------------------------------------------------------------------------
// 稳定内核
// ---------------------------------------------------------------------------

/** 业务默认只需要这一项基础依赖。
  *
  * core 包含经常协同演进、没有重型厂商依赖的能力：领域 ADT、ChatModel SPI、类型化工具、 Context/Memory、权限与 guardrail、单 Agent loop、调度、应用
  * Builder 和观测 SPI。 它们仍以独立 package 组织，但不再伪装成十几个需要分别选版本的发布产品。
  */
lazy val core = project
  .in(file("modules/agent-core"))
  .settings(commonSettings)
  .settings(
    name := "zyblw-agent-core",
    libraryDependencies ++= Seq(
      "dev.zio"         %% "zio-streams"       % zioVersion,
      "dev.zio"         %% "zio-schema"        % zioSchemaVersion,
      "dev.zio"         %% "zio-schema-json"   % zioSchemaVersion,
      "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion
    )
  )

// ---------------------------------------------------------------------------
// 可选能力：只在引入独立协议或重型基础设施时拆 artifact
// ---------------------------------------------------------------------------

lazy val rag = project
  .in(file("modules/agent-rag"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name                             := "zyblw-agent-rag",
    libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion
  )

/** Tika/PDF/EPUB 依赖较重，不能污染只做结构化知识检索的 RAG 用户。 */
lazy val documentLoaders = project
  .in(file("modules/agent-document-loaders"))
  .dependsOn(core, rag)
  .settings(commonSettings)
  .settings(
    name := "zyblw-agent-document-loaders",
    libraryDependencies ++= Seq(
      "dev.zio"        %% "zio-streams"                   % zioVersion,
      "org.apache.tika" % "tika-core"                     % tikaVersion,
      "org.apache.tika" % "tika-parsers-standard-package" % tikaVersion
    )
  )

/** 外部 rerank HTTP 协议保持可选，避免 RAG 核心强制产生网络依赖。 */
lazy val rerank = project
  .in(file("modules/agent-rerank"))
  .dependsOn(core, rag)
  .settings(commonSettings)
  .settings(
    name := "zyblw-agent-rerank",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-streams"      % zioVersion,
      "dev.zio" %% "zio-http"         % zioHttpVersion,
      "dev.zio" %% "zio-http-testkit" % zioHttpVersion % Test
    )
  )

lazy val evals = project
  .in(file("modules/agent-evals"))
  .dependsOn(core, rag)
  .settings(commonSettings)
  .settings(name := "zyblw-agent-evals")

/** OpenAI-compatible、OpenAI Responses、Anthropic Messages 与 Gemini Interactions 都只依赖 同一套 ZIO HTTP
  * transport。合并发布可减少选择和版本矩阵，同时 package 继续隔离协议实现。
  */
lazy val providers = project
  .in(file("modules/agent-providers"))
  .dependsOn(core, rag, testkit % "test->compile")
  .settings(commonSettings)
  .settings(
    name := "zyblw-agent-providers",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http"         % zioHttpVersion,
      "dev.zio" %% "zio-http-testkit" % zioHttpVersion % Test
    )
  )

lazy val postgres = project
  .in(file("modules/agent-postgres"))
  .dependsOn(core, rag, evals)
  .settings(commonSettings)
  .settings(
    name := "zyblw-agent-postgres",
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql"                      % postgresVersion,
      "org.flywaydb"   % "flyway-core"                     % flywayVersion,
      "org.flywaydb"   % "flyway-database-postgresql"      % flywayVersion,
      "com.dimafeng"  %% "testcontainers-scala-postgresql" % testContainersVersion % Test
    )
  )

/** DTO/Endpoint、routes 和可独立运行的 Server host 使用同一 ZIO HTTP 版本，统一发布。 package `http.contract` 仍然是稳定 wire
  * contract，不向外暴露内部 AgentState。
  */
lazy val http = project
  .in(file("modules/agent-zio-http"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "zyblw-agent-zio-http",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-schema"       % zioSchemaVersion,
      "dev.zio" %% "zio-schema-json"  % zioSchemaVersion,
      "dev.zio" %% "zio-http"         % zioHttpVersion,
      "dev.zio" %% "zio-http-testkit" % zioHttpVersion % Test
    )
  )

/** MCP transport 与受控 workspace 共同构成工具互操作边界。 */
lazy val mcp = project
  .in(file("modules/agent-mcp"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "zyblw-agent-mcp",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-streams"      % zioVersion,
      "dev.zio" %% "zio-http"         % zioHttpVersion,
      "dev.zio" %% "zio-http-testkit" % zioHttpVersion % Test
    )
  )

/** SDK/Exporter 会创建资源和后台线程，因此与零成本观测 SPI 分离。 */
lazy val opentelemetry = project
  .in(file("modules/agent-opentelemetry"))
  .dependsOn(core, evals)
  .settings(commonSettings)
  .settings(
    name := "zyblw-agent-opentelemetry",
    libraryDependencies ++= Seq(
      "io.opentelemetry" % "opentelemetry-sdk"           % openTelemetryVersion,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % openTelemetryVersion,
      "io.opentelemetry" % "opentelemetry-sdk-testing"   % openTelemetryVersion % Test,
      "dev.zio"         %% "zio-http"                    % zioHttpVersion,
      "dev.zio"         %% "zio-http-testkit"            % zioHttpVersion       % Test
    )
  )

lazy val testkit = project
  .in(file("modules/agent-testkit"))
  .dependsOn(core, rag, evals)
  .settings(commonSettings)
  .settings(name := "zyblw-agent-testkit")

// ---------------------------------------------------------------------------
// 仓库内工具与示例：参与测试，但不是公共依赖
// ---------------------------------------------------------------------------

lazy val evalCli = project
  .in(file("modules/agent-eval-cli"))
  .dependsOn(core, evals, postgres)
  .settings(commonSettings)
  .settings(
    name                 := "zyblw-agent-eval-cli",
    publish / skip       := true,
    Compile / run / fork := true
  )

lazy val benchmarks = project
  .in(file("modules/agent-benchmarks"))
  .dependsOn(core, testkit)
  .settings(commonSettings)
  .settings(name := "zyblw-agent-benchmarks", publish / skip := true)

lazy val examples = project
  .in(file("modules/agent-examples"))
  .dependsOn(
    core,
    rag,
    documentLoaders,
    rerank,
    evals,
    providers,
    postgres,
    http,
    mcp,
    opentelemetry,
    testkit
  )
  .settings(commonSettings)
  .settings(name := "zyblw-agent-examples", publish / skip := true)

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    rag,
    documentLoaders,
    rerank,
    evals,
    providers,
    postgres,
    http,
    mcp,
    opentelemetry,
    testkit,
    evalCli,
    benchmarks,
    examples
  )
  .settings(
    name           := "zyblw-agent",
    publish / skip := true
  )
