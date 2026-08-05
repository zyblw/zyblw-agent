import {
  AgentRunView,
  ArtifactItem,
  DocumentBlock,
  DocumentChunk,
  EvalTrendPoint,
  KnowledgeDocumentView,
  QueueSnapshotView,
  RetrievalHit,
  RuntimeConfigModel,
  WorkerNodeView
} from '@/types/agent';

export const MOCK_RUNS: AgentRunView[] = [
  {
    runId: 'run-98421a7c',
    threadId: 'thread-tech-architecture',
    agentId: 'zyblw-knowledge-assistant',
    status: 'Paused',
    userQuery: '请基于项目文档分析 PDF RAG 的结构切分与 pgvector schema 隔离，并执行数据校验 SQL。',
    finalAnswer: undefined,
    createdAt: '2026-08-06T01:10:00Z',
    updatedAt: '2026-08-06T01:10:45Z',
    cumulativeUsage: {
      inputTokens: 4120,
      outputTokens: 850,
      totalTokens: 4970,
      promptCacheHits: 3200,
      reasoningTokens: 240
    },
    estimatedCostUsd: 0.0142,
    traceId: 'tr-0192847a9b',
    sessionId: 'sess-tech-01',
    steps: [
      {
        stepIndex: 1,
        nodeName: '上下文与意图解析 (Context & Intent)',
        kind: 'ContextCompression',
        status: 'Completed',
        durationMs: 340,
        timestamp: '2026-08-06T01:10:02Z',
        usage: { inputTokens: 1200, outputTokens: 150, totalTokens: 1350, promptCacheHits: 900 }
      },
      {
        stepIndex: 2,
        nodeName: 'RAG 混合检索 (Knowledge Hybrid Search)',
        kind: 'ToolExecution',
        status: 'Completed',
        durationMs: 820,
        timestamp: '2026-08-06T01:10:05Z',
        toolCalls: [
          {
            callId: 'call-ret-01',
            toolName: 'knowledge_search',
            riskLevel: 'Low',
            arguments: { query: 'PDF RAG structure chunking pgvector', limit: 5 },
            result: { hitsCount: 3, strategy: 'hybrid_rrf_expansion' },
            status: 'Executed',
            guardrailAllowed: true,
            executedAt: '2026-08-06T01:10:04Z'
          }
        ]
      },
      {
        stepIndex: 3,
        nodeName: '高风险数据库副作用 (High Risk Database Action)',
        kind: 'ApprovalWait',
        status: 'Paused',
        durationMs: 0,
        timestamp: '2026-08-06T01:10:45Z',
        toolCalls: [
          {
            callId: 'call-sql-09',
            toolName: 'execute_schema_validation',
            riskLevel: 'High',
            arguments: {
              targetSchema: 'zyblw_agent_knowledge',
              sql: 'SELECT count(*) FROM zyblw_agent_knowledge.agent_knowledge_chunks_1536;'
            },
            status: 'Requested',
            guardrailAllowed: true
          }
        ],
        details: '等待管理员审批执行 `execute_schema_validation` 写工具。'
      }
    ],
    pendingApprovals: [
      {
        callId: 'call-sql-09',
        toolName: 'execute_schema_validation',
        riskLevel: 'High',
        arguments: {
          targetSchema: 'zyblw_agent_knowledge',
          sql: 'SELECT count(*) FROM zyblw_agent_knowledge.agent_knowledge_chunks_1536;'
        },
        status: 'Requested',
        guardrailAllowed: true
      }
    ]
  },
  {
    runId: 'run-8812bf30',
    threadId: 'thread-medical-qa',
    agentId: 'medical-assistant',
    status: 'Completed',
    userQuery: '总结该病例 PDF 的第 3 页诊断结论，并提供准确引用。',
    finalAnswer: '根据该病例 PDF 第 3 页第 2 节诊断结论：患者各项指标恢复正常，建议继续观察并按时复诊。',
    createdAt: '2026-08-06T00:45:00Z',
    updatedAt: '2026-08-06T00:45:12Z',
    cumulativeUsage: {
      inputTokens: 2800,
      outputTokens: 420,
      totalTokens: 3220,
      promptCacheHits: 2100,
      reasoningTokens: 90
    },
    estimatedCostUsd: 0.0088,
    traceId: 'tr-0987123bc',
    sessionId: 'sess-med-99',
    steps: [
      {
        stepIndex: 1,
        nodeName: 'RAG 谱系检索 (RAG Lineage Query)',
        kind: 'ToolExecution',
        status: 'Completed',
        durationMs: 450,
        timestamp: '2026-08-06T00:45:02Z',
        toolCalls: [
          {
            callId: 'call-rag-88',
            toolName: 'knowledge_search',
            riskLevel: 'Low',
            arguments: { query: '病例 诊断结论 页码 3', limit: 3 },
            result: { hitsCount: 2, citations: ['cite-1 (page 3)'] },
            status: 'Executed',
            guardrailAllowed: true,
            executedAt: '2026-08-06T00:45:02Z'
          }
        ]
      },
      {
        stepIndex: 2,
        nodeName: '模型推理生成 (Model Generation)',
        kind: 'ModelInference',
        status: 'Completed',
        durationMs: 1200,
        timestamp: '2026-08-06T00:45:12Z',
        usage: { inputTokens: 2800, outputTokens: 420, totalTokens: 3220, reasoningTokens: 90 }
      }
    ],
    pendingApprovals: []
  }
];

export const MOCK_KNOWLEDGE_DOCS: KnowledgeDocumentView[] = [
  {
    id: 'doc-zyblw-spec-v04',
    fileName: 'zyblw-agent-0.4.0-pdf-rag-pipeline.pdf',
    sourceUri: 'knowledge://local/docs/pdf-rag-pipeline.pdf',
    mediaType: 'application/pdf',
    fileSizeBytes: 1042000,
    status: 'Active',
    totalChunks: 18,
    tenantId: 'tenant-enterprise-a',
    permissions: ['knowledge:read'],
    loaderEngine: 'Docling',
    chunkerStrategy: 'DocumentStructureChunker',
    createdAt: '2026-08-06T00:30:00Z',
    sha256: '9f3e478a89bc2137409218274...'
  },
  {
    id: 'doc-medical-report-03',
    fileName: '临床医学病例报告分析说明书.pdf',
    sourceUri: 'knowledge://local/docs/medical-report-03.pdf',
    mediaType: 'application/pdf',
    fileSizeBytes: 845000,
    status: 'Active',
    totalChunks: 12,
    tenantId: 'tenant-medical-dev',
    permissions: ['medical:read'],
    loaderEngine: 'Docling',
    chunkerStrategy: 'DocumentStructureChunker',
    createdAt: '2026-08-05T22:15:00Z',
    sha256: '468bac5357d3958674ba9786a...'
  }
];

export const MOCK_PDF_BLOCKS: DocumentBlock[] = [
  {
    id: 'block-001',
    ordinal: 0,
    kind: 'Title',
    text: 'zyblw-agent 0.4.0 架构与 RAG 谱系规范',
    headingPath: ['zyblw-agent 0.4.0 架构与 RAG 谱系规范'],
    origins: [
      {
        pageNumber: 1,
        boundingBox: { left: 54, top: 40, right: 540, bottom: 85, pageWidth: 595, pageHeight: 842 }
      }
    ]
  },
  {
    id: 'block-002',
    parentId: 'block-001',
    ordinal: 1,
    kind: 'SectionHeading',
    text: '1. 结构化切分与 pgvector 隔离',
    headingPath: ['zyblw-agent 0.4.0 架构与 RAG 谱系规范', '1. 结构化切分与 pgvector 隔离'],
    origins: [
      {
        pageNumber: 1,
        boundingBox: { left: 54, top: 100, right: 380, bottom: 125, pageWidth: 595, pageHeight: 842 }
      }
    ]
  },
  {
    id: 'block-003',
    parentId: 'block-002',
    ordinal: 2,
    kind: 'Paragraph',
    text: 'DocumentStructureChunker 优先按文档层级结构 block 切分，合并同标题同父级的相邻小块，并在数据库中保留 zyblw_agent_knowledge 专属 Schema 隔离。',
    headingPath: ['zyblw-agent 0.4.0 架构与 RAG 谱系规范', '1. 结构化切分与 pgvector 隔离'],
    origins: [
      {
        pageNumber: 1,
        boundingBox: { left: 54, top: 135, right: 540, bottom: 210, pageWidth: 595, pageHeight: 842 }
      }
    ]
  },
  {
    id: 'block-004',
    parentId: 'block-002',
    ordinal: 3,
    kind: 'Table',
    text: '| 模块名 | 数据库 Schema | 维数 |\n|---|---|---|\n| Agent Core | public (default) | N/A |\n| Agent Knowledge | zyblw_agent_knowledge | 1536 |',
    headingPath: ['zyblw-agent 0.4.0 架构与 RAG 谱系规范', '1. 结构化切分与 pgvector 隔离'],
    origins: [
      {
        pageNumber: 1,
        boundingBox: { left: 54, top: 225, right: 540, bottom: 330, pageWidth: 595, pageHeight: 842 }
      }
    ]
  }
];

export const MOCK_RETRIEVAL_HITS: RetrievalHit[] = [
  {
    score: 0.924,
    signals: { vectorScore: 0.912, textScore: 0.88, hybridRrfRank: 1, contextExpanded: 1.0 },
    chunk: {
      id: 'chunk-pdf-003',
      documentId: 'doc-zyblw-spec-v04',
      text: 'DocumentStructureChunker 优先按文档层级结构 block 切分，合并同标题同父级的相邻小块，并在数据库中保留 zyblw_agent_knowledge 专属 Schema 隔离。',
      sourceUri: 'knowledge://local/docs/pdf-rag-pipeline.pdf',
      tenantId: 'tenant-enterprise-a',
      permissions: ['knowledge:read'],
      indexVersion: 1,
      lineage: {
        parentId: 'doc-zyblw-spec-v04-parent-section-1',
        ordinal: 2,
        previousChunkId: 'chunk-pdf-002',
        nextChunkId: 'chunk-pdf-004',
        headingPath: ['zyblw-agent 0.4.0 架构', '1. 结构化切分'],
        origins: [
          {
            pageNumber: 1,
            boundingBox: { left: 54, top: 135, right: 540, bottom: 210, pageWidth: 595, pageHeight: 842 },
            blockId: 'block-003'
          }
        ]
      }
    }
  }
];

export const MOCK_WORKERS: WorkerNodeView[] = [
  {
    workerId: 'worker-node-shanghai-01',
    hostIp: '10.240.0.12',
    activeLeases: 4,
    fencingGeneration: 128,
    lastHeartbeatAt: '2026-08-06T01:28:30Z',
    status: 'Healthy'
  },
  {
    workerId: 'worker-node-shanghai-02',
    hostIp: '10.240.0.13',
    activeLeases: 2,
    fencingGeneration: 94,
    lastHeartbeatAt: '2026-08-06T01:28:28Z',
    status: 'Healthy'
  }
];

export const MOCK_QUEUE_SNAPSHOT: QueueSnapshotView = {
  queuedCommands: 0,
  dispatchableRuns: 1,
  leasedRuns: 6,
  expiredLeases: 0,
  deadLetterCommands: 0,
  oldestDispatchableAgeMs: 420
};

export const MOCK_EVAL_TRENDS: EvalTrendPoint[] = [
  { date: '07-30', commitHash: 'v0.2.1', passAtK: 0.92, passPowK: 0.88, citationAccuracy: 0.94, toolSelectionAccuracy: 0.96, gatePassed: true },
  { date: '08-02', commitHash: 'v0.3.0', passAtK: 0.95, passPowK: 0.91, citationAccuracy: 0.96, toolSelectionAccuracy: 0.98, gatePassed: true },
  { date: '08-04', commitHash: 'v0.4.0', passAtK: 0.98, passPowK: 0.95, citationAccuracy: 0.99, toolSelectionAccuracy: 0.99, gatePassed: true }
];

export const MOCK_CONFIG: RuntimeConfigModel = {
  defaultProvider: 'openai-compatible',
  defaultModel: 'deepseek-v4-flash',
  temperature: 0.2,
  maxTokens: 4096,
  contextMaxCharacters: 12000,
  ragLimit: 5,
  ragExpansionRadius: 1,
  rerankEnabled: true,
  workerConcurrency: 6,
  workerLeaseTimeoutSeconds: 30,
  allowedToolNames: ['knowledge_search', 'article_draft', 'execute_schema_validation']
};

export const MOCK_ARTIFACTS: ArtifactItem[] = [
  {
    id: 'art-001',
    fileName: 'architecture_summary_v0.4.pdf',
    mediaType: 'application/pdf',
    sizeBytes: 1042000,
    createdAt: '2026-08-06T01:15:00Z',
    sha256: '9f3e478a89bc21374...'
  },
  {
    id: 'art-002',
    fileName: 'eval_report_snapshot.json',
    mediaType: 'application/json',
    sizeBytes: 84200,
    createdAt: '2026-08-06T00:50:00Z',
    sha256: '468bac5357d395...'
  }
];
