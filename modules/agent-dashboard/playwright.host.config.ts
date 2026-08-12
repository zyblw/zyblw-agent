import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests/host',
  reporter: 'line',
  use: {
    baseURL: 'http://127.0.0.1:3101',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command:
      'NEXT_PUBLIC_AGENT_BASE_URL=/api/backend NEXT_PUBLIC_AGENT_AUTH_MODE=host-session NEXT_PUBLIC_AGENT_BASE_PATH=/admin/agent npm run dev -- --hostname 127.0.0.1 --port 3101',
    url: 'http://127.0.0.1:3101/admin/agent',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
