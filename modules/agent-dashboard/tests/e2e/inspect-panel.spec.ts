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

  await page.route(`${backend}/api/v1/admin/**`, async (route) => {
    const path = new URL(route.request().url()).pathname;
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
            model: 'stub-model',
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
  await expect(page.getByText('requires-revalidation')).toBeVisible();
  await expect(page.getByText('Context/profileId')).toBeVisible();
  await expect(page.getByText('stub-model')).toBeVisible();
  await expect(page.getByText('timer')).toBeVisible();
  await expect(page.getByText('prompt', { exact: true })).toHaveCount(0);
});
