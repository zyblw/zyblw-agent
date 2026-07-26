// Git 标签生成版本、签名并通过 Central Portal 发布。1.11.2 同时发布了 sbt 1/2 插件。
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")

// 格式是发布契约的一部分：本地与 CI 使用同一个 Scalafmt 入口，避免只靠编辑器插件。
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
