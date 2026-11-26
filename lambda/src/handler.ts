import type { Handler } from 'aws-lambda';
import { DEFAULT_TARGET, loadRuntimeOptions, parseTarget } from './config.ts';
import { formatSummary, type RunResult } from './report.ts';
import { executeTarget } from './runner.ts';

/** Input of the scheduled function: which target to run (see serverless.yml). */
export interface SmokeEvent {
  target?: string;
}

/** Thrown when a target fails, so Lambda counts the invocation as an error (alarm metric). */
export class SmokeTestFailure extends Error {
  readonly result: RunResult;

  constructor(result: RunResult) {
    super(formatSummary(result));
    this.name = 'SmokeTestFailure';
    this.result = result;
  }
}

/**
 * Runs one target: the target named in the event (DEFAULT_TARGET when absent,
 * portal when that is unset too). Resolves with its result, or rejects with
 * SmokeTestFailure so that Lambda counts the invocation as an error — which
 * is what the CloudWatch alarm watches.
 */
export async function runSmoke(event: SmokeEvent | undefined): Promise<RunResult> {
  const requested = event && typeof event.target === 'string' ? event.target.trim() : '';
  const target = parseTarget(requested || process.env.DEFAULT_TARGET || DEFAULT_TARGET);
  console.log(`[SMOKE] start target=${target} node=${process.version}`);
  const result = await executeTarget(target, loadRuntimeOptions());
  console.log(formatSummary(result));
  if (result.status === 'FAIL') {
    throw new SmokeTestFailure(result);
  }
  return result;
}

/** Lambda entry point (serverless.yml: handler: dist/handler.run). */
export const run: Handler<SmokeEvent, RunResult> = (event) => runSmoke(event);
