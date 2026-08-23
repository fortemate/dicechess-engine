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

/** Fixed-size, thread-safe Transposition Table for expectimax search.
  *
  * Stored in flat primitive arrays to ensure zero-GC pressure on hot search paths. Uses lockless XOR checksum
  * verification for thread-safe concurrent reads.
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

  /** Stores an entry in the transposition table using a depth-preferred replacement policy. */
  def store(key: Long, value: Double, bound: TTBound, depth: Int, lossTainted: Boolean): Unit =
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
      else if oldKey == key then
        _overwrites.incrementAndGet()
        depth >= oldDepth || oldBound != 1
      else
        _collisions.incrementAndGet()
        depth >= oldDepth

    if shouldReplace then
      val cs = computeChecksum(key, value, boundByte, depth, lossTainted)
      keys(idx) = key
      values(idx) = value
      bounds(idx) = boundByte
      depths(idx) = depth.toByte
      lossTainteds(idx) = lossTainted
      checksums(idx) = cs

object TranspositionTable:
  val DefaultCapacity: Int = 1 << 18
