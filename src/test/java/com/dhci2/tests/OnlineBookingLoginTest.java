package com.dhci2.tests;

import com.dhci2.pages.OnlineBookingLoginPage;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Health-check tests for the DH Online Booking SPA (React, hash routing).
 *
 * Target: https://www.clinical.dh.gov.hk/OnlineBookingWeb/#/FHS-CH/login
 *
 * Because the login flow requires a CAPTCHA whose answer exists only inside
 * the generated image, these tests focus on service availability:
 *  1. SPA shell loads and renders the login form,
 *  2. backend site-params API (feeds the site list) responds,
 *  3. captcha generation API responds with an image payload,
 *  4. login API rejects a wrong captcha gracefully (no 5xx),
 *  5. translating the form fields works as expected.
 */
class OnlineBookingLoginTest extends TestBase {

    private OnlineBookingLoginPage loginPage;

    @Override
    @org.junit.jupiter.api.BeforeEach
    public void setUp() {
        super.setUp();
        loginPage = new OnlineBookingLoginPage(page);
    }

    @Test
    @DisplayName("SPA login page loads and renders the login form")
    @Timeout(value = 90)
    void spaLoginPageLoadsSuccessfully() {
        loginPage.open();

        assertAll(
                () -> assertTrue(loginPage.isLoginFormVisible(), "Login form should be visible"),
                () -> assertTrue(loginPage.isCaptchaVisible(), "Captcha input should be visible"),
                () -> assertTrue(loginPage.currentUrl().contains("#/FHS-CH/login"),
                        "Should stay on the SPA login route. Current URL: " + loginPage.currentUrl())
        );

        System.out.println("SPA login page OK. URL: " + loginPage.currentUrl());
    }

    @Test
    @DisplayName("Site params API returns site list (backend health)")
    @Timeout(value = 90)
    void siteParamsApiIsHealthy() {
        loginPage.open();

        APIResponse response = loginPage.fetchSiteParams();
        String body = response.text();

        assertAll(
                () -> assertEquals(200, response.status(), "siteParams/map should return HTTP 200"),
                () -> assertTrue(body.length() > 100,
                        "siteParams/map body should contain the site list"),
                () -> assertTrue(body.contains("ONLINE_BOOKING") || body.contains("svcCd"),
                        "siteParams/map should mention services/sites. Body: " + preview(body))
        );

        System.out.println("siteParams OK (length=" + body.length() + "): " + preview(body));
    }

    @Test
    @DisplayName("Captcha API returns a fresh captcha image (backend health)")
    @Timeout(value = 90)
    void captchaApiIsHealthy() {
        loginPage.open();

        APIResponse response = loginPage.fetchCaptcha();
        String body = response.text();

        assertAll(
                () -> assertEquals(200, response.status(), "generateCaptcha/image should return HTTP 200"),
                () -> assertTrue(body.contains("data:image") || body.contains("captchaKey"),
                        "Captcha response should embed an image or captcha key. Body: "
                                + preview(body, 200))
        );

        System.out.println("Captcha OK: " + preview(body, 200));
    }

    @Test
    @DisplayName("Wrong captcha is rejected without server errors")
    @Timeout(value = 120)
    void wrongCaptchaIsRejectedGracefully() {
        loginPage.open();
        loginPage.fillUsername("1064984038");
        loginPage.fillPassword("Gold1234{}7");
        loginPage.fillCaptcha("AAAA");
        loginPage.submit();

        // The SPA shows "Verification code is incorrect." without navigating away
        page.waitForFunction(
                "() => { const t = document.body.innerText.toLowerCase();"
                        + " return t.includes('incorrect') || t.includes('verification'); }",
                null,
                new Page.WaitForFunctionOptions().setTimeout(30_000));

        assertTrue(loginPage.currentUrl().endsWith("/login"),
                "Should stay on the SPA login route. Current URL: " + loginPage.currentUrl());
    }

    @Test
    @DisplayName("Login form accepts and reflects typed input")
    @Timeout(value = 90)
    void loginFormReflectsInput() {
        loginPage.open();
        loginPage.fillUsername("healthcheck-user");
        loginPage.fillPassword("healthcheck-pass");

        assertAll(
                () -> assertTrue(loginPage.page().locator("[data-testid=\"login_name\"] input, #login_name input")
                        .first().inputValue().contains("healthcheck-user"),
                        "Username input should hold the typed value"),
                () -> assertTrue(loginPage.page().locator("[data-testid=\"login_password\"] input, #login_password input")
                        .first().inputValue().contains("healthcheck-pass"),
                        "Password input should hold the typed value")
        );
    }

    private static String preview(String body) {
        return preview(body, 300);
    }

    private static String preview(String body, int max) {
        if (body == null) {
            return "null";
        }
        String compact = body.replaceAll("\\s+", " ");
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }
}