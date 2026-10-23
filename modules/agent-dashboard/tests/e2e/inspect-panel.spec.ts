import { expect, test } from '@playwright/test';

const backend = 'http://mock.agent';

test('检查面板渲染冻结/现场对照、漂移字段和账本', async ({ page }) => {
  await page.addInitScript(
    ({ backendUrl }) => {
      window.localStorage.setItem('zyblw-agent-dashboard.base-url', backendUrl);
      window.sessionStorage.setItem('zyblw-agent-dashboard.token', 'inspect-admin-token');
    },
    { backendUrl: backend },
  );

  let forbiddenRequests = 0;
  await page.route('http://mock*.agent/api/v1/admin/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const secondBackend = url.hostname === 'mock-b.agent';
    if (route.request().headers().authorization === 'Bearer denied-token') {
      forbiddenRequests += 1;
      await route.fulfill({ status: 403, json: { category: 'forbidden', message: 'permission-revoked' } });
      return;
    }
    if (path.endsWith('/capabilities')) {
      await route.fulfill({
        json: {
          apiVersion: 1,
          runDirectory: false,
          runEventStream: false,
          runtimeConfig: false,
          queueOps: false,
          knowledge: false,
          evalTrends: false,
          models: false,
          runInspection: true,
          harness: false,
          memoryGovernance: false,
          observability: {},
        },
      });
      return;
    }
    if (path.endsWith('/composition')) {
      await route.fulfill({
        json: {
          runId: '11111111-1111-1111-1111-111111111111',
          composition: {
            frozen: {
              fingerprintPrefix: 'abcd1234abcd1234',
              profileId: 'default',
              modelRefPrefix: 'model-ref',
              capturePolicy: 'MetadataOnly',
              allowedTools: ['echo'],
              sourceIds: [{ kind: 'Context', id: 'memory-rag', version: '2' }],
              extensionIds: [],
              environmentId: 'local',
              permissionFingerprintPrefix: 'perm-prefix',
            },
            live: {
              fingerprintPrefix: 'ffff1234abcd1234',
              profileId: 'eval',
              modelRefPrefix: 'model-ref',
              capturePolicy: 'MetadataOnly',
              allowedTools: ['echo'],
              sourceIds: [{ kind: 'Context', id: 'memory-rag', version: '2' }],
              extensionIds: [],
              environmentId: 'local',
              permissionFingerprintPrefix: 'perm-prefix',
            },
            driftKind: 'requires-revalidation',
            changedFields: [{ field: 'profileId', kind: 'Context', securityRelevant: false }],
          },
        },
      });
      return;
    }
    if (path.endsWith('/model-calls')) {
      await route.fulfill({
        json: [
          {
            requestId: 'req-1',
            status: 'Succeeded',
            provider: 'openai',
            model: secondBackend ? 'stub-model-b' : 'stub-model',
            capturePolicy: 'MetadataOnly',
            fingerprintPrefix: 'call-prefix',
            inputTokens: 4,
            outputTokens: 2,
            requestedProfile: 'standard',
          },
        ],
      });
      return;
    }
    if (path.endsWith('/suspension')) {
      await route.fulfill({
        json: {
          runId: '11111111-1111-1111-1111-111111111111',
          record: {
            kind: 'timer',
            createdAtEpochMilli: 1_700_000_000_000,
            deadlineEpochMilli: 1_700_000_060_000,
            expiryOutcome: 'FailRun',
          },
        },
      });
      return;
    }
    await route.fulfill({ status: 404, json: { category: 'not-found', message: path } });
  });

  await page.goto('/?tab=inspect');
  await page.getByLabel('Run ID').fill('11111111-1111-1111-1111-111111111111');
  await expect(page.getByText('abcd1234abcd1234')).toBeVisible();
  await expect(page.getByText('requires-revalidation').first()).toBeVisible();
  await expect(page.getByText('Context/profileId')).toBeVisible();
  await expect(page.getByText('stub-model')).toBeVisible();
  await expect(page.getByText('timer', { exact: true })).toBeVisible();
  await expect(page.getByText('prompt', { exact: true })).toHaveCount(0);

  await page.setViewportSize({ width: 375, height: 812 });
  await expect(page.getByRole('combobox', { name: '控制台模块' })).toBeVisible();
  await expect(page.getByRole('button', { name: '连接设置', exact: true })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  await page.getByRole('button', { name: '连接设置', exact: true }).click();
  await page.getByLabel('后端服务地址 (Base URL)').fill('http://mock-b.agent');
  await page.getByRole('button', { name: '保存连接', exact: true }).click();
  await expect(page.getByRole('cell', { name: 'openai/stub-model-b', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'openai/stub-model', exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: '连接设置', exact: true }).click();
  await page.getByLabel('管理令牌 (Bearer Token)').fill('denied-token');
  await page.getByRole('button', { name: '保存连接', exact: true }).click();
  await expect.poll(() => forbiddenRequests).toBeGreaterThan(0);
  await expect(page.getByText('permission-revoked', { exact: false }).first()).toBeVisible();
  await expect(page.getByRole('cell', { name: 'openai/stub-model-b', exact: true })).toHaveCount(0);
});
