'use client';

/**
 * 运行时配置：基线 / 覆盖 / 生效三列对照，乐观锁写入与审计历史。
 *
 * 三列同时显示是这个页面的关键设计。只显示生效值会让人无法区分"部署里本来就是这么配的"和"有人临时改过"，
 * 而这两种情况在事故复盘时的含义完全不同。
 *
 * 每一项都标注生效边界（立即 / 下个 Run / 需重启）。一个保存成功却要等重启才起作用的开关，如果不明确
 * 说明，会让运维误以为限制已经收紧。
 */

import React, { useMemo, useState } from 'react';
import { History, RotateCcw, Save, ShieldAlert, Zap } from 'lucide-react';
import { AdminApiError } from '@/lib/adminClient';
import { useConfigHistory, useRuntimeConfig, useUpdateRuntimeConfig } from '@/lib/queries';
import { useToast } from '@/lib/toast';
import { hasErrors, validateOverrides, type OverrideErrors } from '@/lib/validation';
import type {
  RuntimeConfigView,
  RuntimeOverrides,
  RuntimeSettingApplies,
  RuntimeSettingField,
} from '@/types/admin';
import { formatInstant, formatRelative, parseList } from '@/lib/format';
import {
  Badge,
  Button,
  ConflictNotice,
  EmptyState,
  ErrorBanner,
  FOCUS_RING,
  LoadingRows,
  Mono,
  Panel,
  StatCard,
  TextInput,
} from '@/components/ui';

/** 生效边界的展示文案与色彩。 */
const APPLIES_META: Record<RuntimeSettingApplies, { label: string; tone: string; hint: string }> = {
  Immediate: {
    label: '立即生效',
    tone: 'text-emerald-700 bg-emerald-50 ring-emerald-600/20 font-medium',
    hint: '下一次工具执行或检索即生效，无需重启或新建 Run。',
  },
  NextRun: {
    label: '下个 Run',
    tone: 'text-blue-700 bg-blue-50 ring-blue-600/20 font-medium',
    hint: '既有 Run 在创建时已把该值冻结进状态，改动它只影响此后新建的 Run。',
  },
  Restart: {
    label: '需重启',
    tone: 'text-slate-600 bg-slate-100 ring-slate-400/20 font-medium',
    hint: '该值在装配依赖图时被固化为不可变资源，因此不接受运行时覆盖。',
  },
};

/**
 * 覆盖字段的编辑控件类型；由字段 key 决定，与后端白名单一一对应。
 *
 * Provider / 模型名不再接受全局覆盖：问答走管理台角色绑定。采样温度与输出上限仍可在此覆盖。
 */
type Editor = 'list' | 'number' | 'boolean' | 'approval' | 'readonly' | 'model';

const EDITORS: Record<string, Editor> = {
  toolAllowedTools: 'list',
  toolDeniedTools: 'list',
  toolDefaultTimeoutMillis: 'number',
  toolMaxResultBytes: 'number',
  toolApprovalPolicy: 'approval',
  toolMaxCallsPerRun: 'number',
  toolMaxCallsPerStep: 'readonly',
  toolMaxParallelism: 'readonly',
  explorationMaxSteps: 'number',
  explorationMaxModelCalls: 'number',
  explorationMaxRepeatedActions: 'number',
  explorationMaxInputTokens: 'number',
  explorationMaxOutputTokens: 'number',
  explorationMaxTotalTokens: 'number',
  explorationMaxDurationSeconds: 'number',
  askMetering: 'readonly',
  costMetering: 'readonly',
  retrievalTopK: 'number',
  retrievalMinimumScore: 'number',
  rerankEnabled: 'boolean',
  modelProvider: 'model',
  modelName: 'model',
  modelTemperature: 'number',
  modelMaxOutputTokens: 'number',
};

const FIELD_NOTES: Record<string, string> = {
  toolMaxCallsPerRun: '问答 Run 不会超过 128。',
  explorationMaxRepeatedActions: '计的是连续工具失败，成功的检索不计入。',
  explorationMaxTotalTokens: '只调低累计时，输入和输出会一起收到不超过这个数。',
};

export function ConfigStudio() {
  const config = useRuntimeConfig();

  if (config.isPending) {
    return (
      <div className="p-4">
        <LoadingRows rows={8} />
      </div>
    );
  }

  if (config.error || !config.data) {
    return (
      <div className="p-4">
        <ErrorBanner error={config.error ?? new Error('未获得配置快照')} context="读取运行时配置" />
      </div>
    );
  }

  // 用 `key` 绑定覆盖版本，让服务端快照变化时 React 直接丢弃并重建编辑器状态。
  // 这比在 effect 里重置草稿更准确：另一个管理员刚改过配置后，继续基于旧基线编辑只会撞上版本冲突，
  // 因此丢弃未保存的改动是正确行为，而不是需要额外同步逻辑的边界情况。
  return (
    <ConfigEditor
      key={config.data.overrideVersion}
      view={config.data}
      onReload={() => void config.refetch()}
      reloading={config.isFetching}
    />
  );
}

/** 配置编辑器；其生命周期与一个覆盖版本绑定。 */
function ConfigEditor({
  view,
  onReload,
  reloading,
}: {
  view: RuntimeConfigView;
  onReload: () => void;
  reloading: boolean;
}) {
  const history = useConfigHistory(20);
  const update = useUpdateRuntimeConfig();
  const { notify } = useToast();

  const [draft, setDraft] = useState<RuntimeOverrides>(view.overrides);
  const [reason, setReason] = useState('');

  const dirty = useMemo(
    () => JSON.stringify(draft) !== JSON.stringify(view.overrides),
    [draft, view.overrides],
  );

  // 客户端校验与后端 `RuntimeOverrides.validate` 同区间：把一个明显越界的值送到服务端再被拒绝，
  // 会让运维在一次 round-trip 之后才知道自己多打了一个零。
  const errors = validateOverrides(draft);
  const invalid = hasErrors(errors);
  const conflict = update.error instanceof AdminApiError && update.error.isConflict;

  const activeOverrides = Object.values(draft).filter((value) => value !== undefined).length;

  function setField(key: keyof RuntimeOverrides, value: unknown) {
    setDraft((current) => {
      const next: RuntimeOverrides = { ...current };
      if (value === undefined) delete next[key];
      else (next as Record<string, unknown>)[key] = value;
      return next;
    });
  }

  function save() {
    update.mutate(
      {
        expectedVersion: view.overrideVersion,
        overrides: draft,
        reason: reason.trim() || '通过控制台修改',
      },
      {
        onSuccess: (result) =>
          notify('success', '配置覆盖已保存', `覆盖版本 v${result.overrideVersion}，立即生效项已在多副本间传播`),
        onError: (error) => {
          if (!(error instanceof AdminApiError && error.isConflict)) {
            notify('error', '保存配置失败', error instanceof Error ? error.message : String(error));
          }
        },
      },
    );
  }

  const diffCount = dirty
    ? Object.keys(draft).filter(
        (k) =>
          JSON.stringify((draft as Record<string, unknown>)[k]) !==
          JSON.stringify((view.overrides as Record<string, unknown>)[k]),
      ).length
    : 0;

  const [category, setCategory] = useState<'all' | 'tools' | 'exploration' | 'rag' | 'model'>('all');

  function applyPreset(name: string) {
    if (name === 'strict') {
      setDraft({
        ...draft,
        toolApprovalPolicy: 'risk-based',
        toolMaxCallsPerRun: 32,
        explorationMaxSteps: 16,
        explorationMaxRepeatedActions: 5,
        explorationMaxDurationSeconds: 60,
      });
      setReason('套用预设：严格安全生产模式');
      notify('info', '已载入严格生产预设', '包含基于风险审批、步数收敛限制，请审核后保存');
    } else if (name === 'fast') {
      setDraft({
        ...draft,
        toolApprovalPolicy: 'never',
        toolMaxCallsPerRun: 16,
        explorationMaxSteps: 12,
        retrievalTopK: 3,
        rerankEnabled: false,
      });
      setReason('套用预设：极速低延迟模式');
      notify('info', '已载入极速响应预设', '关闭重排、减少召回条数，请审核后保存');
    } else if (name === 'explore') {
      setDraft({
        ...draft,
        toolApprovalPolicy: 'never',
        toolMaxCallsPerRun: 64,
        explorationMaxSteps: 48,
        explorationMaxRepeatedActions: 10,
        explorationMaxTotalTokens: 2000000,
        retrievalTopK: 8,
        rerankEnabled: true,
      });
      setReason('套用预设：深度探索与规划模式');
      notify('info', '已载入深度探索预设', '提升步数上限与 Token 预算，适合复杂任务');
    }
  }

  const filteredFields = view.fields.filter((field) => {
    if (category === 'all') return true;
    if (category === 'tools') return field.key.startsWith('tool');
    if (category === 'exploration') return field.key.startsWith('exploration');
    if (category === 'rag') return field.key.startsWith('retrieval') || field.key.startsWith('rerank');
    if (category === 'model') return field.key.startsWith('model');
    return true;
  });

  return (
    <div className="space-y-4 p-4">
      {/* 预设场景快捷工具条 */}
      <div className="rounded-xl border border-indigo-100 bg-gradient-to-r from-indigo-50/80 via-white to-slate-50 p-3.5 shadow-xs">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Zap className="h-4 w-4 text-indigo-600" />
            <span className="text-xs font-semibold text-slate-900">场景化配置预设</span>
            <span className="text-[11px] text-slate-500">一键按行业最佳实践调整运行工作点</span>
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => applyPreset('strict')}
              className="inline-flex items-center gap-1 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-medium text-amber-800 hover:bg-amber-100 transition shadow-xs"
            >
              🛡️ 严格生产模式 (安全第一)
            </button>
            <button
              type="button"
              onClick={() => applyPreset('fast')}
              className="inline-flex items-center gap-1 rounded-lg border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-[11px] font-medium text-emerald-800 hover:bg-emerald-100 transition shadow-xs"
            >
              ⚡ 极速低延迟模式 (精简召回)
            </button>
            <button
              type="button"
              onClick={() => applyPreset('explore')}
              className="inline-flex items-center gap-1 rounded-lg border border-blue-200 bg-blue-50 px-2.5 py-1 text-[11px] font-medium text-blue-800 hover:bg-blue-100 transition shadow-xs"
            >
              🔬 深度探索模式 (放宽预算)
            </button>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="覆盖版本" value={view.overrideVersion} hint="乐观锁令牌" />
        <StatCard
          label="生效覆盖项"
          value={activeOverrides}
          tone={activeOverrides > 0 ? 'warn' : 'neutral'}
          hint="基线之外的改动数"
        />
        <StatCard label="最后修改者" value={<span className="text-base">{view.overrideUpdatedBy}</span>} />
        <StatCard
          label="最后修改"
          value={<span className="text-base">{formatRelative(view.overrideUpdatedAtEpochMilli)}</span>}
          hint={view.overrideReason || undefined}
        />
      </div>

      <Panel
        title="配置项"
        description="基线来自部署配置，覆盖持久化在数据库并在多副本间以秒级延迟传播"
        actions={
          <>
            {diffCount > 0 && (
              <span className="inline-flex items-center rounded-full bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-800 ring-1 ring-inset ring-amber-600/20">
                {diffCount} 项待保存改动
              </span>
            )}
            <TextInput value={reason} onChange={setReason} placeholder="变更原因（进入审计）" className="w-56" />
            <Button
              variant="secondary"
              disabled={!dirty}
              onClick={() => {
                setDraft(view.overrides);
                setReason('');
              }}
            >
              <RotateCcw className="h-3 w-3" /> 放弃改动
            </Button>
            <Button
              onClick={save}
              disabled={!dirty || invalid || update.isPending}
              title={invalid ? '存在越界的取值，修正后才能提交' : undefined}
            >
              <Save className="h-3 w-3" /> {update.isPending ? '保存中…' : '保存覆盖'}
            </Button>
          </>
        }
      >
        {/* 分类过滤器 */}
        <div className="mb-3 flex flex-wrap items-center gap-1.5 border-b border-slate-200 pb-2.5">
          <span className="text-xs text-slate-500 mr-1 font-medium">分类视角:</span>
          {(
            [
              { id: 'all', label: '全部配置' },
              { id: 'tools', label: '🛡️ 工具与安全' },
              { id: 'exploration', label: '🧭 探索与发散限制' },
              { id: 'rag', label: '🔍 检索与重排' },
              { id: 'model', label: '🧠 模型参数' },
            ] as const
          ).map((cat) => (
            <button
              key={cat.id}
              type="button"
              onClick={() => setCategory(cat.id)}
              className={`rounded-md px-2.5 py-1 text-xs font-medium transition ${
                category === cat.id
                  ? 'bg-indigo-50 text-indigo-700 ring-1 ring-indigo-200'
                  : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
              }`}
            >
              {cat.label}
            </button>
          ))}
        </div>

        {conflict ? (
          <ConflictNotice
            onReload={() => {
              update.reset();
              onReload();
            }}
            reloading={reloading}
            description={`你的提交基于 v${view.overrideVersion}，服务端已经更新到更高版本，因此被拒绝。重新加载会取回服务端最新配置，你尚未保存的编辑将被丢弃——重复提交这份旧快照会悄悄回滚别人刚做的改动。`}
          />
        ) : (
          <ErrorBanner error={update.error} context="保存配置覆盖" />
        )}

        {errors.toolSetOverlap && (
          <div className="mt-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800">
            {errors.toolSetOverlap}
          </div>
        )}

        <div className="mt-1 overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-slate-500">
              <tr className="border-b border-slate-200">
                <th className="py-2.5 pr-3 font-semibold text-slate-700">配置项</th>
                <th className="py-2.5 pr-3 font-semibold text-slate-700">部署基线</th>
                <th className="py-2.5 pr-3 font-semibold text-slate-700">覆盖值</th>
                <th className="py-2.5 pr-3 font-semibold text-slate-700">当前生效</th>
                <th className="py-2.5 pr-3 font-semibold text-slate-700">生效边界</th>
              </tr>
            </thead>
            <tbody>
              {filteredFields.map((field) => (
                <ConfigRow
                  key={field.key}
                  field={field}
                  draft={draft}
                  errors={errors}
                  onChange={setField}
                />
              ))}
            </tbody>
          </table>
        </div>

        <p className="mt-3 text-[11px] text-slate-500 leading-relaxed">
          覆盖层是基线之上的稀疏补丁：清空一项等于恢复基线，而不是把它设成默认值。收紧策略会让后续工具调用
          被拒绝或要求审批；放宽策略只影响尚未规划的批次，已冻结进运行状态的预算不受影响。
        </p>
      </Panel>

      <Panel
        title="变更历史"
        description="配置存储本身是 append-only 的审计日志，每一行是那个版本下生效的完整覆盖快照"
        actions={<History className="h-3.5 w-3.5 text-slate-500" />}
      >
        <ErrorBanner error={history.error} context="读取变更历史" />
        {history.isPending ? (
          <LoadingRows rows={3} />
        ) : (history.data?.length ?? 0) === 0 ? (
          <EmptyState title="尚无覆盖记录" reason="当前部署完全运行在基线配置上。" />
        ) : (
          <div className="space-y-1.5">
            {history.data?.map((record) => (
              <div
                key={record.version}
                className="flex items-start gap-3 rounded-lg border border-slate-200 bg-slate-50/50 hover:bg-slate-50 px-3 py-2.5 text-xs transition"
              >
                <Badge>v{record.version}</Badge>
                <div className="min-w-0 flex-1">
                  <div className="text-slate-900 font-medium">{record.reason || '（未填写原因）'}</div>
                  <div className="mt-0.5 text-[11px] text-slate-500">
                    {record.updatedBy} · {formatInstant(record.updatedAtEpochMilli)} ·{' '}
                    {Object.values(record.overrides).filter((value) => value !== undefined).length} 项覆盖
                  </div>
                </div>
                <Button
                  variant="secondary"
                  onClick={() => {
                    setDraft(record.overrides);
                    setReason(`回滚到 v${record.version}`);
                  }}
                >
                  载入此版本
                </Button>
              </div>
            ))}
          </div>
        )}
      </Panel>
    </div>
  );
}

/** 单个配置项行；根据字段类型选择编辑控件。 */
function ConfigRow({
  field,
  draft,
  errors,
  onChange,
}: {
  field: RuntimeSettingField;
  draft: RuntimeOverrides;
  errors: OverrideErrors;
  onChange: (key: keyof RuntimeOverrides, value: unknown) => void;
}) {
  const editor = EDITORS[field.key] ?? 'readonly';
  const key = field.key as keyof RuntimeOverrides;
  const current = draft[key];
  const overridden = current !== undefined;
  const meta = APPLIES_META[field.applies];
  const error = errors[key];

  return (
    <tr className="border-b border-slate-100 align-top hover:bg-slate-50/50 transition">
      <td className="py-2.5 pr-3">
        <div>
          <div className="flex items-center gap-1.5">
            <Mono className="text-slate-900 font-semibold">{field.key}</Mono>
            {(field.key === 'askMetering' || field.key === 'costMetering') && (
              <a href="/admin/operations" className="block text-[11px] text-blue-600 underline hover:text-blue-800">
                网站运营数据
              </a>
            )}
            {field.sensitive && (
              <span title="安全敏感项：改动会直接影响工具治理或审批强度">
                <ShieldAlert className="h-3.5 w-3.5 text-amber-500" />
              </span>
            )}
          </div>
          {FIELD_NOTES[field.key] && (
            <span className="mt-1 block max-w-56 text-[10px] text-slate-500">{FIELD_NOTES[field.key]}</span>
          )}
        </div>
      </td>
      <td className="py-2.5 pr-3 text-slate-500 font-mono text-[11px]">{field.baselineValue}</td>
      <td className="py-2.5 pr-3">
        <ValueEditor
          editor={editor}
          value={current}
          label={field.key}
          invalid={error !== undefined}
          onChange={(value) => onChange(key, value)}
        />
        {error && <div className="mt-1 max-w-56 text-[10px] text-rose-600 font-medium">{error}</div>}
        {overridden && editor !== 'readonly' && editor !== 'model' && (
          <button
            type="button"
            onClick={() => onChange(key, undefined)}
            className={`mt-1 rounded text-[10px] text-slate-500 underline hover:text-slate-800 ${FOCUS_RING}`}
          >
            清除覆盖，恢复基线
          </button>
        )}
      </td>
      <td className="py-2.5 pr-3">
        <span className={overridden ? 'font-semibold text-amber-700 font-mono text-[11px]' : 'text-slate-700 font-mono text-[11px]'}>
          {field.effectiveValue}
        </span>
      </td>
      <td className="py-2.5 pr-3">
        <span title={meta.hint}>
          <Badge className={meta.tone}>
            {field.applies === 'Immediate' && <Zap className="mr-1 h-2.5 w-2.5" />}
            {meta.label}
          </Badge>
        </span>
      </td>
    </tr>
  );
}

/**
 * 按字段类型渲染编辑控件；`undefined` 一律表示"沿用基线"。
 *
 * 控件的可访问名称用 `aria-label` 给出字段 key：表格里的表头离控件太远，读屏用户在一列输入框之间移动时
 * 需要每个控件自报家门。
 */
function ValueEditor({
  editor,
  value,
  label,
  invalid,
  onChange,
}: {
  editor: Editor;
  value: unknown;
  label: string;
  invalid: boolean;
  onChange: (value: unknown) => void;
}) {
  if (editor === 'readonly') {
    return <span className="text-[11px] text-slate-400">不可覆盖</span>;
  }

  if (editor === 'model') {
    return (
      <span className="text-[11px] text-slate-500">
        {value === undefined ? '沿用角色绑定' : String(value)}
        <span className="block text-slate-400">到宿主 /admin/models 绑定角色</span>
      </span>
    );
  }

  const control = `rounded border bg-white px-2 py-1 text-xs text-slate-900 shadow-xs ${FOCUS_RING} ${
    invalid ? 'border-rose-400 focus:border-rose-500' : 'border-slate-300 focus:border-indigo-600'
  }`;

  if (editor === 'boolean') {
    return (
      <select
        aria-label={label}
        aria-invalid={invalid || undefined}
        value={value === undefined ? '' : String(value)}
        onChange={(event) => onChange(event.target.value === '' ? undefined : event.target.value === 'true')}
        className={control}
      >
        <option value="">沿用基线</option>
        <option value="true">启用</option>
        <option value="false">禁用</option>
      </select>
    );
  }

  if (editor === 'approval') {
    return (
      <select
        aria-label={label}
        aria-invalid={invalid || undefined}
        value={typeof value === 'string' ? value : ''}
        onChange={(event) => onChange(event.target.value === '' ? undefined : event.target.value)}
        className={control}
      >
        <option value="">沿用基线</option>
        <option value="never">never — 不要求审批</option>
        <option value="risk-based">risk-based — 按风险等级</option>
        <option value="always">always — 全部要求审批</option>
      </select>
    );
  }

  if (editor === 'number') {
    return (
      <input
        type="number"
        aria-label={label}
        aria-invalid={invalid || undefined}
        value={typeof value === 'number' ? String(value) : ''}
        placeholder="沿用基线"
        onChange={(event) => {
          const text = event.target.value;
          if (text === '') return onChange(undefined);
          const parsed = Number(text);
          // 越界但有限的值（例如多打了一个零）保留下来交给校验层报告原因；无法解析成数字的输入回落到
          // "沿用基线"，因为 number 输入框无法把 NaN 显示出来，留着它只会让草稿与可见内容不一致。
          onChange(Number.isNaN(parsed) ? undefined : parsed);
        }}
        className={`w-28 tabular-nums ${control}`}
      />
    );
  }

  // list：逗号或换行分隔。空文本表示"沿用基线"，而空集合（明确输入后清空到零项）表示"禁用全部"——
  // 后者是 fail-closed 语义下的合法配置，因此不能把两者折叠成同一个状态。
  const asList = Array.isArray(value) ? (value as string[]) : undefined;
  return (
    <div>
      <textarea
        rows={2}
        aria-label={label}
        aria-invalid={invalid || undefined}
        value={asList ? asList.join(', ') : ''}
        placeholder="沿用基线"
        onChange={(event) => {
          const text = event.target.value;
          onChange(text.trim() === '' ? undefined : parseList(text));
        }}
        className={`w-56 text-[11px] ${control}`}
      />
      {asList && asList.length === 0 && (
        <div className="text-[10px] text-rose-400">空集合表示禁用全部</div>
      )}
    </div>
  );
}
