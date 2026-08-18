package dicechess.engine.bench

import scala.io.Source

import com.monovore.decline.*
import cats.implicits.*
import dicechess.engine.search.{BotInfo, BotRegistry, OpeningBookBot, OpeningBookParser}

/** Local arena between a base bot and the same bot decorated with an opening book.
  *
  * The book is read from a file path at runtime, so the book data never has to be committed to this public repository —
  * keep your `opening_book.tsv` git-ignored. The runner registers `<base>-book` (the base bot wrapped by
  * [[dicechess.engine.search.OpeningBookBot]]) and pits the base bot against it via [[BotMatchRunner.runArena]].
  *
  * Usage:
  * `runMain dicechess.engine.bench.OpeningBookArenaRunner --base-bot <baseBotId> --book <bookPath> --games <gamesPerColor> --seed <seed>`
  * (or `mise run arena:book <base> <bookPath> [games]`).
  */
object OpeningBookArenaRunner:
  def main(args: Array[String]): Unit =
    val command = Command(
      name = "OpeningBookArenaRunner",
      header = "Dice Chess Bot Arena - Opening Book Runner"
    ) {
      import ArenaOptions.*
      (baseBotOpt("aggressive"), bookOpt("opening_book.tsv"), gamesOpt(50), seedOpt()).mapN {
        (baseBotId, bookPath, games, seed) =>
          val baseInfo = BotRegistry.availableBots
            .find(_.id.equalsIgnoreCase(baseBotId))
            .getOrElse(sys.error(s"Unknown base bot '$baseBotId'"))
          val baseAlgorithm = BotRegistry.getAlgorithm(baseBotId).get

          val tsv =
            val source = Source.fromFile(bookPath)
            try source.mkString
            finally source.close()
          val book = OpeningBookParser
            .parse(tsv)
            .fold(error => sys.error(s"Failed to parse opening book '$bookPath': ${error.getMessage}"), identity)

          val bookId = s"${baseInfo.id}-book"
          BotRegistry.registerCustomBot(
            BotInfo(
              id = bookId,
              name = s"${baseInfo.name} + Book",
              description = s"${baseInfo.id} decorated with an opening book ($bookPath)",
              difficulty = baseInfo.difficulty,
              isExperimental = true
            ),
            OpeningBookBot.decorate(baseAlgorithm, book)
          )

          println(s"Loaded ${book.size} opening-book entries from $bookPath")
          BotMatchRunner.runArena(baseBotId, Some(bookId), games, BotMatchRunner.StartFen, seed = seed, jsonPath = None)
      }
    }
    ArenaOptions.runCommand(command, args)
