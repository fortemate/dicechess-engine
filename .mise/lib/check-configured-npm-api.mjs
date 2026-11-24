// Exercise the published module from JavaScript, including the object exports on Wasm.
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [entry, rulesEntry] = process.argv.slice(2);
const full = await import(pathToFileURL(resolve(entry)));
const rules = rulesEntry ? await import(pathToFileURL(resolve(rulesEntry))) : undefined;

const PROMOTION_DFEN = 'k7/4P3/8/8/8/8/8/4K3 w - - 0 1 P';
const KING_CAPTURE_DFEN = '8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP';
const canonical = { profile: 'dicechesscom', layout: 'classical', rollPolicy: 'standard' };
const chess960 = Object.freeze({ layout: 'chess960' });
const dictionaryOptions = Object.assign(Object.create(null), chess960);
const castleDfen = 'k7/8/8/8/8/8/P7/6KR w K - 0 1 KRP';
const afterCastle = 'k7/8/8/8/8/8/P7/5RK1 w - - 1 1 P';
const promotion = 'k3N3/8/8/8/8/8/8/4K3 w - - 0 1';
for (const api of [
  full, full.DiceChess, ...(rules ? [rules, rules.DiceChess] : []), full.EngineFacade,
  { applyMove: full.applyMoveFacade, endTurn: full.endTurnFacade },
]) {
  assert.equal(api.applyMove(castleDfen, 'g1', 'h1', undefined, chess960), afterCastle);
  assert.equal(api.applyMove(castleDfen, 'g1', 'h1', undefined, dictionaryOptions), afterCastle);
  assert.equal(api.endTurn(afterCastle, chess960), 'k7/8/8/8/8/8/P7/5RK1 b - - 1 1');
  assert.equal(api.applyMove(PROMOTION_DFEN, 'e7', 'e8', 'n'), promotion);
  assert.equal(api.applyMove(PROMOTION_DFEN, 'e7', 'e8', 'n', canonical), promotion);
  assert.equal(api.applyMove(PROMOTION_DFEN, 'e7', 'e8', 'n', chess960), promotion);
  assert.equal(api.applyMove('bad-fen', 'e7', 'e8', undefined, canonical), undefined);
  assert.equal(api.endTurn('bad-fen', canonical), undefined);
  assert.throws(() => api.applyMove(castleDfen, 'g1', 'h1', undefined, null), TypeError);
  assert.throws(() => api.endTurn(afterCastle, { layout: 'classical', typo: true }), TypeError);
}

for (const api of [full, full.DiceChess]) {
  assert.deepEqual(api.getLegalTurnTree(KING_CAPTURE_DFEN, canonical), api.getLegalTurnTree(KING_CAPTURE_DFEN));
  assert.deepEqual(api.getLegalTurnTree(castleDfen, chess960), full.getChess960LegalTurnTree(castleDfen));
  assert.deepEqual(api.getLegalTurnTree('bad-fen', canonical), {});
  assert.equal(api.getPlayableDice(castleDfen, ['g1h1'], chess960), 'P');
  assert.equal(api.getPlayableDice(castleDfen, ['g1f1', 'g1h1'], chess960), undefined);
  assert.equal(api.getPlayableDice(KING_CAPTURE_DFEN, undefined, canonical), api.getPlayableDice(KING_CAPTURE_DFEN));
  assert.equal(api.getPlayableDice(KING_CAPTURE_DFEN, [null], canonical), undefined);
  assert.equal(api.applyTurn(castleDfen, ['g1h1', 'a2a3'], chess960), 'k7/8/8/8/8/P7/8/5RK1 b - - 0 1');
  assert.equal(api.applyTurn(castleDfen, ['g1h1'], chess960), undefined);
  assert.equal(api.applyTurn(castleDfen, null, chess960), undefined);
  assert.equal(api.applyTurn(KING_CAPTURE_DFEN, ['b3c5']), '8/8/8/2N5/8/8/2P5/K7 w - - 0 1');
  assert.equal(api.requiredRandomDice(true), 3);
  assert.equal(api.requiredRandomDice(false, chess960), 3);
  for (const opening of [{ rollPolicy: 'opening-pawn' }, { ...chess960, rollPolicy: 'opening-pawn' }]) {
    assert.equal(api.requiredRandomDice(true, opening), 2);
    assert.equal(api.requiredRandomDice(false, opening), 3);
  }
  const choice = { rollPolicy: 'choice-2x2d6' };
  assert.equal(api.requiredRandomDice(true, choice), 4);
  assert.equal(api.requiredRandomDice(false, choice), 4);
  assert.equal(api.requiredRandomDice('true'), undefined);
  assert.throws(() => api.requiredRandomDice(true, null), TypeError);
  assert.deepEqual(api.prepareRoll([1, 1], true, { rollPolicy: 'opening-pawn' }), { kind: 'pool', dice: [1, 1, 1] });
  assert.deepEqual(api.prepareRoll([2, 4, 2, 4], true, { rollPolicy: 'choice-2x2d6' }), {
    kind: 'offers', first: [2, 4], second: [2, 4],
  });
  assert.equal(api.prepareRoll([1, , 6], false), undefined);
  assert.throws(() => api.getLegalTurnTree(KING_CAPTURE_DFEN, Object.defineProperty({}, 'typo', { value: false })), TypeError);
  assert.throws(() => api.getPlayableDice(KING_CAPTURE_DFEN, undefined, { layout: 'unknown' }), TypeError);
  assert.throws(() => api.applyTurn(castleDfen, [], { layout: 'chess960', rollPolicy: 'choice-2x2d6' }), TypeError);
  for (const invalid of [null, [], new Date(0), new Map(), Object.create(chess960)]) {
    assert.throws(() => api.prepareRoll([1, 2, 3], true, invalid), TypeError);
  }
}

console.log(`Configured turn and roll APIs passed for ${entry}`);
