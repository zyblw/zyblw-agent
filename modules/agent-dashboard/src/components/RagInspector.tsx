'use client';

/**
 * 知识库：会话租户的索引清单与检索沙盒。
 *
 * 清单 / 退役走稳定 `/api/v1/knowledge/**`，租户只来自会话。沙盒走
 * `POST /api/v1/admin/debug/retrieve`，用请求体里的模拟租户与权限复现 ACL；会调用 Embedding
 * 并产生费用，因此需要 `agent:admin:debug` 且 `knowledge:read`，只在点击时发起。
 */

import React, { useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight, Search } from 'lucide-react';
import {
  useKnowledgeDocuments,
  useKnowledgeRetrieve,
  useRetireDocument,
} from '@/lib/queries';
import { useToast } from '@/lib/toast';
import { useDebouncedUrlValue } from '@/lib/urlState';
import type {
  KnowledgeDocumentPage,
  KnowledgeDocumentView,
  KnowledgeRetrievalHitView,
  KnowledgeRetrievalResult,
} from '@/types/admin';
import {
  formatCount,
  formatDuration,
  formatRelative,
  formatScore,
  parseList,
} from '@/lib/format';
import {
  Badge,
  Button,
  CopyableId,
  EmptyState,
  ErrorBanner,
  Field,
  FOCUS_RING,
  LoadingRows,
  Mono,
  Panel,
  StatCard,
  TextInput,
} from '@/components/ui';

/** 一页索引清单的条数；比 Run 目录小，因为每一行的信息密度高得多。 */
const DOCUMENTS_PAGE_SIZE = 25;

function extractionModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case 'auto':
      return '自动';
    case 'text':
      return '文字层';
    case 'ocr':
      return 'OCR';
    case 'vision':
      return '视觉';
    default:
      return mode?.trim() ?? '';
  }
}

function extractionLabel(doc: KnowledgeDocumentView): string | null {
  const method = doc.extractionMethod?.trim();
  const mode = extractionModeLabel(doc.extractionMode);
  if (!method && !mode) return null;
  return [mode ? `请求 ${mode}` : null, method ? `实际 ${method}` : null, doc.extractionFallbackUsed ? '已升档' : null]
    .filter(Boolean)
    .join(' · ');
}

export function RagInspector() {
  const [tenantDraft, setTenant] = useDebouncedUrlValue('ragTenant');
  const [cursorStack, setCursorStack] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursorStack[cursorStack.length - 1];

  const documents = useKnowledgeDocuments({
    limit: DOCUMENTS_PAGE_SIZE,
    cursor,
  });
  const activeCount = documents.data?.items.filter((doc) => doc.active).length ?? 0;
  const totalChunks =
    documents.data?.items.reduce((sum, doc) => sum + (doc.active ? doc.chunkCount : 0), 0) ?? 0;

  return (
    <div className="space-y-4 p-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <StatCard label="索引版本" value={formatCount(documents.data?.items.length)} hint="当前页可见" />
        <StatCard label="生效文档" value={formatCount(activeCount)} tone="good" hint="当前页可见" />
        <StatCard label="生效 chunk" value={formatCount(totalChunks)} hint="当前页可见" />
      </div>

      <RetrievalSandbox tenant={tenantDraft} onTenantChange={setTenant} />

      <div className="grid gap-4 xl:grid-cols-2">
        <IndexManifests
          page={documents.data}
          error={documents.error}
          pending={documents.isPending}
          canGoBack={cursorStack.length > 1}
          onPrevious={() => setCursorStack(cursorStack.slice(0, -1))}
          onNext={() => setCursorStack([...cursorStack, documents.data?.nextCursor ?? undefined])}
        />
        <LibrarySourcePanel />
      </div>
    </div>
  );
}

/** 检索沙盒。 */
function RetrievalSandbox({ tenant, onTenantChange }: { tenant: string; onTenantChange: (value: string) => void }) {
  const [query, setQuery] = useState('');
  const [permissions, setPermissions] = useState('');
  const [limit, setLimit] = useState('5');
  const [rerank, setRerank] = useState(false);
  const [expandContext, setExpandContext] = useState(true);
  const [mode, setMode] = useState('hybrid');
  const [selectedChunk, setSelectedChunk] = useState<string | null>(null);

  const retrieve = useKnowledgeRetrieve();
  const result = retrieve.data;

  const canRun = query.trim().length > 0 && tenant.trim().length > 0;

  function run() {
    setSelectedChunk(null);
    retrieve.mutate({
      query: query.trim(),
      tenantId: tenant.trim(),
      permissions: parseList(permissions),
      limit: Math.max(1, Math.min(50, Number.parseInt(limit, 10) || 5)),
      rerank,
      expandContext,
      mode,
    });
  }

  const selected = result?.hits.find((hit) => hit.chunk.chunkId === selectedChunk) ?? null;

  return (
    <Panel
      title="检索沙盒"
      description="以指定租户与权限视角执行一次真实检索；会调用 Embedding Provider 并产生费用"
      actions={
        <>
          <Badge className="text-amber-800 bg-amber-50 ring-amber-600/20 font-medium">
            需要 agent:admin:debug + knowledge:read
          </Badge>
          <Button onClick={run} disabled={!canRun || retrieve.isPending}>
            <Search className="h-3 w-3" />
            {retrieve.isPending ? '检索中…' : '执行检索'}
          </Button>
        </>
      }
    >
      {/* 快捷测试示例 */}
      <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg bg-slate-50 p-2.5 border border-slate-200">
        <span className="text-[11px] font-semibold text-slate-600">快速填入测试:</span>
        <button
          type="button"
          onClick={() => {
            setQuery('中医辨证论治原则与八纲');
            onTenantChange('zyblw');
            setMode('hybrid');
          }}
          className="rounded-md border border-indigo-200 bg-indigo-50/70 px-2.5 py-1 text-[11px] font-medium text-indigo-700 hover:bg-indigo-100 transition shadow-xs"
        >
          🌿 中医辨证论治 (zyblw)
        </button>
        <button
          type="button"
          onClick={() => {
            setQuery('经方桂枝汤的配伍与禁忌');
            onTenantChange('zyblw');
            setRerank(true);
          }}
          className="rounded-md border border-emerald-200 bg-emerald-50/70 px-2.5 py-1 text-[11px] font-medium text-emerald-700 hover:bg-emerald-100 transition shadow-xs"
        >
          📜 经方桂枝汤配伍 (带重排)
        </button>
      </div>

      <div className="grid gap-3 lg:grid-cols-[3fr_1fr_1fr_auto]">
        <TextInput label="查询" value={query} onChange={setQuery} placeholder="要复现的检索问题" />
        <TextInput
          label="模拟租户"
          value={tenant}
          onChange={onTenantChange}
          placeholder="必填"
          hint="只用于沙盒 ACL 复现，不会改清单租户"
        />
        <TextInput
          label="模拟权限（逗号分隔）"
          value={permissions}
          onChange={setPermissions}
          placeholder="留空表示无额外授权"
        />
        <TextInput label="topK" value={limit} onChange={setLimit} className="w-20" inputMode="numeric" />
        <TextInput label="mode" value={mode} onChange={setMode} placeholder="hybrid / phrase" />
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-4 text-xs text-slate-700">
        <label className="flex items-center gap-1.5 cursor-pointer">
          <input
            type="checkbox"
            checked={rerank}
            onChange={(event) => setRerank(event.target.checked)}
            className={`rounded border-slate-300 bg-white text-indigo-600 ${FOCUS_RING}`}
          />
          执行重排
        </label>
        <label className="flex items-center gap-1.5 cursor-pointer">
          <input
            type="checkbox"
            checked={expandContext}
            onChange={(event) => setExpandContext(event.target.checked)}
            className={`rounded border-slate-300 bg-white text-indigo-600 ${FOCUS_RING}`}
          />
          上下文扩展（相邻 / 父级）
        </label>
        <span className="text-slate-500">
          关闭重排可以判断一次不理想的召回是向量不准还是重排把正确结果压了下去。
        </span>
      </div>

      <div className="mt-3">
        <ErrorBanner error={retrieve.error} context="执行检索" />
      </div>

      {result && (
        <>
          <div className="mt-3 flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-700">
            <Badge className="text-blue-700 bg-blue-50 ring-blue-600/20 font-medium">
              {formatDuration(result.elapsedMillis)}
            </Badge>
            <span className="font-mono text-slate-800">
              {result.embeddingProvider} / {result.embeddingModel} · {result.embeddingDimension} 维
            </span>
            <Badge className="text-blue-700 bg-blue-50 ring-blue-600/20 font-medium">
              {mode || 'hybrid'}
            </Badge>
            <Badge className={result.rerankApplied ? 'text-emerald-700 bg-emerald-50 ring-emerald-600/20 font-medium' : 'text-slate-600 bg-slate-100 ring-slate-400/20'}>
              重排 {result.rerankApplied ? '已执行' : '未执行'}
            </Badge>
            <Badge className={result.contextExpanded ? 'text-emerald-700 bg-emerald-50 ring-emerald-600/20 font-medium' : 'text-slate-600 bg-slate-100 ring-slate-400/20'}>
              上下文扩展 {result.contextExpanded ? '已执行' : '未执行'}
            </Badge>
            <Badge
              className={
                result.evidenceStatus === 'Supported'
                  ? 'text-emerald-700 bg-emerald-50 ring-emerald-600/20 font-medium'
                  : 'text-amber-800 bg-amber-50 ring-amber-600/20 font-medium'
              }
            >
              证据 {result.evidenceStatus}
            </Badge>
            <span className="ml-auto font-medium text-slate-600">
              {result.hits.length} 条命中 · {result.citations.length} 条引用
            </span>
          </div>

          <EvidenceDiagnostics result={result} />

          {result.hits.length === 0 ? (
            <div className="mt-3">
              <EmptyState
                title="没有命中"
                reason="可能是该租户下没有生效索引、模拟权限不足以访问任何文档，或最低得分阈值过高。"
              />
            </div>
          ) : (
            <div className="mt-3 grid gap-3 xl:grid-cols-[1.4fr_1fr]">
              <div className="space-y-2">
                {result.hits.map((hit, index) => (
                  <HitRow
                    key={hit.chunk.chunkId}
                    hit={hit}
                    rank={index + 1}
                    selected={hit.chunk.chunkId === selectedChunk}
                    onSelect={() => setSelectedChunk(hit.chunk.chunkId)}
                  />
                ))}
              </div>
              <div className="space-y-3">
                {selected ? (
                  <ChunkDetail hit={selected} />
                ) : (
                  <EmptyState title="选择一条命中以查看谱系与信号" />
                )}
              </div>
            </div>
          )}
        </>
      )}

      {!result && !retrieve.isPending && (
        <div className="mt-3">
          <EmptyState
            title="尚未执行检索"
            reason="填写查询与要模拟的租户后点击执行。沙盒不会自动重发请求，因为每次调用都产生真实的 Embedding 费用。"
          />
        </div>
      )}
    </Panel>
  );
}

/** Profile、降级与 Evidence assembler 取舍；全部是低敏标识，不展示被丢弃正文。 */
function EvidenceDiagnostics({ result }: { result: KnowledgeRetrievalResult }) {
  const dropped = result.evidenceSelections.filter((selection) => selection.decision.startsWith('Dropped'));
  return (
    <Panel title="证据诊断" description="用于复现本次检索使用的索引版本、降级阶段和证据裁剪" className="mt-3 bg-slate-50/60 border border-slate-200">
      <div className="grid gap-3 text-xs sm:grid-cols-2 xl:grid-cols-4">
        <Field label="Knowledge Space">
          <Mono className="font-semibold text-slate-900">{result.knowledgeSpaceId ?? 'default'}</Mono>
        </Field>
        <Field label="Index Profile">
          <Mono className="font-semibold text-slate-900">{result.profileId ?? '未解析'}</Mono>
        </Field>
        <Field label="候选 / 接受">
          <span className="tabular-nums font-semibold text-slate-900">
            {result.candidateCount} / {result.acceptedCount}
          </span>
        </Field>
        <Field label="证据预算">
          <span className="tabular-nums font-semibold text-slate-900">{formatCount(result.maxEvidenceTokens)} tokens</span>
        </Field>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {result.degradedStages.length === 0 ? (
          <Badge className="text-emerald-700 bg-emerald-50 ring-emerald-600/20 font-medium">无降级</Badge>
        ) : (
          result.degradedStages.map((stage) => (
            <Badge key={stage} className="text-amber-800 bg-amber-50 ring-amber-600/20 font-medium">
              {stage}
            </Badge>
          ))
        )}
        {dropped.length > 0 && (
          <Badge className="text-amber-800 bg-amber-50 ring-amber-600/20 font-medium">
            丢弃 {dropped.length} 个候选
          </Badge>
        )}
      </div>
      {result.evidenceSelections.length > 0 && (
        <div className="mt-3 overflow-x-auto rounded-lg border border-slate-200 bg-white">
          <table className="w-full min-w-[42rem] text-left text-xs">
            <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
              <tr>
                <th className="px-3 py-2 font-semibold">决策</th>
                <th className="px-3 py-2 font-semibold">文档</th>
                <th className="px-3 py-2 font-semibold">Chunk</th>
                <th className="px-3 py-2 font-semibold">Seed</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 text-slate-700">
              {result.evidenceSelections.map((selection) => (
                <tr key={`${selection.documentId}:${selection.chunkId}:${selection.decision}`} className="hover:bg-slate-50/60">
                  <td className="px-3 py-2 font-medium">{selection.decision}</td>
                  <td className="px-3 py-2"><Mono className="text-slate-800">{selection.documentId}</Mono></td>
                  <td className="px-3 py-2"><Mono className="text-slate-800">{selection.chunkId}</Mono></td>
                  <td className="px-3 py-2"><Mono className="text-slate-800">{selection.seedChunkId}</Mono></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

/** 单条命中；signals 直接展示后端给出的原始键，不做白名单过滤。 */
function HitRow({
  hit,
  rank,
  selected,
  onSelect,
}: {
  hit: KnowledgeRetrievalHitView;
  rank: number;
  selected: boolean;
  onSelect: () => void;
}) {
  const signalKeys = useMemo(() => Object.keys(hit.signals).sort(), [hit.signals]);
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`w-full rounded-xl border p-3.5 text-left transition-all duration-150 ${FOCUS_RING} ${
        selected
          ? 'border-indigo-500 bg-indigo-50/60 shadow-sm ring-1 ring-indigo-200'
          : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50/60 shadow-xs'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className="grid h-5 w-5 shrink-0 place-items-center rounded bg-indigo-100 text-[10px] font-bold text-indigo-700 ring-1 ring-indigo-200">
          #{rank}
        </span>
        <Mono className="text-slate-900 text-xs font-semibold" title={hit.chunk.documentId}>
          {hit.chunk.documentId}
        </Mono>
        <span className="ml-auto inline-flex items-center gap-1 rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-semibold text-indigo-700 ring-1 ring-indigo-200">
          得分: {formatScore(hit.score)}
        </span>
      </div>
      {hit.chunk.headingPath.length > 0 && (
        <div className="mt-2 flex flex-wrap items-center gap-1 text-[11px] text-slate-600 bg-slate-50 px-2.5 py-1 rounded border border-slate-200">
          <span className="text-slate-400 font-medium">路径:</span>
          {hit.chunk.headingPath.map((heading, index) => (
            <React.Fragment key={`${heading}-${index}`}>
              {index > 0 && <ChevronRight className="h-2.5 w-2.5 text-slate-400" />}
              <span className="font-medium text-slate-800">{heading}</span>
            </React.Fragment>
          ))}
        </div>
      )}
      <p className="mt-2 line-clamp-3 text-xs leading-relaxed text-slate-700 bg-slate-50/70 p-2.5 rounded border border-slate-200/80">
        {hit.chunk.text}
      </p>
      {signalKeys.length > 0 && (
        <div className="mt-2.5 grid grid-cols-2 gap-2 sm:grid-cols-3 bg-slate-50 p-2.5 rounded-lg border border-slate-200">
          {signalKeys.map((key) => {
            const rawScore = hit.signals[key];
            const isRank = key.toLowerCase().includes('rank') || key.toLowerCase().includes('rrf');
            return (
              <div key={key} className="space-y-0.5">
                <div className="flex justify-between text-[10px] text-slate-500 font-medium">
                  <span>{key}</span>
                  <span className="font-mono text-slate-900 tabular-nums font-semibold">{formatScore(rawScore, 3)}</span>
                </div>
                <div className="h-1.5 w-full rounded-full bg-slate-200 overflow-hidden">
                  <div
                    className={`h-full rounded-full ${isRank ? 'bg-amber-500' : 'bg-indigo-600'}`}
                    style={{ width: `${Math.min(100, Math.max(8, rawScore > 1 ? (rawScore / 10) * 100 : rawScore * 100))}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </button>
  );
}

/** 命中 chunk 的谱系、定位与权限详情。 */
function ChunkDetail({ hit }: { hit: KnowledgeRetrievalHitView }) {
  const { chunk } = hit;
  return (
    <Panel title="Chunk 详情">
      <div className="divide-y divide-slate-100">
        <Field label="Chunk ID">
          <CopyableId value={chunk.chunkId} label="chunk ID" truncate={24} className="text-slate-900 font-semibold" />
        </Field>
        <Field label="文档">
          <CopyableId value={chunk.documentId} label="文档 ID" truncate={24} className="text-slate-900 font-semibold" />
        </Field>
        <Field label="来源">{chunk.sourceUri}</Field>
        <Field label="索引版本">{chunk.indexVersion}</Field>
        <Field label="序号">{chunk.ordinal ?? '—'}</Field>
        <Field label="父 chunk">{chunk.parentId ? <Mono className="text-slate-900 font-semibold">{chunk.parentId}</Mono> : '—'}</Field>
        <Field label="前 / 后">
          {chunk.previousChunkId ? '有' : '—'} / {chunk.nextChunkId ? '有' : '—'}
        </Field>
        <Field label="租户">{chunk.tenantId}</Field>
        <Field label="权限">{chunk.permissions.length > 0 ? chunk.permissions.join(', ') : '公开'}</Field>
      </div>

      {chunk.origins.length > 0 && (
        <div className="mt-3.5">
          <div className="mb-2 text-xs font-semibold text-slate-900">页面定位</div>
          <div className="space-y-1.5">
            {chunk.origins.map((origin, index) => (
              <div
                key={`${origin.pageNumber}-${origin.blockId ?? index}`}
                className="flex items-center gap-2 rounded-md bg-slate-50 border border-slate-200 px-2.5 py-1.5 text-[11px] text-slate-600"
              >
                <Badge>第 {origin.pageNumber} 页</Badge>
                {origin.blockId && <Mono className="text-slate-600">{origin.blockId}</Mono>}
                {origin.left !== null && origin.left !== undefined && (
                  <span className="ml-auto tabular-nums font-mono text-slate-500">
                    ({formatScore(origin.left, 1)}, {formatScore(origin.top, 1)}) →(
                    {formatScore(origin.right, 1)}, {formatScore(origin.bottom, 1)})
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="mt-3.5">
        <div className="mb-1.5 flex items-center justify-between text-xs font-semibold text-slate-900">
          <span>正文</span>
          {chunk.textTruncated && (
            <Badge className="text-amber-800 bg-amber-50 ring-amber-600/20 font-medium">已截断</Badge>
          )}
        </div>
        <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-50 border border-slate-200 p-2.5 text-[11px] leading-relaxed text-slate-800 font-mono">
          {chunk.text}
        </pre>
      </div>
    </Panel>
  );
}

/** 索引清单与退役操作。 */
function IndexManifests({
  page,
  error,
  pending,
  canGoBack,
  onPrevious,
  onNext,
}: {
  page: KnowledgeDocumentPage | undefined;
  error: unknown;
  pending: boolean;
  canGoBack: boolean;
  onPrevious: () => void;
  onNext: () => void;
}) {
  const retire = useRetireDocument();
  const { notify } = useToast();
  const [confirming, setConfirming] = useState<string | null>(null);

  return (
    <Panel
      title="知识索引清单"
      description="会话租户下的索引版本；只有 active 版本参与检索。需要 knowledge:read，退役需要 knowledge:write"
      actions={
        <>
          <Badge className="text-blue-700 bg-blue-50 ring-blue-600/20 font-medium">knowledge:read / write</Badge>
          <Button variant="secondary" disabled={!canGoBack} onClick={onPrevious}>
            <ChevronLeft className="h-3 w-3" /> 上一页
          </Button>
          <Button variant="secondary" disabled={!page?.hasMore || !page?.nextCursor} onClick={onNext}>
            下一页 <ChevronRight className="h-3 w-3" />
          </Button>
        </>
      }
    >
      <ErrorBanner error={error} context="读取索引清单" />
      <ErrorBanner error={retire.error} context="退役索引版本" />

      {pending ? (
        <LoadingRows rows={4} />
      ) : (page?.items.length ?? 0) === 0 ? (
        <EmptyState
          title="没有索引版本"
          reason="若已摄入过文档，请确认宿主装配了知识索引目录适配器；仅使用内存索引时列表在进程重启后会清空。"
        />
      ) : (
        <div className="space-y-2">
          {page?.items.map((doc) => (
            <div
              key={`${doc.tenantId}/${doc.documentId}/${doc.indexVersion}`}
              className="rounded-lg border border-slate-200 bg-white p-3 shadow-xs hover:border-slate-300 transition"
            >
              <div className="flex items-center gap-2">
                <CopyableId value={doc.documentId} label="文档 ID" truncate={28} className="text-slate-900 font-semibold" />
                <Badge>v{doc.indexVersion}</Badge>
                <Badge
                  className={
                    doc.active
                      ? 'text-emerald-700 bg-emerald-50 ring-emerald-600/20 font-medium'
                      : 'text-slate-600 bg-slate-100 ring-slate-400/20 font-medium'
                  }
                >
                  {doc.status}
                  {doc.active ? ' · 生效' : ''}
                </Badge>
                <span className="ml-auto text-[11px] text-slate-500">
                  {formatRelative(doc.updatedAtEpochMilli)}
                </span>
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-slate-500">
                <span className="font-mono text-slate-700">{doc.sourceUri}</span>
                <span>{formatCount(doc.chunkCount)} chunk</span>
                <span className="font-mono">
                  {doc.embeddingProvider}/{doc.embeddingModel} · {doc.embeddingDimension} 维
                </span>
                <span>{doc.indexingStrategy || '默认切分'}</span>
                {extractionLabel(doc) && (
                  <Badge className="text-blue-700 bg-blue-50 ring-blue-600/20 font-medium">{extractionLabel(doc)}</Badge>
                )}
                {doc.permissions.length > 0 && <span>权限 {doc.permissions.join(', ')}</span>}
                {doc.failureCode && (
                  <Badge className="text-rose-700 bg-rose-50 ring-rose-600/20 font-medium">
                    {doc.failureCode}
                  </Badge>
                )}
              </div>
              {doc.active && (
                <div className="mt-2.5 flex items-center gap-2 border-t border-slate-100 pt-2">
                  {confirming === doc.documentId ? (
                    <>
                      <span className="text-[11px] font-medium text-rose-600">
                        退役后该文档立即不再参与检索，确认？
                      </span>
                      <Button
                        variant="danger"
                        disabled={retire.isPending}
                        onClick={() => {
                          retire.mutate(
                            {
                              documentId: doc.documentId,
                              expectedActiveVersion: doc.indexVersion,
                            },
                            {
                              onSuccess: () =>
                                notify('success', '索引版本已退役', `${doc.documentId} v${doc.indexVersion} 不再参与检索`),
                              onError: (failure) =>
                                notify(
                                  'error',
                                  '退役失败',
                                  failure instanceof Error ? failure.message : String(failure),
                                ),
                            },
                          );
                          setConfirming(null);
                        }}
                      >
                        确认退役
                      </Button>
                      <Button variant="secondary" onClick={() => setConfirming(null)}>
                        取消
                      </Button>
                    </>
                  ) : (
                    <Button variant="secondary" onClick={() => setConfirming(doc.documentId)}>
                      退役此版本
                    </Button>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </Panel>
  );
}

/** 问答知识只从书库运营面写入；控制台不再接受上传。 */
function LibrarySourcePanel() {
  return (
    <Panel title="问答写入" description="控制台只检查索引与检索，不写入问答证据">
      <p className="text-sm leading-6 text-slate-700">
        书目与内部知识都在
        {' '}
        <a className="font-medium text-indigo-600 underline hover:text-indigo-800" href="/admin/library">
          /admin/library
        </a>
        {' '}
        提取、按章审校后再索引。这里上传的孤儿文档不能成为 /ask 引用。
      </p>
    </Panel>
  );
}
