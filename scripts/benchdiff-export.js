#!/usr/bin/env node
// Usage: node scripts/benchdiff-export.js --kind jvm|android --in <summary.json> --out <benchdiff.json>
//
// Converts a stelekit benchmark summary (benchmarks/history/*.json for the JVM load benchmark,
// benchmarks/android-history/*.json for Android) into benchdiff canonical JSON (schema 1).
// Negative values are the benchmarks' "not measured" sentinel and are omitted, as the old
// hand-written PR comment did. Null/missing metrics are omitted too.

'use strict';

const fs = require('fs');

// [field, group, unit, better]; the field name is the benchdiff metric name.
const SPECS = {
  jvm: [
    ['phase1TtiMs', 'jvm/GraphLoad', 'ms', 'lower'],
    ['phase2Ms', 'jvm/GraphLoad', 'ms', 'lower'],
    ['phase3Ms', 'jvm/GraphLoad', 'ms', 'lower'],
    ['totalMs', 'jvm/GraphLoad', 'ms', 'lower'],
    ['jankBaselineP95Ms', 'jvm/WriteLatency', 'ms', 'lower'],
    ['jankLoadP95Ms', 'jvm/WriteLatency', 'ms', 'lower'],
    ['jankFactor', 'jvm/WriteLatency', 'x', 'lower'],
  ],
  android: [
    ['phase1Ms', 'android/GraphLoad', 'ms', 'lower'],
    ['phase3Ms', 'android/GraphLoad', 'ms', 'lower'],
    ['baselineP95Ms', 'android/WriteLatency', 'ms', 'lower'],
    ['phase3P95Ms', 'android/WriteLatency', 'ms', 'lower'],
    ['jankFactor', 'android/WriteLatency', 'x', 'lower'],
    ['writes', 'android/WriteLatency', 'writes', 'higher'],
    ['safDirectPerFileMs', 'android/SafIo', 'ms', 'lower'],
    ['safPerFileMs', 'android/SafIo', 'ms', 'lower'],
    ['safOverhead', 'android/SafIo', 'x', 'lower'],
  ],
};

const ENV_FIELDS = ['gitSha', 'graphConfig', 'pageCount', 'branch'];

function toBenchdiff(summary, kind) {
  const specs = SPECS[kind];
  if (!specs) throw new Error(`unknown kind '${kind}' (expected jvm or android)`);

  const metrics = [];
  for (const [name, group, unit, better] of specs) {
    const value = summary[name];
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) continue;
    metrics.push({ group, name, unit, better, value });
  }

  const env = {};
  for (const key of ENV_FIELDS) {
    if (summary[key] != null) env[key] = String(summary[key]);
  }
  return { schema: 1, env, metrics };
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--kind') args.kind = argv[++i];
    else if (argv[i] === '--in') args.in = argv[++i];
    else if (argv[i] === '--out') args.out = argv[++i];
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.kind || !args.in || !args.out) {
    console.error('usage: benchdiff-export.js --kind jvm|android --in <file> --out <file>');
    process.exit(2);
  }
  const summary = JSON.parse(fs.readFileSync(args.in, 'utf8'));
  const out = toBenchdiff(summary, args.kind);
  fs.writeFileSync(args.out, JSON.stringify(out, null, 2) + '\n');
  console.log(`${args.out}: ${out.metrics.length} metrics`);
}

if (require.main === module) main();
module.exports = { toBenchdiff };
