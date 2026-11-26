// Installs the Chromium used for LOCAL runs (playwright-core) into this
// project's own browser folder: <project>/.browsers
//
// Why not the shared Playwright cache (%LOCALAPPDATA%\ms-playwright): every
// `playwright install` garbage-collects the browsers that no *linked*
// installation uses. The Java suite in the repository root is a different
// Playwright version (and not linked), so sharing the cache makes each
// project delete the other's Chromium. A separate folder keeps them apart.
import { spawnSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

const projectDir = join(import.meta.dirname, '..');
const browsersDir = join(projectDir, '.browsers');
mkdirSync(browsersDir, { recursive: true });
process.env.PLAYWRIGHT_BROWSERS_PATH = browsersDir;

const cli = join(dirname(createRequire(import.meta.url).resolve('playwright-core')), 'cli.js');
const result = spawnSync(process.execPath, [cli, 'install', ...process.argv.slice(2)], {
  stdio: 'inherit',
  env: process.env,
});
process.exitCode = result.status ?? 1;
