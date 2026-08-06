'use client';

/**
 * 写操作的结果通知。
 *
 * 管理台的写操作（配置覆盖、死信重排、索引退役、摄入提交、模型切换）大多没有立竿见影的界面变化：保存成功
 * 后表格看上去和保存前一样，运维无法区分"改成功了"和"按钮没响应"。因此每一次写入都必须有一条明确的回执。
 *
 * 自己实现而不是引入依赖：这里需要的全部能力是一个队列、一个定时器和一个 `aria-live` 区域，为此增加一个
 * 需要长期跟随升级的第三方包不划算。
 *
 * 通知只用于**结果回执**，不用于承载错误详情——错误仍由各面板内联的 `ErrorBanner` 展示，因为处置建议
 * （补 scope / 重新加载 / 检查装配）需要停留在出错的上下文里，而不是几秒后消失。
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { CheckCircle2, Info, X, XCircle } from 'lucide-react';

export type ToastTone = 'success' | 'error' | 'info';

interface Toast {
  id: number;
  tone: ToastTone;
  title: string;
  detail?: string;
}

interface ToastContextValue {
  notify: (tone: ToastTone, title: string, detail?: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

/** 自动消失时长；失败回执停留更久，因为它通常需要被读完而不只是被看到。 */
const DISMISS_MS: Record<ToastTone, number> = { success: 4_000, info: 5_000, error: 8_000 };

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const notify = useCallback((tone: ToastTone, title: string, detail?: string) => {
    nextId.current += 1;
    const id = nextId.current;
    // 只保留最近若干条：一次批量操作可能连续产生多条回执，无上限堆叠会盖住页面本身。
    setToasts((current) => [...current.slice(-4), { id, tone, title, detail }]);
  }, []);

  const value = useMemo<ToastContextValue>(() => ({ notify }), [notify]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        aria-live="polite"
        aria-atomic="false"
        className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2"
      >
        {toasts.map((toast) => (
          <ToastCard key={toast.id} toast={toast} onDismiss={dismiss} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}

const TONE_CLASS: Record<ToastTone, string> = {
  success: 'border-emerald-800/70 bg-emerald-950/80 text-emerald-100',
  error: 'border-rose-800/70 bg-rose-950/80 text-rose-100',
  info: 'border-slate-700 bg-slate-900/90 text-slate-100',
};

const TONE_ICON: Record<ToastTone, React.ComponentType<{ className?: string }>> = {
  success: CheckCircle2,
  error: XCircle,
  info: Info,
};

function ToastCard({ toast, onDismiss }: { toast: Toast; onDismiss: (id: number) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => onDismiss(toast.id), DISMISS_MS[toast.tone]);
    return () => window.clearTimeout(timer);
  }, [toast.id, toast.tone, onDismiss]);

  const Icon = TONE_ICON[toast.tone];
  return (
    <div
      className={`pointer-events-auto flex items-start gap-2 rounded-lg border px-3 py-2 text-xs shadow-lg backdrop-blur ${TONE_CLASS[toast.tone]}`}
    >
      <Icon className="mt-0.5 h-3.5 w-3.5 shrink-0" />
      <div className="min-w-0 flex-1">
        <div className="font-medium">{toast.title}</div>
        {toast.detail && <div className="mt-0.5 break-words opacity-80">{toast.detail}</div>}
      </div>
      <button
        type="button"
        onClick={() => onDismiss(toast.id)}
        aria-label="关闭通知"
        className="shrink-0 rounded opacity-60 transition hover:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-400"
      >
        <X className="h-3 w-3" />
      </button>
    </div>
  );
}

/** 发送一条结果回执；在 Provider 之外调用是装配错误，因此直接抛错而不是静默丢弃。 */
export function useToast(): ToastContextValue {
  const value = useContext(ToastContext);
  if (!value) throw new Error('useToast 必须在 ToastProvider 内使用');
  return value;
}
