package dicechess.engine.bench

/** Minimal JSON value model, encoder, and decoder backing the arena runners' optional `--json` machine-readable report
  * (#521).
  *
  * Hand-rolled rather than pulling in a JSON library: the engine has no JSON dependency anywhere else, and the report
  * shapes here are small and fully under this module's control. [[parse]] exists only so `BotMatchRunnerSpec` can
  * verify the emitted schema — production code exclusively writes JSON, never reads it.
  */
enum Json derives CanEqual:
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

object Json:
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
    case JArr(items)  =>
      sb.append('[')
      for (item, i) <- items.zipWithIndex do
        if i > 0 then sb.append(',')
        appendTo(sb, item)
      sb.append(']')
    case JObj(fields) =>
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

  /** Parses `input` as JSON. Recursive-descent, no external dependency; returns `Left` with a position-tagged message
    * on malformed input instead of throwing.
    */
  def parse(input: String): Either[String, Json] =
    parseValue(input, skipWs(input, 0)).flatMap { case (value, pos) =>
      val after = skipWs(input, pos)
      if after == input.length then Right(value) else Left(s"trailing content at $after")
    }

  private def skipWs(s: String, from: Int): Int =
    var i = from
    while i < s.length && s.charAt(i).isWhitespace do i += 1
    i

  private def parseValue(s: String, i: Int): Either[String, (Json, Int)] =
    if i >= s.length then Left(s"unexpected end of input at $i")
    else
      s.charAt(i) match
        case '{'                        => parseObject(s, i)
        case '['                        => parseArray(s, i)
        case '"'                        => parseString(s, i).map { case (str, p) => (JStr(str), p) }
        case 't'                        => parseKeyword(s, i, "true", JBool(true))
        case 'f'                        => parseKeyword(s, i, "false", JBool(false))
        case 'n'                        => parseKeyword(s, i, "null", JNull)
        case c if c == '-' || c.isDigit => parseNumber(s, i)
        case c                          => Left(s"unexpected character '$c' at $i")

  private def parseKeyword(s: String, i: Int, keyword: String, value: Json): Either[String, (Json, Int)] =
    if s.regionMatches(i, keyword, 0, keyword.length) then Right((value, i + keyword.length))
    else Left(s"expected '$keyword' at $i")

  private def parseObject(s: String, start: Int): Either[String, (Json, Int)] =
    val afterBrace = skipWs(s, start + 1)
    if afterBrace < s.length && s.charAt(afterBrace) == '}' then Right((JObj(Nil), afterBrace + 1))
    else
      def loop(i: Int, acc: List[(String, Json)]): Either[String, (Json, Int)] =
        if i >= s.length || s.charAt(i) != '"' then Left(s"expected object key (string) at $i")
        else
          parseString(s, i).flatMap { case (key, afterKey) =>
            val afterColonWs = skipWs(s, afterKey)
            if afterColonWs >= s.length || s.charAt(afterColonWs) != ':' then Left(s"expected ':' at $afterColonWs")
            else
              val valueStart = skipWs(s, afterColonWs + 1)
              parseValue(s, valueStart).flatMap { case (value, afterValue) =>
                val next    = acc :+ (key -> value)
                val afterWs = skipWs(s, afterValue)
                if afterWs >= s.length then Left(s"unexpected end of input at $afterWs")
                else
                  s.charAt(afterWs) match
                    case ',' => loop(skipWs(s, afterWs + 1), next)
                    case '}' => Right((JObj(next), afterWs + 1))
                    case c   => Left(s"expected ',' or '}' at $afterWs, found '$c'")
              }
          }
      loop(afterBrace, Nil)

  private def parseArray(s: String, start: Int): Either[String, (Json, Int)] =
    val afterBracket = skipWs(s, start + 1)
    if afterBracket < s.length && s.charAt(afterBracket) == ']' then Right((JArr(Nil), afterBracket + 1))
    else
      def loop(i: Int, acc: List[Json]): Either[String, (Json, Int)] =
        parseValue(s, i).flatMap { case (value, afterValue) =>
          val next    = acc :+ value
          val afterWs = skipWs(s, afterValue)
          if afterWs >= s.length then Left(s"unexpected end of input at $afterWs")
          else
            s.charAt(afterWs) match
              case ',' => loop(skipWs(s, afterWs + 1), next)
              case ']' => Right((JArr(next), afterWs + 1))
              case c   => Left(s"expected ',' or ']' at $afterWs, found '$c'")
        }
      loop(afterBracket, Nil)

  private def parseString(s: String, start: Int): Either[String, (String, Int)] =
    val sb                                          = new StringBuilder()
    def loop(i: Int): Either[String, (String, Int)] =
      if i >= s.length then Left(s"unterminated string starting at $start")
      else
        s.charAt(i) match
          case '"'  => Right((sb.toString, i + 1))
          case '\\' =>
            if i + 1 >= s.length then Left(s"unterminated escape at $i")
            else
              s.charAt(i + 1) match
                case '"'  => sb.append('"'); loop(i + 2)
                case '\\' => sb.append('\\'); loop(i + 2)
                case '/'  => sb.append('/'); loop(i + 2)
                case 'n'  => sb.append('\n'); loop(i + 2)
                case 'r'  => sb.append('\r'); loop(i + 2)
                case 't'  => sb.append('\t'); loop(i + 2)
                case 'b'  => sb.append('\b'); loop(i + 2)
                case 'f'  => sb.append('\f'); loop(i + 2)
                case 'u'  =>
                  if i + 6 > s.length then Left(s"incomplete unicode escape at $i")
                  else
                    val hex = s.substring(i + 2, i + 6)
                    parseHex4(hex) match
                      case Some(code) => sb.append(code.toChar); loop(i + 6)
                      case None       => Left(s"invalid unicode escape '$hex' at $i")
                case c => Left(s"invalid escape character '$c' at $i")
          case c =>
            sb.append(c)
            loop(i + 1)
    loop(start + 1)

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
    var i = start
    if i < s.length && s.charAt(i) == '-' then i += 1
    val digitsStart = i
    while i < s.length && s.charAt(i).isDigit do i += 1
    if i == digitsStart then Left(s"invalid number at $start")
    else
      var isFloat = false
      if i < s.length && s.charAt(i) == '.' then
        isFloat = true
        i += 1
        while i < s.length && s.charAt(i).isDigit do i += 1
      if i < s.length && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
        isFloat = true
        i += 1
        if i < s.length && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
        while i < s.length && s.charAt(i).isDigit do i += 1
      val text = s.substring(start, i)
      if isFloat then text.toDoubleOption.map(d => (JNum(d), i)).toRight(s"invalid number '$text' at $start")
      else text.toLongOption.map(l => (JInt(l), i)).toRight(s"invalid number '$text' at $start")
