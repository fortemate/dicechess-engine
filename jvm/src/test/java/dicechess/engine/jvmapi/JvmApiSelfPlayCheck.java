package dicechess.engine.jvmapi;

import dicechess.engine.domain.GameState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Plays a complete game of Dice Chess from the starting position to a decided result, using nothing
 * but {@code dicechess.engine.jvmapi} — no import from {@code dicechess.engine.search} or
 * {@code dicechess.engine.movegen}, and no reflection. This is the autonomous-bot scenario of issue
 * #616: unlike a webhook bot, the loop here owns the whole cycle (roll, choose, apply, end turn,
 * detect the result), so every gap in the facade shows up as code that simply cannot be written.
 *
 * <p>Run from {@code JvmApiSmokeSpec} alongside {@link JvmApiSmokeCheck}, which covers the
 * webhook-shaped surface. Keeping the two separate keeps each one's failure legible: a break here
 * means the autonomous loop lost a capability, not that the original three methods regressed.
 *
 * <p><b>The game is reproducible, and deliberately so.</b> Both sides choose through
 * {@link JvmApi#legalTurns}, with the dice and the choice index drawn from one seeded
 * {@link Random} — dice generation is the caller's job by design. {@link JvmApi#bestTurn} is
 * exercised on every position too, but its result is checked against the enumerated legal turns
 * rather than played: the built-in bots break ties with an unseeded {@code Random} of their own, so
 * letting one drive would make a failure here unreproducible from the seed in this file.
 */
public final class JvmApiSelfPlayCheck {

	/** Starting position with an empty dice pool: the loop rolls its own dice. */
	private static final String START_DFEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 -";

	/**
	 * Turn cap. A decided game takes far fewer; this only stops a regression from hanging the suite,
	 * and is asserted against so that "never finished" fails loudly instead of passing quietly.
	 */
	private static final int MAX_TURNS = 400;

	private JvmApiSelfPlayCheck() {
	}

	/**
	 * Plays the seeded game and returns the final position as a DFEN, so a caller can play it twice and
	 * compare — the check that this file's reproducibility claim is true rather than merely intended.
	 */
	public static String playSeededGame() {
		var random = new Random(20260817L);
		GameState state = JvmApi.parseDfen(START_DFEN);

		require(!JvmApi.isGameOver(state), "the starting position is not a finished game");
		require(JvmApi.winner(state) == JvmApi.NoWinner(), "the starting position has no winner");
		require(JvmApi.dicePool(state).isEmpty(), "the starting position carries no dice");
		require(JvmApi.halfMoveClock(state) == 0, "the starting position's half-move clock is zero");

		var algorithms = JvmApi.algorithms();
		require(algorithms.contains("greedy"), "expected a bot registered as 'greedy', got " + algorithms);

		int turnsPlayed = 0;
		while (!JvmApi.isGameOver(state) && turnsPlayed < MAX_TURNS) {
			int mover = JvmApi.activeColor(state);
			require(mover == 0 || mover == 1, "activeColor must be 0 or 1, got " + mover);

			GameState rolled = JvmApi.withDice(state, roll(random));
			require(JvmApi.dicePool(rolled).size() == 3, "a fresh roll leaves three dice in the pool");

			state = playEnumeratedTurn(rolled, random);
			turnsPlayed++;
		}

		require(turnsPlayed < MAX_TURNS, "game did not finish within " + MAX_TURNS + " turns");
		require(turnsPlayed > 1, "a decided game takes more than one turn; the loop cannot have run");
		require(JvmApi.isGameOver(state), "the loop must exit on a decided game");

		return JvmApi.toDfen(state);
	}

	/**
	 * Runs the self-play game, throwing {@link AssertionError} on the first unmet expectation.
	 */
	public static void run() {
		GameState state = JvmApi.parseDfen(playSeededGame());

		int winner = JvmApi.winner(state);
		require(winner == 0 || winner == 1, "a decided game has a winner of 0 or 1, got " + winner);

		// The result survives a round-trip through the published string format.
		String dfen = JvmApi.toDfen(state);
		GameState reparsed = JvmApi.parseDfen(dfen);
		require(JvmApi.isGameOver(reparsed), "game-over state must survive a DFEN round-trip");
		require(JvmApi.winner(reparsed) == winner, "winner must survive a DFEN round-trip");
		require(JvmApi.toDfen(reparsed).equals(dfen), "DFEN serialization must be stable");

		// A pending roll round-trips as a multiset: DFEN writes the dice in ascending order, whatever order they
		// were supplied in, which is what toDfen documents as canonical rather than verbatim output.
		GameState shuffled = JvmApi.withDice(reparsed, List.of(5, 1, 3));
		require(JvmApi.dicePool(shuffled).equals(List.of(5, 1, 3)), "withDice keeps the supplied dice as given");
		GameState rolledTrip = JvmApi.parseDfen(JvmApi.toDfen(shuffled));
		require(JvmApi.dicePool(rolledTrip).equals(List.of(1, 3, 5)),
				"a DFEN round-trip normalizes the dice pool to ascending order, got " + JvmApi.dicePool(rolledTrip));

		// The winner is ahead on the evaluator's own scale, the loser behind: the two perspectives are mirrored.
		int winnerScore = JvmApi.evaluate(reparsed, winner);
		int loserScore = JvmApi.evaluate(reparsed, winner == 0 ? 1 : 0);
		require(winnerScore > 0, "the side that captured the king evaluates as ahead, got " + winnerScore);
		require(loserScore < 0, "the side that lost its king evaluates as behind, got " + loserScore);

		checkRejections(reparsed);
		checkTimeBudgetBinds();
	}

	/**
	 * A bot that spends time must respect the budget it was given. Before the budget existed, this call
	 * took the untimed path, which scores every legal turn of the roll — minutes of CPU on this position,
	 * with no way for a Java caller to interrupt it.
	 */
	private static void checkTimeBudgetBinds() {
		String midgame = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 1";
		GameState rolled = JvmApi.withDice(JvmApi.parseDfen(midgame), List.of(5, 4, 2));

		long startedAt = System.nanoTime();
		Optional<JvmApi.ScoredTurn> chosen = JvmApi.bestTurn(rolled, "monte-carlo", 200L);
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		require(chosen.isPresent(), "a budgeted search still owes the caller a legal turn");
		require(!chosen.get().uci().isEmpty(), "a chosen turn carries at least one UCI micro-move");

		// Deliberately loose: this catches a return to the unbounded path (minutes), not budget overshoot
		// of a few hundred milliseconds, which a loaded CI runner produces for honest reasons.
		require(elapsedMs < 30_000L, "a 200ms budget must not take " + elapsedMs + "ms");

		expectRejected(() -> JvmApi.bestTurn(rolled, "monte-carlo", 0L), "a zero time budget");
		expectRejected(() -> JvmApi.bestTurn(rolled, "monte-carlo", -1L), "a negative time budget");
	}

	/**
	 * Plays one turn by enumerating and choosing from the seeded source, and checks {@link JvmApi#bestTurn}
	 * against the same enumeration on the way past — the bot's own pick must always be one of the legal
	 * turns, whichever way it breaks its ties.
	 */
	private static GameState playEnumeratedTurn(GameState rolled, Random random) {
		List<JvmApi.Turn> turns = JvmApi.legalTurns(rolled);
		checkBotAgrees(rolled, turns);

		if (turns.isEmpty()) {
			// No legal turn for this roll: a forced pass, not a loss.
			return JvmApi.endTurn(rolled);
		}
		JvmApi.Turn turn = turns.get(random.nextInt(turns.size()));
		GameState afterMoves = turn.finalState();
		require(!JvmApi.toDfen(afterMoves).isBlank(), "every reachable position serializes to a DFEN");
		require(JvmApi.activeColor(afterMoves) == JvmApi.activeColor(rolled),
				"finalState is pre-endTurn, so the active color has not flipped yet");

		return JvmApi.isGameOver(afterMoves) ? afterMoves : JvmApi.endTurn(afterMoves);
	}

	/** {@link JvmApi#bestTurn} must pick a turn the position actually offers, and pass when it offers none. */
	private static void checkBotAgrees(GameState rolled, List<JvmApi.Turn> legal) {
		Optional<JvmApi.ScoredTurn> best = JvmApi.bestTurn(rolled, "greedy");

		if (legal.isEmpty()) {
			require(best.isEmpty(), "with no legal turn the bot must pass, not invent a move");
			return;
		}

		require(best.isPresent(), "the bot must choose when legal turns exist");
		JvmApi.ScoredTurn chosen = best.get();
		require(!chosen.uci().isEmpty(), "a chosen turn carries at least one UCI micro-move");
		require(chosen.uci().size() <= 3, "a turn spends at most three dice, got " + chosen.uci().size());
		require(JvmApi.activeColor(chosen.finalState()) == JvmApi.activeColor(rolled),
				"ScoredTurn.finalState is pre-endTurn, like Turn.finalState");

		boolean isLegal = legal.stream().anyMatch(turn -> turn.uci().equals(chosen.uci()));
		require(isLegal, "the bot chose " + chosen.uci() + ", which is not among the legal turns");
	}

	/** Three dice, values 1-6 — the roll that opens a turn. Generated caller-side, by design. */
	private static List<Integer> roll(Random random) {
		var dice = new ArrayList<Integer>(3);
		for (int i = 0; i < 3; i++) {
			dice.add(random.nextInt(6) + 1);
		}
		return dice;
	}

	/**
	 * The documented rejections: a Java caller must get {@link IllegalArgumentException} rather than a
	 * silently coerced position, a bare {@link NullPointerException}, or a missing-bot surprise.
	 */
	private static void checkRejections(GameState state) {
		expectRejected(() -> JvmApi.withDice(state, List.of(1, 2, 3, 4)), "four dice");
		expectRejected(() -> JvmApi.withDice(state, List.of(7)), "a die above six");
		expectRejected(() -> JvmApi.withDice(state, List.of(0)), "a die below one");
		expectRejected(() -> JvmApi.withDice(state, nullElement()), "a null die");
		expectRejected(() -> JvmApi.withDice(state, null), "a null dice list");
		expectRejected(() -> JvmApi.evaluate(state, 2), "an out-of-range color");
		expectRejected(() -> JvmApi.bestTurn(state, "no-such-bot"), "an unregistered algorithm id");

		// An empty pool is explicitly legal: it clears the dice rather than being rejected.
		require(JvmApi.dicePool(JvmApi.withDice(state, List.of())).isEmpty(), "an empty dice list clears the pool");
	}

	/** {@code List.of} rejects nulls, so the null-element case needs a list that permits one. */
	private static List<Integer> nullElement() {
		var dice = new ArrayList<Integer>(1);
		dice.add(null);
		return dice;
	}

	private static void expectRejected(Runnable call, String what) {
		try {
			call.run();
			throw new AssertionError("expected " + what + " to be rejected");
		} catch (IllegalArgumentException expected) {
			// expected: the facade validates rather than coercing
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
