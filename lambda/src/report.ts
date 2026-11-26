import type { Target } from './config.ts';

export type Status = 'PASS' | 'FAIL';

export interface Check {
  step: string;
  status: Status;
  detail?: string;
  /** Wall-clock milliseconds elapsed since the previous check. */
  durationMs: number;
}

export interface RunResult {
  target: Target;
  status: Status;
  startedAt: string;
  durationMs: number;
  checks: Check[];
  error?: string;
}

/** Thrown by Recorder on the first failed check; ends the target's journey. */
export class CheckFailedError extends Error {
  declare readonly step: string;

  constructor(step: string, message: string) {
    super(message);
    this.name = 'CheckFailedError';
    this.step = step;
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message.trim().split('\n')[0];
  }
  return String(error).trim().split('\n')[0];
}

/** Records every check of one target and logs it (mirrors check()/journeyCheck() of the Java pages). */
export class Recorder {
  readonly target: Target;

  readonly checks: Check[] = [];

  private readonly startedAt = new Date();

  private lastMark = Date.now();

  /** Message of the first failed check, so finish() cannot report a PASS for a failed journey. */
  private firstFailure: string | null = null;

  constructor(target: Target) {
    this.target = target;
  }

  private record(step: string, ok: boolean, detail: string, message: string): void {
    const now = Date.now();
    this.checks.push({
      step,
      status: ok ? 'PASS' : 'FAIL',
      detail: detail || undefined,
      durationMs: now - this.lastMark,
    });
    this.lastMark = now;
    if (!ok) {
      this.firstFailure ??= message;
    }
  }

  /** Smoke check: logs "[OK]"/"[FAIL]" and throws as soon as one does not hold. */
  check(step: string, ok: boolean, detail = ''): void {
    const message = `Check failed: ${step}`;
    this.record(step, ok, detail, message);
    console.log(`${ok ? '[OK]   ' : '[FAIL] '}${step}${detail ? ` (${detail})` : ''}`);
    if (!ok) {
      throw new CheckFailedError(step, message);
    }
  }

  /** Journey step: logs "[JOURNEY] ok" and throws as soon as one is wrong. */
  journeyCheck(step: string, ok: boolean): void {
    const message = `Journey check failed: ${step}`;
    this.record(step, ok, '', message);
    if (!ok) {
      console.log(`[JOURNEY] FAILED: ${step}`);
      throw new CheckFailedError(step, message);
    }
    console.log(`[JOURNEY] ok: ${step}`);
  }

  /** Builds the result. A journey is PASS only when no check failed, whatever `error` says. */
  finish(error?: unknown): RunResult {
    const failed = error !== undefined || this.firstFailure !== null;
    return {
      target: this.target,
      status: failed ? 'FAIL' : 'PASS',
      startedAt: this.startedAt.toISOString(),
      durationMs: Date.now() - this.startedAt.getTime(),
      checks: this.checks,
      error: failed ? (error !== undefined ? errorMessage(error) : (this.firstFailure ?? undefined)) : undefined,
    };
  }
}

export function formatSummary(result: RunResult): string {
  const passed = result.checks.filter((check) => check.status === 'PASS').length;
  const lines = [
    `[SMOKE] ${result.status} target=${result.target} checks=${result.checks.length} `
      + `passed=${passed} failed=${result.checks.length - passed} duration=${(result.durationMs / 1000).toFixed(1)}s`,
  ];
  for (const check of result.checks) {
    lines.push(`  ${check.status.padEnd(5)} ${check.step}${check.detail ? ` (${check.detail})` : ''}`);
  }
  if (result.error) {
    lines.push(`  error: ${result.error}`);
  }
  return lines.join('\n');
}
