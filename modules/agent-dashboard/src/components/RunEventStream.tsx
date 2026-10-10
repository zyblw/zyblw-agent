'use client';

import React, { useEffect, useRef, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Cpu,
  GitCommit,
  Layers,
  Pause,
  Play,
  Radio,
  RotateCw,
  ShieldAlert,
  Trash2,
  Wrench,
} from 'lucide-react';
import { useConnection } from '@/lib/connection';
import { AdminApiError, adminApi } from '@/lib/adminClient';
import { formatCount, formatInstant } from '@/lib/format';
import type { AdminRunEventView, RunSummaryView } from '@/types/admin';
import { Badge, Button, EmptyState, FOCUS_RING, Mono, Panel } from '@/components/ui';

const MAX_EVENTS = 500;
const RECENT_WINDOW = 200;
const MAX_RETRY_DELAY_MS = 15_000;

type StreamStatus = 'idle' | 'connecting' | 'live' | 'retrying' | 'paused' | 'caught-up' | 'error';
type ViewMode = 'waterfall' | 'raw';

const STATUS_LABEL: Record<StreamStatus, string> = {
  idle: '未连接',
  connecting: '连接中',
  live: '实时',
  retrying: '正在重连',
  paused: '已暂停',
  'caught-up': '已追平',
  error: '连接失败',
};

function parseEvent(data: string): AdminRunEventView {
  let value: unknown;
  try {
    value = JSON.parse(data);
  } catch {
    throw new AdminApiError(0, 'invalid-stream-event', '事件流返回了无效 JSON');
  }
  const event = value as Partial<AdminRunEventView>;
  if (
    typeof event.eventId !== 'string' ||
    typeof event.runId !== 'string' ||
    typeof event.sequence !== 'number' ||
    typeof event.eventType !== 'string' ||
    typeof event.atEpochMilli !== 'number'
  ) {
    throw new AdminApiError(0, 'invalid-stream-event', '事件流缺少必需字段');
  }
  return event as AdminRunEventView;
}

function errorMessage(error: unknown): string {
  if (error instanceof AdminApiError) {
    if (error.isForbidden) return '当前凭据缺少 agent:admin:read，无法读取跨租户事件流。';
    if (error.isMissingCapability) return '后端未装配 Run 事件流能力，请检查 AdminCapabilities.runEvents。';
    return error.message;
  }
  return error instanceof Error ? error.message : '事件流连接失败';
}

/**
 * 单 Run 的低敏耐久事件调试器与步态瀑布流 (Trace Waterfall - Clean Light Theme)。
 */
export function RunEventStream({ run }: { run: RunSummaryView }) {
  const { config } = useConnection();
  const [events, setEvents] = useState<AdminRunEventView[]>([]);
  const [status, setStatus] = useState<StreamStatus>('idle');
  const [viewMode, setViewMode] = useState<ViewMode>('waterfall');
  const [failure, setFailure] = useState<string | null>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [lastSequence, setLastSequence] = useState<number | undefined>(undefined);
  const controllerRef = useRef<AbortController | null>(null);
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastSequenceRef = useRef<number | undefined>(undefined);
  const intentionalStopRef = useRef(false);
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(
    () => () => {
      intentionalStopRef.current = true;
      controllerRef.current?.abort();
      if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    },
    [],
  );

  useEffect(() => {
    if (autoScroll && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [events, autoScroll]);

  function stop() {
    intentionalStopRef.current = true;
    controllerRef.current?.abort();
    if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    retryTimerRef.current = null;
    setStatus('paused');
  }

  function start(fromBeginning = false) {
    controllerRef.current?.abort();
    if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    intentionalStopRef.current = false;
    setFailure(null);

    const initialAfter = fromBeginning ? undefined : events[events.length - 1]?.sequence;
    lastSequenceRef.current = initialAfter;
    setLastSequence(initialAfter);
    if (fromBeginning) {
      setEvents([]);
    }

    connectWithBackoff(initialAfter, 0);
  }

  function connectWithBackoff(afterSequence: number | undefined, attempt: number) {
    if (intentionalStopRef.current) return;
    setStatus(attempt === 0 ? 'connecting' : 'retrying');

    const controller = new AbortController();
    controllerRef.current = controller;

    adminApi
      .streamRunEvents(config, run.runId, {
        afterSequence,
        signal: controller.signal,
        onMessage: (msg) => {
          setStatus('live');
          setFailure(null);
          try {
            const parsed = parseEvent(msg.data);
            lastSequenceRef.current = parsed.sequence;
            setLastSequence(parsed.sequence);
            setEvents((prev) => {
              const deduplicated = prev.filter((e) => e.sequence !== parsed.sequence);
              const next = [...deduplicated, parsed];
              return next.length > MAX_EVENTS ? next.slice(-MAX_EVENTS) : next;
            });
          } catch (error) {
            setFailure(errorMessage(error));
          }
        },
      })
      .then(() => {
        if (!intentionalStopRef.current) {
          setStatus('caught-up');
        }
      })
      .catch((error: unknown) => {
        if (intentionalStopRef.current) return;
        setStatus('error');
        setFailure(errorMessage(error));

        const delay = Math.min(1000 * Math.pow(2, attempt), MAX_RETRY_DELAY_MS);
        retryTimerRef.current = setTimeout(() => {
          connectWithBackoff(lastSequenceRef.current, attempt + 1);
        }, delay);
      });
  }

  const active = status === 'connecting' || status === 'live' || status === 'retrying';

  return (
    <Panel
      title="实时事件流"
      description={`低敏耐久事件：默认读取最近 ${RECENT_WINDOW} 条，断线后从最后 sequence 恢复；支持步骤瀑布流与原始日志双视角`}
      actions={
        <>
          <div className="flex items-center rounded-lg bg-slate-100 p-0.5 ring-1 ring-slate-200">
            <button
              type="button"
              onClick={() => setViewMode('waterfall')}
              className={`rounded-md px-2.5 py-1 text-xs font-medium transition ${
                viewMode === 'waterfall'
                  ? 'bg-white text-slate-900 shadow-xs'
                  : 'text-slate-500 hover:text-slate-900'
              }`}
            >
              ⚡ 步态瀑布流
            </button>
            <button
              type="button"
              onClick={() => setViewMode('raw')}
              className={`rounded-md px-2.5 py-1 text-xs font-medium transition ${
                viewMode === 'raw'
                  ? 'bg-white text-slate-900 shadow-xs'
                  : 'text-slate-500 hover:text-slate-900'
              }`}
            >
              📜 原始事件流
            </button>
          </div>

          {!active ? (
            <Button onClick={() => start(false)}>
              <Radio className="h-3.5 w-3.5" /> 连接最近事件
            </Button>
          ) : (
            <Button variant="secondary" onClick={stop}>
              <Pause className="h-3.5 w-3.5" /> 暂停
            </Button>
          )}
          <Button variant="secondary" onClick={() => start(true)} disabled={active}>
            <RotateCw className="h-3.5 w-3.5" /> 从头读取
          </Button>
          <Button
            variant="secondary"
            onClick={() => setEvents([])}
            disabled={events.length === 0}
            ariaLabel="清空事件列表"
          >
            <Trash2 className="h-3.5 w-3.5" /> 清空
          </Button>
        </>
      }
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs">
        <div className="flex items-center gap-2" aria-live="polite">
          <Badge
            className={
              status === 'live'
                ? 'bg-emerald-50 text-emerald-700 ring-emerald-600/20'
                : status === 'error'
                  ? 'bg-rose-50 text-rose-700 ring-rose-600/20'
                  : 'bg-slate-100 text-slate-700 ring-slate-200'
            }
          >
            {STATUS_LABEL[status]}
          </Badge>
          <span className="text-slate-500">
            {formatCount(events.length)} 条
            {lastSequence !== undefined && ` · 最后 #${lastSequence}`}
          </span>
        </div>
        <label className="flex items-center gap-2 text-slate-600 font-medium">
          <input
            type="checkbox"
            checked={autoScroll}
            onChange={(event) => setAutoScroll(event.target.checked)}
            className={`rounded border-slate-300 text-indigo-600 ${FOCUS_RING}`}
          />
          自动滚动
        </label>
      </div>

      {failure && (
        <div className="mb-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
          {failure}
        </div>
      )}

      {events.length === 0 ? (
        <EmptyState
          title={active ? '等待新事件' : '尚未读取事件'}
          reason="连接由人工显式启动；管理流不包含模型输出、Prompt、工具参数/结果或审批原因。"
        />
      ) : (
        <div
          ref={listRef}
          className="max-h-[30rem] overflow-y-auto rounded-xl border border-slate-200 bg-slate-50/50 p-4"
          role="log"
          aria-label="Run 实时事件"
          aria-live="off"
        >
          {viewMode === 'waterfall' ? (
            <TraceWaterfallView events={events} />
          ) : (
            <RawEventsTable events={events} />
          )}
        </div>
      )}
    </Panel>
  );
}

/** 智能体执行步态瀑布流 (Trace Waterfall) */
function TraceWaterfallView({ events }: { events: AdminRunEventView[] }) {
  const firstTimestamp = events[0]?.atEpochMilli ?? 0;

  return (
    <div className="relative space-y-3.5 pl-6 before:absolute before:left-2.5 before:top-3 before:bottom-3 before:w-0.5 before:bg-slate-200">
      {events.map((event) => {
        const deltaMs = event.atEpochMilli - firstTimestamp;
        const deltaFormatted =
          deltaMs === 0
            ? '0ms'
            : deltaMs < 1000
              ? `+${deltaMs}ms`
              : `+${(deltaMs / 1000).toFixed(2)}s`;

        const style = getEventStyle(event);

        return (
          <div key={event.eventId} className="relative flex items-start gap-3">
            {/* 节点图标 */}
            <div
              className={`absolute -left-6 mt-1 flex h-5 w-5 items-center justify-center rounded-full ring-2 ring-white shadow-xs ${style.iconBg}`}
            >
              {style.icon}
            </div>

            {/* 卡片正文 */}
            <div
              className={`flex-1 rounded-xl border p-3.5 text-xs transition shadow-xs hover:border-slate-300 ${style.cardBorder} ${style.cardBg}`}
            >
              <div className="flex flex-wrap items-center justify-between gap-2 mb-1.5">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-[11px] text-slate-400">#{event.sequence}</span>
                  <span className="font-semibold text-slate-900">{style.label}</span>
                  <Badge className="font-mono text-[10px] bg-slate-100 text-slate-700 ring-slate-200">
                    {event.eventType}
                  </Badge>
                  {event.step !== undefined && event.step !== null && (
                    <Badge className="bg-slate-100 text-[10px] text-slate-600 ring-slate-200">
                      Step {event.step}
                    </Badge>
                  )}
                </div>
                <div className="flex items-center gap-2 text-slate-400">
                  <span className="font-mono text-[10px] font-medium text-indigo-600">{deltaFormatted}</span>
                  <time dateTime={new Date(event.atEpochMilli).toISOString()}>
                    {formatInstant(event.atEpochMilli)}
                  </time>
                </div>
              </div>

              {/* 细分指标与进展 */}
              <div className="space-y-1 text-slate-600 text-[11px]">
                {event.tool && (
                  <div className="flex items-center gap-1.5 text-blue-700 font-medium">
                    <Wrench className="h-3 w-3" />
                    <span>工具调用:</span>
                    <Mono className="font-bold text-blue-900">{event.tool.toolName ?? '未具名工具'}</Mono>
                    {event.tool.callId && (
                      <span className="text-slate-400 text-[10px]">({event.tool.callId.slice(0, 8)})</span>
                    )}
                  </div>
                )}

                {event.usage && (
                  <div className="flex items-center gap-1.5 text-indigo-700">
                    <Cpu className="h-3 w-3" />
                    <span>Token 消耗:</span>
                    <span className="font-mono font-medium text-slate-900">
                      {formatCount(event.usage.totalTokens)}
                    </span>
                    <span className="text-slate-500">
                      (输入 {formatCount(event.usage.inputTokens)}, 输出 {formatCount(event.usage.outputTokens)})
                    </span>
                  </div>
                )}

                {event.context && (
                  <div className="flex items-center gap-1.5 text-purple-700">
                    <Layers className="h-3 w-3" />
                    <span>上下文预估:</span>
                    <span className="font-mono font-medium text-slate-900">
                      {formatCount(event.context.estimatedTokens)} tokens
                    </span>
                    {event.context.droppedMessages > 0 && (
                      <span className="text-amber-700 font-medium text-[10px]">
                        (裁剪历史 {event.context.droppedMessages} 条)
                      </span>
                    )}
                  </div>
                )}

                {event.approval && (
                  <div className="flex items-center gap-1.5 text-amber-900 font-semibold">
                    <ShieldAlert className="h-3 w-3 text-amber-600" />
                    <span>触发审批介入:</span>
                    <span>{event.approval.toolName}</span>
                    <Badge className="bg-amber-100 text-amber-800 ring-amber-300 text-[10px]">
                      风险: {event.approval.risk}
                    </Badge>
                  </div>
                )}

                {[event.stage, event.status, event.category].some(Boolean) && (
                  <div className="flex flex-wrap items-center gap-1 pt-1 text-[10px] text-slate-500">
                    {event.stage && <span>阶段: {event.stage}</span>}
                    {event.status && <span>· 状态: {event.status}</span>}
                    {event.category && <span>· 分类: {event.category}</span>}
                  </div>
                )}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

/** 原始事件列表视图 */
function RawEventsTable({ events }: { events: AdminRunEventView[] }) {
  return (
    <div className="space-y-1">
      {events.map((event) => (
        <div
          key={event.eventId}
          className="grid gap-1 border-b border-slate-200/80 bg-white px-3 py-2 text-xs rounded-lg last:border-b-0 hover:bg-slate-50 transition md:grid-cols-[5.5rem_12rem_1fr_auto]"
        >
          <Mono className="text-slate-400">#{event.sequence}</Mono>
          <span className="font-medium text-slate-900">{event.eventType}</span>
          <span className="min-w-0 text-slate-600">
            {[event.stage, event.status, event.category].filter(Boolean).join(' · ') || '—'}
            {event.tool?.toolName && (
              <>
                {' · 工具 '}
                <Mono className="font-semibold text-slate-800">{event.tool.toolName}</Mono>
              </>
            )}
            {event.usage && ` · ${formatCount(event.usage.totalTokens)} tokens`}
          </span>
          <time className="text-slate-400" dateTime={new Date(event.atEpochMilli).toISOString()}>
            {formatInstant(event.atEpochMilli)}
          </time>
        </div>
      ))}
    </div>
  );
}

function getEventStyle(event: AdminRunEventView): {
  icon: React.ReactNode;
  iconBg: string;
  cardBorder: string;
  cardBg: string;
  label: string;
} {
  const type = event.eventType.toLowerCase();
  const status = (event.status ?? '').toLowerCase();

  if (type.includes('fail') || status === 'failed') {
    return {
      icon: <AlertCircle className="h-3 w-3 text-rose-600" />,
      iconBg: 'bg-rose-100 text-rose-700',
      cardBorder: 'border-rose-200',
      cardBg: 'bg-rose-50/50',
      label: '执行异常或失败',
    };
  }
  if (type.includes('approval') || event.approval) {
    return {
      icon: <ShieldAlert className="h-3 w-3 text-amber-600" />,
      iconBg: 'bg-amber-100 text-amber-700',
      cardBorder: 'border-amber-200',
      cardBg: 'bg-amber-50/50',
      label: '人机协同与审批授权',
    };
  }
  if (type.includes('model') || event.stage === 'model') {
    return {
      icon: <Cpu className="h-3 w-3 text-indigo-600" />,
      iconBg: 'bg-indigo-100 text-indigo-700',
      cardBorder: 'border-indigo-200',
      cardBg: 'bg-indigo-50/40',
      label: 'LLM 规划与模型调用',
    };
  }
  if (type.includes('tool') || event.tool) {
    return {
      icon: <Wrench className="h-3 w-3 text-blue-600" />,
      iconBg: 'bg-blue-100 text-blue-700',
      cardBorder: 'border-blue-200',
      cardBg: 'bg-blue-50/40',
      label: '工具执行与副作用',
    };
  }
  if (type.includes('context')) {
    return {
      icon: <Layers className="h-3 w-3 text-purple-600" />,
      iconBg: 'bg-purple-100 text-purple-700',
      cardBorder: 'border-purple-200',
      cardBg: 'bg-purple-50/40',
      label: '上下文组装与压缩',
    };
  }
  if (type.includes('completed') || status === 'succeeded' || status === 'completed') {
    return {
      icon: <CheckCircle2 className="h-3 w-3 text-emerald-600" />,
      iconBg: 'bg-emerald-100 text-emerald-700',
      cardBorder: 'border-emerald-200',
      cardBg: 'bg-emerald-50/50',
      label: '步骤达成与结算',
    };
  }
  if (type.includes('started') || type.includes('created')) {
    return {
      icon: <Play className="h-3 w-3 text-blue-600" />,
      iconBg: 'bg-blue-100 text-blue-700',
      cardBorder: 'border-blue-200',
      cardBg: 'bg-blue-50/40',
      label: '智能体启动',
    };
  }

  return {
    icon: <GitCommit className="h-3 w-3 text-slate-500" />,
    iconBg: 'bg-slate-100 text-slate-600',
    cardBorder: 'border-slate-200',
    cardBg: 'bg-white',
    label: event.eventType,
  };
}
