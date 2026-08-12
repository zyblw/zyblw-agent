import { expect, test } from '@playwright/test';

test('宿主会话不读取 Bearer token，并通过同源 BFF 携带 CSRF 标记', async ({ page }) => {
  let capabilitiesRequests = 0;

  await page.route('**/api/backend/api/v1/admin/**', async (route) => {
    const request = route.request();
    expect(request.headers().authorization).toBeUndefined();
    expect(request.headers()['x-zyblw-csrf']).toBe('1');
    expect(new URL(request.url()).origin).toBe('http://127.0.0.1:3101');
    if (request.url().endsWith('/api/v1/admin/capabilities')) capabilitiesRequests += 1;
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
        observability: {},
      },
    });
  });

  await page.goto('/admin/agent');

  await expect(page.getByText('站点账号 · 管理授权')).toBeVisible();
  await expect(page.getByText(/不存在单独的 Agent 用户名或密码/)).toBeVisible();
  await expect(page.getByRole('heading', { name: '需要管理凭据' })).toHaveCount(0);
  await expect.poll(() => capabilitiesRequests).toBe(1);
});
