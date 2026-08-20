package dicechess.engine.bench

import munit.FunSuite

class JsonSpec extends FunSuite:

  test("render: produces compact JSON for every variant, escaping strings") {
    val json = Json.obj(
      "str"  -> Json.str("a \"quoted\"\nline\\end"),
      "int"  -> Json.int(42L),
      "num"  -> Json.num(66.7),
      "bool" -> Json.bool(true),
      "nil"  -> Json.JNull,
      "arr"  -> Json.arr(Json.int(1), Json.int(2), Json.str("three"))
    )
    val rendered = Json.render(json)
    assertEquals(
      rendered,
      "{\"str\":\"a \\\"quoted\\\"\\nline\\\\end\",\"int\":42,\"num\":66.7,\"bool\":true,\"nil\":null,\"arr\":[1,2,\"three\"]}"
    )
  }

  test("parse: round-trips every variant back to an equal Json value") {
    val json = Json.obj(
      "nested" -> Json.obj("a" -> Json.int(1), "b" -> Json.arr(Json.bool(false), Json.JNull)),
      "pi"     -> Json.num(3.14),
      "name"   -> Json.str("dice \"chess\"")
    )
    val rendered = Json.render(json)
    assertEquals(Json.parse(rendered), Right(json))
  }

  test("parse: whitespace between tokens is insignificant") {
    assertEquals(Json.parse(" { \"a\" : [ 1 , 2 ] } "), Right(Json.obj("a" -> Json.arr(Json.int(1), Json.int(2)))))
  }

  test("parse: rejects malformed input instead of throwing") {
    assert(Json.parse("").isLeft)
    assert(Json.parse("{").isLeft)
    assert(Json.parse("{\"a\":}").isLeft)
    assert(Json.parse("[1,2").isLeft)
    assert(Json.parse("truex").isLeft) // trailing content after a complete value
    assert(Json.parse("\"unterminated").isLeft)
  }

  test("parse: distinguishes whole numbers (JInt) from fractional/exponent numbers (JNum)") {
    assertEquals(Json.parse("42"), Right(Json.int(42L)))
    assertEquals(Json.parse("-7"), Right(Json.int(-7L)))
    assertEquals(Json.parse("42.0"), Right(Json.num(42.0)))
    assertEquals(Json.parse("1e3"), Right(Json.num(1000.0)))
  }

  test("parse: rejects malformed numbers correctly across components") {
    // Isolated minus without digits
    assertEquals(Json.parse("-"), Left("invalid number at 0"))
    // Incomplete exponent
    assertEquals(Json.parse("42e"), Left("invalid number '42e' at 0"))
    assertEquals(Json.parse("42e+"), Left("invalid number '42e+' at 0"))
  }

  test("parse: \\u escapes are decoded as hexadecimal, not decimal") {
    // \u0041 is code point 0x41 = 'A'; decimal parsing would wrongly decode it as code point 41 ('(' is 40, ')' 41).
    assertEquals(Json.parse("\"\\u0041\""), Right(Json.str("A")))
    // Escapes whose digits include a-f are the case decimal parsing can't even represent.
    assertEquals(Json.parse("\"\\u00Ff\""), Right(Json.str(0xff.toChar.toString)))
    assertEquals(Json.parse("\"\\uzzzz\""), Left("invalid unicode escape 'zzzz' at 1"))
  }

  test("parse: rejects malformed escapes") {
    // Dangling backslash
    assertEquals(Json.parse("\"\\"), Left("unterminated escape at 1"))
    // Unknown escape character
    assertEquals(Json.parse("\"\\q\""), Left("invalid escape character 'q' at 1"))
    // Incomplete unicode escape (less than 4 characters left in string after \u)
    assertEquals(Json.parse("\"\\u004"), Left("incomplete unicode escape at 1"))
  }

  test("render: a control character below 0x20 round-trips through its \\u escape") {
    val withBell = Json.str("a" + 0x01.toChar + "b")
    assertEquals(Json.render(withBell), "\"a\\u0001b\"")
    assertEquals(Json.parse(Json.render(withBell)), Right(withBell))
  }

  test("render: NaN and Infinity fall back to null instead of emitting invalid JSON tokens") {
    assertEquals(Json.render(Json.num(Double.NaN)), "null")
    assertEquals(Json.render(Json.num(Double.PositiveInfinity)), "null")
    assertEquals(Json.render(Json.num(Double.NegativeInfinity)), "null")
  }

  test("field/asStr/asNum/asBool/asArr: navigate a parsed document") {
    val json = Json
      .parse("""{"kind":"x","seed":42,"rate":66.7,"ok":true,"items":[1,2,3]}""")
      .getOrElse(fail("expected valid JSON"))
    assertEquals(json.field("kind").flatMap(_.asStr), Some("x"))
    assertEquals(json.field("seed").flatMap(_.asNum), Some(42.0)) // JInt widens through asNum
    assertEquals(json.field("rate").flatMap(_.asNum), Some(66.7))
    assertEquals(json.field("ok").flatMap(_.asBool), Some(true))
    assertEquals(json.field("items").flatMap(_.asArr).map(_.size), Some(3))
    assertEquals(json.field("missing"), None)
  }
