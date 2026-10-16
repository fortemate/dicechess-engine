// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.jvmapi;

import java.util.List;

/** Exercises configured Tactician registration through Java primitives and strings only. */
public final class JvmApiTacticianCheck {

	private JvmApiTacticianCheck() {
	}

	public static void run() {
		String id = "tactician-java-test";
		require(JvmApi.registerTacticianBot(2, 3, 7, 0.0, id, "Java Tactician"), "registration succeeds");
		require(JvmApi.algorithms().contains(id), "the configured algorithm is discoverable");
		require(!JvmApi.registerTacticianBot(2, 3, 7, 0.0, id.toUpperCase(java.util.Locale.ROOT), "Duplicate"),
				"an existing case-insensitive id is not replaced");
		require(!JvmApi.registerTacticianBot(2, 3, 7, 0.0, "aggressive", "Replacement"), "built-in bot is preserved");

		var win = JvmApi.bestTurn(JvmApi.parseDfen("7k/8/8/8/8/8/8/K6R w - - 0 1 R"), id).orElseThrow();
		require(win.uci().equals(List.of("h1h8")), "Tactician takes the king immediately");
		var state = JvmApi.parseDfen("4k3/8/8/3n4/8/4K3/8/R7 w - - 0 1 R");
		var reply = JvmApi.bestTurn(state, id, 1L).orElseThrow();
		require(JvmApi.legalTurns(state).stream().anyMatch(turn -> turn.uci().equals(reply.uci())),
				"the returned nonterminal turn is legal");
		require(JvmApi.bestTurn(JvmApi.parseDfen("7k/8/8/8/8/8/8/K7 w - - 0 1 NN"), id).isEmpty(),
				"a forced pass returns no turn");

		require(!JvmApi.registerTacticianBot(0, 3, 7, 0.0, "invalid", "Invalid"), "a positive limit is required");
		require(!JvmApi.registerTacticianBot(2, -1, 7, 0.0, "invalid", "Invalid"), "negative attack weight is rejected");
		require(!JvmApi.registerTacticianBot(2, 3, -1, 0.0, "invalid", "Invalid"), "negative danger weight is rejected");
		for (double weight : new double[]{-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
			require(!JvmApi.registerTacticianBot(2, 3, 7, weight, "invalid", "Invalid"), "invalid material weight is rejected");
		}
		require(!JvmApi.registerTacticianBot(2, 3, 7, 0.0, " ", "Name"), "blank id is rejected");
		require(!JvmApi.registerTacticianBot(2, 3, 7, 0.0, "invalid", null), "null name is rejected");
		require(!JvmApi.algorithms().contains("invalid"), "rejected registrations leave no entry");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
