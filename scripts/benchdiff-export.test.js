'use strict';
// Run: node --test scripts/benchdiff-export.test.js
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { toBenchdiff } = require('./benchdiff-export.js');

const fixture = name =>
  JSON.parse(fs.readFileSync(path.join(__dirname, 'test-fixtures', name), 'utf8'));

test('jvm: converts load metrics and drops -1 sentinels', () => {
  const out = toBenchdiff(fixture('jvm-summary.json'), 'jvm');
  assert.strictEqual(out.schema, 1);
  const names = out.metrics.map(m => m.name);
  assert.ok(names.includes('phase1TtiMs'));
  // jankLoad* are -1 and jankFactor is negative in the fixture: not measured
  assert.ok(!names.includes('jankLoadP95Ms'));
  assert.ok(!names.includes('jankFactor'));
  const total = out.metrics.find(m => m.name === 'totalMs');
  assert.deepStrictEqual(total, { group: 'jvm/GraphLoad', name: 'totalMs', unit: 'ms', better: 'lower', value: 2 });
  assert.deepStrictEqual(out.env, { gitSha: '046fafe1', graphConfig: 'xlarge', pageCount: '230', branch: 'main' });
});

test('jvm: jankFactor uses unit x when measured', () => {
  const out = toBenchdiff({ jankFactor: 2.5 }, 'jvm');
  assert.deepStrictEqual(out.metrics, [
    { group: 'jvm/WriteLatency', name: 'jankFactor', unit: 'x', better: 'lower', value: 2.5 },
  ]);
});

test('android: writes is higher-is-better, null fields omitted', () => {
  const out = toBenchdiff({ ...fixture('android-summary.json'), safOverhead: null }, 'android');
  const writes = out.metrics.find(m => m.name === 'writes');
  assert.strictEqual(writes.better, 'higher');
  assert.strictEqual(writes.value, 28);
  assert.ok(!out.metrics.some(m => m.name === 'safOverhead'));
  assert.strictEqual(out.env.gitSha, '046fafe1');
});

test('unknown kind throws', () => {
  assert.throws(() => toBenchdiff({}, 'ios'), /unknown kind/);
});
