package dicechess.engine.jvmapi;

import dicechess.engine.domain.GameState;

import java.util.Collections;
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

		int threat = JvmApi.threatWeight(state, "e8", 3);
		require(threat == 0, "expected 0 threat on starting position against e8, got " + threat);
		int threatDfen = JvmApi.threatWeight(dfen, "e8");
		require(threatDfen == 0, "expected 0 threat via DFEN on starting position, got " + threatDfen);

		// Epic #390 synthetic fixtures (F2 promotion + knight moves, F4 forced pass, F6 inherited en-passant) via JvmApi
		List<JvmApi.Turn> f2Turns = JvmApi.legalTurns(JvmApi.parseDfen("k7/4P3/8/8/8/8/8/4K3 w - - 0 1 PNN"));
		require(
				f2Turns.stream().anyMatch(turn -> turn.uci().size() == 3 && "e7e8n".equals(turn.uci().get(0))),
				"F2 must emit a 3-step turn that starts with the e7e8n knight promotion"
		);
		List<JvmApi.Turn> f4Turns = JvmApi.legalTurns(JvmApi.parseDfen("4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP"));
		require(f4Turns.isEmpty(), "F4 blocked pawn roll must be a forced pass (0 legal turns)");
		List<JvmApi.Turn> f6Turns = JvmApi.legalTurns(JvmApi.parseDfen("4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP"));
		require(!f6Turns.isEmpty(), "F6 inherited en-passant fixture must emit legal turns");
		require(
				f6Turns.stream().anyMatch(turn -> turn.uci().contains("e5d6")),
				"F6 must emit at least one turn containing the inherited e5d6 en-passant capture"
		);

		try {
			JvmApi.parseDfen("not a dfen");
			throw new AssertionError("expected parseDfen to reject an invalid DFEN");
		} catch (IllegalArgumentException expected) {
			// expected: FenParser's own parse error, surfaced as an exception a Java caller can catch directly
		}

		checkChess960TurnPaths();
		checkApplyChess960Turn();
	}

	private static void checkChess960TurnPaths() {
		var dfen = "6k1/8/8/8/8/8/P7/5KR1 w K - 0 1";
		List<List<String>> paths = JvmApi.chess960LegalTurnPaths(dfen + " KRP");
		require(paths.contains(List.of("f1g1", "a2a4")), "expected a castle targeting the rook, followed by a pawn move");
		require(paths.contains(List.of("a2a4", "f1g1")), "expected the pawn move before castling as well");
		require(!paths.contains(List.of("f1g1")), "castling alone leaves a playable pawn die");
		require(paths.contains(List.of("g1g8")), "capturing the king must end the turn immediately");
		require(JvmApi.chess960LegalTurnPaths(dfen).isEmpty(), "an absent roll must have no paths");
		require(JvmApi.chess960LegalTurnPaths(dfen + " Q").isEmpty(), "a forced pass must have no paths");

		try {
			JvmApi.chess960LegalTurnPaths("invalid-fen");
			throw new AssertionError("expected chess960LegalTurnPaths to reject an invalid DFEN");
		} catch (IllegalArgumentException expected) {
			// expected: the Chess960 parsing error is surfaced to Java callers
		}
	}

	private static void checkApplyChess960Turn() {
		var board = "6k1/8/8/8/8/8/P7/5KR1 w K - 0 1";
		var rolled = board + " KRP";
		require(
				JvmApi.applyChess960Turn(rolled, List.of("f1g1", "a2a3"))
						.equals("6k1/8/8/8/8/P7/8/5RK1 b - - 0 1"),
				"a complete turn must clear the roll and hand over the move"
		);
		require(
				JvmApi.applyChess960Turn("6k1/8/8/8/8/P7/8/5RK1 b - - 0 1 qqq", List.of())
						.equals("6k1/8/8/8/8/P7/8/5RK1 w - - 0 2"),
				"a forced Black pass must hand over the move and advance the full-move counter"
		);
		require(
				JvmApi.applyChess960Turn(rolled, List.of("g1g8")).equals("6R1/8/8/8/8/8/P7/5K2 w - - 0 1"),
				"a king capture must clear the roll and leave the winner on move"
		);
		requireRejectedChess960Turn(rolled, List.of("f1g1"));
		requireRejectedChess960Turn(rolled, List.of());
		requireRejectedChess960Turn(board, List.of());
		requireRejectedChess960Turn("invalid-fen", List.of());
		requireRejectedChess960Turn(null, List.of());
		requireRejectedChess960Turn(rolled, null);
		requireRejectedChess960Turn(rolled, Collections.singletonList(null));
	}

	private static void requireRejectedChess960Turn(String dfen, List<String> path) {
		try {
			JvmApi.applyChess960Turn(dfen, path);
			throw new AssertionError("expected applyChess960Turn to reject " + dfen + " with path " + path);
		} catch (IllegalArgumentException expected) {
			// Invalid input is surfaced to Java callers as IllegalArgumentException.
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
