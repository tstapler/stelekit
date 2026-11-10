'use strict';
// Run: node --test scripts/check-artifact-privacy.test.js
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const SCRIPT = path.join(__dirname, 'check-artifact-privacy.js');
const CLEAN = {
  scenario: 'LibraryScroll',
  pid: 4242,
  device: { memoryClass: 192, largeHeap: false, coilVersion: '3.2.0' },
  metrics: { cpuMsPerScreenful: 120 },
  'LibraryScroll.maxDecodeBytes': 1048576,
  exitReasons: [{ reason: 'USER_REQUESTED', pid: 4242, timestamp: 2000 }],
  note: undefined,
};

function check(files, ...extra) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'priv-'));
  for (const [name, content] of Object.entries(files)) {
    fs.writeFileSync(path.join(dir, name), typeof content === 'string' ? content : JSON.stringify(content));
  }
  return spawnSync('node', [SCRIPT, dir, '--hostname', 'runner-xyz', ...extra], { encoding: 'utf8' });
}

test('clean synthetic record exits 0', () => {
  const r = check({ 'record.json': CLEAN, 'summary.txt': 'RapidPlacement ok synthetic-3.jpg' });
  assert.strictEqual(r.status, 0, r.stderr);
});

for (const [label, planted, rule] of [
  ['home path', '/home/alice/Pictures/IMG_0001.jpg', 'absolute path'],
  ['content URI', 'content://media/external/images/1', 'content/file URI'],
  ['storage path', '/storage/emulated/0/DCIM/x.jpg', 'absolute path'],
  ['file URI', 'file:///sdcard/a', 'content/file URI'],
  ['user file name', 'vacation.jpg', 'non-synthetic file name'],
  ['EXIF GPS tag', 'GPSLatitude=51.5', 'EXIF GPS tag'],
  ['e-mail', 'alice@example.com', 'e-mail address'],
  ['hostname', 'built on runner-xyz', 'runner hostname'],
]) {
  test(`planted ${label} exits 1 naming the file and rule`, () => {
    const r = check({ 'comment.md': `value: ${planted}` });
    assert.strictEqual(r.status, 1);
    assert.match(r.stderr, new RegExp(`comment\\.md: ${rule}`));
  });
}

test('JSON field outside the allowlist exits 1', () => {
  const r = check({ 'record.json': { ...CLEAN, userNotes: 'x' } });
  assert.strictEqual(r.status, 1);
  assert.match(r.stderr, /record\.json: field outside the ProbeRecord allowlist: userNotes/);
});

test('benchdiff canonical JSON passes', () => {
  const r = check({ 'bench.json': { schema: 1, env: { gitSha: 'abc' }, metrics: [{ group: 'android/AnnotateResources', name: 'A.b', unit: 'ms', better: 'lower', value: 1 }] } });
  assert.strictEqual(r.status, 0, r.stderr);
});
