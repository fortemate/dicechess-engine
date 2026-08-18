/**
 * TypeScript declarations for the Scala.js Dice Chess Engine.
 */
export interface EngineFacadeApi {
    /**
     * Computes a bot move for the given DiceChess FEN (DFEN).
     */
    getBotMove(dfen: string, seed?: number): Record<string, string> | undefined;

    /**
     * Retrieves the dice value (1-6) of the piece at the specified square.
     */
    getPieceTypeAt(dfen: string, square: string): number | undefined;

    /**
     * Applies a move to the given DFEN and returns the resulting state.
     */
    applyMove(dfen: string, from: string, to: string, promotion?: string): string | undefined;

    /**
     * Explicitly ends the current turn, toggling the active color, incrementing full moves,
     * and clearing any stale en-passant targets.
     * @param dfen The current board state in DiceChess FEN notation.
     */
    endTurn(dfen: string): string | undefined;
}

export const EngineFacade: EngineFacadeApi;

export type TimePolicyId = "empirical-v1" | "legacy-linear-v1";

export interface ClockStateOptions {
    remainingMs: number;
    incrementMs?: number;
    moveNumber?: number;
    movesToGo?: number;
}

export interface BestMoveOptions {
    algorithm?: string;
    clock?: ClockStateOptions;
    timePolicy?: TimePolicyId;
    timeBudgetMs?: number;
}

export interface BestMoveResult {
    moves: { from: string, to: string, promotion?: string }[];
    score: number;
    timeTakenMs: number;
    budgetMs: number;
}

export interface DiceChessApi {
    /**
     * Returns metadata for all registered search algorithms.
     */
    getAvailableBots(): { id: string, name: string, description: string, difficulty: number, isExperimental: boolean }[];

    /**
     * Returns stable ids of the built-in time-management policies.
     */
    getAvailableTimePolicies(): TimePolicyId[];

    /**
     * Returns all legal moves as a flat array of UCI strings (e.g., ["e2e4", "e7e8q"]).
     */
    getLegalUciMoves(dfen: string): string[];

    /**
     * Returns the piece type associated with a dice roll.
     */
    getPieceFromDice(dice: number): string | null;

    /**
     * Registers a runtime bot that consults an opening book before delegating to an existing bot.
     * `tsvString` contains tab-separated lines mapping canonical keys
     * ("<placement> <color> <castling> <enPassant> <dice>") to comma-separated moves ("e2e4,f1c4").
     * The decorated bot wraps `baseBotId` and is exposed under `newBotId` for later `getBestMove` calls.
     * Returns `true` when registered, `false` when the base bot is unknown or the TSV is malformed.
     */
    registerOpeningBookBot(tsvString: string, baseBotId: string, newBotId: string, newBotName: string): boolean;

    /**
     * Computes the best sequence of micro-moves for the given position.
     * `timeBudgetMs`, when positive and supported by the chosen algorithm (e.g. "monte-carlo"),
     * bounds per-move thinking time by a wall-clock deadline; other algorithms ignore it.
     */
    getBestMove(dfen: string, options?: BestMoveOptions): BestMoveResult;

    /**
     * Applies a move to the given DFEN and returns the resulting state.
     * @param dfen The starting board state in DiceChess FEN notation.
     * @param from The algebraic notation of the starting square.
     * @param to The algebraic notation of the target square.
     * @param promotion The optional piece type to promote to (e.g. "q").
     */
    applyMove(dfen: string, from: string, to: string, promotion?: string): string | undefined;

    /**
     * Explicitly ends the current turn, toggling the active color, incrementing full moves,
     * and clearing any stale en-passant targets.
     * @param dfen The current board state in DiceChess FEN notation.
     */
    endTurn(dfen: string): string | undefined;

    /**
     * Determines whether the bot should offer a double before its dice roll.
     */
    shouldBotOfferDouble(dfen: string, currentStake: number, options?: { algorithm?: string }): boolean;

    /**
     * Determines whether the bot should accept (Take) or decline (Drop) a double from the opponent.
     */
    shouldBotAcceptDouble(dfen: string, currentStake: number, options?: { algorithm?: string }): boolean;

    /**
     * Determines whether the bot should offer a draw.
     */
    shouldBotOfferDraw(dfen: string, options?: { algorithm?: string }): boolean;

    /**
     * Determines whether the bot should accept a draw offered by the opponent.
     */
    shouldBotAcceptDraw(dfen: string, options?: { algorithm?: string }): boolean;

    /**
     * Estimates pre-roll equity with a Rao-Blackwellized Monte-Carlo rollout.
     * For progressive use, call in batches (varying `seed`) and pool the per-batch results
     * (`rollouts` is the batch size and `standardError` lets you combine batches).
     * Returns a neutral `undecided = 1` result for an invalid DFEN.
     */
    estimateEquity(dfen: string, options?: { rollouts?: number, maxPlies?: number, seed?: number }): { whiteWin: number, blackWin: number, undecided: number, rollouts: number, standardError: number, varianceReductionVsVanilla: number };

    /**
     * Returns the canonical key (the DFEN of the position's symmetry-class representative),
     * shared by all symmetry-equivalent positions — useful as a cache key. `undefined` for an invalid DFEN.
     */
    canonicalKey(dfen: string): string | undefined;
}

export const DiceChess: DiceChessApi;
