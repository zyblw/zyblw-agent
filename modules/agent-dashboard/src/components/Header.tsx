'use client';

/**
 * 顶部导航、连接设置与外部观测入口。
 *
 * 页签可见性由后端 `capabilities` 决定而不是写死：一个只装了 Postgres 但没接 RAG 的部署点开知识库页面
 * 只会看到 404，把它藏起来比让运维自己发现更好。
 */

import React, { useRef } from 'react';
import {
  Activity,
  Cpu,
  Database,
  ExternalLink,
  KeyRound,
  Layers,
  ListChecks,
  Settings2,
  ShieldCheck,
} from 'lucide-react';
import { useConnection } from '@/lib/connection';
import { grafanaDashboardUrl } from '@/types/admin';
import type { AdminCapabilitiesView } from '@/types/admin';
import { Badge, FOCUS_RING, TextInput } from '@/components/ui';

export type DashboardTab = 'runs' | 'rag' | 'queue' | 'models' | 'config' | 'security' | 'evals';

interface TabDefinition {
  id: DashboardTab;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  /** 该页签依赖的后端能力；undefined 表示不依赖任何可选适配器。 */
  capability?: keyof AdminCapabilitiesView;
}

const TABS: TabDefinition[] = [
  { id: 'runs', label: '运行', icon: Activity, capability: 'runDirectory' },
  { id: 'rag', label: '知识库', icon: Database, capability: 'knowledge' },
  { id: 'queue', label: '队列', icon: Layers, capability: 'queueOps' },
  { id: 'models', label: '模型', icon: Cpu, capability: 'models' },
  { id: 'config', label: '配置', icon: Settings2, capability: 'runtimeConfig' },
  { id: 'security', label: '安全', icon: ShieldCheck, capability: 'runtimeConfig' },
  { id: 'evals', label: '评测', icon: ListChecks, capability: 'evalTrends' },
];

/** 返回在当前后端下应显示的页签。 */
export function visibleTabs(capabilities: AdminCapabilitiesView | undefined): TabDefinition[] {
  if (!capabilities) return TABS;
  return TABS.filter((tab) => !tab.capability || capabilities[tab.capability] === true);
}

/** 面板容器的 DOM id；页签用 `aria-controls` 指向它。 */
export function tabPanelId(tab: DashboardTab): string {
  return `panel-${tab}`;
}

/** 页签按钮的 DOM id；面板用 `aria-labelledby` 指回它。 */
export function tabButtonId(tab: DashboardTab): string {
  return `tab-${tab}`;
}

/**
 * 顶栏。
 *
 * 连接设置的展开状态由调用方持有：未提供凭据时的引导态需要一个"去填写"的入口，而它在面板区域而不是顶栏里。
 * 把这个状态留在 Header 内部就只能靠 ref 或事件总线去打开它。
 */
export function Header({
  activeTab,
  onTabChange,
  capabilities,
  showTabs = true,
  connectionOpen,
  onToggleConnection,
}: {
  activeTab: DashboardTab;
  onTabChange: (tab: DashboardTab) => void;
  capabilities: AdminCapabilitiesView | undefined;
  /** 未提供凭据时隐藏页签：此时后端能力尚未探测，列出全部页签等于承诺一批可能并不存在的面板。 */
  showTabs?: boolean;
  connectionOpen: boolean;
  onToggleConnection: (open: boolean) => void;
}) {
  const { config, hasToken, setBaseUrl, setToken, clearToken } = useConnection();
  const tabs = showTabs ? visibleTabs(capabilities) : [];
  const grafanaUrl = capabilities ? grafanaDashboardUrl(capabilities.observability) : null;
  const langfuseUrl = capabilities?.observability.langfuseBaseUrl ?? null;

  // 方向键切换页签需要把焦点跟着移过去；仅改变选中态而不移动焦点，会让键盘用户的下一次按键作用在旧按钮上。
  const buttons = useRef(new Map<DashboardTab, HTMLButtonElement>());

  function moveFocus(index: number, key: string) {
    const count = tabs.length;
    if (count === 0) return;
    const target =
      key === 'ArrowRight'
        ? (index + 1) % count
        : key === 'ArrowLeft'
          ? (index - 1 + count) % count
          : key === 'Home'
            ? 0
            : key === 'End'
              ? count - 1
              : -1;
    if (target < 0) return;
    const next = tabs[target];
    onTabChange(next.id);
    buttons.current.get(next.id)?.focus();
  }

  return (
    <header className="sticky top-0 z-20 border-b border-slate-800 bg-slate-950/90 backdrop-blur">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5">
        <div className="flex shrink-0 items-center gap-2">
          <div className="grid h-7 w-7 place-items-center rounded-md bg-indigo-500 text-xs font-bold text-white">
            zy
          </div>
          <span className="hidden text-sm font-semibold sm:inline">zyblw-agent 控制台</span>
          {capabilities && <Badge>API v{capabilities.apiVersion}</Badge>}
        </div>

        {/* 页签数量会随后端装配的能力增长（当前 7 个），窄屏放不下时横向滚动而不是换行或挤压：
            换行会让下方内容的位置随页签数跳动，挤压则会把图标和文字压成不可读的一团。 */}
        {tabs.length > 0 && (
        <nav
          role="tablist"
          aria-label="控制台页签"
          aria-orientation="horizontal"
          className="custom-scrollbar order-last flex w-full min-w-0 items-center gap-1 overflow-x-auto pb-0.5 md:order-none md:w-auto md:flex-1"
        >
          {tabs.map((tab, index) => {
            const Icon = tab.icon;
            const active = tab.id === activeTab;
            return (
              <button
                key={tab.id}
                id={tabButtonId(tab.id)}
                ref={(element) => {
                  if (element) buttons.current.set(tab.id, element);
                  else buttons.current.delete(tab.id);
                }}
                role="tab"
                type="button"
                aria-selected={active}
                aria-controls={tabPanelId(tab.id)}
                tabIndex={active ? 0 : -1}
                onKeyDown={(event) => {
                  if (['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) {
                    event.preventDefault();
                    moveFocus(index, event.key);
                  }
                }}
                onClick={() => onTabChange(tab.id)}
                className={`inline-flex shrink-0 items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition ${FOCUS_RING} ${
                  active ? 'bg-slate-800 text-white' : 'text-slate-400 hover:bg-slate-900 hover:text-slate-200'
                }`}
              >
                <Icon className="h-3.5 w-3.5" />
                {tab.label}
              </button>
            );
          })}
        </nav>
        )}

        <div className="ml-auto flex shrink-0 items-center gap-2">
          {langfuseUrl && (
            <a
              href={langfuseUrl}
              target="_blank"
              rel="noreferrer"
              className={`inline-flex items-center gap-1 rounded-md border border-slate-700 px-2 py-1 text-xs text-slate-300 hover:bg-slate-800 ${FOCUS_RING}`}
            >
              Langfuse <ExternalLink className="h-3 w-3" />
            </a>
          )}
          {grafanaUrl && (
            <a
              href={grafanaUrl}
              target="_blank"
              rel="noreferrer"
              className={`inline-flex items-center gap-1 rounded-md border border-slate-700 px-2 py-1 text-xs text-slate-300 hover:bg-slate-800 ${FOCUS_RING}`}
            >
              Grafana <ExternalLink className="h-3 w-3" />
            </a>
          )}
          <button
            type="button"
            onClick={() => onToggleConnection(!connectionOpen)}
            aria-expanded={connectionOpen}
            aria-controls="connection-settings"
            className={`inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs transition ${FOCUS_RING} ${
              hasToken
                ? 'border-slate-700 text-slate-300 hover:bg-slate-800'
                : 'border-amber-800 bg-amber-950/40 text-amber-300'
            }`}
          >
            <KeyRound className="h-3 w-3" />
            {hasToken ? '已连接' : '未提供凭据'}
          </button>
        </div>
      </div>

      {connectionOpen && (
        <div id="connection-settings" className="border-t border-slate-800 bg-slate-900/60 px-4 py-3">
          <div className="grid gap-3 md:grid-cols-[2fr_2fr_auto] md:items-end">
            <TextInput
              label="后端地址"
              value={config.baseUrl}
              onChange={setBaseUrl}
              placeholder="http://localhost:8080"
            />
            <TextInput
              label="Bearer Token"
              type="password"
              value={config.token ?? ''}
              onChange={setToken}
              placeholder="由宿主认证方案决定"
            />
            <button
              type="button"
              onClick={clearToken}
              className={`rounded-md border border-slate-700 px-3 py-1.5 text-xs text-slate-300 hover:bg-slate-800 ${FOCUS_RING}`}
            >
              清除凭据
            </button>
          </div>
          <p className="mt-2 text-xs text-slate-500">
            框架不自带认证中间件，身份由宿主的 <code className="text-slate-400">AgentRequestContextResolver</code>{' '}
            解析。管理接口需要 <code className="text-slate-400">agent:admin:read</code> /{' '}
            <code className="text-slate-400">write</code> / <code className="text-slate-400">debug</code> scope。
            地址保存在 localStorage，token 仅保存在 sessionStorage。
          </p>
        </div>
      )}
    </header>
  );
}
