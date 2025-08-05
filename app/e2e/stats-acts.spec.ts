import { expect, test, type APIRequestContext } from '@playwright/test';

import { deleteAccount } from './helpers';

/**
 * In a phone-sized window: a fan's gig history (added through the API as past gigs,
 * which marks them Went) becomes the stats on the Me tab, a line opens the gigs behind it, sharing is
 * switched on in Settings → Privacy, and a two-act gig's page has the act bar. Setlists are not asserted:
 * they come live from setlist.fm, and these gigs exist only in this run.
 */
const PASSWORD = 'correct horse battery';

type Account = { id: string; email: string; handle: string; auth: { Authorization: string } };

async function register(request: APIRequestContext, prefix: string): Promise<Account> {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 100000)}`;
  const handle = `${prefix}_${suffix}`.slice(0, 30);
  const email = `${prefix}-${suffix}@example.com`;
  const registered = await request.post('/api/v1/auth/register', {
    headers: { 'X-Client': 'android' },
    data: { email, password: PASSWORD, handle, displayName: 'Stats Fan' },
  });
  expect(registered.status()).toBe(201);
  const body = await registered.json();
  const auth = { Authorization: `Bearer ${body.accessToken}` };
  // Onboarded through the API, so that signing in lands on the tabs.
  const city = (await (await request.get('/api/v1/cities?q=Manch')).json())[0];
  await request.patch('/api/v1/me', { headers: auth, data: { homeCityId: city.id } });
  await request.put('/api/v1/me/genres', {
    headers: auth,
    data: { genres: [{ genreId: 2, weight: 1 }] },
  });
  await request.post('/api/v1/me/onboarding/complete', { headers: auth });
  return { id: body.user.id, email, handle, auth };
}

/** A past gig added by the fan: the server marks the person who adds a past gig as Went. */
async function addGig(
  request: APIRequestContext,
  fan: Account,
  acts: string[],
  venue: string,
  date: string,
): Promise<{ id: string; venueId: string }> {
  const response = await request.post('/api/v1/gigs', {
    headers: fan.auth,
    data: {
      artistNames: acts,
      newVenue: { name: venue, cityName: 'Manchester', lat: 53.4808, lng: -2.2426 },
      date,
    },
  });
  expect(response.status()).toBe(201);
  const item = (await response.json()).item;
  expect(item.myAttendance).toBe('WENT');
  return { id: item.id, venueId: item.venue.id };
}

test('Stats from Went gigs, the gigs behind a line, sharing, and the act bar on a gig page', async ({
  browser,
  request,
}) => {
  test.setTimeout(180_000);
  const tag = Math.floor(Math.random() * 1e6).toString(36);
  const fan = await register(request, 'stats');
  const headliner = `Keo ${tag}`;
  const support = `Cardinals ${tag}`;
  const year = new Date().getFullYear();
  const twoActs = await addGig(
    request,
    fan,
    [headliner, support],
    `Scala ${tag}`,
    `${year - 1}-03-01`,
  );
  // The support act headlines another venue later the same night: two times seen.
  await addGig(request, fan, [support], `The Snug ${tag}`, `${year - 1}-03-01`);
  await addGig(request, fan, [headliner], `Band on the Wall ${tag}`, `${year - 1}-11-20`);

  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByLabel('Email').last().fill(fan.email);
  await page.getByLabel('Password').last().fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).last().click();
  await expect(page.getByRole('radio', { name: 'For You' }).last()).toBeVisible();

  // Me tab → Stats: totals and the most-seen acts, support included.
  await page.getByRole('tab', { name: /Me$/ }).click();
  await page.getByTestId('profile-tab-stats').last().click();
  const stats = page.getByTestId('stats').last();
  await expect(stats).toBeVisible();
  await expect(page.getByLabel('3 gigs', { exact: true })).toBeVisible();
  await expect(page.getByLabel('2 acts', { exact: true })).toBeVisible();
  await expect(page.getByLabel('3 venues', { exact: true })).toBeVisible();
  const acts = page.getByTestId('stats-act');
  await expect(acts).toHaveCount(2);
  // Both acts were seen twice; the tie goes to the one seen most recently (the headliner, in November).
  await expect(acts.nth(0)).toContainText(headliner);
  await expect(acts.nth(0)).toContainText('2×');
  await expect(acts.nth(1)).toContainText(support);
  // Both times on one night: the line is that date alone, the badge says how many.
  await expect(acts.nth(1)).toContainText(`1 Mar ${year - 1}`);
  await expect(acts.nth(1)).not.toContainText('–');
  await expect(page.getByTestId('me-stats-privacy')).toContainText('Your profile is public');

  // A line opens the gigs behind it, newest first.
  await acts.nth(1).click();
  const gigs = page.getByTestId('stats-gigs').last();
  await expect(gigs).toBeVisible();
  await expect(gigs).toContainText(`The Snug ${tag}`);
  await expect(gigs).toContainText(`Scala ${tag}`);
  await page.goBack();

  // A public profile (the default) shows them to anyone signed in; a private one only to its
  // followers.
  const other = await register(request, 'viewer');
  expect(
    (await request.get(`/api/v1/users/${fan.id}/stats`, { headers: other.auth })).status(),
  ).toBe(200);
  await page.getByTestId('me-stats-privacy').last().click();
  await page.getByRole('radio', { name: 'My followers' }).last().click();
  await expect(page.getByTestId('privacy-profile-detail').last()).toContainText(
    'Only your followers see',
  );
  await expect
    .poll(async () =>
      (await request.get(`/api/v1/users/${fan.id}/stats`, { headers: other.auth })).status(),
    )
    .toBe(403);

  // The two-act gig: a bar of the acts at the top; the support act gets its own part of the page.
  await page.goto(`/gig/${twoActs.id}`);
  await expect(page.getByTestId('act-bar').last()).toBeVisible();
  await expect(page.getByRole('tab')).toHaveCount(3);
  await expect(page.getByRole('tab', { name: 'Whole night' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(page.getByText('LINE-UP').last()).toBeVisible();
  await page.getByRole('tab', { name: `${support}, support` }).click();
  await expect(page.getByTestId('act-billing').last()).toHaveText('SUPPORT');
  await expect(page.getByText(`CLIPS OF ${support.toUpperCase()}`).last()).toBeVisible();
  await expect(page.getByText('LINE-UP')).toHaveCount(0);

  // Clean up: the accounts go (their gigs stay, as every fan-added gig does).
  await page.goto('/');
  await expect(page.getByRole('radio', { name: 'For You' }).last()).toBeVisible();
  await deleteAccount(page, { handle: fan.handle, password: PASSWORD });
  await request.delete('/api/v1/me', { headers: other.auth, data: { password: PASSWORD } });
  await context.close();
});
