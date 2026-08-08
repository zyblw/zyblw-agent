import { expect, test } from '@playwright/test';

const backend = 'http://mock.agent';

test('没有凭据时阻断全部管理请求并显示引导', async ({ page }) => {
  let adminRequests = 0;
  page.on('request', (request) => {
    if (request.url().startsWith(`${backend}/api/v1/admin/`)) adminRequests += 1;
  });

  await page.goto('/');

  await expect(page.getByRole('heading', { name: '需要管理凭据' })).toBeVisible();
  expect(adminRequests).toBe(0);
});

test('模型目录可键盘选择，探活失败给出可执行处置且不泄漏凭据', async ({ page }) => {
  const probeBodies: unknown[] = [];

  await page.addInitScript(({ backendUrl }) => {
    window.localStorage.setItem('zyblw-agent-dashboard.base-url', backendUrl);
    window.sessionStorage.setItem('zyblw-agent-dashboard.token', 'test-admin-token');
  }, { backendUrl: backend });

  await page.route(`${backend}/api/v1/admin/**`, async (route) => {
    const request = route.request();
    expect(request.headers().authorization).toBe('Bearer test-admin-token');
    const path = new URL(request.url()).pathname;

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
          models: true,
          observability: {},
        },
      });
      return;
    }

    if (path.endsWith('/models') && request.method() === 'GET') {
      await route.fulfill({
        json: {
          options: [
            {
              provider: 'openai',
              model: 'primary-model',
              displayName: 'OpenAI-compatible',
              protocol: 'chat-completions',
              capabilities: {
                toolCalls: true,
                parallelToolCalls: true,
                strictToolSchema: true,
                specificToolChoice: true,
                vision: false,
                thinking: false,
                streaming: true,
                usageReporting: true,
                maxInputTokens: 128000,
                maxOutputTokens: 8192,
              },
              isDefaultProvider: true,
              declaredModel: true,
              credential: { present: true, reference: 'env:OPENAI_API_KEY' },
              price: {
                inputPerMillionTokens: '1.25',
                outputPerMillionTokens: '5.00',
                currency: 'USD',
              },
            },
            {
              provider: 'openai',
              model: 'backup-model',
              displayName: 'OpenAI-compatible',
              protocol: 'chat-completions',
              capabilities: {
                toolCalls: true,
                parallelToolCalls: false,
                strictToolSchema: false,
                specificToolChoice: false,
                vision: false,
                thinking: false,
                streaming: true,
                usageReporting: true,
              },
              isDefaultProvider: true,
              declaredModel: true,
              credential: { present: false, reference: 'env:OPENAI_API_KEY' },
            },
          ],
          defaultProvider: 'openai',
          effectiveProvider: 'openai',
          effectiveModel: 'primary-model',
          embedding: {
            provider: 'openai',
            model: 'text-embedding-test',
            dimension: 3072,
            indexDimension: 1536,
            switchable: false,
            immutableReason: '索引维度已锁定；更换模型必须重建索引并全量重新摄入。',
          },
          priceCurrency: 'USD',
          pricedOptionCount: 1,
        },
      });
      return;
    }

    if (path.endsWith('/models/probe') && request.method() === 'POST') {
      probeBodies.push(request.postDataJSON());
      await route.fulfill({
        json: {
          provider: 'openai',
          model: 'backup-model',
          succeeded: false,
          latencyMillis: 18,
          inputTokens: 0,
          outputTokens: 0,
          failureCode: 'unauthorized',
        },
      });
      return;
    }

    await route.fulfill({ status: 404, json: { category: 'not-found', message: path } });
  });

  await page.goto('/?tab=models');

  await expect(page.getByRole('heading', { name: '模型目录' })).toBeVisible();
  await expect(page.getByText('env:OPENAI_API_KEY').first()).toBeVisible();
  await expect(page.getByText('模型维度与索引维度不一致')).toBeVisible();
  await expect(page.getByText(/sk-|test-admin-token/)).toHaveCount(0);

  const backup = page.getByRole('button', { name: 'backup-model' });
  await backup.focus();
  await backup.press('Enter');
  await expect(page).toHaveURL(/modelProvider=openai/);
  await expect(page).toHaveURL(/modelName=backup-model/);

  await page.getByRole('button', { name: '执行探活（产生真实费用）' }).click();

  await expect(page.getByText('Provider 拒绝了凭据；检查页面显示的凭据引用是否已注入并仍然有效。').first())
    .toBeVisible();
  expect(probeBodies).toEqual([{ provider: 'openai', model: 'backup-model' }]);
});
