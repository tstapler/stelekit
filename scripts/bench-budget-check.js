#!/usr/bin/env node
// Single evaluator for benchmarks/budgets/annotation-resource-budgets.json (ADR-001).
//
//   node scripts/bench-budget-check.js --validate [--budgets <file>] [--catalog <ProbeCatalog.kt>]
//   node scripts/bench-budget-check.js --records <summary.json> [--budgets <file>] [--require-all]
//
// Instrumented tests only EMIT records; this script decides. Exit 0 = pass, 1 = a `gate` budget was
// breached (or --validate found an error), 2 = bad usage / unreadable input.
// A breach on a `report-only` entry, or any breach while the file's globalMode is `report-only`,
// prints a warning row and exits 0. Memory entries are skipped on an invalid-device record.

'use strict';

const fs = require('fs');
const path = require('path');

const DEFAULT_BUDGETS = path.join(__dirname, '..', 'benchmarks', 'budgets', 'annotation-resource-budgets.json');
const DEFAULT_CATALOG = path.join(
  __dirname, '..', 'kmp', 'src', 'androidInstrumentedTest', 'kotlin', 'dev', 'stapler', 'stelekit',
  'performance', 'probe', 'ProbeCatalog.kt');

const MODES = ['gate', 'report-only'];
const GLOBAL_MODES = ['report-only', 'enforce'];
const COMPARATORS = { lte: (v, b) => v <= b, gte: (v, b) => v >= b, eq: (v, b) => v === b };
const MUTATIONS = [
  'loaderPerPress', 'perDrawMeasure', 'busyWaitMove', 'backgroundBusyLoop', 'leakStrongRef',
  'exitOnBackground', 'loupeFullDecode', 'cacheNoLimit', 'undoUnbounded', 'oomInjection',
];
// Explicit allowlist: harness-caused REASON_OTHER / USER_REQUESTED / reinstall entries never count.
const BAD_EXIT_REASONS = ['CRASH', 'CRASH_NATIVE', 'ANR', 'EXCESSIVE_RESOURCE_USAGE', 'LOW_MEMORY', 'SIGNALED'];
const BAD_EXIT_METRIC = 'Process.badExitReasons';
const KEY_RE = /^[A-Z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*$/;

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    throw new UsageError(`cannot read ${file}: ${e.message}`);
  }
}

class UsageError extends Error {}

/** Metric keys the probe can emit, read from the string literals of ProbeCatalog.kt. */
function catalogMetrics(file) {
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch (e) {
    throw new UsageError(`cannot read probe catalog ${file}: ${e.message}`);
  }
  const keys = new Set();
  for (const m of text.matchAll(/"([A-Z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*)"/g)) keys.add(m[1]);
  return [...keys];
}

/** Returns a list of error strings (empty = valid). */
function validate(budgets, catalog) {
  const errors = [];
  if (!GLOBAL_MODES.includes(budgets.globalMode)) {
    errors.push(`globalMode must be one of ${GLOBAL_MODES.join(', ')} (got ${JSON.stringify(budgets.globalMode)})`);
  }
  const entries = budgets.entries || {};
  for (const [key, e] of Object.entries(entries)) {
    if (!KEY_RE.test(key)) errors.push(`${key}: key must look like Scenario.metric`);
    if (!MODES.includes(e.mode)) errors.push(`${key}: mode must be gate or report-only (got ${JSON.stringify(e.mode)})`);
    if (typeof e.budget !== 'number' || !Number.isFinite(e.budget)) errors.push(`${key}: budget must be a number`);
    if (!COMPARATORS[e.comparator]) errors.push(`${key}: comparator must be one of ${Object.keys(COMPARATORS).join(', ')}`);
    if (e.mode === 'gate') {
      if (!e.mutation) errors.push(`${key}: a gate entry must name its mutation flag (no mutation proof, no gate)`);
      else if (!MUTATIONS.includes(e.mutation)) errors.push(`${key}: unknown mutation '${e.mutation}'`);
    } else if (e.mutation && !MUTATIONS.includes(e.mutation)) {
      errors.push(`${key}: unknown mutation '${e.mutation}'`);
    }
  }
  for (const key of catalog) {
    if (!entries[key]) errors.push(`${key}: emitted by the probe but has no budget entry`);
  }
  return errors;
}

function isBadExit(x, ctx) {
  if (!BAD_EXIT_REASONS.includes(x.reason)) return false;
  if (ctx.pid != null && x.pid != null && x.pid !== ctx.pid) return false;
  if (ctx.scenarioStartMs != null && x.timestamp != null && x.timestamp < ctx.scenarioStartMs) return false;
  return true;
}

/** Flat `Scenario.metric` numbers from a summary, plus the derived exit-reason count. */
function metricsOf(summary) {
  const values = {};
  for (const [k, v] of Object.entries(summary)) {
    if (KEY_RE.test(k) && typeof v === 'number' && Number.isFinite(v)) values[k] = v;
  }
  if (Array.isArray(summary.exitReasons)) {
    const ctx = { pid: summary.pid, scenarioStartMs: summary.scenarioStartMs };
    values[BAD_EXIT_METRIC] = summary.exitReasons.filter(x => isBadExit(x, ctx)).length;
  }
  return values;
}

function invalidDevice(summary) {
  const d = summary.device || {};
  return d.invalidDevice === true || d.valid === false;
}

function evaluate(budgets, summary, opts = {}) {
  const values = metricsOf(summary);
  const entries = budgets.entries || {};
  const enforcing = budgets.globalMode === 'enforce';
  const badDevice = invalidDevice(summary);
  const rows = [];
  let failed = false;
  for (const [key, e] of Object.entries(entries)) {
    if (!(key in values)) {
      if (opts.requireAll && e.mode === 'gate' && enforcing) {
        failed = true;
        rows.push({ key, status: 'FAIL', note: 'gate metric missing from records' });
      } else {
        rows.push({ key, status: 'missing', note: 'not measured in this run' });
      }
      continue;
    }
    const value = values[key];
    if (badDevice && e.memory) {
      rows.push({ key, status: 'invalid-device', value, budget: e.budget, note: 'memory metric on an invalid device cannot fail the job' });
      continue;
    }
    if (COMPARATORS[e.comparator](value, e.budget)) {
      rows.push({ key, status: 'ok', value, budget: e.budget, comparator: e.comparator });
      continue;
    }
    const fails = e.mode === 'gate' && enforcing;
    if (fails) failed = true;
    const why = e.mode !== 'gate' ? 'report-only entry' : (enforcing ? 'gate' : 'globalMode is report-only');
    rows.push({ key, status: fails ? 'FAIL' : 'warn', value, budget: e.budget, comparator: e.comparator, note: why });
  }
  return { failed, rows };
}

function formatRows(rows) {
  return rows
    .filter(r => r.status !== 'ok' && r.status !== 'missing')
    .map(r => `${r.status.toUpperCase().padEnd(14)} ${r.key} = ${r.value} (budget ${r.comparator || ''} ${r.budget}) ${r.note || ''}`.trimEnd())
    .join('\n');
}

function parseArgs(argv) {
  const a = { requireAll: false, validate: false };
  for (let i = 0; i < argv.length; i++) {
    const x = argv[i];
    if (x === '--validate') a.validate = true;
    else if (x === '--require-all') a.requireAll = true;
    else if (x === '--budgets') a.budgets = argv[++i];
    else if (x === '--records') a.records = argv[++i];
    else if (x === '--catalog') a.catalog = argv[++i];
    else throw new UsageError(`unknown argument ${x}`);
  }
  return a;
}

function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    const budgets = readJson(args.budgets || DEFAULT_BUDGETS);
    if (args.validate) {
      const errors = validate(budgets, catalogMetrics(args.catalog || DEFAULT_CATALOG));
      if (errors.length) {
        console.error(errors.map(e => `ERROR ${e}`).join('\n'));
        process.exit(1);
      }
      console.log(`budgets valid: ${Object.keys(budgets.entries || {}).length} entries, globalMode=${budgets.globalMode}`);
      return;
    }
    if (!args.records) throw new UsageError('usage: bench-budget-check.js --records <summary.json> | --validate');
    const { failed, rows } = evaluate(budgets, readJson(args.records), { requireAll: args.requireAll });
    const text = formatRows(rows);
    if (text) console.log(text);
    const counts = rows.reduce((m, r) => ((m[r.status] = (m[r.status] || 0) + 1), m), {});
    console.log(`budget check: ${failed ? 'FAILED' : 'passed'} ${JSON.stringify(counts)} (globalMode=${budgets.globalMode})`);
    process.exit(failed ? 1 : 0);
  } catch (e) {
    if (e instanceof UsageError) {
      console.error(e.message);
      process.exit(2);
    }
    throw e;
  }
}

if (require.main === module) main();
module.exports = { validate, evaluate, metricsOf, catalogMetrics, BAD_EXIT_REASONS, MUTATIONS };
