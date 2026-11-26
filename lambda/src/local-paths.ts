import { join } from 'node:path';

/**
 * Must be evaluated before playwright-core loads (it reads this variable at
 * module load). Local runs use the Chromium installed by "npm run
 * install-browser" into <project>/.browsers — a folder of this project, not
 * the Playwright cache shared with the Java suite in the repository root, so
 * that neither project's "install" garbage-collects the other's browsers.
 * On Lambda the browser comes from the Chromium layer instead.
 */
if (!process.env.AWS_LAMBDA_JS_RUNTIME) {
  process.env.PLAYWRIGHT_BROWSERS_PATH ??= join(process.cwd(), '.browsers');
}
