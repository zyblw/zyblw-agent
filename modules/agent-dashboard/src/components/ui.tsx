'use client';

/**
 * 管理台共用的展示原语 (Enterprise Clean Light Palette)。
 *
 * 采用整洁、清晰、现代的企业级浅色/白色体系（对齐 Stripe / GitHub / Linear）。
 * 加载、空态、错误三态在所有工作台面板中保持一致规范。
 */

import React, { useCallback, useEffect, useId, useState } from 'react';
import { Check, Copy, RotateCcw } from 'lucide-react';
import { AdminApiError } from '@/lib/adminClient';
import { useToast } from '@/lib/toast';

/**
 * 统一的可见焦点环。
 */
export const FOCUS_RING =
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-1 focus-visible:ring-offset-white';

/** 面板容器。 */
export function Panel({
  title,
  description,
  actions,
  children,
  className = '',
}: {
  title?: string;
  description?: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section className={`rounded-xl border border-slate-200 bg-white shadow-xs transition-all duration-200 ${className}`}>
      {(title || actions) && (
        <header className="flex items-start justify-between gap-4 border-b border-slate-100 px-5 py-4 bg-slate-50/60 rounded-t-xl">
          <div>
            {title && <h2 className="text-sm font-semibold tracking-tight text-slate-900 flex items-center gap-2">{title}</h2>}
            {description && <p className="mt-0.5 text-xs text-slate-500">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 flex-wrap items-center justify-end gap-2">{actions}</div>}
        </header>
      )}
      <div className="p-5">{children}</div>
    </section>
  );
}

/** 状态指示灯 */
export function StatusDot({ status }: { status: 'running' | 'success' | 'warn' | 'danger' | 'idle' }) {
  const styles = {
    running: 'bg-emerald-500 ring-emerald-500/30 animate-pulse',
    success: 'bg-emerald-500 ring-emerald-500/30',
    warn: 'bg-amber-500 ring-amber-500/30 animate-pulse',
    danger: 'bg-rose-500 ring-rose-500/30',
    idle: 'bg-slate-400 ring-slate-400/20',
  };
  return (
    <span className="relative flex h-2 w-2 items-center justify-center">
      <span className={`inline-block h-2 w-2 rounded-full ring-2 ${styles[status]}`} />
    </span>
  );
}

/** 首屏统计卡片。 */
export function StatCard({
  label,
  value,
  hint,
  tone = 'neutral',
  icon,
}: {
  label: string;
  value: React.ReactNode;
  hint?: string;
  tone?: 'neutral' | 'warn' | 'danger' | 'good' | 'indigo';
  icon?: React.ReactNode;
}) {
  const toneClasses = {
    warn: 'text-slate-900 border-amber-200 bg-amber-50/40 hover:border-amber-300',
    danger: 'text-slate-900 border-rose-200 bg-rose-50/40 hover:border-rose-300',
    good: 'text-slate-900 border-emerald-200 bg-emerald-50/40 hover:border-emerald-300',
    indigo: 'text-slate-900 border-indigo-200 bg-indigo-50/40 hover:border-indigo-300',
    neutral: 'text-slate-900 border-slate-200 bg-white hover:border-slate-300',
  };

  return (
    <div className={`relative overflow-hidden rounded-xl border p-4 shadow-xs transition-all duration-200 ${toneClasses[tone]}`}>
      <div className="flex items-center justify-between text-xs text-slate-500 font-medium">
        <span>{label}</span>
        {icon && <span className="opacity-70">{icon}</span>}
      </div>
      <div className="mt-2 text-2xl font-bold tracking-tight text-slate-900 tabular-nums">{value}</div>
      {hint && <div className="mt-1 text-[11px] text-slate-500 leading-relaxed">{hint}</div>}
    </div>
  );
}

/** 分数对比条 (用于 RAG 检索 Dense / Sparse / Rerank) */
export function ScoreBar({
  label,
  score,
  max = 1,
  color = 'indigo',
}: {
  label: string;
  score: number;
  max?: number;
  color?: 'indigo' | 'emerald' | 'amber' | 'sky';
}) {
  const pct = Math.min(100, Math.max(0, (score / max) * 100));
  const colorMap = {
    indigo: 'bg-indigo-600',
    emerald: 'bg-emerald-600',
    amber: 'bg-amber-500',
    sky: 'bg-sky-600',
  };

  return (
    <div className="space-y-1">
      <div className="flex items-center justify-between text-[11px]">
        <span className="text-slate-600 font-medium">{label}</span>
        <span className="font-mono text-slate-900 font-semibold tabular-nums">{score.toFixed(4)}</span>
      </div>
      <div className="h-1.5 w-full rounded-full bg-slate-100 overflow-hidden">
        <div
          className={`h-full rounded-full transition-all duration-300 ${colorMap[color]}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

/** 语义标签。 */
export function Badge({
  children,
  className = 'text-slate-700 bg-slate-100 ring-slate-200',
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-md px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset ${className}`}
    >
      {children}
    </span>
  );
}

/** 主操作按钮。 */
export function Button({
  children,
  onClick,
  disabled,
  variant = 'primary',
  type = 'button',
  title,
  ariaLabel,
}: {
  children: React.ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  variant?: 'primary' | 'secondary' | 'danger';
  type?: 'button' | 'submit';
  title?: string;
  ariaLabel?: string;
}) {
  const base = `inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-40 ${FOCUS_RING}`;
  const variants = {
    primary: 'bg-indigo-600 text-white hover:bg-indigo-700 shadow-xs active:bg-indigo-800',
    secondary: 'border border-slate-200 bg-white text-slate-700 hover:bg-slate-50 hover:text-slate-900 shadow-xs',
    danger: 'border border-rose-200 bg-rose-50 text-rose-700 hover:bg-rose-100 shadow-xs',
  };
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      title={title}
      aria-label={ariaLabel}
      className={`${base} ${variants[variant]}`}
    >
      {children}
    </button>
  );
}

/** 输入控件共用的外观；集中一处以免各面板的边框与内距逐渐分叉。 */
const CONTROL_CLASS = `w-full rounded-md border border-slate-300 bg-white px-2.5 py-1.5 text-xs text-slate-900 placeholder:text-slate-400 focus:outline-none focus:border-indigo-600 shadow-xs ${FOCUS_RING}`;

/**
 * 文本输入。
 */
export function TextInput({
  value,
  onChange,
  placeholder,
  label,
  type = 'text',
  className = '',
  error,
  hint,
  disabled,
  inputMode,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  label?: string;
  type?: string;
  className?: string;
  error?: string;
  hint?: string;
  disabled?: boolean;
  inputMode?: 'text' | 'numeric' | 'decimal';
}) {
  const describedBy = useId();
  const message = error ?? hint;
  return (
    <label className={`block ${className}`}>
      {label && <span className="mb-1 block text-xs font-medium text-slate-700">{label}</span>}
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        inputMode={inputMode}
        aria-invalid={error ? true : undefined}
        aria-describedby={message ? describedBy : undefined}
        onChange={(event) => onChange(event.target.value)}
        className={`${CONTROL_CLASS} ${error ? 'border-rose-400 ring-rose-200' : ''} disabled:opacity-50 disabled:bg-slate-50`}
      />
      {message && (
        <span id={describedBy} className={`mt-1 block text-[10px] ${error ? 'text-rose-600 font-medium' : 'text-slate-500'}`}>
          {message}
        </span>
      )}
    </label>
  );
}

/** 下拉选择。 */
export function Select({
  value,
  onChange,
  options,
  label,
  className = '',
  disabled,
  error,
  hint,
}: {
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string; disabled?: boolean }[];
  label?: string;
  className?: string;
  disabled?: boolean;
  error?: string;
  hint?: string;
}) {
  const describedBy = useId();
  const message = error ?? hint;
  return (
    <label className={`block ${className}`}>
      {label && <span className="mb-1 block text-xs font-medium text-slate-700">{label}</span>}
      <select
        value={value}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        aria-describedby={message ? describedBy : undefined}
        onChange={(event) => onChange(event.target.value)}
        className={`${CONTROL_CLASS} ${error ? 'border-rose-400 ring-rose-200' : ''} disabled:opacity-50 disabled:bg-slate-50`}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
      </select>
      {message && (
        <span id={describedBy} className={`mt-1 block text-[10px] ${error ? 'text-rose-600 font-medium' : 'text-slate-500'}`}>
          {message}
        </span>
      )}
    </label>
  );
}

/**
 * 错误提示。
 */
export function ErrorBanner({ error, context }: { error: unknown; context?: string }) {
  if (!error) return null;
  const api = error instanceof AdminApiError ? error : null;
  const advice = api?.isForbidden
    ? '当前凭据缺少所需的管理 scope（agent:admin:read / write / debug）。'
    : api?.isConflict
      ? '数据已被其他管理员修改，请重新加载后再提交。'
      : api?.isMissingCapability
        ? '后端未装配该管理能力，请检查宿主是否提供了对应的适配器。'
        : api?.category === 'network'
          ? '无法连接后端，请确认服务地址、CORS 与网络可达性。'
          : undefined;
  const message = error instanceof Error ? error.message : String(error);
  return (
    <div className="rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-xs text-rose-800 shadow-xs">
      <div className="font-semibold text-rose-900">
        {context ? `${context}失败` : '请求失败'}
        {api ? `（${api.category}${api.status ? ` / HTTP ${api.status}` : ''}）` : ''}
      </div>
      <div className="mt-0.5 text-rose-700">{message}</div>
      {advice && <div className="mt-1 text-rose-600 font-medium">{advice}</div>}
    </div>
  );
}

/** 空状态。 */
export function EmptyState({ title, reason }: { title: string; reason?: string }) {
  return (
    <div className="rounded-lg border border-dashed border-slate-200 bg-slate-50/50 px-4 py-8 text-center">
      <div className="text-sm font-medium text-slate-700">{title}</div>
      {reason && <div className="mx-auto mt-1 max-w-lg text-xs text-slate-500">{reason}</div>}
    </div>
  );
}

/** 加载占位。 */
export function LoadingRows({ rows = 3 }: { rows?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="h-8 animate-pulse rounded-md bg-slate-100" />
      ))}
    </div>
  );
}

/** 键值对展示行。 */
export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-1.5 border-b border-slate-100 last:border-b-0">
      <span className="text-xs text-slate-500">{label}</span>
      <span className="text-right text-xs font-medium tabular-nums text-slate-900">{children}</span>
    </div>
  );
}

/** 等宽标识；Run ID、chunk ID 等需要精确比对的值。 */
export function Mono({
  children,
  className = '',
  title,
}: {
  children: React.ReactNode;
  className?: string;
  title?: string;
}) {
  return (
    <span title={title} className={`font-mono text-[11px] ${className}`}>
      {children}
    </span>
  );
}

/**
 * 把一个标识写入剪贴板。
 */
async function writeClipboard(value: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
      return true;
    }
  } catch {
    // 降级
  }
  try {
    const holder = document.createElement('textarea');
    holder.value = value;
    holder.setAttribute('readonly', '');
    holder.style.position = 'fixed';
    holder.style.opacity = '0';
    document.body.appendChild(holder);
    holder.select();
    const copied = document.execCommand('copy');
    document.body.removeChild(holder);
    return copied;
  } catch {
    return false;
  }
}

/** 复制按钮。 */
export function CopyButton({ value, label }: { value: string; label: string }) {
  const { notify } = useToast();
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1_500);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const copy = useCallback(
    (event: React.MouseEvent) => {
      event.stopPropagation();
      void writeClipboard(value).then((ok) => {
        if (ok) setCopied(true);
        else notify('error', '复制失败', '当前浏览器或上下文不允许写入剪贴板，请手动选中该标识复制。');
      });
    },
    [value, notify],
  );

  return (
    <button
      type="button"
      onClick={copy}
      aria-label={`复制${label}`}
      title={`复制${label}：${value}`}
      className={`inline-flex shrink-0 items-center rounded p-0.5 text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 ${FOCUS_RING}`}
    >
      {copied ? <Check className="h-3 w-3 text-emerald-600" /> : <Copy className="h-3 w-3" />}
    </button>
  );
}

/**
 * 可复制的标识。
 */
export function CopyableId({
  value,
  label,
  truncate,
  className = 'text-slate-800',
}: {
  value: string;
  label: string;
  truncate?: number;
  className?: string;
}) {
  const shown = truncate && value.length > truncate ? `${value.slice(0, truncate)}…` : value;
  return (
    <span className="inline-flex items-center gap-1">
      <Mono className={className} title={value}>
        {shown}
      </Mono>
      <CopyButton value={value} label={label} />
    </span>
  );
}

/**
 * 乐观锁冲突的恢复入口。
 */
export function ConflictNotice({
  onReload,
  reloading,
  description,
}: {
  onReload: () => void;
  reloading?: boolean;
  description?: string;
}) {
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 shadow-xs">
      <div className="min-w-0 flex-1">
        <div className="font-semibold text-amber-950">配置已被其他管理员修改</div>
        <div className="mt-0.5 text-amber-800">
          {description ?? '你的提交基于一个已过期的版本，因此被拒绝。重新加载会取回服务端最新值，你尚未保存的编辑将被丢弃。'}
        </div>
      </div>
      <Button variant="secondary" onClick={onReload} disabled={reloading}>
        <RotateCcw className={`h-3 w-3 ${reloading ? 'animate-spin' : ''}`} /> 重新加载最新配置
      </Button>
    </div>
  );
}
