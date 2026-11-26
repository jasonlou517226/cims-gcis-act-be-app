import assert from 'node:assert/strict';
import test from 'node:test';
import { runSmoke, SmokeTestFailure } from '../src/handler.ts';
import type { RunResult } from '../src/report.ts';

/** Environment variables that could make the handler find credentials or believe it is on Lambda. */
const SENSITIVE = [
  'TEST_USERNAME',
  'TEST_PASSWORD',
  'OB_LOGIN',
  'OB_PASSWORD',
  'STDHS_LOGIN',
  'STDHS_PASSWORD',
  'PDQA_LOGIN',
  'PDQA_PASSWORD',
  'AWS_LAMBDA_JS_RUNTIME',
  'DEFAULT_TARGET',
];

function clearSensitiveEnvironment(): Record<string, string | undefined> {
  const saved = { ...process.env };
  for (const name of SENSITIVE) {
    delete process.env[name];
  }
  return saved;
}

/** Resolves with the failed result carried by the SmokeTestFailure the handler rejects with. */
async function failureOf(promise: Promise<RunResult>): Promise<RunResult> {
  let failure: unknown;
  try {
    await promise;
  } catch (error) {
    failure = error;
  }
  assert.ok(failure instanceof SmokeTestFailure, `expected a SmokeTestFailure, got ${String(failure)}`);
  assert.equal(failure.name, 'SmokeTestFailure');
  return failure.result;
}

test('handler: the event names the target; missing credentials reject with SmokeTestFailure (no browser started)', async () => {
  const saved = clearSensitiveEnvironment();
  const lines: string[] = [];
  const originalLog = console.log;
  console.log = (line: string) => {
    lines.push(String(line));
  };
  try {
    const result = await failureOf(runSmoke({ target: 'stdhs' }));
    assert.equal(result.status, 'FAIL');
    assert.equal(result.target, 'stdhs');
    assert.match(result.error ?? '', /Missing credentials for target "stdhs": set STDHS_LOGIN and STDHS_PASSWORD\./);
    assert.ok(result.checks.length === 0);
    // The rejection message is the summary, so the invocation error is readable in CloudWatch.
    assert.match(lines.join('\n'), /\[SMOKE\] FAIL target=stdhs/);
  } finally {
    console.log = originalLog;
    Object.assign(process.env, saved);
  }
});

test('handler: without an event target, DEFAULT_TARGET decides and portal is the last resort', async () => {
  const saved = clearSensitiveEnvironment();
  try {
    process.env.DEFAULT_TARGET = 'booking';
    assert.equal((await failureOf(runSmoke({}))).target, 'booking');
    assert.equal((await failureOf(runSmoke({ target: '   ' }))).target, 'booking');

    delete process.env.DEFAULT_TARGET;
    assert.equal((await failureOf(runSmoke({}))).target, 'portal');
    assert.equal((await failureOf(runSmoke(undefined))).target, 'portal');

    process.env.DEFAULT_TARGET = 'ocr'; // alias accepted like on the command line
    assert.equal((await failureOf(runSmoke({}))).target, 'booking');
  } finally {
    Object.assign(process.env, saved);
  }
});

test('handler: an event target overrides DEFAULT_TARGET, and an unknown one is rejected before anything runs', async () => {
  const saved = clearSensitiveEnvironment();
  try {
    process.env.DEFAULT_TARGET = 'portal';
    assert.equal((await failureOf(runSmoke({ target: 'pdqa' }))).target, 'pdqa');

    let failure: unknown;
    try {
      await runSmoke({ target: 'nope' });
    } catch (error) {
      failure = error;
    }
    assert.ok(failure instanceof Error);
    assert.equal(failure instanceof SmokeTestFailure, false);
    assert.match(failure.message, /Unknown target "nope"/);
  } finally {
    Object.assign(process.env, saved);
  }
});
