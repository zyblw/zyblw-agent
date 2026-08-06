'use client';

/**
 * 把界面状态放进 URL query。
 *
 * 运维界面的每一次"你看到的是什么"都必须可分享、可刷新：一个只存在于组件 state 里的筛选条件，会让"我这边
 * 看到三个失败的 Run"变成一句无法复现的话。因此页签、筛选、选中项都以 query 参数为单一事实来源，而不是
 * 先放在 state 里再同步进 URL——后者需要两个方向的同步逻辑，且必然出现二者不一致的中间态。
 *
 * 写入一律用 `replace` 而不是 `push`：勾选一个状态过滤不该在浏览器历史里留下一条记录，否则返回键会变成
 * "逐个撤销我刚才的筛选"。
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

/** 一次可以原子提交的多参数更新；`null` 与空串表示删除该参数。 */
export type UrlPatch = Record<string, string | null | undefined>;

export interface UrlState {
  /** 读取一个参数；缺失时返回 `fallback`。 */
  get: (key: string, fallback?: string) => string;
  /** 读取一个逗号分隔的多值参数。 */
  getList: (key: string) => string[];
  /** 读取一个布尔参数；只有字面量 `1` 视为真，避免 `?awaiting=false` 被读成真。 */
  getFlag: (key: string) => boolean;
  /** 原子更新若干参数。多个参数必须一次提交，分多次调用会让后一次覆盖掉前一次尚未写回的快照。 */
  set: (patch: UrlPatch) => void;
}

export function useUrlState(): UrlState {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const set = useCallback(
    (patch: UrlPatch) => {
      const next = new URLSearchParams(searchParams.toString());
      for (const [key, value] of Object.entries(patch)) {
        if (value === null || value === undefined || value === '') next.delete(key);
        else next.set(key, value);
      }
      const query = next.toString();
      router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
    },
    [searchParams, router, pathname],
  );

  return useMemo<UrlState>(
    () => ({
      get: (key, fallback = '') => searchParams.get(key) ?? fallback,
      getList: (key) =>
        (searchParams.get(key) ?? '')
          .split(',')
          .map((item) => item.trim())
          .filter((item) => item.length > 0),
      getFlag: (key) => searchParams.get(key) === '1',
      set,
    }),
    [searchParams, set],
  );
}

/** 自由文本筛选项的三个值：输入框显示的草稿、提交草稿的函数、真正驱动查询的已提交值。 */
export type DebouncedUrlValue = [draft: string, setDraft: (next: string) => void, committed: string];

/**
 * 自由文本筛选项的防抖 URL 绑定。
 *
 * 勾选框、下拉框可以直写 URL，但文本框不行：租户 ID 敲 12 个字符会写 12 次 URL，也就是 12 个不同的
 * query key、12 次跨租户目录扫描。这里让输入框显示本地草稿、URL 在停顿后才落定，因此查询只发一次。
 *
 * 没有改成回车/失焦提交，因为那需要一份可以与 URL 长期分叉的本地状态，而"URL 是唯一事实来源"是整个
 * 深链设计的前提。防抖同时保住了两件事：打字是即时的，地址栏晚 300 毫秒定稿——没有人会在两次击键之间
 * 分享链接。
 */
export function useDebouncedUrlValue(key: string, delayMs = 300): DebouncedUrlValue {
  const url = useUrlState();
  const committed = url.get(key);
  const [draft, setDraft] = useState(committed);
  // 上一次我们见过的已提交值，用来区分"URL 因为我们自己的防抖写入而变化"与"URL 被外部改变了"。它存在
  // state 里而不是 ref 里：渲染期需要读它来做纠正，而渲染期读写 ref 会让结果依赖于渲染次数。
  const [seen, setSeen] = useState(committed);

  // 外部改动（深链打开、切页签、其它控件重置筛选）必须覆盖草稿。这里在渲染期比较并纠正，而不是用 effect
  // 同步：effect 会多渲染一轮，那一轮里输入框显示的还是已经不成立的旧草稿。
  if (committed !== seen) {
    setSeen(committed);
    if (committed !== draft) setDraft(committed);
  }

  useEffect(() => {
    if (draft === committed) return;
    const timer = setTimeout(() => {
      // 先记下即将写入的值，否则这次写入回流时会被上面的渲染期纠正误判成外部改动，从而把草稿冲掉。
      setSeen(draft);
      url.set({ [key]: draft });
    }, delayMs);
    return () => clearTimeout(timer);
  }, [draft, committed, key, delayMs, url]);

  return [draft, setDraft, committed];
}

/** 把多值参数编码成 query 值；空集合编码成 `null` 以便从 URL 中移除。 */
export function encodeList(values: readonly string[]): string | null {
  return values.length > 0 ? values.join(',') : null;
}

/** 把布尔参数编码成 query 值。 */
export function encodeFlag(value: boolean): string | null {
  return value ? '1' : null;
}
