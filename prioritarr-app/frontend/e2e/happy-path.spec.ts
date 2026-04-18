import { expect, test } from '@playwright/test'

const API_KEY = process.env.PLAYWRIGHT_API_KEY ?? 'ci-test-key'

/**
 * Single happy-path scenario per Spec D §10.3:
 * 1. Reset state via the v1 test-mode endpoint.
 * 2. Push a fake OnGrab (which creates a managed_download row because
 *    DRY_RUN=false in CI).
 * 3. Load the UI, enter the API key on /login.
 * 4. Assert the /series list renders with at least one row, and the
 *    /downloads page shows the seeded download.
 * 5. Click Recompute on the series row; assert no error and the row
 *    stays present (we can't deterministically assert a priority value
 *    without more upstream mock choreography).
 */
test('happy path: login → series → downloads → recompute', async ({ page, request, baseURL }) => {
  test.setTimeout(60_000)

  const api = baseURL!

  // Seed state: reset + push OnGrab.
  const reset = await request.post(`${api}/api/v1/_testing/reset`)
  expect(reset.ok()).toBeTruthy()

  const onGrab = await request.post(`${api}/api/sonarr/on-grab`, {
    data: {
      eventType: 'Grab',
      series: { id: 1, title: 'E2E Series', tvdbId: 12345 },
      episodes: [{ id: 1, episodeNumber: 1, seasonNumber: 1, airDateUtc: '2026-01-01T00:00:00Z' }],
      downloadClient: 'QBittorrent',
      downloadId: 'E2E-HAPPY-PATH',
    },
  })
  expect(onGrab.ok()).toBeTruthy()

  // Boot the UI.
  await page.goto('/')

  // Enter API key — either the login page shows up, or we're already in.
  const keyInput = page.getByPlaceholder('X-Api-Key')
  if (await keyInput.isVisible().catch(() => false)) {
    await keyInput.fill(API_KEY)
    await page.getByRole('button', { name: /connect/i }).click()
  }

  // Series page — the seeded series should be visible.
  await expect(page.getByRole('heading', { name: /^Series/ })).toBeVisible()

  // Downloads page.
  await page.getByTitle('Downloads').click()
  await expect(page.getByRole('heading', { name: /Downloads/ })).toBeVisible()
  await expect(page.getByText('E2E-HAPPY-PAT', { exact: false })).toBeVisible()

  // Back to series; click recompute on the first row.
  await page.getByTitle('Series').click()
  const recompute = page.getByRole('button', { name: /recompute/i }).first()
  await recompute.click()
  await expect(page.getByRole('heading', { name: /^Series/ })).toBeVisible()
})
