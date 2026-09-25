/**
 * TypeScript declarations for the rules-only entry of the Scala.js Dice Chess Engine,
 * published as the `./rules` subpath of `@fortemate/dicechess-engine`.
 *
 * The functions are identical in name and behaviour to their counterparts on `DiceChess`
 * in the full entry; this entry simply does not reach the search package, so a host that
 * only validates and renders games never downloads the bots.
 *
 *     import { DiceChess } from '@fortemate/dicechess-engine/rules';
 */
export interface DiceChessRulesApi {
    /**
     * Returns all legal moves as a flat array of UCI strings (e.g., ["e2e4", "e7e8q"]).
     * Empty for an invalid DFEN or a position whose dice pool allows no move.
     * They are the legal first actions of a turn from this position, judged in isolation: asked
     * again after each micro-move, the answer can admit actions the whole turn does not allow.
     * The full entry's `getLegalTurnTree` follows a whole turn.
     */
    getLegalUciMoves(dfen: string): string[];

    /**
     * Generates all pseudo-legal moves for the active color and dice pool
     * (or all moves if the dice pool is empty).
     */
    generateMoves(dfen: string): string[];

    /**
     * Applies a move to the given DFEN and returns the resulting state.
     * The result keeps the dice the move did not spend; castling spends the king and the rook die.
     * A move that no die in the pool allows is still applied, and leaves the pool empty.
     * `undefined` when the move is not pseudo-legal or an argument is invalid.
     * @param dfen The starting board state in DiceChess FEN notation.
     * @param from The algebraic notation of the starting square.
     * @param to The algebraic notation of the target square.
     * @param promotion The optional piece type to promote to (e.g. "q").
     */
    applyMove(dfen: string, from: string, to: string, promotion?: string): string | undefined;

    /**
     * Explicitly ends the current turn, toggling the active color, incrementing full moves,
     * clearing the dice pool and dropping any stale en-passant target.
     */
    endTurn(dfen: string): string | undefined;

    /**
     * Executes perft (performance test) counting leaf nodes at the given depth.
     */
    perft(dfen: string, depth: number): number;

    /**
     * Returns the piece type associated with a dice roll (1-6), or null for an invalid roll.
     */
    getPieceFromDice(dice: number): string | null;

    /**
     * Returns the canonical key (the DFEN of the position's symmetry-class representative),
     * shared by all symmetry-equivalent positions — useful as a cache key.
     */
    canonicalKey(dfen: string): string | undefined;
}

export const DiceChess: DiceChessRulesApi;

export const getLegalUciMoves: DiceChessRulesApi["getLegalUciMoves"];
export const generateMoves: DiceChessRulesApi["generateMoves"];
export const applyMove: DiceChessRulesApi["applyMove"];
export const endTurn: DiceChessRulesApi["endTurn"];
export const perft: DiceChessRulesApi["perft"];
export const getPieceFromDice: DiceChessRulesApi["getPieceFromDice"];
export const canonicalKey: DiceChessRulesApi["canonicalKey"];
