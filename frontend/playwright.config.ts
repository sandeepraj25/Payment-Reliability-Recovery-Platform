import { defineConfig } from '@playwright/test';

// E2E tests run against a REAL backend (PostgreSQL + RabbitMQ + Spring Boot) - see the e2e job in CI.
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  retries: 0,
  workers: 1,
  use: {
    baseURL: process.env.PAYGUARD_E2E_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1440, height: 900 },
  },
});
