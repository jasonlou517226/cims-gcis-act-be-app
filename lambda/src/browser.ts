import { randomUUID } from 'node:crypto';
import { rm } from 'node:fs/promises';
import { createRequire } from 'node:module';
// Keeps the local browsers out of the shared cache: must stay above the playwright-core import.
import './local-paths.ts';
import { chromium, type Browser, type BrowserContext, type Page } from 'playwright-core';
import { isLambdaRuntime, type RuntimeOptions } from './config.ts';

/** A running browser plus the means to clean up whatever was created for it. */
export interface LaunchedBrowser {
  readonly browser: Browser;

  close(): Promise<void>;
}

/** Default timeout of page operations (same as TestBase). */
export const DEFAULT_TIMEOUT_MS = 30_000;

/** Lambda layers are mounted here; the Chromium layer provides node_modules/@sparticuz/chromium. */
const LAYER_ROOT = '/opt';

type ChromiumPackage = (typeof import('@sparticuz/chromium'))['default'];

/**
 * The "@sparticuz/chromium" package of the Chromium layer.
 *
 * Node's module lookup never searches the layer by itself — neither `import()`
 * nor `require()` look in /opt, and the ESM loader also ignores NODE_PATH — so
 * the layer's node_modules is addressed explicitly. The package is ESM-only;
 * loading it through require() needs Node.js 22.12 or later (Lambda's
 * nodejs22.x qualifies).
 */
function loadChromiumPackage(): ChromiumPackage {
  try {
    const requireFromLayer = createRequire(`${LAYER_ROOT}/index.js`);
    return requireFromLayer('@sparticuz/chromium').default as ChromiumPackage;
  } catch (error) {
    throw new Error(
      `Cannot load "@sparticuz/chromium" from the Chromium layer (${LAYER_ROOT}/node_modules) — `
        + 'is the layer attached to the function? '
        + `(${error instanceof Error ? error.message : String(error)})`,
    );
  }
}

/**
 * Starts Chromium the way the current environment allows:
 * - on Lambda, the headless build shipped by the "@sparticuz/chromium" layer;
 * - locally, the Chromium installed by "npm run install-browser" (playwright-core),
 *   optionally visible and in slow motion so the run can be followed.
 */
export async function launchBrowser(options: RuntimeOptions): Promise<LaunchedBrowser> {
  return isLambdaRuntime() ? launchLambdaChromium() : launchLocalChromium(options);
}

async function launchLambdaChromium(): Promise<LaunchedBrowser> {
  const sparticuz = loadChromiumPackage();
  // The package keeps its defaults (graphics/WebGL on, as with a regular
  // Chromium): the tested sites never needed anything else, and this is not
  // the place for an unverified optimisation. Loading the package also set
  // FONTCONFIG_PATH / HOME / LD_LIBRARY_PATH for Lambda, which the browser
  // process inherits.
  // Playwright leaves its profile directory behind on warm invocations; a
  // per-invocation directory (removed on close) keeps /tmp from filling up.
  const userDataDir = `/tmp/pw-${randomUUID()}`;
  const browser = await chromium.launch({
    args: [...sparticuz.args, `--user-data-dir=${userDataDir}`],
    executablePath: await sparticuz.executablePath(),
    headless: true,
  });
  return {
    browser,
    async close(): Promise<void> {
      await browser.close().catch(() => undefined);
      await rm(userDataDir, { recursive: true, force: true }).catch(() => undefined);
    },
  };
}

async function launchLocalChromium(options: RuntimeOptions): Promise<LaunchedBrowser> {
  let browser: Browser;
  try {
    browser = await chromium.launch({
      headless: !options.headed,
      slowMo: options.slowMoMs > 0 ? options.slowMoMs : undefined,
      args: options.headed ? ['--start-fullscreen'] : [],
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (/Executable doesn't exist/i.test(message)) {
      throw new Error('Local Chromium is not installed — run "npm run install-browser" once.');
    }
    throw error;
  }
  return {
    browser,
    async close(): Promise<void> {
      await browser.close();
    },
  };
}

/**
 * Opens an isolated context + page. The tested government sites use
 * self-signed/odd certificates, so HTTPS errors are ignored (as TestBase does).
 */
export async function newPage(
  browser: Browser,
  options: { navigationTimeoutMs?: number } = {},
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
  if (options.navigationTimeoutMs !== undefined) {
    page.setDefaultNavigationTimeout(options.navigationTimeoutMs);
  }
  return { context, page };
}
