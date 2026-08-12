'use client';

/**
 * 控制台入口。
 *
 * 页签集合由后端能力探测结果决定，因此这里的第一件事是把 `capabilities` 拿到手。在拿到之前不渲染任何面板：
 * 一个只装了 Postgres 但没接 RAG 的部署点开知识库页只会看到 404，先探测再决定显示什么比让运维自己撞上更好。
 *
 * 当前页签放在 URL query 里而不是组件 state 里：运维需要把"你看一下这个 Run"发给同事，而一个刷新后就回到
 * 首页的地址做不到这件事。读取 query 需要 `useSearchParams`，它在预渲染时会挂起，因此整棵消费它的子树被一个
 * Suspense 边界包住——否则生产构建会直接失败。
 */

import React, { Suspense, useState } from 'react';
import { AlertTriangle, KeyRound, Loader2 } from 'lucide-react';
import { Header, tabButtonId, tabPanelId, visibleTabs, type DashboardTab } from '@/components/Header';
import { RunInspector } from '@/components/RunInspector';
import { RagInspector } from '@/components/RagInspector';
import { QueueOps } from '@/components/QueueOps';
import { ModelGovernance } from '@/components/ModelGovernance';
import { ConfigStudio } from '@/components/ConfigStudio';
import { SecurityArtifacts } from '@/components/SecurityArtifacts';
import { EvalAnalytics } from '@/components/EvalAnalytics';
import { useCapabilities } from '@/lib/queries';
import { useConnection } from '@/lib/connection';
import { useUrlState } from '@/lib/urlState';
import { Badge, Button, ErrorBanner, FOCUS_RING, Panel } from '@/components/ui';

const SHELL_CLASS =
  'flex min-h-screen flex-col bg-slate-950 text-slate-100 font-sans selection:bg-indigo-500 selection:text-white';

export default function Home() {
  return (
    <Suspense
      fallback={
        <div className={SHELL_CLASS}>
          <div className="flex flex-1 items-center justify-center gap-2 p-16 text-sm text-slate-500">
            <Loader2 className="h-4 w-4 animate-spin" /> 正在加载控制台…
          </div>
        </div>
      }
    >
      <Console />
    </Suspense>
  );
}

function Console() {
  const url = useUrlState();
  const { config, hasToken } = useConnection();
  const [connectionOpen, setConnectionOpen] = useState(false);
  const capabilities = useCapabilities(hasToken);

  // 生效页签由派生得到而不是在 effect 里纠正：URL 里的页签所依赖的能力在后端不存在时（切换环境或分享给
  // 另一套部署的同事后很常见），自动落回第一个可用页签，用户不会停在一个只会显示 404 的面板上。
  const tabs = visibleTabs(capabilities.data);
  const picked = url.get('tab');
  const activeTab: DashboardTab = tabs.find((tab) => tab.id === picked)?.id ?? tabs[0]?.id ?? 'runs';

  return (
    <div className={SHELL_CLASS}>
      <Header
        activeTab={activeTab}
        onTabChange={(tab) => url.set({ tab })}
        capabilities={capabilities.data}
        showTabs={hasToken}
        connectionOpen={connectionOpen}
        onToggleConnection={setConnectionOpen}
      />

      <main className="flex flex-1 flex-col">
        {!hasToken ? (
          <CredentialGate onOpenConnection={() => setConnectionOpen(true)} />
        ) : capabilities.isPending ? (
          <div className="flex items-center justify-center gap-2 p-16 text-sm text-slate-500">
            <Loader2 className="h-4 w-4 animate-spin" /> 正在探测后端管理能力…
          </div>
        ) : capabilities.error ? (
          <div className="mx-auto max-w-2xl space-y-3 p-8">
            <ErrorBanner error={capabilities.error} context="探测后端管理能力" />
            <div className="rounded-lg border border-slate-800 bg-slate-900/40 px-4 py-3 text-xs text-slate-400">
              <div className="mb-1.5 flex items-center gap-1.5 font-medium text-slate-200">
                <AlertTriangle className="h-3.5 w-3.5" /> 排查顺序
              </div>
              <ol className="list-decimal space-y-1 pl-4">
                {config.authMode === 'host-session' && (
                  <>
                    <li>
                      先在主站登录；管理台不维护第二套账号密码，而是复用站点的 HttpOnly 安全会话。
                    </li>
                    <li>
                      确认该业务账号的稳定 userId 已被授予 Agent 管理权限。普通用户知道地址也会被 403 拒绝。
                    </li>
                  </>
                )}
                <li>确认后端地址正确，且 <code className="text-slate-300">/api/v1/admin/capabilities</code> 可达。</li>
                <li>确认宿主已把 <code className="text-slate-300">AdminHttpApi</code> 的路由合并进 HTTP 应用。</li>
                <li>
                  确认凭据包含 <code className="text-slate-300">agent:admin:read</code> scope；管理接口一律要求显式
                  scope，缺失即拒绝。
                </li>
                <li>跨域部署时确认后端允许控制台来源的 CORS 预检。</li>
              </ol>
            </div>
          </div>
        ) : (
          <div
            role="tabpanel"
            id={tabPanelId(activeTab)}
            aria-labelledby={tabButtonId(activeTab)}
            tabIndex={0}
            className={`flex-1 outline-none ${FOCUS_RING}`}
          >
            {activeTab === 'runs' && <RunInspector capabilities={capabilities.data} />}
            {activeTab === 'rag' && <RagInspector />}
            {activeTab === 'queue' && <QueueOps />}
            {activeTab === 'models' && <ModelGovernance capabilities={capabilities.data} />}
            {activeTab === 'config' && <ConfigStudio />}
            {activeTab === 'security' && <SecurityArtifacts onOpenConfig={() => url.set({ tab: 'config' })} />}
            {activeTab === 'evals' && <EvalAnalytics />}
          </div>
        )}
      </main>
    </div>
  );
}

/**
 * 未提供凭据时的引导态。
 *
 * 不渲染任何面板：每一个都会立刻 401，界面会变成一片红色错误横幅，而真正的原因（还没填 token）被埋在其中
 * 最不显眼的位置。这里换成一次性说清楚需要什么、去哪填。
 */
function CredentialGate({ onOpenConnection }: { onOpenConnection: () => void }) {
  return (
    // 引导卡在竖直方向居中：它是空态下页面上唯一的内容，顶对齐会让下方留出一整屏空白，看起来像加载失败。
    <div className="mx-auto flex w-full max-w-2xl flex-1 flex-col justify-center p-8">
      <Panel
        title="需要管理凭据"
        description="管理接口一律要求显式 scope，缺失即拒绝；控制台在拿到凭据前不会向后端发起任何管理请求"
        actions={
          <Button onClick={onOpenConnection}>
            <KeyRound className="h-3 w-3" /> 填写连接与凭据
          </Button>
        }
      >
        <div className="space-y-3 text-xs text-slate-400">
          <p>
            管理面看到的是整个部署而不是单个 Run 的所有者视角，因此不能复用业务侧「归属即可读」的规则。请在右上角
            的连接设置里填写后端地址与 Bearer token。
          </p>

          <div className="space-y-1.5">
            <div className="flex items-start gap-2">
              <Badge className="text-sky-300 bg-sky-500/10 ring-sky-500/30">agent:admin:read</Badge>
              <span>只读聚合：Run 目录、队列积压、有效配置快照、模型目录、评测趋势。至少需要它，否则连能力探测都会被拒绝。</span>
            </div>
            <div className="flex items-start gap-2">
              <Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">agent:admin:write</Badge>
              <span>改变部署行为：工具白名单、审批策略、模型切换、死信重排、索引退役。蕴含读权限。</span>
            </div>
            <div className="flex items-start gap-2">
              <Badge className="text-rose-300 bg-rose-500/10 ring-rose-500/30">agent:admin:debug</Badge>
              <span>产生真实 Provider 费用：检索沙盒、文档摄入、模型探活。不被写权限蕴含，必须单独授予。</span>
            </div>
          </div>

          <p className="text-slate-500">
            框架不自带认证中间件，token 的含义由宿主的{' '}
            <code className="text-slate-400">AgentRequestContextResolver</code> 决定。token 只保存在
            sessionStorage，关闭标签页即失效；后端地址保存在 localStorage。
          </p>
        </div>
      </Panel>
    </div>
  );
}
