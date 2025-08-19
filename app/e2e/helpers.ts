import { expect, type Page } from '@playwright/test';

export type E2eUser = { handle: string; password: string };

/** Signs up a fresh account and walks through onboarding (city, two genres, no artists) to the Home tab. */
export async function signUpAndOnboard(page: Page, displayName = 'E2E User'): Promise<E2eUser> {
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 100000)}`;
  const handle = `e2e_${suffix}`.slice(0, 30);
  const password = 'correct horse battery';
  await page.goto('/');
  await page.getByText('New here? Create an account').click();
  await page.getByLabel('Email').last().fill(`e2e-${suffix}@example.com`);
  await page.getByLabel('Handle').last().fill(handle);
  await page.getByLabel('Display name').last().fill(displayName);
  await page.getByLabel('Password').last().fill(password);
  await page.getByRole('button', { name: 'Create account' }).last().click();
  await page.getByLabel('City').last().fill('Manch');
  await page.getByText('Manchester', { exact: true }).last().click();
  await page
    .getByRole('button', { name: /Continue as Manchester/ })
    .last()
    .click();
  await page.getByRole('button', { name: 'Indie', exact: true }).last().click();
  await page.getByRole('button', { name: 'Techno', exact: true }).last().click();
  await page
    .getByRole('button', { name: /Continue \(2\/8\)/ })
    .last()
    .click();
  await expect(page.getByText('Follow some artists')).toBeVisible();
  await page.getByRole('button', { name: 'Finish' }).last().click();
  await expect(page.getByRole('radio', { name: 'For You' }).last()).toBeVisible();
  return { handle, password };
}

/**
 * Me tab → Settings → delete the account with its password. The page must be showing the tab bar.
 * (Navigation stays inside the app on purpose: every full page load makes an `/auth/refresh` call, and
 * the auth endpoints allow 10 a minute per IP, which a few journeys from one machine would exceed.)
 */
export async function deleteAccount(page: Page, user: E2eUser): Promise<void> {
  await page.getByRole('tab', { name: /Me$/ }).click();
  await expect(page.getByText(`@${user.handle}`).first()).toBeVisible();
  await page.getByTestId('me-settings').last().click();
  await page.getByText('Account and deletion →').last().click();
  await page.getByLabel('Password').last().fill(user.password);
  await page.getByRole('button', { name: 'Delete account' }).last().click();
  await expect(page.getByText('Welcome back')).toBeVisible();
}

/**
 * Chooses a file through the Create tab's "Choose videos" button. The gig and the clips can be chosen in
 * either order, and Post needs both; unless a gig was chosen, the clip is posted as "not
 * from a gig".
 */
export async function chooseVideo(page: Page, file: string): Promise<void> {
  if (await page.getByTestId('no-gig').isVisible()) {
    await page.getByTestId('no-gig').click();
  }
  const chooser = page.waitForEvent('filechooser');
  await page.getByTestId('choose-video').click();
  await (await chooser).setFiles(file);
}

/**
 * Signs in as the bootstrap admin (`SOUNDCHECK_ADMIN_EMAIL/PASSWORD`, as the API was started with; CI
 * sets throwaway values) on the sign-in screen the page is showing. Never logs the password.
 */
export async function signInAsAdmin(page: Page): Promise<void> {
  const email = process.env.SOUNDCHECK_ADMIN_EMAIL;
  const password = process.env.SOUNDCHECK_ADMIN_PASSWORD;
  if (!email || !password) {
    throw new Error(
      'Set SOUNDCHECK_ADMIN_EMAIL and SOUNDCHECK_ADMIN_PASSWORD (the API’s bootstrap admin)',
    );
  }
  await expect(page.getByText('Welcome back')).toBeVisible();
  await page.getByLabel('Email').last().fill(email);
  await page.getByLabel('Password').last().fill(password);
  await page.getByRole('button', { name: 'Sign in' }).last().click();
}
