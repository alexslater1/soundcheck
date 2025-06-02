import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test, type APIRequestContext, type Locator } from '@playwright/test';

import { deleteAccount, signUpAndOnboard } from './helpers';

/**
 * Through the edge against the real stack (API, worker, LocalStack, FFmpeg): the ranked For You
 * feed in a phone-sized window. Twelve clips are posted through the API first; a new user then opens
 * Home, the first clip plays, "Why this post?" explains it, and scrolling through ten clips produces
 * impressions with their positions and the feed session's id, sent to `POST /events` in batches.
 */
const CLIPS = [
  resolve(__dirname, '../../fixtures/clips/clip-09-square-720p-11s.mp4'),
  resolve(__dirname, '../../fixtures/clips/clip-18-square-480p-8s.mp4'),
];
const POSTS = 12;

type SentEvent = {
  type: string;
  postId: string;
  surface: string;
  sessionId: string | null;
  position: number | null;
  value: number | null;
};

/**
 * Brings the n-th page of the pager into view. `scrollTop` is assigned directly: on the web, React
 * Native replaces the element's `scrollTo` with its own (`{ x, y, animated }`).
 */
async function scrollToPage(list: Locator, n: number): Promise<void> {
  await list.evaluate((el, index) => {
    el.scrollTop = index * el.clientHeight;
  }, n);
}

/**
 * Registers an author through the API and publishes `count` clips; resolves when all are playable.
 * Returns the author's token so that the journey can delete the account at the end: left behind, the
 * clips of every run filled the local For You feeds.
 */
async function seedPosts(
  request: APIRequestContext,
  count: number,
): Promise<{ Authorization: string }> {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 100000)}`;
  const handle = `fy_${suffix}`.slice(0, 30);
  const registered = await request.post('/api/v1/auth/register', {
    headers: { 'X-Client': 'android' },
    data: {
      email: `fy-${suffix}@example.com`,
      password: 'correct horse battery',
      handle,
      displayName: 'For You Author',
    },
  });
  expect(registered.status()).toBe(201);
  const auth = { Authorization: `Bearer ${(await registered.json()).accessToken}` };

  const postIds: string[] = [];
  for (let i = 0; i < count; i++) {
    const bytes = readFileSync(CLIPS[i % CLIPS.length]!);
    const ticket = await (
      await request.post('/api/v1/media/uploads', {
        headers: auth,
        data: { kind: 'VIDEO', contentType: 'video/mp4', bytes: bytes.length },
      })
    ).json();
    const put = await request.put(ticket.putUrl, {
      headers: { 'Content-Type': 'video/mp4' },
      data: bytes,
    });
    expect(put.ok()).toBe(true);
    await request.post(`/api/v1/media/uploads/${ticket.mediaId}/complete`, { headers: auth });
    const post = await request.post('/api/v1/posts', {
      headers: auth,
      data: { mediaId: ticket.mediaId, caption: `For You clip ${i + 1}` },
    });
    expect(post.status()).toBe(201);
    postIds.push((await post.json()).id);
  }
  // The worker transcodes them one after another.
  await expect
    .poll(
      async () => {
        const states = await Promise.all(
          postIds.map(
            async (id) =>
              (await (await request.get(`/api/v1/posts/${id}`, { headers: auth })).json()).status,
          ),
        );
        return states.filter((status) => status === 'PUBLISHED').length;
      },
      { timeout: 180_000, intervals: [2_000] },
    )
    .toBe(count);
  return auth;
}

test('For You: the first clip plays, is explained, and scrolling ten clips reports ten impressions', async ({
  browser,
  request,
}) => {
  // Not test.skip: a journey that did not run must not look like one that passed.
  expect(existsSync(CLIPS[0]!), 'synthetic clips missing: run `make clips` first').toBe(true);
  test.setTimeout(420_000);
  const author = await seedPosts(request, POSTS);

  // A phone-sized window: one clip per screen, details laid over it.
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  const events: SentEvent[] = [];
  page.on('request', (sent) => {
    if (sent.method() === 'POST' && sent.url().endsWith('/api/v1/events')) {
      events.push(...(sent.postDataJSON() as { events: SentEvent[] }).events);
    }
  });
  const firstPage = page.waitForResponse(
    (response) => response.url().includes('/api/v1/feed/home') && response.status() === 200,
  );

  const user = await signUpAndOnboard(page, 'Fay Viewer');
  const feed = (await (await firstPage).json()) as { sessionId: string; items: unknown[] };
  expect(feed.items.length).toBe(10);

  // Home opens on For You; the clip in view plays.
  await expect(page.getByRole('radio', { name: 'For You' })).toHaveAttribute(
    'aria-checked',
    'true',
  );
  const list = page.getByTestId('post-pager-list');
  await expect(list).toBeVisible();
  // One clip per screen: every page is as tall as the list.
  const pageHeight = await list.evaluate((el) => el.clientHeight);
  expect(pageHeight).toBeGreaterThan(500);

  // The clip in view plays. (A local database can hold older posts whose files went with the last
  // `make down`: LocalStack keeps nothing between runs. Those show "could not be played"; the journey
  // moves on to the next page until a clip plays, and the seeded ones always can.)
  let playing = -1;
  for (let i = 0; i < 10 && playing < 0; i++) {
    await scrollToPage(list, i);
    try {
      await expect
        .poll(
          async () =>
            page.evaluate(() =>
              Array.from(document.querySelectorAll('video')).some(
                (video) => !video.paused && video.currentTime > 0.5,
              ),
            ),
          { timeout: 8_000 },
        )
        .toBe(true);
      playing = i;
    } catch {
      // not this one
    }
  }
  expect(playing, 'a clip among the first ten pages plays').toBeGreaterThanOrEqual(0);
  // Exactly one clip plays at a time.
  expect(
    await page.evaluate(
      () => Array.from(document.querySelectorAll('video')).filter((video) => !video.paused).length,
    ),
  ).toBe(1);
  await scrollToPage(list, 0);

  // Why this post?
  await page.getByRole('button', { name: 'More' }).first().click();
  await page.getByRole('button', { name: 'Why this post?' }).click();
  await expect(page.getByTestId('why-this-post')).toBeVisible();
  await expect(page.getByTestId('why-this-post')).toContainText(
    /New for you|Trending|Matches|gigs near you/,
  );
  await page.getByRole('button', { name: 'Close' }).click();

  // Scroll through ten clips, resting a little over a second on each.
  for (let i = 1; i <= 10; i++) {
    await scrollToPage(list, i);
    await page.waitForTimeout(1_300);
  }

  // Impressions for the positions scrolled through, each with the session the feed came from. They
  // leave in batches (at twenty events, or after ten seconds).
  const impressions = () => events.filter((event) => event.type === 'IMPRESSION');
  await expect
    .poll(() => new Set(impressions().map((event) => event.position)).size, { timeout: 20_000 })
    .toBeGreaterThanOrEqual(10);
  for (const impression of impressions()) {
    expect(impression.surface).toBe('FOR_YOU');
    expect(impression.sessionId).toBe(feed.sessionId);
  }
  expect(impressions().map((event) => event.position)).toEqual(
    expect.arrayContaining([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]),
  );
  // No clip is reported twice.
  expect(new Set(impressions().map((event) => event.postId)).size).toBe(impressions().length);
  // The player reported watching, too.
  expect(events.some((event) => event.type === 'VIEW_START')).toBe(true);
  // The second page of the session was fetched on the way.
  expect(await list.evaluate((el) => el.scrollHeight)).toBeGreaterThan(10 * pageHeight);

  // A like is a signal with its place in the feed.
  await page.getByRole('button', { name: 'Like' }).first().click();
  await expect
    .poll(() => events.find((event) => event.type === 'LIKE')?.sessionId ?? null, {
      timeout: 20_000,
    })
    .toBe(feed.sessionId);

  // On a wide window the same feed shows the details, and the reasons, beside the clip.
  await page.setViewportSize({ width: 1280, height: 800 });
  await expect(page.getByTestId('post-reasons').first()).toBeVisible();

  // Following is still there, and empty for someone who follows nobody.
  await page.getByRole('radio', { name: 'Following' }).click();
  await expect(page.getByTestId('feed-empty')).toBeVisible();

  console.log(`for-you journey: feed session ${feed.sessionId}`);
  await deleteAccount(page, user);
  const deleted = await request.delete('/api/v1/me', {
    headers: author,
    data: { password: 'correct horse battery' },
  });
  expect(deleted.status()).toBe(202);
  await context.close();
});
