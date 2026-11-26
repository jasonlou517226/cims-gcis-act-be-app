#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { READ_ONLY_TARGETS, loadRuntimeOptions, parseTarget, type RuntimeOptions, type Target } from './config.ts';
import { formatSummary, type RunResult } from './report.ts';
import { executeTarget } from './runner.ts';

const USAGE = `Usage: npm run local -- [options] [target ...]

Targets (same names as run.ps1):
  portal    School Portal — 4 login checks (TEST_USERNAME / TEST_PASSWORD)
  upload    School Portal — login -> Upload Student List -> upload Excel -> confirm -> delete classes
  booking   Online Booking, Child Health Service — captcha login + 16-step walk in EN / 繁體 / 简体
            (OB_LOGIN / OB_PASSWORD)
  stdhs     Online Booking, Student Health Service — captcha login + steps 17-34
            (STDHS_LOGIN / STDHS_PASSWORD)
  pdqa      Online Booking, Families Clinics — captcha login only (PDQA_LOGIN / PDQA_PASSWORD)
  all       portal + booking + stdhs + pdqa (everything that does not write data)
  ocr       alias of booking

Options:
  -H, --headed        show the browser (fullscreen)
  -S, --slowmo        slow motion, 2s per action
      --slowmo-ms N   slow motion with a custom delay
      --no-headed, --no-slowmo   turn the above off (run.ps1: -NH / -NS)
      --pause-ms N    pause after each upload step (default 2000; 0 disables)
  -h, --help          this help

portal and upload run headed + in slow motion by default, the others headless.
Credentials come from the environment or a .env file in this folder (see .env.example).
First run only: npm run install-browser`;

/** Targets that default to a visible, slowed-down browser (as run.ps1 does). */
const WATCH_BY_DEFAULT: readonly Target[] = ['portal', 'upload'];

const DEFAULT_TARGET: Target = 'booking';

function loadDotEnv(): void {
  try {
    process.loadEnvFile();
  } catch {
    // No .env file: the environment alone configures the run.
  }
}

function toNonNegativeInt(raw: string, option: string): number {
  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value) || value < 0) {
    throw new Error(`${option} expects a non-negative number, got "${raw}".`);
  }
  return value;
}

function expandTargets(names: string[]): Target[] {
  return names.flatMap((name) => {
    if (name.trim().toLowerCase() === 'all') {
      return [...READ_ONLY_TARGETS];
    }
    return [parseTarget(name)];
  });
}

async function main(): Promise<number> {
  loadDotEnv();
  // Accept the run.ps1 spellings of the negative switches.
  const argv = process.argv.slice(2).map((arg) => {
    if (arg === '-NH') {
      return '--no-headed';
    }
    return arg === '-NS' ? '--no-slowmo' : arg;
  });
  const { values, positionals } = parseArgs({
    args: argv,
    allowPositionals: true,
    strict: true,
    options: {
      headed: { type: 'boolean', short: 'H' },
      slowmo: { type: 'boolean', short: 'S' },
      'slowmo-ms': { type: 'string' },
      'no-headed': { type: 'boolean' },
      'no-slowmo': { type: 'boolean' },
      'pause-ms': { type: 'string' },
      help: { type: 'boolean', short: 'h' },
    },
  });
  if (values.help) {
    console.log(USAGE);
    return 0;
  }

  const targets = positionals.length > 0 ? expandTargets(positionals) : [DEFAULT_TARGET];
  // Explicit switches win; then the target's default; then the environment.
  const switches: Partial<RuntimeOptions> = {};
  if (values['no-headed']) {
    switches.headed = false;
  } else if (values.headed) {
    switches.headed = true;
  }
  if (values['no-slowmo']) {
    switches.slowMoMs = 0;
  } else if (values['slowmo-ms'] !== undefined) {
    switches.slowMoMs = toNonNegativeInt(values['slowmo-ms'], '--slowmo-ms');
  } else if (values.slowmo) {
    switches.slowMoMs = 2_000;
  }
  if (values['pause-ms'] !== undefined) {
    switches.stepPauseMs = toNonNegativeInt(values['pause-ms'], '--pause-ms');
  }

  const results: RunResult[] = [];
  for (const target of targets) {
    const defaults: Partial<RuntimeOptions> = WATCH_BY_DEFAULT.includes(target)
      ? { headed: true, slowMoMs: 2_000 }
      : {};
    const options = loadRuntimeOptions(process.env, { ...defaults, ...switches });
    const mode = `${options.headed ? 'headed' : 'headless'}${options.slowMoMs > 0 ? `, slow motion ${options.slowMoMs}ms` : ''}`;
    console.log(`\n=== ${target} (${mode}) ===`);
    const result = await executeTarget(target, options);
    console.log(formatSummary(result));
    results.push(result);
  }

  console.log('\n=== overall ===');
  for (const result of results) {
    console.log(`${result.status.padEnd(4)} ${result.target} (${result.checks.length} checks, ${(result.durationMs / 1000).toFixed(1)}s)`);
  }
  return results.every((result) => result.status === 'PASS') ? 0 : 1;
}

try {
  process.exitCode = await main();
} catch (error) {
  console.log(`${USAGE}\n\n${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 2;
}
