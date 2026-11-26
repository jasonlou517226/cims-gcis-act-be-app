// Bundles the Lambda handler with esbuild: src/handler.ts -> dist/handler.js.
//
// One CommonJS file holding only our own code. Two packages stay external on
// purpose and are shipped next to the bundle instead of inside it:
//   - playwright-core  locates its driver relative to its own package folder
//                      (it ships in the function package, see serverless.yml)
//   - @sparticuz/chromium  resolves its binaries inside its package, and the
//                      package only exists in the Chromium layer (/opt)
// Invoked by `npm ci` / `npm install` (prepare); run `npm run build` after
// editing the sources and before packaging by hand.
import { mkdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const projectDir = join(dirname(fileURLToPath(import.meta.url)), '..');
const outfile = join(projectDir, 'dist', 'handler.js');

function kb(bytes) {
  return `${(bytes / 1024).toFixed(0)} KB`;
}

async function main() {
  mkdirSync(dirname(outfile), { recursive: true });
  const result = await build({
    entryPoints: [join(projectDir, 'src', 'handler.ts')],
    outfile,
    bundle: true,
    // CommonJS: the Lambda package carries no package.json, so Node treats
    // the .js file as CommonJS.
    format: 'cjs',
    platform: 'node',
    target: 'node22',
    sourcemap: true,
    legalComments: 'none',
    external: ['playwright-core', '@sparticuz/chromium'],
    metafile: true,
    logLevel: 'warning',
  });
  if (result.errors.length > 0) {
    throw new Error(`${result.errors.length} build error(s)`);
  }
  const inputs = Object.keys(result.metafile?.inputs ?? {});
  console.log(`[build] function bundle: ${inputs.length} source file(s) -> dist/handler.js (${kb(statSync(outfile).size)})`);
}

try {
  await main();
} catch (error) {
  console.error(`[build] FAILED: ${error instanceof Error ? error.message : error}`);
  process.exit(1);
}
