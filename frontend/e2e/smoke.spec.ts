import { test, expect } from '@playwright/test'

test('login page renders', async ({ page }) => {
  await page.goto('/login')
  await expect(page).toHaveTitle('AI 财务系统')
  await expect(page.getByRole('heading', { name: /AI 财务系统/ })).toBeVisible()
})

test('local admin login reaches the ledger workspace', async ({ page }) => {
  const compatibilityWarnings: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'warning' && message.text().includes('[antd: compatible]')) {
      compatibilityWarnings.push(message.text())
    }
  })

  await page.goto('/login')
  await page.getByLabel('用户名').fill('admin')
  const meResponse = page.waitForResponse((response) => response.url().endsWith('/v1/me'))
  await page.getByRole('button', { name: '进入本地工作台' }).click()

  expect((await meResponse).status()).toBe(200)
  await expect(page).toHaveURL(/\/ledgers$/)
  await expect(page.getByText('admin', { exact: true })).toBeVisible()
  expect(compatibilityWarnings).toEqual([])
})
