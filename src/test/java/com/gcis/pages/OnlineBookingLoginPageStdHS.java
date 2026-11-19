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
 * Page Object for the DH Online Booking SPA — Student Health Service
 * (React, hash routing), LOGIN-ONLY. Highly mirrors
 * {@link OnlineBookingLoginPage} (Child Health Service); only the service
 * selection and routes differ.
 *
 * Target: https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/StdHS/login
 * (reached via the service-selection root route {@code #/login}, where the
 * "Student Health Service" option routes to the StdHS login form)
 *
 * Notes:
 * - The SPA is a React app served from a single index.html; real "pages" are
 *   hash routes (e.g. #/StdHS/login). Navigation therefore means: goto the
 *   base URL with the hash, then wait for the React app to render.
 * - The login form posts to /online-booking-user/loginWithSam with a captcha
 *   (captchaKey + captchaAns). The captcha answer is solved by
 *   {@link #solveCurrentCaptcha(long)}: the ACT/test backend conveniently
 *   echoes the answer in the response (captchaForACT) and it is used
 *   directly; environments without that field fall back to OCR
 *   (Vision/Tesseract via {@link CaptchaOcr}) with retries.
 * - The StdHS smoke ({@code main()}) logs in and then walks the StdHS pages
 *   in English (steps 17-22): Health Message (image) → View Health Assessment
 *   Findings and Recommendations (accessed) → What's New (iAM Smart notice,
 *   "Next" → back to Health Message) → Appointment home (reschedule
 *   footnote) → Change Personal Email → Change Password; then switches to
 *   Traditional Chinese ({@code btn_zh-HK}) and repeats the walk (steps
 *   23-28, 下一頁 / 更改個人電郵 / 更改密碼).
 */
public class OnlineBookingLoginPageStdHS {

    public static final String URL = "https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/StdHS/login";

    /** Root service-selection login route and the StdHS option label on it. */
    public static final String SERVICE_SELECTION_URL =
            "https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/login";
    public static final String STUDENT_HEALTH_SERVICE_TEXT = "Student Health Service";

    private static final String LOGIN_ROUTE_HASH = "#/StdHS/login";

    /** Post-login hash routes covered by the StdHS journey (steps 17-22). */
    public static final String HOME_URL = "https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/StdHS";
    public static final String HEALTH_URL = HOME_URL + "/spa/spa-wsmash/health";
    public static final String FINDING_AND_RECOMMENDATION_URL =
            HOME_URL + "/spa/spa-wsmash/findingAndRecommendation";
    public static final String LATEST_NEWS_URL = HOME_URL + "/latestNews";
    public static final String CHANGE_PROFILE_URL = HOME_URL + "/changeProfile";
    public static final String CHANGE_PASSWORD_URL = HOME_URL + "/changePassword";

    /** Marker texts that prove each page rendered successfully (exact UI copy). */
    public static final String LATEST_NEWS_MARKER_TEXT_1 = "To \"iAM Smart\" user:";
    public static final String LATEST_NEWS_MARKER_TEXT_2 =
            "Due to system update, please reset the linkage before login to Student Health"
                    + " Service through the \"iAM Smart\" platform.";
    public static final String APPT_FOOTNOTE_MARKER_TEXT =
            "The appointment can only be rescheduled within the coming 4 weeks."
                    + " If you wish to reschedule to a later date, please log in and try again later.";
    public static final String CHANGE_PROFILE_MARKER_TEXT = "Change Personal Email";
    public static final String CHANGE_PASSWORD_MARKER_TEXT = "Change Password";

    /** Traditional Chinese (zh-HK) marker texts for the same pages. */
    public static final String APPT_FOOTNOTE_MARKER_TEXT_ZH =
            "改期只可選擇未來四星期內的日期。如需改期到稍後的日期，請稍後登入再試。";
    public static final String CHANGE_PROFILE_MARKER_TEXT_ZH = "更改個人電郵";
    public static final String CHANGE_PASSWORD_MARKER_TEXT_ZH = "更改密碼";
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
    private final Locator studentHealthServiceOption;
    private final Locator nextButton;
    private final Locator nextButtonZh;
    private final Locator zhLanguageButton;

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

    public OnlineBookingLoginPageStdHS(Page page) {
        this.page = page;
        // ID-based XPath locators for the Online Booking SPA login form
        this.usernameInput = page.locator("//*[@id=\"login_name\"]");
        this.passwordInput = page.locator("//*[@id=\"login_password\"]");
        this.captchaInput = page.locator("//*[@id=\"captchaInput\"]");
        this.loginButton = page.locator("//*[@id=\"login_button\"]/span");
        this.reloadCaptchaButton = page.locator("#reloadImage").first();

        // The root #/login route shows a service picker; its labels come from
        // the backend translations (e.g. "Student Health Service Booking
        // System"), so substring text matching is used. Clicking the card
        // routes to #/StdHS/login.
        this.studentHealthServiceOption = page.getByText(STUDENT_HEALTH_SERVICE_TEXT).first();

        // What's New page "Next" button (MUI button, label in a child <span>,
        // so accessible-name matching beats id guessing).
        this.nextButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Next").setExact(true)).first();
        this.nextButtonZh = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("下一頁").setExact(true)).first();
        // The SPA header renders one language button per supported language
        // with a stable id (btn_zh-HK / btn_zh-CN / btn_en); i18next swaps all
        // texts in place on click.
        this.zhLanguageButton = page.locator("#btn_zh-HK");

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
     * {@code #/login}, clicks the "Student Health Service" option and waits
     * for the StdHS login form to render (the SPA lazy-loads the service's
     * micro-app after the route change). Throws when the login form does not
     * appear or the browser does not end up on the StdHS login route.
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
        studentHealthServiceOption.waitFor(new Locator.WaitForOptions().setTimeout(20_000));
        studentHealthServiceOption.click();
        usernameInput.waitFor(new Locator.WaitForOptions().setTimeout(20_000));
        if (!page.url().contains(LOGIN_ROUTE_HASH)) {
            throw new IllegalStateException("Expected the StdHS login route after selecting"
                    + " Student Health Service, but at: " + page.url());
        }
    }

    public OnlineBookingLoginPageStdHS fillUsername(String username) {
        usernameInput.fill(username);
        return this;
    }

    public OnlineBookingLoginPageStdHS fillPassword(String password) {
        passwordInput.fill(password);
        return this;
    }

    public OnlineBookingLoginPageStdHS fillCaptcha(String answer) {
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

    // ---------- Post-login journey (steps 17-22) ----------

    /** Waits until the given text is rendered somewhere on the page (throws on timeout). */
    public void waitForText(String text, long timeoutMillis) {
        page.getByText(text).first()
                .waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis));
    }

    /** True when the given text is currently rendered on the page. */
    public boolean isTextVisible(String text) {
        return page.getByText(text).first().isVisible();
    }

    /** Navigates the SPA to the given hash-route URL (the React app re-renders). */
    public OnlineBookingLoginPageStdHS openRoute(String url) {
        page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        return this;
    }

    /** Navigates to the given hash-route URL and waits for its marker text. */
    public OnlineBookingLoginPageStdHS openRouteAndWaitFor(String url, String markerText) {
        openRoute(url);
        waitForText(markerText, 20_000);
        return this;
    }

    /** Waits until at least one image is visible on the page (throws on timeout). */
    public void waitForAnyImage(long timeoutMillis) {
        page.locator("img:visible").first()
                .waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis));
    }

    /**
     * Switches the UI language to Traditional Chinese by clicking the header
     * language button (stable id {@code btn_zh-HK}, label "繁"). i18next swaps
     * the texts in place (no navigation); a short settle wait follows because
     * the health page's readiness cue is an image, not text — the following
     * marker/image waits are the authoritative checks.
     */
    public void switchToTraditionalChinese() {
        zhLanguageButton.click();
        page.waitForTimeout(1_500);
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

    /** Records one journey check; throws (failing the run/test) when it does not hold. */
    private static void journeyCheck(String what, boolean ok) {
        if (!ok) {
            throw new IllegalStateException("Journey check failed: " + what);
        }
        System.out.println("[JOURNEY] ok: " + what);
    }

    /**
     * Runs the StdHS post-login journey (steps 17-22) and throws
     * {@link IllegalStateException} on the first failed step:
     *
     *  17. Health Message (spa-wsmash/health) — an image is shown
     *  18. View Health Assessment Findings and Recommendations — page accessed
     *  19. What's New — iAM Smart notice shown, press "Next" → back on
     *      Health Message with an image
     *  20. Appointment home (#/StdHS) — reschedule footnote shown
     *  21. Change Personal Email (/changeProfile) — heading shown
     *  22. Change Password (/changePassword) — heading shown
     *
     *  Traditional Chinese part:
     *  23. Switch to zh-HK on Health Message — image shown
     *  24. 檢視個人健康檢查結果及建議 (findingAndRecommendation) — accessed
     *  25. 最新消息 (latestNews) — press "下一頁" → back on Health Message
     *      with an image
     *  26. 預約時間 home (#/StdHS) — Chinese reschedule footnote shown
     *  27. 更改個人電郵 (/changeProfile) — heading shown
     *  28. 更改密碼 (/changePassword) — heading shown
     */
    public void postLoginJourney() {
        openRoute(HEALTH_URL);
        waitForAnyImage(20_000);
        journeyCheck("step 17: health page shows an image (URL: " + page.url() + ")",
                isOnRoute("/StdHS/spa/spa-wsmash/health"));

        openRoute(FINDING_AND_RECOMMENDATION_URL);
        journeyCheck("step 18: findings page accessed (URL: " + page.url()
                + ")", isOnRoute("/StdHS/spa/spa-wsmash/findingAndRecommendation"));

        openRoute(LATEST_NEWS_URL);
        waitForText(LATEST_NEWS_MARKER_TEXT_1, 20_000);
        waitForText(LATEST_NEWS_MARKER_TEXT_2, 20_000);
        journeyCheck("step 19: what's-new shows the iAM Smart notice (URL: " + page.url()
                + ")", isOnRoute("/StdHS/latestNews"));
        nextButton.click();
        page.waitForURL(url -> url.toString().contains("#/StdHS/spa/spa-wsmash/health"),
                new Page.WaitForURLOptions().setTimeout(20_000));
        waitForAnyImage(20_000);
        journeyCheck("step 19b: \"Next\" leads back to the health page with an image (URL: "
                + page.url() + ")", isOnRoute("/StdHS/spa/spa-wsmash/health"));

        openRouteAndWaitFor(HOME_URL, APPT_FOOTNOTE_MARKER_TEXT);
        journeyCheck("step 20: appointment home shows the reschedule footnote (URL: "
                + page.url() + ")", isOnRoute("/StdHS"));

        openRouteAndWaitFor(CHANGE_PROFILE_URL, CHANGE_PROFILE_MARKER_TEXT);
        journeyCheck("step 21: change-profile page shows its heading (URL: "
                + page.url() + ")", isOnRoute("/StdHS/changeProfile"));

        openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER_TEXT);
        journeyCheck("step 22: change-password page shows its heading (URL: "
                + page.url() + ")", isOnRoute("/StdHS/changePassword"));

        // ---- Traditional Chinese walk (steps 23-28) ----
        openRoute(HEALTH_URL);
        switchToTraditionalChinese();
        waitForAnyImage(20_000);
        journeyCheck("step 23: health page in Traditional Chinese shows an image (URL: "
                + page.url() + ")", isOnRoute("/StdHS/spa/spa-wsmash/health"));

        openRoute(FINDING_AND_RECOMMENDATION_URL);
        journeyCheck("step 24: Chinese findings page accessed (URL: " + page.url()
                + ")", isOnRoute("/StdHS/spa/spa-wsmash/findingAndRecommendation"));

        openRoute(LATEST_NEWS_URL);
        nextButtonZh.click();
        page.waitForURL(url -> url.toString().contains("#/StdHS/spa/spa-wsmash/health"),
                new Page.WaitForURLOptions().setTimeout(20_000));
        waitForAnyImage(20_000);
        journeyCheck("step 25: \"下一頁\" leads back to the health page with an image (URL: "
                + page.url() + ")", isOnRoute("/StdHS/spa/spa-wsmash/health"));

        openRouteAndWaitFor(HOME_URL, APPT_FOOTNOTE_MARKER_TEXT_ZH);
        journeyCheck("step 26: Chinese appointment home shows the reschedule footnote (URL: "
                + page.url() + ")", isOnRoute("/StdHS"));

        openRouteAndWaitFor(CHANGE_PROFILE_URL, CHANGE_PROFILE_MARKER_TEXT_ZH);
        journeyCheck("step 27: Chinese change-profile page shows its heading (URL: "
                + page.url() + ")", isOnRoute("/StdHS/changeProfile"));

        openRouteAndWaitFor(CHANGE_PASSWORD_URL, CHANGE_PASSWORD_MARKER_TEXT_ZH);
        journeyCheck("step 28: Chinese change-password page shows its heading (URL: "
                + page.url() + ")", isOnRoute("/StdHS/changePassword"));
    }

    // ---------- Standalone local runner ----------

    /**
     * Local smoke run of the StdHS login + journey — no JUnit required.
     * Launches Chromium, opens the service-selection root route
     * ({@code #/login}), selects "Student Health Service", logs in with a
     * real account (captcha solved via {@link #solveCurrentCaptcha(long)},
     * retrying like {@code OnlineBookingOcrLoginTest}), then walks the StdHS
     * pages in English (steps 17-22) and Traditional Chinese (steps 23-28,
     * see {@link #postLoginJourney()}).
     *
     * Credentials (never hard-code secrets): -Dob.login=... -Dob.password=...
     * or STDHS_LOGIN / STDHS_PASSWORD env vars; the run aborts when they are
     * absent. ({@code run.ps1 stdhs} wires the built-in test account in.)
     *
     * Run with Maven exec (test scope includes this class):
     *   mvn test-compile exec:java \
     *       -Dexec.mainClass=com.gcis.pages.OnlineBookingLoginPageStdHS \
     *       -Dexec.classpathScope=test
     * or simply run {@code main()} from the IDE. Flags mirror TestBase:
     * {@code -Dheaded=true} (fullscreen browser), {@code -Dslowmo=true},
     * {@code -Dslowmo.ms=N}, {@code -Dob.attempts=N} (captcha budget).
     * Exits 0 when every check passes, 1 otherwise.
     */
    public static void main(String[] args) {
        boolean headless = !"true".equalsIgnoreCase(System.getProperty("headed", "false"));
        boolean slowMo = "true".equalsIgnoreCase(System.getProperty("slowmo", "false"));

        String login = firstNonBlank(System.getProperty("ob.login"), System.getenv("STDHS_LOGIN"));
        String password = firstNonBlank(System.getProperty("ob.password"), System.getenv("STDHS_PASSWORD"));
        if (login == null || password == null) {
            System.out.println("[SMOKE] FAILED: the login-only run needs real credentials — set"
                    + " -Dob.login/-Dob.password or STDHS_LOGIN/STDHS_PASSWORD env vars");
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
                OnlineBookingLoginPageStdHS loginPage = new OnlineBookingLoginPageStdHS(page);
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
                    Integer respCode = OnlineBookingLoginPageStdHS.respCodeOf(body);
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

                check("left the StdHS login route", loginPage.hasLeftLoginPage());

                // ---- Post-login journey: steps 17-22 ----
                loginPage.postLoginJourney();

                System.out.println("[SMOKE] OK — StdHS journey passed: login → health (image)"
                        + " → findings & recommendations (accessed) → what's new (Next → health)"
                        + " → appointment home → change profile → change password"
                        + " → 繁體中文: health (image) → findings (accessed) → 最新消息 (下一頁 → health)"
                        + " → 預約時間 → 更改個人電郵 → 更改密碼");
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
