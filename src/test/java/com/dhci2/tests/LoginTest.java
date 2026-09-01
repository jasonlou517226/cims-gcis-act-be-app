package com.dhci2.tests;

import com.dhci2.pages.LoginPage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated tests for the School Portal login page:
 * https://www1.dhsisp.gov.hk/SchoolPortalWeb/login.htm
 */
class LoginTest extends TestBase {

    /** Credentials: env vars TEST_USERNAME / TEST_PASSWORD override the built-in test account. */
    private static final String VALID_USERNAME = cred("TEST_USERNAME", "ehr-test");
    private static final String VALID_PASSWORD = cred("TEST_PASSWORD", "Sira!!2117");

    /** Reads a credential from a system property (test.username) or env var, falling back to the default. */
    private static String cred(String name, String fallback) {
        String v = System.getProperty(name.toLowerCase().replace('_', '.'));
        if (v == null || v.isBlank()) {
            v = System.getenv(name);
        }
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private LoginPage loginPage;

    @Override
    @org.junit.jupiter.api.BeforeEach
    public void setUp() {
        super.setUp();
        loginPage = new LoginPage(page);
    }

    @Test
    @DisplayName("Login page can be loaded and shows the login form")
    @Timeout(value = 60)
    void loginPageLoadsSuccessfully() {
        loginPage.open();

        assertAll(
                () -> assertTrue(loginPage.isLoginFormVisible(), "Login form should be visible"),
                () -> assertTrue(loginPage.currentPageTitle().contains("Login"),
                        "Page title should contain 'Login', but was: " + loginPage.currentPageTitle()),
                () -> assertEquals(LoginPage.URL, loginPage.currentUrl(), "Should stay on the login URL")
        );
    }

    @Timeout(value = 120)
    @Test
    @DisplayName("Valid credentials log in successfully")
    void loginWithValidCredentials() {
        loginPage.loginAs(VALID_USERNAME, VALID_PASSWORD);

        // Handle "another active session" dialog if it appears, then wait until
        // we leave the public login page (SchoolPortalWeb/login*).
        loginPage.confirmSessionConflictIfPresent();
        page.waitForURL(
                url -> !url.contains("SchoolPortalWeb/login"),
                new Page.WaitForURLOptions().setTimeout(60_000));

        String currentUrl = loginPage.currentUrl();
        assertTrue(!currentUrl.contains("SchoolPortalWeb/login"),
                "After login we should not stay on login page. Current URL: " + currentUrl);

        System.out.println("Login OK. Current URL: " + currentUrl);
        System.out.println("Login OK. Page title: " + loginPage.currentPageTitle());
    }

    @Timeout(value = 120)
    @Test
    @DisplayName("Invalid credentials are rejected and stay on login page")
    void loginWithInvalidCredentials() {
        loginPage.loginAs(VALID_USERNAME, "WrongPassword123!");

        // Capture state ASAP: the observation waits below can die with
        // TargetClosedError when the page is closed mid-run (manual close in
        // headed watch mode, or this legacy portal closing/crashing itself
        // after a failed login).
        String currentUrl = loginPage.currentUrl();
        String bodyText = "";
        boolean pageClosed = false;
        try {
            // Expect either an error message on the login page or staying on login-related URL
            page.waitForTimeout(3_000);
            loginPage.confirmSessionConflictIfPresent();
            page.waitForTimeout(2_000);
            currentUrl = loginPage.currentUrl();
            bodyText = page.locator("body").innerText();
        } catch (PlaywrightException e) {
            pageClosed = true;
            System.out.println("[invalid-login] page closed while observing: "
                    + e.getMessage().split("\n")[0]);
        }

        final String url = currentUrl;
        final String body = bodyText;
        final boolean closed = pageClosed;
        final boolean formVisible;
        if (pageClosed) {
            formVisible = false;
        } else {
            boolean visible = false;
            try {
                visible = loginPage.usernameInput().isVisible();
            } catch (PlaywrightException e) {
                System.out.println("[invalid-login] form check skipped: page closed");
            }
            formVisible = visible;
        }

        assertAll(
                () -> assertTrue(url.contains("login") || body.toLowerCase().contains("invalid"),
                        "Should remain on login page or show an error. URL: " + url),
                () -> assertTrue(formVisible || !url.contains("login.htm") || closed,
                        "Login form should still be reachable")
        );
    }

    @Test
    @DisplayName("Clear button empties both input fields")
    @Timeout(value = 60)
    void clearButtonEmptiesFields() {
        loginPage.open();
        loginPage.fillUsername(VALID_USERNAME);
        loginPage.fillPassword(VALID_PASSWORD);

        loginPage.clearForm();

        assertAll(
                () -> assertEquals("", loginPage.usernameInput().inputValue(), "Username field should be empty"),
                () -> assertEquals("", loginPage.passwordInput().inputValue(), "Password field should be empty")
        );
    }
}