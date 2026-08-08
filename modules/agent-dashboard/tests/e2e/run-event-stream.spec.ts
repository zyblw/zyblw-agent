import { expect, test, type Page, type Route } from '@playwright/test';

const backend = 'http://mock.agent';
const runId = '11111111-2222-4333-8444-555555555555';

const usage = {
  modelCalls: 1,
  toolCalls: 0,
  inputTokens: 12,
  outputTokens: 4,
  totalTokens: 16,
  cachedInputTokens: 0,
  reasoningOutputTokens: 0,
  estimatedCost: '0.0001',
};

const createdEvent = {
  eventId: 'event-0',
  runId,
  sequence: 0,
  eventType: 'RunCreated',
  atEpochMilli: 1785974400000,
  status: 'Created',
};

const completedEvent = {
  eventId: 'event-1',
  runId,
  sequence: 1,
  eventType: 'RunCompleted',
  atEpochMilli: 1785974402000,
  status: 'Completed',
  usage,
};

/** 把事件序列化为 SSE frame；`id` 就是 sequence，与后端 `Last-Event-ID` 续传契约一致。 */
function eventFrame(event: { eventType: string; sequence: number }): string {
  return [`event: ${event.eventType}`, `data: ${JSON.stringify(event)}`, `id: ${event.sequence}`, ''].join(
    '\n',
  );
}

/** 服务端中途故障 frame；它没有 `id`，因此不会推进客户端游标。 */
function streamErrorFrame(message: string): string {
  return ['event: stream_error', `data: ${JSON.stringify({ category: 'persistence', message })}`, ''].join(
    '\n',
  );
}

/**
 * 装载管理台正常工作所需的最小后端。
 *
 * `onStream` 收到每一次事件流请求的序号（从 0 开始），返回该次连接的 SSE 正文，
 * 因此单个用例可以描述"先失败再续传"这类跨连接行为。
 */
async function installAdminRoutes(
  page: Page,
  streamHeaders: Record<string, string>[],
  onStream: (attempt: number) => string,
): Promise<void> {
  await page.addInitScript(
    ({ backendUrl }) => {
      window.localStorage.setItem('zyblw-agent-dashboard.base-url', backendUrl);
      window.sessionStorage.setItem('zyblw-agent-dashboard.token', 'stream-admin-token');
    },
    { backendUrl: backend },
  );

  await page.route(`${backend}/api/v1/admin/**`, async (route: Route) => {
    const request = route.request();
    expect(request.headers().authorization).toBe('Bearer stream-admin-token');
    const path = new URL(request.url()).pathname;

    if (path.endsWith('/capabilities')) {
      await route.fulfill({
        json: {
          apiVersion: 1,
          runDirectory: true,
          runEventStream: true,
          runtimeConfig: false,
          queueOps: false,
          knowledge: false,
          evalTrends: false,
          models: false,
          observability: {},
        },
      });
      return;
    }

    if (path.endsWith('/runs/overview')) {
      await route.fulfill({
        json: {
          capturedAtEpochMilli: 1785974402000,
          totalRuns: 1,
          countsByStatus: { Succeeded: 1 },
          awaitingApproval: 0,
        },
      });
      return;
    }

    if (path.endsWith('/runs') && request.method() === 'GET') {
      await route.fulfill({
        json: {
          items: [
            {
              runId,
              agentId: 'support-agent',
              sessionId: 'session-1',
              threadId: null,
              status: 'Succeeded',
              steps: 1,
              awaitingApproval: false,
              tenantId: 'acme',
              userId: 'user-1',
              usage,
              createdAtEpochMilli: 1785974400000,
              updatedAtEpochMilli: 1785974402000,
              stateVersion: 2,
              lastEventSequence: 1,
            },
          ],
          hasMore: false,
        },
      });
      return;
    }

    if (path.endsWith(`/runs/${runId}/events/stream`)) {
      const attempt = streamHeaders.length;
      streamHeaders.push(request.headers());
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache, no-transform', 'X-Accel-Buffering': 'no' },
        body: onStream(attempt),
      });
      return;
    }

    await route.fulfill({ status: 404, json: { category: 'not-found', message: path } });
  });
}

/** 打开 Run 列表并进入选中 Run 的调试器。 */
async function openDebugger(page: Page): Promise<void> {
  await page.goto('/?tab=runs');
  await page.getByText(runId.slice(0, 8)).click();
  await expect(page.getByRole('heading', { name: '实时事件流' })).toBeVisible();
}

test('Run 调试器以 Bearer fetch 读取低敏 SSE 并显示恢复序号', async ({ page }) => {
  const streamHeaders: Record<string, string>[] = [];

  await installAdminRoutes(page, streamHeaders, () =>
    `${eventFrame(createdEvent)}\n${eventFrame(completedEvent)}\n`,
  );

  await openDebugger(page);
  await page.getByRole('button', { name: '连接最近事件' }).click();

  await expect(page.getByText('RunCreated', { exact: true })).toBeVisible();
  await expect(page.getByText('RunCompleted', { exact: true })).toBeVisible();
  await expect(page.getByText('已追平', { exact: true })).toBeVisible();
  await expect(page.getByText('最后 #1', { exact: false })).toBeVisible();
  expect(streamHeaders).toHaveLength(1);
  expect(streamHeaders[0]['last-event-id']).toBe('-1');
  expect(streamHeaders[0].accept).toBe('text/event-stream');
});

/**
 * 服务端中途故障后必须从**最后确认的 sequence** 续传，而不是从头重放。
 *
 * 这条用例覆盖 `stream_error` 这一路径：它由 `onMessage` 抛出，此时响应体仍然打开，客户端必须显式中止本次
 * fetch 再重连，否则会在服务端留下一条持续轮询数据库的连接。断线后重放已看过的事件同样是缺陷——它会让
 * 连续性校验把重复序号判成跳变。
 */
test('事件流中途故障后按最后确认序号续传且不重放已收事件', async ({ page }) => {
  const streamHeaders: Record<string, string>[] = [];

  await installAdminRoutes(page, streamHeaders, (attempt) =>
    attempt === 0
      ? `${eventFrame(createdEvent)}\n${streamErrorFrame('事件页读取失败')}\n`
      : `${eventFrame(completedEvent)}\n`,
  );

  await openDebugger(page);
  await page.getByRole('button', { name: '连接最近事件' }).click();

  // 第一次连接只送达 sequence 0，随后以 stream_error 中断。
  await expect(page.getByText('RunCreated', { exact: true })).toBeVisible();
  await expect(page.getByText('正在重连', { exact: true })).toBeVisible();

  // 重连后补上 sequence 1，并且不会出现第二条 RunCreated。
  await expect(page.getByText('RunCompleted', { exact: true })).toBeVisible();
  await expect(page.getByText('已追平', { exact: true })).toBeVisible();
  await expect(page.getByText('最后 #1', { exact: false })).toBeVisible();
  await expect(page.getByText('RunCreated', { exact: true })).toHaveCount(1);

  expect(streamHeaders).toHaveLength(2);
  expect(streamHeaders[0]['last-event-id']).toBe('-1');
  expect(streamHeaders[1]['last-event-id']).toBe('0');
});
