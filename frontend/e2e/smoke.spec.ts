import { test, expect } from '@playwright/test'

test('login page renders', async ({ page }) => {
  await page.goto('/login')
  await expect(page).toHaveTitle('AI 财务系统')
  await expect(page.getByRole('heading', { name: /AI 财务系统/ })).toBeVisible()
})
