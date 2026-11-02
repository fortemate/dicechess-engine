// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import java.util.concurrent.atomic.AtomicLong

enum TTBound derives CanEqual:
  case Exact, LowerBound, UpperBound

final case class TTEntry(
    key: Long,
    value: Double,
    bound: TTBound,
    depth: Int,
    lossTainted: Boolean
) derives CanEqual

/** Fixed-size, racy-reader-safe Transposition Table for stochastic expectimax search (#59, #397).
  *
  * Stored in flat primitive arrays (`checksums`, `keys`, `values`, `bounds`, `depths`, `lossTainteds`) to ensure
  * zero-GC pressure on hot search paths.
  *
  * ## Ownership boundary vs [[EvaluationCache]]
  *
  * `TranspositionTable` and [[EvaluationCache]] (#295) cache different functions under incompatible correctness keys
  * and remain strictly separate abstractions:
  *   - **Function and payload:** [[EvaluationCache]] memoizes a deterministic static callback
  *     `(GameState, Color) => Int` and stores only an exact `Int` score. `TranspositionTable` caches a stochastic
  *     subtree search outcome `(value: Double, depth: Int, bound: TTBound, lossTainted: Boolean)` stated from the
  *     search's single root `perspective` on its [[UtilityBounds]] scale.
  *   - **Identity key and counters:** [[EvaluationCache]] keys by exact `(GameState, Color)` value equality
  *     (`GameState.equals`, including bitboards, the 64-square mailbox, `flags` with `dicePool`, `castlingRights`,
  *     `halfMoveClock`, the full `enPassant` bitboard, and `fullMoveNumber`). `TranspositionTable` keys by a 64-bit
  *     [[dicechess.engine.domain.Zobrist]] hash (piece placement, `activeColor`, `castlingRights`, the full `enPassant`
  *     bitboard, and canonical `dicePool`) XORed at depth-3 inner `MAX` chance nodes with a role salt
  *     (`MaxChanceKeySalt`), so a `MAX` continuation never collides with a `MIN` continuation on the same board;
  *     `halfMoveClock` and `fullMoveNumber` are deliberately excluded from the Zobrist key.
  *   - **Collisions and replacement:** [[EvaluationCache]] resolves bucket collisions losslessly via value equality and
  *     never evicts during an invocation. `TranspositionTable` maps `key & (capacity - 1)` to a single slot with
  *     depth-preferred replacement (`depth >= oldDepth`, or replacing a non-exact bound on the same key) and a 64-bit
  *     XOR checksum (~2⁻⁶⁴ residual collision acceptance).
  *   - **Terminal and loss-taint semantics:** Immediate root king capture short-circuits with
  *     [[SearchScoring.TerminalWinScore]] before either cache is consulted. Inside recursive chance/decision subtrees,
  *     a line where at least one opponent roll captures the root perspective's king sets `lossTainted = true` (scored
  *     at `UtilityBounds.terminalLoss`), which `TranspositionTable` preserves alongside `value` and `bound` so
  *     [[RootRescore]] never blends a loss-tainted candidate on a table hit.
  *   - **Cancellation and incomplete values:** Callers must check [[SearchControl.isCancelled]] (or their invocation
  *     deadline) **before** probing `TranspositionTable` or calling [[EvaluationCache.apply]], so an already-cancelled
  *     invocation deterministically returns [[SearchOutcome.Incomplete]] rather than progressing on a warm hit.
  *     [[SearchOutcome.Incomplete]] carries no score and must never be passed to [[store]].
  *
  * ## Concurrency and lifetime contract
  *
  *   - **Concurrency:** Racy-reader safe, not general-purpose lock-synchronized: the classic lockless XOR scheme
  *     (Hyatt). A checksum over ALL entry fields is written alongside each entry; a reader that observes a mixed or
  *     torn snapshot fails the checksum and reports a miss, so concurrent readers can lose entries but never observe a
  *     corrupt one (residual false-accept probability ~2⁻⁶⁴). Single writer recommended until parallel search (#61)
  *     revisits publication with JVM fences.
  *   - **Lifetime and invalidation:** Entries encode only `(position, role, depth)` for one fixed evaluator, model,
  *     [[UtilityBounds]] scale, and [[RootRescore]] configuration — for that fixed configuration an `Exact` value never
  *     goes stale across moves or plies, which is why entries carry no generation field. Owned by the caller/search
  *     instance that created it; never share across different evaluators, models, or utility scales without calling
  *     [[clear]] or constructing a fresh table.
  *
  * ## Example
  *
  * ```scala
  * import dicechess.engine.domain.FenParser
  * import dicechess.engine.search.{SearchControl, SearchOutcome, TTBound, TranspositionTable}
  *
  * val state = FenParser.parse("4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP").toOption.get
  * val tt    = new TranspositionTable(1 << 10)
  * val key   = state.zobristHash
  *
  * // Store a completed depth-2 exact subtree expectation with its loss-taint flag:
  * tt.store(key, value = 125.0, bound = TTBound.Exact, depth = 2, lossTainted = false)
  *
  * // Check cooperative cancellation BEFORE probing so expired calls never progress on warm hits:
  * def lookupDepth2(control: SearchControl): SearchOutcome[Double] =
  *   if control.isCancelled then SearchOutcome.Incomplete(control.workUsed)
  *   else
  *     tt.probe(key) match
  *       case Some(entry) if entry.depth == 2 && entry.bound == TTBound.Exact =>
  *         SearchOutcome.Exact(entry.value, control.workUsed)
  *       case _ =>
  *         SearchOutcome.Incomplete(control.workUsed)
  *
  * assert(lookupDepth2(SearchControl.Unbounded) == SearchOutcome.Exact(125.0, 0L))
  * val expired = SearchControl.untilDeadline(deadlineNanos = 0L, clockNanos = () => 1L)
  * assert(lookupDepth2(expired) == SearchOutcome.Incomplete(0L))
  * ```
  *
  * @param capacityPowerOfTwo
  *   table capacity, must be a power of two (default 2^18 = 262,144 entries)
  */
class TranspositionTable(capacityPowerOfTwo: Int = 1 << 18):
  require(
    capacityPowerOfTwo > 0 && (capacityPowerOfTwo & (capacityPowerOfTwo - 1)) == 0,
    s"Capacity must be a positive power of two, got $capacityPowerOfTwo"
  )

  private val capacity = capacityPowerOfTwo
  private val mask     = capacity - 1

  private val checksums    = new Array[Long](capacity)
  private val keys         = new Array[Long](capacity)
  private val values       = new Array[Double](capacity)
  private val bounds       = new Array[Byte](capacity) // 0 = empty, 1 = Exact, 2 = LowerBound, 3 = UpperBound
  private val depths       = new Array[Byte](capacity)
  private val lossTainteds = new Array[Boolean](capacity)

  private val _hits       = new AtomicLong(0L)
  private val _misses     = new AtomicLong(0L)
  private val _stores     = new AtomicLong(0L)
  private val _overwrites = new AtomicLong(0L)
  private val _collisions = new AtomicLong(0L)

  def hits: Long       = _hits.get()
  def misses: Long     = _misses.get()
  def stores: Long     = _stores.get()
  def overwrites: Long = _overwrites.get()
  def collisions: Long = _collisions.get()

  def hitRate: Double =
    val h     = _hits.get()
    val m     = _misses.get()
    val total = h + m
    if total > 0 then h.toDouble / total else 0.0

  def resetTelemetry(): Unit =
    _hits.set(0L)
    _misses.set(0L)
    _stores.set(0L)
    _overwrites.set(0L)
    _collisions.set(0L)

  def clear(): Unit =
    java.util.Arrays.fill(bounds, 0.toByte)
    java.util.Arrays.fill(checksums, 0L)
    resetTelemetry()

  private inline def computeChecksum(
      key: Long,
      value: Double,
      boundByte: Byte,
      depth: Int,
      lossTainted: Boolean
  ): Long =
    key ^ java.lang.Double.doubleToRawLongBits(
      value
    ) ^ (boundByte.toLong << 56) ^ ((depth & 0xff).toLong << 48) ^ (if lossTainted
                                                                    then 1L << 40
                                                                    else 0L)

  /** Probes the transposition table for `key`.
    *
    * Returns `Some(TTEntry)` on a hit with valid checksum, or `None` on a miss or concurrent write race.
    */
  def probe(key: Long): Option[TTEntry] =
    val idx = (key & mask).toInt
    val k   = keys(idx)
    if k != key then
      _misses.incrementAndGet()
      None
    else
      val b = bounds(idx)
      if b == 0 then
        _misses.incrementAndGet()
        None
      else
        val v  = values(idx)
        val d  = depths(idx).toInt & 0xff
        val lt = lossTainteds(idx)
        val cs = checksums(idx)

        val expectedCs = computeChecksum(k, v, b, d, lt)
        if cs == expectedCs then
          _hits.incrementAndGet()
          val bound = b match
            case 1 => TTBound.Exact
            case 2 => TTBound.LowerBound
            case 3 => TTBound.UpperBound
            case _ => TTBound.Exact
          Some(TTEntry(k, v, bound, d, lt))
        else
          _misses.incrementAndGet()
          None

  /** Stores an entry in the transposition table using a depth-preferred replacement policy.
    *
    * `depth` must fit the byte-wide slot: the depth-3 work (#60) stays far below the 255 cap, and a silent wrap here
    * would let a shallow entry masquerade as a deep one.
    */
  def store(key: Long, value: Double, bound: TTBound, depth: Int, lossTainted: Boolean): Unit =
    require(depth >= 0 && depth <= 255, s"depth must fit the byte-wide slot [0, 255], got $depth")
    _stores.incrementAndGet()
    val idx      = (key & mask).toInt
    val oldKey   = keys(idx)
    val oldBound = bounds(idx)
    val oldDepth = depths(idx).toInt & 0xff

    val boundByte: Byte = bound match
      case TTBound.Exact      => 1
      case TTBound.LowerBound => 2
      case TTBound.UpperBound => 3

    val shouldReplace =
      if oldBound == 0 then true
      else if oldKey == key then depth >= oldDepth || oldBound != 1
      else depth >= oldDepth

    if shouldReplace then
      // Counted as outcomes, not attempts: a rejected shallower store changes nothing and increments nothing.
      if oldBound != 0 then if oldKey == key then _overwrites.incrementAndGet() else _collisions.incrementAndGet()
      val cs = computeChecksum(key, value, boundByte, depth, lossTainted)
      keys(idx) = key
      values(idx) = value
      bounds(idx) = boundByte
      depths(idx) = depth.toByte
      lossTainteds(idx) = lossTainted
      checksums(idx) = cs

object TranspositionTable:
  val DefaultCapacity: Int = 1 << 18
