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
import java.util.regex.Pattern;

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

    /** Message shown in 已上載班別 once every uploaded class has been deleted. */
    private static final Pattern NO_CLASS_KEYWORD = Pattern.compile(
            "沒有已上載的班別|No class has been found", Pattern.CASE_INSENSITIVE);

    /** Excel bundled with this app (upload_excel/ under the project root). */
    public static final String DEFAULT_UPLOAD_FILE = "upload_excel/for_load_test2.xlsx";

    /** Pause (ms) after each journey action so steps can be followed by eye. */
    private static final long STEP_PAUSE_MS = Long.getLong("step.pause.ms", 2_000);

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
     * Picks the Excel for the 瀏覽... control following the real user path.
     * The fake button is covered by the transparent {@code <input type=file
     * id=listFile>}, so we click that input — which fires its onclick and lets
     * the page's own polling mirror the chosen file name into the readonly
     * {@code #file-name} text field (visible proof of the selection) — and
     * answer the OS file chooser with the file. Falls back to setting the
     * input directly (Selenium LocalFileDetector equivalent) when no chooser
     * appears, and finally writes the name into {@code #file-name} ourselves.
     */
    public LoginPage browseAndSelectFile(String filePath) {
        Path file = Paths.get(filePath);
        Locator fileInput = page.locator("input[type=file]");
        boolean shown = false;
        if (fileInput.count() > 0) {
            try {
                FileChooser chooser = page.waitForFileChooser(
                        new Page.WaitForFileChooserOptions().setTimeout(10_000),
                        () -> fileInput.first().click(new Locator.ClickOptions().setTimeout(10_000)));
                chooser.setFiles(file);
                dismissDialogAfterFileSelection(8_000);
                shown = waitForFileNameShown(10_000);
            } catch (PlaywrightException noChooser) {
                // Fall through to the direct path below.
            }
        }
        if (!shown) {
            if (fileInput.count() > 0) {
                fileInput.first().setInputFiles(file);
            } else {
                FileChooser chooser = page.waitForFileChooser(
                        new Page.WaitForFileChooserOptions().setTimeout(15_000),
                        () -> browseButton.click());
                chooser.setFiles(file);
            }
            dismissDialogAfterFileSelection(2_000);
            shown = waitForFileNameShown(5_000);
        }
        if (!shown) {
            page.locator("#file-name").evaluate("(el, name) => { el.value = name; }",
                    file.getFileName().toString());
        }
        try {
            page.locator("#file-name").first().scrollIntoViewIfNeeded();
        } catch (PlaywrightException ignored) {
            // Field absent; nothing to bring into view.
        }
        return this;
    }

    /** Value currently shown in the readonly file-name field ("" when absent). */
    public String displayedFileName() {
        Locator field = page.locator("#file-name");
        try {
            return field.count() == 0 ? "" : field.first().inputValue();
        } catch (PlaywrightException e) {
            return "";
        }
    }

    /** Polls the readonly #file-name field until it shows something. */
    private boolean waitForFileNameShown(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!displayedFileName().isBlank()) {
                return true;
            }
            page.waitForTimeout(250);
        }
        return false;
    }

    /**
     * Closes the jQuery-UI pop-up the page may raise right after a file is
     * chosen (its overlay would block every later click). Logs what the pop-up
     * said and clicks its affirmative button (OK / Yes / 確定 / 是 ...), or the
     * title-bar close button when it has none. Does nothing when no pop-up shows.
     */
    private void dismissDialogAfterFileSelection(long timeoutMs) {
        Locator overlay = page.locator(".ui-widget-overlay");
        try {
            overlay.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
        } catch (PlaywrightException noPopUp) {
            return;
        }
        Locator dialog = page.locator(".ui-dialog").last();
        String title = dialog.locator(".ui-dialog-title").count() > 0
                ? dialog.locator(".ui-dialog-title").first().innerText() : "";
        String message = dialog.locator(".ui-dialog-content").count() > 0
                ? dialog.locator(".ui-dialog-content").first().innerText().replace("\n", " ") : "";
        Locator buttons = dialog.locator("button");
        List<String> labels = buttons.allInnerTexts();
        System.out.println("[SMOKE] pop-up after file selection: title=\"" + title
                + "\" message=\"" + message + "\" buttons=" + labels);
        Locator target = null;
        for (String label : List.of("OK", "Yes", "Confirm", "確定", "是", "確認")) {
            Locator match = buttons.filter(new Locator.FilterOptions().setHasText(label));
            if (match.count() > 0) {
                target = match.first();
                break;
            }
        }
        if (target == null) {
            target = buttons.count() > 0 ? buttons.first()
                    : dialog.locator(".ui-dialog-titlebar-close").first();
        }
        target.click();
    }

    /**
     * Clicks 上載 (validation()) and waits for the confirmation section
     * (確認及儲存) to appear — the page reloads with the parsed Excel summary.
     * The click timeout also covers the form-submit navigation it triggers,
     * which can be slow when the test backend parses the Excel. When the
     * section never shows up, the page state is dumped before failing.
     */
    public LoginPage clickUploadButton() {
        // The file input's change/interval handlers can reset the form;
        // make sure the declaration is still ticked before submitting.
        checkUploadDeclaration();
        uploadButton.click(new Locator.ClickOptions().setTimeout(180_000));
        try {
            confirmSaveButton.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(180_000));
        } catch (PlaywrightException neverVisible) {
            dumpPageDiagnostics();
            throw neverVisible;
        }
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
     * Waits until the 已上載班別 section reports that no class is left
     * ("沒有已上載的班別" / "No class has been found").
     */
    public boolean waitForNoUploadedClass(long timeoutMs) {
        try {
            page.getByText(NO_CLASS_KEYWORD).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (PlaywrightException timeout) {
            return false;
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

    /**
     * Best-effort snapshot of the current page state for failure analysis:
     * URL, visible body text and a full-page screenshot under target/.
     */
    private void dumpPageDiagnostics() {
        try {
            System.out.println("[DEBUG] current URL: " + page.url());
            String body = page.locator("body").innerText();
            System.out.println("[DEBUG] body text (first 1500 chars): "
                    + body.substring(0, Math.min(body.length(), 1_500)).replace("\n", " | "));
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("target", "upload-failure.png")).setFullPage(true));
            System.out.println("[DEBUG] screenshot saved to target/upload-failure.png");
        } catch (PlaywrightException alreadyClosed) {
            System.out.println("[DEBUG] page already closed; nothing more to capture ("
                    + alreadyClosed.getMessage().split("\n")[0] + ")");
        }
    }

    /** Prints the DOM around the declaration text and all checkable controls (diagnosis aid). */
    private void dumpDeclarationDiagnostics() {        try {
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
     * Each journey action pauses -Dstep.pause.ms (default 2000) afterwards so
     * the run can be followed by eye; set 0 to disable.
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
                settle(page);

                // ---- Go to "Upload Student List" ----
                loginPage.openUploadStudentList();
                check("landed on Upload Student List page", loginPage.isOnUploadStudentListPage());
                System.out.println("[SMOKE] upload page OK. URL: " + loginPage.currentUrl());
                settle(page);

                // ---- Pre-cleanup: remove classes left over by previous runs ----
                // (A leftover class makes the portal reject the duplicate upload
                // and the confirm section then never appears.)
                if (loginPage.hasUploadedClassToDelete(5_000)) {
                    int removed = loginPage.deleteAllUploadedClasses(1_500);
                    System.out.println("[SMOKE] pre-cleanup deleted classes: " + removed);
                    page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    settle(page);
                }

                // ---- Step 2: tick the declaration checkbox ----
                loginPage.checkUploadDeclaration();
                System.out.println("[SMOKE] declaration ticked (upload section)");
                settle(page);

                // ---- Step 3: 瀏覽 → pick the Excel file ----
                loginPage.browseAndSelectFile(uploadPath.toString());
                check("file name shown in the readonly field",
                        !loginPage.displayedFileName().isBlank());
                System.out.println("[SMOKE] file selected: " + uploadPath
                        + " (field shows: " + loginPage.displayedFileName() + ")");
                settle(page);

                // ---- Step 4: 上載 (waits for the confirm section) ----
                loginPage.clickUploadButton();
                check("confirm-and-save section appeared after upload",
                        loginPage.isConfirmSaveVisible());
                settle(page);

                // ---- Step 5: tick the declaration checkbox → 確認及儲存 ----
                loginPage.checkConfirmDeclaration();
                System.out.println("[SMOKE] declaration ticked (confirm section)");
                settle(page);
                loginPage.clickConfirmAndSave();
                settle(page);

                // ---- Step 6: "Save Student List" pop-up → Yes ----
                loginPage.clickSaveDialogYes(30_000);
                System.out.println("[SMOKE] save dialog confirmed (Yes)");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                settle(page);

                // ---- Step 7: delete every class under 已上載班別 ----
                boolean deletedSome = false;
                if (loginPage.hasUploadedClassToDelete(30_000)) {
                    int deleted = loginPage.deleteAllUploadedClasses(1_500);
                    check("uploaded classes deleted", deleted > 0 && loginPage.deleteClassIconsCount() == 0);
                    System.out.println("[SMOKE] deleted classes: " + deleted);
                    deletedSome = true;
                } else {
                    System.out.println("[SMOKE] no uploaded classes to delete");
                }

                // ---- Step 8: do not finish right away — wait until the list itself
                // confirms that no class is left (沒有已上載的班別 / No class has been found) ----
                boolean noClassLeft = loginPage.waitForNoUploadedClass(deletedSome ? 60_000 : 15_000);
                if (deletedSome) {
                    check("list confirms no uploaded class left (沒有已上載的班別 / No class has been found)", noClassLeft);
                }
                System.out.println("[SMOKE] no-class keyword shown: " + noClassLeft);
                settle(page);

                System.out.println("[SMOKE] OK — login → upload → confirm → delete → no class left passed");
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

    /** Pauses STEP_PAUSE_MS so the just-performed action can be seen (-Dstep.pause.ms). */
    private static void settle(Page page) {
        if (STEP_PAUSE_MS > 0) {
            page.waitForTimeout(STEP_PAUSE_MS);
        }
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