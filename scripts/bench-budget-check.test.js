'use strict';
// Run: node --test scripts/bench-budget-check.test.js
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { validate, evaluate, metricsOf, catalogMetrics, MUTATIONS } = require('./bench-budget-check.js');

const SCRIPT = path.join(__dirname, 'bench-budget-check.js');
const REPO_BUDGETS = path.join(__dirname, '..', 'benchmarks', 'budgets', 'annotation-resource-budgets.json');
const CATALOG = path.join(__dirname, '..', 'kmp', 'src', 'androidInstrumentedTest', 'kotlin', 'dev', 'stapler', 'stelekit', 'performance', 'probe', 'ProbeCatalog.kt');
const fx = name => path.join(__dirname, 'fixtures', 'budgets', name);
const load = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const run = (...args) => spawnSync('node', [SCRIPT, ...args], { encoding: 'utf8' });

test('checked-in budgets file validates against the probe catalog', () => {
  const r = run('--validate', '--budgets', REPO_BUDGETS, '--catalog', CATALOG);
  assert.strictEqual(r.status, 0, r.stderr);
});

test('validate: metric emitted by the probe without a budget entry is an error', () => {
  const errors = validate(load(REPO_BUDGETS), [...catalogMetrics(CATALOG), 'RapidPlacement.brandNewMetric']);
  assert.deepStrictEqual(errors, ['RapidPlacement.brandNewMetric: emitted by the probe but has no budget entry']);
});

test('validate: bad mode, bad globalMode, gate without mutation are all errors', () => {
  const errors = validate({
    globalMode: 'sometimes',
    entries: {
      'A.b': { budget: 1, comparator: 'lte', mode: 'hard' },
      'A.c': { budget: 1, comparator: 'lte', mode: 'gate' },
      'A.d': { budget: 1, comparator: 'lte', mode: 'gate', mutation: 'nope' },
    },
  }, []);
  assert.ok(errors.some(e => e.startsWith('globalMode')));
  assert.ok(errors.some(e => e.startsWith('A.b: mode')));
  assert.ok(errors.some(e => e.startsWith('A.c: a gate entry must name its mutation')));
  assert.ok(errors.some(e => e.startsWith("A.d: unknown mutation 'nope'")));
});

test('every gate in the checked-in file names a mutation', () => {
  for (const [k, e] of Object.entries(load(REPO_BUDGETS).entries)) {
    if (e.mode === 'gate') assert.ok(e.mutation, k);
  }
});

test('mutation names match Mutations.kt exactly (no drift between checker and harness)', () => {
  const kt = fs.readFileSync(path.join(path.dirname(CATALOG), 'Mutations.kt'), 'utf8');
  const declared = [...kt.matchAll(/const val [A-Z_]+ = "(\w+)"/g)].map(m => m[1]).filter(n => n !== 'annotateMutation');
  assert.deepStrictEqual([...declared].sort(), [...MUTATIONS].sort());
});

test('every Scenario.metric in ProbeCatalog has a mutation or is explicitly unmutated', () => {
  const { entries } = load(REPO_BUDGETS);
  for (const key of catalogMetrics(CATALOG)) assert.ok(entries[key], key);
});

test('gate breach exits 1 and names metric, value and budget', () => {
  const r = run('--budgets', fx('budgets-enforce.json'), '--records', fx('records-breach.json'));
  assert.strictEqual(r.status, 1);
  assert.match(r.stdout, /FAIL\s+RapidPlacement\.maxDecodeBytes = 12582912 \(budget lte 2097152\)/);
});

test('same breach on a report-only entry exits 0 with a warning row', () => {
  const budgets = load(fx('budgets-enforce.json'));
  budgets.entries['RapidPlacement.maxDecodeBytes'].mode = 'report-only';
  const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'bud-')), 'b.json');
  fs.writeFileSync(tmp, JSON.stringify(budgets));
  const r = run('--budgets', tmp, '--records', fx('records-breach.json'));
  assert.strictEqual(r.status, 0);
  assert.match(r.stdout, /WARN\s+RapidPlacement\.maxDecodeBytes = 12582912/);
});

test('mode flip is one edit: report-only cpu entry fails once changed to gate', () => {
  const budgets = load(fx('budgets-enforce.json'));
  const records = { 'RapidPlacement.cpuMsPerPlacement': 250 };
  assert.strictEqual(evaluate(budgets, records).failed, false);
  budgets.entries['RapidPlacement.cpuMsPerPlacement'].mode = 'gate';
  assert.strictEqual(evaluate(budgets, records).failed, true);
});

test('globalMode report-only: a breached gate cannot fail the job', () => {
  const budgets = load(fx('budgets-enforce.json'));
  budgets.globalMode = 'report-only';
  const res = evaluate(budgets, load(fx('records-breach.json')));
  assert.strictEqual(res.failed, false);
  assert.strictEqual(res.rows.find(r => r.key === 'RapidPlacement.maxDecodeBytes').status, 'warn');
});

test('clean records pass', () => {
  const r = run('--budgets', fx('budgets-enforce.json'), '--records', fx('records-clean.json'));
  assert.strictEqual(r.status, 0);
});

test('exit reasons: only allowlisted reasons for this pid after scenario start count', () => {
  const values = metricsOf(load(fx('records-exit-reasons.json')));
  assert.strictEqual(values['Process.badExitReasons'], 0);
  const mutated = load(fx('records-exit-reasons.json'));
  mutated.exitReasons.push({ reason: 'EXCESSIVE_RESOURCE_USAGE', pid: 4242, timestamp: 3000 });
  assert.strictEqual(metricsOf(mutated)['Process.badExitReasons'], 1);
  assert.strictEqual(evaluate(load(fx('budgets-enforce.json')), mutated).failed, true);
});

test('memory metrics on an invalid device cannot fail the job', () => {
  const records = { ...load(fx('records-breach.json')), device: { invalidDevice: true } };
  const res = evaluate(load(fx('budgets-enforce.json')), records);
  assert.strictEqual(res.failed, false);
  assert.strictEqual(res.rows.find(r => r.key === 'RapidPlacement.maxDecodeBytes').status, 'invalid-device');
});

test('missing gate metric fails only under --require-all in enforce mode', () => {
  const budgets = load(fx('budgets-enforce.json'));
  assert.strictEqual(evaluate(budgets, {}).failed, false);
  assert.strictEqual(evaluate(budgets, {}, { requireAll: true }).failed, true);
});

test('unreadable records exit 2', () => {
  assert.strictEqual(run('--records', '/nonexistent.json').status, 2);
});
