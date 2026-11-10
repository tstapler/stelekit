#!/usr/bin/env node
// Proposes budgets from observed runs; never edits the budgets file.
//
//   node scripts/bench-budget-calibrate.js --history <dir|file...> [--budgets <file>] [--promote]
//
// budget = max(max(observed) * 1.5, 2 * median). With --promote, a metric is eligible only with
// >= 10 valid runs and zero invalid-device runs; otherwise it prints `need 10, have N` and exits 1.
// Eligible metrics print the one-line edit to make (flip `mode` to gate, set `budget`, set
// `calibratedFromRuns`), so promotion stays a reviewed human change.

'use strict';

const fs = require('fs');
const path = require('path');

const MIN_RUNS = 10;
const HEADROOM = 1.5;
const MEDIAN_FACTOR = 2;
const KEY_RE = /^[A-Z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*$/;
const DEFAULT_BUDGETS = path.join(__dirname, '..', 'benchmarks', 'budgets', 'annotation-resource-budgets.json');

function median(values) {
  const s = [...values].sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

function propose(values) {
  return Math.ceil(Math.max(Math.max(...values) * HEADROOM, median(values) * MEDIAN_FACTOR));
}

/** `runs` are parsed summaries. Returns {key -> {valid, invalid, values}}. */
function collect(runs) {
  const stats = {};
  for (const run of runs) {
    const invalid = !!(run.device && (run.device.invalidDevice === true || run.device.valid === false));
    for (const [k, v] of Object.entries(run)) {
      if (!KEY_RE.test(k) || typeof v !== 'number' || !Number.isFinite(v)) continue;
      const s = (stats[k] = stats[k] || { valid: 0, invalid: 0, values: [] });
      if (invalid) s.invalid++;
      else {
        s.valid++;
        s.values.push(v);
      }
    }
  }
  return stats;
}

function plan(runs, budgets, { promote }) {
  const out = [];
  let refused = false;
  const stats = collect(runs);
  for (const key of Object.keys(stats).sort()) {
    const s = stats[key];
    if (!s.values.length) continue;
    const entry = (budgets.entries || {})[key];
    const proposal = propose(s.values);
    const line = { key, proposal, median: median(s.values), max: Math.max(...s.values), valid: s.valid, invalid: s.invalid, current: entry && entry.budget };
    if (promote) {
      if (s.valid < MIN_RUNS || s.invalid > 0) {
        refused = true;
        line.refusal = s.invalid > 0 ? `${s.invalid} invalid-device run(s)` : `need ${MIN_RUNS}, have ${s.valid}`;
      }
    }
    out.push(line);
  }
  return { lines: out, refused };
}

function format({ lines }, promote) {
  return lines.map(l => {
    if (l.refusal) return `REFUSED ${l.key}: ${l.refusal}`;
    const base = `${l.key}: propose budget ${l.proposal} (median ${l.median}, max ${l.max}, runs ${l.valid}, current ${l.current})`;
    return promote ? `${base}\n  edit: "${l.key}": mode -> gate, budget -> ${l.proposal}, calibratedFromRuns -> ${l.valid}` : base;
  }).join('\n');
}

function loadRuns(inputs) {
  const files = [];
  for (const p of inputs) {
    if (fs.statSync(p).isDirectory()) {
      for (const f of fs.readdirSync(p).sort()) if (f.endsWith('.json')) files.push(path.join(p, f));
    } else files.push(p);
  }
  return files.map(f => JSON.parse(fs.readFileSync(f, 'utf8')));
}

function main() {
  const argv = process.argv.slice(2);
  const inputs = [];
  let budgetsFile = DEFAULT_BUDGETS;
  let promote = false;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--history') while (argv[i + 1] && !argv[i + 1].startsWith('--')) inputs.push(argv[++i]);
    else if (argv[i] === '--budgets') budgetsFile = argv[++i];
    else if (argv[i] === '--promote') promote = true;
  }
  if (!inputs.length) {
    console.error('usage: bench-budget-calibrate.js --history <dir|file...> [--budgets f] [--promote]');
    process.exit(2);
  }
  const result = plan(loadRuns(inputs), JSON.parse(fs.readFileSync(budgetsFile, 'utf8')), { promote });
  console.log(format(result, promote));
  process.exit(result.refused ? 1 : 0);
}

if (require.main === module) main();
module.exports = { propose, median, collect, plan, MIN_RUNS };
