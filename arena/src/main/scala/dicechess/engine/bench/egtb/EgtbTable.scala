// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{Color, GameState, PieceType, Square}
import java.io.{
  BufferedInputStream,
  BufferedOutputStream,
  DataInputStream,
  DataOutputStream,
  File,
  FileInputStream,
  InputStream
}
import java.nio.file.{FileAlreadyExistsException, Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.{Callable, Executors}
import scala.jdk.CollectionConverters.*

final case class EgtbConfig(
    discount: Double = 0.995,
    epsilon: Double = 1e-4,
    maxIterations: Int = 500,
    threads: Int = Runtime.getRuntime.availableProcessors(),
    maxKw: Int = 64
):
  require(maxKw >= 1 && maxKw <= 64, s"maxKw must be in [1, 64], got $maxKw")
  require(threads >= 1, s"threads must be >= 1, got $threads")
  require(discount.isFinite && discount > 0.0 && discount <= 1.0, s"discount must be in (0.0, 1.0], got $discount")
  require(epsilon.isFinite && epsilon > 0.0, s"epsilon must be finite and > 0, got $epsilon")
  require(maxIterations >= 1, s"maxIterations must be >= 1, got $maxIterations")

final case class EgtbResult(
    iterations: Int,
    maxDelta: Double,
    elapsedMs: Long,
    vWhite: Array[Float],
    vBlack: Array[Float],
    avgWhiteValue: Double,
    avgBlackValue: Double,
    whiteWinCount: Int,
    blackUpsetCount: Int
)

/** O(1) in-memory probe table for 3-piece Dice Chess Endgame Tablebases (EGTB).
  *
  * Backed by a 16-bit fixed-point array (1 MB total memory footprint). Provides instantaneous evaluation of 3-piece
  * endgame positions with exact game-theoretic win probabilities.
  */
final class EgtbTable private (
    val pieceType: PieceType,
    private val vWhite: Array[Short],
    private val vBlack: Array[Short]
):

  /** Probes the win probability for White in [0.0, 1.0].
    *
    * @param kw
    *   White King square index in [0, 63]
    * @param kb
    *   Black King square index in [0, 63]
    * @param pieceSq
    *   Auxiliary White piece square index in [0, 63]
    * @param isWhiteTurn
    *   True if White is to move, false if Black is to move
    * @return
    *   Game-theoretic value V(s) in [0.0, 1.0] (probability of White winning)
    */
  def probe(kw: Int, kb: Int, pieceSq: Int, isWhiteTurn: Boolean): Option[Double] =
    if KQEgtbSolver.isLegal(kw, kb, pieceSq) then
      val idx      = KQEgtbSolver.stateIndex(kw, kb, pieceSq)
      val raw      = if isWhiteTurn then vWhite(idx) else vBlack(idx)
      val unsigned = raw.toInt & 0xffff
      Some(unsigned.toDouble / 65535.0)
    else None

  /** Probes a live GameState directly. Returns Some(probability) if the state matches this table's 3-piece material
    * configuration, or None if the state is not a 3-piece position of this type.
    */
  def probeState(state: GameState): Option[Double] =
    val whitePieces = state.whitePieces
    val blackPieces = state.blackPieces
    val kings       = state.kings

    val kwBit = whitePieces & kings
    val kbBit = blackPieces & kings

    val hasValidKings  = kwBit.count == 1 && kbBit.count == 1
    val hasValidPieces = blackPieces.count == 1 && whitePieces.count == 2

    if hasValidKings && hasValidPieces then
      val auxPieces = whitePieces & ~kings
      if auxPieces.count == 1 then
        val kw        = java.lang.Long.numberOfTrailingZeros(kwBit.value)
        val kb        = java.lang.Long.numberOfTrailingZeros(kbBit.value)
        val auxSq     = java.lang.Long.numberOfTrailingZeros(auxPieces.value)
        val pieceOnSq = state.mailbox(Square.fromIndex(auxSq))
        if pieceOnSq.pieceType == pieceType then probe(kw, kb, auxSq, state.activeColor.isWhite)
        else None
      else None
    else None

object EgtbTable:

  private def reject(condition: Boolean, message: String): Unit =
    if !condition then scala.util.Failure(new IllegalArgumentException(message)).get

  private def rejectExistingTarget(targetPath: Path): Unit =
    if Files.exists(targetPath) then
      scala.util
        .Failure(
          new FileAlreadyExistsException(
            s"EGTB output file already exists at '$targetPath'. Pass --force to overwrite."
          )
        )
        .get

  def load(file: File): EgtbTable =
    load(new BufferedInputStream(new FileInputStream(file)))

  def load(inStream: InputStream): EgtbTable =
    val in = new DataInputStream(inStream)
    try
      val magic = new Array[Byte](4)
      in.readFully(magic)
      val magicStr = new String(magic, "US-ASCII")
      require(magicStr == "EGTB", s"Invalid EGTB magic header: $magicStr")

      val version = in.readByte()
      require(version == 1, s"Unsupported EGTB version: $version")

      val typeCode = in.readByte()
      reject(typeCode >= 0 && typeCode <= 4, s"Unknown EGTB piece type code: $typeCode")
      val pieceType = typeCode match
        case 0 => PieceType.Queen
        case 1 => PieceType.Rook
        case 2 => PieceType.Bishop
        case 3 => PieceType.Knight
        case 4 => PieceType.Pawn
        case _ => PieceType.Pawn

      val boardSize = in.readShort()
      require(boardSize == 64, s"Unsupported board size: $boardSize")

      val states = KQEgtbSolver.StatesPerTurn
      val vW     = new Array[Short](states)
      val vB     = new Array[Short](states)

      var i = 0
      while i < states do
        vW(i) = in.readShort()
        i += 1

      i = 0
      while i < states do
        vB(i) = in.readShort()
        i += 1

      new EgtbTable(pieceType, vW, vB)
    finally in.close()

  private def findExistingParent(path: Path): Option[Path] =
    var checkDir = Option(path.getParent)
    while checkDir.exists(dir => !Files.exists(dir)) do checkDir = checkDir.flatMap(dir => Option(dir.getParent))
    checkDir

  private def checkAllowedDirectory(targetPath: Path, base: Path): Unit =
    val normalizedBase = base.toAbsolutePath.normalize()
    val realBase       = if Files.exists(normalizedBase) then normalizedBase.toRealPath() else normalizedBase
    reject(
      targetPath.startsWith(normalizedBase) || targetPath.startsWith(realBase),
      s"Refusing to write EGTB to unauthorized path '$targetPath'. Output must reside within '$normalizedBase'."
    )

    findExistingParent(targetPath).foreach { parent =>
      val realParent = parent.toRealPath()
      reject(
        realParent.startsWith(realBase),
        s"Refusing to write EGTB to unauthorized path '$targetPath'. Directory resolves via symlink to '$realParent', outside authorized '$realBase'."
      )
    }

  private def validateTargetPath(targetPath: Path, allowedDir: Option[Path], force: Boolean): Unit =
    reject(!Files.isSymbolicLink(targetPath), s"Refusing to write EGTB to symbolic link: $targetPath")

    allowedDir.foreach(base => checkAllowedDirectory(targetPath, base))

    if !force then rejectExistingTarget(targetPath)

  private def writeBinaryTable(
      tempFile: Path,
      pieceType: PieceType,
      vWhite: Array[Float],
      vBlack: Array[Float]
  ): Unit =
    val outStream = Files.newOutputStream(tempFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    val out       = new DataOutputStream(new BufferedOutputStream(outStream))
    try
      out.writeBytes("EGTB")
      out.writeByte(1)
      reject(
        Set(PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight, PieceType.Pawn).contains(pieceType),
        s"Unsupported EGTB piece type: $pieceType"
      )
      val typeCode = pieceType match
        case PieceType.Queen  => 0
        case PieceType.Rook   => 1
        case PieceType.Bishop => 2
        case PieceType.Knight => 3
        case PieceType.Pawn   => 4
        case _                => 0
      out.writeByte(typeCode)
      out.writeShort(64)

      var i = 0
      while i < KQEgtbSolver.StatesPerTurn do
        val wFixed = (vWhite(i).min(1.0f).max(0.0f) * 65535.0f).round.toShort
        out.writeShort(wFixed)
        i += 1

      i = 0
      while i < KQEgtbSolver.StatesPerTurn do
        val bFixed = (vBlack(i).min(1.0f).max(0.0f) * 65535.0f).round.toShort
        out.writeShort(bFixed)
        i += 1
    finally out.close()

  def save(
      targetFile: File,
      pieceType: PieceType,
      vWhite: Array[Float],
      vBlack: Array[Float],
      force: Boolean = false,
      allowedDir: Option[Path] = Some(Paths.get("").toAbsolutePath.normalize())
  ): File =
    val targetPath = targetFile.toPath.toAbsolutePath.normalize()
    validateTargetPath(targetPath, allowedDir, force)

    val parent = Option(targetPath.getParent)
    parent.filterNot(path => Files.exists(path)).foreach(path => Files.createDirectories(path))

    val tempFile = Files.createTempFile(
      parent.getOrElse(Paths.get(".")),
      s".${targetPath.getFileName.toString}.",
      ".tmp"
    )
    try
      writeBinaryTable(tempFile, pieceType, vWhite, vBlack)
      if force then
        Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      else
        rejectExistingTarget(targetPath)
        Files.move(tempFile, targetPath)

      println(s"Saved compressed EGTB to $targetPath (${Files.size(targetPath)} bytes)")
      targetPath.toFile
    finally Files.deleteIfExists(tempFile)

  final private case class ValueArrays(
      vWhiteCurrent: Array[Float],
      vBlackCurrent: Array[Float],
      vWhiteNext: Array[Float],
      vBlackNext: Array[Float]
  )

  def runValueIteration(
      name: String,
      config: EgtbConfig,
      initWhite: Float,
      initBlack: Float,
      auxRange: Range = 0 until 64,
      evalWhite: (Int, Int, Int, Array[Float], Float) => Float,
      evalBlack: (Int, Int, Int, Array[Float], Float) => Float
  ): EgtbResult =
    val startTime = System.currentTimeMillis()
    val gamma     = config.discount.toFloat

    var vWhiteCurrent = new Array[Float](KQEgtbSolver.StatesPerTurn)
    var vBlackCurrent = new Array[Float](KQEgtbSolver.StatesPerTurn)
    var vWhiteNext    = new Array[Float](KQEgtbSolver.StatesPerTurn)
    var vBlackNext    = new Array[Float](KQEgtbSolver.StatesPerTurn)

    for
      kw  <- 0 until 64
      kb  <- 0 until 64
      aux <- auxRange
    do
      val idx = KQEgtbSolver.stateIndex(kw, kb, aux)
      if KQEgtbSolver.isLegal(kw, kb, aux) then
        vWhiteCurrent(idx) = initWhite
        vBlackCurrent(idx) = initBlack

    require(config.maxKw >= 1 && config.maxKw <= 64, s"maxKw must be in [1, 64], got ${config.maxKw}")
    require(config.threads >= 1, s"threads must be >= 1, got ${config.threads}")
    val executor  = Executors.newFixedThreadPool(config.threads)
    var iteration = 0
    var maxDelta  = 1.0

    try
      while iteration < config.maxIterations && maxDelta > config.epsilon do
        iteration += 1

        val arrays = ValueArrays(vWhiteCurrent, vBlackCurrent, vWhiteNext, vBlackNext)
        val tasks  = (0 until config.maxKw).map { kw =>
          createIterationTask(
            kw,
            auxRange,
            arrays,
            gamma,
            evalWhite,
            evalBlack
          )
        }

        val futures = executor.invokeAll(tasks.asJava)
        maxDelta = futures.asScala.map(_.get()).max

        val tmpW = vWhiteCurrent
        vWhiteCurrent = vWhiteNext
        vWhiteNext = tmpW

        val tmpB = vBlackCurrent
        vBlackCurrent = vBlackNext
        vBlackNext = tmpB

        if iteration % 25 == 0 || maxDelta <= config.epsilon then
          val elapsed = System.currentTimeMillis() - startTime
          println(
            f"[EGTB $name] Iteration $iteration%3d | maxDelta = $maxDelta%.6f | elapsed = ${elapsed / 1000.0}%.2fs"
          )

    finally executor.shutdown()

    val elapsed = System.currentTimeMillis() - startTime
    computeFinalStats(iteration, maxDelta, elapsed, vWhiteCurrent, vBlackCurrent, auxRange)

  private def createIterationTask(
      kw: Int,
      auxRange: Range,
      arrays: ValueArrays,
      gamma: Float,
      evalWhite: (Int, Int, Int, Array[Float], Float) => Float,
      evalBlack: (Int, Int, Int, Array[Float], Float) => Float
  ): Callable[Double] =
    new Callable[Double] {
      override def call(): Double =
        var localMaxDelta = 0.0

        for
          kb  <- 0 until 64
          aux <- auxRange
        do
          val idx = KQEgtbSolver.stateIndex(kw, kb, aux)
          if KQEgtbSolver.isLegal(kw, kb, aux) then
            val newW   = evalWhite(kw, kb, aux, arrays.vBlackCurrent, gamma)
            val deltaW = math.abs(newW - arrays.vWhiteCurrent(idx))
            arrays.vWhiteNext(idx) = newW
            if deltaW > localMaxDelta then localMaxDelta = deltaW

            val newB   = evalBlack(kw, kb, aux, arrays.vWhiteCurrent, gamma)
            val deltaB = math.abs(newB - arrays.vBlackCurrent(idx))
            arrays.vBlackNext(idx) = newB
            if deltaB > localMaxDelta then localMaxDelta = deltaB

        localMaxDelta
    }

  private def computeFinalStats(
      iteration: Int,
      maxDelta: Double,
      elapsedMs: Long,
      vWhite: Array[Float],
      vBlack: Array[Float],
      auxRange: Range
  ): EgtbResult =
    var sumW        = 0.0
    var sumB        = 0.0
    var count       = 0
    var whiteWins   = 0
    var blackUpsets = 0

    for
      kw  <- 0 until 64
      kb  <- 0 until 64
      aux <- auxRange
    do
      val idx = KQEgtbSolver.stateIndex(kw, kb, aux)
      if KQEgtbSolver.isLegal(kw, kb, aux) then
        count += 1
        val w = vWhite(idx)
        val b = vBlack(idx)
        sumW += w
        sumB += b
        if w >= 0.95f then whiteWins += 1
        if b < 0.60f then blackUpsets += 1

    EgtbResult(
      iterations = iteration,
      maxDelta = maxDelta,
      elapsedMs = elapsedMs,
      vWhite = vWhite,
      vBlack = vBlack,
      avgWhiteValue = if count > 0 then sumW / count else 0.0,
      avgBlackValue = if count > 0 then sumB / count else 0.0,
      whiteWinCount = whiteWins,
      blackUpsetCount = blackUpsets
    )
