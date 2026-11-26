import { launchBrowser, type LaunchedBrowser } from './browser.ts';
import { loadCredentials, type Credentials, type RuntimeOptions, type Target } from './config.ts';
import { runOnlineBookingJourney } from './journeys/online-booking.ts';
import { runPortalJourney } from './journeys/portal.ts';
import { runUploadJourney } from './journeys/upload.ts';
import { Recorder, type RunResult } from './report.ts';

async function runTarget(
  target: Target,
  launcher: LaunchedBrowser,
  credentials: Credentials,
  options: RuntimeOptions,
  rec: Recorder,
): Promise<void> {
  switch (target) {
    case 'portal':
      return runPortalJourney(launcher, credentials, rec);
    case 'upload':
      return runUploadJourney(launcher, credentials, options, rec);
    case 'booking':
    case 'stdhs':
    case 'pdqa':
      return runOnlineBookingJourney(launcher, target, credentials, options, rec);
  }
}

/**
 * Runs one target end to end — credentials, browser, journey, cleanup — and
 * always reports: a PASS result, or a FAIL result carrying the first error.
 */
export async function executeTarget(
  target: Target,
  options: RuntimeOptions,
  env: Record<string, string | undefined> = process.env,
): Promise<RunResult> {
  const rec = new Recorder(target);
  let launcher: LaunchedBrowser | null = null;
  try {
    // Fail fast when the credentials of this target are not configured.
    const credentials = loadCredentials(target, env);
    launcher = await launchBrowser(options);
    await runTarget(target, launcher, credentials, options, rec);
    return rec.finish();
  } catch (error) {
    return rec.finish(error);
  } finally {
    if (launcher) {
      await launcher.close();
    }
  }
}
