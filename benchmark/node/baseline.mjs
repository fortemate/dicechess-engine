/**
 * JVM baseline metrics captured via JMH (see benchmark/BASELINE.md).
 * Used for comparing JS and WebAssembly throughput against JVM HotSpot C2.
 */

export const JVM_BASELINE = {
  // MoveGenerator.generateAllMoves (ops/us)
  movegen_all: {
    initial: 2.63,
    kiwipete: 1.41,
    endgame: 4.00,
  },

  // MoveGenerator.generateMoves (ops/us by dice roll and position)
  movegen_single: {
    initial: {
      1: 7.571,
      2: 39.559,
      4: 123.559,
      5: 120.882,
      6: 18.101,
    },
    kiwipete: {
      1: 10.137,
      2: 16.252,
      4: 25.623,
      5: 15.996,
      6: 6.589,
    },
    endgame: {
      1: 20.945,
      2: 280.068,
      4: 19.202,
      5: 241.622,
      6: 34.434,
    },
  },

  // LegalMovesFilter.filterMaximalMoves (ops/us by dice combination and position)
  legal_moves_filter: {
    initial: {
      '1,2,3': 0.00229,  // ~436.6 us/op
      '4,5,6': 6.789,    // ~0.154 us/op
      '1,1,1': 0.00028,  // ~3517.8 us/op
      '6,4,2': 0.106,    // ~9.895 us/op
      '5,5,5': 20.568,   // ~0.050 us/op
    },
    kiwipete: {
      '1,2,3': 0.00018,  // ~5469.8 us/op
      '4,5,6': 0.00132,  // ~757.5 us/op
      '1,1,1': 0.00238,  // ~421.0 us/op
      '6,4,2': 0.00127,  // ~789.9 us/op
      '5,5,5': 0.00124,  // ~804.4 us/op
    },
    endgame: {
      '1,2,3': 0.237,    // ~4.219 us/op
      '4,5,6': 0.020,    // ~49.935 us/op
      '1,1,1': 0.0094,   // ~106.214 us/op
      '6,4,2': 0.0214,   // ~46.627 us/op
      '5,5,5': 22.518,   // ~0.045 us/op
    },
  },

  // Estimated JVM Perft Nodes Per Second (NPS)
  perft_nps: {
    initial: 20_000_000,
    kiwipete: 12_000_000,
    endgame: 25_000_000,
  },
};

/**
 * Returns the JVM baseline throughput in ops/us (or NPS for Perft) for a scenario.
 */
export function getJvmBaseline(scenario) {
  if (scenario.category === 'perft') {
    return JVM_BASELINE.perft_nps[scenario.position] || null;
  }
  if (scenario.category === 'movegen') {
    if (scenario.type === 'all') {
      return JVM_BASELINE.movegen_all[scenario.position] || null;
    }
    return JVM_BASELINE.movegen_single[scenario.position]?.[scenario.diceRoll] || null;
  }
  if (scenario.category === 'legal_moves_filter') {
    return JVM_BASELINE.legal_moves_filter[scenario.position]?.[scenario.diceKey] || null;
  }
  return null;
}
