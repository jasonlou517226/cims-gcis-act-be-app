import type { Response } from 'playwright-core';
import { newPage, type LaunchedBrowser } from '../browser.ts';
import type { Credentials, RuntimeOptions } from '../config.ts';
import {
  CHILD_HEALTH_SERVICE,
  FAMILIES_CLINICS,
  OnlineBookingPage,
  RESP_CAPTCHA_FAIL,
  RESP_OK,
  STUDENT_HEALTH_SERVICE,
  errMsgOf,
  respCodeOf,
  type ServiceProfile,
} from '../pages/online-booking.ts';
import { errorMessage, type Recorder } from '../report.ts';
import { childHealthServiceJourney } from './booking.ts';
import { studentHealthServiceJourney } from './stdhs.ts';

export type OnlineBookingTarget = 'booking' | 'stdhs' | 'pdqa';

const PROFILES: Readonly<Record<OnlineBookingTarget, ServiceProfile>> = {
  booking: CHILD_HEALTH_SERVICE,
  stdhs: STUDENT_HEALTH_SERVICE,
  pdqa: FAMILIES_CLINICS,
};

/**
 * Logs in to the Online Booking SPA.
 *
 * The captcha is answered with the value the ACT/test backend echoes in the
 * captcha response ("captchaForACT"). A wrong captcha (respCode 113) makes the
 * SPA fetch a new image by itself, so the answer is simply re-read; the
 * credentials are re-filled every time because the SPA clears the form after
 * a failed attempt. When the backend accepts the captcha but rejects the
 * credentials, the loop stops at once — retrying cannot help.
 *
 * This build has no OCR fallback (the Java suite falls back to Tesseract /
 * Vision): an environment that does not expose "captchaForACT" fails with an
 * explicit reason.
 */
export async function loginWithCaptcha(
  app: OnlineBookingPage,
  credentials: Credentials,
  options: RuntimeOptions,
  rec: Recorder,
): Promise<void> {
  const name = app.serviceName;
  await app.open();
  rec.check(`${name}: login form is visible`, await app.isLoginFormVisible());
  rec.check(`${name}: captcha input is visible`, await app.isCaptchaVisible());

  let accepted = false;
  let failure = '';
  for (let attempt = 1; attempt <= options.captchaAttempts && !accepted; attempt++) {
    const payload = await app.solveCurrentCaptcha(20_000);
    if (!payload) {
      console.log(`attempt ${attempt}: no captcha image captured, renewing`);
      await app.reloadCaptcha();
      continue;
    }
    const answer = payload.actAnswer;
    if (answer === null) {
      failure = 'the captcha response has no "captchaForACT" (OCR fallback is not available in this build)';
      break;
    }
    if (answer.length !== 4) {
      console.log(`attempt ${attempt}: captcha unreadable [${answer}], renewing image`);
      await app.reloadCaptcha();
      continue;
    }
    console.log(`attempt ${attempt}: captcha answer = ${answer}`);
    await app.fillUsername(credentials.login);
    await app.fillPassword(credentials.password);
    await app.fillCaptcha(answer);

    let response: Response;
    try {
      response = await app.submitAndWaitForLoginResponse(20_000);
    } catch (error) {
      if (app.hasLeftLoginPage()) {
        accepted = true;
        break;
      }
      console.log(`attempt ${attempt}: no login response (${errorMessage(error)}), retrying`);
      await app.reloadCaptcha();
      continue;
    }

    const body = await response.text();
    const respCode = respCodeOf(body);
    console.log(`attempt ${attempt}: respCode=${respCode}`);
    if (respCode === null) {
      accepted = app.hasLeftLoginPage();
      if (!accepted) {
        await app.reloadCaptcha();
      }
      continue;
    }
    if (respCode === RESP_CAPTCHA_FAIL) {
      // The SPA auto-fetches a fresh captcha after 113; just retry.
      console.log(`attempt ${attempt}: captcha rejected (${RESP_CAPTCHA_FAIL}), retrying`);
      continue;
    }
    if (respCode === RESP_OK) {
      accepted = app.hasLeftLoginPage();
      if (!accepted) {
        // respCode 0 but still on the login route: give the SPA a moment to
        // finish routing before declaring failure.
        await app.page.waitForTimeout(3_000);
        accepted = app.hasLeftLoginPage();
        if (!accepted) {
          failure = 'respCode 0 but still on the login route';
        }
      }
      break;
    }
    failure = `backend rejected login (${errMsgOf(body)})`;
    break;
  }
  rec.check(
    `${name}: login accepted (attempts=${options.captchaAttempts}${failure ? `; ${failure}` : ''})`,
    accepted,
    `URL: ${app.currentUrl()}`,
  );
}

/**
 * One Online Booking target: service selection → captcha login → (Child Health
 * Service) the three-language walk and logout, (Student Health Service) steps
 * 17-34, (Families Clinics) login only.
 */
export async function runOnlineBookingJourney(
  launcher: LaunchedBrowser,
  target: OnlineBookingTarget,
  credentials: Credentials,
  options: RuntimeOptions,
  rec: Recorder,
): Promise<void> {
  const { context, page } = await newPage(launcher.browser);
  try {
    const app = new OnlineBookingPage(page, PROFILES[target]);
    await loginWithCaptcha(app, credentials, options, rec);
    if (target === 'booking') {
      await childHealthServiceJourney(app, rec);
    } else if (target === 'stdhs') {
      await studentHealthServiceJourney(app, rec);
    } else {
      rec.check('pdqa: left the PDQAS login route', app.hasLeftLoginPage(), `URL: ${app.currentUrl()}`);
    }
  } finally {
    await context.close();
  }
}
