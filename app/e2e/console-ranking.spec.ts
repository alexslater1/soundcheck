import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test, type APIRequestContext } from '@playwright/test';

import { signInAsAdmin } from './helpers';

/**
 * The admin opens the ranking playground, picks a user, sets `recency` to
 * 0, sees the preview reorder (τ < 1), saves the draft and activates it through the confirm, which
 * lists the change; that user's next feed session is built with it (checked through the API).
 *
 * Changes shared state (the active config), so `afterAll` re-activates the config that was active
 * before, and deletes the accounts it made. Run it against your own stack.
 */
const CLIP = resolve(__dirname, '../../fixtures/clips/clip-18-square-480p-8s.mp4');
const POSTS = 4;
const HEADERS = { 'X-Client': 'android' };

type Account = { id: string; handle: string; auth: { Authorization: string } };

let admin: { Authorization: string } | undefined;
let previouslyActive: string | undefined;
const made: Account[] = [];

async function register(request: APIRequestContext, prefix: string): Promise<Account> {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 100000)}`;
  const handle = `${prefix}_${suffix}`.slice(0, 30);
  const response = await request.post('/api/v1/auth/register', {
    headers: HEADERS,
    data: {
      email: `${prefix}-${suffix}@example.com`,
      password: 'correct horse battery',
      handle,
      displayName: prefix,
    },
  });
  expect(response.status()).toBe(201);
  const body = await response.json();
  const account = {
    id: body.user.id as string,
    handle,
    auth: { ...HEADERS, Authorization: `Bearer ${body.accessToken}` },
  };
  made.push(account);
  return account;
}

async function adminAuth(request: APIRequestContext): Promise<{ Authorization: string }> {
  if (!admin) {
    const response = await request.post('/api/v1/auth/login', {
      headers: HEADERS,
      data: {
        email: process.env.SOUNDCHECK_ADMIN_EMAIL,
        password: process.env.SOUNDCHECK_ADMIN_PASSWORD,
      },
    });
    expect(response.status(), 'set SOUNDCHECK_ADMIN_EMAIL and _PASSWORD').toBe(200);
    admin = { ...HEADERS, Authorization: `Bearer ${(await response.json()).accessToken}` };
  }
  return admin;
}

/** An author with a few published clips, followed by the viewer, so the viewer has candidates. */
async function seedFeed(request: APIRequestContext, viewer: Account): Promise<void> {
  const author = await register(request, 'pg_author');
  const bytes = readFileSync(CLIP);
  const postIds: string[] = [];
  for (let i = 0; i < POSTS; i++) {
    const ticket = await (
      await request.post('/api/v1/media/uploads', {
        headers: author.auth,
        data: { kind: 'VIDEO', contentType: 'video/mp4', bytes: bytes.length },
      })
    ).json();
    expect(
      (
        await request.put(ticket.putUrl, { headers: { 'Content-Type': 'video/mp4' }, data: bytes })
      ).ok(),
    ).toBe(true);
    await request.post(`/api/v1/media/uploads/${ticket.mediaId}/complete`, {
      headers: author.auth,
    });
    const post = await request.post('/api/v1/posts', {
      headers: author.auth,
      data: { clips: [{ mediaId: ticket.mediaId }], caption: `Playground clip ${i + 1}` },
    });
    expect(post.status()).toBe(201);
    postIds.push((await post.json()).id);
  }
  await expect
    .poll(
      async () => {
        const states = await Promise.all(
          postIds.map(
            async (id) =>
              (await (await request.get(`/api/v1/posts/${id}`, { headers: author.auth })).json())
                .status,
          ),
        );
        return states.filter((s) => s === 'PUBLISHED').length;
      },
      { timeout: 180_000, intervals: [2_000] },
    )
    .toBe(POSTS);
  expect(
    (await request.put(`/api/v1/users/${author.id}/follow`, { headers: viewer.auth })).ok(),
  ).toBe(true);
}

test.afterAll(async ({ request }) => {
  if (previouslyActive) {
    const auth = await adminAuth(request);
    const configs = await (
      await request.get('/api/v1/admin/ranking/configs', { headers: auth })
    ).json();
    const current = (configs as { id: string; status: string }[]).find(
      (c) => c.status === 'ACTIVE',
    );
    if (current?.id !== previouslyActive) {
      const restored = await request.post(
        `/api/v1/admin/ranking/configs/${previouslyActive}/activate`,
        { headers: auth },
      );
      expect(restored.ok()).toBe(true);
    }
  }
  for (const account of made) {
    await request.delete('/api/v1/me', {
      headers: account.auth,
      data: { password: 'correct horse battery' },
    });
  }
});

test('the playground previews a weight change, saves it and activates it for the next session', async ({
  page,
  request,
}) => {
  expect(existsSync(CLIP), 'synthetic clips missing: run `make clips` first').toBe(true);
  test.setTimeout(360_000);
  const auth = await adminAuth(request);
  const before = (await (
    await request.get('/api/v1/admin/ranking/configs', { headers: auth })
  ).json()) as { id: string; version: number; status: string; weights: Record<string, number> }[];
  const active = before.find((c) => c.status === 'ACTIVE')!;
  previouslyActive = active.id;
  expect(active.weights.recency, 'the active config must weigh recency').not.toBe(0);

  const viewer = await register(request, 'pg_viewer');
  await seedFeed(request, viewer);

  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/console/ranking');
  await signInAsAdmin(page);
  // Signing in should come back to the page; on some machines the sign-in screen sometimes loses
  // its `next` and lands on onboarding, so go to the page if it didn't.
  await expect(page).not.toHaveURL(/\/sign-in/);
  if (!/\/console\/ranking/.test(page.url())) await page.goto('/console/ranking');
  await expect(page.getByTestId('config-list')).toBeVisible();

  // Pick the user.
  await page.getByTestId('user-picker-search').fill(viewer.handle);
  await page.getByTestId(`user-picker-option-${viewer.handle}`).click();
  await expect(page.getByTestId('user-picker-selected')).toHaveText(`@${viewer.handle}`);
  await expect(page.getByTestId('preview-a-item-1')).toBeVisible();
  await expect(page.getByTestId('stat-tau')).toHaveText('1.000');

  // recency → 0: the draft column reorders.
  const recency = page.getByTestId('slider-recency-input');
  await recency.fill('0');
  await recency.press('Tab');
  await expect
    .poll(async () => Number(await page.getByTestId('stat-tau').textContent()), {
      timeout: 15_000,
    })
    .toBeLessThan(1);
  await expect(page.getByTestId('preview-updating')).toHaveCount(0);

  // Save as draft, then activate it: the confirm lists the change first.
  await page.getByTestId('ranking-save').click();
  await expect(page.getByTestId('ranking-activate')).toBeEnabled();
  await page.getByTestId('ranking-activate').click();
  await expect(page.getByText(`• weight recency: ${active.weights.recency} → 0`)).toBeVisible();
  await expect(
    page.getByText('• New feed sessions use it; sessions already open keep theirs'),
  ).toBeVisible();
  await page.getByTestId('confirm-ok').click();
  await expect(page.getByTestId('confirm-ok')).toHaveCount(0);

  const after = (await (
    await request.get('/api/v1/admin/ranking/configs', { headers: auth })
  ).json()) as { id: string; version: number; status: string; weights: Record<string, number> }[];
  const nowActive = after.find((c) => c.status === 'ACTIVE')!;
  expect(nowActive.id).not.toBe(active.id);
  expect(nowActive.weights.recency).toBe(0);

  // The user's next session is built with it.
  const home = await request.get('/api/v1/feed/home?limit=5', { headers: viewer.auth });
  expect(home.ok()).toBe(true);
  const sessionId = (await home.json()).sessionId as string;
  const served = await (
    await request.get(`/api/v1/admin/feed/users/${viewer.id}/served?limit=1`, { headers: auth })
  ).json();
  expect(served.items[0].sessionId).toBe(sessionId);
  expect(served.items[0].configVersion).toBe(nowActive.version);
});
