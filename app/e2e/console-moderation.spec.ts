import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test, type APIRequestContext } from '@playwright/test';

import { signInAsAdmin } from './helpers';

/**
 * A fan reports a clip with a reason; the admin hides it from the console's Moderation page,
 * through the confirmation; the clip is gone from that fan's feeds (checked through the API); the admin
 * restores it from the Resolved tab and it is back.
 *
 * Changes shared state (a hidden post), so `afterAll` restores the post if the test stopped half-way and
 * deletes the accounts it made. Run it against your own stack (`E2E_BASE_URL`), never someone else's.
 */
const CLIP = resolve(__dirname, '../../fixtures/clips/clip-18-square-480p-8s.mp4');
const HEADERS = { 'X-Client': 'android' };

type Account = { id: string; handle: string; auth: { Authorization: string } };

let admin: { Authorization: string } | undefined;
let postId: string | undefined;
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

/** One published clip by the author: uploaded, completed and transcoded by the worker. */
async function publishClip(request: APIRequestContext, author: Account): Promise<string> {
  const bytes = readFileSync(CLIP);
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
  await request.post(`/api/v1/media/uploads/${ticket.mediaId}/complete`, { headers: author.auth });
  const post = await request.post('/api/v1/posts', {
    headers: author.auth,
    data: { clips: [{ mediaId: ticket.mediaId }], caption: `Moderation clip ${Date.now()}` },
  });
  expect(post.status()).toBe(201);
  const id = (await post.json()).id as string;
  await expect
    .poll(
      async () =>
        (await (await request.get(`/api/v1/posts/${id}`, { headers: author.auth })).json()).status,
      { timeout: 180_000, intervals: [2_000] },
    )
    .toBe('PUBLISHED');
  return id;
}

async function followingIds(request: APIRequestContext, viewer: Account): Promise<string[]> {
  const response = await request.get('/api/v1/feed/following?limit=50', { headers: viewer.auth });
  expect(response.ok()).toBe(true);
  return ((await response.json()).items as { id: string }[]).map((p) => p.id);
}

test.afterAll(async ({ request }) => {
  if (postId) {
    const auth = await adminAuth(request);
    // A run that stopped after the hide leaves the post hidden: put it back (409 when it isn't).
    await request.post(`/api/v1/admin/posts/${postId}/restore`, { headers: auth });
  }
  for (const account of made) {
    await request.delete('/api/v1/me', {
      headers: account.auth,
      data: { password: 'correct horse battery' },
    });
  }
});

test('a reported clip is hidden from the console, gone from feeds, and restored', async ({
  page,
  request,
}) => {
  expect(existsSync(CLIP), 'synthetic clips missing: run `make clips` first').toBe(true);
  test.setTimeout(300_000);
  const author = await register(request, 'mod_author');
  const fan = await register(request, 'mod_fan');
  postId = await publishClip(request, author);
  expect((await request.put(`/api/v1/users/${author.id}/follow`, { headers: fan.auth })).ok()).toBe(
    true,
  );
  expect(await followingIds(request, fan)).toContain(postId);

  // The fan reports it with a reason.
  const reported = await request.post(`/api/v1/posts/${postId}/report`, {
    headers: fan.auth,
    data: { reason: 'VIOLENCE', details: 'A fight in the crowd' },
  });
  expect(reported.status()).toBe(204);

  // The admin hides it from the Moderation page, through the confirmation.
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/console/moderation');
  await signInAsAdmin(page);
  await expect(page).not.toHaveURL(/\/sign-in/);
  if (!/\/console\/moderation/.test(page.url())) await page.goto('/console/moderation');
  const card = page.getByTestId(`report-card-${postId}`);
  // Other reports may be queued before this one: load pages until the card shows.
  for (let i = 0; i < 20 && !(await card.isVisible()); i++) {
    const more = page.getByTestId('moderation-more');
    if (!(await more.isVisible())) break;
    await more.click();
  }
  await expect(card).toBeVisible();
  await expect(card.getByText('Violence')).toBeVisible();
  await expect(card.getByText('“A fight in the crowd”')).toBeVisible();
  await card.getByTestId('report-hide_post').click();
  await expect(
    page.getByText('• The post disappears from every feed and list, for everyone but its author'),
  ).toBeVisible();
  await page.getByTestId('confirm-ok').click();
  await expect(page.getByTestId('confirm-ok')).toHaveCount(0);
  await expect(card).toHaveCount(0);

  // Gone from the fan's feeds and from the post's own address; its author still sees it, marked.
  expect(await followingIds(request, fan)).not.toContain(postId);
  expect((await request.get(`/api/v1/posts/${postId}`, { headers: fan.auth })).status()).toBe(404);
  expect(
    (await (await request.get(`/api/v1/posts/${postId}`, { headers: author.auth })).json()).status,
  ).toBe('HIDDEN');
  const home = await (
    await request.get('/api/v1/feed/home?limit=50', { headers: fan.auth })
  ).json();
  expect((home.items as { post: { id: string } }[]).map((i) => i.post.id)).not.toContain(postId);

  // Restore from the Resolved tab.
  await page.getByTestId('moderation-tab-resolved').click();
  const resolved = page.getByTestId(`report-card-${postId}`);
  await expect(resolved).toBeVisible();
  await expect(resolved.getByTestId('report-resolution')).toContainText('now HIDDEN');
  await resolved.getByTestId('report-restore').click();
  await page.getByTestId('confirm-ok').click();
  await expect(page.getByTestId('confirm-ok')).toHaveCount(0);
  await expect(resolved.getByTestId('report-resolution')).toContainText('now PUBLISHED');

  expect((await request.get(`/api/v1/posts/${postId}`, { headers: fan.auth })).status()).toBe(200);
  expect(await followingIds(request, fan)).toContain(postId);
});
