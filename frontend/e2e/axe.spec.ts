import AxeBuilder from '@axe-core/playwright'
import { test, expect } from '@playwright/test'

test('login page has no critical accessibility violations', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading').first()).toBeVisible()
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations, results.violations.map((violation) => `${violation.id}: ${violation.help}`).join('\n')).toEqual([])
})
