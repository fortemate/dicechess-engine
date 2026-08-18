package dicechess.engine.cli

import cats.implicits.*
import com.monovore.decline.*
import dicechess.engine.domain.FenParser
import dicechess.engine.search.KingCaptureProbability

/** Sealed CLI command hierarchy representing parsed REPL actions. */
sealed trait CliCommand
case class EvalCommand(fen: String, unicode: Boolean) extends CliCommand

/** Command parsing schema and evaluation for interactive CLI usage.
  *
  * This module leverages `decline` to rebuild standard FEN strings from split terminal arguments and bind them to the
  * internal command hierarchy.
  */
object Commands:

  val fenOpt = Opts.arguments[String]("FEN").map(_.toList.mkString(" "))

  val unicodeOpt = Opts.flag("unicode", help = "Use Unicode characters for chess pieces").orFalse

  val evalCommand = Opts.subcommand("eval", "Evaluate a position and print the board"):
    (fenOpt, unicodeOpt).mapN(EvalCommand.apply)

  val rootCommand = Command("dicechess", "Dice Chess Engine CLI"):
    evalCommand

  def execute(command: CliCommand): Unit = command match
    case EvalCommand(fen, unicode) =>
      FenParser.parse(fen) match
        case Right(state) =>
          println(BoardPrinter.printBoard(state, unicode))

          val opponent = state.activeColor.opponent
          val kcp      = KingCaptureProbability.kingCaptureProbability(state, opponent)
          val qcp      = KingCaptureProbability.queenCaptureProbability(state, opponent)

          println(f"King Capture Probability (for ${opponent}): ${kcp * 100}%.1f%%")
          println(f"Queen Capture Probability (for ${opponent}): ${qcp * 100}%.1f%%")

        case Left(err) =>
          println(s"Error: $err")
