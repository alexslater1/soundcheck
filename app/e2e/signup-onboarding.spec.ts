import { expect, test } from '@playwright/test';

/**
 * Sign up → onboarding (city, genres, artists) → Home; Settings shows the API health. Then follow
 * an artist during onboarding, find a gig near the chosen city, mark Going.
 * Runs against the real stack through the edge (the catalog must hold gigs: `make ingest`); the account
 * is deleted at the end via Settings.
 */
test('sign up, onboard, follow an artist, mark a gig as Going, delete the account', async ({
  page,
}) => {
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('BROWSER:', m.text());
  });
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  const email = `e2e-${suffix}@example.com`;
  const handle = `e2e_${suffix}`.slice(0, 30);
  const password = 'correct horse battery';

  // Signed out → redirected to sign-in; go to sign-up.
  await page.goto('/');
  await expect(page.getByText('Welcome back')).toBeVisible();
  await page.getByText('New here? Create an account').click();
  await expect(page.getByText('Create account', { exact: true }).first()).toBeVisible();

  await page.getByLabel('Email').last().fill(email);
  await page.getByLabel('Handle').last().fill(handle);
  await page.getByLabel('Display name').last().fill('E2E Fan');
  await page.getByLabel('Password').last().fill(password);
  await page.getByRole('button', { name: 'Create account' }).last().click();

  // Onboarding: city.
  await expect(page.getByText('Where do you go to gigs?')).toBeVisible();
  await page.getByLabel('City').last().fill('Manch');
  await page.getByText('Manchester', { exact: true }).last().click();
  await page
    .getByRole('button', { name: /Continue as Manchester/ })
    .last()
    .click();

  // Genres.
  await expect(page.getByText('What do you listen to?')).toBeVisible();
  await page.getByRole('button', { name: 'Indie', exact: true }).last().click();
  await page.getByRole('button', { name: 'Techno', exact: true }).last().click();
  await page
    .getByRole('button', { name: /Continue \(2\/8\)/ })
    .last()
    .click();

  // Artists: follow the first suggested artist, then finish → tabs.
  await expect(page.getByText('Follow some artists')).toBeVisible();
  const firstFollow = page.getByRole('button', { name: /^Follow / }).first();
  await expect(firstFollow).toBeVisible();
  await firstFollow.click();
  await expect(page.getByRole('button', { name: /^Unfollow / }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Finish' }).last().click();
  await expect(page.getByText('Soundcheck', { exact: true }).first()).toBeVisible();

  // Find a gig near the onboarding city and mark Going.
  await page.getByRole('tab', { name: /Gigs$/ }).click();
  await expect(page.getByText('Within 25 km of Manchester')).toBeVisible();
  const firstGig = page.getByTestId('gig-card').first();
  await expect(firstGig).toBeVisible();
  await firstGig.click();
  const going = page.getByRole('radio', { name: 'Going', exact: true });
  await expect(going).toBeVisible();
  await expect(page.getByText('LINE-UP')).toBeVisible();
  const goingBefore = Number(
    (await page.getByTestId('attendance-counts').innerText()).split(' ')[0],
  );
  await going.click();
  await expect(going).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByTestId('attendance-counts')).toHaveText(
    new RegExp(`^${goingBefore + 1} going`),
  );
  // The choice is stored on the server: it survives a reload of the gig page.
  await page.reload();
  await expect(page.getByRole('radio', { name: 'Going', exact: true })).toHaveAttribute(
    'aria-checked',
    'true',
  );
  await page.goto('/');

  // Me tab shows the profile; Settings → delete the account with the password.
  await page.getByRole('tab', { name: /Me$/ }).click();
  await expect(page.getByText(`@${handle}`)).toBeVisible();
  await page.getByText('Settings →').last().click();
  await expect(page.getByTestId('health-status')).toHaveText(/API: UP/);
  await page.getByText('Account and deletion →').last().click();
  await page.getByLabel('Password').last().fill(password);
  await page.getByRole('button', { name: 'Delete account' }).last().click();
  await expect(page.getByText('Welcome back')).toBeVisible();
});
