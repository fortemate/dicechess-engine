package dicechess.engine

import munit.FunSuite
import org.jline.reader.impl.DefaultParser
import java.io.{ByteArrayOutputStream, PrintStream}

class MainSpec extends FunSuite:

  test("processLine returns false for 'exit' and 'quit'"):
    val parser = new DefaultParser()
    assertEquals(Main.processLine("exit", parser), false)
    assertEquals(Main.processLine("quit", parser), false)

  test("processLine returns true for valid commands"):
    val parser = new DefaultParser()
    val out    = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      assertEquals(Main.processLine("eval 8/8/8/8/8/8/8/8 w - - 0 1", parser), true)
    }

  test("processLine returns true for help command"):
    val parser = new DefaultParser()
    val out    = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      assertEquals(Main.processLine("help", parser), true)
    }
    val output = out.toString
    assert(output.contains("Usage:"))

  test("processLine returns true for invalid command but handles it"):
    val parser = new DefaultParser()

    // Commands.rootCommand.parse in decline handles the error through System.err (not Console.err).
    // To capture System.err, we must rebind System.err directly.
    val oldErr = System.err
    val err    = new ByteArrayOutputStream()
    try
      System.setErr(new PrintStream(err))
      assertEquals(Main.processLine("unknown", parser), true)
    finally System.setErr(oldErr)

    val output = err.toString
    assert(output.contains("Unexpected argument: unknown"))
