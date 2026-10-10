'use client';

/**
 * 现代智能体控制台侧边导航栏 (Studio Sidebar)。
 *
 * 按照企业级智能体平台最佳实践（LangSmith / Langfuse / Dify）：
 * 将原先杂乱水平堆叠的页签，按功能职责分层重组为：
 * 1. 观测与追踪 (Observability)
 * 2. 智能体中枢 (Agent Assets)
 * 3. 治理与基建 (Governance & Infra)
 */

import React, { useRef } from 'react';
import {
  Activity,
  Brain,
  Cpu,
  Database,
  ExternalLink,
  Fingerprint,
  Goal,
  KeyRound,
  Layers,
  ListChecks,
  Settings2,
  ShieldCheck,
  Sparkles,
} from 'lucide-react';
import { useConnection } from '@/lib/connection';
import { grafanaDashboardUrl, type AdminCapabilitiesView } from '@/types/admin';
import { FOCUS_RING } from '@/components/ui';

export type DashboardTab =
  | 'runs'
  | 'inspect'
  | 'harness'
  | 'memory'
  | 'rag'
  | 'queue'
  | 'models'
  | 'config'
  | 'security'
  | 'evals';

export interface TabDefinition {
  id: DashboardTab;
  label: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
  capability?: keyof AdminCapabilitiesView;
  badge?: string;
}

export const TABS: TabDefinition[] = [
  {
    id: 'runs',
    label: '运行与追踪',
    description: '执行流水、瀑布流与成本',
    icon: Activity,
    capability: 'runDirectory',
  },
  {
    id: 'inspect',
    label: '架构穿透',
    description: '装配快照与环境漂移比对',
    icon: Fingerprint,
    capability: 'runInspection',
  },
  {
    id: 'rag',
    label: '知识库检索',
    description: '混合召回与重排调试沙盒',
    icon: Database,
    capability: 'knowledge',
  },
  {
    id: 'memory',
    label: '长期记忆',
    description: '用户画像与合规分级导出',
    icon: Brain,
    capability: 'memoryGovernance',
  },
  {
    id: 'harness',
    label: '目标与计划',
    description: '复杂任务拆解与硬预算度量',
    icon: Goal,
    capability: 'harness',
  },
  {
    id: 'models',
    label: '模型网关',
    description: '多级降级拓扑与连通探活',
    icon: Cpu,
    capability: 'models',
  },
  {
    id: 'queue',
    label: '任务队列',
    description: '4阶段流水线与死信自愈',
    icon: Layers,
    capability: 'queueOps',
  },
  {
    id: 'security',
    label: '安全护栏',
    description: '输入输出拦截与注入防御',
    icon: ShieldCheck,
    capability: 'runtimeConfig',
  },
  {
    id: 'evals',
    label: '评测基准',
    description: '自动化测试集质量打分',
    icon: ListChecks,
    capability: 'evalTrends',
  },
  {
    id: 'config',
    label: '配置工坊',
    description: '场景预设与参数即时微调',
    icon: Settings2,
    capability: 'runtimeConfig',
  },
];

export interface NavGroup {
  title: string;
  tabIds: DashboardTab[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    title: '观测与追踪',
    tabIds: ['runs', 'inspect'],
  },
  {
    title: '智能体中枢',
    tabIds: ['rag', 'memory', 'harness'],
  },
  {
    title: '治理与基建',
    tabIds: ['models', 'queue', 'security', 'evals', 'config'],
  },
];

export function visibleTabs(capabilities: AdminCapabilitiesView | undefined): TabDefinition[] {
  if (!capabilities) return TABS;
  return TABS.filter((tab) => !tab.capability || capabilities[tab.capability] === true);
}

export function tabPanelId(tab: DashboardTab): string {
  return `panel-${tab}`;
}

export function tabButtonId(tab: DashboardTab): string {
  return `tab-${tab}`;
}

export function Sidebar({
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
  showTabs?: boolean;
  connectionOpen: boolean;
  onToggleConnection: (open: boolean) => void;
}) {
  const { config, hasToken } = useConnection();
  const available = showTabs ? visibleTabs(capabilities) : [];
  const tabMap = new Map(TABS.map((t) => [t.id, t]));
  const buttonRefs = useRef(new Map<DashboardTab, HTMLButtonElement>());

  const grafanaUrl = capabilities ? grafanaDashboardUrl(capabilities.observability) : null;
  const langfuseUrl = capabilities?.observability.langfuseBaseUrl ?? null;

  return (
    <aside className="flex h-screen w-64 shrink-0 flex-col border-r border-slate-200 bg-white text-slate-800 select-none">
      {/* 品牌标识 */}
      <div className="flex items-center gap-3 border-b border-slate-100 px-5 py-4">
        <div className="relative flex h-8 w-8 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-indigo-600 text-sm font-extrabold text-white shadow-xs">
          zy
          <Sparkles className="absolute -top-1 -right-1 h-3 w-3 text-amber-300 animate-pulse" />
        </div>
        <div className="flex flex-col min-w-0">
          <div className="flex items-center gap-1.5">
            <span className="font-bold tracking-tight text-slate-900 text-sm">zyblw-agent</span>
            <span className="rounded bg-indigo-50 px-1 py-0.2 text-[10px] font-semibold text-indigo-700 border border-indigo-200/60">
              v1
            </span>
          </div>
          <span className="text-[11px] text-slate-500 font-medium truncate">智能体开发与运维平台</span>
        </div>
      </div>

      {/* 分组导航列表 */}
      <div className="flex-1 overflow-y-auto px-3 py-3 space-y-5 custom-scrollbar">
        {showTabs &&
          NAV_GROUPS.map((group) => {
            const groupTabs = group.tabIds
              .map((id) => tabMap.get(id)!)
              .filter((tab) => available.some((a) => a.id === tab.id));

            if (groupTabs.length === 0) return null;

            return (
              <div key={group.title} className="space-y-1">
                <div className="px-2.5 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                  {group.title}
                </div>
                <div className="space-y-0.5">
                  {groupTabs.map((tab) => {
                    const isActive = activeTab === tab.id;
                    const Icon = tab.icon;

                    return (
                      <button
                        key={tab.id}
                        id={tabButtonId(tab.id)}
                        ref={(el) => {
                          if (el) buttonRefs.current.set(tab.id, el);
                          else buttonRefs.current.delete(tab.id);
                        }}
                        role="tab"
                        aria-selected={isActive}
                        aria-controls={tabPanelId(tab.id)}
                        onClick={() => onTabChange(tab.id)}
                        className={`group relative flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-left text-xs transition duration-150 ${FOCUS_RING} ${
                          isActive
                            ? 'bg-indigo-50 font-semibold text-indigo-700 border border-indigo-200/60 shadow-xs'
                            : 'text-slate-600 hover:bg-slate-100/80 hover:text-slate-900'
                        }`}
                      >
                        {isActive && (
                          <div className="absolute left-0 top-1.5 bottom-1.5 w-1 rounded-r-full bg-indigo-600 shadow-xs" />
                        )}
                        <Icon
                          className={`h-4 w-4 shrink-0 transition ${
                            isActive ? 'text-indigo-600' : 'text-slate-400 group-hover:text-slate-600'
                          }`}
                        />
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center justify-between">
                            <span className="truncate">{tab.label}</span>
                            {tab.badge && (
                              <span className="rounded bg-indigo-50 px-1 py-0.2 text-[10px] text-indigo-700 font-mono border border-indigo-200/60">
                                {tab.badge}
                              </span>
                            )}
                          </div>
                          <p
                            className={`truncate text-[10px] ${
                              isActive ? 'text-indigo-600/80' : 'text-slate-400 group-hover:text-slate-500'
                            }`}
                          >
                            {tab.description}
                          </p>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>
            );
          })}
      </div>

      {/* 底部系统状态与会话信息 */}
      <div className="border-t border-slate-200 p-3 space-y-2.5 bg-slate-50/70">
        {/* 站点账号与会话信息：满足测试断言与说明 */}
        {config.authMode === 'host-session' ? (
          <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 p-2.5 text-xs text-slate-700">
            <div className="flex items-center gap-1.5 font-medium text-emerald-900 text-[11px]">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
              <span>站点账号 · 管理授权</span>
            </div>
            <p className="mt-1 text-[10px] text-slate-500 leading-normal">
              当前管理台复用主站登录会话，不存在单独的 Agent 用户名或密码；权限由服务端按稳定 userId 授予并审计。
            </p>
          </div>
        ) : (
          <div className="flex items-center justify-between px-1 text-xs">
            <span className="flex items-center gap-1.5 text-[11px] text-slate-600">
              <span
                className={`h-2 w-2 rounded-full ${
                  hasToken ? 'bg-emerald-500 ring-2 ring-emerald-500/20' : 'bg-amber-500 animate-pulse'
                }`}
              />
              {hasToken ? '已连接授权' : '需要连接凭据'}
            </span>
            <button
              type="button"
              onClick={() => onToggleConnection(!connectionOpen)}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-0.5 text-[11px] text-slate-700 hover:bg-slate-50 shadow-xs transition"
            >
              <KeyRound className="h-3 w-3 text-indigo-600" />
              <span>凭据设置</span>
            </button>
          </div>
        )}

        {/* 外部观测深链 */}
        {(grafanaUrl || langfuseUrl) && (
          <div className="flex items-center gap-2 pt-1.5 border-t border-slate-200 text-[11px] text-slate-400">
            {langfuseUrl && (
              <a
                href={langfuseUrl}
                target="_blank"
                rel="noreferrer"
                className="hover:text-indigo-600 transition flex items-center gap-0.5 truncate"
              >
                Langfuse <ExternalLink className="h-2.5 w-2.5 inline" />
              </a>
            )}
            {grafanaUrl && (
              <a
                href={grafanaUrl}
                target="_blank"
                rel="noreferrer"
                className="hover:text-indigo-600 transition flex items-center gap-0.5 truncate"
              >
                Grafana <ExternalLink className="h-2.5 w-2.5 inline" />
              </a>
            )}
          </div>
        )}
      </div>
    </aside>
  );
}
