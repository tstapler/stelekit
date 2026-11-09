'use strict';
// Run: node --test scripts/bench-budget-calibrate.test.js
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { propose, plan } = require('./bench-budget-calibrate.js');

const KEY = 'RapidPlacement.cpuMsPerPlacement';
const SCRIPT = path.join(__dirname, 'bench-budget-calibrate.js');
const BUDGETS = path.join(__dirname, 'fixtures', 'budgets', 'budgets-enforce.json');
const runs = values => values.map(v => ({ [KEY]: v }));

test('proposal is max * 1.5 (median 141, max 190 -> 285)', () => {
  assert.strictEqual(propose([100, 120, 130, 135, 138, 141, 141, 150, 160, 190]), 285);
});

test('proposal is 2x median when that is larger', () => {
  assert.strictEqual(propose([100, 100, 100, 100, 100, 100, 100, 100, 100, 101]), 200);
});

test('promote with 9 valid runs refuses with "need 10, have 9"', () => {
  const res = plan(runs([1, 2, 3, 4, 5, 6, 7, 8, 9]), { entries: {} }, { promote: true });
  assert.strictEqual(res.refused, true);
  assert.strictEqual(res.lines[0].refusal, 'need 10, have 9');
});

test('promote with 10 valid runs is eligible', () => {
  const res = plan(runs([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]), { entries: {} }, { promote: true });
  assert.strictEqual(res.refused, false);
});

test('promote refuses when any run was on an invalid device', () => {
  const all = [...runs([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]), { [KEY]: 500, device: { invalidDevice: true } }];
  const res = plan(all, { entries: {} }, { promote: true });
  assert.strictEqual(res.refused, true);
  assert.match(res.lines[0].refusal, /invalid-device/);
});

test('cli prints the proposal and does not edit the budgets file', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cal-'));
  [100, 120, 130, 135, 138, 141, 141, 150, 160, 190].forEach((v, i) =>
    fs.writeFileSync(path.join(dir, `r${i}.json`), JSON.stringify({ [KEY]: v })));
  const before = fs.readFileSync(BUDGETS, 'utf8');
  const r = spawnSync('node', [SCRIPT, '--history', dir, '--budgets', BUDGETS], { encoding: 'utf8' });
  assert.strictEqual(r.status, 0);
  assert.match(r.stdout, /propose budget 285/);
  assert.strictEqual(fs.readFileSync(BUDGETS, 'utf8'), before);
});

test('cli --promote with 9 runs exits 1', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cal-'));
  for (let i = 0; i < 9; i++) fs.writeFileSync(path.join(dir, `r${i}.json`), JSON.stringify({ [KEY]: 100 + i }));
  const r = spawnSync('node', [SCRIPT, '--history', dir, '--budgets', BUDGETS, '--promote'], { encoding: 'utf8' });
  assert.strictEqual(r.status, 1);
  assert.match(r.stdout, /need 10, have 9/);
});
