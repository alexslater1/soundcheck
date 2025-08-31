import { expect, test } from '@playwright/test';

import { deleteAccount, signInAsAdmin, signUpAndOnboard } from './helpers';

/**
 * The console's two doors. The admin, signed out, opens a console page, signs in and lands back
 * on it inside the shell; a fresh user gets "This area is for operators" and their browser sends no
 * admin request at all. The fan can switch to the admin account from that page and back again from the
 * console's nav. Changes no shared state (the fresh account is deleted).
 */
test('the admin signs in and comes back to the console page they asked for', async ({ page }) => {
  await page.goto('/console/users?range=30d');
  await signInAsAdmin(page);

  await expect(page).toHaveURL(/\/console\/users/);
  await expect(page.getByTestId('console-nav-users')).toBeVisible();
  await expect(page.getByRole('radio', { name: '30 d' })).toBeChecked();
  await expect(page.getByTestId('users-search')).toBeVisible();
  // The admin's own account is listed (the bootstrap creates it with the handle "admin").
  await page.getByTestId('users-search').fill('admin');
  await expect(page.getByText('@admin', { exact: false }).first()).toBeVisible();

  await page.getByTestId('console-nav-ranking').click();
  await expect(page.getByTestId('config-list')).toBeVisible();
});

test('a fan sees the operators-only page, sends no admin request, and can switch accounts', async ({
  page,
}) => {
  const adminRequests: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/admin')) adminRequests.push(request.url());
  });
  const user = await signUpAndOnboard(page, 'Curious Fan');

  await page.goto('/console/users');
  await expect(page.getByText('This area is for operators')).toBeVisible();
  await expect(page.getByText(`signed in as @${user.handle}`, { exact: false })).toBeVisible();
  expect(adminRequests).toEqual([]);

  // Switch to the admin account: sign-in, then into the console. It lands on the Overview, not on
  // Users: while the operators-only page shows, the console's pages aren't mounted and the address bar
  // reads /console, so that is the `next` the sign-in screen gets (seen in CI).
  await page.getByText('Sign out and sign in as admin').click();
  await signInAsAdmin(page);
  await expect(page).toHaveURL(/\/console$/);
  await expect(page.getByTestId('console-account')).toHaveText('Signed in as @admin');

  // And back: signing out of the console and in as the fan goes home, not to "for operators".
  await page.getByTestId('console-sign-out').click();
  await expect(page.getByText('Welcome back')).toBeVisible();
  await page.getByLabel('Email').last().fill(user.email);
  await page.getByLabel('Password').last().fill(user.password);
  await page.getByRole('button', { name: 'Sign in' }).last().click();
  await expect(page.getByRole('radio', { name: 'For You' }).last()).toBeVisible();
  await expect(page.getByText('This area is for operators')).toHaveCount(0);

  await deleteAccount(page, user);
});
