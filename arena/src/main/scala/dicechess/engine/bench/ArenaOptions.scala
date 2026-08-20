package dicechess.engine.bench

import cats.data.Validated

import com.monovore.decline.*
import dicechess.engine.search.{ExpectimaxConfig, TimePolicies, TimePolicy}

private[bench] object ArenaOptions:

  val modelPathOpt: Opts[String] =
    Opts.argument[String](metavar = "model.onnx")

  val rescoreModelPathOpt: Opts[Option[String]] =
    Opts.option[String]("rescore-model", help = "Path to the rescore ONNX model").orNone

  val rescoreWeightOpt: Opts[Double] =
    Opts.option[Double]("rescore-weight", help = "Weight for rescore model").withDefault(0.5)

  def botUnderTestOpt(default: String = "monte-carlo"): Opts[String] =
    Opts
      .option[String]("bot", help = s"Bot under test ID (default: $default)")
      .orElse(Opts.option[String]("base-bot", help = s"Alias for --bot (default: $default)"))
      .withDefault(default)

  def baselineOpt(default: String = "aggressive"): Opts[String] =
    Opts
      .option[String]("baseline", help = s"Baseline bot ID (default: $default)")
      .orElse(Opts.option[String]("opponent", help = s"Alias for --baseline (default: $default)"))
      .withDefault(default)

  def baseBotOpt(default: String = "greedy"): Opts[String] =
    Opts.option[String]("base-bot", help = s"Baseline bot ID (default: $default)").withDefault(default)

  def opponentOpt(default: String = "aggressive"): Opts[String] =
    Opts.option[String]("opponent", help = s"Opponent bot ID (default: $default)").withDefault(default)

  def gamesOpt(default: Int = 50): Opts[Int] =
    Opts
      .option[Int]("games", help = s"Number of games per color (default: $default)")
      .withDefault(default)
      .validate("games must be > 0")(_ > 0)

  def seedOpt(default: Long = 42L): Opts[Long] =
    Opts.option[Long]("seed", help = s"Random seed (default: $default)").withDefault(default)

  val jsonPathOpt: Opts[Option[String]] =
    Opts.option[String]("json", help = "Path to write machine-readable JSON report").orNone

  val sprtConfigOpt: Opts[Option[SprtConfig]] =
    Opts
      .option[String]("sprt", help = "SPRT config as elo0,elo1,alpha,beta (e.g. -2,2,0.05,0.05)")
      .mapValidated { str =>
        val parts = str.split(",").map(_.trim)
        if parts.length == 4 then
          try
            val elo0  = parts(0).toDouble
            val elo1  = parts(1).toDouble
            val alpha = parts(2).toDouble
            val beta  = parts(3).toDouble
            if elo0.isNaN || elo1.isNaN || elo0.isInfinite || elo1.isInfinite then
              Validated.invalidNel("SPRT elo values must be finite numbers")
            else if elo0 >= elo1 then Validated.invalidNel("SPRT config values must have elo0 < elo1")
            else if alpha.isNaN || alpha <= 0.0 || alpha >= 1.0 then
              Validated.invalidNel("SPRT alpha must be strictly in (0.0, 1.0)")
            else if beta.isNaN || beta <= 0.0 || beta >= 1.0 then
              Validated.invalidNel("SPRT beta must be strictly in (0.0, 1.0)")
            else Validated.valid(SprtConfig(elo0, elo1, alpha, beta))
          catch case _: NumberFormatException => Validated.invalidNel("SPRT config values must be numbers")
        else Validated.invalidNel("SPRT config must be 4 comma-separated values: elo0,elo1,alpha,beta")
      }
      .orNone

  /** The feature sets [[extractFeatures]] knows how to build, in help-text order.
    *
    * Shared rather than inlined per option: a runner that takes a feature set for each of two sides would otherwise
    * carry a second copy of this list, and the copies drift the moment a set is added.
    */
  val FeatureSets: List[String] = List("material", "rich", "kcp", "rawboard")

  def featuresOpt(default: String = "rich"): Opts[String] =
    Opts
      .option[String]("features", help = s"Feature set: ${FeatureSets.mkString(", ")} (default: $default)")
      .withDefault(default)
      .validate("Unknown feature set")(set => FeatureSets.contains(set.toLowerCase))

  def extractFeatures(
      featureSet: String
  ): (dicechess.engine.domain.GameState, dicechess.engine.domain.Color) => Array[Float] =
    featureSet.toLowerCase match
      case "material" => dicechess.engine.search.OnnxFeatures.extract
      case "rich"     => dicechess.engine.search.RichFeatures.extract
      case "kcp"      => dicechess.engine.search.KcpFeatures.extract
      case "rawboard" => dicechess.engine.search.RawBoardFeatures.extract
      case other      => sys.error(s"Unknown feature set '$other'")

  private val limitMustBePositive = "limit must be > 0"

  def candidateLimitOpt(default: Int = ExpectimaxConfig().candidateLimit): Opts[Int] =
    Opts
      .option[Int]("candidate-limit", help = s"Candidate limit for expectimax search (default: $default)")
      .withDefault(default)
      .validate(limitMustBePositive)(_ > 0)

  /** The same option without a default, for runners that must tell "not given" from "given the default value".
    *
    * A candidate limit is an [[ExpectimaxConfig]] parameter and means nothing at one ply, so a runner offering both
    * search depths has to reject the combination rather than ignore the flag — and it cannot detect an explicit
    * `--candidate-limit 24` through [[candidateLimitOpt]], whose default is 24.
    */
  val optionalCandidateLimitOpt: Opts[Option[Int]] =
    Opts
      .option[Int]("candidate-limit", help = "Candidate limit for expectimax search (expectimax only)")
      .validate(limitMustBePositive)(_ > 0)
      .orNone

  def searchOpt: Opts[SearchKind] =
    Opts
      .option[String](
        "search",
        help = s"Search over the model: ${SearchKind.available.map(_.id).mkString(", ")}" +
          s" (default: ${SearchKind.default.id})"
      )
      .withDefault(SearchKind.default.id)
      .mapValidated { id =>
        SearchKind.get(id) match
          case Some(kind) => Validated.valid(kind)
          case None       =>
            Validated.invalidNel(
              s"Unknown search '$id' (available: ${SearchKind.available.map(_.id).mkString(", ")})"
            )
      }

  val preRankWithModelOpt: Opts[Boolean] =
    Opts.flag("pre-rank-with-model", help = "Use rescore model for pre-ranking").orFalse

  def bookOpt(default: String = "opening_book.tsv"): Opts[String] =
    Opts.option[String]("book", help = s"Path to the opening book TSV file (default: $default)").withDefault(default)

  val bookPathOpt: Opts[Option[String]] =
    Opts.option[String]("book", help = "Path to the opening book TSV file").orNone

  val reqBookPathOpt: Opts[String] =
    Opts.argument[String](metavar = "book.tsv")

  def presetsOpt(default: String = "1+0,3+2,10+10"): Opts[String] =
    Opts.option[String]("presets", help = s"Comma-separated time controls (default: $default)").withDefault(default)

  def timePolicyOpt(name: String, role: String): Opts[TimePolicy] =
    Opts
      .option[String](
        name,
        help = s"Time policy for the $role: ${TimePolicies.available.map(_.id).mkString(", ")}"
      )
      .withDefault(TimePolicies.default.id)
      .mapValidated { id =>
        TimePolicies.get(id) match
          case Some(policy) => Validated.valid(policy)
          case None         =>
            Validated.invalidNel(
              s"Unknown time policy '$id' (available: ${TimePolicies.available.map(_.id).mkString(", ")})"
            )
      }

  def wideKOpt(default: Int = 48): Opts[Int] =
    Opts
      .option[Int]("wide-k", help = s"Candidate limit for wide search (default: $default)")
      .withDefault(default)
      .validate("wide-k must be > 0")(_ > 0)

  def narrowKOpt(default: Int = 24): Opts[Int] =
    Opts
      .option[Int]("narrow-k", help = s"Candidate limit for narrow search (default: $default)")
      .withDefault(default)
      .validate("narrow-k must be > 0")(_ > 0)

  val corpusPathOpt: Opts[String] =
    Opts.argument[String](metavar = "corpus.csv.gz")

  def positionsOpt(default: Int = 300): Opts[Int] =
    Opts
      .option[Int]("positions", help = s"Number of positions to probe (default: $default)")
      .withDefault(default)
      .validate("positions must be > 0")(_ > 0)

  def limitOpt(default: Int = 24): Opts[Int] =
    Opts
      .option[Int]("limit", help = s"Production candidate limit under test (default: $default)")
      .withDefault(default)
      .validate(limitMustBePositive)(_ > 0)

  /** Parses `args` and runs the command, turning both a bad argument list and a failure inside the command body into a
    * one-line `Left`.
    *
    * `parse` is inside the `try` deliberately. These are `Command[Unit]`, so decline has already *executed* the body by
    * the time it hands back a `Right` — the body's effects are what produced the `Unit`. Catching around the result
    * instead of around `parse` therefore catches nothing at all: the exception escapes first, and the runner dies with
    * a stack trace instead of the error message this function exists to produce.
    */
  def parseAndRun(command: Command[Unit], args: Array[String]): Either[String, Unit] =
    try
      command.parse(args.toIndexedSeq, sys.env) match
        case Left(help) => Left(help.toString)
        case Right(())  => Right(())
    catch
      case e: Exception =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        Left(msg)

  def runCommand(command: Command[Unit], args: Array[String]): Unit =
    parseAndRun(command, args) match
      case Left(err) =>
        System.err.println(err)
        sys.exit(1)
      case Right(()) => ()
