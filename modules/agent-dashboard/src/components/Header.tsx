'use client';

/**
 * 现代智能体控制台顶部工作台状态栏 (Studio Header)。
 *
 * 负责展示当前工作区面包屑、环境连接指示灯、主机安全会话标识与凭据设置弹窗。
 */

import React from 'react';
import {
  ExternalLink,
  KeyRound,
  SlidersHorizontal,
  X,
} from 'lucide-react';
import { useConnection } from '@/lib/connection';
import { grafanaDashboardUrl } from '@/types/admin';
import type { AdminCapabilitiesView } from '@/types/admin';
import { Badge, Button, FOCUS_RING, TextInput } from '@/components/ui';
import {
  visibleTabs,
  tabPanelId,
  tabButtonId,
  type DashboardTab,
} from '@/components/Sidebar';

export { visibleTabs, tabPanelId, tabButtonId, type DashboardTab };

const TAB_DESCRIPTIONS: Record<DashboardTab, { section: string; title: string; subtitle: string }> = {
  runs: {
    section: '观测与追踪',
    title: '运行流水与追踪 (Runs & Traces)',
    subtitle: '智能体每次会话的完整调用链流水、分步耗时、Token 消耗与成本核算',
  },
  inspect: {
    section: '观测与追踪',
    title: '架构穿透与组合比对 (Deep Inspect)',
    subtitle: '创建时不可变冻结的装配指纹与当前运行进程比对，检测环境漂移与安全阻断',
  },
  rag: {
    section: '智能体资产',
    title: '知识库与混合检索沙盒 (Knowledge & RAG)',
    subtitle: '稠密向量 + 稀疏关键词多路召回与重排模型打分效果可视化调试',
  },
  memory: {
    section: '智能体资产',
    title: '长期记忆治理与合规 (Memory Store)',
    subtitle: '跨会话语义记忆与用户画像有界导出，敏感度分级与合规审计',
  },
  harness: {
    section: '智能体资产',
    title: '目标规划与任务支架 (Goals & Plans)',
    subtitle: '长程目标拆解进度、分步 Todo 状态分布与任务级硬预算防死循环保障',
  },
  models: {
    section: '治理与基建',
    title: '模型网关与容灾治理 (Model Hub)',
    subtitle: '注册模型目录、高可用降级链路拓扑、Token 价格核算与连通探活',
  },
  queue: {
    section: '治理与基建',
    title: '分布式任务队列运维 (Queue Ops)',
    subtitle: '4阶段状态管道拓扑（Inbound → In-Flight → Recovery → DeadLetter）与死信重排',
  },
  security: {
    section: '治理与基建',
    title: '安全防御与护栏工坊 (Guardrails)',
    subtitle: '输入输出语义拦截、Prompt 注入攻击防御、敏感工具鉴权与审计证明',
  },
  evals: {
    section: '治理与基建',
    title: '评测基准与质量趋势 (Eval Trends)',
    subtitle: '自动化评测集打分、准确率回归对比与模型回答忠实度分析',
  },
  config: {
    section: '治理与基建',
    title: '运行时动态配置工坊 (Config Studio)',
    subtitle: '一键应用生产/极速/探索场景预设，在线微调参数并实时预览 Diff 差异',
  },
};

export function Header({
  activeTab,
  capabilities,
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
  const { config, hasToken, setBaseUrl, setToken, clearToken } = useConnection();
  const info = TAB_DESCRIPTIONS[activeTab] ?? {
    section: '控制台',
    title: '智能体管理台',
    subtitle: 'zyblw-agent 运行与运维中心',
  };

  const grafanaUrl = capabilities ? grafanaDashboardUrl(capabilities.observability) : null;
  const langfuseUrl = capabilities?.observability.langfuseBaseUrl ?? null;

  return (
    <header className="sticky top-0 z-30 flex flex-col border-b border-slate-200 bg-white/95 backdrop-blur-md">
      <div className="flex items-center justify-between px-6 py-3">
        {/* 当前模块面包屑与副标题 */}
        <div className="flex flex-col min-w-0">
          <div className="flex items-center gap-2 text-xs">
            <span className="font-medium text-slate-400">{info.section}</span>
            <span className="text-slate-300">/</span>
            <h1 className="font-semibold text-slate-900 text-sm">{info.title}</h1>
          </div>
          <p className="mt-0.5 text-xs text-slate-500 truncate max-w-2xl">{info.subtitle}</p>
        </div>

        {/* 顶部右侧状态与操作 */}
        <div className="flex items-center gap-3">
          {/* 会话指示器：严格保留宿主会话标识与断言支持 */}
          {config.authMode === 'host-session' ? (
            <div
              className="flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-xs text-emerald-800"
              title="当前管理台复用主站登录会话，不存在单独的 Agent 用户名或密码"
            >
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
              <span className="font-medium">站点账号 · 管理授权</span>
              <span className="hidden xl:inline text-[10px] text-emerald-700 border-l border-emerald-200 pl-2">
                复用主站安全会话
              </span>
            </div>
          ) : (
            <div className="flex items-center gap-1.5">
              <span
                className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${
                  hasToken
                    ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
                    : 'bg-amber-50 text-amber-800 ring-amber-200'
                }`}
              >
                <span className={`h-1.5 w-1.5 rounded-full ${hasToken ? 'bg-emerald-500' : 'bg-amber-500'}`} />
                {hasToken ? 'Bearer 授权生效' : '待配置凭据'}
              </span>
            </div>
          )}

          {/* API 版本徽章 */}
          {capabilities && (
            <Badge className="bg-slate-100 text-slate-600 ring-slate-200 font-mono text-[11px]">
              API v{capabilities.apiVersion}
            </Badge>
          )}

          {/* 外部观测链接 */}
          {langfuseUrl && (
            <a
              href={langfuseUrl}
              target="_blank"
              rel="noreferrer"
              className={`hidden sm:inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs text-slate-600 hover:bg-slate-50 hover:text-slate-900 transition shadow-xs ${FOCUS_RING}`}
            >
              Langfuse <ExternalLink className="h-3 w-3" />
            </a>
          )}
          {grafanaUrl && (
            <a
              href={grafanaUrl}
              target="_blank"
              rel="noreferrer"
              className={`hidden sm:inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs text-slate-600 hover:bg-slate-50 hover:text-slate-900 transition shadow-xs ${FOCUS_RING}`}
            >
              Grafana <ExternalLink className="h-3 w-3" />
            </a>
          )}

          {/* 凭据设置展开按钮 */}
          <button
            type="button"
            onClick={() => onToggleConnection(!connectionOpen)}
            className={`inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 hover:text-slate-900 transition shadow-xs ${FOCUS_RING}`}
          >
            <KeyRound className="h-3.5 w-3.5 text-indigo-600" />
            <span>连接设置</span>
          </button>
        </div>
      </div>

      {/* 连接与凭据配置抽屉/面板 */}
      {connectionOpen && (
        <div className="border-t border-slate-200 bg-slate-50/95 px-6 py-4 shadow-md">
          <div className="mx-auto flex max-w-4xl flex-col gap-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <SlidersHorizontal className="h-4 w-4 text-indigo-600" />
                <h2 className="text-sm font-semibold text-slate-900">后端连接与管理凭据配置</h2>
              </div>
              <button
                type="button"
                onClick={() => onToggleConnection(false)}
                className="text-slate-400 hover:text-slate-700 transition p-1 rounded-md hover:bg-slate-100"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="grid gap-4 md:grid-cols-2">
              <TextInput
                label="后端服务地址 (Base URL)"
                value={config.baseUrl}
                onChange={setBaseUrl}
                placeholder="http://127.0.0.1:8080"
              />
              <TextInput
                label="管理令牌 (Bearer Token)"
                value={config.token ?? ''}
                onChange={setToken}
                placeholder="填写包含 agent:admin:read/write 的令牌"
                type="password"
              />
            </div>

            <div className="flex items-center justify-between text-xs text-slate-500 pt-1">
              <span>
                Token 仅安全留存在当前浏览器标签页 Session，关闭后自动失效；基地址将保存在本地 LocalStorage。
              </span>
              {hasToken && (
                <Button variant="danger" onClick={clearToken}>
                  清除当前凭据
                </Button>
              )}
            </div>
          </div>
        </div>
      )}
    </header>
  );
}
