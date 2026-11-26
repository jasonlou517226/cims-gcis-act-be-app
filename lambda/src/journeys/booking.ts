import type { OnlineBookingPage, UiLanguage } from '../pages/online-booking.ts';
import { ONLINE_BOOKING_BASE_URL } from '../pages/online-booking.ts';
import type { Recorder } from '../report.ts';

/** Post-login hash routes covered by the Child Health Service journey. */
const HOME_URL = `${ONLINE_BOOKING_BASE_URL}/#/FHS-CH`;
const CHANGE_PASSWORD_URL = `${HOME_URL}/changePassword`;
const LATEST_NEWS_URL = `${HOME_URL}/latestNews`;

/** Marker texts that prove each route rendered successfully (exact UI copy). */
const HOME_MARKER: Readonly<Record<UiLanguage, string>> = {
  en: 'You can make appointment for the following services:',
  'zh-HK': '你可以預約以下服務',
  'zh-CN': '你可以预约以下服务',
};

const CHANGE_PASSWORD_MARKER: Readonly<Record<UiLanguage, string>> = {
  en: 'Change Password',
  'zh-HK': '更改密碼',
  'zh-CN': '更改密码',
};

const LATEST_NEWS_MARKER: Readonly<Record<UiLanguage, string>> = {
  en: 'Service adjustment of Maternal and Child Health Centres (MCHCs):',
  'zh-HK': '母嬰健康院調整服務',
  'zh-CN': '母婴健康院调整服务',
};

function at(step: number, what: string, app: OnlineBookingPage): string {
  return `step ${step}: ${what} (URL: ${app.currentUrl()})`;
}

/** Waits for the home marker of the language and checks we are on the home route. */
async function assertHome(app: OnlineBookingPage, rec: Recorder, language: UiLanguage, step: number, what: string): Promise<void> {
  await app.waitForText(HOME_MARKER[language], 20_000);
  rec.journeyCheck(at(step, what, app), app.isOnRoute('/FHS-CH'));
}

/** Opens Change Password, checks it, and presses Back / 返回 (which returns to Home). */
async function visitChangePassword(app: OnlineBookingPage, rec: Recorder, language: UiLanguage, step: number, what: string): Promise<void> {
  await app.openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER[language]);
  rec.journeyCheck(at(step, what, app), app.isOnRoute('/FHS-CH/changePassword'));
  await app.clickBack(language);
}

/** Opens What's New, checks it, and presses Next / 下一頁 / 下一页 (which returns to Home). */
async function visitLatestNews(app: OnlineBookingPage, rec: Recorder, language: UiLanguage, step: number, what: string): Promise<void> {
  await app.openRouteAndWaitFor(LATEST_NEWS_URL, LATEST_NEWS_MARKER[language]);
  rec.journeyCheck(at(step, what, app), app.isOnRoute('/FHS-CH/latestNews'));
  await app.clickNext(language);
}

/**
 * Post-login Child Health Service journey (steps 1-16). Throws on the first
 * step whose marker or route is wrong:
 *
 *   English:            1 Home → 2 Change Password (Back) → 3 Home
 *                       → 4 What's New (Next) → 5 Home
 *   Traditional Chinese: 6 switch language → 7 更改密碼 (返回) → 8 Home
 *                       → 9 最新消息 (下一頁) → 10 Home
 *   Simplified Chinese:  11 switch language → 12 更改密码 (返回) → 13 Home
 *                       → 14 最新消息 (下一页) → 15 Home
 *   16 Logout — back on the login route
 */
export async function childHealthServiceJourney(app: OnlineBookingPage, rec: Recorder): Promise<void> {
  await assertHome(app, rec, 'en', 1, 'home shows the services marker');
  await visitChangePassword(app, rec, 'en', 2, 'change-password page shows its marker');
  await assertHome(app, rec, 'en', 3, 'back on home with the services marker');
  await visitLatestNews(app, rec, 'en', 4, "what's-new page shows its marker");
  await assertHome(app, rec, 'en', 5, 'back on home with the services marker');

  await app.switchLanguage('zh-HK', HOME_MARKER['zh-HK']);
  rec.journeyCheck(at(6, 'home re-rendered in Traditional Chinese', app), app.isOnRoute('/FHS-CH'));
  await visitChangePassword(app, rec, 'zh-HK', 7, 'Chinese change-password page shows its marker');
  await assertHome(app, rec, 'zh-HK', 8, 'back on home, Chinese services marker shown');
  await visitLatestNews(app, rec, 'zh-HK', 9, "Chinese what's-new page shows its marker");
  await assertHome(app, rec, 'zh-HK', 10, 'back on home, Chinese services marker shown');

  await app.switchLanguage('zh-CN', HOME_MARKER['zh-CN']);
  rec.journeyCheck(at(11, 'home re-rendered in Simplified Chinese', app), app.isOnRoute('/FHS-CH'));
  await visitChangePassword(app, rec, 'zh-CN', 12, 'Simplified Chinese change-password page shows its marker');
  await assertHome(app, rec, 'zh-CN', 13, 'back on home, Simplified Chinese marker shown');
  await visitLatestNews(app, rec, 'zh-CN', 14, "Simplified Chinese what's-new page shows its marker");
  await assertHome(app, rec, 'zh-CN', 15, 'back on home, Simplified Chinese marker shown');

  await app.logout();
  rec.journeyCheck(
    `step 16: back on the login route after logout (URL: ${app.currentUrl()})`,
    app.currentUrl().includes(app.loginRouteHash),
  );
}
