'use client';

/**
 * 模型治理：已注册目录、连通性探活与 Embedding 的不可变说明。
 *
 * 本页只读。连接、模型、角色绑定与价格由宿主管理台维护并热加载到注册表；每次运行按角色（或用户所选模型）
 * 钉住 provider/model，因此这里不再提供全局模型覆盖的写入口——那会与角色绑定形成两份互相矛盾的事实。
 *
 * 凭据只有"就位与否"和"来自哪个引用"两个事实可用。这里不展示、不请求、也不存储任何 Key 值。
 */

import React, { useMemo } from 'react';
import {
  AlertTriangle,
  Boxes,
  CircleSlash,
  KeyRound,
  Radio,
  Sparkles,
} from 'lucide-react';
import { useModelCatalog, useProbeModel } from '@/lib/queries';
import { useToast } from '@/lib/toast';
import { useUrlState } from '@/lib/urlState';
import {
  providersOf,
  type EmbeddingModelView,
  type ModelCatalogView,
  type ModelOptionView,
} from '@/types/admin';
import { formatCount, formatDuration, formatPercent } from '@/lib/format';
import {
  Badge,
  Button,
  EmptyState,
  ErrorBanner,
  Field,
  FOCUS_RING,
  LoadingRows,
  Mono,
  Panel,
  StatCard,
} from '@/components/ui';

/** URL 里承载模型页选择的参数名；与其它页签的租户参数刻意不同名，避免切页签时互相污染。 */
const PROVIDER_PARAM = 'modelProvider';
const MODEL_PARAM = 'modelName';

const PROBE_FAILURE_MESSAGES: Record<string, string> = {
  'provider-not-found': '目标 Provider 未在当前部署注册；检查路由装配，而不是重试请求。',
  'model-not-found': 'Provider 已注册，但目标模型不在其目录中；请选择目录中的模型或修正装配声明。',
  unauthorized: 'Provider 拒绝了凭据；检查页面显示的凭据引用是否已注入并仍然有效。',
  'rate-limited': 'Provider 正在限流；稍后重试，或切换到已有备用组合。',
  timeout: 'Provider 在探活预算内没有完成；检查网络、网关和 Provider 可用性。',
  capability: '目标模型无法满足最小请求所需的能力协商。',
  configuration: 'Provider 配置不完整或不合法；检查部署侧配置。',
  'invalid-request': 'Provider 拒绝了请求；模型名、协议或账户权限可能不匹配。',
  unavailable: 'Provider 或其上游暂时不可用；稍后重试。',
};

function probeFailureMessage(code: string | null | undefined): string {
  if (!code) return 'Provider 没有返回可识别的失败分类。';
  return PROBE_FAILURE_MESSAGES[code] ?? `未识别的稳定失败分类：${code}`;
}

export function ModelGovernance() {
  const catalog = useModelCatalog();

  const url = useUrlState();
  const view = catalog.data;
  const options = useMemo(() => view?.options ?? [], [view]);
  const providers = useMemo(() => providersOf(view), [view]);

  // 选择完全由 URL 派生：URL 里的值不在目录中（切换环境、模型下线）时静默回落到生效值或默认 Provider，
  // 而不是在 effect 里纠正 state——后者会多一轮渲染，且在目录刷新时需要额外判断当前选择是否仍然有效。
  const urlProvider = url.get(PROVIDER_PARAM);
  const selectedProvider = providers.includes(urlProvider)
    ? urlProvider
    : (view?.effectiveProvider ?? view?.defaultProvider ?? providers[0] ?? '');

  const providerOptions = useMemo(
    () => options.filter((option) => option.provider === selectedProvider),
    [options, selectedProvider],
  );
  const urlModel = url.get(MODEL_PARAM);
  const selectedModel = providerOptions.some((option) => option.model === urlModel)
    ? urlModel
    : (providerOptions.find((option) => option.model === view?.effectiveModel)?.model ??
      providerOptions[0]?.model ??
      '');

  const selectedOption = providerOptions.find((option) => option.model === selectedModel) ?? null;

  function selectCombination(provider: string, model: string | null) {
    // Provider 与模型必须一次写入：分两次 replace 会让第二次基于第一次尚未回流的 searchParams 快照。
    url.set({ [PROVIDER_PARAM]: provider, [MODEL_PARAM]: model });
  }

  const pricedRatio = options.length > 0 ? (view?.pricedOptionCount ?? 0) / options.length : 0;
  const effectiveLabel =
    view?.effectiveProvider || view?.effectiveModel
      ? `${view?.effectiveProvider ?? view?.defaultProvider ?? '—'} / ${view?.effectiveModel ?? '（未覆盖模型名）'}`
      : '各 Agent 定义';

  return (
    <div className="space-y-4 p-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="已注册 Provider" value={formatCount(providers.length)} hint="装配时解析到的路由名" />
        <StatCard label="可选模型" value={formatCount(options.length)} hint="Provider × 模型组合" />
        <StatCard
          label="当前生效模型"
          value={<span className="text-base">{effectiveLabel}</span>}
          tone={view?.effectiveProvider || view?.effectiveModel ? 'warn' : 'neutral'}
          hint={
            view?.effectiveProvider || view?.effectiveModel
              ? '运行时覆盖已生效，所有 Agent 都被改写'
              : '未设置覆盖，每个 Agent 沿用自己的 modelSettings'
          }
        />
        <StatCard
          label="价格表覆盖"
          value={
            view?.priceCurrency
              ? `${formatCount(view.pricedOptionCount)} / ${formatCount(options.length)}`
              : '未声明'
          }
          tone={!view?.priceCurrency ? 'danger' : pricedRatio < 1 ? 'warn' : 'good'}
          hint={
            view?.priceCurrency
              ? `${formatPercent(pricedRatio, 0)} 的模型有单价（${view.priceCurrency}）；其余按零计费估算`
              : '未提供价格表，成本未知；有费用上限的调用会被拒绝'
          }
        />
      </div>

      <ErrorBanner error={catalog.error} context="读取模型目录" />

      {catalog.isPending ? (
        <LoadingRows rows={6} />
      ) : options.length === 0 ? (
        <EmptyState
          title="没有已注册的模型"
          reason="宿主未向 AdminCapabilities 提供 ModelAdminService，或模型路由器没有注册任何 Provider。目录为空时后端会拒绝一切模型覆盖（fail-closed）。"
        />
      ) : (
        <>
          <ModelCatalogTable
            options={options}
            providers={providers}
            catalog={view}
            selectedProvider={selectedProvider}
            selectedModel={selectedModel}
            onSelect={selectCombination}
          />

          {/* 弹性高可用降级链路拓扑 */}
          <div className="rounded-xl border border-indigo-200 bg-indigo-50/30 p-4 shadow-xs">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <Sparkles className="h-4 w-4 text-indigo-600" />
                <span className="text-xs font-semibold text-slate-900">高可用模型降级拓扑 (Fallback Chain)</span>
              </div>
              <Badge className="text-emerald-700 bg-emerald-50 ring-emerald-600/20">
                ZIO 结构化断路器驱动
              </Badge>
            </div>
            <div className="flex flex-wrap items-center gap-3 text-xs">
              <div className="flex items-center gap-2 rounded-lg bg-white border border-indigo-200 px-3 py-2 shadow-xs">
                <span className="h-2 w-2 rounded-full bg-emerald-500 animate-pulse" />
                <span className="text-slate-500 font-medium">主选 Provider:</span>
                <span className="font-bold text-indigo-900">{view?.defaultProvider ?? '默认'}</span>
              </div>
              <span className="text-slate-400">➔ 瞬时故障自愈 ➔</span>
              {providers
                .filter((p) => p !== view?.defaultProvider)
                .map((fallbackProvider) => (
                  <div
                    key={fallbackProvider}
                    className="flex items-center gap-2 rounded-lg bg-white border border-slate-200 px-3 py-2 text-slate-700 shadow-xs"
                  >
                    <span className="h-1.5 w-1.5 rounded-full bg-slate-400" />
                    <span>备用目标:</span>
                    <Mono className="font-semibold text-slate-900">{fallbackProvider}</Mono>
                  </div>
                ))}
              {providers.length <= 1 && (
                <span className="text-slate-400 text-[11px]">
                  （当前仅注册单一 Provider；若需跨厂商主备容灾，可装配 FallbackChatModel 候选）
                </span>
              )}
            </div>
            <p className="mt-2.5 text-[11px] text-slate-500 leading-relaxed">
              保护规则：仅瞬时故障（429 限流、5xx 超载、网络超时）触发候选降级；工具 Schema 不兼容、Prompt 注入拦截等严格语义一律 fail-closed，杜绝静默篡改。
            </p>
          </div>

          <div className="grid gap-4 xl:grid-cols-2">
            <Panel title="生效模型" description="由宿主管理台的角色绑定决定；本页只读">
              <div className="space-y-2 text-xs leading-6 text-slate-600">
                <p>
                  连接、模型、角色与价格统一在宿主的「模型」管理页维护：每次运行按角色（或用户所选模型）钉住
                  provider/model，改绑后下一次调用即生效，全局模型覆盖不再参与路由。
                </p>
                <a href="/admin/models" className="inline-flex items-center gap-1 text-indigo-600 font-medium hover:underline">
                  前往模型管理页 →
                </a>
              </div>
            </Panel>

            <ModelProbePanel provider={selectedProvider} model={selectedModel} option={selectedOption} />
          </div>

          <EmbeddingSection embedding={view?.embedding ?? null} />
        </>
      )}
    </div>
  );
}

/** 能力位徽章；只展示与"这个模型能不能承担 Agent 循环"直接相关的几项。 */
function CapabilityBadges({ option }: { option: ModelOptionView }) {
  // 管理面可能在滚动发布期间先连到尚未返回加法字段的旧副本；缺失只表示未声明，不能让整个目录崩溃。
  const supportedReasoningEfforts = option.capabilities.supportedReasoningEfforts ?? [];
  const flags: { label: string; on: boolean; critical?: boolean }[] = [
    { label: '工具调用', on: option.capabilities.toolCalls, critical: true },
    { label: '并行工具', on: option.capabilities.parallelToolCalls },
    { label: '严格 Schema', on: option.capabilities.strictToolSchema },
    { label: '视觉', on: option.capabilities.vision },
    {
      label: supportedReasoningEfforts.length
        ? `推理 ${supportedReasoningEfforts.join('/')}`
        : '推理默认',
      on: option.capabilities.thinking,
    },
    { label: '流式', on: option.capabilities.streaming },
    { label: `缓存 ${option.capabilities.promptCacheKind}`, on: option.capabilities.promptCacheKind !== 'Unsupported' },
  ];
  return (
    <div className="flex flex-wrap gap-1">
      {flags.map((flag) => (
        <Badge
          key={flag.label}
          className={
            flag.on
              ? 'text-emerald-700 bg-emerald-50 ring-emerald-600/20'
              : flag.critical
                ? 'text-rose-700 bg-rose-50 ring-rose-600/20'
                : 'text-slate-400 bg-slate-100 ring-slate-200'
          }
        >
          {flag.label}
        </Badge>
      ))}
    </div>
  );
}

/** 目录表格；按 Provider 分组，每行一个模型。 */
function ModelCatalogTable({
  options,
  providers,
  catalog,
  selectedProvider,
  selectedModel,
  onSelect,
}: {
  options: ModelOptionView[];
  providers: string[];
  catalog: ModelCatalogView | undefined;
  selectedProvider: string;
  selectedModel: string;
  onSelect: (provider: string, model: string) => void;
}) {
  const missingCredentials = options.filter((option) => !option.credential.present).length;

  return (
    <Panel
      title="模型目录"
      description="装配时注册的全部 Provider 与模型；点击一行即选中它作为切换与探活的目标"
      actions={
        missingCredentials > 0 ? (
          <Badge className="text-rose-700 bg-rose-50 ring-rose-600/20">
            {missingCredentials} 个组合缺凭据
          </Badge>
        ) : (
          <Badge className="text-emerald-700 bg-emerald-50 ring-emerald-600/20">凭据全部就位</Badge>
        )
      }
    >
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="w-full text-left text-xs">
          <caption className="sr-only">已注册模型目录，按 Provider 分组</caption>
          <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
            <tr>
              <th scope="col" className="py-2.5 px-3 font-semibold">模型</th>
              <th scope="col" className="py-2.5 px-3 font-semibold">能力</th>
              <th scope="col" className="py-2.5 px-3 font-semibold">上下文窗口</th>
              <th scope="col" className="py-2.5 px-3 font-semibold">单价 / 百万 token</th>
              <th scope="col" className="py-2.5 px-3 font-semibold">凭据</th>
            </tr>
          </thead>
          {providers.map((provider) => {
            const group = options.filter((option) => option.provider === provider);
            const first = group[0];
            return (
              <tbody key={provider} className="divide-y divide-slate-100">
                <tr className="bg-slate-50/80 border-b border-slate-100">
                  <th scope="colgroup" colSpan={5} className="py-2 px-3 text-left font-semibold text-slate-800">
                    <span className="inline-flex items-center gap-2">
                      <Boxes className="h-3.5 w-3.5 text-slate-400" />
                      {provider}
                      {first?.isDefaultProvider && (
                        <Badge className="text-indigo-700 bg-indigo-50 ring-indigo-200">默认 Provider</Badge>
                      )}
                      {first && <span className="text-[11px] font-normal text-slate-500">{first.protocol}</span>}
                    </span>
                  </th>
                </tr>
                {group.map((option) => {
                  const selected = option.provider === selectedProvider && option.model === selectedModel;
                  const effective =
                    catalog?.effectiveProvider === option.provider && catalog?.effectiveModel === option.model;
                  return (
                    <tr
                      key={`${option.provider}/${option.model}`}
                      onClick={() => onSelect(option.provider, option.model)}
                      className={`cursor-pointer border-b border-slate-100 align-top transition hover:bg-slate-50 ${
                        selected ? 'bg-indigo-50/70 ring-1 ring-inset ring-indigo-200' : ''
                      } ${option.credential.present ? '' : 'bg-rose-50/30'}`}
                    >
                      <td className="py-2.5 px-3">
                        <div className="flex flex-wrap items-center gap-1.5">
                          {/* 行的 onClick 只是指针便利；真正的可达控件是这个按钮。只装配了模型目录而没有
                              配置能力的部署里没有下拉框，此时它是键盘用户选中探活目标的唯一入口。 */}
                          <button
                            type="button"
                            aria-pressed={selected}
                            onClick={() => onSelect(option.provider, option.model)}
                            className={`rounded font-medium ${FOCUS_RING}`}
                          >
                            <Mono className="text-slate-900">{option.model}</Mono>
                          </button>
                          {effective && (
                            <Badge className="text-amber-800 bg-amber-50 ring-amber-600/25">当前生效</Badge>
                          )}
                        </div>
                        <div className="mt-0.5 text-[11px] text-slate-500">{option.displayName}</div>
                        {!option.declaredModel && (
                          <div className="mt-1 text-[10px] text-amber-600 font-medium">
                            部署默认模型，能力按 Provider 级推断
                          </div>
                        )}
                      </td>
                      <td className="py-2.5 px-3">
                        <CapabilityBadges option={option} />
                      </td>
                      <td className="py-2.5 px-3 tabular-nums text-slate-700">
                        <div>入 {formatCount(option.capabilities.maxInputTokens ?? null)}</div>
                        <div className="text-slate-500 text-[11px]">出 {formatCount(option.capabilities.maxOutputTokens ?? null)}</div>
                      </td>
                      <td className="py-2.5 px-3 tabular-nums text-slate-700 font-mono">
                        {option.price ? (
                          <>
                            <div>
                              入 {option.price.inputPerMillionTokens} {option.price.currency}
                            </div>
                            <div className="text-slate-500 text-[11px]">
                              出 {option.price.outputPerMillionTokens} {option.price.currency}
                            </div>
                            {option.price.cachedInputPerMillionTokens && (
                              <div className="text-slate-500 text-[10px]">
                                缓存读 {option.price.cachedInputPerMillionTokens} {option.price.currency}
                              </div>
                            )}
                            {option.price.cacheWriteInputPerMillionTokens && (
                              <div className="text-slate-500 text-[10px]">
                                缓存写 {option.price.cacheWriteInputPerMillionTokens} {option.price.currency}
                              </div>
                            )}
                          </>
                        ) : (
                          <span className="text-slate-400">未定价 · 成本未知</span>
                        )}
                      </td>
                      <td className="py-2.5 px-3">
                        {option.credential.present ? (
                          <span className="inline-flex items-center gap-1 text-emerald-700">
                            <KeyRound className="h-3 w-3" />
                            <Mono className="text-slate-600">{option.credential.reference}</Mono>
                          </span>
                        ) : (
                          <div className="text-rose-700">
                            <span className="inline-flex items-center gap-1 font-medium">
                              <AlertTriangle className="h-3 w-3" /> 缺凭据
                            </span>
                            <div className="mt-0.5 text-[10px] text-rose-600">
                              切到它会因缺凭据而全线失败；请先配置 <Mono>{option.credential.reference}</Mono>
                            </div>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            );
          })}
        </table>
      </div>

      <p className="mt-3 text-[11px] text-slate-500">
        管理台只能看到凭据是否就位以及它来自哪个引用，看不到也不会请求 Key 值。能力位来自 Provider 的声明，
        用于在切换前发现「这个模型不支持工具调用」这类会让 Agent 循环直接退化的组合。
      </p>
    </Panel>
  );
}

/** 连通性探活。 */
function ModelProbePanel({
  provider,
  model,
  option,
}: {
  provider: string;
  model: string;
  option: ModelOptionView | null;
}) {
  const probe = useProbeModel();
  const { notify } = useToast();
  const result = probe.data;

  return (
    <Panel
      title="连通性探活"
      description="向 Provider 发一次最小真实调用，验证凭据有效、路由可达、能力协商通过"
      actions={<Badge className="text-amber-800 bg-amber-50 ring-amber-600/25">需要 agent:admin:debug</Badge>}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Button
          onClick={() =>
            probe.mutate(
              { provider, model: model || undefined },
              {
                onSuccess: (value) =>
                  value.succeeded
                    ? notify('success', '探活成功', `${value.provider} / ${value.model} · ${formatDuration(value.latencyMillis)}`)
                    : notify('error', '探活失败', probeFailureMessage(value.failureCode)),
                onError: (error) =>
                  notify('error', '探活请求失败', error instanceof Error ? error.message : String(error)),
              },
            )
          }
          disabled={!provider || probe.isPending}
        >
          <Radio className="h-3 w-3" /> {probe.isPending ? '探活中…' : '执行探活（产生真实费用）'}
        </Button>
        {option && !option.credential.present && (
          <span className="text-[11px] text-rose-600 font-medium">该组合缺凭据，探活预计会以认证失败告终。</span>
        )}
      </div>

      <div className="mt-3">
        <ErrorBanner error={probe.error} context="执行探活" />
      </div>

      {result ? (
        <div className="mt-3 divide-y divide-slate-100">
          <Field label="结果">
            {result.succeeded ? (
              <Badge className="text-emerald-700 bg-emerald-50 ring-emerald-600/20">成功</Badge>
            ) : (
              <Badge className="text-rose-700 bg-rose-50 ring-rose-600/20">失败</Badge>
            )}
          </Field>
          <Field label="实际路由">
            <Mono className="text-slate-900 font-semibold">
              {result.provider} / {result.model}
            </Mono>
          </Field>
          <Field label="端到端耗时">{formatDuration(result.latencyMillis)}</Field>
          <Field label="消耗 token">
            入 {formatCount(result.inputTokens)} · 出 {formatCount(result.outputTokens)}
          </Field>
          <Field label="失败分类">
            {result.failureCode ? <Mono className="text-rose-700 font-semibold">{result.failureCode}</Mono> : '—'}
          </Field>
          {!result.succeeded && (
            <div className="py-2.5 text-xs leading-relaxed text-rose-700 font-medium">
              {probeFailureMessage(result.failureCode)}
            </div>
          )}
        </div>
      ) : (
        !probe.isPending && (
          <div className="mt-3">
            <EmptyState
              title="尚未探活"
              reason="探活只在点击时发起，不会因为窗口重新聚焦或组件重挂载而自动重发——每一次都是一次真实的 Provider 调用。"
            />
          </div>
        )
      )}

      <p className="mt-3 flex items-start gap-1.5 text-[11px] text-slate-500">
        <Sparkles className="mt-0.5 h-3 w-3 shrink-0 text-indigo-600" />
        探活只返回是否成功、耗时和 token 用量，不返回模型输出正文。否则一个只需要 agent:admin:debug 的端点
        就变成了可以向任意 Provider 提问并读回答案的通道。
      </p>
    </Panel>
  );
}

/** Embedding 只读区块；这里刻意没有任何切换入口。 */
function EmbeddingSection({ embedding }: { embedding: EmbeddingModelView | null }) {
  if (!embedding) {
    return (
      <Panel title="向量化模型" description="知识库检索使用的 Embedding 模型">
        <EmptyState
          title="未装配 Embedding"
          reason="该部署没有接入向量化模型，因此知识库检索不可用。这与「已装配但配置有误」是两种不同的状态。"
        />
      </Panel>
    );
  }

  const mismatch =
    embedding.indexDimension !== null &&
    embedding.indexDimension !== undefined &&
    embedding.indexDimension !== embedding.dimension;

  return (
    <Panel
      title="向量化模型"
      description="只读；更换 Embedding 模型必须走迁移与全量重新摄入，不能在运行时切换"
      actions={
        <Badge className="text-slate-600 bg-slate-100 ring-slate-200">
          <CircleSlash className="mr-1 h-2.5 w-2.5" /> 不可切换
        </Badge>
      }
    >
      {mismatch && (
        <div className="mb-3 rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-xs text-rose-800">
          <div className="flex items-center gap-1.5 font-semibold text-rose-900">
            <AlertTriangle className="h-3.5 w-3.5" /> 模型维度与索引维度不一致
          </div>
          <div className="mt-0.5 text-rose-700">
            模型输出 {embedding.dimension} 维，索引列固定为 {embedding.indexDimension} 维。任何摄入都会在写入前
            失败，既有向量也无法与新查询向量比较。需要执行匹配维度的迁移并全量重新摄入。
          </div>
        </div>
      )}

      <div className="grid gap-x-8 md:grid-cols-2">
        <div className="divide-y divide-slate-100">
          <Field label="Provider">{embedding.provider}</Field>
          <Field label="模型">
            <Mono className="text-slate-900 font-semibold">{embedding.model}</Mono>
          </Field>
        </div>
        <div className="divide-y divide-slate-100">
          <Field label="模型输出维度">{formatCount(embedding.dimension)}</Field>
          <Field label="索引列维度">
            <span className={mismatch ? 'text-rose-700 font-bold' : undefined}>
              {embedding.indexDimension === null || embedding.indexDimension === undefined
                ? '—'
                : formatCount(embedding.indexDimension)}
            </span>
          </Field>
        </div>
      </div>

      <p className="mt-3 rounded-lg border border-slate-200 bg-slate-50/70 px-3 py-2 text-[11px] leading-relaxed text-slate-600">
        {embedding.immutableReason}
      </p>
    </Panel>
  );
}
