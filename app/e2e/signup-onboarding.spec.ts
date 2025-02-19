import { expect, test } from '@playwright/test';

/**
 * Sign up → onboarding (city, genres, artists) → Home shows the API health badge.
 * Runs against the real stack through the edge; the account is deleted at the end via Settings.
 */
test('sign up, onboard, land on Home, delete the account', async ({ page }) => {
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

  // Artists placeholder → finish → tabs.
  await expect(page.getByText('Follow some artists')).toBeVisible();
  await page.getByRole('button', { name: 'Finish' }).last().click();
  await expect(page.getByText('Soundcheck', { exact: true }).first()).toBeVisible();
  await expect(page.getByTestId('health-status')).toHaveText(/API: UP/);

  // Me tab shows the profile; Settings → delete the account with the password.
  await page.getByRole('tab', { name: /Me$/ }).click();
  await expect(page.getByText(`@${handle}`)).toBeVisible();
  await page.getByText('Settings →').last().click();
  await page.getByText('Account and deletion →').last().click();
  await page.getByLabel('Password').last().fill(password);
  await page.getByRole('button', { name: 'Delete account' }).last().click();
  await expect(page.getByText('Welcome back')).toBeVisible();
});
