#!/usr/bin/env node

/**
 * Node.js Benchmark Runner for Dice Chess Engine (Scala.js and WebAssembly WasmGC).
 *
 * Measures:
 *   1. Perft Nodes Per Second (NPS) across canonical positions.
 *   2. MoveGenerator.generateMoves (ops/us and us/op).
 *   3. LegalMovesFilter.filterMaximalMoves (ops/us and us/op).
 *
 * Compares throughput between JS, Wasm, and JVM HotSpot C2 baseline.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { ALL_SCENARIOS } from './scenarios.mjs';
import { executeBenchmark } from './benchmark.mjs';
import { getJvmBaseline } from './baseline.mjs';

function parseArgs(args) {
  const options = {
    target: 'all',
    filter: null,
    quick: false,
    format: 'table',
    bundle: null,
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--target' && i + 1 < args.length) {
      options.target = args[++i].toLowerCase();
    } else if (arg === '--filter' && i + 1 < args.length) {
      options.filter = new RegExp(args[++i], 'i');
    } else if (arg === '--format' && i + 1 < args.length) {
      options.format = args[++i].toLowerCase();
    } else if (arg === '--bundle' && i + 1 < args.length) {
      options.bundle = args[++i];
    } else if (arg === '--quick') {
      options.quick = true;
    } else if (arg === '--help' || arg === '-h') {
      printHelp();
      process.exit(0);
    } else if (arg.startsWith('--filter=')) {
      options.filter = new RegExp(arg.slice(9), 'i');
    } else if (arg.startsWith('--target=')) {
      options.target = arg.slice(9).toLowerCase();
    } else if (arg.startsWith('--format=')) {
      options.format = arg.slice(9).toLowerCase();
    } else if (arg.startsWith('--bundle=')) {
      options.bundle = arg.slice(9);
    }
  }

  const validTargets = ['js', 'wasm', 'all'];
  if (!validTargets.includes(options.target)) {
    console.error(`❌ Invalid --target '${options.target}'. Supported values: ${validTargets.join(', ')}`);
    process.exit(1);
  }

  const validFormats = ['table', 'markdown', 'json'];
  if (!validFormats.includes(options.format)) {
    console.error(`❌ Invalid --format '${options.format}'. Supported values: ${validFormats.join(', ')}`);
    process.exit(1);
  }

  if (options.target === 'all' && options.bundle) {
    console.error(`❌ Cannot specify a single --bundle when --target is 'all'. Use --target js or --target wasm with --bundle, or omit --bundle for auto-discovery.`);
    process.exit(1);
  }

  return options;
}


function printHelp() {
  console.log(`
⚡ Dice Chess Engine Node.js Benchmark Runner (JS vs Wasm vs JVM)

Usage:
  node benchmark/node/runner.mjs [options]

Options:
  --target <js|wasm|all>    Target runtime to benchmark (default: all)
  --filter <regex>          Filter benchmark scenarios by name/id pattern
  --quick                   Run with fewer warmup/measurement iterations for fast check
  --format <table|markdown|json>
                            Output format (default: table)
  --bundle <path>           Explicit path to compiled JS/Wasm bundle
  -h, --help                Show this help message

Examples:
  mise run bench:js
  mise run bench:wasm
  mise run bench:all
  node benchmark/node/runner.mjs --target js --filter perft
  node benchmark/node/runner.mjs --target all --quick
`);
}

/**
 * Searches for compiled bundle in target/out or dist directories.
 */
function resolveBundlePath(target, explicitPath, repoRoot) {
  if (explicitPath) {
    const resolved = path.resolve(explicitPath);
    if (fs.existsSync(resolved)) return resolved;
    throw new Error(`Explicit bundle path not found: ${resolved}`);
  }

  const isWasm = target === 'wasm';
  const outDir = path.join(repoRoot, 'target', 'out', 'sjs1');

  if (fs.existsSync(outDir)) {
    const scalaDirs = fs.readdirSync(outDir).filter(d => d.startsWith('scala-'));
    for (const sDir of scalaDirs) {
      const sub = isWasm
        ? path.join(outDir, sDir, 'dicechess-engine-wasm', 'dicechess-engine-wasm-opt', 'main.js')
        : path.join(outDir, sDir, 'dicechess-engine', 'dicechess-engine-opt', 'main.js');
      if (fs.existsSync(sub)) {
        return sub;
      }
    }
  }

  // Fallback to dist folders
  const distPath = isWasm
    ? path.join(repoRoot, 'dist-wasm', 'main.js')
    : path.join(repoRoot, 'dist', 'dicechess-engine.js');
  if (fs.existsSync(distPath)) {
    return distPath;
  }

  const buildCmd = isWasm ? 'mise run wasm:build' : 'mise run js:build';
  throw new Error(
    `Compiled ${target.toUpperCase()} bundle not found.\nPlease build the artifact first using: ${buildCmd}`
  );
}

/**
 * Dynamically imports and normalizes the engine module.
 */
async function loadEngine(bundlePath) {
  const fileUrl = pathToFileURL(bundlePath).href;
  const mod = await import(fileUrl);
  // Merge mod top-level exports and mod.DiceChess so methods are accessible either way
  const engine = Object.assign({}, mod.DiceChess || {}, mod);

  if (typeof engine.perft !== 'function') {
    throw new Error(`Loaded bundle at ${bundlePath} does not export required 'perft' function.`);
  }

  return engine;
}


function formatThroughput(category, result) {
  if (category === 'perft') {
    const nps = result.nps;
    if (nps >= 1e6) return `${(nps / 1e6).toFixed(2)} M NPS`;
    if (nps >= 1e3) return `${(nps / 1e3).toFixed(1)} k NPS`;
    return `${Math.round(nps)} NPS`;
  }
  const ops = result.opsPerUs;
  if (ops >= 1.0) return `${ops.toFixed(3)} ops/μs`;
  if (ops >= 0.001) return `${ops.toFixed(4)} ops/μs`;
  return `${(ops * 1000).toFixed(2)} ops/ms`;
}

function formatJvmBaseline(category, jvmVal) {
  if (jvmVal === null || jvmVal === undefined) return '—';
  if (category === 'perft') {
    return `${(jvmVal / 1e6).toFixed(1)} M NPS`;
  }
  if (jvmVal >= 1.0) return `${jvmVal.toFixed(3)} ops/μs`;
  return `${jvmVal.toFixed(4)} ops/μs`;
}

function formatRatio(num, den) {
  if (!num || !den || den <= 0) return '—';
  const ratio = num / den;
  return `${ratio.toFixed(2)}x`;
}

function renderSingleTable(target, results) {
  console.log(`\n================================================================================`);
  console.log(`  🎲 Dice Chess Engine Performance: ${target.toUpperCase()} (V8 / Node.js ${process.version})`);
  console.log(`================================================================================\n`);

  const categories = [
    { key: 'perft', title: '1. Perft (Nodes Per Second - NPS)' },
    { key: 'movegen', title: '2. MoveGenerator.generateMoves (Throughput)' },
    { key: 'legal_moves_filter', title: '3. LegalMovesFilter.filterMaximalMoves (Throughput)' },
  ];

  for (const { key, title } of categories) {
    const subset = results.filter(r => r.scenario.category === key);
    if (subset.length === 0) continue;

    console.log(`--- ${title} ---`);
    console.log(
      'Scenario'.padEnd(44) +
      `${target.toUpperCase()} Throughput`.padStart(20) +
      'Avg Time'.padStart(14) +
      'JVM Baseline'.padStart(16) +
      'vs JVM'.padStart(10)
    );
    console.log('-'.repeat(104));

    for (const r of subset) {
      const sc = r.scenario;
      const jvm = getJvmBaseline(sc);
      const name = sc.name.length > 43 ? sc.name.slice(0, 42) + '…' : sc.name;
      const targetVal = key === 'perft' ? r.nps : r.opsPerUs;
      const targetStr = formatThroughput(key, r);
      const avgTimeStr = key === 'perft' ? `${r.meanElapsedMs.toFixed(1)} ms` : `${r.avgUsPerOp.toFixed(3)} μs`;
      const jvmStr = formatJvmBaseline(key, jvm);
      const vsJvm = formatRatio(targetVal, jvm);

      console.log(
        name.padEnd(44) +
        targetStr.padStart(20) +
        avgTimeStr.padStart(14) +
        jvmStr.padStart(16) +
        vsJvm.padStart(10)
      );
    }
    console.log('');
  }
}

function renderComparisonTable(jsResults, wasmResults) {
  console.log(`\n================================================================================`);
  console.log(`  🏆 Dice Chess Engine Performance Comparison: JS vs Wasm (vs JVM Baseline)`);
  console.log(`  Node.js ${process.version} | V8 TurboFan (JS) vs Liftoff/TurboFan (WasmGC)`);
  console.log(`================================================================================\n`);

  const categories = [
    { key: 'perft', title: '1. Perft (Nodes Per Second - NPS)' },
    { key: 'movegen', title: '2. MoveGenerator.generateMoves (Throughput)' },
    { key: 'legal_moves_filter', title: '3. LegalMovesFilter.filterMaximalMoves (Throughput)' },
  ];

  for (const { key, title } of categories) {
    const jsSub = jsResults.filter(r => r.scenario.category === key);
    if (jsSub.length === 0) continue;

    console.log(`--- ${title} ---`);
    console.log(
      'Scenario'.padEnd(42) +
      'JS (ES2022)'.padStart(16) +
      'Wasm (WasmGC)'.padStart(18) +
      'Wasm vs JS'.padStart(12) +
      'JVM Baseline'.padStart(16) +
      'Wasm vs JVM'.padStart(13)
    );
    console.log('-'.repeat(117));

    for (const jsR of jsSub) {
      const wasmR = wasmResults.find(r => r.scenario.id === jsR.scenario.id);
      if (!wasmR) continue;

      const sc = jsR.scenario;
      const jvm = getJvmBaseline(sc);
      const name = sc.name.length > 41 ? sc.name.slice(0, 40) + '…' : sc.name;

      const jsVal = key === 'perft' ? jsR.nps : jsR.opsPerUs;
      const wasmVal = key === 'perft' ? wasmR.nps : wasmR.opsPerUs;

      const jsStr = formatThroughput(key, jsR);
      const wasmStr = formatThroughput(key, wasmR);
      const wasmVsJs = formatRatio(wasmVal, jsVal);
      const jvmStr = formatJvmBaseline(key, jvm);
      const wasmVsJvm = formatRatio(wasmVal, jvm);

      console.log(
        name.padEnd(42) +
        jsStr.padStart(16) +
        wasmStr.padStart(18) +
        wasmVsJs.padStart(12) +
        jvmStr.padStart(16) +
        wasmVsJvm.padStart(13)
      );
    }
    console.log('');
  }
}


function renderMarkdownComparison(jsResults, wasmResults) {
  console.log(`## Dice Chess Engine Performance: JS vs WebAssembly vs JVM\n`);
  console.log(`Measured on Node.js ${process.version}.\n`);

  const categories = [
    { key: 'perft', title: 'Perft (Nodes Per Second - NPS)' },
    { key: 'movegen', title: 'MoveGenerator.generateMoves' },
    { key: 'legal_moves_filter', title: 'LegalMovesFilter.filterMaximalMoves' },
  ];

  for (const { key, title } of categories) {
    const jsSub = jsResults.filter(r => r.scenario.category === key);
    if (jsSub.length === 0) continue;

    console.log(`### ${title}\n`);
    console.log(`| Scenario | JS Throughput | Wasm Throughput | Wasm vs JS | JVM Baseline | Wasm vs JVM |`);
    console.log(`|:---------|:--------------|:----------------|:-----------|:-------------|:------------|`);

    for (const jsR of jsSub) {
      const wasmR = wasmResults.find(r => r.scenario.id === jsR.scenario.id);
      if (!wasmR) continue;

      const sc = jsR.scenario;
      const jvm = getJvmBaseline(sc);
      const jsVal = key === 'perft' ? jsR.nps : jsR.opsPerUs;
      const wasmVal = key === 'perft' ? wasmR.nps : wasmR.opsPerUs;

      const jsStr = formatThroughput(key, jsR);
      const wasmStr = formatThroughput(key, wasmR);
      const wasmVsJs = formatRatio(wasmVal, jsVal);
      const jvmStr = formatJvmBaseline(key, jvm);
      const wasmVsJvm = formatRatio(wasmVal, jvm);

      console.log(`| \`${sc.name}\` | \`${jsStr}\` | \`${wasmStr}\` | **${wasmVsJs}** | \`${jvmStr}\` | \`${wasmVsJvm}\` |`);
    }
    console.log('');
  }
}

async function runTarget(target, options, scenarios, repoRoot) {
  const bundlePath = resolveBundlePath(target, options.bundle, repoRoot);
  const engine = await loadEngine(bundlePath);

  const results = [];
  for (const scenario of scenarios) {
    const res = await executeBenchmark(engine, scenario, { quick: options.quick });
    results.push(res);
  }
  return results;
}

async function main() {
  const args = process.argv.slice(2);
  const options = parseArgs(args);

  // Determine repository root (from script location)
  const scriptDir = path.dirname(new URL(import.meta.url).pathname);
  const repoRoot = path.resolve(scriptDir, '..', '..');

  // Filter scenarios if requested
  const scenarios = options.filter
    ? ALL_SCENARIOS.filter(s => options.filter.test(s.id) || options.filter.test(s.name))
    : ALL_SCENARIOS;

  if (scenarios.length === 0) {
    console.log(`No scenarios matched filter: ${options.filter}`);
    process.exit(0);
  }

  if (options.target === 'all') {
    if (options.format !== 'json') {
      console.log(`Running benchmarks on JS and WebAssembly builds (${scenarios.length} scenarios)...`);
    }

    const jsResults = await runTarget('js', options, scenarios, repoRoot);
    const wasmResults = await runTarget('wasm', options, scenarios, repoRoot);

    if (options.format === 'json') {
      console.log(JSON.stringify({ js: jsResults, wasm: wasmResults }, null, 2));
    } else if (options.format === 'markdown') {
      renderMarkdownComparison(jsResults, wasmResults);
    } else {
      renderComparisonTable(jsResults, wasmResults);
    }
  } else {
    if (options.format !== 'json') {
      console.log(`Running benchmarks on ${options.target.toUpperCase()} build (${scenarios.length} scenarios)...`);
    }

    const results = await runTarget(options.target, options, scenarios, repoRoot);

    if (options.format === 'json') {
      console.log(JSON.stringify({ [options.target]: results }, null, 2));
    } else {
      renderSingleTable(options.target, results);
    }
  }
}

main().catch(err => {
  console.error(`\n❌ Benchmark failed:`, err);
  process.exit(1);
});
