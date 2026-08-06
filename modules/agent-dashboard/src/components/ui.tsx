'use client';

/**
 * 管理台共用的展示原语。
 *
 * 抽出来的目的不是"减少代码量"，而是让加载、空、错误三种状态在六个面板里表现一致。一个面板用骨架屏、
 * 另一个用空白、第三个静默失败，会让运维无法判断"看不到数据"到底意味着什么。
 */

import React, { useCallback, useEffect, useId, useState } from 'react';
import { Check, Copy, RotateCcw } from 'lucide-react';
import { AdminApiError } from '@/lib/adminClient';
import { useToast } from '@/lib/toast';

/**
 * 统一的可见焦点环。
 *
 * 集中成一个常量而不是让每个控件各写一遍：暗色主题下焦点环很容易被写成与背景对比不足的颜色，只有一处定义
 * 才能保证键盘用户在所有面板里都能看清自己在哪。
 */
export const FOCUS_RING =
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400 focus-visible:ring-offset-1 focus-visible:ring-offset-slate-950';

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
    <section className={`rounded-xl border border-slate-800 bg-slate-900/40 ${className}`}>
      {(title || actions) && (
        <header className="flex items-start justify-between gap-4 border-b border-slate-800 px-4 py-3">
          <div>
            {title && <h2 className="text-sm font-semibold text-slate-100">{title}</h2>}
            {description && <p className="mt-0.5 text-xs text-slate-400">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 flex-wrap items-center justify-end gap-2">{actions}</div>}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  );
}

/** 首屏统计卡片。 */
export function StatCard({
  label,
  value,
  hint,
  tone = 'neutral',
}: {
  label: string;
  value: React.ReactNode;
  hint?: string;
  tone?: 'neutral' | 'warn' | 'danger' | 'good';
}) {
  const toneClass =
    tone === 'warn'
      ? 'text-amber-300'
      : tone === 'danger'
        ? 'text-rose-300'
        : tone === 'good'
          ? 'text-emerald-300'
          : 'text-slate-100';
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/40 px-4 py-3">
      <div className="text-xs text-slate-400">{label}</div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${toneClass}`}>{value}</div>
      {hint && <div className="mt-1 text-xs text-slate-500">{hint}</div>}
    </div>
  );
}

/** 语义标签。 */
export function Badge({
  children,
  className = 'text-slate-300 bg-slate-500/10 ring-slate-500/30',
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
    primary: 'bg-indigo-500 text-white hover:bg-indigo-400',
    secondary: 'border border-slate-700 bg-slate-800/60 text-slate-200 hover:bg-slate-800',
    danger: 'border border-rose-800 bg-rose-950/50 text-rose-200 hover:bg-rose-900/50',
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
const CONTROL_CLASS = `w-full rounded-md border bg-slate-950/60 px-2.5 py-1.5 text-xs text-slate-100 placeholder:text-slate-600 focus:outline-none ${FOCUS_RING}`;

/**
 * 文本输入。
 *
 * `label` 用包裹式关联而不是 `htmlFor`+`id`：包裹关联对屏幕阅读器等价，且不需要调用方为每个控件想一个
 * 全局唯一 id。`error` 同时驱动 `aria-invalid` 与描述文本，让校验失败对键盘和读屏用户同样可感知。
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
      {label && <span className="mb-1 block text-xs text-slate-400">{label}</span>}
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        inputMode={inputMode}
        aria-invalid={error ? true : undefined}
        aria-describedby={message ? describedBy : undefined}
        onChange={(event) => onChange(event.target.value)}
        className={`${CONTROL_CLASS} ${error ? 'border-rose-700' : 'border-slate-700'} disabled:opacity-50`}
      />
      {message && (
        <span id={describedBy} className={`mt-1 block text-[10px] ${error ? 'text-rose-400' : 'text-slate-500'}`}>
          {message}
        </span>
      )}
    </label>
  );
}

/** 下拉选择；选项集合由调用方给出，空值项用于表达"沿用基线 / 不覆盖"。 */
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
      {label && <span className="mb-1 block text-xs text-slate-400">{label}</span>}
      <select
        value={value}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        aria-describedby={message ? describedBy : undefined}
        onChange={(event) => onChange(event.target.value)}
        className={`${CONTROL_CLASS} ${error ? 'border-rose-700' : 'border-slate-700'} disabled:opacity-50`}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
      </select>
      {message && (
        <span id={describedBy} className={`mt-1 block text-[10px] ${error ? 'text-rose-400' : 'text-slate-500'}`}>
          {message}
        </span>
      )}
    </label>
  );
}

/**
 * 错误提示。
 *
 * 按 HTTP 语义分流处置建议：授权不足要补 scope，版本冲突要重新加载，能力缺失要在后端装配对应适配器。
 * 三者都显示成"请求失败"会让运维在错误的方向上排查。
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
    <div className="rounded-lg border border-rose-900/60 bg-rose-950/30 px-3 py-2 text-xs text-rose-200">
      <div className="font-medium">
        {context ? `${context}失败` : '请求失败'}
        {api ? `（${api.category}${api.status ? ` / HTTP ${api.status}` : ''}）` : ''}
      </div>
      <div className="mt-0.5 text-rose-300/80">{message}</div>
      {advice && <div className="mt-1 text-rose-300/60">{advice}</div>}
    </div>
  );
}

/** 空状态；`reason` 用于说明"为什么是空的"，这通常比"暂无数据"有用得多。 */
export function EmptyState({ title, reason }: { title: string; reason?: string }) {
  return (
    <div className="rounded-lg border border-dashed border-slate-800 px-4 py-8 text-center">
      <div className="text-sm text-slate-400">{title}</div>
      {reason && <div className="mx-auto mt-1 max-w-lg text-xs text-slate-600">{reason}</div>}
    </div>
  );
}

/** 加载占位。 */
export function LoadingRows({ rows = 3 }: { rows?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="h-8 animate-pulse rounded-md bg-slate-800/50" />
      ))}
    </div>
  );
}

/** 键值对展示行。 */
export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-1">
      <span className="text-xs text-slate-500">{label}</span>
      <span className="text-right text-xs font-medium tabular-nums text-slate-200">{children}</span>
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
 *
 * `navigator.clipboard` 只在安全上下文可用，而管理台在内网常以裸 HTTP 访问，因此必须有降级路径：先试
 * 异步剪贴板 API，失败再退回一次性 textarea + `execCommand`，两者都不可用时给出明确回执，而不是静默失败
 * 让人以为已经复制成功。
 */
async function writeClipboard(value: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
      return true;
    }
  } catch {
    // 落到下面的降级路径；权限被拒和不安全上下文都会走到这里。
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

/** 复制按钮；`label` 只用于无障碍名称，界面上是一个图标。 */
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
      // 复制按钮常常嵌在可点击的行里；不拦住冒泡的话，一次复制会顺带改变选中项。
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
      className={`inline-flex shrink-0 items-center rounded p-0.5 text-slate-500 transition hover:bg-slate-800 hover:text-slate-200 ${FOCUS_RING}`}
    >
      {copied ? <Check className="h-3 w-3 text-emerald-400" /> : <Copy className="h-3 w-3" />}
    </button>
  );
}

/**
 * 可复制的标识。
 *
 * 表格里展示截断值以免一列 UUID 把其它列挤没，但 `title` 与复制按钮拿到的都是完整值：运维需要的是把它粘进
 * 日志查询，而不是抄一个前 8 位。
 */
export function CopyableId({
  value,
  label,
  truncate,
  className = 'text-slate-300',
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
 *
 * 409 之后重试同一份请求只会再次失败，因此这里给的不是"重试"而是"重新加载"：把服务端最新值取回来，并明确
 * 告知未保存的编辑已被丢弃。让运维在不知情的情况下把自己的旧快照再提交一次，会悄悄回滚别人刚做的改动。
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
    <div className="flex flex-wrap items-center gap-3 rounded-lg border border-amber-900/60 bg-amber-950/20 px-3 py-2 text-xs text-amber-200">
      <div className="min-w-0 flex-1">
        <div className="font-medium">配置已被其他管理员修改</div>
        <div className="mt-0.5 text-amber-300/80">
          {description ?? '你的提交基于一个已过期的版本，因此被拒绝。重新加载会取回服务端最新值，你尚未保存的编辑将被丢弃。'}
        </div>
      </div>
      <Button variant="secondary" onClick={onReload} disabled={reloading}>
        <RotateCcw className={`h-3 w-3 ${reloading ? 'animate-spin' : ''}`} /> 重新加载最新配置
      </Button>
    </div>
  );
}
