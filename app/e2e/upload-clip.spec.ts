import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import { chooseVideo as choose, deleteAccount, signUpAndOnboard } from './helpers';

/**
 * The web upload flow, through the edge against the real stack (API with the worker role,
 * LocalStack, FFmpeg): choose a clip on the Create tab → it uploads straight to storage → the
 * worker transcodes it → the row turns Ready → Watch plays it through hls.js. Then the same with a
 * clip over 16 MB, which must go up in parts. The clips are the synthetic ones from `make clips`.
 */
const CLIPS = resolve(__dirname, '../../fixtures/clips');
const SMALL = resolve(CLIPS, 'clip-02-portrait-720p-12s.mp4'); // 4.3 MB → one PUT
const LARGE = resolve(CLIPS, 'clip-07-portrait-480p-14s.mp4'); // 21.6 MB → three parts of 8 MiB

test('upload a clip on web, watch it when it is ready, then upload a large one in parts', async ({
  page,
}) => {
  // Not test.skip: a journey that did not run must not look like one that passed.
  expect(existsSync(SMALL), 'synthetic clips missing: run `make clips` first').toBe(true);
  test.setTimeout(240_000);
  // PUTs to storage, by kind: a whole file, or one part of a multipart upload.
  const puts: string[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (request.method() === 'PUT' && !url.pathname.startsWith('/api/')) {
      puts.push(url.searchParams.has('partNumber') ? 'part' : 'whole');
    }
  });

  // An upload's state as its row in the list says it (the preview strip above repeats it).
  const rowsSaying = (state: string) =>
    page.locator('[data-testid^="upload-"][data-testid$="-state"]', {
      hasText: new RegExp(`^${state}$`),
    });

  const user = await signUpAndOnboard(page);
  await page.getByRole('tab', { name: /Create$/ }).click();
  await expect(page.getByTestId('uploads-empty')).toBeVisible();

  // A small clip: one PUT, then the worker makes it Ready.
  await choose(page, SMALL);
  await expect(page.getByText('clip-02-portrait-720p-12s.mp4', { exact: true })).toBeVisible();
  await expect(rowsSaying('Ready')).toBeVisible({ timeout: 90_000 });
  expect(puts).toEqual(['whole']);

  // Watch: the video element is playing an HLS stream fed by hls.js, the poster is gone.
  await page.getByRole('link', { name: 'Watch' }).click();
  const video = page.getByTestId('clip-player-video');
  await expect
    .poll(async () => video.evaluate((v: HTMLVideoElement) => v.currentTime), { timeout: 30_000 })
    .toBeGreaterThan(1);
  expect(await video.evaluate((v: HTMLVideoElement) => v.currentSrc.startsWith('blob:'))).toBe(
    true,
  );
  expect(await video.evaluate((v: HTMLVideoElement) => v.videoHeight > v.videoWidth)).toBe(true);
  await expect(page.getByTestId('clip-player-poster')).toHaveCount(0);
  // Clips start muted; one tap turns the sound on.
  expect(await video.evaluate((v: HTMLVideoElement) => v.muted)).toBe(true);
  await page.getByRole('button', { name: 'Turn sound on' }).click();
  await expect(page.getByRole('button', { name: 'Turn sound off' })).toBeVisible();
  expect(await video.evaluate((v: HTMLVideoElement) => v.muted)).toBe(false);

  // A clip over 16 MB goes up in parts and becomes Ready too. (A full page load: the list comes back
  // from the persisted queue.)
  await page.goto('/create');
  await expect(rowsSaying('Ready')).toHaveCount(1);
  await choose(page, LARGE);
  await expect(page.getByText(/uploaded in parts/)).toBeVisible({ timeout: 60_000 });
  await expect(rowsSaying('Ready')).toHaveCount(2, { timeout: 120_000 });
  expect(puts).toEqual(['whole', 'part', 'part', 'part']);

  // Clean up: delete the account.
  await deleteAccount(page, user);
});
