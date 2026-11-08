import { expect, test, type Page } from '@playwright/test';

const USER = process.env.PAYGUARD_E2E_USER ?? 'admin';
const PASSWORD = process.env.PAYGUARD_E2E_PASSWORD ?? '';
const SHOTS = 'e2e-screens';

async function login(page: Page) {
  await page.goto('/');
  await page.getByLabel('Username').fill(USER);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
}

test('wrong password is rejected', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Password').fill('definitely-wrong');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toHaveText('Wrong username or password.');
});

test('failure lab runs a real scenario and shows the recorded recovery timeline', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Failure lab' }).first().click();
  await page.getByRole('button', { name: 'Missing webhook' }).click();
  await page.getByRole('button', { name: 'Execute' }).click();
  const timeline = page.locator('section', { hasText: 'Event flow & recovery timeline' });
  await expect(timeline.getByText('PASSED').first()).toBeVisible({ timeout: 90_000 });
  await expect(timeline.getByText('4 · Recovery mechanism')).toBeVisible();
  await expect(timeline.getByText('Invariants checked against the database')).toBeVisible();
  await expect(timeline.getByLabel('violated')).toHaveCount(0);
  await page.screenshot({ path: `${SHOTS}/lab.png`, fullPage: true });
});

test('dashboard shows database-backed numbers', async ({ page }) => {
  await login(page);
  const payments = page.locator('div', { has: page.getByText('Payments', { exact: true }) }).locator('div.text-2xl').first();
  await expect(payments).not.toHaveText('0');
  await expect(page.getByText('Recovered')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/dashboard.png`, fullPage: true });
});

test('payment explorer compares merchant and gateway and shows the lifecycle', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Payments' }).first().click();
  await page.locator('tbody tr').first().click();
  await expect(page.getByText('Gateway vs merchant')).toBeVisible();
  await expect(page.getByText('Lifecycle timeline')).toBeVisible();
  await expect(page.getByText('Consistent.')).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: `${SHOTS}/payments.png`, fullPage: true });
});

test('reconciliation can be started and lists its run', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Reconciliation' }).first().click();
  await page.getByRole('button', { name: 'Start reconciliation' }).click();
  await expect(page.getByText(/^#\d+/).first()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: 'Export CSV' })).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/reconciliation.png`, fullPage: true });
});

test('architecture explorer explains components and dark mode toggles', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Architecture' }).first().click();
  await page.getByRole('button', { name: 'Outbox publisher' }).click();
  await expect(page.getByText(/FOR UPDATE SKIP LOCKED/)).toBeVisible();
  const before = await page.evaluate(() => document.documentElement.classList.contains('dark'));
  await page.getByRole('button', { name: 'Toggle dark mode' }).click();
  expect(await page.evaluate(() => document.documentElement.classList.contains('dark'))).toBe(!before);
  await page.screenshot({ path: `${SHOTS}/architecture.png`, fullPage: true });
});
