// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator

/** Answers which rolls let the side to move capture the king on a target square within up to N actions (N = 1, 2, or
  * 3), computed as the total weight of the matching rolls out of [[DiceRolls.totalOrderedRolls]] (216).
  *
  * Unlike roll-by-roll search, this explores paths across all rolls in a single traversal from the position before the
  * roll:
  *   - Without dice in the position, pseudo-legal moves for every piece type are considered;
  *   - Each path tracking towards the target square notes the dice it spends (castling spends King and Rook, a normal
  *     move spends its mover piece's die);
  *   - Roll coverage is tracked as a 56-bit bitmask over the 56 distinct rolls in [[DiceRolls.weighted]];
  *   - Branches whose possible dice combinations are already fully covered by shorter or existing paths are pruned;
  *   - Transpositions by (board Zobrist hash, spent dice key) are detected and skipped;
  *   - Terminal captures at maximum depth use direct attacker bitboards without allocating full move lists;
  *   - Once all 56 rolls are covered, the search terminates immediately with the maximum weight 216.
  */
object KingThreat:

  val AllRollsMask: Long = (1L << 56) - 1

  private val rollCoverageTable: Array[Long] =
    val table = new Array[Long](512)
    table(0) = AllRollsMask

    def countDie(roll: List[Int], die: Int): Int =
      (if roll.head == die then 1 else 0) +
        (if roll.tail.head == die then 1 else 0) +
        (if roll.last == die then 1 else 0)

    // 1 die
    var d1 = 1
    while d1 <= 6 do
      var mask = 0L
      var i    = 0
      while i < 56 do
        val r = DiceRolls.weighted(i)._1
        if countDie(r, d1) >= 1 then mask |= (1L << i)
        i += 1
      table(d1) = mask
      d1 += 1

    // 2 dice
    d1 = 1
    while d1 <= 6 do
      var d2 = d1
      while d2 <= 6 do
        val key  = (d1 << 3) | d2
        var mask = 0L
        var i    = 0
        while i < 56 do
          val r    = DiceRolls.weighted(i)._1
          val cnt1 = countDie(r, d1)
          if d1 == d2 then
            if cnt1 >= 2 then mask |= (1L << i)
          else
            val cnt2 = countDie(r, d2)
            if cnt1 >= 1 && cnt2 >= 1 then mask |= (1L << i)
          i += 1
        table(key) = mask
        d2 += 1
      d1 += 1

    // 3 dice
    d1 = 1
    while d1 <= 6 do
      var d2 = d1
      while d2 <= 6 do
        var d3 = d2
        while d3 <= 6 do
          val key  = (d1 << 6) | (d2 << 3) | d3
          var mask = 0L
          var i    = 0
          while i < 56 do
            val r = DiceRolls.weighted(i)._1
            if r.head == d1 && r.tail.head == d2 && r.last == d3 then mask |= (1L << i)
            i += 1
          table(key) = mask
          d3 += 1
        d2 += 1
      d1 += 1

    table

  private val rollWeightsTable: Array[Int] =
    val table = new Array[Int](56)
    var i     = 0
    while i < 56 do
      table(i) = DiceRolls.weighted(i)._2
      i += 1
    table

  /** Look up the bitmask of rolls (0..55) that contain the multiset encoded by `key`. */
  inline def rollCoverage(key: Int): Long = rollCoverageTable(key)

  /** Add a die face `1..6` to a sorted multiset `key`. */
  inline def addDie(key: Int, die: Int): Int =
    if key == 0 then die
    else if key <= 6 then
      val d1 = key
      if die < d1 then (die << 3) | d1
      else (d1 << 3) | die
    else
      val d1 = (key >> 3) & 7
      val d2 = key & 7
      if die <= d1 then (die << 6) | (d1 << 3) | d2
      else if die <= d2 then (d1 << 6) | (die << 3) | d2
      else (d1 << 6) | (d2 << 3) | die

  /** Add King (6) and Rook (4) dice (castling) to `key`. Only valid when key has <= 1 die. */
  inline def addCastle(key: Int): Int =
    if key == 0 then (4 << 3) | 6
    else
      val d = key
      if d <= 4 then (d << 6) | (4 << 3) | 6
      else if d <= 6 then (4 << 6) | (d << 3) | 6
      else (4 << 6) | (6 << 3) | d

  /** Computes the total roll weight out of 216 from a 56-bit coverage mask. */
  def maskToWeight(covered: Long): Int =
    var sum = 0
    var i   = 0
    while i < 56 do
      if (covered & (1L << i)) != 0L then sum += rollWeightsTable(i)
      i += 1
    sum

  /** Returns the total weight (0..216) of ordered rolls that allow the active color to capture the king on `target`
    * within up to `maxActions` (1, 2, or 3).
    */
  def threatWeight(state: GameState, target: Square, maxActions: Int): Int =
    val actions = math.max(0, math.min(maxActions, 3))
    if actions == 0 then 0
    else
      val targetBB    = Bitboard.fromSquare(target)
      val enemyPieces = if state.activeColor.isWhite then state.blackPieces else state.whitePieces
      // Target king must belong to the opponent
      if (targetBB & enemyPieces & state.kings).isEmpty then 0
      else
        val ctx = new ThreatContext()

        // --- Depth 1: direct attackers check ---
        val attackers1 = MoveGenerator.allAttackers(state, target, state.activeColor)
        if !attackers1.isEmpty then addDirectAttackers(state, attackers1, 0, ctx)

        if ctx.isAllCovered || actions == 1 then maskToWeight(ctx.coveredRolls)
        else
          // --- Depth 1 non-capturing moves ---
          val moves1 = MoveGenerator.generateAllMoves(state)
          var curr1  = moves1
          while curr1.nonEmpty && !ctx.isAllCovered do
            val m1 = curr1.head
            curr1 = curr1.tail
            if m1.toSquare != target then
              val moverType   = state.mailbox(m1.fromSquare).pieceType
              val isCastle    = m1.isCastling
              val cost        = if isCastle then 2 else 1
              val spent1      = if isCastle then addCastle(0) else addDie(0, moverType.diceValue)
              val branchMask1 = rollCoverage(spent1)
              if (branchMask1 & ~ctx.coveredRolls) != 0L then
                val s1 = state.makeMove(m1)
                searchDepth2(s1, target, actions, cost, spent1, ctx)

          maskToWeight(ctx.coveredRolls)

  private def searchDepth2(
      s1: GameState,
      target: Square,
      maxActions: Int,
      diceSpentSoFar: Int,
      spent1: Int,
      ctx: ThreatContext
  ): Unit =
    // --- Check direct captures at depth 2 ---
    val attackers2 = MoveGenerator.allAttackers(s1, target, s1.activeColor)
    if !attackers2.isEmpty then addDirectAttackers(s1, attackers2, spent1, ctx)

    // If maxActions == 2, depth 2 is the last action; no need to continue to depth 3
    // Depth 3 requires at least 1 remaining die. If move 1 was castling (diceSpentSoFar == 2),
    // any move at depth 2 would take the 3rd die, leaving 0 dice for depth 3.
    if !ctx.isAllCovered && maxActions >= 3 && diceSpentSoFar < 2 then
      // Avoid redundant depth-2 expansions
      val transKey = s1.zobristHash ^ (spent1.toLong << 16)
      if ctx.visited.add(transKey) then
        val moves2 = MoveGenerator.generateAllMoves(s1)
        var curr2  = moves2
        while curr2.nonEmpty && !ctx.isAllCovered do
          val m2 = curr2.head
          curr2 = curr2.tail
          if m2.toSquare != target then
            // Castling at depth 2 consumes 2 dice (total 3), leaving no dice for depth 3
            if !m2.isCastling then
              val moverType   = s1.mailbox(m2.fromSquare).pieceType
              val spent2      = addDie(spent1, moverType.diceValue)
              val branchMask2 = rollCoverage(spent2)
              if (branchMask2 & ~ctx.coveredRolls) != 0L then
                val s2         = s1.makeMove(m2)
                val attackers3 = MoveGenerator.allAttackers(s2, target, s2.activeColor)
                if !attackers3.isEmpty then addDirectAttackers(s2, attackers3, spent2, ctx)

  private inline def addDirectAttackers(
      state: GameState,
      attackers: Bitboard,
      spentBefore: Int,
      ctx: ThreatContext
  ): Unit =
    var mask = 0
    if !(attackers & state.pawns).isEmpty then mask |= (1 << PieceType.Pawn.diceValue)
    if !(attackers & state.knights).isEmpty then mask |= (1 << PieceType.Knight.diceValue)
    if !(attackers & state.bishops).isEmpty then mask |= (1 << PieceType.Bishop.diceValue)
    if !(attackers & state.rooks).isEmpty then mask |= (1 << PieceType.Rook.diceValue)
    if !(attackers & state.queens).isEmpty then mask |= (1 << PieceType.Queen.diceValue)
    if !(attackers & state.kings).isEmpty then mask |= (1 << PieceType.King.diceValue)

    var d = 1
    while d <= 6 && !ctx.isAllCovered do
      if (mask & (1 << d)) != 0 then
        val pathSpent = addDie(spentBefore, d)
        ctx.addCoverage(rollCoverage(pathSpent))
      d += 1

  final private class ThreatContext:
    var coveredRolls: Long = 0L
    val visited            = new LongArraySet(2048)

    inline def isAllCovered: Boolean = coveredRolls == AllRollsMask

    inline def addCoverage(mask: Long): Boolean =
      coveredRolls |= mask
      isAllCovered

  final private class LongArraySet(capacity: Int):
    private val mask  = capacity - 1
    private val table = new Array[Long](capacity)

    def add(rawKey: Long): Boolean =
      var k = rawKey ^ (rawKey >>> 32)
      k = (k ^ (k >>> 16)) * 0x85ebca6bL
      k = (k ^ (k >>> 13)) * 0xc2b2ae35L
      k = k ^ (k >>> 16)
      if k == 0L then k = 1L

      var idx   = (k & mask).toInt
      var found = false
      while table(idx) != 0L && !found do
        if table(idx) == k then found = true
        else idx = (idx + 1) & mask

      if found then false
      else
        table(idx) = k
        true
