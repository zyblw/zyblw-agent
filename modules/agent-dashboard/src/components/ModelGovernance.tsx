'use client';

/**
 * 模型治理：已注册目录、运行时切换、连通性探活与 Embedding 的不可变说明。
 *
 * 切换**不是**一个独立的写端点，而是向 `PUT /api/v1/admin/config` 提交一份完整的覆盖快照。这样模型工作点
 * 与工具治理共用同一套乐观锁、审计历史和跨副本刷新；为模型再造一条写入路径会产生两份可能互相矛盾的配置事实。
 * 代价是提交时必须带上**其它人已设置的全部覆盖项**，否则一次模型切换会顺手清掉别人配的工具白名单。
 *
 * 界面只让用户从目录里选择组合。后端会拒绝未注册的 provider/model（400 InvalidConfiguration），但把一个
 * 自由输入框摆在这里意味着一次拼写错误就能让每一次模型调用变成 ProviderNotFound——而它会先显示"保存成功"。
 *
 * 凭据只有"就位与否"和"来自哪个引用"两个事实可用。这里不展示、不请求、也不存储任何 Key 值。
 */

import React, { useMemo, useState } from 'react';
import {
  AlertTriangle,
  Boxes,
  CircleSlash,
  Eraser,
  KeyRound,
  Radio,
  Save,
  Sparkles,
} from 'lucide-react';
import { AdminApiError } from '@/lib/adminClient';
import { useModelCatalog, useProbeModel, useRuntimeConfig, useUpdateRuntimeConfig } from '@/lib/queries';
import { useToast } from '@/lib/toast';
import { useUrlState } from '@/lib/urlState';
import { hasErrors, validateOverrides } from '@/lib/validation';
import {
  MODEL_OVERRIDE_KEYS,
  providersOf,
  type AdminCapabilitiesView,
  type EmbeddingModelView,
  type ModelCatalogView,
  type ModelOptionView,
  type RuntimeConfigView,
  type RuntimeOverrides,
} from '@/types/admin';
import { formatCount, formatDuration, formatPercent } from '@/lib/format';
import {
  Badge,
  Button,
  ConflictNotice,
  EmptyState,
  ErrorBanner,
  Field,
  FOCUS_RING,
  LoadingRows,
  Mono,
  Panel,
  Select,
  StatCard,
  TextInput,
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

export function ModelGovernance({ capabilities }: { capabilities: AdminCapabilitiesView | undefined }) {
  const catalog = useModelCatalog();
  // 只装配 models 而不装配 config 是一个有意的组合：模型页可看不可改。此时不请求配置，避免制造一批 404。
  const canSwitch = capabilities?.runtimeConfig === true;
  const config = useRuntimeConfig(canSwitch);

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
              : '部署未声明价格表，所有成本估算恒为零'
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

          <div className="grid gap-4 xl:grid-cols-2">
            {canSwitch ? (
              config.isPending ? (
                <Panel title="切换生效模型">
                  <LoadingRows rows={4} />
                </Panel>
              ) : config.data ? (
                <ModelSwitchForm
                  // 覆盖版本变化即丢弃草稿：继续基于旧基线编辑只会撞上乐观锁冲突，因此重建编辑器状态
                  // 才是正确行为，而不是一个需要额外同步逻辑的边界情况。
                  key={config.data.overrideVersion}
                  view={config.data}
                  providers={providers}
                  providerOptions={providerOptions}
                  provider={selectedProvider}
                  model={selectedModel}
                  option={selectedOption}
                  onSelect={selectCombination}
                  onReload={() => void config.refetch()}
                  reloading={config.isFetching}
                />
              ) : (
                <Panel title="切换生效模型">
                  <ErrorBanner error={config.error ?? new Error('未获得配置快照')} context="读取运行时配置" />
                </Panel>
              )
            ) : (
              <Panel title="切换生效模型" description="后端未装配运行时配置能力">
                <EmptyState
                  title="该部署不允许在运行时切换模型"
                  reason="模型切换走的是配置覆盖写入路径。宿主只装配了模型目录而没有装配 RuntimeSettingsService，因此这个页面是只读的。"
                />
              </Panel>
            )}

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
  const flags: { label: string; on: boolean; critical?: boolean }[] = [
    { label: '工具调用', on: option.capabilities.toolCalls, critical: true },
    { label: '并行工具', on: option.capabilities.parallelToolCalls },
    { label: '严格 Schema', on: option.capabilities.strictToolSchema },
    { label: '视觉', on: option.capabilities.vision },
    { label: '思考', on: option.capabilities.thinking },
    { label: '流式', on: option.capabilities.streaming },
  ];
  return (
    <div className="flex flex-wrap gap-1">
      {flags.map((flag) => (
        <Badge
          key={flag.label}
          className={
            flag.on
              ? 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30'
              : flag.critical
                ? 'text-rose-300 bg-rose-500/10 ring-rose-500/30'
                : 'text-slate-500 bg-slate-500/5 ring-slate-700/60'
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
          <Badge className="text-rose-300 bg-rose-500/10 ring-rose-500/30">
            {missingCredentials} 个组合缺凭据
          </Badge>
        ) : (
          <Badge className="text-emerald-300 bg-emerald-500/10 ring-emerald-500/30">凭据全部就位</Badge>
        )
      }
    >
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <caption className="sr-only">已注册模型目录，按 Provider 分组</caption>
          <thead className="text-slate-500">
            <tr className="border-b border-slate-800">
              <th scope="col" className="py-2 pr-3 font-medium">模型</th>
              <th scope="col" className="py-2 pr-3 font-medium">能力</th>
              <th scope="col" className="py-2 pr-3 font-medium">上下文窗口</th>
              <th scope="col" className="py-2 pr-3 font-medium">单价 / 百万 token</th>
              <th scope="col" className="py-2 pr-3 font-medium">凭据</th>
            </tr>
          </thead>
          {providers.map((provider) => {
            const group = options.filter((option) => option.provider === provider);
            const first = group[0];
            return (
              <tbody key={provider}>
                <tr className="bg-slate-900/40">
                  <th scope="colgroup" colSpan={5} className="py-1.5 pr-3 text-left font-medium text-slate-300">
                    <span className="inline-flex items-center gap-2">
                      <Boxes className="h-3.5 w-3.5 text-slate-500" />
                      {provider}
                      {first?.isDefaultProvider && (
                        <Badge className="text-indigo-300 bg-indigo-500/10 ring-indigo-500/30">默认 Provider</Badge>
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
                      className={`cursor-pointer border-b border-slate-900 align-top transition hover:bg-slate-900/60 ${
                        selected ? 'bg-indigo-950/30' : ''
                      } ${option.credential.present ? '' : 'bg-rose-950/10'}`}
                    >
                      <td className="py-2 pr-3">
                        <div className="flex flex-wrap items-center gap-1.5">
                          {/* 行的 onClick 只是指针便利；真正的可达控件是这个按钮。只装配了模型目录而没有
                              配置能力的部署里没有下拉框，此时它是键盘用户选中探活目标的唯一入口。 */}
                          <button
                            type="button"
                            aria-pressed={selected}
                            onClick={() => onSelect(option.provider, option.model)}
                            className={`rounded ${FOCUS_RING}`}
                          >
                            <Mono className="text-slate-200">{option.model}</Mono>
                          </button>
                          {effective && (
                            <Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">当前生效</Badge>
                          )}
                        </div>
                        <div className="mt-0.5 text-[11px] text-slate-500">{option.displayName}</div>
                        {!option.declaredModel && (
                          <div className="mt-1 text-[10px] text-amber-400/80">
                            部署默认模型，能力按 Provider 级推断
                          </div>
                        )}
                      </td>
                      <td className="py-2 pr-3">
                        <CapabilityBadges option={option} />
                      </td>
                      <td className="py-2 pr-3 tabular-nums text-slate-400">
                        <div>入 {formatCount(option.capabilities.maxInputTokens ?? null)}</div>
                        <div className="text-slate-500">出 {formatCount(option.capabilities.maxOutputTokens ?? null)}</div>
                      </td>
                      <td className="py-2 pr-3 tabular-nums text-slate-400">
                        {option.price ? (
                          <>
                            <div>
                              入 {option.price.inputPerMillionTokens} {option.price.currency}
                            </div>
                            <div className="text-slate-500">
                              出 {option.price.outputPerMillionTokens} {option.price.currency}
                            </div>
                            {option.price.cachedInputPerMillionTokens && (
                              <div className="text-slate-600">
                                缓存入 {option.price.cachedInputPerMillionTokens} {option.price.currency}
                              </div>
                            )}
                          </>
                        ) : (
                          <span className="text-slate-600">未定价 · 费用估算为零</span>
                        )}
                      </td>
                      <td className="py-2 pr-3">
                        {option.credential.present ? (
                          <span className="inline-flex items-center gap-1 text-emerald-300">
                            <KeyRound className="h-3 w-3" />
                            <Mono className="text-slate-400">{option.credential.reference}</Mono>
                          </span>
                        ) : (
                          <div className="text-rose-300">
                            <span className="inline-flex items-center gap-1 font-medium">
                              <AlertTriangle className="h-3 w-3" /> 缺凭据
                            </span>
                            <div className="mt-0.5 text-[10px] text-rose-300/80">
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

      <p className="mt-3 text-[11px] text-slate-600">
        管理台只能看到凭据是否就位以及它来自哪个引用，看不到也不会请求 Key 值。能力位来自 Provider 的声明，
        用于在切换前发现「这个模型不支持工具调用」这类会让 Agent 循环直接退化的组合。
      </p>
    </Panel>
  );
}

/** 从一份覆盖里去掉四个模型键；返回新对象，不修改入参。 */
function withoutModelOverrides(overrides: RuntimeOverrides): RuntimeOverrides {
  const next: RuntimeOverrides = { ...overrides };
  for (const key of MODEL_OVERRIDE_KEYS) delete next[key];
  return next;
}

/** 解析一个可选数字输入；空串表示"不覆盖"，非法文本返回 NaN 以便校验层给出原因。 */
function parseOptionalNumber(text: string): number | undefined {
  if (text.trim() === '') return undefined;
  return Number(text);
}

/** 切换表单；其生命周期与一个覆盖版本绑定。 */
function ModelSwitchForm({
  view,
  providers,
  providerOptions,
  provider,
  model,
  option,
  onSelect,
  onReload,
  reloading,
}: {
  view: RuntimeConfigView;
  providers: string[];
  providerOptions: ModelOptionView[];
  provider: string;
  model: string;
  option: ModelOptionView | null;
  onSelect: (provider: string, model: string | null) => void;
  onReload: () => void;
  reloading: boolean;
}) {
  const update = useUpdateRuntimeConfig();
  const { notify } = useToast();

  const [temperature, setTemperature] = useState(
    view.overrides.modelTemperature === undefined ? '' : String(view.overrides.modelTemperature),
  );
  const [maxOutputTokens, setMaxOutputTokens] = useState(
    view.overrides.modelMaxOutputTokens === undefined ? '' : String(view.overrides.modelMaxOutputTokens),
  );
  const [reason, setReason] = useState('');

  // 提交的是**完整覆盖快照**而不是补丁：后端用整份对象替换当前覆盖层，因此必须原样带上工具白名单等
  // 本页面不涉及的项，否则一次模型切换会顺手清掉别人设置的工具治理。
  const draft: RuntimeOverrides = {
    ...view.overrides,
    modelProvider: provider || undefined,
    modelName: model || undefined,
    modelTemperature: parseOptionalNumber(temperature),
    modelMaxOutputTokens: parseOptionalNumber(maxOutputTokens),
  };

  const errors = validateOverrides(draft);
  const reasonMissing = reason.trim().length === 0;
  const conflict = update.error instanceof AdminApiError && update.error.isConflict;
  const preserved = withoutModelOverrides(view.overrides);
  const otherOverrides = Object.keys(preserved).length;
  const hasModelOverride = Object.keys(view.overrides).length > otherOverrides;

  function submit(next: RuntimeOverrides, successMessage: string) {
    update.mutate(
      { expectedVersion: view.overrideVersion, overrides: next, reason: reason.trim() },
      {
        onSuccess: (result) => notify('success', successMessage, `覆盖版本 v${result.overrideVersion}`),
        onError: (error) => {
          if (!(error instanceof AdminApiError && error.isConflict)) {
            notify('error', '模型切换失败', error instanceof Error ? error.message : String(error));
          }
        },
      },
    );
  }

  return (
    <Panel
      title="切换生效模型"
      description="提交一次配置覆盖；对所有 Agent 立即生效，并进入同一份审计历史"
      actions={<Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">需要 agent:admin:write</Badge>}
    >
      {conflict ? (
        <div className="mb-3">
          <ConflictNotice
            onReload={() => {
              update.reset();
              onReload();
            }}
            reloading={reloading}
            description={`你提交的覆盖基于 v${view.overrideVersion}，服务端已经更新到更高版本，因此被拒绝。重新加载会取回最新配置，本表单里未保存的温度、输出上限与变更原因将被丢弃。`}
          />
        </div>
      ) : (
        <div className="mb-3">
          <ErrorBanner error={update.error} context="切换模型" />
        </div>
      )}

      {/* 只能从目录里选：自由输入的 provider 名会被后端拒绝（400），但在此之前它已经让人以为自己配对了。 */}
      <div className="grid gap-3 md:grid-cols-2">
        <Select
          label="Provider"
          value={provider}
          onChange={(next) => onSelect(next, null)}
          options={providers.map((name) => ({ value: name, label: name }))}
          hint="切换 Provider 会重新选择该 Provider 下的第一个模型"
        />
        <Select
          label="模型"
          value={model}
          onChange={(next) => onSelect(provider, next)}
          options={providerOptions.map((item) => ({
            value: item.model,
            label: item.credential.present ? item.model : `${item.model}（缺凭据）`,
          }))}
          hint={`该 Provider 下有 ${providerOptions.length} 个已注册模型`}
        />
      </div>

      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <Field label="当前生效覆盖">
          {view.overrides.modelProvider ? (
            <Mono className="text-slate-200">
              {view.overrides.modelProvider}/{view.overrides.modelName ?? '（未指定模型）'}
            </Mono>
          ) : (
            '各 Agent 定义'
          )}
        </Field>
        <Field label="其它覆盖项">
          {otherOverrides > 0 ? `${otherOverrides} 项将原样保留` : '无'}
        </Field>
      </div>

      {option && !option.credential.present && (
        <div className="mt-3 rounded-lg border border-rose-900/60 bg-rose-950/20 px-3 py-2 text-xs text-rose-200">
          <div className="flex items-center gap-1.5 font-medium">
            <AlertTriangle className="h-3.5 w-3.5" /> 目标 Provider 缺少凭据
          </div>
          <div className="mt-0.5 text-rose-300/80">
            切到它之后每一次模型调用都会失败。请先在部署侧配置 <Mono>{option.credential.reference}</Mono>。
          </div>
        </div>
      )}

      {option && !option.capabilities.toolCalls && (
        <div className="mt-3 rounded-lg border border-amber-900/60 bg-amber-950/20 px-3 py-2 text-xs text-amber-200">
          该模型不支持工具调用。切换后依赖工具的 Agent 会退化成纯文本问答，而不是报错。
        </div>
      )}

      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <TextInput
          label="采样温度（留空表示不覆盖）"
          value={temperature}
          onChange={setTemperature}
          placeholder="0.0 – 2.0"
          inputMode="decimal"
          error={errors.modelTemperature}
          hint="沿用各 Agent 自己的 modelSettings"
        />
        <TextInput
          label="单次输出上限（留空表示不覆盖）"
          value={maxOutputTokens}
          onChange={setMaxOutputTokens}
          placeholder="1 – 1000000"
          inputMode="numeric"
          error={errors.modelMaxOutputTokens}
          hint="超过模型自身上限时由 Provider 拒绝"
        />
      </div>

      <div className="mt-3">
        <TextInput
          label="变更原因（必填，进入审计历史）"
          value={reason}
          onChange={setReason}
          placeholder="例如：DeepSeek 限流，临时切到备用 Provider"
          error={reasonMissing ? '必须填写变更原因；它是事后复盘唯一能解释这次切换的记录' : undefined}
        />
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button
          onClick={() => submit(draft, '模型切换已生效')}
          disabled={!provider || !model || reasonMissing || hasErrors(errors) || update.isPending}
          title={
            hasErrors(errors)
              ? '存在越界的取值，修正后才能提交'
              : reasonMissing
                ? '请先填写变更原因'
                : undefined
          }
        >
          <Save className="h-3 w-3" /> {update.isPending ? '提交中…' : '切换到该模型'}
        </Button>
        <Button
          variant="secondary"
          disabled={reasonMissing || update.isPending || !hasModelOverride}
          onClick={() => submit(preserved, '模型覆盖已清除')}
          title={
            !hasModelOverride
              ? '当前没有模型覆盖，各 Agent 已在使用自己的定义'
              : reasonMissing
                ? '请先填写变更原因'
                : '删除四个模型覆盖项，恢复各 Agent 自己的定义'
          }
        >
          <Eraser className="h-3 w-3" /> 清除模型覆盖
        </Button>
        <span className="text-[11px] text-slate-600">当前覆盖版本 v{view.overrideVersion}</span>
      </div>

      <p className="mt-3 text-[11px] text-slate-600">
        模型覆盖没有「部署基线」可言：基线就是每个 Agent 自己的 modelSettings。因此清除覆盖等于让各 Agent 恢复
        自己的定义，而不是把它们统一设成某个默认模型。
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
      actions={<Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">需要 agent:admin:debug</Badge>}
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
          <span className="text-[11px] text-rose-300">该组合缺凭据，探活预计会以认证失败告终。</span>
        )}
      </div>

      <div className="mt-3">
        <ErrorBanner error={probe.error} context="执行探活" />
      </div>

      {result ? (
        <div className="mt-3 divide-y divide-slate-900">
          <Field label="结果">
            {result.succeeded ? (
              <Badge className="text-emerald-300 bg-emerald-500/10 ring-emerald-500/30">成功</Badge>
            ) : (
              <Badge className="text-rose-300 bg-rose-500/10 ring-rose-500/30">失败</Badge>
            )}
          </Field>
          <Field label="实际路由">
            <Mono>
              {result.provider} / {result.model}
            </Mono>
          </Field>
          <Field label="端到端耗时">{formatDuration(result.latencyMillis)}</Field>
          <Field label="消耗 token">
            入 {formatCount(result.inputTokens)} · 出 {formatCount(result.outputTokens)}
          </Field>
          <Field label="失败分类">
            {result.failureCode ? <Mono className="text-rose-300">{result.failureCode}</Mono> : '—'}
          </Field>
          {!result.succeeded && (
            <div className="py-2 text-xs leading-relaxed text-rose-200">
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

      <p className="mt-3 flex items-start gap-1.5 text-[11px] text-slate-600">
        <Sparkles className="mt-0.5 h-3 w-3 shrink-0" />
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
        <Badge className="text-slate-400 bg-slate-500/10 ring-slate-500/30">
          <CircleSlash className="mr-1 h-2.5 w-2.5" /> 不可切换
        </Badge>
      }
    >
      {mismatch && (
        <div className="mb-3 rounded-lg border border-rose-900/60 bg-rose-950/30 px-3 py-2 text-xs text-rose-200">
          <div className="flex items-center gap-1.5 font-medium">
            <AlertTriangle className="h-3.5 w-3.5" /> 模型维度与索引维度不一致
          </div>
          <div className="mt-0.5 text-rose-300/80">
            模型输出 {embedding.dimension} 维，索引列固定为 {embedding.indexDimension} 维。任何摄入都会在写入前
            失败，既有向量也无法与新查询向量比较。需要执行匹配维度的迁移并全量重新摄入。
          </div>
        </div>
      )}

      <div className="grid gap-x-8 md:grid-cols-2">
        <div className="divide-y divide-slate-900">
          <Field label="Provider">{embedding.provider}</Field>
          <Field label="模型">
            <Mono>{embedding.model}</Mono>
          </Field>
        </div>
        <div className="divide-y divide-slate-900">
          <Field label="模型输出维度">{formatCount(embedding.dimension)}</Field>
          <Field label="索引列维度">
            <span className={mismatch ? 'text-rose-300' : undefined}>
              {embedding.indexDimension === null || embedding.indexDimension === undefined
                ? '—'
                : formatCount(embedding.indexDimension)}
            </span>
          </Field>
        </div>
      </div>

      <p className="mt-3 rounded-lg border border-slate-800 bg-slate-950/40 px-3 py-2 text-[11px] leading-relaxed text-slate-400">
        {embedding.immutableReason}
      </p>
    </Panel>
  );
}
