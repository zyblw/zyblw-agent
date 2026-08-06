/** 管理台共用的展示格式化。集中在一处，避免同一个时间戳在不同面板呈现出不同精度。 */

/** 把 epoch 毫秒渲染成本地时间；`0` 表示后端从未写入过该时间，显示占位符而不是 1970 年。 */
export function formatInstant(epochMilli: number | null | undefined): string {
  if (!epochMilli) return '—';
  return new Date(epochMilli).toLocaleString('zh-CN', { hour12: false });
}

/** 相对时间，用于"多久之前更新"这类值班判断。 */
export function formatRelative(epochMilli: number | null | undefined): string {
  if (!epochMilli) return '—';
  const deltaMs = Date.now() - epochMilli;
  if (deltaMs < 0) return '刚刚';
  const seconds = Math.floor(deltaMs / 1000);
  if (seconds < 60) return `${seconds} 秒前`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  return `${Math.floor(hours / 24)} 天前`;
}

/** 毫秒时长的紧凑渲染。 */
export function formatDuration(millis: number | null | undefined): string {
  if (millis === null || millis === undefined) return '—';
  if (millis < 1000) return `${millis} ms`;
  if (millis < 60_000) return `${(millis / 1000).toFixed(1)} s`;
  const minutes = Math.floor(millis / 60_000);
  const seconds = Math.round((millis % 60_000) / 1000);
  return `${minutes}m ${seconds}s`;
}

/** 千分位整数。 */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN');
}

/** 字节数的二进制单位渲染。 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—';
  const units = ['B', 'KiB', 'MiB', 'GiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`;
}

/** 得分渲染；检索信号和评测分数都可能是任意实数，固定小数位便于纵向比较。 */
export function formatScore(value: number | null | undefined, digits = 4): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return value.toFixed(digits);
}

/** 百分比渲染。 */
export function formatPercent(ratio: number | null | undefined, digits = 1): string {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) return '—';
  return `${(ratio * 100).toFixed(digits)}%`;
}

/**
 * 把逗号或换行分隔的输入解析成去重列表。
 *
 * 工具白名单和权限集合在界面上是自由文本，但后端会拒绝含空名称的集合，因此这里必须过滤空片段而不是
 * 把校验推给一次失败的请求。
 */
export function parseList(input: string): string[] {
  return Array.from(
    new Set(
      input
        .split(/[,\n]/)
        .map((item) => item.trim())
        .filter((item) => item.length > 0),
    ),
  );
}

/** Run 状态到语义色的映射；未知状态回退到中性色而不是崩溃。 */
export function runStatusTone(status: string): string {
  switch (status) {
    case 'Succeeded':
      return 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30';
    case 'Failed':
      return 'text-rose-300 bg-rose-500/10 ring-rose-500/30';
    case 'Cancelled':
      return 'text-slate-300 bg-slate-500/10 ring-slate-500/30';
    case 'AwaitingApproval':
      return 'text-amber-300 bg-amber-500/10 ring-amber-500/30';
    case 'Running':
      return 'text-sky-300 bg-sky-500/10 ring-sky-500/30';
    default:
      return 'text-slate-300 bg-slate-500/10 ring-slate-500/30';
  }
}

/** 摄入任务状态到语义色的映射。 */
export function ingestionStatusTone(status: string): string {
  if (status === 'Completed') return 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30';
  if (status === 'Failed') return 'text-rose-300 bg-rose-500/10 ring-rose-500/30';
  return 'text-sky-300 bg-sky-500/10 ring-sky-500/30';
}
