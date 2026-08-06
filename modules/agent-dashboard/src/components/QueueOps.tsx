'use client';

/**
 * 队列运维：积压快照与死信人工重排。
 *
 * 这个页面是值班界面，因此数据自己刷新而不是等人按按钮。死信清单刻意不含命令正文：审批决定、取消原因和
 * 重试理由属于业务事实，判断"要不要重排"只需要命令类型、失败分类和尝试次数。
 */

import React from 'react';
import { AlertOctagon, Clock, RefreshCw } from 'lucide-react';
import { useDeadLetters, useQueueSnapshot, useRetryDeadLetter } from '@/lib/queries';
import { useToast } from '@/lib/toast';
import { formatCount, formatDuration, formatInstant, formatRelative } from '@/lib/format';
import {
  Badge,
  Button,
  CopyableId,
  EmptyState,
  ErrorBanner,
  LoadingRows,
  Panel,
  StatCard,
} from '@/components/ui';

export function QueueOps() {
  const queue = useQueueSnapshot();
  const deadLetters = useDeadLetters(50);
  const retry = useRetryDeadLetter();
  const { notify } = useToast();

  const snapshot = queue.data;

  return (
    <div className="space-y-4 p-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">
        <StatCard label="排队命令" value={formatCount(snapshot?.queuedCommands)} />
        <StatCard label="可派发 Run" value={formatCount(snapshot?.dispatchableRuns)} />
        <StatCard label="持有租约" value={formatCount(snapshot?.leasedRuns)} tone="good" />
        <StatCard
          label="租约过期"
          value={formatCount(snapshot?.expiredLeases)}
          tone={(snapshot?.expiredLeases ?? 0) > 0 ? 'warn' : 'neutral'}
          hint="Worker 崩溃后待回收"
        />
        <StatCard
          label="死信"
          value={formatCount(snapshot?.deadLetterCommands)}
          tone={(snapshot?.deadLetterCommands ?? 0) > 0 ? 'danger' : 'neutral'}
          hint="需人工处理"
        />
        <StatCard
          label="最久等待"
          value={formatDuration(snapshot?.oldestDispatchableAgeMillis ?? null)}
          tone={
            (snapshot?.oldestDispatchableAgeMillis ?? 0) > 60_000 ? 'warn' : 'neutral'
          }
          hint="队首命令年龄"
        />
      </div>

      <ErrorBanner error={queue.error} context="读取队列快照" />

      {snapshot && (
        <div className="flex items-center gap-1.5 text-[11px] text-slate-500">
          <Clock className="h-3 w-3" />
          快照采集于 {formatInstant(snapshot.capturedAtEpochMilli)}，每 5 秒自动刷新
        </div>
      )}

      <Panel
        title="死信命令"
        description="重试次数耗尽后进入死信；重排会把命令重新放回队列并递增人工重试计数"
        actions={
          <Button
            variant="secondary"
            onClick={() => void deadLetters.refetch()}
            disabled={deadLetters.isFetching}
          >
            <RefreshCw className={`h-3 w-3 ${deadLetters.isFetching ? 'animate-spin' : ''}`} /> 刷新
          </Button>
        }
      >
        <ErrorBanner error={deadLetters.error} context="读取死信清单" />
        <ErrorBanner error={retry.error} context="重排死信命令" />

        {deadLetters.isPending ? (
          <LoadingRows rows={3} />
        ) : (deadLetters.data?.length ?? 0) === 0 ? (
          <EmptyState title="没有死信命令" reason="队列健康：所有命令都在重试预算内完成或仍在处理中。" />
        ) : (
          <div className="space-y-2">
            {deadLetters.data?.map((command) => (
              <div
                key={command.commandId}
                className="rounded-lg border border-rose-900/40 bg-rose-950/10 px-3 py-2"
              >
                <div className="flex items-center gap-2 text-xs">
                  <AlertOctagon className="h-3.5 w-3.5 shrink-0 text-rose-400" />
                  <Badge className="text-rose-300 bg-rose-500/10 ring-rose-500/30">
                    {command.commandType}
                  </Badge>
                  <CopyableId
                    value={command.commandId}
                    label="命令 ID"
                    truncate={8}
                    className="text-slate-400"
                  />
                  <span className="text-slate-500">Run</span>
                  <CopyableId value={command.runId} label="Run ID" truncate={8} className="text-slate-400" />
                  <span className="ml-auto text-[11px] text-slate-500">
                    {formatRelative(command.updatedAtEpochMilli)}
                  </span>
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-x-3 text-[11px] text-slate-500">
                  <span>自动尝试 {command.attempt} 次</span>
                  <span>人工重排 {command.manualRetryCount} 次</span>
                  <span>创建于 {formatInstant(command.createdAtEpochMilli)}</span>
                </div>
                {command.lastFailure && (
                  <div className="mt-1.5 rounded bg-slate-950/60 px-2 py-1 text-[11px] text-rose-300/80">
                    {command.lastFailure}
                  </div>
                )}
                <div className="mt-2">
                  {/*
                    "已重排"不再由本地状态记住：一条重排失败的命令必须能被再次重排，而一个只增不减的本地
                    集合会把它永久禁用。禁用范围收窄到这一次请求在途期间，之后完全以服务端刷新回来的
                    manualRetryCount 和清单本身为准。
                  */}
                  <Button
                    variant="secondary"
                    disabled={retry.isPending && retry.variables === command.commandId}
                    onClick={() =>
                      retry.mutate(command.commandId, {
                        onSuccess: (result) =>
                          notify(
                            'success',
                            '死信已重新排队',
                            `${result.commandId} · 状态 ${result.status} · 人工重排 ${result.manualRetryCount} 次`,
                          ),
                        onError: (error) =>
                          notify('error', '重排失败', error instanceof Error ? error.message : String(error)),
                      })
                    }
                  >
                    <RefreshCw
                      className={`h-3 w-3 ${retry.isPending && retry.variables === command.commandId ? 'animate-spin' : ''}`}
                    />
                    {retry.isPending && retry.variables === command.commandId ? '提交中…' : '重新排队'}
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}

        <p className="mt-3 text-[11px] text-slate-600">
          重排前应先确认根因已消除。命令重新进入队列后会以新的租约被某个 Worker 领取，如果失败原因仍然存在，
          它只会再次耗尽重试预算回到这里。
        </p>
      </Panel>
    </div>
  );
}
