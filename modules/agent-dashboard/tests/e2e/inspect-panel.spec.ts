import { expect, test } from '@playwright/test';

const backend = 'http://mock.agent';

test('检查面板只渲染低敏组合指纹和账本字段', async ({ page }) => {
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
          fingerprintPrefix: 'abcd1234abcd1234',
          profileId: 'default',
          modelRefPrefix: 'model-ref',
          capturePolicy: 'MetadataOnly',
          sourceIds: ['memory-rag@2'],
          environmentId: 'local',
          permissionFingerprintPrefix: 'perm-prefix',
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
          },
        ],
      });
      return;
    }
    if (path.endsWith('/approval')) {
      await route.fulfill({ status: 404, json: { category: 'not-found', message: 'none' } });
      return;
    }
    await route.fulfill({ status: 404, json: { category: 'not-found', message: path } });
  });

  await page.goto('/?tab=inspect');
  await page.getByLabel('Run ID').fill('11111111-1111-1111-1111-111111111111');
  await expect(page.getByText('abcd1234abcd1234')).toBeVisible();
  await expect(page.getByText('stub-model')).toBeVisible();
  await expect(page.getByText('prompt')).toHaveCount(0);
});
