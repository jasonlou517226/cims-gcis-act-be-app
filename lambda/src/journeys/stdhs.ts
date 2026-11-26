import type { OnlineBookingPage, UiLanguage } from '../pages/online-booking.ts';
import { ONLINE_BOOKING_BASE_URL } from '../pages/online-booking.ts';
import type { Recorder } from '../report.ts';

/** Post-login hash routes covered by the Student Health Service journey (steps 17-34). */
const HOME_URL = `${ONLINE_BOOKING_BASE_URL}/#/StdHS`;
const HEALTH_URL = `${HOME_URL}/spa/spa-wsmash/health`;
const FINDING_URL = `${HOME_URL}/spa/spa-wsmash/findingAndRecommendation`;
const LATEST_NEWS_URL = `${HOME_URL}/latestNews`;
const CHANGE_PROFILE_URL = `${HOME_URL}/changeProfile`;
const CHANGE_PASSWORD_URL = `${HOME_URL}/changePassword`;

const HEALTH_ROUTE = '/StdHS/spa/spa-wsmash/health';
const FINDING_ROUTE = '/StdHS/spa/spa-wsmash/findingAndRecommendation';

/** What's New (iAM Smart notice) — English only: the walk reads it before pressing Next. */
const LATEST_NEWS_MARKER_1 = 'To "iAM Smart" user:';
const LATEST_NEWS_MARKER_2 =
  'Due to system update, please reset the linkage before login to Student Health Service through the "iAM Smart" platform.';

/** Marker texts that prove each page rendered successfully (exact UI copy). */
const APPT_FOOTNOTE_MARKER: Readonly<Record<UiLanguage, string>> = {
  en: 'The appointment can only be rescheduled within the coming 4 weeks.'
    + ' If you wish to reschedule to a later date, please log in and try again later.',
  'zh-HK': '改期只可選擇未來四星期內的日期。如需改期到稍後的日期，請稍後登入再試。',
  'zh-CN': '改期只可选择未来四星期内的日期。如需改期到稍后的日期，请稍后登入再试',
};

const CHANGE_PROFILE_MARKER: Readonly<Record<UiLanguage, string>> = {
  en: 'Change Personal Email',
  'zh-HK': '更改個人電郵',
  'zh-CN': '更改个人电邮',
};

const CHANGE_PASSWORD_MARKER: Readonly<Record<UiLanguage, string>> = {
  en: 'Change Password',
  'zh-HK': '更改密碼',
  'zh-CN': '更改密码',
};

function at(step: number | string, what: string, app: OnlineBookingPage): string {
  return `step ${step}: ${what} (URL: ${app.currentUrl()})`;
}

/** Opens the Health Message page and checks that it shows an image. */
async function assertHealthPageShowsImage(app: OnlineBookingPage, rec: Recorder, step: number, what: string): Promise<void> {
  await app.openRoute(HEALTH_URL);
  await app.waitForAnyImage(20_000);
  rec.journeyCheck(at(step, what, app), app.isOnRoute(HEALTH_ROUTE));
}

/**
 * What's New → Next. In English the iAM Smart notice is read first (step 19);
 * pressing Next must lead back to the Health Message page with an image
 * (`backStep`: 19b in English, 25 in Traditional and 31 in Simplified Chinese).
 */
async function latestNewsThenNext(
  app: OnlineBookingPage,
  rec: Recorder,
  language: UiLanguage,
  backStep: string,
  nextLabel: string,
): Promise<void> {
  await app.openRoute(LATEST_NEWS_URL);
  if (language === 'en') {
    await app.waitForText(LATEST_NEWS_MARKER_1, 20_000);
    await app.waitForText(LATEST_NEWS_MARKER_2, 20_000);
    rec.journeyCheck(at(19, "what's-new shows the iAM Smart notice", app), app.isOnRoute('/StdHS/latestNews'));
  }
  await app.clickNext(language);
  await app.waitForUrlContains('#/StdHS/spa/spa-wsmash/health', 20_000);
  await app.waitForAnyImage(20_000);
  rec.journeyCheck(
    at(backStep, `"${nextLabel}" leads back to the health page with an image`, app),
    app.isOnRoute(HEALTH_ROUTE),
  );
}

/**
 * Post-login Student Health Service journey (steps 17-34). Throws on the first
 * step whose marker or route is wrong. Each language walks: Health Message
 * (image) → Findings and Recommendations (accessed) → What's New (Next → back
 * to Health Message) → Appointment home (reschedule footnote) → Change
 * Personal Email → Change Password.
 */
export async function studentHealthServiceJourney(app: OnlineBookingPage, rec: Recorder): Promise<void> {
  // ---- English walk (steps 17-22) ----
  await assertHealthPageShowsImage(app, rec, 17, 'health page shows an image');
  await app.openRoute(FINDING_URL);
  rec.journeyCheck(at(18, 'findings page accessed', app), app.isOnRoute(FINDING_ROUTE));
  // Java logs the notice as "step 19" and the return to the health page as "step 19b".
  await latestNewsThenNext(app, rec, 'en', '19b', 'Next');
  await app.openRouteAndWaitFor(HOME_URL, APPT_FOOTNOTE_MARKER.en);
  rec.journeyCheck(at(20, 'appointment home shows the reschedule footnote', app), app.isOnRoute('/StdHS'));
  await app.openRouteAndWaitFor(CHANGE_PROFILE_URL, CHANGE_PROFILE_MARKER.en);
  rec.journeyCheck(at(21, 'change-profile page shows its heading', app), app.isOnRoute('/StdHS/changeProfile'));
  await app.openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER.en);
  rec.journeyCheck(at(22, 'change-password page shows its heading', app), app.isOnRoute('/StdHS/changePassword'));

  // ---- Traditional Chinese walk (steps 23-28) ----
  await app.openRoute(HEALTH_URL);
  await app.switchLanguage('zh-HK');
  await app.waitForAnyImage(20_000);
  rec.journeyCheck(at(23, 'health page in Traditional Chinese shows an image', app), app.isOnRoute(HEALTH_ROUTE));
  await app.openRoute(FINDING_URL);
  rec.journeyCheck(at(24, 'Chinese findings page accessed', app), app.isOnRoute(FINDING_ROUTE));
  await latestNewsThenNext(app, rec, 'zh-HK', '25', '下一頁');
  await app.openRouteAndWaitFor(HOME_URL, APPT_FOOTNOTE_MARKER['zh-HK']);
  rec.journeyCheck(at(26, 'Chinese appointment home shows the reschedule footnote', app), app.isOnRoute('/StdHS'));
  await app.openRouteAndWaitFor(CHANGE_PROFILE_URL, CHANGE_PROFILE_MARKER['zh-HK']);
  rec.journeyCheck(at(27, 'Chinese change-profile page shows its heading', app), app.isOnRoute('/StdHS/changeProfile'));
  await app.openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER['zh-HK']);
  rec.journeyCheck(at(28, 'Chinese change-password page shows its heading', app), app.isOnRoute('/StdHS/changePassword'));

  // ---- Simplified Chinese walk (steps 29-34) ----
  await app.openRoute(HEALTH_URL);
  await app.switchLanguage('zh-CN');
  await app.waitForAnyImage(20_000);
  rec.journeyCheck(at(29, 'health page in Simplified Chinese shows an image', app), app.isOnRoute(HEALTH_ROUTE));
  await app.openRoute(FINDING_URL);
  rec.journeyCheck(at(30, 'Simplified Chinese findings page accessed', app), app.isOnRoute(FINDING_ROUTE));
  await latestNewsThenNext(app, rec, 'zh-CN', '31', '下一页');
  await app.openRouteAndWaitFor(HOME_URL, APPT_FOOTNOTE_MARKER['zh-CN']);
  rec.journeyCheck(at(32, 'Simplified Chinese appointment home shows the reschedule footnote', app), app.isOnRoute('/StdHS'));
  await app.openRouteAndWaitFor(CHANGE_PROFILE_URL, CHANGE_PROFILE_MARKER['zh-CN']);
  rec.journeyCheck(at(33, 'Simplified Chinese change-profile page shows its heading', app), app.isOnRoute('/StdHS/changeProfile'));
  await app.openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER['zh-CN']);
  rec.journeyCheck(at(34, 'Simplified Chinese change-password page shows its heading', app), app.isOnRoute('/StdHS/changePassword'));
}
