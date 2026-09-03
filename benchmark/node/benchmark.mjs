/**
 * Benchmark execution harness with JIT warmup and statistical analysis.
 * Uses high-resolution performance.now() and adaptive iteration sizing.
 */

import { performance } from 'node:perf_hooks';

/**
 * Calculates summary statistics (mean, stddev, min, max, median) from an array of numbers.
 */
export function computeStats(values) {
  if (!values || values.length === 0) {
    return { mean: 0, stddev: 0, min: 0, max: 0, median: 0, relativeError: 0 };
  }

  const n = values.length;
  const sum = values.reduce((acc, v) => acc + v, 0);
  const mean = sum / n;

  const variance = values.reduce((acc, v) => acc + Math.pow(v - mean, 2), 0) / (n > 1 ? n - 1 : 1);
  const stddev = Math.sqrt(variance);
  const relativeError = mean > 0 ? (stddev / mean) * 100 : 0;

  const sorted = [...values].sort((a, b) => a - b);
  const min = sorted[0];
  const max = sorted[sorted.length - 1];
  const median = n % 2 === 0 ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2 : sorted[Math.floor(n / 2)];

  return { mean, stddev, min, max, median, relativeError };
}

/**
 * Calibrates an adaptive batch size for tight-loop benchmarks (MoveGen and FilterMaximalMoves)
 * so that each measurement iteration targets approximately targetMs (default 50-100ms).
 */
function calibrateBatchSize(fn, targetMs = 50) {
  // Probe with 10 iterations
  const probeStart = performance.now();
  fn(10);
  const probeElapsed = performance.now() - probeStart;

  if (probeElapsed <= 0.05) {
    // Very fast: probe with 500 iterations
    const probe2Start = performance.now();
    fn(500);
    const probe2Elapsed = performance.now() - probe2Start;
    const msPerOp = probe2Elapsed / 500;
    const estimated = Math.round(targetMs / Math.max(msPerOp, 0.00001));
    return Math.max(100, Math.min(estimated, 200_000));
  }

  const msPerOp = probeElapsed / 10;
  const estimated = Math.round(targetMs / Math.max(msPerOp, 0.0001));
  return Math.max(1, Math.min(estimated, 50_000));
}

/**
 * Runs a Perft benchmark scenario with warmup and measurement iterations.
 */
export async function runPerftBenchmark(engine, scenario, options = {}) {
  const quick = options.quick || false;
  const warmupIterations = quick ? 1 : (options.warmupIterations ?? 3);
  const measurementIterations = quick ? 2 : (options.measurementIterations ?? 5);

  const { fen, depth, expectedNodes } = scenario;

  // JIT Warmup: allow TurboFan / Liftoff to optimize call paths
  for (let w = 0; w < warmupIterations; w++) {
    const nodes = engine.perft(fen, depth);
    if (expectedNodes && nodes !== expectedNodes) {
      throw new Error(`Perft node mismatch in ${scenario.id}: expected ${expectedNodes}, got ${nodes}`);
    }
  }

  // Measurement phase
  const npsList = [];
  const elapsedList = [];
  let totalNodes = 0;

  for (let i = 0; i < measurementIterations; i++) {
    const start = performance.now();
    const nodes = engine.perft(fen, depth);
    const elapsedMs = performance.now() - start;

    const elapsedSec = Math.max(elapsedMs / 1000, 1e-7);
    const nps = nodes / elapsedSec;

    npsList.push(nps);
    elapsedList.push(elapsedMs);
    totalNodes = nodes;
  }

  const npsStats = computeStats(npsList);
  const elapsedStats = computeStats(elapsedList);

  return {
    scenario,
    category: 'perft',
    nodes: totalNodes,
    depth,
    nps: npsStats.mean,
    npsStdDev: npsStats.stddev,
    npsRelError: npsStats.relativeError,
    meanElapsedMs: elapsedStats.mean,
    iterations: measurementIterations,
  };
}

/**
 * Runs a MoveGenerator benchmark scenario in a tight loop.
 */
export async function runMoveGenBenchmark(engine, scenario, options = {}) {
  const quick = options.quick || false;
  const warmupIterations = quick ? 1 : (options.warmupIterations ?? 3);
  const measurementIterations = quick ? 2 : (options.measurementIterations ?? 5);
  const targetMs = quick ? 20 : (options.targetMs ?? 50);

  const dfen = scenario.dfen;

  // Calibrate batch size
  const batchSize = calibrateBatchSize(n => engine.benchGenerateMoves(dfen, n), targetMs);

  // Warmup phase
  for (let w = 0; w < warmupIterations; w++) {
    engine.benchGenerateMoves(dfen, batchSize);
  }

  // Measurement phase
  const opsPerSecList = [];
  const elapsedList = [];

  for (let i = 0; i < measurementIterations; i++) {
    const start = performance.now();
    const totalMoves = engine.benchGenerateMoves(dfen, batchSize);
    const elapsedMs = performance.now() - start;

    const elapsedSec = Math.max(elapsedMs / 1000, 1e-7);
    const opsPerSec = batchSize / elapsedSec;

    opsPerSecList.push(opsPerSec);
    elapsedList.push(elapsedMs);
  }

  const opsStats = computeStats(opsPerSecList);
  const elapsedStats = computeStats(elapsedList);

  // Convert to ops/us and us/op (matching JMH conventions)
  const opsPerUs = opsStats.mean / 1e6;
  const avgUsPerOp = opsStats.mean > 0 ? 1e6 / opsStats.mean : 0;

  return {
    scenario,
    category: 'movegen',
    batchSize,
    opsPerSec: opsStats.mean,
    opsPerUs,
    avgUsPerOp,
    stddev: opsStats.stddev,
    relError: opsStats.relativeError,
    meanElapsedMs: elapsedStats.mean,
    iterations: measurementIterations,
  };
}

/**
 * Runs a LegalMovesFilter benchmark scenario in a tight loop.
 */
export async function runFilterMaximalMovesBenchmark(engine, scenario, options = {}) {
  const quick = options.quick || false;
  const warmupIterations = quick ? 1 : (options.warmupIterations ?? 3);
  const measurementIterations = quick ? 2 : (options.measurementIterations ?? 5);
  const targetMs = quick ? 25 : (options.targetMs ?? 60);

  const dfen = scenario.dfen;

  // Calibrate batch size
  const batchSize = calibrateBatchSize(n => engine.benchFilterMaximalMoves(dfen, n), targetMs);

  // Warmup phase
  for (let w = 0; w < warmupIterations; w++) {
    engine.benchFilterMaximalMoves(dfen, batchSize);
  }

  // Measurement phase
  const opsPerSecList = [];
  const elapsedList = [];

  for (let i = 0; i < measurementIterations; i++) {
    const start = performance.now();
    const totalMoves = engine.benchFilterMaximalMoves(dfen, batchSize);
    const elapsedMs = performance.now() - start;

    const elapsedSec = Math.max(elapsedMs / 1000, 1e-7);
    const opsPerSec = batchSize / elapsedSec;

    opsPerSecList.push(opsPerSec);
    elapsedList.push(elapsedMs);
  }

  const opsStats = computeStats(opsPerSecList);
  const elapsedStats = computeStats(elapsedList);

  const opsPerUs = opsStats.mean / 1e6;
  const avgUsPerOp = opsStats.mean > 0 ? 1e6 / opsStats.mean : 0;

  return {
    scenario,
    category: 'legal_moves_filter',
    batchSize,
    opsPerSec: opsStats.mean,
    opsPerUs,
    avgUsPerOp,
    stddev: opsStats.stddev,
    relError: opsStats.relativeError,
    meanElapsedMs: elapsedStats.mean,
    iterations: measurementIterations,
  };
}

/**
 * Dispatches a scenario to its appropriate benchmark runner.
 */
export async function executeBenchmark(engine, scenario, options = {}) {
  switch (scenario.category) {
    case 'perft':
      return runPerftBenchmark(engine, scenario, options);
    case 'movegen':
      return runMoveGenBenchmark(engine, scenario, options);
    case 'legal_moves_filter':
      return runFilterMaximalMovesBenchmark(engine, scenario, options);
    default:
      throw new Error(`Unknown benchmark category: ${scenario.category}`);
  }
}
