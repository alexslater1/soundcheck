import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import { chooseVideo, deleteAccount, signUpAndOnboard } from './helpers';

/**
 * Through the edge against the real stack (API, worker, LocalStack, FFmpeg), with two
 * people in two browser sessions: A uploads a clip and posts it with a caption; B follows A, finds the
 * clip in the Following feed, likes it and comments; A's Activity tab shows what B did.
 */
const CLIP = resolve(__dirname, '../../fixtures/clips/clip-02-portrait-720p-12s.mp4');

test('A posts a clip; B follows A, sees it in Following, likes and comments; A is notified', async ({
  browser,
}) => {
  // Not test.skip: a journey that did not run must not look like one that passed.
  expect(existsSync(CLIP), 'synthetic clips missing: run `make clips` first').toBe(true);
  test.setTimeout(240_000);
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const a = await contextA.newPage();
  const b = await contextB.newPage();

  // A: upload, write a caption while it uploads, Post → back on the profile, where the clip appears.
  const userA = await signUpAndOnboard(a, 'Anna Poster');
  await a.getByRole('tab', { name: /Create$/ }).click();
  await chooseVideo(a, CLIP);
  await expect(a.getByTestId('compose-form')).toBeVisible();
  await a.getByLabel('Caption').fill('Encore of the night');
  await a.getByTestId('post-button').click();
  await expect(a.getByText(`@${userA.handle}`).first()).toBeVisible();
  await expect(a.getByTestId('post-tile')).toHaveCount(1, { timeout: 30_000 });
  // The badge clears by itself when the worker has finished and the post is published.
  await expect(a.getByText('Processing…')).toHaveCount(0, { timeout: 90_000 });

  // B: nothing in Following yet; open A's profile and follow.
  const userB = await signUpAndOnboard(b, 'Ben Fan');
  await expect(b.getByTestId('feed-empty')).toBeVisible();
  await b.goto(`/u/${userA.handle}`);
  await expect(b.getByTestId('post-tile')).toHaveCount(1);
  await b.getByTestId('follow-user').click();
  await expect(b.getByRole('button', { name: `Unfollow ${userA.handle}` })).toBeVisible();
  await expect(b.getByTestId('followers-count')).toHaveText('1');

  // B: back on Home, the clip is now in the Following feed, playing; like it.
  await b.goBack();
  await expect(b.getByTestId('post-caption')).toHaveText('Encore of the night');
  const video = b.getByTestId('post-player-video');
  await expect
    .poll(async () => video.evaluate((v: HTMLVideoElement) => v.currentTime), { timeout: 30_000 })
    .toBeGreaterThan(0.5);
  await b.getByRole('button', { name: 'Like' }).click();
  await expect(b.getByTestId('like-count')).toHaveText('1');
  await expect(b.getByRole('button', { name: 'Unlike' })).toBeVisible();

  // B: open the post and comment.
  await b.getByRole('link', { name: '0 comments' }).click();
  await expect(b.getByTestId('comments-empty')).toBeVisible();
  await b.getByLabel('Add a comment').fill('Great set');
  await b.getByRole('button', { name: 'Send' }).click();
  await expect(b.getByTestId('comment')).toHaveText(new RegExp(`@${userB.handle}\\s*Great set`));

  // A: the Activity tab shows the follow, the like and the comment (and that the clip became ready).
  await a.getByRole('tab', { name: /Activity/ }).click();
  await expect(a.getByText(`@${userB.handle} started following you`)).toBeVisible();
  await expect(a.getByText(`@${userB.handle} liked your clip`)).toBeVisible();
  await expect(a.getByText(`@${userB.handle} commented on your clip`)).toBeVisible();
  await expect(a.getByText('Your clip is ready')).toBeVisible();
  await a.getByText('Mark all as read').click();
  await expect(a.getByText('Mark all as read')).toHaveCount(0);

  // A notification opens the post it is about, at the address that Share copies (/post/{id}).
  await a.getByText(`@${userB.handle} commented on your clip`).click();
  await expect(a.getByTestId('post-caption')).toHaveText('Encore of the night');
  await expect(a.getByTestId('comment')).toHaveCount(1);
  expect(new URL(a.url()).pathname).toBe(new URL(b.url()).pathname);
  expect(new URL(a.url()).pathname).toMatch(/^\/post\/[0-9a-f-]{36}$/);

  await b.goBack();
  await deleteAccount(b, userB);
  await a.goBack();
  await deleteAccount(a, userA);
  await contextA.close();
  await contextB.close();
});
