'use client';

/**
 * 客户端 Provider 组合。
 *
 * `QueryClient` 通过 `useState` 初始化而不是模块级单例：Next.js 在开发热更新和服务端渲染下会多次求值模块，
 * 单例会让不同请求共享缓存。管理台的缓存里包含跨租户聚合，共享它是一个真实的越权风险。
 */

import React, { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConnectionProvider } from '@/lib/connection';
import { ToastProvider } from '@/lib/toast';
import { shouldRetry } from '@/lib/queries';

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: shouldRetry,
            // 管理台的数据都是他人可并发修改的部署状态，重新聚焦窗口时重新获取是正确的默认值。
            refetchOnWindowFocus: true,
            staleTime: 5_000,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={client}>
      <ConnectionProvider>
        <ToastProvider>{children}</ToastProvider>
      </ConnectionProvider>
    </QueryClientProvider>
  );
}
