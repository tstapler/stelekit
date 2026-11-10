#!/usr/bin/env node
// Usage: node scripts/benchdiff-export.js --kind jvm|android|android-resource --in <summary.json> --out <benchdiff.json>
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

// android-resource summaries carry flat `Scenario.metric` numbers (ProbeCatalog); the unit and the
// direction are derived from the metric name so a new probe metric needs no exporter change.
const RESOURCE_KEY = /^[A-Z][A-Za-z0-9]*\.([a-z][A-Za-z0-9]*)$/;
const RESOURCE_GROUP = 'android/AnnotateResources';
const HIGHER_IS_BETTER = new Set(['survived', 'editorReferenceCleared']);
const UNIT_SUFFIXES = [[/Ms($|[A-Z])/, 'ms'], [/Bytes$/, 'B'], [/Mb($|[A-Z])/, 'MB'], [/Pct$/, '%']];

function resourceUnit(metric) {
  for (const [re, unit] of UNIT_SUFFIXES) if (re.test(metric)) return unit;
  return 'count';
}

function resourceMetrics(summary) {
  const metrics = [];
  for (const [key, value] of Object.entries(summary)) {
    const m = RESOURCE_KEY.exec(key);
    if (!m || typeof value !== 'number' || !Number.isFinite(value) || value < 0) continue;
    metrics.push({
      group: RESOURCE_GROUP, name: key, unit: resourceUnit(m[1]),
      better: HIGHER_IS_BETTER.has(m[1]) ? 'higher' : 'lower', value,
    });
  }
  return metrics;
}

const ENV_FIELDS =['gitSha', 'graphConfig', 'pageCount', 'branch'];

function toBenchdiff(summary, kind) {
  const specs = SPECS[kind];
  if (!specs && kind !== 'android-resource') {
    throw new Error(`unknown kind '${kind}' (expected jvm, android or android-resource)`);
  }

  const metrics = [];
  if (kind === 'android-resource') metrics.push(...resourceMetrics(summary));
  else {
    for (const [name, group, unit, better] of specs) {
      const value = summary[name];
      if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) continue;
      metrics.push({ group, name, unit, better, value });
    }
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
    console.error('usage: benchdiff-export.js --kind jvm|android|android-resource --in <file> --out <file>');
    process.exit(2);
  }
  const summary = JSON.parse(fs.readFileSync(args.in, 'utf8'));
  const out = toBenchdiff(summary, args.kind);
  fs.writeFileSync(args.out, JSON.stringify(out, null, 2) + '\n');
  console.log(`${args.out}: ${out.metrics.length} metrics`);
}

if (require.main === module) main();
module.exports = { toBenchdiff };
