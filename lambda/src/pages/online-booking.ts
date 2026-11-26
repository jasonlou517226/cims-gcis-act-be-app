import type { Locator, Page, Response } from 'playwright-core';

/** Root service-selection route: the SPA shows one card per service here. */
export const SERVICE_SELECTION_URL = 'https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/login';

export const ONLINE_BOOKING_BASE_URL = 'https://testing.clinical.dh.gov.hk/OnlineBookingWeb';

/** Backend API paths used by the SPA (matched against on-the-wire response URLs). */
export const CAPTCHA_API = '/online-booking-user/generateCaptcha/image';
export const LOGIN_API = '/online-booking-user/loginWithSam';

/** Backend respCode values observed on the login API. */
export const RESP_OK = 0;
export const RESP_CAPTCHA_FAIL = 113;

/** One of the services offered by the root #/login route. */
export interface ServiceProfile {
  readonly target: 'booking' | 'stdhs' | 'pdqa';

  /** Short name used in log lines. */
  readonly name: string;

  /** Text of the service card on the #/login route (backend translation, matched as a substring). */
  readonly serviceOptionText: string;

  /** Hash route of the service's login form. */
  readonly loginRouteHash: string;
}

export const CHILD_HEALTH_SERVICE: ServiceProfile = {
  target: 'booking',
  name: 'Child Health Service',
  serviceOptionText: 'Child Health Service',
  loginRouteHash: '#/FHS-CH/login',
};

export const STUDENT_HEALTH_SERVICE: ServiceProfile = {
  target: 'stdhs',
  name: 'Student Health Service',
  serviceOptionText: 'Student Health Service',
  loginRouteHash: '#/StdHS/login',
};

export const FAMILIES_CLINICS: ServiceProfile = {
  target: 'pdqa',
  name: 'Families Clinics',
  serviceOptionText: 'Families Clinics (Civil Service Eligible Persons)',
  loginRouteHash: '#/PDQAS/login',
};

/** Chinese UI languages switchable from the SPA header (stable button ids). */
export type ChineseLanguage = 'zh-HK' | 'zh-CN';

const LANGUAGE_BUTTON: Readonly<Record<ChineseLanguage, string>> = {
  'zh-HK': '#btn_zh-HK',
  'zh-CN': '#btn_zh-CN',
};

/** Button label of "Next" / "Back" per UI language. */
export type UiLanguage = 'en' | 'zh-HK' | 'zh-CN';

const NEXT_BUTTON: Readonly<Record<UiLanguage, string>> = {
  en: 'Next',
  'zh-HK': '下一頁',
  'zh-CN': '下一页',
};

/** "返回" is written identically in both Chinese variants. */
const BACK_BUTTON: Readonly<Record<UiLanguage, string>> = {
  en: 'Back',
  'zh-HK': '返回',
  'zh-CN': '返回',
};

/** A captcha image seen on the wire. */
export interface CaptchaPayload {
  /** Answer echoed by the ACT/test backend ("captchaForACT"); null where the backend does not expose it. */
  actAnswer: string | null;
}

/** How long the queue must stay quiet before its newest payload is considered "on screen". */
const SETTLE_MS = 800;

/** A pending "wait for the next captcha" request. */
interface CaptchaWaiter {
  readonly timer: NodeJS.Timeout;

  resolve(payload: CaptchaPayload | null): void;
}

/** respCode of a loginWithSam JSON body (null when absent). */
export function respCodeOf(body: string): number | null {
  try {
    const parsed = JSON.parse(body) as { respCode?: unknown };
    if (typeof parsed?.respCode === 'number') {
      return parsed.respCode;
    }
  } catch {
    // Not JSON — fall back to a tolerant text search below.
  }
  const match = /"respCode"\s*:\s*(-?\d+)/.exec(body);
  return match ? Number(match[1]) : null;
}

/** errMsg of a loginWithSam JSON body (null when absent). */
export function errMsgOf(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { errMsg?: unknown };
    if (typeof parsed?.errMsg === 'string') {
      return parsed.errMsg;
    }
  } catch {
    // Not JSON — fall back to a tolerant text search below.
  }
  const match = /"errMsg"\s*:\s*"([^"]*)"/.exec(body);
  return match ? match[1] : null;
}

/** Path of a URL without the origin, for compact error messages. */
function pathnameOf(url: string): string {
  try {
    return new URL(url).pathname;
  } catch {
    return url;
  }
}

/**
 * Value of a plain JSON string field like "captchaForACT":"AB12". Returns null
 * when the field is absent or empty (an empty value counts as missing).
 */
export function extractJsonStringField(body: string, field: string): string | null {
  const key = JSON.stringify(field);
  const keyAt = body.indexOf(key);
  if (keyAt < 0) {
    return null;
  }
  const colon = body.indexOf(':', keyAt + key.length);
  const valueStart = body.indexOf('"', colon + 1) + 1;
  const valueEnd = body.indexOf('"', valueStart);
  if (colon < 0 || valueStart <= 0 || valueEnd <= valueStart) {
    return null;
  }
  return body.substring(valueStart, valueEnd);
}

/**
 * Page Object for the DH Online Booking SPA (React, hash routing), shared by
 * its three services (see the ServiceProfile values above).
 *
 * The SPA renders the captcha as an <img> fed by an XHR response, so the
 * answer is captured on the network layer: the ACT/test backend echoes it in
 * the captcha response ("captchaForACT"). The login verdict is likewise read
 * from the wire (respCode), because the SPA renders localized error text.
 */
export class OnlineBookingPage {
  readonly page: Page;

  private readonly profile: ServiceProfile;

  private readonly usernameInput: Locator;

  private readonly passwordInput: Locator;

  private readonly captchaInput: Locator;

  private readonly loginButton: Locator;

  private readonly reloadCaptchaButton: Locator;

  private readonly serviceOption: Locator;

  /** Logout may render as a link or a button, with a language-dependent label. */
  private readonly logoutTrigger: Locator;

  private readonly captchaQueue: CaptchaPayload[] = [];

  private readonly captchaWaiters: CaptchaWaiter[] = [];

  /** Backend API calls that answered with a server error (for self-explanatory failures). */
  private readonly serverErrors: string[] = [];

  constructor(page: Page, profile: ServiceProfile) {
    this.page = page;
    this.profile = profile;
    this.usernameInput = page.locator('//*[@id="login_name"]');
    this.passwordInput = page.locator('//*[@id="login_password"]');
    this.captchaInput = page.locator('//*[@id="captchaInput"]');
    this.loginButton = page.locator('//*[@id="login_button"]/span');
    this.reloadCaptchaButton = page.locator('#reloadImage').first();
    this.serviceOption = page.getByText(profile.serviceOptionText).first();
    this.logoutTrigger = page.locator(
      'button:has-text("Logout"), a:has-text("Logout"),'
        + ' button:has-text("登出"), a:has-text("登出"),'
        + ' button:has-text("退出"), a:has-text("退出")',
    ).first();
    page.on('response', this.handleResponse);
  }

  private handleResponse = async (response: Response): Promise<void> => {
    const url = response.url();
    if (response.status() >= 500) {
      this.serverErrors.push(`HTTP ${response.status()} ${response.request().method()} ${pathnameOf(url)}`);
    }
    if (url.includes(CAPTCHA_API)) {
      try {
        const body = await response.text();
        // Only a response that carries an image is worth queueing.
        if (body.includes('captchaData')) {
          const payload: CaptchaPayload = { actAnswer: extractJsonStringField(body, 'captchaForACT') };
          const waiter = this.captchaWaiters.shift();
          if (waiter) {
            clearTimeout(waiter.timer);
            waiter.resolve(payload);
          } else {
            this.captchaQueue.push(payload);
          }
        }
      } catch {
        // Body may be unavailable for cached/duplicated responses.
      }
    } else if (url.includes(LOGIN_API)) {
      // Informational only: the authoritative read is submitAndWaitForLoginResponse().
      console.log(`[OBPage] loginWithSam response HTTP ${response.status()}`);
    }
  };

  /**
   * Opens the SPA through the service-selection root route: navigates to
   * #/login, clicks the service card and waits for the service's login form
   * to render (the SPA lazy-loads it after the route change). Throws when the
   * form does not appear or the browser is not on the login route.
   */
  async open(): Promise<void> {
    await this.page.goto(SERVICE_SELECTION_URL, { waitUntil: 'domcontentloaded' });
    // Give the SPA a chance to finish bootstrapping; analytics/long-poll
    // requests may keep the network busy, so the element waits below are the
    // authoritative check.
    try {
      await this.page.waitForLoadState('networkidle', { timeout: 15_000 });
    } catch {
      // Ignored on purpose.
    }
    try {
      await this.serviceOption.waitFor({ timeout: 20_000 });
    } catch (error) {
      throw new Error(this.describeStartupFailure(`the service picker did not show "${this.profile.serviceOptionText}" within 20 s`), { cause: error });
    }
    await this.serviceOption.click();
    try {
      await this.usernameInput.waitFor({ timeout: 20_000 });
    } catch (error) {
      throw new Error(this.describeStartupFailure(`the ${this.profile.name} login form did not appear within 20 s after selecting the service`), { cause: error });
    }
    if (!this.page.url().includes(this.profile.loginRouteHash)) {
      throw new Error(
        `Expected the ${this.profile.loginRouteHash} route after selecting ${this.profile.name}, but at: ${this.page.url()}`,
      );
    }
  }

  /**
   * Explains why the SPA did not come up: where the browser ended up (the
   * app itself sends it to #/notFound when its bootstrap APIs fail) and
   * which backend calls answered with a server error.
   */
  private describeStartupFailure(what: string): string {
    const route = this.page.url().includes('#') ? this.page.url().substring(this.page.url().indexOf('#')) : '(no hash route)';
    const place = route === '#/notFound'
      ? `${route} — the SPA itself gave up (its bootstrap APIs failed)`
      : route;
    const errors = [...new Set(this.serverErrors)];
    const backend = errors.length > 0
      ? `Backend API errors seen: ${errors.slice(0, 5).join(', ')}${errors.length > 5 ? `, …(+${errors.length - 5})` : ''}`
      : 'No backend API answered with a server error (5xx)';
    return `${what}. Landed on: ${place}. ${backend}.`;
  }

  async fillUsername(username: string): Promise<this> {
    await this.usernameInput.fill(username);
    return this;
  }

  async fillPassword(password: string): Promise<this> {
    await this.passwordInput.fill(password);
    return this;
  }

  async fillCaptcha(answer: string): Promise<this> {
    await this.captchaInput.fill(answer);
    return this;
  }

  /**
   * Clicks Login and waits for the loginWithSam response (rejects with a
   * Playwright timeout when the backend does not answer in time). The wait is
   * armed before the click so that a fast reply cannot be missed; Promise.all
   * also keeps the wait handled when the click itself fails.
   */
  async submitAndWaitForLoginResponse(timeoutMs: number): Promise<Response> {
    const [response] = await Promise.all([
      this.page.waitForResponse(
        (candidate) => candidate.url().includes(LOGIN_API),
        { timeout: timeoutMs },
      ),
      this.loginButton.click(),
    ]);
    return response;
  }

  async isLoginFormVisible(): Promise<boolean> {
    return (await this.usernameInput.isVisible())
      && (await this.passwordInput.isVisible())
      && (await this.loginButton.isVisible());
  }

  async isCaptchaVisible(): Promise<boolean> {
    return this.captchaInput.isVisible();
  }

  /** True when the SPA navigated away from the login route (i.e. login succeeded). */
  hasLeftLoginPage(): boolean {
    return !this.page.url().includes(this.profile.loginRouteHash);
  }

  currentUrl(): string {
    return this.page.url();
  }

  /** Hash route of this service's login form. */
  get loginRouteHash(): string {
    return this.profile.loginRouteHash;
  }

  /** Short name of the service, for log lines. */
  get serviceName(): string {
    return this.profile.name;
  }

  // ---------- Captcha ----------

  /**
   * Waits for the next captcha seen on the network and returns it, draining
   * the queue to the latest one: after a failed login the SPA fetches a new
   * captcha itself, so several images can be in flight, and the image on
   * screen is always the LAST one received. Returns null when nothing arrives.
   */
  async solveCurrentCaptcha(waitMs: number): Promise<CaptchaPayload | null> {
    const first = await this.pollCaptcha(waitMs);
    if (!first) {
      return null;
    }
    let latest = first;
    let deadline = Date.now() + SETTLE_MS;
    while (Date.now() < deadline) {
      const newer = await this.pollCaptcha(deadline - Date.now());
      if (newer) {
        latest = newer;
        deadline = Date.now() + SETTLE_MS;
      }
    }
    return latest;
  }

  /** Resolves with the next queued/arriving captcha, or null after `timeoutMs`. */
  private pollCaptcha(timeoutMs: number): Promise<CaptchaPayload | null> {
    const queued = this.captchaQueue.shift();
    if (queued) {
      return Promise.resolve(queued);
    }
    if (timeoutMs <= 0) {
      return Promise.resolve(null);
    }
    return new Promise<CaptchaPayload | null>((resolve) => {
      let waiter: CaptchaWaiter;
      const timer = setTimeout(() => {
        const index = this.captchaWaiters.indexOf(waiter);
        if (index >= 0) {
          this.captchaWaiters.splice(index, 1);
        }
        resolve(null);
      }, timeoutMs);
      waiter = { timer, resolve };
      this.captchaWaiters.push(waiter);
    });
  }

  /** Clicks "Renew the image" to request a fresh captcha. */
  async reloadCaptcha(): Promise<void> {
    this.captchaQueue.length = 0;
    await this.reloadCaptchaButton.click();
  }

  // ---------- Post-login journey helpers ----------

  /** Waits until the given text is rendered somewhere on the page (throws on timeout). */
  async waitForText(text: string, timeoutMs: number): Promise<void> {
    await this.page.getByText(text).first().waitFor({ timeout: timeoutMs });
  }

  /** Navigates to a hash-route URL (the React app re-renders without reloading). */
  async openRoute(url: string): Promise<void> {
    await this.page.goto(url, { waitUntil: 'domcontentloaded' });
  }

  /** Navigates to a hash-route URL and waits for its marker text: the real "page is ready" cue. */
  async openRouteAndWaitFor(url: string, markerText: string): Promise<void> {
    await this.openRoute(url);
    await this.waitForText(markerText, 20_000);
  }

  /** Waits until at least one image is visible on the page. */
  async waitForAnyImage(timeoutMs: number): Promise<void> {
    await this.page.locator('img:visible').first().waitFor({ timeout: timeoutMs });
  }

  /**
   * Switches the UI language from the header button (i18next swaps the texts
   * in place, no navigation). With `homeMarker` set, the marker wait is the
   * readiness cue; otherwise a short settle wait follows (for pages whose
   * readiness cue is an image rather than text).
   */
  async switchLanguage(language: ChineseLanguage, homeMarker?: string): Promise<void> {
    await this.page.locator(LANGUAGE_BUTTON[language]).click();
    if (homeMarker) {
      await this.waitForText(homeMarker, 20_000);
    } else {
      await this.page.waitForTimeout(1_500);
    }
  }

  /** Clicks the "Next" button of the given UI language. */
  async clickNext(language: UiLanguage): Promise<void> {
    await this.page.getByRole('button', { name: NEXT_BUTTON[language], exact: true }).first().click();
  }

  /** Clicks the "Back" / "返回" button of the given UI language. */
  async clickBack(language: UiLanguage): Promise<void> {
    await this.page.getByRole('button', { name: BACK_BUTTON[language], exact: true }).first().click();
  }

  /** Waits until the URL contains the given text (throws on timeout). */
  async waitForUrlContains(part: string, timeoutMs: number): Promise<void> {
    await this.page.waitForURL((url) => url.href.includes(part), { timeout: timeoutMs });
  }

  /**
   * Clicks "Logout" and waits for the SPA to return to the login route,
   * falling back to waiting for the login form itself if the URL check times
   * out (e.g. the app logs out via a full reload).
   */
  async logout(): Promise<void> {
    await this.logoutTrigger.click();
    try {
      await this.waitForUrlContains(this.profile.loginRouteHash, 20_000);
    } catch {
      await this.usernameInput.waitFor({ timeout: 10_000 });
    }
  }

  /** True when the browser is on the given hash route (ignoring a trailing slash and case). */
  isOnRoute(route: string): boolean {
    const url = this.page.url();
    const hash = url.includes('#') ? url.substring(url.indexOf('#') + 1) : '';
    return (hash.endsWith('/') ? hash.substring(0, hash.length - 1) : hash).toLowerCase() === route.toLowerCase();
  }
}
