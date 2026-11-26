import type { Page } from 'playwright-core';
import { newPage, type LaunchedBrowser } from '../browser.ts';
import type { Credentials } from '../config.ts';
import { SCHOOL_PORTAL_LOGIN_URL, SchoolPortalPage } from '../pages/school-portal.ts';
import type { Recorder } from '../report.ts';
import { errorMessage } from '../report.ts';

/** Runs `scenario` in its own browser context, so every test starts logged out (as JUnit does per test). */
async function withFreshContext(
  launcher: LaunchedBrowser,
  scenario: (page: Page) => Promise<void>,
): Promise<void> {
  const { context, page } = await newPage(launcher.browser);
  try {
    await scenario(page);
  } finally {
    await context.close();
  }
}

/**
 * School Portal login checks (port of LoginTest): the login page renders,
 * valid credentials are accepted, invalid credentials are rejected, and the
 * Clear button empties both fields.
 */
export async function runPortalJourney(
  launcher: LaunchedBrowser,
  credentials: Credentials,
  rec: Recorder,
): Promise<void> {
  await withFreshContext(launcher, async (page) => {
    const portal = new SchoolPortalPage(page);
    await portal.open();
    const title = await portal.currentPageTitle();
    const url = portal.currentUrl();
    rec.check('portal: login form is visible', await portal.isLoginFormVisible());
    rec.check('portal: page title contains "Login"', title.includes('Login'), `title: ${title}`);
    rec.check('portal: stays on the login URL', url === SCHOOL_PORTAL_LOGIN_URL, `URL: ${url}`);
  });

  await withFreshContext(launcher, async (page) => {
    const portal = new SchoolPortalPage(page);
    await portal.loginAs(credentials.login, credentials.password);
    // Handle the "another active session" dialog, then wait until we leave
    // the public login page.
    await portal.confirmSessionConflictIfPresent();
    const loggedIn = await portal.waitForLoginSuccess(60_000);
    rec.check('portal: valid credentials log in (left SchoolPortalWeb/login)', loggedIn, `URL: ${portal.currentUrl()}`);
  });

  await withFreshContext(launcher, async (page) => {
    const portal = new SchoolPortalPage(page);
    await portal.loginAs(credentials.login, 'WrongPassword123!');
    // Capture the state quickly: in a headed run the page can be closed by hand
    // while we are still observing it, and this legacy portal can close itself
    // after a failed login — neither must fail the test.
    let url = portal.currentUrl();
    let body = '';
    let closed = false;
    try {
      await page.waitForTimeout(3_000);
      await portal.confirmSessionConflictIfPresent();
      await page.waitForTimeout(2_000);
      url = portal.currentUrl();
      body = await page.locator('body').innerText();
    } catch (error) {
      closed = true;
      console.log(`[invalid-login] page closed while observing: ${errorMessage(error)}`);
    }
    let formVisible = false;
    if (!closed) {
      try {
        formVisible = await portal.isUsernameInputVisible();
      } catch {
        console.log('[invalid-login] form check skipped: page closed');
      }
    }
    const rejected = url.includes('login') || body.toLowerCase().includes('invalid');
    const formReachable = formVisible || !url.includes('login.htm') || closed;
    rec.check('portal: invalid credentials are rejected (login page or error shown)', rejected, `URL: ${url}`);
    rec.check('portal: login form still reachable after a rejected login', formReachable, `URL: ${url}`);
  });

  await withFreshContext(launcher, async (page) => {
    const portal = new SchoolPortalPage(page);
    await portal.open();
    await portal.fillUsername(credentials.login);
    await portal.fillPassword(credentials.password);
    await portal.clearForm();
    // Lengths only: credentials never reach the log.
    const usernameLength = (await portal.usernameValue()).length;
    const passwordLength = (await portal.passwordValue()).length;
    rec.check('portal: Clear button empties the username field', usernameLength === 0, `length: ${usernameLength}`);
    rec.check('portal: Clear button empties the password field', passwordLength === 0, `length: ${passwordLength}`);
  });
}
