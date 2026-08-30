package com.dhci2.pages;

import com.dhci2.support.CaptchaOcr;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Page Object for the DH Online Booking SPA (React, hash routing).
 *
 * Target: https://www.clinical.dh.gov.hk/OnlineBookingWeb/#/FHS-CH/login
 *
 * Notes:
 * - The SPA is a React app served from a single index.html; real "pages" are
 *   hash routes (e.g. #/FHS-CH/login). Navigation therefore means: goto the
 *   base URL with the hash, then wait for the React app to render.
 * - The login form posts to /online-booking-user/loginWithSam with a captcha
 *   (captchaKey + captchaAns). The captcha answer is only shown inside the
 *   generated image; we solve it with macOS Vision OCR (see CaptchaOcr) and
 *   retry with a fresh captcha until one is recognized & accepted.
 */
public class OnlineBookingLoginPage {

    public static final String BASE_URL = "https://www.clinical.dh.gov.hk/OnlineBookingWeb/";
    public static final String URL = BASE_URL + "#/FHS-CH/login";

    /** Backend APIs used by the SPA (relative to /OnlineBookingWeb). */
    public static final String SITE_PARAMS_API = "/online-booking-user/siteParams/map";
    public static final String CAPTCHA_API = "/online-booking-user/generateCaptcha/image";
    public static final String LOGIN_API = "/online-booking-user/loginWithSam";

    /** Backend respCode values observed on the login API. */
    public static final int RESP_OK = 0;
    public static final int RESP_BAD_CREDENTIALS = 100;
    public static final int RESP_CAPTCHA_FAIL = 113;

    private final Page page;

    // Locators (MUI inputs rendered by the React SPA)
    private final Locator usernameInput;
    private final Locator passwordInput;
    private final Locator captchaInput;
    private final Locator loginButton;
    private final Locator reloadCaptchaButton;

    /** Latest captcha image payload (base64 JPEG, without data: prefix) seen on the wire. */
    private final BlockingQueue<String> captchaPayloads = new LinkedBlockingQueue<>();

    /** How long the queue must stay quiet before its newest payload is considered "on screen". */
    private static final long SETTLE_MILLIS = 800;

    /** Result of the most recent loginWithSam call (respCode / errMsg from JSON body). */
    private volatile Integer lastLoginRespCode;
    private volatile String lastLoginErrMsg;

    public OnlineBookingLoginPage(Page page) {
        this.page = page;
        // data-testid attributes are stable hooks emitted by the SPA
        this.usernameInput = page.locator("[data-testid=\"login_name\"] input, #login_name input").first();
        this.passwordInput = page.locator("[data-testid=\"login_password\"] input, #login_password input").first();
        this.captchaInput = page.locator("[data-testid=\"captchaInput\"] input, #captchaInput input").first();
        this.loginButton = page.locator("#login_button, [data-testid=\"login_loginBtn\"]").first();
        this.reloadCaptchaButton = page.locator("#reloadImage").first();

        // The SPA renders the captcha as an <img> fed by an XHR response; the
        // raw base64 only exists on the network layer, so capture it here.
        // Login results are also read from the wire (respCode) because the SPA
        // renders localized (Chinese) error text that is brittle to match.
        page.onResponse(response -> {
            String url = response.url();
            if (url.contains(CAPTCHA_API)) {
                try {
                    String base64 = extractCaptchaBase64(response.text());
                    if (base64 != null) {
                        captchaPayloads.offer(base64);
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

    /** Opens the SPA login route and waits for the React app to render. */
    public void open() {
        page.navigate(URL, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        // Wait for the SPA to finish bootstrapping and render the login form
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(15_000));
        } catch (PlaywrightException ignored) {
            // Some analytics/long-poll requests may keep the network busy;
            // the element waits below are the authoritative check.
        }
        usernameInput.waitFor(new Locator.WaitForOptions().setTimeout(20_000));
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

    /**
     * Calls the site-params backend API through the browser context and returns
     * the raw JSON. This is what feeds the SPA's site list (FHS-CH etc.), so it
     * is a good backend health signal.
     */
    public APIResponse fetchSiteParams() {
        return page.request().get(BASE_URL + SITE_PARAMS_API.substring(1));
    }

    /**
     * Calls the captcha generation API through the browser context. A healthy
     * response is HTTP 200 with a non-trivial JSON body (contains the base64
     * image and/or captcha key).
     */
    public APIResponse fetchCaptcha() {
        return page.request().get(BASE_URL + CAPTCHA_API.substring(1));
    }

    /** Convenience accessor for request/response debugging in tests. */
    public Page page() {
        return page;
    }

    // ---------- Captcha solving ----------

    /**
     * Waits for the next captcha image payload captured from the network and
     * OCRs it. Returns the recognized (cleaned, upper-cased) text, or
     * {@code null} if no payload arrives or nothing could be read.
     */
    public String solveCurrentCaptcha(long waitMillis) throws InterruptedException {
        String latest = captchaPayloads.poll(waitMillis, TimeUnit.MILLISECONDS);
        if (latest == null) {
            return null;
        }
        // Drain to the latest payload: after a failed login the SPA fetches a
        // new captcha itself, and the test may also click "renew", so several
        // images can be in flight. The image on screen is always the LAST one
        // received — OCR anything older and the answer can never match.
        long deadline = System.currentTimeMillis() + SETTLE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            String newer = captchaPayloads.poll(
                    deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (newer != null) {
                latest = newer;
                deadline = System.currentTimeMillis() + SETTLE_MILLIS;
            }
        }
        return CaptchaOcr.solveClean(latest);
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
        return !page.url().contains("#/FHS-CH/login");
    }
}
