import { mkdirSync } from 'node:fs';
import { basename } from 'node:path';
import type { Locator, Page } from 'playwright-core';
import { isLambdaRuntime } from '../config.ts';
import { errorMessage } from '../report.ts';

export const SCHOOL_PORTAL_LOGIN_URL = 'https://testing.dhsisp.gov.hk/SchoolPortalWeb/login.htm';

/** Part of the URL that identifies the public login page (left once logged in). */
export const LOGIN_URL_PART = 'SchoolPortalWeb/login';

/** "Upload Student List" page (requires an authenticated session). */
export const UPLOAD_STUDENT_LIST_URL = 'https://testing.dhsisp.gov.hk/SchoolPortalTrust/upload.htm?sel=upload';

/** Text shown next to both declaration checkboxes on the upload page. */
const DECLARATION_TEXT = '本人確認';

/** jQuery-UI dialog titles telling the two confirmation pop-ups apart (hard-coded server side). */
const DIALOG_TITLE_SAVE = 'Save Student List';
const DIALOG_TITLE_DELETE = 'Record deletion';

/** Message shown in 已上載班別 once every uploaded class has been deleted. */
const NO_CLASS_PATTERN = /沒有已上載的班別|No class has been found/i;

/** Affirmative pop-up buttons, in the order they are preferred. */
const AFFIRMATIVE_BUTTONS = ['OK', 'Yes', 'Confirm', '確定', '是', '確認'];

/**
 * Page Object for the School Portal login page and its "Upload Student List" journey.
 */
export class SchoolPortalPage {
  readonly page: Page;

  private readonly usernameInput: Locator;

  private readonly passwordInput: Locator;

  private readonly submitButton: Locator;

  private readonly clearButton: Locator;

  /** Shown when the same account is already logged in on another device. */
  private readonly sessionConflictConfirm: Locator;

  private readonly browseButton: Locator; // 瀏覽... (opens the OS file chooser)

  private readonly uploadButton: Locator; // 上載 (validation())

  private readonly confirmSaveButton: Locator; // 確認及儲存 (save())

  private readonly deleteClassIcons: Locator; // delete.png rows in 已上載班別:

  constructor(page: Page) {
    this.page = page;
    this.usernameInput = page.locator('#username');
    this.passwordInput = page.locator('#password');
    this.submitButton = page.locator('#fm_login_submitButton');
    this.clearButton = page.locator('#clearLogin');
    this.sessionConflictConfirm = page.locator(
      'button:has-text("I understood and wanted to proceed"),'
        + ' a:has-text("I understood and wanted to proceed"),'
        + " input[value='I understood and wanted to proceed']",
    );
    this.browseButton = page.locator('#fakeButton');
    this.uploadButton = page.locator('#upload');
    // Legacy page: id=nextButton is duplicated; ours is the save() one
    // (the uploadList() twin belongs to another section).
    this.confirmSaveButton = page.locator("input#nextButton[onclick='save()']").first();
    this.deleteClassIcons = page.locator("img.img_Mouse_over[src*='delete.png']");
  }

  // ---------- Login ----------

  /** Opens the login page and waits until the DOM is loaded. */
  async open(): Promise<void> {
    await this.page.goto(SCHOOL_PORTAL_LOGIN_URL, { waitUntil: 'domcontentloaded' });
  }

  async fillUsername(username: string): Promise<this> {
    await this.usernameInput.fill(username);
    return this;
  }

  async fillPassword(password: string): Promise<this> {
    await this.passwordInput.fill(password);
    return this;
  }

  /** Clicks Submit and waits for the navigation it triggers. */
  async submit(): Promise<void> {
    await this.submitButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  /** Performs a full login with the given credentials. */
  async loginAs(username: string, password: string): Promise<void> {
    await this.open();
    await this.fillUsername(username);
    await this.fillPassword(password);
    await this.submit();
  }

  /** Clears both fields using the page's own Clear button. */
  async clearForm(): Promise<void> {
    await this.clearButton.click();
  }

  /**
   * If the "There is another active session" dialog appears after login,
   * confirms that we want to proceed logging in here. Does nothing otherwise.
   */
  async confirmSessionConflictIfPresent(): Promise<void> {
    try {
      await this.sessionConflictConfirm.first().click({ timeout: 3_000 });
    } catch {
      // Dialog not present; nothing to do.
    }
  }

  /** True once the browser has left the public login page (SchoolPortalWeb/login*). */
  async waitForLoginSuccess(timeoutMs: number): Promise<boolean> {
    try {
      await this.page.waitForURL((url) => !url.href.includes(LOGIN_URL_PART), { timeout: timeoutMs });
      return true;
    } catch {
      return false;
    }
  }

  async isLoginFormVisible(): Promise<boolean> {
    return (await this.usernameInput.isVisible())
      && (await this.passwordInput.isVisible())
      && (await this.submitButton.isVisible());
  }

  async isUsernameInputVisible(): Promise<boolean> {
    return this.usernameInput.isVisible();
  }

  usernameValue(): Promise<string> {
    return this.usernameInput.inputValue();
  }

  passwordValue(): Promise<string> {
    return this.passwordInput.inputValue();
  }

  currentPageTitle(): Promise<string> {
    return this.page.title();
  }

  currentUrl(): string {
    return this.page.url();
  }

  // ---------- Login → Upload Student List ----------

  /** Opens the "Upload Student List" page directly (needs an active session). */
  async openUploadStudentList(): Promise<void> {
    await this.page.goto(UPLOAD_STUDENT_LIST_URL, { waitUntil: 'domcontentloaded' });
  }

  isOnUploadStudentListPage(): boolean {
    return this.page.url().includes('SchoolPortalTrust/upload.htm');
  }

  // ---------- Upload journey (upload.htm) ----------

  /**
   * Ticks the declaration checkbox of the upload section — the control with
   * name=declaration whose doDeclare(this) enables the 上載 button. The
   * selectors are locale-independent (the page may render in EN or 繁中).
   */
  async checkUploadDeclaration(): Promise<void> {
    const checkbox = await this.firstExisting(
      this.page.locator('input[type=checkbox][name=declaration]'),
      this.page.locator('input[type=checkbox]:near(#fakeButton, 400)'),
      this.page.locator(`input[type=checkbox]:near(:text("${DECLARATION_TEXT}"), 300)`),
    );
    if (!checkbox) {
      await this.dumpDeclarationDiagnostics();
      throw new Error('declaration checkbox (upload section) not found');
    }
    if (!(await checkbox.isChecked())) {
      await checkbox.check();
    }
  }

  /**
   * Ticks the declaration checkbox shown next to the 確認及儲存 button —
   * typically #checkbox1, whose checkRadioBtn() enables #nextButton.
   */
  async checkConfirmDeclaration(): Promise<void> {
    const checkbox = await this.firstExisting(
      this.page.locator("input[type=checkbox]:near(input#nextButton[onclick='save()'], 400)"),
      this.page.locator('#checkbox1'),
      this.page.locator('input[type=checkbox][name=declaration]'),
      this.page.locator(`input[type=checkbox]:near(:text("${DECLARATION_TEXT}"), 300)`),
    );
    if (!checkbox) {
      await this.dumpDeclarationDiagnostics();
      throw new Error('declaration checkbox (confirm section) not found');
    }
    if (!(await checkbox.isChecked())) {
      await checkbox.check();
    }
  }

  /**
   * Picks the Excel for the 瀏覽... control following the real user path: the
   * fake button is covered by the transparent <input type=file id=listFile>, so
   * that input is clicked (which lets the page mirror the chosen file name into
   * the readonly #file-name field) and the OS file chooser is answered with the
   * file. Falls back to setting the input directly when no chooser appears, and
   * finally writes the name into #file-name ourselves.
   */
  async browseAndSelectFile(filePath: string): Promise<void> {
    const fileInput = this.page.locator('input[type=file]');
    let shown = false;
    if ((await fileInput.count()) > 0) {
      try {
        // Arm the wait before the click that opens the chooser.
        const chooserPromise = this.page.waitForEvent('filechooser', { timeout: 10_000 });
        await fileInput.first().click({ timeout: 10_000 });
        const chooser = await chooserPromise;
        await chooser.setFiles(filePath);
        await this.dismissDialogAfterFileSelection(8_000);
        shown = await this.waitForFileNameShown(10_000);
      } catch {
        // Fall through to the direct path below.
      }
    }
    if (!shown) {
      if ((await fileInput.count()) > 0) {
        await fileInput.first().setInputFiles(filePath);
      } else {
        const chooserPromise = this.page.waitForEvent('filechooser', { timeout: 15_000 });
        await this.browseButton.click();
        const chooser = await chooserPromise;
        await chooser.setFiles(filePath);
      }
      await this.dismissDialogAfterFileSelection(2_000);
      shown = await this.waitForFileNameShown(5_000);
    }
    if (!shown) {
      await this.page
        .locator('#file-name')
        .evaluate((el, name) => {
          (el as HTMLInputElement).value = name;
        }, basename(filePath));
    }
    try {
      await this.page.locator('#file-name').first().scrollIntoViewIfNeeded();
    } catch {
      // Field absent; nothing to bring into view.
    }
  }

  /** Value currently shown in the readonly file-name field ("" when absent). */
  async displayedFileName(): Promise<string> {
    const field = this.page.locator('#file-name');
    try {
      return (await field.count()) === 0 ? '' : await field.first().inputValue();
    } catch {
      return '';
    }
  }

  /** Polls the readonly #file-name field until it shows something. */
  private async waitForFileNameShown(timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if ((await this.displayedFileName()).trim() !== '') {
        return true;
      }
      await this.page.waitForTimeout(250);
    }
    return false;
  }

  /**
   * Closes the jQuery-UI pop-up the page may raise right after a file is
   * chosen (its overlay would block every later click): logs what the pop-up
   * said and clicks its affirmative button (OK / Yes / 確定 / 是 ...), or the
   * title-bar close button when it has none. Does nothing when no pop-up shows.
   */
  private async dismissDialogAfterFileSelection(timeoutMs: number): Promise<void> {
    try {
      await this.page.locator('.ui-widget-overlay').first().waitFor({ state: 'visible', timeout: timeoutMs });
    } catch {
      return;
    }
    const dialog = this.page.locator('.ui-dialog').last();
    const titleLocator = dialog.locator('.ui-dialog-title');
    const contentLocator = dialog.locator('.ui-dialog-content');
    const title = (await titleLocator.count()) > 0 ? await titleLocator.first().innerText() : '';
    const message = (await contentLocator.count()) > 0
      ? (await contentLocator.first().innerText()).replace(/\n/g, ' ')
      : '';
    const buttons = dialog.locator('button');
    const labels = await buttons.allInnerTexts();
    console.log(`[SMOKE] pop-up after file selection: title="${title}" message="${message}" buttons=${labels.join('/')}`);
    let target: Locator | null = null;
    for (const label of AFFIRMATIVE_BUTTONS) {
      const match = buttons.filter({ hasText: label });
      if ((await match.count()) > 0) {
        target = match.first();
        break;
      }
    }
    if (!target) {
      target = (await buttons.count()) > 0 ? buttons.first() : dialog.locator('.ui-dialog-titlebar-close').first();
    }
    await target.click();
  }

  /**
   * Clicks 上載 (validation()) and waits for the confirmation section
   * (確認及儲存) to appear — the page reloads with the parsed Excel summary.
   * Both waits are long because the test backend can be slow to parse the
   * Excel; the page state is dumped before failing.
   */
  async clickUploadButton(): Promise<void> {
    // The file input's change/interval handlers can reset the form;
    // make sure the declaration is still ticked before submitting.
    await this.checkUploadDeclaration();
    await this.uploadButton.click({ timeout: 180_000 });
    try {
      await this.confirmSaveButton.waitFor({ state: 'visible', timeout: 180_000 });
    } catch (error) {
      await this.dumpPageDiagnostics();
      throw error;
    }
  }

  async isConfirmSaveVisible(): Promise<boolean> {
    return this.confirmSaveButton.isVisible();
  }

  /** Clicks 確認及儲存 (save()). */
  async clickConfirmAndSave(): Promise<void> {
    await this.confirmSaveButton.click();
  }

  /**
   * Clicks "Yes" on the "Save Student List" / "確定要繼續嗎?" jQuery-UI
   * pop-up shown after 確認及儲存. Falls back to any visible dialog Yes
   * button when the titled dialog cannot be found.
   */
  async clickSaveDialogYes(timeoutMs: number): Promise<void> {
    await this.clickDialogYes(DIALOG_TITLE_SAVE, '確定要繼續嗎', timeoutMs);
  }

  /** True when the 已上載班別 section lists at least one class with a delete icon. */
  async hasUploadedClassToDelete(timeoutMs: number): Promise<boolean> {
    try {
      await this.deleteClassIcons.first().waitFor({ state: 'visible', timeout: timeoutMs });
      return true;
    } catch {
      return false;
    }
  }

  deleteClassIconsCount(): Promise<number> {
    return this.deleteClassIcons.count();
  }

  /**
   * Deletes every class listed under 已上載班別 by clicking its delete.png
   * icon and confirming the "Record deletion" / "確定刪除此班?" pop-up when it
   * appears. Returns the number of classes deleted.
   */
  async deleteAllUploadedClasses(settleMs: number): Promise<number> {
    let deleted = 0;
    while (deleted < 50 && (await this.deleteClassIcons.count()) > 0) {
      await this.deleteClassIcons.first().click();
      await this.confirmDeleteDialogIfPresent(5_000);
      deleted += 1;
      await this.page.waitForTimeout(settleMs);
    }
    return deleted;
  }

  /** Clicks "Yes" on the "Record deletion" / "確定刪除此班?" pop-up when it appears. */
  async confirmDeleteDialogIfPresent(timeoutMs: number): Promise<void> {
    try {
      await this.clickDialogYes(DIALOG_TITLE_DELETE, '確定刪除此班', timeoutMs);
    } catch {
      // No pop-up; deletion went through directly.
    }
  }

  /**
   * Waits until the 已上載班別 section reports that no class is left
   * ("沒有已上載的班別" / "No class has been found").
   */
  async waitForNoUploadedClass(timeoutMs: number): Promise<boolean> {
    try {
      await this.page.getByText(NO_CLASS_PATTERN).first().waitFor({ state: 'visible', timeout: timeoutMs });
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Clicks the Yes button of a jQuery-UI dialog identified by its (English)
   * title first, then its (localized) body text, then any visible dialog Yes
   * button — the title and the Yes button are hard-coded on the server side.
   */
  private async clickDialogYes(titleKeyword: string, bodyKeyword: string, timeoutMs: number): Promise<void> {
    const perTry = Math.max(Math.floor(timeoutMs / 3), 2_000);
    let yes = this.page
      .locator(`.ui-dialog:has(.ui-dialog-title:text("${titleKeyword}")) button:has-text("Yes")`)
      .first();
    try {
      await yes.waitFor({ state: 'visible', timeout: perTry });
    } catch {
      try {
        yes = this.page.locator(`.ui-dialog:has(:text("${bodyKeyword}")) button:has-text("Yes")`).first();
        await yes.waitFor({ state: 'visible', timeout: perTry });
      } catch {
        yes = this.page.locator('.ui-dialog button:has-text("Yes")').first();
        await yes.waitFor({ state: 'visible', timeout: perTry });
      }
    }
    await yes.click();
  }

  /**
   * First of the given candidate locators that resolves to a visible element
   * (falls back to the first merely-attached one; selectors that fail to
   * parse are skipped).
   */
  private async firstExisting(...candidates: Locator[]): Promise<Locator | null> {
    let fallback: Locator | null = null;
    for (const candidate of candidates) {
      let count = 0;
      try {
        count = await candidate.count();
      } catch {
        continue;
      }
      if (count > 0) {
        const first = candidate.first();
        if (await first.isVisible()) {
          return first;
        }
        fallback ??= first;
      }
    }
    return fallback;
  }

  /** Best-effort snapshot of the current page state for failure analysis. */
  private async dumpPageDiagnostics(): Promise<void> {
    try {
      console.log(`[DEBUG] current URL: ${this.page.url()}`);
      const body = await this.page.locator('body').innerText();
      console.log(`[DEBUG] body text (first 1500 chars): ${body.slice(0, 1500).replace(/\n/g, ' | ')}`);
      if (!isLambdaRuntime()) {
        mkdirSync('build', { recursive: true });
        await this.page.screenshot({ path: 'build/upload-failure.png', fullPage: true });
        console.log('[DEBUG] screenshot saved to build/upload-failure.png');
      }
    } catch (error) {
      console.log(`[DEBUG] page already closed; nothing more to capture (${errorMessage(error)})`);
    }
  }

  /** Logs the DOM around the declaration text and all checkable controls (diagnosis aid). */
  private async dumpDeclarationDiagnostics(): Promise<void> {
    try {
      const checkboxes = this.page.locator('input[type=checkbox]');
      const count = await checkboxes.count();
      console.log(`[DEBUG] input[type=checkbox] count: ${count}`);
      for (let i = 0; i < count; i++) {
        console.log(`[DEBUG] checkbox ${i} outerHTML: ${await checkboxes.nth(i).evaluate((el) => el.outerHTML)}`);
      }
      const langLinks = this.page.locator(
        'a:has-text("中文"), a[href*=locale], a[href*=lang], select[name*=locale], select[name*=lang]',
      );
      const langCount = await langLinks.count();
      console.log(`[DEBUG] language links: ${langCount}`);
      for (let i = 0; i < langCount && i < 8; i++) {
        console.log(`[DEBUG] lang ${i}: ${await langLinks.nth(i).evaluate((el) => el.outerHTML)}`);
      }
      const texts = this.page.locator(`text=${DECLARATION_TEXT}`);
      const textCount = await texts.count();
      console.log(`[DEBUG] declaration text hits: ${textCount}`);
      for (let i = 0; i < textCount && i < 3; i++) {
        console.log(`[DEBUG] hit ${i} outerHTML: ${await texts.nth(i).evaluate((el) => el.outerHTML)}`);
        console.log(`[DEBUG] hit ${i} row outerHTML: ${await texts.nth(i).evaluate((el) => {
          let node: Element | null = el;
          while (node && node.tagName !== 'TR' && node.tagName !== 'TABLE' && node.tagName !== 'BODY') {
            node = node.parentElement;
          }
          return node ? node.outerHTML.substring(0, 1200) : 'n/a';
        })}`);
      }
    } catch {
      // Diagnostics are best-effort only.
    }
  }
}
