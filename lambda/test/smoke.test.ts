import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  DEFAULT_UPLOAD_FILE,
  READ_ONLY_TARGETS,
  TARGETS,
  loadCredentials,
  loadRuntimeOptions,
  locateUploadFile,
  parseTarget,
} from '../src/config.ts';
import { errMsgOf, extractJsonStringField, respCodeOf } from '../src/pages/online-booking.ts';
import { CheckFailedError, Recorder, formatSummary } from '../src/report.ts';

test('parseTarget accepts every target, is case-insensitive, and maps ocr to booking', () => {
  for (const target of TARGETS) {
    assert.equal(parseTarget(target), target);
    assert.equal(parseTarget(` ${target.toUpperCase()} `), target);
  }
  assert.equal(parseTarget('ocr'), 'booking');
});

test('parseTarget rejects unknown or empty targets and lists the valid ones', () => {
  for (const value of ['', '  ', undefined, 'portal2', 'student']) {
    assert.throws(() => parseTarget(value), (error: unknown) => {
      assert.ok(error instanceof Error);
      assert.match(error.message, /Valid targets: portal, upload, booking, stdhs, pdqa/);
      return true;
    });
  }
});

test('each target reads its own credential variables', () => {
  const env = {
    TEST_USERNAME: 'portal-user',
    TEST_PASSWORD: 'portal-pass',
    OB_LOGIN: 'ob-user',
    OB_PASSWORD: 'ob-pass',
    STDHS_LOGIN: 'stdhs-user',
    STDHS_PASSWORD: 'stdhs-pass',
    PDQA_LOGIN: 'pdqa-login',
    PDQA_PASSWORD: 'pdqa-pass',
  };
  assert.deepEqual(loadCredentials('portal', env), { login: 'portal-user', password: 'portal-pass' });
  assert.deepEqual(loadCredentials('upload', env), { login: 'portal-user', password: 'portal-pass' });
  assert.deepEqual(loadCredentials('booking', env), { login: 'ob-user', password: 'ob-pass' });
  assert.deepEqual(loadCredentials('stdhs', env), { login: 'stdhs-user', password: 'stdhs-pass' });
  assert.deepEqual(loadCredentials('pdqa', env), { login: 'pdqa-login', password: 'pdqa-pass' });
});

test('loadCredentials trims the values', () => {
  assert.deepEqual(loadCredentials('booking', { OB_LOGIN: '  user ', OB_PASSWORD: ' pass\n' }), {
    login: 'user',
    password: 'pass',
  });
});

test('loadCredentials fails fast and names the missing variables (never their values)', () => {
  assert.throws(() => loadCredentials('stdhs', { STDHS_LOGIN: 'someone' }), /Missing credentials for target "stdhs": set STDHS_PASSWORD\./);
  assert.throws(() => loadCredentials('portal', { TEST_PASSWORD: 'secret-value' }), /set TEST_USERNAME\./);
  const error = (() => {
    try {
      loadCredentials('pdqa', {});
      return null;
    } catch (caught) {
      return caught as Error;
    }
  })();
  assert.ok(error);
  assert.equal(error.message, 'Missing credentials for target "pdqa": set PDQA_LOGIN and PDQA_PASSWORD.');
});

test('the upload journey is not among the read-only targets', () => {
  assert.ok(!READ_ONLY_TARGETS.includes('upload'));
  assert.deepEqual([...READ_ONLY_TARGETS], ['portal', 'booking', 'stdhs', 'pdqa']);
});

test('loadRuntimeOptions: defaults, environment and explicit switches', () => {
  assert.deepEqual(loadRuntimeOptions({}), {
    headed: false,
    slowMoMs: 0,
    stepPauseMs: 2000,
    captchaAttempts: 5,
  });
  assert.deepEqual(loadRuntimeOptions({ HEADED: 'true', SLOWMO: 'true', OB_ATTEMPTS: '15', STEP_PAUSE_MS: '0' }), {
    headed: true,
    slowMoMs: 2000,
    stepPauseMs: 0,
    captchaAttempts: 15,
  });
  assert.equal(loadRuntimeOptions({ SLOWMO: 'true', SLOWMO_MS: '750' }).slowMoMs, 750);
  assert.equal(loadRuntimeOptions({ OB_ATTEMPTS: 'not-a-number' }).captchaAttempts, 5);
  assert.equal(loadRuntimeOptions({ OB_ATTEMPTS: '0' }).captchaAttempts, 1);
  assert.deepEqual(loadRuntimeOptions({}, { headed: true, slowMoMs: 500 }), {
    headed: true,
    slowMoMs: 500,
    stepPauseMs: 2000,
    captchaAttempts: 5,
  });
});

test('loadRuntimeOptions: nothing is ever visible or slowed down on Lambda', () => {
  const lambda = { AWS_LAMBDA_JS_RUNTIME: 'nodejs22.x', HEADED: 'true', SLOWMO: 'true' };
  assert.deepEqual(loadRuntimeOptions(lambda), {
    headed: false,
    slowMoMs: 0,
    stepPauseMs: 0,
    captchaAttempts: 5,
  });
  assert.equal(loadRuntimeOptions(lambda, { headed: true, slowMoMs: 1000, stepPauseMs: 1 }).headed, false);
});

test('respCodeOf reads JSON bodies and falls back to a text search', () => {
  assert.equal(respCodeOf('{"respCode":0,"errMsg":null}'), 0);
  assert.equal(respCodeOf('{ "respCode" : 113 , "errMsg" : "x" }'), 113);
  assert.equal(respCodeOf('<html>"respCode": -5</html>'), -5);
  assert.equal(respCodeOf('{"respCode":"113"}'), null);
  assert.equal(respCodeOf('{"other":1}'), null);
  assert.equal(respCodeOf('not json'), null);
});

test('errMsgOf reads JSON bodies and falls back to a text search', () => {
  assert.equal(errMsgOf('{"respCode":100,"errMsg":"Wrong password"}'), 'Wrong password');
  assert.equal(errMsgOf('{"respCode":0,"errMsg":null}'), null);
  assert.equal(errMsgOf('pre "errMsg": "captcha expired" post'), 'captcha expired');
  assert.equal(errMsgOf('{"respCode":100}'), null);
});

test('extractJsonStringField finds plain string fields; absent or empty ones are null', () => {
  const body = '{"respCode":0,"data":{"captchaLength":"4","captchaForACT":"LRS6","captchaData":"data:image/jpeg;base64,/9j"}}';
  assert.equal(extractJsonStringField(body, 'captchaForACT'), 'LRS6');
  assert.equal(extractJsonStringField(body, 'captchaLength'), '4');
  assert.equal(extractJsonStringField(body, 'nope'), null);
  assert.equal(extractJsonStringField('{"captchaForACT":""}', 'captchaForACT'), null);
});

test('Recorder logs and records every check, and throws on the first failing one', () => {
  const lines: string[] = [];
  const originalLog = console.log;
  console.log = (line: string) => {
    lines.push(String(line));
  };
  try {
    const rec = new Recorder('booking');
    rec.check('a passing check', true, 'with detail');
    rec.journeyCheck('step 1: a passing step', true);
    assert.throws(() => rec.check('a failing check', false, 'why'), CheckFailedError);
    assert.throws(() => rec.journeyCheck('step 2: a failing step', false), CheckFailedError);

    // Failed checks stay in the report, and one failed check makes the journey FAIL
    // even when finish() is called without the error.
    const result = rec.finish();
    assert.equal(result.status, 'FAIL');
    assert.equal(result.error, 'Check failed: a failing check');
    assert.deepEqual(
      result.checks.map((check) => `${check.status} ${check.step}`),
      ['PASS a passing check', 'PASS step 1: a passing step', 'FAIL a failing check', 'FAIL step 2: a failing step'],
    );
    assert.equal(result.checks[0]?.detail, 'with detail');
  } finally {
    console.log = originalLog;
  }
  assert.deepEqual(lines, [
    '[OK]   a passing check (with detail)',
    '[JOURNEY] ok: step 1: a passing step',
    '[FAIL] a failing check (why)',
    '[JOURNEY] FAILED: step 2: a failing step',
  ]);
});

test('Recorder.finish carries the first error of a failed journey', () => {
  const rec = new Recorder('portal');
  let thrown: unknown;
  try {
    rec.check('something is wrong', false, 'detail');
  } catch (error) {
    thrown = error;
  }
  const result = rec.finish(thrown);
  assert.equal(result.status, 'FAIL');
  assert.equal(result.checks[0]?.status, 'FAIL');
  assert.equal(result.error, 'Check failed: something is wrong');
  assert.match(formatSummary(result), /\[SMOKE\] FAIL target=portal checks=1 passed=0 failed=1 duration=[\d.]+s/);
  assert.match(formatSummary(result), /FAIL  something is wrong \(detail\)/);
});

test('locateUploadFile finds the bundled Excel from any working directory below the repository', () => {
  const root = makeScratchDir();
  try {
    const bundled = join(root, DEFAULT_UPLOAD_FILE);
    mkdirSync(join(root, 'upload_excel'));
    writeFileSync(bundled, 'xlsx');
    const deep = join(root, 'a', 'b');
    mkdirSync(deep, { recursive: true });
    assert.equal(locateUploadFile({}, deep), bundled);
    assert.equal(locateUploadFile({}, root), bundled);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('locateUploadFile honours UPLOAD_FILE and falls back to the packaged copy', () => {
  const root = makeScratchDir();
  try {
    const custom = join(root, 'custom.xlsx');
    writeFileSync(custom, 'xlsx');
    assert.equal(locateUploadFile({ UPLOAD_FILE: custom }, root), custom);
    writeFileSync(join(root, 'other.xlsx'), 'xlsx');
    assert.equal(locateUploadFile({ UPLOAD_FILE: 'other.xlsx' }, root), join(root, 'other.xlsx'));

    const packaged = join(root, 'build', DEFAULT_UPLOAD_FILE);
    mkdirSync(join(root, 'build', 'upload_excel'), { recursive: true });
    writeFileSync(packaged, 'xlsx');
    assert.equal(locateUploadFile({}, root), packaged);

    rmSync(join(root, 'build'), { recursive: true, force: true });
    assert.equal(locateUploadFile({}, root), join(root, DEFAULT_UPLOAD_FILE));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

function makeScratchDir(): string {
  return mkdtempSync(join(tmpdir(), 'smoke-test-'));
}
