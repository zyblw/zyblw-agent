package com.zyblw.agent.memory

// 会话历史 SPI 只管理 thread 范围消息；跨会话事实应进入 LongTermMemory，而不是混入此接口。

import com.zyblw.agent.core.*
import zio.*

trait ConversationStore:
  /** 加载按原始顺序保存的消息历史。 */
  def load(threadId: ThreadId): IO[AgentError, Chunk[AgentMessage]]

  /** 追加一批消息；实现必须保持批次内顺序。 */
  def append(threadId: ThreadId, messages: Chunk[AgentMessage]): IO[AgentError, Unit]

  /** 原子替换会话快照；实现不得产生部分写入。 */
  def replace(threadId: ThreadId, messages: Chunk[AgentMessage]): IO[AgentError, Unit]

object ConversationStore:
  val inMemory: ULayer[ConversationStore] =
    ZLayer.fromZIO {
      Ref.Synchronized.make(Map.empty[ThreadId, Chunk[AgentMessage]]).map { state =>
        new ConversationStore:
          def load(threadId: ThreadId): UIO[Chunk[AgentMessage]] =
            state.get.map(_.getOrElse(threadId, Chunk.empty))

          def append(threadId: ThreadId, messages: Chunk[AgentMessage]): UIO[Unit] =
            state.update(current =>
              current.updated(threadId, current.getOrElse(threadId, Chunk.empty) ++ messages)
            )

          def replace(threadId: ThreadId, messages: Chunk[AgentMessage]): UIO[Unit] =
            state.update(_.updated(threadId, messages))
      }
    }
