package com.gcis.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * Page Object for the School Portal login page.
 *
 * Target page: https://www1.dhsisp.gov.hk/SchoolPortalWeb/login.htm
 */
public class LoginPage {

    public static final String URL = "https://www1.dhsisp.gov.hk/SchoolPortalWeb/login.htm";

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
}