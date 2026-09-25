// Check the two entries of the JavaScript npm package (#222, ADR 009).
//
// The package ships one Scala.js linker output split into modules: `.` (the full API) and `./rules`
// (rules only). Two things must hold and neither is visible to a Scala test:
//
//   1. both entries answer the same rules questions with the same values;
//   2. the `./rules` module graph never reaches `dicechess.engine.search`, which is the entire point
//      of the subpath — a host that only validates and renders games must not download the bots.
//
// The sizes printed here are the numbers quoted for a release: the transitive closure of each entry,
// which is what a consumer actually downloads, not the size of the entry file alone.
import { readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const distDirectory = resolve(process.argv[2] ?? 'dist');
const manifest = JSON.parse(readFileSync(join(distDirectory, 'package.json'), 'utf8'));

// Classes that live behind the full entry only. `KcpScratchBoard` is the canary the Definition of
// Done names: it is reachable from `KingCaptureProbability` alone, so its absence proves the rules
// root does not drag the king-capture machinery — and with it the rest of the search package — in.
const SEARCH_MARKERS = [
  'KcpScratchBoard',
  'KingCaptureProbability',
  'MonteCarloEquity',
  'BotRegistry',
  'GreedySearch',
  'TurnGenerator',
  'Evaluator',
  'OpeningBook',
  'TimeManager',
];

const INITIAL_DFEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const PROMOTION_DFEN = 'k7/4P3/8/8/8/8/8/4K3 w - - 0 1 P';
const RULES_FUNCTIONS = [
  'getLegalUciMoves',
  'generateMoves',
  'applyMove',
  'endTurn',
  'perft',
  'getPieceFromDice',
  'canonicalKey',
];

/** Records a violation and keeps going, so one run reports every problem rather than the first. */
const fail = (message) => {
  console.error(`error: ${message}`);
  process.exitCode = 1;
};

/** Every module the entry pulls in, following the relative imports the linker emits. */
const moduleClosure = (entryFile) => {
  const seen = new Set();
  const pending = [entryFile];
  while (pending.length > 0) {
    const file = pending.pop();
    if (seen.has(file)) continue;
    seen.add(file);
    const code = readFileSync(file, 'utf8');
    for (const match of code.matchAll(/(?:from|import)\s*\(?\s*"(\.\/[^"]+)"/g)) {
      pending.push(resolve(dirname(file), match[1]));
    }
  }
  return [...seen].sort();
};

/**
 * Resolves a subpath through the package's own `exports` map rather than a hard-coded filename, so
 * this check follows exactly what a consumer's `import` would resolve to — including a map that
 * points at a file the build forgot to produce.
 */
const entryFile = (subpath) => {
  const target = manifest.exports?.[subpath]?.import;
  if (typeof target !== 'string') throw new Error(`package.json has no "${subpath}" import target`);
  return resolve(distDirectory, target);
};

/** Prints an entry's closure — the bytes a consumer downloads, which is the figure a release quotes. */
const report = (subpath, files) => {
  const bytes = files.reduce((total, file) => total + statSync(file).size, 0);
  console.log(`  ${subpath.padEnd(8)} ${String(bytes).padStart(9)} B in ${files.length} module(s)`);
  for (const file of files) console.log(`      ${String(statSync(file).size).padStart(9)} B  ${file.slice(distDirectory.length + 1)}`);
  return bytes;
};

console.log(`Entry closures of ${manifest.name}@${manifest.version}:`);
const fullFiles = moduleClosure(entryFile('.'));
const rulesFiles = moduleClosure(entryFile('./rules'));
report('.', fullFiles);
report('./rules', rulesFiles);

// The published shape is two entries over ONE shared chunk. It is pinned here because the tarball
// file list in `.mise/tasks/package/verify` spells it out too: a linker that starts emitting more
// chunks must be noticed while the change is being made, not at the next release.
const isInternalChunk = (file) => /\/internal-[0-9a-f]+\.js$/.test(file);
const internalChunks = fullFiles.filter(isInternalChunk);
if (fullFiles.length !== 2 || rulesFiles.length !== 2 || internalChunks.length !== 1) {
  fail(
    'the split no longer has the expected shape (each entry plus exactly one shared internal chunk); ' +
      'if that is intended, update the expected file list in .mise/tasks/package/verify as well',
  );
} else if (!rulesFiles.includes(internalChunks[0])) {
  fail('the two entries no longer share one internal chunk, so an app that loads both would carry the rules twice');
}

for (const file of rulesFiles) {
  const code = readFileSync(file, 'utf8');
  for (const marker of SEARCH_MARKERS) {
    if (code.includes(marker)) {
      fail(`the "./rules" entry reaches ${marker} (${file.slice(distDirectory.length + 1)}); the subpath must not carry the search package`);
    }
  }
}

const full = await import(pathToFileURL(entryFile('.')).href);
const rules = await import(pathToFileURL(entryFile('./rules')).href);

for (const name of RULES_FUNCTIONS) {
  if (typeof rules[name] !== 'function') fail(`the "./rules" entry does not export the function ${name}`);
  if (typeof rules.DiceChess?.[name] !== 'function') fail(`the "./rules" entry's DiceChess has no ${name}`);
}
if (typeof full.DiceChess?.getBestMove !== 'function') fail('the "." entry lost DiceChess.getBestMove');
if (typeof full.EngineFacade?.getBotMove !== 'function') fail('the "." entry lost EngineFacade.getBotMove');

const promotions = [...rules.DiceChess.getLegalUciMoves(PROMOTION_DFEN)].sort();
if (promotions.join(',') !== 'e7e8b,e7e8n,e7e8q,e7e8r') {
  fail(`the "./rules" entry returned ${JSON.stringify(promotions)} for the promotion position`);
}
if (rules.DiceChess.perft(INITIAL_DFEN, 1) !== 20) fail('the "./rules" entry miscounted perft(initial, 1)');
if (rules.DiceChess.applyMove(INITIAL_DFEN, 'e2', 'e4') !== full.DiceChess.applyMove(INITIAL_DFEN, 'e2', 'e4')) {
  fail('the two entries disagree on applyMove');
}
if (rules.DiceChess.getPieceFromDice(6) !== 'k') fail('the "./rules" entry miscounted the dice-to-piece mapping');
if (rules.DiceChess.canonicalKey(INITIAL_DFEN) !== full.DiceChess.canonicalKey(INITIAL_DFEN)) {
  fail('the two entries disagree on canonicalKey');
}
// applyMove keeps the dice the move did not spend, on both entries (#279).
const afterPush = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e3 0 1 PN';
for (const [subpath, api] of [['.', full.DiceChess], ['./rules', rules.DiceChess]]) {
  const played = api.applyMove(`${INITIAL_DFEN} PPN`, 'e2', 'e4');
  if (played !== afterPush) fail(`the "${subpath}" entry's applyMove returned ${JSON.stringify(played)} with dice PPN`);
}

if (process.exitCode) {
  console.error('npm package entry check FAILED');
} else {
  console.log(`Both entries answer alike and "./rules" carries none of: ${SEARCH_MARKERS.join(', ')}`);
}
