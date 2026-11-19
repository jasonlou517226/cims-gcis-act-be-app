package com.gcis.pages;

import com.gcis.support.CaptchaOcr;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Page Object for the DH Online Booking SPA (React, hash routing).
 *
 * Target: https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/FHS-CH/login
 * (reached via the service-selection root route {@code #/login}, where the
 * "Child Health Service" option routes to the FHS-CH login form)
 *
 * Notes:
 * - The SPA is a React app served from a single index.html; real "pages" are
 *   hash routes (e.g. #/FHS-CH/login). Navigation therefore means: goto the
 *   base URL with the hash, then wait for the React app to render.
 * - The login form posts to /online-booking-user/loginWithSam with a captcha
 *   (captchaKey + captchaAns). The captcha answer is solved by
 *   {@link #solveCurrentCaptcha(long)}: the ACT/test backend conveniently
 *   echoes the answer in the response (captchaForACT) and it is used
 *   directly; environments without that field fall back to OCR
 *   (Vision/Tesseract via {@link CaptchaOcr}) with retries.
 * - After login the smoke journey ({@code main()}) walks the FHS-CH routes in
 *   English first (Home → Change Password + "Back" → Home → What's New +
 *   "Next" → Home), then switches the UI to Traditional Chinese via the
 *   header language button (stable id {@code btn_zh-HK}) and repeats the
 *   walk (返回 / 下一頁), then to Simplified Chinese ({@code btn_zh-CN},
 *   返回 / 下一页), and finally logs out.
 */
public class OnlineBookingLoginPage {

    public static final String URL = "https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/FHS-CH/login";

    /** Root service-selection login route and the FHS-CH option label on it. */
    public static final String SERVICE_SELECTION_URL = "https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/login";
    public static final String CHILD_HEALTH_SERVICE_TEXT = "Child Health Service";

    /** Post-login hash routes covered by the smoke journey. */
    public static final String HOME_URL = "https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/FHS-CH";
    public static final String CHANGE_PASSWORD_URL = HOME_URL + "/changePassword";
    public static final String LATEST_NEWS_URL = HOME_URL + "/latestNews";

    /** English marker texts that prove each route rendered successfully (exact UI copy). */
    public static final String HOME_MARKER_TEXT = "You can make appointment for the following services:";
    public static final String CHANGE_PASSWORD_MARKER_TEXT = "Change Password";
    public static final String LATEST_NEWS_MARKER_TEXT =
            "Service adjustment of Maternal and Child Health Centres (MCHCs):";

    /** Traditional Chinese (zh-HK) marker texts for the same routes. */
    public static final String HOME_MARKER_TEXT_ZH = "你可以預約以下服務";
    public static final String CHANGE_PASSWORD_MARKER_TEXT_ZH = "更改密碼";
    public static final String LATEST_NEWS_MARKER_TEXT_ZH = "母嬰健康院調整服務";

    /** Simplified Chinese (zh-CN) marker texts for the same routes. */
    public static final String HOME_MARKER_TEXT_SC = "你可以预约以下服务";
    public static final String CHANGE_PASSWORD_MARKER_TEXT_SC = "更改密码";
    public static final String LATEST_NEWS_MARKER_TEXT_SC = "母婴健康院调整服务";

    private static final String LOGIN_ROUTE_HASH = "#/FHS-CH/login";

    /** Backend API paths used by the SPA (matched against on-the-wire response URLs). */
    public static final String CAPTCHA_API = "/online-booking-user/generateCaptcha/image";
    public static final String LOGIN_API = "/online-booking-user/loginWithSam";

    /** Backend respCode values observed on the login API. */
    public static final int RESP_OK = 0;
    public static final int RESP_CAPTCHA_FAIL = 113;

    private final Page page;

    // Locators (MUI inputs rendered by the React SPA)
    private final Locator usernameInput;
    private final Locator passwordInput;
    private final Locator captchaInput;
    private final Locator loginButton;
    private final Locator reloadCaptchaButton;

    // Post-login journey controls (matched by accessible name / label text)
    private final Locator backButton;
    private final Locator nextButton;
    private final Locator logoutTrigger;
    private final Locator backButtonZh;
    private final Locator nextButtonZh;
    private final Locator zhLanguageButton;
    private final Locator nextButtonSc;
    private final Locator scLanguageButton;
    private final Locator childHealthServiceOption;

    /** A captcha seen on the wire: the image payload plus the test-env answer. */
    private record Captcha(String imageBase64, String actAnswer) {
    }

    /** Latest captcha payloads seen on the wire (image + test-env answer). */
    private final BlockingQueue<Captcha> captchaPayloads = new LinkedBlockingQueue<>();

    /** How long the queue must stay quiet before its newest payload is considered "on screen". */
    private static final long SETTLE_MILLIS = 800;

    /** Result of the most recent loginWithSam call (respCode / errMsg from JSON body). */
    private volatile Integer lastLoginRespCode;
    private volatile String lastLoginErrMsg;

    public OnlineBookingLoginPage(Page page) {
        this.page = page;
        // ID-based XPath locators for the Online Booking SPA login form
        this.usernameInput = page.locator("//*[@id=\"login_name\"]");
        this.passwordInput = page.locator("//*[@id=\"login_password\"]");
        this.captchaInput = page.locator("//*[@id=\"captchaInput\"]");
        this.loginButton = page.locator("//*[@id=\"login_button\"]/span");
        this.reloadCaptchaButton = page.locator("#reloadImage").first();

        // Journey controls: MUI buttons carry their label in a child <span>,
        // so accessible-name matching beats id guessing.
        this.backButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Back").setExact(true)).first();
        this.nextButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Next").setExact(true)).first();

        // Chinese (zh-HK) journey controls, used after the language switch.
        this.backButtonZh = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("返回").setExact(true)).first();
        this.nextButtonZh = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("下一頁").setExact(true)).first();
        // The SPA header renders one language button per supported language
        // with a stable id (btn_zh-HK / btn_zh-CN / btn_en, aria-label
        // "繁體中文" / "简体中文" / "English"); i18next swaps all texts in
        // place on click. Note "返回" is written identically in both Chinese
        // variants, so backButtonZh is reused for the Simplified walk.
        this.zhLanguageButton = page.locator("#btn_zh-HK");
        this.nextButtonSc = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("下一页").setExact(true)).first();
        this.scLanguageButton = page.locator("#btn_zh-CN");

        // The root #/login route shows a service picker; its labels come from
        // the backend translations (e.g. "Child Health Service Booking
        // System"), so substring text matching is used. Clicking the card
        // routes to #/FHS-CH/login.
        this.childHealthServiceOption = page.getByText(CHILD_HEALTH_SERVICE_TEXT).first();

        // Logout may render as a link or a button, and its label depends on
        // the UI language (Logout / 登出 / 退出).
        this.logoutTrigger = page.locator(
                "button:has-text(\"Logout\"), a:has-text(\"Logout\"),"
                        + " button:has-text(\"登出\"), a:has-text(\"登出\"),"
                        + " button:has-text(\"退出\"), a:has-text(\"退出\")").first();

        // The SPA renders the captcha as an <img> fed by an XHR response; the
        // raw base64 only exists on the network layer, so capture it here.
        // Login results are also read from the wire (respCode) because the SPA
        // renders localized (Chinese) error text that is brittle to match.
        page.onResponse(response -> {
            String url = response.url();
            if (url.contains(CAPTCHA_API)) {
                try {
                    String body = response.text();
                    String base64 = extractCaptchaBase64(body);
                    // The ACT/test backend echoes the answer (captchaForACT);
                    // production-like environments do not — OCR stays the
                    // fallback for those.
                    String act = extractJsonStringField(body, "captchaForACT");
                    if (base64 != null) {
                        captchaPayloads.offer(new Captcha(base64, act));
                    }
                } catch (PlaywrightException ignored) {
                    // body may be unavailable for cached/duplicated responses
                }
            } else if (url.contains(LOGIN_API)) {
                // Kept for debugging only; the authoritative read is
                // submitAndWaitForLoginResponse(), which runs on the test
                // thread where response.text() cannot deadlock.
                System.out.println("[OBPage] loginWithSam response HTTP " + response.status());
            }
        });
    }

    /** Parses an integer JSON field like "respCode":113 from a raw body. */
    private static Integer extractIntField(String body, String field) {
        int i = body.indexOf(field);
        if (i < 0) {
            return null;
        }
        int colon = body.indexOf(":", i + field.length());
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
            end++;
        }
        try {
            return Integer.valueOf(body.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts the raw base64 JPEG from a generateCaptcha/image JSON body.
     * The payload is a data URI whose base64 part contains JSON-escaped
     * newlines (\n as two literal chars), which must be unescaped first.
     */
    private static String extractCaptchaBase64(String body) {
        int i = body.indexOf("captchaData");
        if (i < 0) {
            return null;
        }
        int start = body.indexOf("\"", body.indexOf(":", i) + 1) + 1;
        int end = body.indexOf("\"", start);
        if (start <= 0 || end <= start) {
            return null;
        }
        String dataUri = unescapeJson(body.substring(start, end));
        String prefix = "data:image/jpeg;base64,";
        return dataUri.startsWith(prefix) ? dataUri.substring(prefix.length()) : dataUri;
    }

    /** Minimal JSON string unescape for the escapes the captcha API uses. */
    private static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');   // dropped later as whitespace
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case '/' -> out.append('/');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Extracts a plain (non-escaped) JSON string field like
     * "captchaForACT":"AB12" from a raw body, or null when absent/empty.
     */
    private static String extractJsonStringField(String body, String field) {
        int i = body.indexOf("\"" + field + "\"");
        if (i < 0) {
            return null;
        }
        int start = body.indexOf("\"", body.indexOf(":", i + field.length() + 2) + 1) + 1;
        int end = body.indexOf("\"", start);
        if (start <= 0 || end <= start) {
            return null;
        }
        return body.substring(start, end);
    }

    /**
     * Opens the SPA through the service-selection root route: navigates to
     * {@code #/login}, clicks the "Child Health Service" option and waits for
     * the FHS-CH login form to render (the SPA lazy-loads the service's
     * micro-app after the route change). Throws when the login form does not
     * appear or the browser does not end up on the FHS-CH login route.
     */
    public void open() {
        page.navigate(SERVICE_SELECTION_URL, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        // Wait for the SPA to finish bootstrapping and render the service picker
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(15_000));
        } catch (PlaywrightException ignored) {
            // Some analytics/long-poll requests may keep the network busy;
            // the element waits below are the authoritative check.
        }
        childHealthServiceOption.waitFor(new Locator.WaitForOptions().setTimeout(20_000));
        childHealthServiceOption.click();
        usernameInput.waitFor(new Locator.WaitForOptions().setTimeout(20_000));
        if (!page.url().contains(LOGIN_ROUTE_HASH)) {
            throw new IllegalStateException("Expected the FHS-CH login route after selecting"
                    + " Child Health Service, but at: " + page.url());
        }
    }

    public OnlineBookingLoginPage fillUsername(String username) {
        usernameInput.fill(username);
        return this;
    }

    public OnlineBookingLoginPage fillPassword(String password) {
        passwordInput.fill(password);
        return this;
    }

    public OnlineBookingLoginPage fillCaptcha(String answer) {
        captchaInput.fill(answer);
        return this;
    }

    /** Clicks the Login button (will fail captcha unless the answer is correct). */
    public void submit() {
        loginButton.click();
    }

    /**
     * Clicks Login and waits for the loginWithSam response, pumping the
     * Playwright event loop while waiting (unlike a Thread.sleep poll, this
     * lets response listeners run). Returns the raw Response, or throws
     * PlaywrightException on timeout.
     */
    public Response submitAndWaitForLoginResponse(long timeoutMillis) {
        return page.waitForResponse(
                r -> r.url().contains(LOGIN_API),
                new Page.WaitForResponseOptions().setTimeout(timeoutMillis),
                () -> loginButton.click());
    }

    /** respCode field of a loginWithSam JSON body (null when absent). */
    public static Integer respCodeOf(String body) {
        return extractIntField(body, "respCode");
    }

    /** errMsg field of a loginWithSam JSON body (null when absent). */
    public static String errMsgOf(String body) {
        int i = body.indexOf("errMsg");
        if (i < 0) {
            return null;
        }
        int start = body.indexOf("\"", body.indexOf(":", i) + 1) + 1;
        int end = body.indexOf("\"", start);
        return (start > 0 && end > start) ? body.substring(start, end) : null;
    }

    // ---------- Assertion helpers ----------

    public String currentPageTitle() {
        return page.title();
    }

    public String currentUrl() {
        return page.url();
    }

    public boolean isLoginFormVisible() {
        return usernameInput.isVisible() && passwordInput.isVisible()
                && loginButton.isVisible();
    }

    public boolean isCaptchaVisible() {
        return captchaInput.isVisible();
    }

    /** Convenience accessor for request/response debugging in tests. */
    public Page page() {
        return page;
    }

    // ---------- Captcha solving ----------

    /**
     * Waits for the next captcha seen on the network and returns its answer.
     * On the ACT/test backend the answer rides along in the response
     * ({@code captchaForACT}) and is used directly (deterministic, ~100%);
     * otherwise the image is OCR'd (best-effort, see {@link CaptchaOcr}) and
     * the recognized (cleaned, upper-cased) text is returned. Returns
     * {@code null} if no payload arrives or nothing could be read.
     */
    public String solveCurrentCaptcha(long waitMillis) throws InterruptedException {
        Captcha latest = captchaPayloads.poll(waitMillis, TimeUnit.MILLISECONDS);
        if (latest == null) {
            return null;
        }
        // Drain to the latest payload: after a failed login the SPA fetches a
        // new captcha itself, and the test may also click "renew", so several
        // images can be in flight. The image on screen is always the LAST one
        // received — OCR anything older and the answer can never match.
        long deadline = System.currentTimeMillis() + SETTLE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Captcha newer = captchaPayloads.poll(
                    deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (newer != null) {
                latest = newer;
                deadline = System.currentTimeMillis() + SETTLE_MILLIS;
            }
        }
        if (latest.actAnswer() != null && !latest.actAnswer().isBlank()) {
            System.out.println("[captcha] using captchaForACT from the test backend: "
                    + latest.actAnswer());
            return latest.actAnswer();
        }
        return CaptchaOcr.solveClean(latest.imageBase64());
    }

    /** Clicks "Renew the image" to request a fresh captcha (also consumed by {@link #solveCurrentCaptcha(long)}). */
    public void reloadCaptcha() {
        captchaPayloads.clear();
        reloadCaptchaButton.click();
    }

    /**
     * Waits up to {@code waitMillis} for a loginWithSam response captured on
     * the wire and reports its backend respCode, or {@code null} on timeout.
     * Clear previous result before submitting (see {@link #resetLoginResult()}).
     */
    public Integer waitLoginRespCode(long waitMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (System.currentTimeMillis() < deadline) {
            if (lastLoginRespCode != null) {
                return lastLoginRespCode;
            }
            Thread.sleep(200);
        }
        return lastLoginRespCode;
    }

    /** errMsg from the last loginWithSam response (may be null). */
    public String lastLoginErrMsg() {
        return lastLoginErrMsg;
    }

    /** Clears the captured login result so the next submit can be awaited. */
    public void resetLoginResult() {
        lastLoginRespCode = null;
        lastLoginErrMsg = null;
    }

    /** True when the SPA navigated away from the login route (i.e. login succeeded). */
    public boolean hasLeftLoginPage() {
        return !page.url().contains(LOGIN_ROUTE_HASH);
    }

    // ---------- Post-login journey (Home / Change Password / What's New / Logout) ----------

    /** Waits until the given text is rendered somewhere on the page (throws on timeout). */
    public void waitForText(String text, long timeoutMillis) {
        page.getByText(text).first()
                .waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis));
    }

    /** True when the given text is currently rendered on the page. */
    public boolean isTextVisible(String text) {
        return page.getByText(text).first().isVisible();
    }

    /**
     * Navigates the SPA to the given hash-route URL and waits for its marker
     * text to render. Hash navigation does not reload the document; the React
     * app re-renders, so the marker wait is the real "page is ready" cue.
     */
    public OnlineBookingLoginPage openRouteAndWaitFor(String url, String markerText) {
        page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        waitForText(markerText, 20_000);
        return this;
    }

    /** Opens the Home route and waits for the services-menu marker text. */
    public OnlineBookingLoginPage openHome() {
        return openRouteAndWaitFor(HOME_URL, HOME_MARKER_TEXT);
    }

    /** Opens the Change Password route and waits for its heading marker text. */
    public OnlineBookingLoginPage openChangePassword() {
        return openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER_TEXT);
    }

    /** Opens the What's New route and waits for its first news-item marker text. */
    public OnlineBookingLoginPage openLatestNews() {
        return openRouteAndWaitFor(LATEST_NEWS_URL, LATEST_NEWS_MARKER_TEXT);
    }

    /** Opens the Change Password route and waits for the Chinese heading marker. */
    public OnlineBookingLoginPage openChangePasswordChinese() {
        return openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER_TEXT_ZH);
    }

    /** Opens the What's New route and waits for the Chinese news-item marker. */
    public OnlineBookingLoginPage openLatestNewsChinese() {
        return openRouteAndWaitFor(LATEST_NEWS_URL, LATEST_NEWS_MARKER_TEXT_ZH);
    }

    /** Clicks the "Back" button (Change Password page returns to Home). */
    public void clickBack() {
        backButton.click();
    }

    /** Clicks the "Next" button (What's New page returns to Home). */
    public void clickNext() {
        nextButton.click();
    }

    /** Clicks the "返回" button (Chinese Change Password page returns to Home). */
    public void clickBackChinese() {
        backButtonZh.click();
    }

    /** Clicks the "下一頁" button (Chinese What's New page returns to Home). */
    public void clickNextChinese() {
        nextButtonZh.click();
    }

    /**
     * Switches the UI language to Traditional Chinese by clicking the header
     * language button (stable id {@code btn_zh-HK}, label "繁"), then waits
     * for the home page to re-render in Chinese. i18next swaps the texts in
     * place (no navigation), so the Chinese marker wait is the readiness cue.
     * Call this while on the home route.
     */
    public void switchToTraditionalChinese() {
        zhLanguageButton.click();
        waitForText(HOME_MARKER_TEXT_ZH, 20_000);
    }

    /** Opens the Change Password route and waits for the Simplified Chinese heading marker. */
    public OnlineBookingLoginPage openChangePasswordSimplifiedChinese() {
        return openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER_TEXT_SC);
    }

    /** Opens the What's New route and waits for the Simplified Chinese news-item marker. */
    public OnlineBookingLoginPage openLatestNewsSimplifiedChinese() {
        return openRouteAndWaitFor(LATEST_NEWS_URL, LATEST_NEWS_MARKER_TEXT_SC);
    }

    /** Clicks the "下一页" button (Simplified Chinese What's New page returns to Home). */
    public void clickNextSimplifiedChinese() {
        nextButtonSc.click();
    }

    /**
     * Switches the UI language to Simplified Chinese by clicking the header
     * language button (stable id {@code btn_zh-CN}, label "简"), then waits
     * for the home page to re-render in Simplified Chinese. Call this while
     * on the home route.
     */
    public void switchToSimplifiedChinese() {
        scLanguageButton.click();
        waitForText(HOME_MARKER_TEXT_SC, 20_000);
    }

    /**
     * Clicks "Logout" and waits for the SPA to return to the login route,
     * falling back to waiting for the login form itself if the URL check
     * times out (e.g. the app logs out via a full reload).
     */
    public void logout() {
        logoutTrigger.click();
        try {
            page.waitForURL(url -> url.toString().contains(LOGIN_ROUTE_HASH),
                    new Page.WaitForURLOptions().setTimeout(20_000));
        } catch (PlaywrightException fallbackToForm) {
            usernameInput.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
        }
    }

    /** True when the browser is on the given hash route (ignoring a trailing slash). */
    public boolean isOnRoute(String route) {
        String url = page.url();
        String hash = url.contains("#") ? url.substring(url.indexOf('#') + 1) : "";
        if (hash.endsWith("/")) {
            hash = hash.substring(0, hash.length() - 1);
        }
        return hash.equalsIgnoreCase(route);
    }

    /**
     * Runs the full post-login journey and throws {@link IllegalStateException}
     * on the first failed step (usable from both JUnit tests and {@code main()}):
     *
     *   English part:
     *   1. Home (#/FHS-CH)                  — "You can make appointment for the following services:"
     *   2. Change Password (/changePassword) — "Change Password", then press "Back"
     *   3. Home again                       — services marker still shown
     *   4. What's New (/latestNews)         — "Service adjustment of Maternal and Child Health
     *                                        Centres (MCHCs):", then press "Next"
     *   5. Home again                       — services marker still shown
     *
     *   Traditional Chinese part:
     *   6. Switch language to zh-HK         — home shows "你可以預約以下服務"
     *   7. 更改密碼 (/changePassword)        — "更改密碼", then press "返回"
     *   8. Home again                       — "你可以預約以下服務" still shown
     *   9. 最新消息 (/latestNews)            — "母嬰健康院調整服務", then press "下一頁"
     *  10. Home again                       — "你可以預約以下服務" still shown
     *
     *   Simplified Chinese part:
     *  11. Switch language to zh-CN         — home shows "你可以预约以下服务"
     *  12. 更改密码 (/changePassword)        — "更改密码", then press "返回"
     *  13. Home again                       — "你可以预约以下服务" still shown
     *  14. 最新消息 (/latestNews)            — "母婴健康院调整服务", then press "下一页"
     *  15. Home again                       — "你可以预约以下服务" still shown
     *
     *  16. Logout                           — back on the login route
     */
    public void postLoginJourney() {
        waitForText(HOME_MARKER_TEXT, 20_000);
        journeyCheck("step 1: home shows the services marker (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        openChangePassword();
        journeyCheck("step 2: change-password page shows its marker (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH/changePassword"));
        clickBack();

        waitForText(HOME_MARKER_TEXT, 20_000);
        journeyCheck("step 3: back on home with the services marker (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        openLatestNews();
        journeyCheck("step 4: what's-new page shows its marker (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH/latestNews"));
        clickNext();

        waitForText(HOME_MARKER_TEXT, 20_000);
        journeyCheck("step 5: back on home with the services marker (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        switchToTraditionalChinese();
        journeyCheck("step 6: home re-rendered in Traditional Chinese (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        openChangePasswordChinese();
        journeyCheck("step 7: Chinese change-password page shows its marker (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH/changePassword"));
        clickBackChinese();

        waitForText(HOME_MARKER_TEXT_ZH, 20_000);
        journeyCheck("step 8: back on home, Chinese services marker shown (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        openLatestNewsChinese();
        journeyCheck("step 9: Chinese what's-new page shows its marker (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH/latestNews"));
        clickNextChinese();

        waitForText(HOME_MARKER_TEXT_ZH, 20_000);
        journeyCheck("step 10: back on home, Chinese services marker shown (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        switchToSimplifiedChinese();
        journeyCheck("step 11: home re-rendered in Simplified Chinese (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        openChangePasswordSimplifiedChinese();
        journeyCheck("step 12: Simplified Chinese change-password page shows its marker (URL: "
                + page.url() + ")", isOnRoute("/FHS-CH/changePassword"));
        clickBackChinese();   // "返回" is identical in Traditional and Simplified

        waitForText(HOME_MARKER_TEXT_SC, 20_000);
        journeyCheck("step 13: back on home, Simplified Chinese marker shown (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        openLatestNewsSimplifiedChinese();
        journeyCheck("step 14: Simplified Chinese what's-new page shows its marker (URL: "
                + page.url() + ")", isOnRoute("/FHS-CH/latestNews"));
        clickNextSimplifiedChinese();

        waitForText(HOME_MARKER_TEXT_SC, 20_000);
        journeyCheck("step 15: back on home, Simplified Chinese marker shown (URL: " + page.url() + ")",
                isOnRoute("/FHS-CH"));

        logout();
        journeyCheck("step 16: back on the login route after logout (URL: " + page.url() + ")",
                page.url().contains(LOGIN_ROUTE_HASH));
    }

    /** Records one journey check; throws (failing the run/test) when it does not hold. */
    private static void journeyCheck(String what, boolean ok) {
        if (!ok) {
            throw new IllegalStateException("Journey check failed: " + what);
        }
        System.out.println("[JOURNEY] ok: " + what);
    }

    // ---------- Standalone local runner ----------

    /**
     * Local smoke run of the full post-login journey — no JUnit required.
     * Launches Chromium, opens the service-selection root route
     * ({@code #/login}), selects "Child Health Service", logs in with a real
     * account (captcha solved via {@link #solveCurrentCaptcha(long)},
     * retrying like {@code OnlineBookingOcrLoginTest}), then walks the FHS-CH
     * routes in English (steps 1-5), Traditional Chinese (steps 6-10),
     * Simplified Chinese (steps 11-15) and finally logs out (step 16).
     *
     * Credentials (never hard-code secrets): -Dob.login=... -Dob.password=...
     * or OB_LOGIN / OB_PASSWORD env vars; the run aborts when they are absent.
     *
     * Run with Maven exec (test scope includes this class):
     *   mvn test-compile exec:java \
     *       -Dexec.mainClass=com.gcis.pages.OnlineBookingLoginPage \
     *       -Dexec.classpathScope=test
     * or simply run {@code main()} from the IDE. Flags mirror TestBase:
     * {@code -Dheaded=true} (fullscreen browser), {@code -Dslowmo=true},
     * {@code -Dslowmo.ms=N}, {@code -Dob.attempts=N} (captcha budget).
     * Exits 0 when every check passes, 1 otherwise.
     */
    public static void main(String[] args) {
        boolean headless = !"true".equalsIgnoreCase(System.getProperty("headed", "false"));
        boolean slowMo = "true".equalsIgnoreCase(System.getProperty("slowmo", "false"));

        String login = firstNonBlank(System.getProperty("ob.login"), System.getenv("OB_LOGIN"));
        String password = firstNonBlank(System.getProperty("ob.password"), System.getenv("OB_PASSWORD"));
        if (login == null || password == null) {
            System.out.println("[SMOKE] FAILED: the full journey needs real credentials — set"
                    + " -Dob.login/-Dob.password or OB_LOGIN/OB_PASSWORD env vars");
            System.exit(2);
        }

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(headless);
            if (slowMo) {
                options.setSlowMo(Integer.getInteger("slowmo.ms", 2_000));
            }
            if (!headless) {
                // Watch mode: open the browser window in full screen for easy viewing.
                options.setArgs(List.of("--start-fullscreen"));
            }
            Browser browser = playwright.chromium().launch(options);
            // Government site with self-signed/odd certificates; ignore HTTPS errors.
            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
            Page page = context.newPage();
            page.setDefaultTimeout(30_000);

            try {
                OnlineBookingLoginPage loginPage = new OnlineBookingLoginPage(page);
                loginPage.open();

                check("login form is visible", loginPage.isLoginFormVisible());
                check("captcha input is visible", loginPage.isCaptchaVisible());

                // ---- Login (captcha retry loop, mirrors OnlineBookingOcrLoginTest) ----
                final int maxAttempts = Integer.getInteger("ob.attempts", 5);
                boolean accepted = false;
                String failure = null;
                for (int attempt = 1; attempt <= maxAttempts && !accepted; attempt++) {
                    String answer = loginPage.solveCurrentCaptcha(20_000);
                    if (answer == null || answer.length() != 4) {
                        System.out.println("attempt " + attempt + ": captcha unreadable"
                                + (answer == null ? "" : " [" + answer + "]") + ", renewing image");
                        loginPage.reloadCaptcha();
                        continue;
                    }
                    System.out.println("attempt " + attempt + ": captcha answer = " + answer);
                    // Re-fill credentials every time: the SPA clears the form
                    // after a failed login attempt.
                    loginPage.fillUsername(login);
                    loginPage.fillPassword(password);
                    loginPage.fillCaptcha(answer);

                    Response resp;
                    try {
                        resp = loginPage.submitAndWaitForLoginResponse(20_000);
                    } catch (PlaywrightException timeout) {
                        if (loginPage.hasLeftLoginPage()) {
                            accepted = true;
                            break;
                        }
                        loginPage.reloadCaptcha();
                        continue;
                    }

                    String body = resp.text();
                    Integer respCode = OnlineBookingLoginPage.respCodeOf(body);
                    System.out.println("attempt " + attempt + ": respCode=" + respCode);
                    if (respCode == null) {
                        accepted = loginPage.hasLeftLoginPage();
                        if (!accepted) {
                            loginPage.reloadCaptcha();
                        }
                        continue;
                    }
                    if (respCode == RESP_CAPTCHA_FAIL) {
                        // The SPA auto-fetches a fresh captcha after 113; just retry.
                        continue;
                    }
                    if (respCode == RESP_OK) {
                        accepted = loginPage.hasLeftLoginPage();
                        if (!accepted) {
                            loginPage.page().waitForTimeout(3_000);
                            accepted = loginPage.hasLeftLoginPage();
                            if (!accepted) {
                                failure = "respCode 0 but still on the login route";
                            }
                        }
                        break;
                    }
                    // Captcha was right, backend rejected the credentials.
                    failure = "backend rejected login (" + errMsgOf(body) + ")";
                    break;
                }
                check("login accepted (maxAttempts=" + maxAttempts
                        + (failure == null ? "" : "; " + failure) + ")", accepted);

                // ---- Post-login journey: steps 1-6 (shared with OnlineBookingOcrLoginTest) ----
                loginPage.postLoginJourney();

                System.out.println("[SMOKE] OK — full journey passed: login → home → change password"
                        + " (Back) → home → what's new (Next) → home → 繁體中文 (更改密碼/返回,"
                        + " 最新消息/下一頁) → home → 简体中文 (更改密码/返回, 最新消息/下一页)"
                        + " → home → logout");
            } finally {
                context.close();
                browser.close();
            }
        } catch (Exception e) {
            System.out.println("[SMOKE] FAILED: " + e);
            System.exit(1);
        }
    }

    /** First non-blank value, or null when all are blank (env-var friendly). */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }

    /** Records one smoke check; throws (failing the run) when it does not hold. */
    private static void check(String what, boolean ok) {
        if (!ok) {
            throw new IllegalStateException("Check failed: " + what);
        }
        System.out.println("[SMOKE] ok: " + what);
    }
}
