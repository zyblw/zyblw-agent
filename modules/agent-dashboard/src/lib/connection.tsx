'use client';

/**
 * 后端连接与凭据的应用级上下文。
 *
 * 地址和 token 放在 React 状态而不是构建期环境变量里，是因为同一份静态构建会被运维用来连开发、预发和
 * 生产实例。环境变量只提供**默认值**，运行时仍可切换。
 *
 * token 保存在 `sessionStorage` 而不是 `localStorage`：管理 token 能改工具白名单和审批策略，让它在关闭
 * 标签页后继续留在磁盘上没有必要的收益。地址保存在 `localStorage`，因为它不是凭据。
 */

import React, { createContext, useCallback, useContext, useMemo, useSyncExternalStore } from 'react';
import type { AdminClientConfig } from '@/lib/adminClient';

const BASE_URL_KEY = 'zyblw-agent-dashboard.base-url';
const TOKEN_KEY = 'zyblw-agent-dashboard.token';

/** 构建期默认地址；未设置时回退到本地开发端口。 */
const DEFAULT_BASE_URL = process.env.NEXT_PUBLIC_AGENT_BASE_URL ?? 'http://localhost:8080';

/**
 * 把浏览器存储当作外部数据源订阅。
 *
 * 这里用 `useSyncExternalStore` 而不是"effect 里读存储再 setState"：后者在服务端渲染时先产出默认值，
 * 挂载后再改成存储值，会多一轮级联渲染，也会让输入框在首帧闪一次错误的地址。`getServerSnapshot` 让服务端
 * 明确使用默认值，React 负责在 hydration 后无警告地切到客户端快照。
 */
const storageListeners = new Set<() => void>();

/** 通知所有订阅者存储已变化。写入必须经由它，否则同页面的其他消费者不会更新。 */
function emitStorageChange(): void {
  for (const listener of storageListeners) listener();
}

function subscribe(listener: () => void): () => void {
  storageListeners.add(listener);
  // 同时监听 storage 事件，让另一个标签页改地址后当前页面也能跟上。
  window.addEventListener('storage', listener);
  return () => {
    storageListeners.delete(listener);
    window.removeEventListener('storage', listener);
  };
}

/** 读取一项持久化值；服务端与首帧统一返回默认值。 */
function usePersistedValue(storage: 'local' | 'session', key: string, fallback: string): string {
  return useSyncExternalStore(
    subscribe,
    () => {
      const store = storage === 'local' ? window.localStorage : window.sessionStorage;
      return store.getItem(key) ?? fallback;
    },
    () => fallback,
  );
}

interface ConnectionContextValue {
  config: AdminClientConfig;
  /** 凭据是否已提供；未提供时所有管理请求都会返回 401/403。 */
  hasToken: boolean;
  setBaseUrl: (value: string) => void;
  setToken: (value: string) => void;
  clearToken: () => void;
}

const ConnectionContext = createContext<ConnectionContextValue | null>(null);

export function ConnectionProvider({ children }: { children: React.ReactNode }) {
  const baseUrl = usePersistedValue('local', BASE_URL_KEY, DEFAULT_BASE_URL);
  const token = usePersistedValue('session', TOKEN_KEY, '');

  const setBaseUrl = useCallback((value: string) => {
    window.localStorage.setItem(BASE_URL_KEY, value);
    emitStorageChange();
  }, []);

  const setToken = useCallback((value: string) => {
    window.sessionStorage.setItem(TOKEN_KEY, value);
    emitStorageChange();
  }, []);

  const clearToken = useCallback(() => {
    window.sessionStorage.removeItem(TOKEN_KEY);
    emitStorageChange();
  }, []);

  const value = useMemo<ConnectionContextValue>(
    () => ({
      config: { baseUrl, token: token || undefined },
      hasToken: token.length > 0,
      setBaseUrl,
      setToken,
      clearToken,
    }),
    [baseUrl, token, setBaseUrl, setToken, clearToken],
  );

  return <ConnectionContext.Provider value={value}>{children}</ConnectionContext.Provider>;
}

/** 读取当前连接配置；在 Provider 之外调用是装配错误，因此直接抛错而不是返回默认值。 */
export function useConnection(): ConnectionContextValue {
  const value = useContext(ConnectionContext);
  if (!value) throw new Error('useConnection 必须在 ConnectionProvider 内使用');
  return value;
}
