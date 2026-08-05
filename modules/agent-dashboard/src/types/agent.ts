export type RunStatus = 'Created' | 'Running' | 'Paused' | 'Completed' | 'Failed' | 'Cancelled';

export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  promptCacheHits?: number;
  reasoningTokens?: number;
}

export interface ToolCallLedgerItem {
  callId: string;
  toolName: string;
  riskLevel: 'Low' | 'Medium' | 'High' | 'Critical';
  arguments: Record<string, unknown>;
  result?: Record<string, unknown> | string;
  status: 'Requested' | 'Approved' | 'Rejected' | 'Executed' | 'Failed';
  guardrailAllowed: boolean;
  executedAt?: string;
}

export interface StepTimelineNode {
  stepIndex: number;
  nodeName: string;
  kind: 'ModelInference' | 'ToolExecution' | 'ApprovalWait' | 'ContextCompression' | 'GuardrailCheck';
  status: 'Completed' | 'Failed' | 'Paused' | 'InProgress';
  durationMs: number;
  timestamp: string;
  usage?: TokenUsage;
  toolCalls?: ToolCallLedgerItem[];
  details?: string;
}

export interface AgentRunView {
  runId: string;
  threadId: string;
  agentId: string;
  status: RunStatus;
  userQuery: string;
  finalAnswer?: string;
  createdAt: string;
  updatedAt: string;
  cumulativeUsage: TokenUsage;
  estimatedCostUsd: number;
  traceId?: string;
  sessionId?: string;
  steps: StepTimelineNode[];
  pendingApprovals: ToolCallLedgerItem[];
}

export interface BoundingBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
  pageWidth?: number;
  pageHeight?: number;
}

export interface DocumentOrigin {
  pageNumber: number;
  boundingBox?: BoundingBox;
  blockId?: string;
}

export interface DocumentBlock {
  id: string;
  parentId?: string;
  ordinal: number;
  kind: 'Title' | 'SectionHeading' | 'Paragraph' | 'ListItem' | 'Table' | 'Code' | 'Formula' | 'Other';
  text: string;
  headingPath: string[];
  origins: DocumentOrigin[];
}

export interface ChunkLineage {
  parentId?: string;
  ordinal: number;
  previousChunkId?: string;
  nextChunkId?: string;
  headingPath: string[];
  origins: DocumentOrigin[];
}

export interface DocumentChunk {
  id: string;
  documentId: string;
  text: string;
  sourceUri: string;
  tenantId: string;
  permissions: string[];
  indexVersion: number;
  lineage?: ChunkLineage;
}

export interface RetrievalHit {
  chunk: DocumentChunk;
  score: number;
  signals: Record<string, number>;
}

export interface WorkerNodeView {
  workerId: string;
  hostIp: string;
  activeLeases: number;
  fencingGeneration: number;
  lastHeartbeatAt: string;
  status: 'Healthy' | 'Stale' | 'Dead';
}

export interface QueueSnapshotView {
  queuedCommands: number;
  dispatchableRuns: number;
  leasedRuns: number;
  expiredLeases: number;
  deadLetterCommands: number;
  oldestDispatchableAgeMs: number;
}

export interface EvalTrendPoint {
  date: string;
  commitHash: string;
  passAtK: number;
  passPowK: number;
  citationAccuracy: number;
  toolSelectionAccuracy: number;
  gatePassed: boolean;
}

export interface RuntimeConfigModel {
  defaultProvider: string;
  defaultModel: string;
  temperature: number;
  maxTokens: number;
  contextMaxCharacters: number;
  ragLimit: number;
  ragExpansionRadius: number;
  rerankEnabled: boolean;
  workerConcurrency: number;
  workerLeaseTimeoutSeconds: number;
  allowedToolNames: string[];
}

export interface ArtifactItem {
  id: string;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  createdAt: string;
  sha256: string;
}
