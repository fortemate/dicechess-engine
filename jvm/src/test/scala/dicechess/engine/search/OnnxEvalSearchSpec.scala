package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Exercises the ONNX plumbing (feature extraction -> tensor -> session -> parsed output) against a tiny synthetic
  * model trained on random noise — it has no chess signal whatsoever, and is not meant to. A real, trained model is a
  * private artifact kept in a separate repository, not published alongside this codebase; this fixture only proves the
  * wiring is correct.
  */
class OnnxEvalSearchSpec extends FunSuite:

  private val modelPath =
    getClass.getResource("/synthetic_test_model.onnx").getPath

  test("onnxEval runs without throwing and returns a score on the scaled [0, 10000] axis") {
    val bot   = new OnnxEvalSearch(modelPath)
    val state = FenParser.parse(FenParser.InitialPosition).toOption.get
    try
      val score = bot.onnxEval(state, Color.White)
      assert(score >= 0 && score <= 10000, s"expected a score in [0, 10000], got $score")
    finally bot.close()
  }

  test("onnxEval is symmetric for a material-balanced position regardless of color") {
    // At the starting position, White's own material equals Black's own material, so
    // OnnxFeatures.extract(state, White) and OnnxFeatures.extract(state, Black) must be
    // identical feature vectors — the same invariant holds regardless of what the model itself
    // learned, since it's a property of the mover-perspective feature extraction, not the model.
    val bot   = new OnnxEvalSearch(modelPath)
    val state = FenParser.parse(FenParser.InitialPosition).toOption.get
    try assertEquals(bot.onnxEval(state, Color.White), bot.onnxEval(state, Color.Black))
    finally bot.close()
  }

  test("findBestMove returns a legal move from the starting position") {
    val bot   = new OnnxEvalSearch(modelPath)
    val start = FenParser.parse(FenParser.InitialPosition).toOption.get
    val state = start.copy(flags = start.flags.withDicePool(List(1, 1, 4)))
    try
      val result = bot.findBestMove(state, scala.util.Random(0))
      assert(result.isDefined)
    finally bot.close()
  }

  // A few materially-distinct positions so the batch has real variation to reproduce.
  private val fens = List(
    FenParser.InitialPosition,
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR w KQkq - 0 1", // White missing a queen
    "r1bqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"  // Black missing a knight
  )

  test("onnxEvalBatch matches evaluating each position individually, in order") {
    val bot    = new OnnxEvalSearch(modelPath)
    val states = fens.map(fen => FenParser.parse(fen).toOption.get).toArray
    try
      val batched    = bot.onnxEvalBatch(states, Color.White)
      val individual = states.map(bot.onnxEval(_, Color.White))
      assertEquals(batched.toList, individual.toList)
    finally bot.close()
  }

  test("onnxEvalBatch on an empty input yields an empty result") {
    val bot = new OnnxEvalSearch(modelPath)
    try assertEquals(bot.onnxEvalBatch(Array.empty, Color.White).toList, Nil)
    finally bot.close()
  }

  test("onnxEval feeds the supplied extractor's output to the model (9-wide RichFeatures is rejected here)") {
    // Proves the constructor's extractor actually drives the tensor: RichFeatures' 9 columns can't
    // fit this 7-feature model, so the session run must fail. The default (OnnxFeatures, 7) is
    // exercised by every other test.
    val bot   = new OnnxEvalSearch(modelPath, RichFeatures.extract)
    val state = FenParser.parse(FenParser.InitialPosition).toOption.get
    try intercept[Exception](bot.onnxEval(state, Color.White))
    finally bot.close()
  }

  test("onnxEvalBatch also routes through the supplied extractor (9-wide RichFeatures is rejected here)") {
    val bot    = new OnnxEvalSearch(modelPath, RichFeatures.extract)
    val states = Array(FenParser.parse(FenParser.InitialPosition).toOption.get)
    try intercept[Exception](bot.onnxEvalBatch(states, Color.White))
    finally bot.close()
  }

  // Fixture with 268 legal turn paths (roll 1,1,4 from the starting position) — comfortably more than one
  // OnnxEvalSearch.BatchSize (32) chunk, so a deadline landing mid-search has more than one chunk to cut off.
  private def wideBranchingState: GameState =
    val start = FenParser.parse(FenParser.InitialPosition).toOption.get
    start.copy(flags = start.flags.withDicePool(List(1, 1, 4)))

  test(
    "findBestMove(deadline) still returns a legal turn when the deadline has already elapsed on entry (#497, #171)"
  ) {
    var chunkCalls = 0
    val deadline   = 1_000L
    val bot        = new OnnxEvalSearch(modelPath, clock = () => 2_000L) {
      override def onnxEvalBatch(states: Array[GameState], color: Color): Array[Int] =
        chunkCalls += 1
        super.onnxEvalBatch(states, color)
    }
    try
      val result = bot.findBestMove(wideBranchingState, deadline, scala.util.Random(0))
      assert(result.isDefined, "the anytime contract must still return a legal turn")
      assert(result.get.moves.nonEmpty)
      assertEquals(chunkCalls, 0, "no chunks should run when the deadline has already expired")
    finally bot.close()
  }

  test(
    "findBestMove(deadline) returns the best candidate from completed chunks and skips remaining chunks (#497, #171)"
  ) {
    var chunkCalls  = 0
    var now         = 0L
    val deadline    = 100L
    val allPaths    = TurnGenerator.generateAllLegalTurnPaths(wideBranchingState)
    val totalChunks = math.ceil(allPaths.length.toDouble / OnnxEvalSearch.BatchSize).toInt
    val bot         = new OnnxEvalSearch(modelPath, clock = () => now) {
      override def onnxEvalBatch(states: Array[GameState], color: Color): Array[Int] =
        chunkCalls += 1
        now = deadline + 1L // Cut off after the first chunk completes
        super.onnxEvalBatch(states, color)
    }
    try
      val result = bot.findBestMove(wideBranchingState, deadline, scala.util.Random(0))
      assert(result.isDefined)
      assert(result.get.moves.nonEmpty)
      assertEquals(chunkCalls, 1, "expected exactly one chunk to complete before cutoff")
      assert(chunkCalls < totalChunks, s"expected remaining chunks out of $totalChunks to be skipped")
      val firstChunkPaths = allPaths.take(OnnxEvalSearch.BatchSize)
      assert(
        firstChunkPaths.contains(result.get.moves),
        "selected move must originate from the completed first chunk"
      )
    finally bot.close()
  }

  test(
    "findBestMove(deadline) deterministically considers completed-chunk candidates over skipped ones (#171)"
  ) {
    var chunkCalls       = 0
    var now              = 0L
    val deadline         = 100L
    val allPaths         = TurnGenerator.generateAllLegalTurnPaths(wideBranchingState)
    val targetChunk0Path = allPaths(5)
    val bot              = new OnnxEvalSearch(modelPath, clock = () => now) {
      override def onnxEvalBatch(states: Array[GameState], color: Color): Array[Int] =
        val chunkIndex = chunkCalls
        chunkCalls += 1
        now = deadline + 1L // Expire deadline so chunk 1 never runs
        val scores = new Array[Int](states.length)
        var idx    = 0
        while idx < states.length do
          val globalIndex = chunkIndex * OnnxEvalSearch.BatchSize + idx
          scores(idx) = if globalIndex == 5 then 8000 else if globalIndex == 35 then 9999 else 1000
          idx += 1
        scores
    }
    try
      val result = bot.findBestMove(wideBranchingState, deadline, scala.util.Random(0))
      assert(result.isDefined)
      assertEquals(chunkCalls, 1, "only chunk 0 should have executed")
      assertEquals(result.get.score, 8000, "best score must be from the completed chunk 0")
      assertEquals(result.get.moves, targetChunk0Path, "chosen move must be candidate 5 from chunk 0")
    finally bot.close()
  }

  test("findBestMove(deadline) considers all completed chunks when multiple complete before deadline (#171)") {
    var chunkCalls       = 0
    var now              = 0L
    val deadline         = 200L
    val allPaths         = TurnGenerator.generateAllLegalTurnPaths(wideBranchingState)
    val targetChunk1Path = allPaths(35)
    val bot              = new OnnxEvalSearch(modelPath, clock = () => now) {
      override def onnxEvalBatch(states: Array[GameState], color: Color): Array[Int] =
        val chunkIndex = chunkCalls
        chunkCalls += 1
        if chunkCalls >= 2 then now = deadline + 1L // Expire deadline after 2 chunks
        val scores = new Array[Int](states.length)
        var idx    = 0
        while idx < states.length do
          val globalIndex = chunkIndex * OnnxEvalSearch.BatchSize + idx
          scores(idx) = if globalIndex == 5 then 8000 else if globalIndex == 35 then 9999 else 1000
          idx += 1
        scores
    }
    try
      val result = bot.findBestMove(wideBranchingState, deadline, scala.util.Random(0))
      assert(result.isDefined)
      assertEquals(chunkCalls, 2, "exactly two chunks should have executed")
      assertEquals(result.get.score, 9999, "best score must come from chunk 1 which completed")
      assertEquals(result.get.moves, targetChunk1Path, "chosen move must be candidate 35 from chunk 1")
    finally bot.close()
  }
