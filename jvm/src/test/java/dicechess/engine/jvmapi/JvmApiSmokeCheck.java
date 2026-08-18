package dicechess.engine.jvmapi;

import dicechess.engine.domain.GameState;

import java.util.List;

/**
 * A plain-Java exercise of {@link JvmApi}'s public surface, run from {@code JvmApiSmokeSpec} (MUnit
 * has no Java-source discovery of its own — this class supplies the code path, the Scala spec supplies
 * the test runner). The point is catching what the Scala compiler cannot see from the producing side:
 * whether a Java caller can actually resolve and invoke these methods without reflection, and whether
 * the erased/converted types (opaque {@code Color}, {@code java.util.List}) behave as expected.
 */
public final class JvmApiSmokeCheck {

	private JvmApiSmokeCheck() {
	}

	/**
	 * Runs the exercise, throwing {@link AssertionError} on the first unmet expectation.
	 */
	public static void run() {
		var dfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 P";
		GameState state = JvmApi.parseDfen(dfen);

		int color = JvmApi.activeColor(state);
		require(color == 0, "expected White (0) to move, got " + color);

		List<JvmApi.Turn> turns = JvmApi.legalTurns(state);
		require(!turns.isEmpty(), "expected at least one legal turn for a single pawn die on the starting position");

		for (var turn : turns) {
			List<String> uci = turn.uci();
			require(!uci.isEmpty(), "a legal turn must carry at least one UCI micro-move");
			require(turn.finalState() != null, "finalState must never be null");
		}

		require(!turns.get(0).uci().get(0).isBlank(), "first UCI token must not be blank");

		try {
			JvmApi.parseDfen("not a dfen");
			throw new AssertionError("expected parseDfen to reject an invalid DFEN");
		} catch (IllegalArgumentException expected) {
			// expected: FenParser's own parse error, surfaced as an exception a Java caller can catch directly
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
