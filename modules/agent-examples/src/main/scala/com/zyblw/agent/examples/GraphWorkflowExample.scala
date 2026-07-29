package com.zyblw.agent.examples

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.workflow.*
import zio.*

/** 不调用模型的耐久 diamond graph：显式 fan-out、AllSucceeded fan-in、节点 execution ledger 与完成 checkpoint。
  *
  * 真实业务可把任意分支实现成 Agent 节点，但图的边、循环预算和汇合语义仍由代码声明。
  */
object GraphWorkflowExample extends ZIOAppDefault:
  final case class ResearchState(findings: Chunk[String], report: Option[String] = None)

  private val start   = NodeId("start")
  private val market  = NodeId("market")
  private val product = NodeId("product")
  private val join    = NodeId("join")

  private val nodes = Map[NodeId, WorkflowNode[Any, ResearchState]](
    start  -> node(start)(state => ZIO.succeed(NodeOutcome.Succeeded(state))),
    market -> node(market)(state =>
      ZIO.succeed(NodeOutcome.Succeeded(state.copy(findings = Chunk("市场需求增长"))))
    ),
    product -> node(product)(state =>
      ZIO.succeed(NodeOutcome.Succeeded(state.copy(findings = Chunk("产品留存稳定"))))
    ),
    join -> node(join)(state =>
      ZIO.succeed(
        NodeOutcome.Succeeded(
          state.copy(report = Some(state.findings.mkString("；")))
        )
      )
    )
  )

  private val definition = WorkflowDefinition
    .make(
      id = WorkflowId("research-report"),
      version = WorkflowVersion(1),
      entry = start,
      nodes = nodes,
      transitions = Map(
        start -> WorkflowTransition.FanOut(
          NonEmptyChunk(market, product),
          join,
          FanInPolicy.AllSucceeded
        ),
        market  -> WorkflowTransition.Complete(),
        product -> WorkflowTransition.Complete(),
        join    -> WorkflowTransition.Complete()
      )
    )
    .fold(
      issues => throw new IllegalArgumentException(issues.map(_.message).mkString("; ")),
      identity
    )

  private val reducer = new StateReducer[ResearchState]:
    def merge(
        base: ResearchState,
        branches: Chunk[ResearchState]
    ): IO[WorkflowError, ResearchState] =
      ZIO.succeed(base.copy(findings = branches.flatMap(_.findings)))

  def run: ZIO[Any, Any, Unit] =
    (for
      store     <- ZIO.service[WorkflowExecutionStore[ResearchState]]
      runId     <- RunId.random
      sessionId <- SessionId.random
      engine = WorkflowEngine.makeDurable(
        definition,
        store,
        reducer,
        WorkflowExecutionPolicy(WorkerId("graph-example")),
        maxParallelism = 2
      )
      events <- engine
        .run(ResearchState(Chunk.empty), WorkflowContext(runId, sessionId))
        .runCollect
      completed <- ZIO
        .fromOption(events.collectFirst { case WorkflowEvent.Completed(state) => state })
        .orElseFail(AgentError.Unexpected("graph example 没有完成"))
      _ <- Console.printLine(completed.report.getOrElse("<empty report>"))
    yield ()).provide(WorkflowExecutionStore.inMemory[ResearchState])

  private def node(id0: NodeId)(
      execute0: ResearchState => IO[WorkflowError, NodeOutcome[ResearchState]]
  ): WorkflowNode[Any, ResearchState] =
    new WorkflowNode[Any, ResearchState]:
      val id = id0
      def execute(
          state: ResearchState,
          context: WorkflowContext
      ): IO[WorkflowError, NodeOutcome[ResearchState]] =
        execute0(state)
