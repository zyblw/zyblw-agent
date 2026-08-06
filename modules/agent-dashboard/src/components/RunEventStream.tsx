'use client';

import React, { useEffect, useRef, useState } from 'react';
import { Pause, Radio, RotateCw, Trash2 } from 'lucide-react';
import { useConnection } from '@/lib/connection';
import { AdminApiError, adminApi } from '@/lib/adminClient';
import { formatCount, formatInstant } from '@/lib/format';
import type { AdminRunEventView, ErrorResponse, RunSummaryView } from '@/types/admin';
import { Badge, Button, EmptyState, FOCUS_RING, Mono, Panel } from '@/components/ui';

const MAX_EVENTS = 500;
const RECENT_WINDOW = 200;
const MAX_RETRY_DELAY_MS = 15_000;

type StreamStatus = 'idle' | 'connecting' | 'live' | 'retrying' | 'paused' | 'caught-up' | 'error';

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
 * 单 Run 的低敏耐久事件调试器。
 *
 * 连接必须由运维显式启动，避免选中一行就创建长期数据库轮询。网络故障从最后确认的 sequence 指数退避重连；
 * 主动暂停和服务端正常结束不会重连。内存只保留最后 500 条，长 Run 不会拖垮浏览器。
 */
export function RunEventStream({ run }: { run: RunSummaryView }) {
  const { config } = useConnection();
  const [events, setEvents] = useState<AdminRunEventView[]>([]);
  const [status, setStatus] = useState<StreamStatus>('idle');
  const [failure, setFailure] = useState<string | null>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  /** 供渲染使用的最后确认序号；`lastSequenceRef` 是异步重连逻辑的同步事实源，不能在渲染期读取。 */
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
    setEvents([]);
    const initialSequence = fromBeginning
      ? -1
      : Math.max(-1, run.lastEventSequence - RECENT_WINDOW);
    lastSequenceRef.current = initialSequence;
    setLastSequence(undefined);
    void open(initialSequence, 0);
  }

  async function open(afterSequence: number, retryAttempt: number): Promise<void> {
    if (intentionalStopRef.current) return;
    const controller = new AbortController();
    controllerRef.current = controller;
    setStatus(retryAttempt === 0 ? 'connecting' : 'retrying');
    /** 本次连接是否已收到事件；决定失败后重启退避还是继续放大间隔。 */
    let delivered = false;

    try {
      await adminApi.streamRunEvents(config, run.runId, {
        afterSequence,
        signal: controller.signal,
        onMessage(message) {
          if (message.event === 'stream_error') {
            let streamError: ErrorResponse = { category: 'stream', message: '事件流在服务端中断' };
            try {
              streamError = JSON.parse(message.data) as ErrorResponse;
            } catch {
              // 使用稳定的本地兜底，不显示无法验证的原始响应。
            }
            throw new AdminApiError(503, streamError.category, streamError.message);
          }

          const event = parseEvent(message.data);
          if (event.runId !== run.runId) {
            throw new AdminApiError(0, 'event-run-mismatch', '事件流返回了其他 Run 的事件');
          }
          if (
            lastSequenceRef.current !== undefined &&
            event.sequence !== lastSequenceRef.current + 1
          ) {
            throw new AdminApiError(0, 'event-sequence-gap', '事件序号不连续，已停止显示以避免误判');
          }
          lastSequenceRef.current = event.sequence;
          delivered = true;
          setLastSequence(event.sequence);
          setEvents((current) => [...current, event].slice(-MAX_EVENTS));
          setStatus('live');
          setFailure(null);
        },
      });
      if (!intentionalStopRef.current) setStatus('caught-up');
    } catch (error) {
      if (controller.signal.aborted || intentionalStopRef.current) return;
      // 中止本次 fetch 再重连。`stream_error`、序号跳变和 Run 不匹配都是从 onMessage 抛出的，
      // 此时响应体仍然打开；不显式中止会在服务端留下一条持续轮询数据库的 SSE 连接。
      controller.abort();
      // 已经收到过事件说明连接本身是健康的，随后的故障应从最短间隔重新退避，
      // 否则一条长时间运行的流在几次偶发断线后会永久停在最大间隔上。
      const attempt = delivered ? 0 : retryAttempt;
      const delay = Math.min(1_000 * 2 ** attempt, MAX_RETRY_DELAY_MS);
      setStatus('retrying');
      setFailure(`${errorMessage(error)} ${Math.round(delay / 1_000)} 秒后重试。`);
      retryTimerRef.current = setTimeout(() => {
        void open(lastSequenceRef.current ?? afterSequence, attempt + 1);
      }, delay);
    }
  }

  const active = status === 'connecting' || status === 'live' || status === 'retrying';

  return (
    <Panel
      title="实时事件流"
      description={`低敏耐久事件；默认读取最近 ${RECENT_WINDOW} 条，断线后从最后 sequence 恢复`}
      actions={
        <>
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
                ? 'bg-emerald-500/10 text-emerald-300 ring-emerald-500/30'
                : status === 'error'
                  ? 'bg-rose-500/10 text-rose-300 ring-rose-500/30'
                  : 'bg-slate-800 text-slate-300 ring-slate-700'
            }
          >
            {STATUS_LABEL[status]}
          </Badge>
          <span className="text-slate-500">
            {formatCount(events.length)} 条
            {lastSequence !== undefined && ` · 最后 #${lastSequence}`}
          </span>
        </div>
        <label className="flex items-center gap-2 text-slate-400">
          <input
            type="checkbox"
            checked={autoScroll}
            onChange={(event) => setAutoScroll(event.target.checked)}
            className={`rounded border-slate-700 bg-slate-950 ${FOCUS_RING}`}
          />
          自动滚动
        </label>
      </div>

      {failure && (
        <div className="mb-3 rounded-md border border-amber-900/60 bg-amber-950/20 px-3 py-2 text-xs text-amber-200">
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
          className="max-h-[28rem] overflow-y-auto rounded-lg border border-slate-800"
          role="log"
          aria-label="Run 实时事件"
          aria-live="off"
        >
          {events.map((event) => (
            <div
              key={event.eventId}
              className="grid gap-1 border-b border-slate-900 px-3 py-2 text-xs last:border-b-0 md:grid-cols-[5.5rem_12rem_1fr_auto]"
            >
              <Mono className="text-slate-500">#{event.sequence}</Mono>
              <span className="font-medium text-slate-200">{event.eventType}</span>
              <span className="min-w-0 text-slate-400">
                {[event.stage, event.status, event.category].filter(Boolean).join(' · ') || '—'}
                {event.tool?.toolName && (
                  <>
                    {' · 工具 '}
                    <Mono>{event.tool.toolName}</Mono>
                  </>
                )}
                {event.usage && ` · ${formatCount(event.usage.totalTokens)} tokens`}
              </span>
              <time className="text-slate-600" dateTime={new Date(event.atEpochMilli).toISOString()}>
                {formatInstant(event.atEpochMilli)}
              </time>
            </div>
          ))}
        </div>
      )}
    </Panel>
  );
}
