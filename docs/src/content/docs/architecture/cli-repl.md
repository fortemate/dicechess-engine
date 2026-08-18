---
title: Interactive CLI REPL
description: Documentation for the Dice Chess Engine interactive CLI.
---

# Interactive CLI REPL

The Dice Chess Engine provides an interactive command-line interface (CLI) to easily evaluate positions and experiment with the engine's capabilities. It uses JLine 3 for a robust terminal experience with command history and line editing, and Decline for strict command-line argument parsing.

## Starting the CLI

To launch the interactive CLI from the project root, run:

```bash
mise run run
```

Or, if you have `sbt` installed directly:

```bash
sbt rootJVM/run
```

Once started, you will see the `dicechess>` prompt:

```text
==================================================
🎲♟️ Dice Chess Engine Interactive CLI
Type 'help' for commands, or 'exit' to quit.
==================================================
dicechess> 
```

## Available Commands

### `eval`

Evaluates a given position (in FEN format) and prints an ASCII (or Unicode) representation of the board along with King and Queen Capture Probabilities.

**Usage:**

```bash
eval [--unicode] <FEN>
```

**Example (Starting Position):**

```bash
dicechess> eval rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1
```

**Example (Unicode Output):**

```bash
dicechess> eval --unicode rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1
```

### General Commands

- `help`: Shows a list of available commands and their descriptions.
- `exit` (or `quit`): Exits the interactive shell.

## Architecture

The interactive REPL is implemented in `Main.scala`. It initializes a `Terminal` and `LineReader` via JLine 3 and continuously loops, reading user input. 

When a line is read, it's tokenized using JLine's built-in parser (which properly respects quoted strings for arguments like custom FENs) and then passed to `Decline`, which manages argument mapping, validation, and help text generation.

Code structure:
- `dicechess.engine.Main`: The REPL loop.
- `dicechess.engine.cli.Commands`: Decline parsers and command definitions.
- `dicechess.engine.cli.BoardPrinter`: ASCII and Unicode board rendering logic.
