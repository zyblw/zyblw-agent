package com.zyblw.agent.consumer

import com.zyblw.agent.core.*

object MavenConsumerSmoke:
  def main(args: Array[String]): Unit =
    val agentId = AgentId("maven-consumer")
    val message = AgentMessage.user("consumer contract")

    require(agentId.value == "maven-consumer")
    require(message.text == "consumer contract")
