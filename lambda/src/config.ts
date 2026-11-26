import { existsSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';

/** A smoke-test target (same names as run.ps1 in the Java project). */
export type Target = 'portal' | 'upload' | 'booking' | 'stdhs' | 'pdqa';

export const TARGETS: readonly Target[] = ['portal', 'upload', 'booking', 'stdhs', 'pdqa'];

/** Target used when neither the event nor DEFAULT_TARGET names one. */
export const DEFAULT_TARGET: Target = 'portal';

/** Targets that never write data, i.e. are safe to run unattended on a schedule. */
export const READ_ONLY_TARGETS: readonly Target[] = ['portal', 'booking', 'stdhs', 'pdqa'];

/** "ocr" is kept as an alias of "booking" (run.ps1 naming). */
const TARGET_ALIASES: Readonly<Record<string, Target>> = { ocr: 'booking' };

/** Environment variables that carry the credentials of each target. */
const CREDENTIAL_VARIABLES: Readonly<Record<Target, readonly [login: string, password: string]>> = {
  portal: ['TEST_USERNAME', 'TEST_PASSWORD'],
  upload: ['TEST_USERNAME', 'TEST_PASSWORD'],
  booking: ['OB_LOGIN', 'OB_PASSWORD'],
  stdhs: ['STDHS_LOGIN', 'STDHS_PASSWORD'],
  pdqa: ['PDQA_LOGIN', 'PDQA_PASSWORD'],
};

export const DEFAULT_UPLOAD_FILE = 'upload_excel/for_load_test2.xlsx';

export interface Credentials {
  login: string;
  password: string;
}

export interface RuntimeOptions {
  /** Show the browser window (local only — always off on Lambda). */
  headed: boolean;
  /** Delay Playwright applies to every operation, to watch the run (local only). */
  slowMoMs: number;
  /** Pause after each step of the upload journey (local only). */
  stepPauseMs: number;
  /** Captcha submissions allowed per Online Booking login. */
  captchaAttempts: number;
}

type Env = Record<string, string | undefined>;

export function parseTarget(value: string | null | undefined): Target {
  const name = (value ?? '').trim().toLowerCase();
  const target = (TARGETS as readonly string[]).includes(name) ? (name as Target) : TARGET_ALIASES[name];
  if (!target) {
    throw new Error(`Unknown target "${value ?? ''}". Valid targets: ${TARGETS.join(', ')} (alias: ocr).`);
  }
  return target;
}

export function isLambdaRuntime(env: Env = process.env): boolean {
  return Boolean(env.AWS_LAMBDA_JS_RUNTIME);
}

/** Credentials of a target; aborts with a clear message when any is missing. */
export function loadCredentials(target: Target, env: Env = process.env): Credentials {
  const [loginVar, passwordVar] = CREDENTIAL_VARIABLES[target];
  const login = env[loginVar]?.trim() ?? '';
  const password = env[passwordVar]?.trim() ?? '';
  if (!login || !password) {
    const missing = [loginVar, passwordVar].filter((name) => !env[name]?.trim());
    throw new Error(`Missing credentials for target "${target}": set ${missing.join(' and ')}.`);
  }
  return { login, password };
}

function intEnv(env: Env, name: string, fallback: number): number {
  const parsed = Number.parseInt(env[name] ?? '', 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function loadRuntimeOptions(env: Env = process.env, overrides: Partial<RuntimeOptions> = {}): RuntimeOptions {
  const options: RuntimeOptions = {
    headed: env.HEADED === 'true',
    slowMoMs: env.SLOWMO_MS !== undefined ? intEnv(env, 'SLOWMO_MS', 2000) : env.SLOWMO === 'true' ? 2000 : 0,
    stepPauseMs: intEnv(env, 'STEP_PAUSE_MS', 2000),
    captchaAttempts: Math.max(1, intEnv(env, 'OB_ATTEMPTS', 5)),
  };
  const switches = Object.fromEntries(
    Object.entries(overrides).filter(([, value]) => value !== undefined),
  );
  Object.assign(options, switches);
  if (isLambdaRuntime(env)) {
    // Nothing to watch inside Lambda, and no time to waste.
    return { ...options, headed: false, slowMoMs: 0, stepPauseMs: 0 };
  }
  return options;
}

/**
 * Absolute path of the Excel used by the upload journey: $UPLOAD_FILE when
 * set, otherwise the bundled upload_excel/for_load_test2.xlsx, searched from
 * the working directory upwards and then inside the Lambda package. The path
 * is returned even when the file does not exist (callers report the error).
 */
export function locateUploadFile(env: Env = process.env, cwd: string = process.cwd()): string {
  const raw = env.UPLOAD_FILE?.trim() || DEFAULT_UPLOAD_FILE;
  if (isAbsolute(raw)) {
    return resolve(raw);
  }
  for (let dir = cwd; ; dir = dirname(dir)) {
    const candidate = join(dir, raw);
    if (existsSync(candidate)) {
      return candidate;
    }
    if (dirname(dir) === dir) {
      break;
    }
  }
  const packaged = join(cwd, 'build', raw);
  if (existsSync(packaged)) {
    return packaged;
  }
  return resolve(cwd, raw);
}
