// Git 标签生成版本、签名并通过 Central Portal 发布。1.12.0 正式支持 sbt 2。
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.0")

// 格式是发布契约的一部分：本地与 CI 使用同一个 Scalafmt 入口，避免只靠编辑器插件。
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")

// 对 Maven Central 历史制品执行 MiMa/API 兼容审计；3.3.0 同时支持 sbt 2 与 Scala 3.8。
addSbtPlugin("ch.epfl.scala" % "sbt-version-policy" % "3.3.0")
