// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.json

/** Minimal JSON value model, encoder, and decoder — the one JSON implementation in this repository (#521).
  *
  * Hand-rolled rather than pulling in a JSON library: the engine deliberately has no JSON dependency, and every shape
  * read or written here is small and fully under this repository's control — the arena runners' optional `--json`
  * machine-readable reports and their fixture catalogs, and the ONNX model manifests of
  * [[dicechess.engine.model.ModelManifest]]. [[Json.parse]] parses JSON syntax; each consumer validates its own
  * versioned schema on top of the parsed value.
  *
  * `private[engine]` on purpose: the published jar carries it as an internal utility, not as public API, so the engine
  * never has to keep a JSON model binary compatible for downstream callers.
  */
private[engine] enum Json derives CanEqual:
  case JNull
  case JBool(value: Boolean)
  case JInt(value: Long)
  case JNum(value: Double)
  case JStr(value: String)
  case JArr(items: List[Json])
  case JObj(fields: List[(String, Json)])

  /** Looks up a field by name; `None` for any variant other than [[Json.JObj]] or a missing key. */
  def field(name: String): Option[Json] = this match
    case Json.JObj(fields) => fields.collectFirst { case (k, v) if k == name => v }
    case _                 => None

  def asStr: Option[String] = this match
    case Json.JStr(value) => Some(value)
    case _                => None

  /** Widens [[Json.JInt]] as well, since a whole-number field can round-trip through either variant. */
  def asNum: Option[Double] = this match
    case Json.JNum(value) => Some(value)
    case Json.JInt(value) => Some(value.toDouble)
    case _                => None

  def asBool: Option[Boolean] = this match
    case Json.JBool(value) => Some(value)
    case _                 => None

  def asArr: Option[List[Json]] = this match
    case Json.JArr(items) => Some(items)
    case _                => None

private[engine] object Json:
  def obj(fields: (String, Json)*): Json = JObj(fields.toList)
  def arr(items: Json*): Json            = JArr(items.toList)
  def str(value: String): Json           = JStr(value)
  def int(value: Long): Json             = JInt(value)
  def num(value: Double): Json           = JNum(value)
  def bool(value: Boolean): Json         = JBool(value)

  /** Renders compact JSON text — this format is for automation, not human reading, so no pretty-printing. */
  def render(json: Json): String =
    val sb = new StringBuilder()
    appendTo(sb, json)
    sb.toString

  private def appendTo(sb: StringBuilder, json: Json): Unit = json match
    case JNull        => sb.append("null")
    case JBool(value) => sb.append(if value then "true" else "false")
    case JInt(value)  => sb.append(value)
    case JNum(value)  => sb.append(if value.isNaN || value.isInfinite then "null" else value.toString)
    case JStr(value)  => appendString(sb, value)
    case JArr(items)  => appendArray(sb, items)
    case JObj(fields) => appendObject(sb, fields)

  private def appendArray(sb: StringBuilder, items: List[Json]): Unit =
    sb.append('[')
    for (item, i) <- items.zipWithIndex do
      if i > 0 then sb.append(',')
      appendTo(sb, item)
    sb.append(']')

  private def appendObject(sb: StringBuilder, fields: List[(String, Json)]): Unit =
    sb.append('{')
    for ((key, value), i) <- fields.zipWithIndex do
      if i > 0 then sb.append(',')
      appendString(sb, key)
      sb.append(':')
      appendTo(sb, value)
    sb.append('}')

  private def appendString(sb: StringBuilder, value: String): Unit =
    sb.append('"')
    for ch <- value do
      ch match
        case '"'                 => sb.append("\\\"")
        case '\\'                => sb.append("\\\\")
        case '\n'                => sb.append("\\n")
        case '\r'                => sb.append("\\r")
        case '\t'                => sb.append("\\t")
        case c if c.toInt < 0x20 =>
          val hex = Integer.toHexString(c.toInt)
          sb.append("\\u").append("0" * (4 - hex.length)).append(hex)
        case c => sb.append(c)
    sb.append('"')

  /** Nesting limit for containers.
    *
    * JSON's grammar is recursive, so a container's parser must call the value parser and nesting costs stack frames.
    * Unbounded, a deeply nested document ends in a `StackOverflowError` — an `Error`, which no `Either` in this file
    * can carry back to the caller, so the "returns Left instead of throwing" promise would quietly not hold. Every
    * document this repository reads (arena reports, fixture catalogs, model manifests) nests a handful of levels.
    *
    * Element *count* is not bounded and does not need to be: the object, array and string loops below are iterative, so
    * a long document costs heap, not stack.
    */
  private val MaxDepth = 64

  /** Parses `input` as JSON. Recursive-descent, no external dependency; returns `Left` with a position-tagged message
    * on malformed input — including input nested deeper than [[MaxDepth]] — instead of throwing.
    */
  def parse(input: String): Either[String, Json] =
    parseValue(input, skipWs(input, 0), 0).flatMap { case (value, pos) =>
      val after = skipWs(input, pos)
      if after == input.length then Right(value) else Left(s"trailing content at $after")
    }

  private def skipWs(s: String, from: Int): Int =
    var i = from
    while i < s.length && s.charAt(i).isWhitespace do i += 1
    i

  private def parseValue(s: String, i: Int, depth: Int): Either[String, (Json, Int)] =
    if i >= s.length then Left(s"unexpected end of input at $i")
    else
      s.charAt(i) match
        case '{'                        => parseObject(s, i, depth + 1)
        case '['                        => parseArray(s, i, depth + 1)
        case '"'                        => parseString(s, i).map { case (str, p) => (JStr(str), p) }
        case 't'                        => parseKeyword(s, i, "true", JBool(true))
        case 'f'                        => parseKeyword(s, i, "false", JBool(false))
        case 'n'                        => parseKeyword(s, i, "null", JNull)
        case c if c == '-' || c.isDigit => parseNumber(s, i)
        case c                          => Left(s"unexpected character '$c' at $i")

  private def parseKeyword(s: String, i: Int, keyword: String, value: Json): Either[String, (Json, Int)] =
    if s.regionMatches(i, keyword, 0, keyword.length) then Right((value, i + keyword.length))
    else Left(s"expected '$keyword' at $i")

  /** Object body, one field per iteration.
    *
    * Iterative, and accumulating through a builder rather than `acc :+ field`: the recursive version cost one stack
    * frame and one full list copy per field, so a large object was quadratic on the way to overflowing the stack.
    */
  private def parseObject(s: String, start: Int, depth: Int): Either[String, (Json, Int)] =
    if depth > MaxDepth then Left(s"maximum nesting depth exceeded at $start")
    else
      val afterBrace = skipWs(s, start + 1)
      if afterBrace < s.length && s.charAt(afterBrace) == '}' then Right((JObj(Nil), afterBrace + 1))
      else
        val fields = List.newBuilder[(String, Json)]
        var i      = afterBrace
        var result = Option.empty[Either[String, (Json, Int)]]
        while result.isEmpty do
          parseObjectField(s, i, depth) match
            case Left(error)                     => result = Some(Left(error))
            case Right((key, value, afterValue)) =>
              fields.addOne(key -> value)
              val afterWs = skipWs(s, afterValue)
              if afterWs >= s.length then result = Some(Left(s"unexpected end of input at $afterWs"))
              else
                s.charAt(afterWs) match
                  case ',' => i = skipWs(s, afterWs + 1)
                  case '}' => result = Some(Right((JObj(fields.result()), afterWs + 1)))
                  case c   => result = Some(Left(s"expected ',' or '}' at $afterWs, found '$c'"))
        result.get

  private def parseObjectField(s: String, i: Int, depth: Int): Either[String, (String, Json, Int)] =
    if i >= s.length || s.charAt(i) != '"' then Left(s"expected object key (string) at $i")
    else
      parseString(s, i).flatMap { case (key, afterKey) =>
        val afterColonWs = skipWs(s, afterKey)
        if afterColonWs >= s.length || s.charAt(afterColonWs) != ':' then Left(s"expected ':' at $afterColonWs")
        else
          val valueStart = skipWs(s, afterColonWs + 1)
          parseValue(s, valueStart, depth).map { case (value, afterValue) =>
            (key, value, afterValue)
          }
      }

  /** Array body, one element per iteration — iterative and builder-accumulated for the reason [[parseObject]] gives. */
  private def parseArray(s: String, start: Int, depth: Int): Either[String, (Json, Int)] =
    if depth > MaxDepth then Left(s"maximum nesting depth exceeded at $start")
    else
      val afterBracket = skipWs(s, start + 1)
      if afterBracket < s.length && s.charAt(afterBracket) == ']' then Right((JArr(Nil), afterBracket + 1))
      else
        val items  = List.newBuilder[Json]
        var i      = afterBracket
        var result = Option.empty[Either[String, (Json, Int)]]
        while result.isEmpty do
          parseValue(s, i, depth) match
            case Left(error)                => result = Some(Left(error))
            case Right((value, afterValue)) =>
              items.addOne(value)
              val afterWs = skipWs(s, afterValue)
              if afterWs >= s.length then result = Some(Left(s"unexpected end of input at $afterWs"))
              else
                s.charAt(afterWs) match
                  case ',' => i = skipWs(s, afterWs + 1)
                  case ']' => result = Some(Right((JArr(items.result()), afterWs + 1)))
                  case c   => result = Some(Left(s"expected ',' or ']' at $afterWs, found '$c'"))
        result.get

  /** String body, one character per iteration — a long run of escapes recursed once per escape before. */
  private def parseString(s: String, start: Int): Either[String, (String, Int)] =
    val sb     = new StringBuilder()
    var i      = start + 1
    var result = Option.empty[Either[String, (String, Int)]]
    while result.isEmpty do
      if i >= s.length then result = Some(Left(s"unterminated string starting at $start"))
      else
        s.charAt(i) match
          case '"'  => result = Some(Right((sb.toString, i + 1)))
          case '\\' =>
            parseEscape(s, i) match
              case Left(error)        => result = Some(Left(error))
              case Right((ch, nextI)) =>
                sb.append(ch)
                i = nextI
          case c =>
            sb.append(c)
            i += 1
    result.get

  private def parseEscape(s: String, i: Int): Either[String, (Char, Int)] =
    if i + 1 >= s.length then Left(s"unterminated escape at $i")
    else
      s.charAt(i + 1) match
        case '"'  => Right(('"', i + 2))
        case '\\' => Right(('\\', i + 2))
        case '/'  => Right(('/', i + 2))
        case 'n'  => Right(('\n', i + 2))
        case 'r'  => Right(('\r', i + 2))
        case 't'  => Right(('\t', i + 2))
        case 'b'  => Right(('\b', i + 2))
        case 'f'  => Right(('\f', i + 2))
        case 'u'  =>
          if i + 6 > s.length then Left(s"incomplete unicode escape at $i")
          else
            val hex = s.substring(i + 2, i + 6)
            parseHex4(hex) match
              case Some(code) => Right((code.toChar, i + 6))
              case None       => Left(s"invalid unicode escape '$hex' at $i")
        case c => Left(s"invalid escape character '$c' at $i")

  /** Parses a 4-digit `\u` escape as hexadecimal (`Character.digit` reports invalid digits as `-1` instead of throwing,
    * unlike `Integer.parseInt`/`String.toIntOption`, which default to radix 10 and would silently misdecode e.g. `A` as
    * code point 41 instead of `0x41`).
    */
  private def parseHex4(hex: String): Option[Int] =
    if hex.length != 4 then None
    else
      val digits = hex.map(c => Character.digit(c, 16))
      if digits.exists(_ < 0) then None else Some(digits.foldLeft(0)((acc, d) => acc * 16 + d))

  private def parseNumber(s: String, start: Int): Either[String, (Json, Int)] =
    val afterMinus = if start < s.length && s.charAt(start) == '-' then start + 1 else start
    val afterWhole = consumeDigits(s, afterMinus)
    if afterWhole == afterMinus then Left(s"invalid number at $start")
    else
      val (afterFraction, hasFraction) = consumeFraction(s, afterWhole)
      val (afterExponent, hasExponent) = consumeExponent(s, afterFraction)
      val text                         = s.substring(start, afterExponent)
      if hasFraction || hasExponent then
        text.toDoubleOption.map(d => (JNum(d), afterExponent)).toRight(s"invalid number '$text' at $start")
      else text.toLongOption.map(l => (JInt(l), afterExponent)).toRight(s"invalid number '$text' at $start")

  private def consumeDigits(s: String, start: Int): Int =
    var i = start
    while i < s.length && s.charAt(i).isDigit do i += 1
    i

  private def consumeFraction(s: String, start: Int): (Int, Boolean) =
    if start < s.length && s.charAt(start) == '.' then (consumeDigits(s, start + 1), true)
    else (start, false)

  private def consumeExponent(s: String, start: Int): (Int, Boolean) =
    if start < s.length && (s.charAt(start) == 'e' || s.charAt(start) == 'E') then
      val afterE    = start + 1
      val afterSign =
        if afterE < s.length && (s.charAt(afterE) == '+' || s.charAt(afterE) == '-') then afterE + 1 else afterE
      (consumeDigits(s, afterSign), true)
    else (start, false)
