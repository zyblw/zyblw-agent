'use client';

/**
 * 智能体控制台入口 (Studio App Shell - Clean Enterprise Light Palette)。
 *
 * 采用现代企业级双栏工作区布局：
 * 左侧：纯净清晰的功能侧边栏 (Sidebar)
 * 右侧：动态工作区画板 (Studio TopBar + Main Canvas)
 */

import React, { Suspense, useState } from 'react';
import { AlertTriangle, KeyRound, Loader2 } from 'lucide-react';
import { Sidebar } from '@/components/Sidebar';
import { Header, tabButtonId, tabPanelId, visibleTabs, type DashboardTab } from '@/components/Header';
import { RunInspector } from '@/components/RunInspector';
import { RagInspector } from '@/components/RagInspector';
import { QueueOps } from '@/components/QueueOps';
import { ModelGovernance } from '@/components/ModelGovernance';
import { ConfigStudio } from '@/components/ConfigStudio';
import { SecurityArtifacts } from '@/components/SecurityArtifacts';
import { EvalAnalytics } from '@/components/EvalAnalytics';
import { InspectPanel } from '@/components/InspectPanel';
import { HarnessPanel } from '@/components/HarnessPanel';
import { MemoryPanel } from '@/components/MemoryPanel';
import { useCapabilities } from '@/lib/queries';
import { useConnection } from '@/lib/connection';
import { useUrlState } from '@/lib/urlState';
import { Badge, Button, ErrorBanner, FOCUS_RING, Panel } from '@/components/ui';

export default function Home() {
  return (
    <Suspense
      fallback={
        <div className="flex h-screen w-screen items-center justify-center bg-slate-50 text-slate-500 font-sans">
          <div className="flex items-center gap-2.5 text-sm font-medium">
            <Loader2 className="h-5 w-5 animate-spin text-indigo-600" />
            <span>正在加载智能体控制台...</span>
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

  // 生效页签由能力派生得到
  const tabs = visibleTabs(capabilities.data);
  const picked = url.get('tab');
  const activeTab: DashboardTab = tabs.find((tab) => tab.id === picked)?.id ?? tabs[0]?.id ?? 'runs';

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-slate-50 text-slate-900 font-sans selection:bg-indigo-500 selection:text-white">
      {/* 现代智能体左侧导航栏 */}
      <Sidebar
        activeTab={activeTab}
        onTabChange={(tab) => url.set({ tab })}
        capabilities={capabilities.data}
        showTabs={hasToken}
        connectionOpen={connectionOpen}
        onToggleConnection={setConnectionOpen}
      />

      {/* 主工作区画布 */}
      <div className="flex flex-1 flex-col overflow-hidden min-w-0">
        <Header
          activeTab={activeTab}
          onTabChange={(tab) => url.set({ tab })}
          capabilities={capabilities.data}
          showTabs={hasToken}
          connectionOpen={connectionOpen}
          onToggleConnection={setConnectionOpen}
        />

        <main className="flex-1 overflow-y-auto custom-scrollbar bg-slate-50">
          {!hasToken ? (
            <CredentialGate onOpenConnection={() => setConnectionOpen(true)} />
          ) : capabilities.isPending ? (
            <div className="flex h-64 items-center justify-center gap-2.5 text-sm font-medium text-slate-500">
              <Loader2 className="h-5 w-5 animate-spin text-indigo-600" />
              <span>正在探测后端管理能力与服务状态…</span>
            </div>
          ) : capabilities.error ? (
            <div className="mx-auto max-w-2xl space-y-4 p-8">
              <ErrorBanner error={capabilities.error} context="探测后端管理能力" />
              <div className="rounded-xl border border-slate-200 bg-white p-5 text-xs text-slate-600 shadow-xs">
                <div className="mb-2.5 flex items-center gap-1.5 font-semibold text-slate-900">
                  <AlertTriangle className="h-4 w-4 text-amber-500" /> 排查建议
                </div>
                <ol className="list-decimal space-y-2 pl-4">
                  {config.authMode === 'host-session' && (
                    <>
                      <li>先在主站登录；管理台不维护第二套账号密码，而是复用站点的 HttpOnly 安全会话。</li>
                      <li>确认该业务账号的稳定 userId 已被授予 Agent 管理权限。普通用户会被 403 拒绝。</li>
                    </>
                  )}
                  <li>确认后端地址正确，且 <code className="text-slate-800 bg-slate-100 px-1 py-0.5 rounded font-mono">/api/v1/admin/capabilities</code> 可达。</li>
                  <li>确认宿主已把 <code className="text-slate-800 bg-slate-100 px-1 py-0.5 rounded font-mono">AdminHttpApi</code> 的路由合并进 HTTP 应用。</li>
                  <li>
                    确认凭据包含 <code className="text-slate-800 bg-slate-100 px-1 py-0.5 rounded font-mono">agent:admin:read</code> scope；管理接口一律要求显式 scope。
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
              className={`min-h-full outline-none ${FOCUS_RING}`}
            >
              {activeTab === 'runs' && <RunInspector capabilities={capabilities.data} />}
              {activeTab === 'inspect' && <InspectPanel />}
              {activeTab === 'harness' && <HarnessPanel />}
              {activeTab === 'memory' && <MemoryPanel />}
              {activeTab === 'rag' && <RagInspector />}
              {activeTab === 'queue' && <QueueOps />}
              {activeTab === 'models' && <ModelGovernance />}
              {activeTab === 'config' && <ConfigStudio />}
              {activeTab === 'security' && <SecurityArtifacts onOpenConfig={() => url.set({ tab: 'config' })} />}
              {activeTab === 'evals' && <EvalAnalytics />}
            </div>
          )}
        </main>
      </div>
    </div>
  );
}

/** 未提供凭据时的引导态 */
function CredentialGate({ onOpenConnection }: { onOpenConnection: () => void }) {
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-1 flex-col justify-center p-8 mt-12">
      <Panel
        title="需要管理凭据"
        description="管理接口一律要求显式 scope，缺失即拒绝；控制台在拿到凭据前不会向后端发起任何管理请求"
        actions={
          <Button onClick={onOpenConnection}>
            <KeyRound className="h-3.5 w-3.5" /> 填写连接与凭据
          </Button>
        }
      >
        <div className="space-y-4 text-xs text-slate-600">
          <p className="leading-relaxed">
            管理面看到的是整个部署而不是单个 Run 的所有者视角，因此不能复用业务侧「归属即可读」的规则。请在右上角的连接设置里填写后端地址与 Bearer token。
          </p>

          <div className="space-y-2.5 pt-1">
            <div className="flex items-start gap-2.5">
              <Badge className="text-blue-700 bg-blue-50 ring-blue-600/20 font-mono">agent:admin:read</Badge>
              <span className="text-slate-600">只读聚合：Run 目录、队列积压、有效配置快照、模型目录、评测趋势。至少需要它，否则连能力探测都会被拒绝。</span>
            </div>
            <div className="flex items-start gap-2.5">
              <Badge className="text-amber-800 bg-amber-50 ring-amber-600/25 font-mono">agent:admin:write</Badge>
              <span className="text-slate-600">改变部署行为：工具白名单、审批策略、模型切换、死信重排。蕴含读权限。</span>
            </div>
            <div className="flex items-start gap-2.5">
              <Badge className="text-rose-700 bg-rose-50 ring-rose-600/20 font-mono">agent:admin:debug</Badge>
              <span className="text-slate-600">产生真实 Provider 费用：检索沙盒、模型探活。不被写权限蕴含；沙盒另外需要 knowledge:read。</span>
            </div>
            <div className="flex items-start gap-2.5">
              <Badge className="text-emerald-700 bg-emerald-50 ring-emerald-600/20 font-mono">knowledge:read / write</Badge>
              <span className="text-slate-600">知识清单与退役。不被 agent:admin:* 蕴含。</span>
            </div>
          </div>

          <p className="text-slate-500 pt-3 border-t border-slate-200">
            框架不自带认证中间件，token 的含义由宿主的{' '}
            <code className="text-slate-800 font-mono bg-slate-100 px-1 py-0.5 rounded">AgentRequestContextResolver</code> 决定。token 只保存在
            sessionStorage，关闭标签页即失效；后端地址保存在 localStorage。
          </p>
        </div>
      </Panel>
    </div>
  );
}
