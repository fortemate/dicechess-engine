/**
 * Canonical positions and benchmark scenarios for Dice Chess engine.
 * Aligned with JMH benchmarks in benchmark/src/main/scala/dicechess/engine/bench/.
 */

export const POSITIONS = {
  initial: {
    name: 'initial',
    description: 'Standard starting position (balanced, all pieces, many pawns)',
    fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  },
  kiwipete: {
    name: 'kiwipete',
    description: 'Complex middlegame with pins, en passant, and castling',
    fen: 'r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1',
  },
  endgame: {
    name: 'endgame',
    description: 'Sparse board (rook + pawns)',
    fen: '8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1',
  },
  castling: {
    name: 'castling',
    description: 'Both sides can castle with open files',
    fen: 'r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1',
  },
  promotion: {
    name: 'promotion',
    description: 'Pawn about to promote',
    fen: 'k7/4P3/8/8/8/8/8/4K3 w - - 0 1',
  },
};

/** Dice roll notation: 1=p, 2=n, 3=b, 4=r, 5=q, 6=k */
export const DICE_NOTATION = {
  1: 'p',
  2: 'n',
  3: 'b',
  4: 'r',
  5: 'q',
  6: 'k',
};

/**
 * Builds a DFEN string by attaching a 7th field with dice notation.
 */
export function buildDfen(baseFen, diceList) {
  if (!diceList || diceList.length === 0) {
    return baseFen;
  }
  const pool = diceList.map(d => DICE_NOTATION[d] || '').join('');
  return `${baseFen} ${pool}`;
}

/**
 * Standard Perft scenarios across canonical positions.
 */
export const PERFT_SCENARIOS = [
  {
    id: 'perft:initial:d1',
    name: 'Perft initial d=1',
    category: 'perft',
    position: 'initial',
    fen: POSITIONS.initial.fen,
    depth: 1,
    expectedNodes: 20,
  },
  {
    id: 'perft:initial:d2',
    name: 'Perft initial d=2',
    category: 'perft',
    position: 'initial',
    fen: POSITIONS.initial.fen,
    depth: 2,
    expectedNodes: 400,
  },
  {
    id: 'perft:initial:d3',
    name: 'Perft initial d=3',
    category: 'perft',
    position: 'initial',
    fen: POSITIONS.initial.fen,
    depth: 3,
    expectedNodes: 8902,
  },
  {
    id: 'perft:kiwipete:d1',
    name: 'Perft kiwipete d=1',
    category: 'perft',
    position: 'kiwipete',
    fen: POSITIONS.kiwipete.fen,
    depth: 1,
    expectedNodes: 48,
  },
  {
    id: 'perft:kiwipete:d2',
    name: 'Perft kiwipete d=2',
    category: 'perft',
    position: 'kiwipete',
    fen: POSITIONS.kiwipete.fen,
    depth: 2,
    expectedNodes: 2049,
  },
  {
    id: 'perft:kiwipete:d3',
    name: 'Perft kiwipete d=3',
    category: 'perft',
    position: 'kiwipete',
    fen: POSITIONS.kiwipete.fen,
    depth: 3,
    expectedNodes: 98903,
  },
  {
    id: 'perft:endgame:d1',
    name: 'Perft endgame d=1',
    category: 'perft',
    position: 'endgame',
    fen: POSITIONS.endgame.fen,
    depth: 1,
    expectedNodes: 16,
  },
  {
    id: 'perft:endgame:d2',
    name: 'Perft endgame d=2',
    category: 'perft',
    position: 'endgame',
    fen: POSITIONS.endgame.fen,
    depth: 2,
    expectedNodes: 278,
  },
  {
    id: 'perft:endgame:d3',
    name: 'Perft endgame d=3',
    category: 'perft',
    position: 'endgame',
    fen: POSITIONS.endgame.fen,
    depth: 3,
    expectedNodes: 4867,
  },
  {
    id: 'perft:endgame:d4',
    name: 'Perft endgame d=4',
    category: 'perft',
    position: 'endgame',
    fen: POSITIONS.endgame.fen,
    depth: 4,
    expectedNodes: 90159,
  },

];

/**
 * MoveGenerator.generateMoves scenarios across canonical positions and dice rolls.
 */
export const MOVEGEN_SCENARIOS = [];

for (const posKey of ['initial', 'kiwipete', 'endgame']) {
  const pos = POSITIONS[posKey];

  // generateAllMoves (no dice)
  MOVEGEN_SCENARIOS.push({
    id: `movegen:${posKey}:all`,
    name: `generateAllMoves ${posKey}`,
    category: 'movegen',
    position: posKey,
    dfen: pos.fen,
    diceStr: 'all',
    type: 'all',
  });

  // generateMoves per die (1: Pawn, 2: Knight, 4: Rook, 5: Queen, 6: King)
  const diceList = [
    { roll: 1, label: 'pawn' },
    { roll: 2, label: 'knight' },
    { roll: 4, label: 'rook' },
    { roll: 5, label: 'queen' },
    { roll: 6, label: 'king' },
  ];

  for (const { roll, label } of diceList) {
    MOVEGEN_SCENARIOS.push({
      id: `movegen:${posKey}:${label}`,
      name: `generateMoves ${posKey} [${roll}-${label}]`,
      category: 'movegen',
      position: posKey,
      dfen: buildDfen(pos.fen, [roll]),
      diceRoll: roll,
      diceStr: `${roll}`,
      type: 'single',
    });
  }
}

/**
 * LegalMovesFilter.filterMaximalMoves scenarios across canonical positions and dice combinations.
 * Aligned with JMH LegalMovesFilterBenchmark:
 * - 1,2,3 (Mixed: Pawn + Knight + Bishop)
 * - 4,5,6 (Heavy: Rook + Queen + King)
 * - 1,1,1 (Homogeneous: 3x Pawn)
 * - 6,4,2 (Castling-eligible: King + Rook + Knight)
 * - 5,5,5 (3x Queen)
 */
export const LEGAL_MOVES_FILTER_DICE = [
  { dice: [1, 2, 3], label: '1,2,3 (pnb)', key: '1,2,3' },
  { dice: [4, 5, 6], label: '4,5,6 (rqk)', key: '4,5,6' },
  { dice: [1, 1, 1], label: '1,1,1 (ppp)', key: '1,1,1' },
  { dice: [6, 4, 2], label: '6,4,2 (krn)', key: '6,4,2' },
  { dice: [5, 5, 5], label: '5,5,5 (qqq)', key: '5,5,5' },
];

export const LEGAL_MOVES_FILTER_SCENARIOS = [];

for (const posKey of ['initial', 'kiwipete', 'endgame']) {
  const pos = POSITIONS[posKey];
  for (const { dice, label, key } of LEGAL_MOVES_FILTER_DICE) {
    LEGAL_MOVES_FILTER_SCENARIOS.push({
      id: `filterMaximalMoves:${posKey}:${key}`,
      name: `filterMaximalMoves ${posKey} [${label}]`,
      category: 'legal_moves_filter',
      position: posKey,
      dice,
      diceKey: key,
      dfen: buildDfen(pos.fen, dice),
    });
  }
}

/** All scenarios combined */
export const ALL_SCENARIOS = [
  ...PERFT_SCENARIOS,
  ...MOVEGEN_SCENARIOS,
  ...LEGAL_MOVES_FILTER_SCENARIOS,
];
