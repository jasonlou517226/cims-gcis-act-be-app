package com.gcis.pages;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /** Declaration text shown next to both declaration checkboxes on the upload page. */
    private static final String DECLARATION_TEXT = "本人確認";

    /** jQuery-UI dialog titles used to tell the two confirmation pop-ups apart. */
    private static final String DIALOG_TITLE_SAVE = "Save Student List";
    private static final String DIALOG_TITLE_DELETE = "Record deletion";

    /** Excel bundled with this app (upload_excel/ under the project root). */
    public static final String DEFAULT_UPLOAD_FILE = "upload_excel/for_load_test2.xlsx";

    private final Page page;

    // Locators (based on the real HTML of login.htm)
    private final Locator usernameInput;
    private final Locator passwordInput;
    private final Locator submitButton;
    private final Locator clearButton;
    private final Locator sessionConflictConfirm;

    // Locators (based on the real HTML of upload.htm)
    private final Locator browseButton;      // 瀏覽... (opens the OS file chooser)
    private final Locator uploadButton;      // 上載 (validation())
    private final Locator confirmSaveButton; // 確認及儲存 (save())
    private final Locator deleteClassIcons;  // delete.png rows in 已上載班別:

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
        this.browseButton = page.locator("#fakeButton");
        this.uploadButton = page.locator("#upload");
        // Legacy page: id=nextButton is duplicated; ours is the save() one
        // (the uploadList() twin belongs to another section).
        this.confirmSaveButton = page.locator("input#nextButton[onclick='save()']").first();
        this.deleteClassIcons = page.locator("img.img_Mouse_over[src*='delete.png']");
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

    // ---------- Upload journey (upload.htm) ----------

    /**
     * Ticks the declaration checkbox of the upload section — the control with
     * {@code name=declaration} whose {@code doDeclare(this)} enables the 上載
     * button. Selectors are locale-independent (page may render in EN or 繁中).
     */
    public LoginPage checkUploadDeclaration() {
        Locator checkbox = firstExisting(
                page.locator("input[type=checkbox][name=declaration]"),
                page.locator("input[type=checkbox]:near(#fakeButton, 400)"),
                page.locator(String.format(
                        "input[type=checkbox]:near(:text(\"%s\"), 300)", DECLARATION_TEXT)));
        if (checkbox == null) {
            dumpDeclarationDiagnostics();
            throw new PlaywrightException("declaration checkbox (upload section) not found");
        }
        if (!checkbox.isChecked()) {
            checkbox.check();
        }
        return this;
    }

    /**
     * Ticks the declaration checkbox shown next to the 確認及儲存 button —
     * typically {@code #checkbox1} whose {@code checkRadioBtn()} enables
     * {@code #nextButton}.
     */
    public LoginPage checkConfirmDeclaration() {
        Locator checkbox = firstExisting(
                page.locator("input[type=checkbox]:near(input#nextButton[onclick='save()'], 400)"),
                page.locator("#checkbox1"),
                page.locator("input[type=checkbox][name=declaration]"),
                page.locator(String.format(
                        "input[type=checkbox]:near(:text(\"%s\"), 300)", DECLARATION_TEXT)));
        if (checkbox == null) {
            dumpDeclarationDiagnostics();
            throw new PlaywrightException("declaration checkbox (confirm section) not found");
        }
        if (!checkbox.isChecked()) {
            checkbox.check();
        }
        return this;
    }

    /**
     * Picks the Excel for the 瀏覽... control. The fake button is covered by
     * the real (transparent) {@code <input type=file id=listFile>}, so the
     * reliable Playwright equivalent of Selenium's LocalFileDetector is to set
     * the files on that input directly (fires the same change event); the
     * file-chooser API is kept as fallback.
     */
    public LoginPage browseAndSelectFile(String filePath) {
        Locator fileInput = page.locator("input[type=file]");
        if (fileInput.count() > 0) {
            fileInput.first().setInputFiles(Paths.get(filePath));
            return this;
        }
        FileChooser chooser = page.waitForFileChooser(
                new Page.WaitForFileChooserOptions().setTimeout(15_000),
                () -> browseButton.click());
        chooser.setFiles(Paths.get(filePath));
        return this;
    }

    /**
     * Clicks 上載 (validation()) and waits for the confirmation section
     * (確認及儲存) to appear — the page reloads with the parsed Excel summary.
     * The click timeout also covers the form-submit navigation it triggers,
     * which can be slow when the test backend parses the Excel.
     */
    public LoginPage clickUploadButton() {
        uploadButton.click(new Locator.ClickOptions().setTimeout(180_000));
        confirmSaveButton.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE).setTimeout(180_000));
        return this;
    }

    public boolean isConfirmSaveVisible() {
        return confirmSaveButton.isVisible();
    }

    /** Clicks 確認及儲存 (save()). */
    public LoginPage clickConfirmAndSave() {
        confirmSaveButton.click();
        return this;
    }

    /**
     * Clicks "Yes" on the "Save Student List" / "確定要繼續嗎?" jQuery-UI
     * pop-up shown after 確認及儲存. Falls back to any visible dialog Yes
     * button when the titled dialog cannot be found.
     */
    public LoginPage clickSaveDialogYes(long timeoutMs) {
        clickDialogYes(DIALOG_TITLE_SAVE, "確定要繼續嗎", timeoutMs);
        return this;
    }

    /** True when the 已上載班別 section lists at least one class with a delete icon. */
    public boolean hasUploadedClassToDelete(long timeoutMs) {
        try {
            deleteClassIcons.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (PlaywrightException timeout) {
            return false;
        }
    }

    /**
     * Deletes every class listed under 已上載班別: by clicking its delete.png
     * icon and confirming the "Record deletion" / "確定刪除此班?" pop-up when
     * it appears.
     *
     * @return number of classes deleted
     */
    public int deleteAllUploadedClasses(long settleMs) {
        int deleted = 0;
        while (deleted < 50 && deleteClassIcons.count() > 0) {
            deleteClassIcons.first().click();
            confirmDeleteDialogIfPresent(5_000);
            deleted++;
            page.waitForTimeout(settleMs);
        }
        return deleted;
    }

    /** Clicks "Yes" on the "Record deletion" / "確定刪除此班?" pop-up when it appears. */
    public void confirmDeleteDialogIfPresent(long timeoutMs) {
        try {
            clickDialogYes(DIALOG_TITLE_DELETE, "確定刪除此班", timeoutMs);
        } catch (PlaywrightException ignored) {
            // No pop-up; deletion went through directly.
        }
    }

    /**
     * First of the given candidate locators that resolves to a visible
     * element (falls back to the first merely-attached one; selectors that
     * fail to parse are skipped).
     */
    private Locator firstExisting(Locator... candidates) {
        Locator fallback = null;
        for (Locator candidate : candidates) {
            int count;
            try {
                count = candidate.count();
            } catch (PlaywrightException invalidSelector) {
                continue;
            }
            if (count > 0) {
                Locator first = candidate.first();
                if (first.isVisible()) {
                    return first;
                }
                if (fallback == null) {
                    fallback = first;
                }
            }
        }
        return fallback;
    }

    /** Prints the DOM around the declaration text and all checkable controls (diagnosis aid). */
    private void dumpDeclarationDiagnostics() {
        try {
            Locator checkboxes = page.locator("input[type=checkbox]");
            System.out.println("[DEBUG] input[type=checkbox] count: " + checkboxes.count());
            for (int i = 0; i < checkboxes.count(); i++) {
                System.out.println("[DEBUG] checkbox " + i + " outerHTML: "
                        + checkboxes.nth(i).evaluate("el => el.outerHTML"));
            }
            Locator langLinks = page.locator(
                    "a:has-text(\"中文\"), a[href*=locale], a[href*=lang], select[name*=locale], select[name*=lang]");
            System.out.println("[DEBUG] language links: " + langLinks.count());
            for (int i = 0; i < langLinks.count() && i < 8; i++) {
                System.out.println("[DEBUG] lang " + i + ": "
                        + langLinks.nth(i).evaluate("el => el.outerHTML"));
            }
            Locator texts = page.locator(String.format("text=%s", DECLARATION_TEXT));
            System.out.println("[DEBUG] declaration text hits: " + texts.count());
            for (int i = 0; i < texts.count() && i < 3; i++) {
                System.out.println("[DEBUG] hit " + i + " outerHTML: "
                        + texts.nth(i).evaluate("el => el.outerHTML"));
                System.out.println("[DEBUG] hit " + i + " row outerHTML: "
                        + texts.nth(i).evaluate(
                                "el => { let n = el; while (n && n.tagName !== 'TR' && n.tagName !== 'TABLE' && n.tagName !== 'BODY') n = n.parentElement; return n ? n.outerHTML.substring(0, 1200) : 'n/a'; }"));
            }
        } catch (PlaywrightException ignored) {
            // Diagnostics are best-effort only.
        }
    }

    /**
     * Clicks the Yes button of a jQuery-UI dialog identified by its (English)
     * title first, then its (localized) body text, then any visible dialog
     * Yes button — the title and Yes button are hard-coded on the server side.
     */
    private void clickDialogYes(String titleKeyword, String bodyKeyword, long timeoutMs) {
        long perTry = Math.max(timeoutMs / 3, 2_000);
        Locator yes = page.locator(String.format(
                ".ui-dialog:has(.ui-dialog-title:text(\"%s\")) button:has-text(\"Yes\")",
                titleKeyword)).first();
        try {
            yes.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(perTry));
        } catch (PlaywrightException titledNotFound) {
            try {
                yes = page.locator(String.format(
                        ".ui-dialog:has(:text(\"%s\")) button:has-text(\"Yes\")", bodyKeyword)).first();
                yes.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE).setTimeout(perTry));
            } catch (PlaywrightException bodyNotFound) {
                yes = page.locator(".ui-dialog button:has-text(\"Yes\")").first();
                yes.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE).setTimeout(perTry));
            }
        }
        yes.click();
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
     * Local smoke run: login → Upload Student List → upload Excel → confirm →
     * delete uploaded classes — no JUnit required.
     * Launches Chromium, opens SchoolPortalWeb/login.htm, logs in with the
     * test account, handles the session-conflict dialog, waits for the
     * authenticated redirect, navigates to the "Upload Student List" page
     * (SchoolPortalTrust/upload.htm?sel=upload), then walks the full upload
     * journey: declaration checkbox → 瀏覽 (pick Excel) → 上載 → declaration
     * checkbox → 確認及儲存 → "Save Student List" Yes → delete every class
     * under 已上載班別 (confirming the "Record deletion" pop-up when shown).
     *
     * Credentials (env vars override the built-in test account):
     *   -Dtest.username=... -Dtest.password=... or TEST_USERNAME / TEST_PASSWORD.
     * Upload file (default upload_excel/for_load_test2.xlsx):
     *   -Dupload.file=... or UPLOAD_FILE env var.
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
        String uploadFile = firstNonBlank(System.getProperty("upload.file"),
                System.getenv("UPLOAD_FILE"));
        Path uploadPath = locateUploadFile(uploadFile != null ? uploadFile : DEFAULT_UPLOAD_FILE);
        if (!Files.isRegularFile(uploadPath)) {
            System.out.println("[SMOKE] FAILED: upload file not found: " + uploadPath);
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
            // Legacy government test portal: navigations (form submits, Excel
            // parsing) can easily exceed the 30s default on slow runs.
            page.setDefaultNavigationTimeout(180_000);

            try {
                LoginPage loginPage = new LoginPage(page);
                loginPage.open();
                check("login form is visible", loginPage.isLoginFormVisible());

                // ---- Step 1: Login ----
                loginPage.loginAs(login, password);
                loginPage.confirmSessionConflictIfPresent();
                boolean loggedIn = loginPage.waitForLoginSuccess(60_000);
                check("login accepted (left SchoolPortalWeb/login)", loggedIn);
                System.out.println("[SMOKE] login OK. URL: " + loginPage.currentUrl());

                // ---- Go to "Upload Student List" ----
                loginPage.openUploadStudentList();
                check("landed on Upload Student List page", loginPage.isOnUploadStudentListPage());
                System.out.println("[SMOKE] upload page OK. URL: " + loginPage.currentUrl());

                // ---- Step 2: tick the declaration checkbox ----
                loginPage.checkUploadDeclaration();
                System.out.println("[SMOKE] declaration ticked (upload section)");

                // ---- Step 3: 瀏覽 → pick the Excel file ----
                loginPage.browseAndSelectFile(uploadPath.toString());
                System.out.println("[SMOKE] file selected: " + uploadPath);

                // ---- Step 4: 上載 (waits for the confirm section) ----
                loginPage.clickUploadButton();
                check("confirm-and-save section appeared after upload",
                        loginPage.isConfirmSaveVisible());

                // ---- Step 5: tick the declaration checkbox → 確認及儲存 ----
                loginPage.checkConfirmDeclaration();
                System.out.println("[SMOKE] declaration ticked (confirm section)");
                loginPage.clickConfirmAndSave();

                // ---- Step 6: "Save Student List" pop-up → Yes ----
                loginPage.clickSaveDialogYes(30_000);
                System.out.println("[SMOKE] save dialog confirmed (Yes)");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.waitForTimeout(2_000);

                // ---- Step 7: delete every class under 已上載班別 ----
                if (loginPage.hasUploadedClassToDelete(30_000)) {
                    int deleted = loginPage.deleteAllUploadedClasses(1_500);
                    check("uploaded classes deleted", deleted > 0 && loginPage.deleteClassIconsCount() == 0);
                    System.out.println("[SMOKE] deleted classes: " + deleted);
                } else {
                    System.out.println("[SMOKE] no uploaded classes to delete");
                }

                System.out.println("[SMOKE] OK — login → upload → confirm → delete passed");
            } finally {
                context.close();
                browser.close();
            }
        } catch (Exception e) {
            System.out.println("[SMOKE] FAILED: " + e);
            System.exit(1);
        }
    }

    /** Number of delete icons currently listed under 已上載班別. */
    public int deleteClassIconsCount() {
        return deleteClassIcons.count();
    }

    /**
     * Resolves the upload Excel path. Absolute paths are taken as-is; relative
     * paths (like the bundled upload_excel/for_load_test2.xlsx) are looked up
     * from the working directory and each of its parents, so the file is found
     * even when main() runs from an IDE with a different working directory.
     */
    private static Path locateUploadFile(String raw) {
        Path p = Paths.get(raw);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(raw).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return p.toAbsolutePath().normalize();
    }

    /** First non-blank value, or null when all are blank (env-var friendly). */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
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