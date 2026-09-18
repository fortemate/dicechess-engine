// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.json

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

  test("parse: a long document costs heap, not stack — the container and string loops are iterative") {
    // The recursive version overflowed the stack a few thousand elements in, and an Error is not something an
    // Either-returning parser can hand back. These sizes are far past where it used to die.
    val array = Json.parse("[" + List.fill(20000)("1").mkString(",") + "]")
    assertEquals(array.map { case Json.JArr(items) => items.length; case _ => -1 }, Right(20000))

    val fields = (1 to 20000).map(i => s""""k$i":$i""").mkString(",")
    val obj    = Json.parse("{" + fields + "}")
    assertEquals(obj.map { case Json.JObj(entries) => entries.length; case _ => -1 }, Right(20000))

    val escaped = Json.parse("\"" + ("\\n" * 20000) + "\"")
    assertEquals(escaped.map { case Json.JStr(value) => value.length; case _ => -1 }, Right(20000))
  }

  test("parse: nesting past the depth limit is a rejection, not a StackOverflowError") {
    val deep = Json.parse("[" * 5000 + "]" * 5000)
    assert(deep.isLeft, "deeply nested input must be refused")
    assert(deep.left.exists(_.startsWith("maximum nesting depth exceeded")), deep.toString)

    val deepObjects = Json.parse("""{"a":""" * 5000 + "1" + "}" * 5000)
    assert(deepObjects.left.exists(_.startsWith("maximum nesting depth exceeded")), deepObjects.toString)

    // The limit is generous enough for every document this repository actually reads.
    val ordinary = Json.parse("[" * 32 + "1" + "]" * 32)
    assert(ordinary.isRight, ordinary.toString)
  }
