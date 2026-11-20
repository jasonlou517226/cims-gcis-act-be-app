package com.gcis.pages;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.List;

/**
 * Page Object for the School Portal login page.
 *
 * Target page: https://testing.dhsisp.gov.hk/SchoolPortalWeb/login.htm
 */
public class LoginPage {

    public static final String URL = "https://testing.dhsisp.gov.hk/SchoolPortalWeb/login.htm";

    /** "Upload Student List" page (requires an authenticated session). */
    public static final String UPLOAD_STUDENT_LIST_URL =
            "https://testing.dhsisp.gov.hk/SchoolPortalTrust/upload.htm?sel=upload";

    private final Page page;

    // Locators (based on the real HTML of login.htm)
    private final Locator usernameInput;
    private final Locator passwordInput;
    private final Locator submitButton;
    private final Locator clearButton;
    private final Locator sessionConflictConfirm;

    public LoginPage(Page page) {
        this.page = page;
        this.usernameInput = page.locator("#username");
        this.passwordInput = page.locator("#password");
        this.submitButton = page.locator("#fm_login_submitButton");
        this.clearButton = page.locator("#clearLogin");
        // Shown when the same account is already logged in on another device
        this.sessionConflictConfirm = page.locator(
                "button:has-text(\"I understood and wanted to proceed\"),"
                        + " a:has-text(\"I understood and wanted to proceed\"),"
                        + " input[value='I understood and wanted to proceed']");
    }

    /** Opens the login page and waits until the DOM is loaded. */
    public void open() {
        page.navigate(URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
    }

    public LoginPage fillUsername(String username) {
        usernameInput.fill(username);
        return this;
    }

    public LoginPage fillPassword(String password) {
        passwordInput.fill(password);
        return this;
    }

    /** Clicks Submit and waits for navigation to finish. */
    public void submit() {
        submitButton.click();
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    }

    /** Performs a full login with the given credentials. */
    public void loginAs(String username, String password) {
        open();
        fillUsername(username);
        fillPassword(password);
        submit();
    }

    /** Clears both fields using the page's own Clear button. */
    public void clearForm() {
        clearButton.click();
    }

    /**
     * If the "There is another active session" dialog appears after login,
     * confirm that we want to proceed logging in here. Does nothing otherwise.
     */
    public void confirmSessionConflictIfPresent() {
        try {
            sessionConflictConfirm.first().click(
                    new Locator.ClickOptions().setTimeout(3_000));
        } catch (PlaywrightException ignored) {
            // Dialog not present; nothing to do.
        }
    }

    // ---------- Login → Upload Student List journey ----------

    /** Waits until the browser has left the public login page (SchoolPortalWeb/login*). */
    public boolean waitForLoginSuccess(long timeoutMs) {
        try {
            page.waitForURL(url -> !url.contains("SchoolPortalWeb/login"),
                    new Page.WaitForURLOptions().setTimeout(timeoutMs));
            return true;
        } catch (PlaywrightException timeout) {
            return false;
        }
    }

    /** Opens the "Upload Student List" page directly (needs an active session). */
    public LoginPage openUploadStudentList() {
        page.navigate(UPLOAD_STUDENT_LIST_URL,
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        return this;
    }

    public boolean isOnUploadStudentListPage() {
        return page.url().contains("SchoolPortalTrust/upload.htm");
    }

    /**
     * Full journey: login at SchoolPortalWeb/login.htm, confirm the session
     * conflict dialog if shown, wait for the authenticated redirect, then
     * navigate to the "Upload Student List" page (SchoolPortalTrust/upload.htm).
     *
     * @return true when we end up on the Upload Student List page
     */
    public boolean loginAndOpenUploadStudentList(String username, String password, long timeoutMs) {
        loginAs(username, password);
        confirmSessionConflictIfPresent();
        if (!waitForLoginSuccess(timeoutMs)) {
            return false;
        }
        openUploadStudentList();
        return isOnUploadStudentListPage();
    }

    // ---------- Assertions helpers ----------

    public String currentPageTitle() {
        return page.title();
    }

    public String currentUrl() {
        return page.url();
    }

    public boolean isLoginFormVisible() {
        return usernameInput.isVisible() && passwordInput.isVisible() && submitButton.isVisible();
    }

    public Locator usernameInput() {
        return usernameInput;
    }

    public Locator passwordInput() {
        return passwordInput;
    }

    public Locator submitButton() {
        return submitButton;
    }

    // ---------- Standalone local runner ----------

    /**
     * Local smoke run: login → Upload Student List — no JUnit required.
     * Launches Chromium, opens SchoolPortalWeb/login.htm, logs in with the
     * test account, handles the session-conflict dialog, waits for the
     * authenticated redirect, then navigates to the "Upload Student List"
     * page (SchoolPortalTrust/upload.htm?sel=upload).
     *
     * Credentials (env vars override the built-in test account):
     *   -Dtest.username=... -Dtest.password=... or TEST_USERNAME / TEST_PASSWORD.
     *
     * Run with Maven exec (test scope includes this class):
     *   mvn test-compile exec:java -Dexec.mainClass=com.gcis.pages.LoginPage -Dexec.classpathScope=test
     * or simply run main() from the IDE. Flags mirror TestBase:
     *   -Dheaded=true (fullscreen browser), -Dslowmo=true, -Dslowmo.ms=N.
     * Exits 0 when every check passes, 1 otherwise.
     */
    public static void main(String[] args) {
        boolean headless = !"true".equalsIgnoreCase(System.getProperty("headed", "false"));
        boolean slowMo = "true".equalsIgnoreCase(System.getProperty("slowmo", "false"));

        String login = cred("TEST_USERNAME", "JASON_LOU1");
        String password = cred("TEST_PASSWORD", "Sira!!2118");

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
                LoginPage loginPage = new LoginPage(page);
                loginPage.open();
                check("login form is visible", loginPage.isLoginFormVisible());

                // ---- Login ----
                loginPage.loginAs(login, password);
                loginPage.confirmSessionConflictIfPresent();
                boolean loggedIn = loginPage.waitForLoginSuccess(60_000);
                check("login accepted (left SchoolPortalWeb/login)", loggedIn);
                System.out.println("[SMOKE] login OK. URL: " + loginPage.currentUrl());

                // ---- Go to "Upload Student List" ----
                loginPage.openUploadStudentList();
                check("landed on Upload Student List page", loginPage.isOnUploadStudentListPage());
                System.out.println("[SMOKE] upload page OK. URL: " + loginPage.currentUrl());
                System.out.println("[SMOKE] upload page title: " + loginPage.currentPageTitle());

                System.out.println("[SMOKE] OK — login → Upload Student List passed");
            } finally {
                context.close();
                browser.close();
            }
        } catch (Exception e) {
            System.out.println("[SMOKE] FAILED: " + e);
            System.exit(1);
        }
    }

    /** Reads a credential from a system property (test.username) or env var, falling back to the default. */
    private static String cred(String name, String fallback) {
        String v = System.getProperty(name.toLowerCase().replace('_', '.'));
        if (v == null || v.isBlank()) {
            v = System.getenv(name);
        }
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** Records one smoke check; throws (failing the run) when it does not hold. */
    private static void check(String what, boolean holds) {
        System.out.println((holds ? "[OK]   " : "[FAIL] ") + what);
        if (!holds) {
            throw new IllegalStateException("check failed: " + what);
        }
    }
}