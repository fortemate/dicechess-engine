package dicechess.engine.cli

import munit.FunSuite

class CommandsSpec extends FunSuite:

  test("Commands.rootCommand parses 'eval' with ASCII flag correctly"):
    val args   = List("eval", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", "w", "KQkq", "-", "0", "1")
    val result = Commands.rootCommand.parse(args)
    assert(result.isRight)
    val cmd = result.toOption.get
    assert(cmd.isInstanceOf[EvalCommand])
    val evalCmd = cmd.asInstanceOf[EvalCommand]
    assertEquals(evalCmd.fen, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    assert(!evalCmd.unicode)

  test("Commands.rootCommand parses 'eval --unicode' correctly"):
    val args   = List("eval", "--unicode", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", "w", "KQkq", "-", "0", "1")
    val result = Commands.rootCommand.parse(args)
    assert(result.isRight)
    val evalCmd = result.toOption.get.asInstanceOf[EvalCommand]
    assert(evalCmd.unicode)

  test("Commands.rootCommand rejects unknown subcommand"):
    val args   = List("unknown")
    val result = Commands.rootCommand.parse(args)
    assert(result.isLeft)

  test("Commands.execute runs EvalCommand correctly (smoke test)"):
    // We only smoke-test that execute doesn't crash since it prints to stdout
    val cmd = EvalCommand("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", unicode = false)
    Commands.execute(cmd)

  test("Commands.execute handles invalid FEN gracefully in EvalCommand"):
    val cmd = EvalCommand("invalid_fen", unicode = false)
    Commands.execute(cmd)
