import { defineConfig, devices } from '@playwright/test'
import { existsSync } from 'node:fs'

const systemChrome = process.env.PLAYWRIGHT_CHROME_PATH || 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'

export default defineConfig({
  testDir: './e2e',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    launchOptions: existsSync(systemChrome) ? { executablePath: systemChrome } : undefined,
  },
  webServer: {
    command: 'pnpm dev --host 127.0.0.1',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
