/**
 * 运行时覆盖的客户端预校验。
 *
 * 这是 Scala 侧 `RuntimeOverrides.validate` 的**镜像而非替代**：后端仍然会在写入前完整校验，这里只是为了
 * 不让一个明显越界的值消耗一次 round-trip 才得到拒绝。区间必须与后端逐条对齐，任何一侧改动都要同时改另一侧，
 * 否则界面会拒绝一个合法值，或者放行一个必然被拒的值。
 *
 * 目录相关的校验（provider / model 是否已注册）刻意不在这里：它依赖服务端目录，属于效果式校验。界面通过
 * "只能从目录里选"来避免这类错误，而不是复制一份可能过期的注册表。
 */

import type { RuntimeOverrides } from '@/types/admin';

/** 各字段的校验错误；键与 `RuntimeOverrides` 的字段一一对应，未出错的字段不出现。 */
export type OverrideErrors = Partial<Record<keyof RuntimeOverrides | 'toolSetOverlap', string>>;

function integerInRange(value: number | undefined, min: number, max: number, message: string): string | undefined {
  if (value === undefined) return undefined;
  if (!Number.isInteger(value) || value < min || value > max) return message;
  return undefined;
}

function numberInRange(value: number | undefined, min: number, max: number, message: string): string | undefined {
  if (value === undefined) return undefined;
  if (!Number.isFinite(value) || value < min || value > max) return message;
  return undefined;
}

/** 校验一份覆盖草稿；返回的对象为空表示可以提交。 */
export function validateOverrides(overrides: RuntimeOverrides): OverrideErrors {
  const errors: OverrideErrors = {};

  const emptyToolName = (tools: string[] | undefined) => tools?.some((tool) => tool.trim().length === 0) ?? false;
  if (emptyToolName(overrides.toolAllowedTools)) errors.toolAllowedTools = '工具白名单不能包含空名称';
  if (emptyToolName(overrides.toolDeniedTools)) errors.toolDeniedTools = '工具黑名单不能包含空名称';

  errors.toolDefaultTimeoutMillis = integerInRange(
    overrides.toolDefaultTimeoutMillis,
    100,
    600_000,
    '工具超时必须在 100ms 到 600000ms 之间',
  );
  errors.toolMaxResultBytes = integerInRange(
    overrides.toolMaxResultBytes,
    1024,
    16 * 1024 * 1024,
    '工具结果上限必须在 1KiB 到 16MiB 之间',
  );
  errors.toolMaxCallsPerRun = integerInRange(
    overrides.toolMaxCallsPerRun,
    1,
    1000,
    '单 Run 工具调用上限必须在 1 到 1000 之间',
  );
  errors.toolMaxCallsPerStep = integerInRange(
    overrides.toolMaxCallsPerStep,
    1,
    100,
    '单步工具调用上限必须在 1 到 100 之间',
  );
  errors.retrievalTopK = integerInRange(overrides.retrievalTopK, 1, 100, '检索 topK 必须在 1 到 100 之间');
  errors.retrievalMinimumScore = numberInRange(
    overrides.retrievalMinimumScore,
    0,
    1,
    '检索最低得分必须在 0.0 到 1.0 之间',
  );
  errors.modelTemperature = numberInRange(overrides.modelTemperature, 0, 2, '模型温度必须在 0.0 到 2.0 之间');
  errors.modelMaxOutputTokens = integerInRange(
    overrides.modelMaxOutputTokens,
    1,
    1_000_000,
    '模型输出上限必须在 1 到 1000000 之间',
  );

  if (overrides.modelProvider !== undefined && overrides.modelProvider.trim().length === 0) {
    errors.modelProvider = 'Provider 覆盖不能为空字符串';
  }
  if (overrides.modelName !== undefined && overrides.modelName.trim().length === 0) {
    errors.modelName = '模型名覆盖不能为空字符串';
  }

  // 同一个工具同时出现在两个名单上不是"黑名单赢"，而是一份自相矛盾的策略：后端直接拒绝，因此这里也拒绝。
  if (overrides.toolAllowedTools && overrides.toolDeniedTools) {
    const overlap = overrides.toolAllowedTools.filter((tool) => overrides.toolDeniedTools?.includes(tool));
    if (overlap.length > 0) {
      errors.toolSetOverlap = `工具同时出现在白名单和黑名单: ${overlap.slice().sort().join(',')}`;
    }
  }

  for (const key of Object.keys(errors) as (keyof OverrideErrors)[]) {
    if (errors[key] === undefined) delete errors[key];
  }
  return errors;
}

/** 是否存在任何校验错误。 */
export function hasErrors(errors: OverrideErrors): boolean {
  return Object.keys(errors).length > 0;
}
