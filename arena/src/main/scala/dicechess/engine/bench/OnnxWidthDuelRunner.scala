package dicechess.engine.bench

import com.monovore.decline.*
import cats.implicits.*

import dicechess.engine.search.*

/** Duels one model against itself at two different `candidateLimit` values, under a clock.
  *
  * The gap this fills: every other timed runner resolves its opponent through [[BotRegistry]] by id, so it can only
  * play an ONNX bot against a *built-in* bot. The question that matters for the pre-rank seam (`dicechess-ev#14`) is
  * narrower than that — hold the model fixed and vary only how many root candidates the search is allowed to expand.
  * Nothing could ask it in-process before this.
  *
  * Why it is worth asking. A probe over 300 corpus positions found that the material cut at `K=24` changes the search's
  * decision in 64% of positions with more than 24 legal turns, and that the hidden turns are usually better by
  * loss-taint margins — the narrow search's best candidate loses the king on some rolls where the wide one does not.
  * Yet the untimed K-curve flattened past 24. Both can only be true if the value never converts to wins, or if the
  * K-curve was measured too coarsely to see it (n=100 per cell resolves about ±10pp). This runner settles it by
  * changing one variable and nothing else — and it needs no trained ranker, so a null here closes `dicechess-ev#14`
  * before any training data is generated.
  *
  * Timed on purpose, unlike the probe. Width costs time: a wider search that finds a better move but arrives late is
  * not an improvement. That trade-off only exists under a clock, and it is hardware-dependent — the same comparison has
  * measured +28 elo on fast cores against +82 on an Ampere A1 — so the machine is part of the experiment, not a detail
  * of it.
  *
  * Reports through [[BotMatchRunner.runTimedMatch]], so a `--json` run carries the mirrored-pair histogram and the
  * `resolution` block (#508): what difference the run could actually have resolved, rather than only a win rate.
  *
  * Usage:
  * `sbt 'arena/runMain dicechess.engine.bench.OnnxWidthDuelRunner <model.onnx> --wide-k 48 --narrow-k 24 --features rich --games 10 --presets 3+2'`
  */
object OnnxWidthDuelRunner:

  def main(args: Array[String]): Unit =
    import ArenaOptions.*

    val command = Command(
      name = "OnnxWidthDuelRunner",
      header = "Dice Chess Bot Arena - ONNX Width Duel Runner"
    )(
      (
        modelPathOpt,
        wideKOpt(48),
        narrowKOpt(24),
        featuresOpt("rich"),
        gamesOpt(10),
        presetsOpt("3+2"),
        seedOpt(),
        sprtConfigOpt,
        jsonPathOpt,
        timePolicyOpt("wide-time-policy", "wide search"),
        timePolicyOpt("narrow-time-policy", "narrow search")
      ).mapN {
        (modelPath, wideK, narrowK, featureSet, games, presets, seed, sprtConfig, jsonPath, widePolicy, narrowPolicy) =>
          if wideK <= narrowK then sys.error(s"wideK ($wideK) must be greater than narrowK ($narrowK)")

          val extractFeatures = ArenaOptions.extractFeatures(featureSet)

          // Two sessions over the SAME file rather than one shared session: each side owns its own ONNX session exactly as a
          // deployed bot does, so neither gains from a warmed cache the other filled.
          val wide = new OnnxExpectimaxSearch(modelPath, ExpectimaxConfig(wideK), extractFeatures)
          try
            val narrow = new OnnxExpectimaxSearch(modelPath, ExpectimaxConfig(narrowK), extractFeatures)
            try
              println(
                s"Width duel: $modelPath ($featureSet) K=$wideK vs K=$narrowK, " +
                  s"$games mirrored pairs per control, controls=$presets, seed=$seed" +
                  sprtConfig.fold("")(_ => " (SPRT stopping on)")
              )
              // The WIDE side is the bot under test, so a reported score above 50% means widening helped.
              val results = TimedArenaRunner
                .parsePresets(presets)
                .map(tc =>
                  BotMatchRunner
                    .runTimedMatch(
                      wide,
                      narrow,
                      TimedMatchSetup(
                        games,
                        tc,
                        seed = seed,
                        sprtConfig = sprtConfig,
                        botTimeManager = TimeManager(widePolicy),
                        baselineTimeManager = TimeManager(narrowPolicy)
                      )
                    )
                )

              val wideId   = s"K=$wideK"
              val narrowId = s"K=$narrowK"
              BotMatchRunner.printTimedSummary(wideId, narrowId, results)
              jsonPath.foreach { path =>
                BotMatchRunner
                  .writeJsonReport(path, BotMatchRunner.timedReportJson(wideId, narrowId, games, seed, results))
                println(s"Wrote $path")
              }
            finally narrow.close()
          finally wide.close()
      }
    )

    ArenaOptions.runCommand(command, args)
