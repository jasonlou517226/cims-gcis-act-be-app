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
 * - The StdHS smoke ({@code main()}) is login-only: service selection →
 *   captcha login → confirm the SPA left the login route.
 */
public class OnlineBookingLoginPageStdHS {

    public static final String URL = "https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/StdHS/login";

    /** Root service-selection login route and the StdHS option label on it. */
    public static final String SERVICE_SELECTION_URL =
            "https://testing.clinical.dh.gov.hk/OnlineBookingWeb/#/login";
    public static final String STUDENT_HEALTH_SERVICE_TEXT = "Student Health Service";

    private static final String LOGIN_ROUTE_HASH = "#/StdHS/login";

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

    // ---------- Standalone local runner ----------

    /**
     * Local smoke run — LOGIN-ONLY, no JUnit required. Launches Chromium,
     * opens the service-selection root route ({@code #/login}), selects
     * "Student Health Service", logs in with a real account (captcha solved
     * via {@link #solveCurrentCaptcha(long)}, retrying like
     * {@code OnlineBookingOcrLoginTest}) and confirms the SPA left the
     * StdHS login route.
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

                // Login-only: confirm we are off the login route and report
                // where the SPA landed.
                check("left the StdHS login route", loginPage.hasLeftLoginPage());
                System.out.println("[SMOKE] OK — StdHS login-only passed, now at: "
                        + loginPage.currentUrl());
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
