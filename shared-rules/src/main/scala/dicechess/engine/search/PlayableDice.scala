// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

/** The dice of a rolled position that a legal turn can still spend (#293).
  *
  * A client that draws the dice dims a die once no legal turn can use it. A die is playable while at least one legal
  * turn that begins with the actions already played spends it after them. In the start position with the dice queen,
  * rook and knight, only a knight can move first; after `b1a3` the rook can go `a1b1`, and every legal turn is "knight,
  * then rook". The knight and rook dice are therefore playable at the roll and only the queen die is not. A die that is
  * not playable stays so for the rest of the turn, since each action played only narrows the turns that can follow.
  *
  * It is built on [[TurnGenerator.forEachLegalTurnPath]] and changes nothing in it. Every legal turn is replayed with
  * the engine's own `makeMove` and `diceAfter`, and the dice each action spends are the pool before it less the pool it
  * leaves, so castling spends the king and the rook die with no rule of its own here.
  */
object PlayableDice:

  /** The dice a legal turn can still spend once `played` has been played.
    *
    * The turn is judged as a whole, which is why this takes the position at the start of the turn and the actions
    * played since, not the position after them. The Maximum Micro-moves Rule counts the dice the whole turn could use,
    * and a turn that takes the king ends there. Judged afresh, the position after a king capture would still have dice
    * to spend, and after an action allowed only because the next one takes the king, other moves would be allowed too.
    *
    * It costs one enumeration of the legal turns and a replay of their moves. A caller that holds the turns can often
    * skip it: when a turn continuing `played` has as many actions as there are dice left, every die left is playable,
    * since each action spends at least one.
    *
    * @param state
    *   the rolled position at the start of the turn, with the dice of the roll in its pool
    * @param played
    *   the micro-moves already played this turn, in order; empty at the roll. They are compared as UCI names moves, by
    *   origin, destination and promotion piece, so a move built from its UCI notation matches the one the search
    *   generates.
    * @return
    *   the die faces (1–6, ascending) that a legal turn beginning with `played` spends after it, each as many times as
    *   the most such a turn spends. Empty when no legal turn continues: after a complete turn, a king capture included,
    *   and for a roll with no legal move or a position without dice. `None` when no legal turn begins with `played`.
    */
  def of(state: GameState, played: Seq[Move]): Option[List[Int]] =
    // Plain arrays and loops rather than collection operations: this method is on the JavaScript entry, where each
    // generic collection call links in code of its own.
    val prefix = new Array[Int](played.length)
    var k      = 0
    played.foreach { move =>
      prefix(k) = uciKey(move.toInt)
      k += 1
    }
    // What a turn spends after the prefix is the pool once the prefix is played, the same on every turn that begins with
    // it, less the pool the turn leaves at its end. So for each face this keeps the fewest dice showing it that such a
    // turn leaves at its end.
    var afterPrefix = state.flags
    val fewestLeft  = new Array[Int](7)
    var face        = 1
    while face <= 6 do
      fewestLeft(face) = GameFlags.DiceSlots
      face += 1
    // The position before each move of the current turn, and the move that led from one to the next. The turns come
    // from a depth-first search, so a turn shares its first moves with the one before, and the positions they share are
    // kept rather than replayed. `known` counts the positions that belong to the current turn.
    val before  = new Array[GameState](3)
    val reached = new Array[Int](2)
    before(0) = state
    var known   = 1
    var matched = 0
    TurnGenerator.forEachLegalTurnPath(state) { (moves, len) =>
      if beginsWith(moves, len, prefix) then
        matched += 1
        // Keep the positions this turn shares with the one before, and replay the rest, up to its last move.
        var shared = 1
        while shared < known && shared < len && reached(shared - 1) == moves(shared - 1).toInt do shared += 1
        known = shared
        while known < len do
          before(known) = afterMove(before(known - 1), moves(known - 1))
          reached(known - 1) = moves(known - 1).toInt
          known += 1
        val atEnd = diceLeft(before(len - 1), moves(len - 1))
        afterPrefix = if prefix.length < len then before(prefix.length).flags else atEnd
        var f = 1
        while f <= 6 do
          fewestLeft(f) = math.min(fewestLeft(f), diceShowing(atEnd, f))
          f += 1
    }
    if matched == 0 then Option.when(prefix.isEmpty)(Nil)
    else
      var dice = List.empty[Int]
      face = 6
      while face >= 1 do
        var n = diceShowing(afterPrefix, face) - fewestLeft(face)
        while n > 0 do
          dice = face :: dice
          n -= 1
        face -= 1
      Some(dice)

  /** `state` after `move`, a move of a legal turn, with the dice it leaves. */
  private def afterMove(state: GameState, move: Move): GameState =
    state.makeMove(move).withDiceSlotsOf(diceLeft(state, move))

  /** The flags of `state` once `move` has spent its dice.
    *
    * `diceAfter` is `inline`. Calling it from this one method, which is never inlined, links the code it expands to
    * once rather than at every call.
    */
  @noinline
  private def diceLeft(state: GameState, move: Move): GameFlags = state.diceAfter(move)

  /** True when the turn, `len` moves of `moves`, is at least as long as `prefix`, a list of [[uciKey]]s, and starts
    * with its moves.
    */
  private def beginsWith(moves: Array[Move], len: Int, prefix: Array[Int]): Boolean =
    var i = 0
    while i < prefix.length && i < len && uciKey(moves(i).toInt) == prefix(i) do i += 1
    i == prefix.length

  /** A move as UCI names it: its origin, its destination and, for a promotion, the piece it promotes to.
    *
    * Whether the move captures, castles or takes en passant is not part of the name, so those flags are dropped. A
    * [[dicechess.engine.domain.Move]] keeps its destination in bits 0–5, its origin in bits 6–11 and its flags in bits
    * 12–15. Among the flags, bit 3 marks a promotion, and bits 0–1 then name the piece whether or not it captures.
    */
  private inline def uciKey(move: Int): Int =
    val flags = (move >>> 12) & 0xf
    if (flags & 8) != 0 then (move & 0xfff) | ((8 | (flags & 3)) << 12) else move & 0xfff

  /** How many of the dice in the pool of `flags` show `face`. */
  private inline def diceShowing(flags: GameFlags, face: Int): Int =
    (if flags.diceSlot1 == face then 1 else 0) +
      (if flags.diceSlot2 == face then 1 else 0) +
      (if flags.diceSlot3 == face then 1 else 0)
