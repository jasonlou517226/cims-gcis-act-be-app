import { existsSync } from 'node:fs';
import { newPage, type LaunchedBrowser } from '../browser.ts';
import { locateUploadFile, type Credentials, type RuntimeOptions } from '../config.ts';
import { SchoolPortalPage } from '../pages/school-portal.ts';
import type { Recorder } from '../report.ts';

/**
 * School Portal "Upload Student List" journey (port of LoginPage.main()):
 * login → open Upload Student List → pre-clean leftover classes → tick the
 * declaration → 瀏覽 (pick the Excel) → 上載 → tick the declaration →
 * 確認及儲存 → "Save Student List" pop-up Yes → delete every class under
 * 已上載班別 → wait until the list reports that no class is left.
 *
 * This journey writes to the portal (and cleans up after itself), so it is
 * not part of the scheduled targets by default.
 */
export async function runUploadJourney(
  launcher: LaunchedBrowser,
  credentials: Credentials,
  options: RuntimeOptions,
  rec: Recorder,
): Promise<void> {
  const uploadPath = locateUploadFile();
  if (!existsSync(uploadPath)) {
    throw new Error(`Upload file not found: ${uploadPath} (set UPLOAD_FILE to override).`);
  }
  // Legacy government test portal: navigations (form submits, Excel parsing)
  // can easily exceed the 30s default on slow runs.
  const { context, page } = await newPage(launcher.browser, { navigationTimeoutMs: 180_000 });
  const settle = async (): Promise<void> => {
    if (options.stepPauseMs > 0) {
      await page.waitForTimeout(options.stepPauseMs);
    }
  };
  try {
    const portal = new SchoolPortalPage(page);
    await portal.open();
    rec.check('upload: login form is visible', await portal.isLoginFormVisible());

    // ---- Step 1: Login ----
    await portal.loginAs(credentials.login, credentials.password);
    await portal.confirmSessionConflictIfPresent();
    const loggedIn = await portal.waitForLoginSuccess(60_000);
    rec.check('upload: login accepted (left SchoolPortalWeb/login)', loggedIn, `URL: ${portal.currentUrl()}`);
    await settle();

    // ---- Go to "Upload Student List" ----
    await portal.openUploadStudentList();
    rec.check('upload: landed on the Upload Student List page', portal.isOnUploadStudentListPage(), `URL: ${portal.currentUrl()}`);
    await settle();

    // ---- Pre-cleanup: remove classes left over by previous runs. A leftover
    // class makes the portal reject the duplicate upload, and the confirm
    // section then never appears. ----
    if (await portal.hasUploadedClassToDelete(5_000)) {
      const removed = await portal.deleteAllUploadedClasses(1_500);
      console.log(`[SMOKE] pre-cleanup deleted classes: ${removed}`);
      await page.reload({ waitUntil: 'domcontentloaded' });
      await settle();
    }

    // ---- Step 2: tick the declaration checkbox ----
    await portal.checkUploadDeclaration();
    console.log('[SMOKE] declaration ticked (upload section)');
    await settle();

    // ---- Step 3: 瀏覽 → pick the Excel file ----
    await portal.browseAndSelectFile(uploadPath);
    const shownName = await portal.displayedFileName();
    rec.check('upload: file name shown in the readonly field', shownName.trim() !== '', `field shows: ${shownName}`);
    console.log(`[SMOKE] file selected: ${uploadPath} (field shows: ${shownName})`);
    await settle();

    // ---- Step 4: 上載 (waits for the confirm section) ----
    await portal.clickUploadButton();
    rec.check('upload: confirm-and-save section appeared after upload', await portal.isConfirmSaveVisible());
    await settle();

    // ---- Step 5: tick the declaration checkbox → 確認及儲存 ----
    await portal.checkConfirmDeclaration();
    console.log('[SMOKE] declaration ticked (confirm section)');
    await settle();
    await portal.clickConfirmAndSave();
    await settle();

    // ---- Step 6: "Save Student List" pop-up → Yes ----
    await portal.clickSaveDialogYes(30_000);
    console.log('[SMOKE] save dialog confirmed (Yes)');
    await page.waitForLoadState('domcontentloaded');
    await settle();

    // ---- Step 7: delete every class under 已上載班別 ----
    let deletedSome = false;
    if (await portal.hasUploadedClassToDelete(30_000)) {
      const deleted = await portal.deleteAllUploadedClasses(1_500);
      rec.check(
        'upload: uploaded classes deleted',
        deleted > 0 && (await portal.deleteClassIconsCount()) === 0,
        `deleted: ${deleted}`,
      );
      console.log(`[SMOKE] deleted classes: ${deleted}`);
      deletedSome = true;
    } else {
      console.log('[SMOKE] no uploaded classes to delete');
    }

    // ---- Step 8: wait until the list itself confirms that no class is left
    // (沒有已上載的班別 / No class has been found) ----
    const noClassLeft = await portal.waitForNoUploadedClass(deletedSome ? 60_000 : 15_000);
    if (deletedSome) {
      rec.check(
        'upload: list confirms no uploaded class left (沒有已上載的班別 / No class has been found)',
        noClassLeft,
      );
    }
    console.log(`[SMOKE] no-class keyword shown: ${noClassLeft}`);
    console.log('[SMOKE] OK — login → upload → confirm → delete → no class left passed');
  } finally {
    await context.close();
  }
}
