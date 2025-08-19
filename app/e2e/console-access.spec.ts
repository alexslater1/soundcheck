import { expect, test } from '@playwright/test';

import { deleteAccount, signInAsAdmin, signUpAndOnboard } from './helpers';

/**
 * The console's two doors. The admin, signed out, opens a console page, signs in and lands back
 * on it inside the shell; a fresh user gets "This area is for operators" and their browser sends no
 * admin request at all. Changes no shared state (the fresh account is deleted).
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
  await expect(page.getByText('Built by Group B')).toBeVisible();
});

test('a fan sees the operators-only page and sends no admin request', async ({ page }) => {
  const adminRequests: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/admin')) adminRequests.push(request.url());
  });
  const user = await signUpAndOnboard(page, 'Curious Fan');

  await page.goto('/console/users');
  await expect(page.getByText('This area is for operators')).toBeVisible();
  expect(adminRequests).toEqual([]);

  await page.goto('/');
  await deleteAccount(page, user);
});
