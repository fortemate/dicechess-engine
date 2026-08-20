package dicechess.engine

import dicechess.engine.cli.Commands
import org.jline.reader.{LineReaderBuilder, UserInterruptException, EndOfFileException}
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.TerminalBuilder

/** Primary entry point for the Dice Chess Engine JVM CLI.
  *
  * This object owns the interactive JVM terminal lifecycle, managing the JLine `LineReader` and the event loop. It acts
  * as the boundary where raw terminal input is captured and converted into structured commands.
  */
object Main:
  def main(args: Array[String]): Unit =
    val terminal = TerminalBuilder
      .builder()
      .system(true)
      .build()

    val completer = new StringsCompleter("eval", "help", "exit")

    val lineReader = LineReaderBuilder
      .builder()
      .terminal(terminal)
      .completer(completer)
      .build()

    println("==================================================")
    println("🎲♟️ Dice Chess Engine Interactive CLI")
    println("Type 'help' for commands, or 'exit' to quit.")
    println("==================================================")

    var running = true
    val parser  = new org.jline.reader.impl.DefaultParser()

    try
      while running do
        try
          val line = lineReader.readLine("dicechess> ").trim
          if line.nonEmpty then running = processLine(line, parser)
        catch
          case _: UserInterruptException =>
          // Ignore, just clear the line
          case _: EndOfFileException =>
            running = false
          case e: Exception =>
            System.err.println(s"Error: ${e.getMessage}")
    finally
      terminal.close()

  private def processLine(line: String, parser: org.jline.reader.Parser): Boolean =
    if line == "exit" || line == "quit" then false
    else
      import scala.jdk.CollectionConverters.*
      val parsedLine = parser.parse(line, 0)
      val tokens     = parsedLine.words().asScala.toList

      if tokens.headOption.contains("help") then println(Commands.rootCommand.showHelp)
      else
        Commands.rootCommand.parse(tokens) match
          case Left(help) => System.err.println(help)
          case Right(cmd) => Commands.execute(cmd)
      true
