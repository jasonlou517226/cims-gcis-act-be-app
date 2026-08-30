package com.dhci2.tests;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

/**
 * Base class for all Playwright based tests.
 * Manages Playwright / Browser / Context / Page lifecycle.
 */
public abstract class TestBase {

    protected Playwright playwright;
    protected Browser browser;
    protected BrowserContext context;
    protected Page page;

    /** Run tests in headless mode by default. Set -D headed=true to watch the browser. */
    protected static boolean isHeadless() {
        return !"true".equalsIgnoreCase(System.getProperty("headed", "false"));
    }

    protected static boolean isSlowMo() {
        return "true".equalsIgnoreCase(System.getProperty("slowmo", "false"));
    }

    /** How long each action pauses in slowmo mode (ms). Default 2000 = 2s per action. */
    protected static int slowMoMillis() {
        return Integer.getInteger("slowmo.ms", 2_000);
    }

    @BeforeEach
    public void setUp() {
        playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(isHeadless());
        if (isSlowMo()) {
            options.setSlowMo(slowMoMillis());
        }
        if (!isHeadless()) {
            // Watch mode: open the browser window in full screen for easy viewing.
            options.setArgs(List.of("--start-fullscreen"));
        }
        browser = playwright.chromium().launch(options);
        // The portal is an older government site; ignore HTTPS errors to be safe.
        context = browser.newContext(
                new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
        page = context.newPage();
        page.setDefaultTimeout(30_000);
    }

    @AfterEach
    public void tearDown() {
        if (page != null) {
            page.close();
        }
        if (context != null) {
            context.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}