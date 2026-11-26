// Builds the artifacts that are shipped next to the Lambda function code:
//
//   build/chromium-layer.zip   Lambda layer = the @sparticuz/chromium npm package
//                              (headless Chromium built for serverless) plus its
//                              dependency tree, laid out as node_modules/<package>
//                              so the function can `import("@sparticuz/chromium")`
//                              from /opt/node_modules.
//   build/upload_excel/        Excel file used by the "upload" journey.
//
// Everything comes from npm and the repository itself: no binary is committed.
// Invoked by `npm ci` / `npm install` (prepare) and before packaging/deploying.
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { zipSync } from 'fflate';

const projectDir = join(dirname(fileURLToPath(import.meta.url)), '..');
const nodeModulesDir = join(projectDir, 'node_modules');
const buildDir = join(projectDir, 'build');
const layerZip = join(buildDir, 'chromium-layer.zip');

const CHROMIUM_PACKAGE = '@sparticuz/chromium';
const UPLOAD_EXCEL_NAME = 'for_load_test2.xlsx';

/** One fixed timestamp keeps the zip byte-identical for identical inputs. */
const FIXED_MTIME = new Date('2024-01-01T00:00:00Z');
/** Type declarations, source maps and prose are not needed at runtime. */
const OMIT = /\.(d\.ts|d\.mts|d\.cts|map|md)$/i;

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

/** Package dir of `name`: the nearest node_modules lookup starting from `fromDir`. */
function resolvePackageDir(name, fromDir) {
  let dir = fromDir;
  for (;;) {
    const candidate = join(dir, 'node_modules', ...name.split('/'));
    if (existsSync(join(candidate, 'package.json'))) {
      return candidate;
    }
    const parent = dirname(dir);
    if (parent === dir) {
      throw new Error(`Cannot resolve "${name}" from ${fromDir} — run "npm ci" first.`);
    }
    dir = parent;
  }
}

/** All regular files below `dir`, as POSIX paths relative to `dir`. */
function listFiles(dir, base = dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      listFiles(full, base, out);
    } else if (entry.isFile()) {
      out.push(relative(base, full).split(sep).join('/'));
    }
  }
  return out;
}

/** Production dependency closure of `rootName` as [name, packageDir] pairs. */
function dependencyClosure(rootName) {
  const rootDir = resolvePackageDir(rootName, projectDir);
  const seen = new Map();
  const queue = [[rootName, rootDir]];
  while (queue.length > 0) {
    const [name, dir] = queue.shift();
    if (seen.has(name)) {
      continue;
    }
    seen.set(name, dir);
    const pkg = readJson(join(dir, 'package.json'));
    for (const dep of Object.keys(pkg.dependencies ?? {})) {
      if (!seen.has(dep)) {
        queue.push([dep, resolvePackageDir(dep, dir)]);
      }
    }
  }
  return seen;
}

function buildChromiumLayer() {
  const packages = dependencyClosure(CHROMIUM_PACKAGE);
  const entries = {};
  let fileCount = 0;
  for (const [, dir] of packages) {
    const prefix = relative(nodeModulesDir, dir).split(sep).join('/');
    for (const rel of listFiles(dir)) {
      if (OMIT.test(rel) && !/LICENSE/i.test(rel)) {
        continue;
      }
      // The Brotli binaries are already compressed: store them uncompressed.
      const level = rel.endsWith('.br') ? 0 : 6;
      entries[`node_modules/${prefix}/${rel}`] = [
        new Uint8Array(readFileSync(join(dir, rel))),
        { level, mtime: FIXED_MTIME },
      ];
      fileCount += 1;
    }
  }
  mkdirSync(buildDir, { recursive: true });
  writeFileSync(layerZip, zipSync(entries, {}));
  const version = readJson(join(packages.get(CHROMIUM_PACKAGE), 'package.json')).version;
  return {
    version,
    fileCount,
    packageCount: packages.size,
    bytes: statSync(layerZip).size,
  };
}

function copyUploadExcel() {
  const source = join(projectDir, '..', 'upload_excel', UPLOAD_EXCEL_NAME);
  if (!existsSync(source)) {
    return null;
  }
  const targetDir = join(buildDir, 'upload_excel');
  mkdirSync(targetDir, { recursive: true });
  const target = join(targetDir, UPLOAD_EXCEL_NAME);
  copyFileSync(source, target);
  return { target, bytes: statSync(target).size };
}

function mb(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function main() {
  const layer = buildChromiumLayer();
  console.log(
    `[assets] chromium layer: @sparticuz/chromium ${layer.version}, ` +
      `${layer.packageCount} package(s), ${layer.fileCount} file(s), ${mb(layer.bytes)} -> build/chromium-layer.zip`,
  );
  const excel = copyUploadExcel();
  if (excel) {
    console.log(`[assets] upload excel: ${mb(excel.bytes)} -> build/upload_excel/${UPLOAD_EXCEL_NAME}`);
  } else {
    console.log('[assets] upload excel: upload_excel/' + UPLOAD_EXCEL_NAME + ' not found (upload journey unavailable)');
  }
}

try {
  main();
} catch (error) {
  console.error(`[assets] FAILED: ${error instanceof Error ? error.message : error}`);
  process.exit(1);
}
