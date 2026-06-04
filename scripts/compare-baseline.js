#!/usr/bin/env node
// Usage: node scripts/compare-baseline.js --baseline <file> --current <file> [--threshold <pct>]
//
// Compares a current benchmark result JSON against a committed baseline.
// Exits 0 if all metrics are within threshold; exits 1 if any regression is detected.
// If the baseline file does not exist, prints a warning and exits 0 (non-blocking).
//
// Threshold defaults:
//   TINY   -> 30% (shared CI runner variance is higher for short-duration runs)
//   SMALL  -> 20% (longer runs are more stable)
//   Default -> 30% (conservative; override with --threshold or BENCH_THRESHOLD env var)

'use strict';

const fs = require('fs');
const path = require('path');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--baseline') args.baseline = argv[++i];
    else if (argv[i] === '--current') args.current = argv[++i];
    else if (argv[i] === '--threshold') args.threshold = Number(argv[++i]);
  }
  return args;
}

function loadJson(filePath) {
  const abs = path.resolve(filePath);
  if (!fs.existsSync(abs)) return null;
  try {
    return JSON.parse(fs.readFileSync(abs, 'utf8'));
  } catch (e) {
    console.error(`Failed to parse JSON at ${abs}: ${e.message}`);
    process.exit(1);
  }
}

function compareMetrics(baseline, current, thresholdPct) {
  const regressions = [];
  const baseMetrics = baseline.metrics || {};
  const curMetrics = current.metrics || {};

  for (const [key, baseValue] of Object.entries(baseMetrics)) {
    const curValue = curMetrics[key];
    if (curValue === undefined || curValue === null) continue;
    // Sentinel values (-1) and skipped indicators are excluded from comparison
    if (baseValue < 0 || curValue < 0) continue;
    if (key === 'skipped') continue;

    const threshold = thresholdPct / 100;
    const limit = baseValue * (1 + threshold);
    if (curValue > limit) {
      regressions.push({
        metric: key,
        baseline: baseValue,
        current: curValue,
        overBy: ((curValue / baseValue - 1) * 100).toFixed(1),
        limitMs: limit.toFixed(1),
      });
    }
  }
  return regressions;
}

function printTable(regressions) {
  const header = `${'Metric'.padEnd(24)} ${'Baseline'.padStart(10)} ${'Current'.padStart(10)} ${'Over by'.padStart(10)}`;
  const separator = '─'.repeat(header.length);
  console.error(separator);
  console.error(header);
  console.error(separator);
  for (const r of regressions) {
    console.error(
      `${r.metric.padEnd(24)} ${String(r.baseline).padStart(10)} ${String(r.current.toFixed(1)).padStart(10)} ${(r.overBy + '%').padStart(10)}`,
    );
  }
  console.error(separator);
}

function main() {
  const args = parseArgs(process.argv.slice(2));

  if (!args.baseline || !args.current) {
    console.error('Usage: node scripts/compare-baseline.js --baseline <file> --current <file> [--threshold <pct>]');
    process.exit(1);
  }

  let thresholdPct = args.threshold ?? Number(process.env.BENCH_THRESHOLD ?? '30');
  if (isNaN(thresholdPct) || thresholdPct < 0) {
    console.warn(`[WARN] Invalid threshold value: ${thresholdPct}. Must be a non-negative number. Defaulting to 30%.`);
    thresholdPct = 30;
  }

  // Missing baseline is non-blocking (first run)
  if (!fs.existsSync(path.resolve(args.baseline))) {
    console.warn(`No baseline found at ${args.baseline} — skipping comparison (non-blocking)`);
    process.exit(0);
  }

  const baseline = loadJson(args.baseline);
  const current = loadJson(args.current);

  if (!baseline || !current) {
    console.error('Could not load baseline or current JSON');
    process.exit(1);
  }

  // Support both single-object and array-of-results formats
  const baselineList = Array.isArray(baseline) ? baseline : [baseline];
  const currentList = Array.isArray(current) ? current : [current];

  let totalRegressions = 0;

  for (const baseEntry of baselineList) {
    const curEntry = currentList.find(
      (c) => c.scenario === baseEntry.scenario && c.graphConfig === baseEntry.graphConfig,
    );
    if (!curEntry) {
      console.warn(`[SKIP] No current result for scenario=${baseEntry.scenario} graphConfig=${baseEntry.graphConfig} — skipping comparison`);
      continue;
    }

    // Use per-preset threshold unless overridden explicitly via --threshold or BENCH_THRESHOLD.
    const effectiveThreshold = args.threshold !== undefined || process.env.BENCH_THRESHOLD !== undefined
      ? thresholdPct
      : (curEntry.graphConfig === 'SMALL' ? 20 : 30);

    const regressions = compareMetrics(baseEntry, curEntry, effectiveThreshold);
    if (regressions.length > 0) {
      console.error(`\nREGRESSION DETECTED: ${curEntry.scenario}/${curEntry.graphConfig} on platform=${curEntry.platform}`);
      printTable(regressions);
      totalRegressions += regressions.length;
    } else {
      console.log(`OK: ${curEntry.scenario ?? 'unknown'}/${curEntry.graphConfig ?? 'unknown'} — all metrics within ${effectiveThreshold}% of baseline`);
    }
  }

  if (totalRegressions > 0) {
    console.error(`\n${totalRegressions} regression(s) detected. Failing.`);
    process.exit(1);
  }

  console.log('All metrics within threshold. No regressions.');
  process.exit(0);
}

main();
