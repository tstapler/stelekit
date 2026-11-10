#!/usr/bin/env node
// Fails (exit 1) when anything CI is about to upload or post could carry user data.
//
//   node scripts/check-artifact-privacy.js <file|dir>... [--hostname <name>]
//
// Run before upload-artifact and before the sticky comment step. Only synthetic fixtures may reach
// these outputs (plan B.6d): no absolute paths, content:// or file:// URIs, non-synthetic image file
// names, EXIF GPS tags, e-mail-like strings or the runner hostname; JSON keys must come from the
// ProbeRecord / benchdiff allowlist.

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const STRING_RULES = [
  ['absolute path', /(?:^|[\s"'=(])(?:\/(?:home|Users|storage|data|sdcard|mnt|tmp)\/|[A-Za-z]:\\)/],
  ['content/file URI', /\b(?:content|file):\/\//],
  ['EXIF GPS tag', /GPS(?:Latitude|Longitude|Altitude|Position)|\bGPSInfo\b/],
  ['e-mail address', /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/],
];
const IMAGE_NAME = /([^\s"'/\\]+\.(?:jpe?g|png|heic|webp|gif))\b/gi;

const FIXED_KEYS = new Set([
  // ProbeRecord envelope
  'scenario', 'metrics', 'device', 'pid', 'scenarioStartMs', 'exitReasons', 'reason', 'timestamp',
  'memoryClass', 'largeHeap', 'coilVersion', 'sdkInt', 'memTotalKb', 'invalidDevice', 'valid',
  'abi', 'model', 'apiLevel',
  // run summaries (android-history, resource history)
  'gitSha', 'branch', 'phase1Ms', 'phase3Ms', 'pageCount', 'baselineP95Ms', 'phase3P95Ms',
  'jankFactor', 'writes', 'safDirectMs', 'safProviderMs', 'safPerFileMs', 'safDirectPerFileMs',
  'safOverhead', 'graphConfig', 'phase1TtiMs', 'phase2Ms', 'totalMs', 'jankBaselineP95Ms', 'jankLoadP95Ms',
  // benchdiff canonical JSON
  'schema', 'env', 'group', 'name', 'unit', 'better', 'value',
]);
const METRIC_KEY = /^[A-Z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*$/;

function listFiles(target) {
  const st = fs.statSync(target);
  if (!st.isDirectory()) return [target];
  return fs.readdirSync(target).flatMap(f => listFiles(path.join(target, f)));
}

const BARE_METRIC = /^[a-z][A-Za-z0-9]*$/;

// Under a ProbeRecord `metrics` object the keys are bare metric names (cpuMsPerPlacement).
function disallowedKeys(node, found = [], parent = '') {
  if (Array.isArray(node)) node.forEach(n => disallowedKeys(n, found, parent));
  else if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) {
      const bareMetric = parent === 'metrics' && BARE_METRIC.test(k);
      if (!FIXED_KEYS.has(k) && !METRIC_KEY.test(k) && !bareMetric) found.push(k);
      disallowedKeys(v, found, k);
    }
  }
  return found;
}

/** Returns [{rule, detail}] for one file's text. */
function scanText(text, file, hostname) {
  const hits = [];
  for (const [rule, re] of STRING_RULES) {
    const m = re.exec(text);
    if (m) hits.push({ rule, detail: m[0].trim() });
  }
  for (const m of text.matchAll(IMAGE_NAME)) {
    if (!m[1].startsWith('synthetic-')) hits.push({ rule: 'non-synthetic file name', detail: m[1] });
  }
  if (hostname && hostname.length > 2 && text.includes(hostname)) hits.push({ rule: 'runner hostname', detail: hostname });
  if (file.endsWith('.json')) {
    try {
      const parsed = JSON.parse(text);
      // Other benchmark histories (the JVM load benchmark) keep their own shapes; only probe output is key-checked.
      const probeShaped = /android-resource-history/.test(file) || ['scenario', 'exitReasons', 'schema'].some(k => k in parsed);
      for (const key of probeShaped ? disallowedKeys(parsed) : []) hits.push({ rule: 'field outside the ProbeRecord allowlist', detail: key });
    } catch (e) {
      hits.push({ rule: 'unparseable JSON', detail: e.message });
    }
  }
  return hits;
}

function main() {
  const argv = process.argv.slice(2);
  const targets = [];
  let hostname = os.hostname();
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--hostname') hostname = argv[++i];
    else targets.push(argv[i]);
  }
  if (!targets.length) {
    console.error('usage: check-artifact-privacy.js <file|dir>... [--hostname name]');
    process.exit(2);
  }
  let bad = 0;
  for (const file of targets.filter(t => fs.existsSync(t)).flatMap(listFiles)) {
    for (const hit of scanText(fs.readFileSync(file, 'utf8'), file, hostname)) {
      console.error(`PRIVACY ${file}: ${hit.rule}: ${hit.detail}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log('artifact privacy check: clean');
}

if (require.main === module) main();
module.exports = { scanText };
