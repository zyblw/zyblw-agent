package com.zyblw.agent.http.contract

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import zio.*

/** 生成稳定 Agent HTTP OpenAPI 快照。
  *
  * 该入口只存在于 Test 配置，不进入发布制品。CI 先由 Endpoint 单一事实源生成 revision，再用 oasdiff 与基线分支中已审查的 快照比较，避免手工维护另一份 Schema。
  */
object OpenApiSnapshotCli extends ZIOAppDefault:
  def run =
    getArgs.flatMap {
      case Chunk(output) =>
        ZIO.attemptBlocking {
          val path   = Path.of(output)
          val parent = path.getParent
          if parent != null then
            val _ = Files.createDirectories(parent)
          val _ = Files.writeString(path, AgentHttpContract.openApiJson + "\n", StandardCharsets.UTF_8)
        }.unit
      case _ =>
        ZIO.fail(IllegalArgumentException("usage: OpenApiSnapshotCli <output.json>"))
    }
