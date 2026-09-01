package com.dhci2.tests;

import com.dhci2.pages.OnlineBookingLoginPage;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full automated login against the Online Booking SPA.
 *
 * The captcha is solved with OCR (macOS Vision or tesseract — see
 * {@link com.dhci2.support.CaptchaOcr}). By default up to 5 captcha attempts
 * are made; set -Dob.attempts=N to change the budget when OCR misreads
 * (it is case-sensitive & noisy).
 *
 * Credentials come from system properties or environment variables
 * (never hard-code secrets):
 *   -Dob.login=...  -Dob.password=...   or   OB_LOGIN / OB_PASSWORD env vars
 * When credentials are absent the test is skipped.
 */
class OnlineBookingOcrLoginTest extends TestBase {

    /** First non-blank value, or null when all are blank (env-var friendly). */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }

    private OnlineBookingLoginPage loginPage;

    @Override
    @org.junit.jupiter.api.BeforeEach
    public void setUp() {
        super.setUp();
        loginPage = new OnlineBookingLoginPage(page);
    }

    @Test
    @DisplayName("OCR-solved captcha lets a valid user log in")
    @Timeout(value = 300)
    void loginWithOcrCaptcha() throws InterruptedException {
        String login = firstNonBlank(System.getProperty("ob.login"), System.getenv("OB_LOGIN"));
        String password = firstNonBlank(System.getProperty("ob.password"), System.getenv("OB_PASSWORD"));
        Assumptions.assumeTrue(login != null && password != null,
                "Set -Dob.login/-Dob.password or OB_LOGIN/OB_PASSWORD env vars to run this test");

        loginPage.open();
        loginPage.fillUsername(login);
        loginPage.fillPassword(password);

        // Attempt budget: default 5. OCR is case-sensitive and noisy —
        // adjust via -Dob.attempts=N if the test becomes flaky.
        final int maxAttempts = Integer.getInteger("ob.attempts", 5);
        String lastCaptcha = null;
        String lastErrMsg = null;
        Integer lastRespCode = null;
        boolean accepted = false;

        for (int attempt = 1; attempt <= maxAttempts && !accepted; attempt++) {
            // Wait for the captcha image captured on the wire and OCR it.
            String answer = loginPage.solveCurrentCaptcha(20_000);
            if (answer == null || answer.length() != 4) {
                System.out.println("attempt " + attempt + ": OCR unreadable"
                        + (answer == null ? "" : " [" + answer + "]") + ", renewing image");
                loginPage.reloadCaptcha();
                continue;
            }
            lastCaptcha = answer;
            System.out.println("attempt " + attempt + ": OCR answer = " + answer
                    + " (engine: " + com.dhci2.support.CaptchaOcr.activeBackend() + ")");
            // Re-fill credentials every time: the SPA clears the form after a
            // failed login, and client-side validation would silently block
            // the submit if a required field became empty.
            loginPage.fillUsername(login);
            loginPage.fillPassword(password);
            loginPage.fillCaptcha(answer);

            // Click Login and read the loginWithSam verdict from the wire.
            // waitForResponse keeps the Playwright event loop pumping while
            // waiting, so the response body can be read on the test thread.
            Response resp;
            try {
                resp = loginPage.submitAndWaitForLoginResponse(20_000);
            } catch (PlaywrightException timeout) {
                if (loginPage.hasLeftLoginPage()) {
                    accepted = true;
                    break;
                }
                System.out.println("attempt " + attempt + ": no login response ("
                        + timeout.getClass().getSimpleName() + ": "
                        + String.valueOf(timeout.getMessage()).split("\n")[0] + "), retrying");
                loginPage.reloadCaptcha();
                continue;
            }

            String body = resp.text();
            Integer respCode = OnlineBookingLoginPage.respCodeOf(body);
            lastRespCode = respCode;
            System.out.println("attempt " + attempt + ": respCode=" + respCode
                    + " body=" + body);

            if (respCode == null) {
                if (loginPage.hasLeftLoginPage()) {
                    accepted = true;
                    break;
                }
                loginPage.reloadCaptcha();
                continue;
            }
            if (respCode == OnlineBookingLoginPage.RESP_CAPTCHA_FAIL) {
                // The SPA auto-fetches a fresh captcha after 113 (observed on
                // the wire); do NOT click "renew" here as well — a second
                // concurrent fetch would race with the one already in flight.
                System.out.println("attempt " + attempt + ": captcha rejected (113); "
                        + "SPA refreshes the image, retrying");
                continue;
            }
            if (respCode == OnlineBookingLoginPage.RESP_OK) {
                accepted = loginPage.hasLeftLoginPage();
                if (accepted) {
                    break;
                }
                // respCode 0 but still on the login route: give the SPA a
                // moment to finish routing before declaring failure.
                loginPage.page().waitForTimeout(3_000);
                accepted = loginPage.hasLeftLoginPage();
                break;
            }
            // respCode 100 or other error: captcha was RIGHT, backend
            // rejected the credentials — surface it and stop retrying.
            lastErrMsg = OnlineBookingLoginPage.errMsgOf(body);
            System.out.println("attempt " + attempt + ": captcha accepted; backend rejected login ("
                    + lastErrMsg + ")");
            break;
        }

        assertTrue(accepted, "Login should succeed (maxAttempts=" + maxAttempts
                + "; last answer: " + lastCaptcha + ", last respCode: " + lastRespCode
                + ", last errMsg: " + lastErrMsg + ")");
        System.out.println("LOGIN OK, now at: " + loginPage.currentUrl());
    }
}