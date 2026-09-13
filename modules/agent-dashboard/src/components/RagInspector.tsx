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
          <Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">
            需要 agent:admin:debug + knowledge:read
          </Badge>
          <Button onClick={run} disabled={!canRun || retrieve.isPending}>
            <Search className="h-3 w-3" />
            {retrieve.isPending ? '检索中…' : '执行检索'}
          </Button>
        </>
      }
    >
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

      <div className="mt-3 flex flex-wrap items-center gap-4 text-xs text-slate-300">
        <label className="flex items-center gap-1.5">
          <input
            type="checkbox"
            checked={rerank}
            onChange={(event) => setRerank(event.target.checked)}
            className={`rounded border-slate-700 bg-slate-950 ${FOCUS_RING}`}
          />
          执行重排
        </label>
        <label className="flex items-center gap-1.5">
          <input
            type="checkbox"
            checked={expandContext}
            onChange={(event) => setExpandContext(event.target.checked)}
            className={`rounded border-slate-700 bg-slate-950 ${FOCUS_RING}`}
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
          <div className="mt-3 flex flex-wrap items-center gap-2 rounded-lg border border-slate-800 bg-slate-950/40 px-3 py-2 text-xs text-slate-400">
            <Badge className="text-sky-300 bg-sky-500/10 ring-sky-500/30">
              {formatDuration(result.elapsedMillis)}
            </Badge>
            <span>
              {result.embeddingProvider} / {result.embeddingModel} · {result.embeddingDimension} 维
            </span>
            <Badge className="text-sky-300 bg-sky-500/10 ring-sky-500/30">
              {mode || 'hybrid'}
            </Badge>
            <Badge className={result.rerankApplied ? 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30' : ''}>
              重排 {result.rerankApplied ? '已执行' : '未执行'}
            </Badge>
            <Badge className={result.contextExpanded ? 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30' : ''}>
              上下文扩展 {result.contextExpanded ? '已执行' : '未执行'}
            </Badge>
            <Badge
              className={
                result.evidenceStatus === 'Supported'
                  ? 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30'
                  : 'text-amber-300 bg-amber-500/10 ring-amber-500/30'
              }
            >
              证据 {result.evidenceStatus}
            </Badge>
            <span className="ml-auto">
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
    <Panel title="证据诊断" description="用于复现本次检索使用的索引版本、降级阶段和证据裁剪" className="mt-3 bg-slate-950/30">
      <div className="grid gap-3 text-xs sm:grid-cols-2 xl:grid-cols-4">
        <Field label="Knowledge Space">
          <Mono>{result.knowledgeSpaceId ?? 'default'}</Mono>
        </Field>
        <Field label="Index Profile">
          <Mono>{result.profileId ?? '未解析'}</Mono>
        </Field>
        <Field label="候选 / 接受">
          <span className="tabular-nums text-slate-200">
            {result.candidateCount} / {result.acceptedCount}
          </span>
        </Field>
        <Field label="证据预算">
          <span className="tabular-nums text-slate-200">{formatCount(result.maxEvidenceTokens)} tokens</span>
        </Field>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {result.degradedStages.length === 0 ? (
          <Badge className="text-emerald-300 bg-emerald-500/10 ring-emerald-500/30">无降级</Badge>
        ) : (
          result.degradedStages.map((stage) => (
            <Badge key={stage} className="text-amber-300 bg-amber-500/10 ring-amber-500/30">
              {stage}
            </Badge>
          ))
        )}
        {dropped.length > 0 && (
          <Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">
            丢弃 {dropped.length} 个候选
          </Badge>
        )}
      </div>
      {result.evidenceSelections.length > 0 && (
        <div className="mt-3 overflow-x-auto rounded-lg border border-slate-800">
          <table className="w-full min-w-[42rem] text-left text-xs">
            <thead className="bg-slate-900/80 text-slate-400">
              <tr>
                <th className="px-3 py-2 font-medium">决策</th>
                <th className="px-3 py-2 font-medium">文档</th>
                <th className="px-3 py-2 font-medium">Chunk</th>
                <th className="px-3 py-2 font-medium">Seed</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800 text-slate-300">
              {result.evidenceSelections.map((selection) => (
                <tr key={`${selection.documentId}:${selection.chunkId}:${selection.decision}`}>
                  <td className="px-3 py-2">{selection.decision}</td>
                  <td className="px-3 py-2"><Mono>{selection.documentId}</Mono></td>
                  <td className="px-3 py-2"><Mono>{selection.chunkId}</Mono></td>
                  <td className="px-3 py-2"><Mono>{selection.seedChunkId}</Mono></td>
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
      className={`w-full rounded-lg border px-3 py-2 text-left transition ${FOCUS_RING} ${
        selected ? 'border-indigo-600 bg-indigo-950/30' : 'border-slate-800 bg-slate-950/40 hover:bg-slate-900/60'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className="grid h-5 w-5 shrink-0 place-items-center rounded bg-slate-800 text-[10px] font-semibold text-slate-300">
          {rank}
        </span>
        <Mono className="text-slate-400" title={hit.chunk.documentId}>
          {hit.chunk.documentId}
        </Mono>
        <Badge className="ml-auto text-indigo-300 bg-indigo-500/10 ring-indigo-500/30">
          {formatScore(hit.score)}
        </Badge>
      </div>
      {hit.chunk.headingPath.length > 0 && (
        <div className="mt-1 flex items-center gap-1 text-[11px] text-slate-500">
          {hit.chunk.headingPath.map((heading, index) => (
            <React.Fragment key={`${heading}-${index}`}>
              {index > 0 && <ChevronRight className="h-2.5 w-2.5" />}
              <span>{heading}</span>
            </React.Fragment>
          ))}
        </div>
      )}
      <p className="mt-1.5 line-clamp-3 text-xs leading-relaxed text-slate-300">{hit.chunk.text}</p>
      {signalKeys.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1">
          {signalKeys.map((key) => (
            <span
              key={key}
              className="rounded bg-slate-800/60 px-1.5 py-0.5 text-[10px] tabular-nums text-slate-400"
            >
              {key} {formatScore(hit.signals[key], 3)}
            </span>
          ))}
        </div>
      )}
    </button>
  );
}

/** 命中 chunk 的谱系、定位与权限详情。 */
function ChunkDetail({ hit }: { hit: KnowledgeRetrievalHitView }) {
  const { chunk } = hit;
  return (
    <Panel title="Chunk 详情" className="bg-slate-950/40">
      <div className="divide-y divide-slate-900">
        <Field label="Chunk ID">
          <CopyableId value={chunk.chunkId} label="chunk ID" truncate={24} className="text-slate-200" />
        </Field>
        <Field label="文档">
          <CopyableId value={chunk.documentId} label="文档 ID" truncate={24} className="text-slate-200" />
        </Field>
        <Field label="来源">{chunk.sourceUri}</Field>
        <Field label="索引版本">{chunk.indexVersion}</Field>
        <Field label="序号">{chunk.ordinal ?? '—'}</Field>
        <Field label="父 chunk">{chunk.parentId ? <Mono>{chunk.parentId}</Mono> : '—'}</Field>
        <Field label="前 / 后">
          {chunk.previousChunkId ? '有' : '—'} / {chunk.nextChunkId ? '有' : '—'}
        </Field>
        <Field label="租户">{chunk.tenantId}</Field>
        <Field label="权限">{chunk.permissions.length > 0 ? chunk.permissions.join(', ') : '公开'}</Field>
      </div>

      {chunk.origins.length > 0 && (
        <div className="mt-3">
          <div className="mb-1.5 text-xs font-medium text-slate-300">页面定位</div>
          <div className="space-y-1">
            {chunk.origins.map((origin, index) => (
              <div
                key={`${origin.pageNumber}-${origin.blockId ?? index}`}
                className="flex items-center gap-2 rounded bg-slate-900/60 px-2 py-1 text-[11px] text-slate-400"
              >
                <Badge>第 {origin.pageNumber} 页</Badge>
                {origin.blockId && <Mono className="text-slate-500">{origin.blockId}</Mono>}
                {origin.left !== null && origin.left !== undefined && (
                  <span className="ml-auto tabular-nums text-slate-500">
                    ({formatScore(origin.left, 1)}, {formatScore(origin.top, 1)}) →(
                    {formatScore(origin.right, 1)}, {formatScore(origin.bottom, 1)})
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="mt-3">
        <div className="mb-1.5 flex items-center justify-between text-xs font-medium text-slate-300">
          <span>正文</span>
          {chunk.textTruncated && (
            <Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">已截断</Badge>
          )}
        </div>
        <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded bg-slate-950 p-2 text-[11px] leading-relaxed text-slate-300">
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
          <Badge className="text-sky-300 bg-sky-500/10 ring-sky-500/30">knowledge:read / write</Badge>
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
              className="rounded-lg border border-slate-800 bg-slate-950/40 px-3 py-2"
            >
              <div className="flex items-center gap-2">
                <CopyableId value={doc.documentId} label="文档 ID" truncate={28} />
                <Badge>v{doc.indexVersion}</Badge>
                <Badge
                  className={
                    doc.active
                      ? 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30'
                      : 'text-slate-400 bg-slate-500/10 ring-slate-500/30'
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
                <span>{doc.sourceUri}</span>
                <span>{formatCount(doc.chunkCount)} chunk</span>
                <span>
                  {doc.embeddingProvider}/{doc.embeddingModel} · {doc.embeddingDimension} 维
                </span>
                <span>{doc.indexingStrategy || '默认切分'}</span>
                {extractionLabel(doc) && (
                  <Badge className="text-sky-300 bg-sky-500/10 ring-sky-500/30">{extractionLabel(doc)}</Badge>
                )}
                {doc.permissions.length > 0 && <span>权限 {doc.permissions.join(', ')}</span>}
                {doc.failureCode && (
                  <Badge className="text-rose-300 bg-rose-500/10 ring-rose-500/30">
                    {doc.failureCode}
                  </Badge>
                )}
              </div>
              {doc.active && (
                <div className="mt-2 flex items-center gap-2">
                  {confirming === doc.documentId ? (
                    <>
                      <span className="text-[11px] text-rose-300">
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
      <p className="text-sm leading-6 text-slate-300">
        书目与内部知识都在
        {' '}
        <a className="text-indigo-300 underline" href="/admin/library">
          /admin/library
        </a>
        {' '}
        提取、按章审校后再索引。这里上传的孤儿文档不能成为 /ask 引用。
      </p>
    </Panel>
  );
}
