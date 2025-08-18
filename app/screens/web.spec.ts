import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

import { chooseVideo } from '../e2e/helpers';

const OUT = resolve(__dirname, '../../docs/screens/web');
// The clips live in the main checkout (gitignored); a worktree points here with CLIPS_DIR.
const CLIPS = process.env.CLIPS_DIR ?? resolve(__dirname, '../../fixtures/clips');
const PASSWORD = 'correct horse battery';

type Account = { id: string; email: string; handle: string; auth: Record<string, string> };

async function register(
  request: APIRequestContext,
  prefix: string,
  displayName: string,
): Promise<Account> {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  const handle = `${prefix}_${suffix}`.slice(0, 30);
  const email = `${prefix}-${suffix}@example.com`;
  const registered = await request.post('/api/v1/auth/register', {
    headers: { 'X-Client': 'android' },
    data: { email, password: PASSWORD, handle, displayName },
  });
  expect(registered.status()).toBe(201);
  const body = await registered.json();
  const auth = { Authorization: `Bearer ${body.accessToken}` };
  const city = (await (await request.get('/api/v1/cities?q=Manch')).json())[0];
  await request.patch('/api/v1/me', { headers: auth, data: { homeCityId: city.id } });
  await request.put('/api/v1/me/genres', {
    headers: auth,
    data: { genres: [{ genreId: 2, weight: 1 }] },
  });
  await request.post('/api/v1/me/onboarding/complete', { headers: auth });
  return { id: body.user.id, email, handle, auth };
}

/** Uploads synthetic clips through the API and posts them as one post. */
async function post(request: APIRequestContext, author: Account, files: string[], caption: string) {
  const clips = [];
  for (const file of files) {
    const bytes = readFileSync(resolve(CLIPS, file));
    const ticket = await (
      await request.post('/api/v1/media/uploads', {
        headers: author.auth,
        data: { kind: 'VIDEO', contentType: 'video/mp4', bytes: bytes.length },
      })
    ).json();
    const put = await request.put(ticket.putUrl, {
      headers: { 'Content-Type': 'video/mp4' },
      data: bytes,
    });
    expect(put.ok()).toBe(true);
    await request.post(`/api/v1/media/uploads/${ticket.mediaId}/complete`, {
      headers: author.auth,
    });
    clips.push({ mediaId: ticket.mediaId });
  }
  const created = await request.post('/api/v1/posts', {
    headers: author.auth,
    data: { clips, caption },
  });
  expect(created.status()).toBe(201);
  return (await created.json()).id as string;
}

async function signIn(page: Page, account: Account) {
  await page.goto('/');
  await page.getByLabel('Email').last().fill(account.email);
  await page.getByLabel('Password').last().fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).last().click();
  await expect(page.getByRole('radio', { name: 'For You' }).last()).toBeVisible();
}

const shot = (page: Page, name: string) => page.screenshot({ path: resolve(OUT, name) });

test('web screenshots', async ({ browser, request }) => {
  const maya = await register(request, 'shots_maya', 'Maya Lane');
  const sam = await register(request, 'shots_sam', 'Sam Okoro');
  try {
    // Maya posts synthetic clips: a two-clip post (the carousel) and a single landscape clip.
    const ids = [
      await post(request, maya, ['clip-01-landscape-720p-10s.mp4'], 'Soundcheck from the side'),
      await post(
        request,
        maya,
        ['clip-02-portrait-720p-12s.mp4', 'clip-09-square-720p-11s.mp4'],
        'Two from the front row',
      ),
    ];
    await expect
      .poll(
        async () => {
          const states = await Promise.all(
            ids.map(
              async (id) =>
                (await (await request.get(`/api/v1/posts/${id}`, { headers: maya.auth })).json())
                  .status,
            ),
          );
          return states.every((status) => status === 'PUBLISHED');
        },
        { timeout: 180_000, intervals: [2_000] },
      )
      .toBe(true);
    await request.put(`/api/v1/users/${maya.id}/follow`, { headers: sam.auth });

    const context = await browser.newContext();
    const page = await context.newPage();
    await signIn(page, sam);

    // Feed: Following shows Maya's newest post full width, the scrim under the overlay.
    await page.getByRole('radio', { name: 'Following' }).last().click();
    await expect(page.getByText('Two from the front row').last()).toBeVisible();
    await page.waitForTimeout(2_500); // the clip starts and the poster fades
    await shot(page, '10-feed-following.png');

    // Gigs: near home, distances, statuses.
    await page.getByRole('tab', { name: /Gigs$/ }).click();
    await expect(page.getByTestId('gig-list')).toBeVisible();
    const notNow = page.getByRole('button', { name: 'Not now' });
    if (await notNow.isVisible()) {
      await notNow.click();
    }
    await page.waitForTimeout(1_500);
    await shot(page, '11-gigs-near-home.png');
    // Further down: this month's gigs with their distance and price.
    await page.getByTestId('gig-card').first().scrollIntoViewIfNeeded();
    await page.getByTestId('gig-list').evaluate((el) => el.scrollBy(0, 200));
    await page.waitForTimeout(800);
    // The artists' photos come from Ticketmaster and stay out of the repo: masked.
    await page.screenshot({
      path: resolve(OUT, '12-gigs-cards.png'),
      // The three cards fully on screen; a mask of the half-hidden fourth would cover the tab bar.
      mask: [0, 1, 2].map((i) => page.getByTestId('gig-card').nth(i).locator('img')),
      clip: { x: 0, y: 0, width: 412, height: 760 },
      maskColor: '#3B2A63',
    });

    // Maya's profile: the grid.
    await page.goto(`/u/${maya.handle}`);
    await expect(page.getByText('Maya Lane').first()).toBeVisible();
    await page.waitForTimeout(1_500);
    await shot(page, '13-profile-grid.png');
    await context.close();

    // Create: Maya picks a clip; its row shows the stage it is at.
    const own = await browser.newContext();
    const create = await own.newPage();
    await signIn(create, maya);
    await create.getByRole('tab', { name: /Create$/ }).click();
    await chooseVideo(create, resolve(CLIPS, 'clip-07-portrait-480p-14s.mp4'));
    await expect(
      create.locator('[data-testid^="upload-"][data-testid$="-state"]').first(),
    ).toHaveText(/Uploading|Processing/);
    await shot(create, '14-create-progress.png');
    // Posted while it uploads: the own grid shows the post as Processing… until the worker is done.
    await create.getByTestId('post-button').last().click();
    await create.getByRole('tab', { name: /Me$/ }).click();
    await expect(create.getByText('Processing…').first()).toBeVisible();
    await create.waitForTimeout(1_000);
    await shot(create, '15-me-processing.png');
    await own.close();
  } finally {
    for (const account of [maya, sam]) {
      await request.delete('/api/v1/me', { headers: account.auth, data: { password: PASSWORD } });
    }
  }
});
