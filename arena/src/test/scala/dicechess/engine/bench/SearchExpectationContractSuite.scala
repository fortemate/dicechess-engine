// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.json.Json
import java.lang.reflect.Method
import munit.FunSuite

class SearchExpectationContractSuite extends FunSuite:
  private val context = "scenarios[4]"

  private val parser: Method =
    val methods = Option(SearchFixtureCatalog.getClass.getDeclaredMethods).getOrElse(fail("Missing declared methods"))
    val matches = methods.filter { method =>
      Option(method.getName).contains("parseExpectation") &&
      method.getParameterTypes.map(_.getName).toList == List("scala.collection.immutable.List", "java.lang.String")
    }
    assertEquals(matches.length, 1)
    val method = matches.head
    assert(java.lang.reflect.Modifier.isPrivate(method.getModifiers))
    method.setAccessible(true)
    method

  private def parse(fields: List[(String, Json)], at: String = context): Either[String, SearchExpectation] =
    Option(parser.invoke(SearchFixtureCatalog, fields, at))
      .getOrElse(fail("parseExpectation returned null"))
      .asInstanceOf[Either[String, SearchExpectation]]

  private def turns(value: Json): List[(String, Json)] = List("expectedTurns" -> value)

  private def error(fields: List[(String, Json)], message: String, at: String = context): Unit =
    assertEquals(parse(fields, at), Left(message))

  private val nonBooleanValues: List[Json] = List(
    Json.JNull,
    Json.JInt(1L),
    Json.JNum(1.5),
    Json.JStr("true"),
    Json.JArr(Nil),
    Json.JObj(Nil)
  )

  test("expectPass requires exclusive keys and accepts only true") {
    val cardinalityError = s"$context must define exactly one of expectedTurns or expectPass"
    error(Nil, cardinalityError)
    error(
      List("expectedTurns" -> Json.JStr("invalid"), "expectPass" -> Json.JStr("invalid")),
      cardinalityError
    )
    error(
      List("expectPass" -> Json.JStr("invalid"), "expectedTurns" -> Json.JStr("invalid")),
      cardinalityError
    )

    val metadata = List("id" -> Json.JStr("metadata-only"), "dfen" -> Json.JStr("ignored here"))
    assertEquals(parse(metadata :+ ("expectPass" -> Json.JBool(true))), Right(SearchExpectation.Pass))
    error(metadata :+ ("expectPass" -> Json.JBool(false)), s"$context.expectPass must be true when present")
    nonBooleanValues.foreach { value =>
      error(metadata :+ ("expectPass" -> value), s"$context.expectPass must be a boolean")
    }
  }

  test("expectedTurns must be a nonempty array of nonempty turn arrays") {
    val nonArrays = List(
      Json.JNull,
      Json.JBool(true),
      Json.JInt(1L),
      Json.JNum(2.5),
      Json.JStr("turn"),
      Json.JObj(Nil)
    )
    nonArrays.foreach(value => error(turns(value), s"$context.expectedTurns must be an array"))
    error(turns(Json.JArr(Nil)), s"$context.expectedTurns must not be empty")

    nonArrays.foreach { value =>
      error(
        turns(Json.JArr(List(value))),
        s"$context.expectedTurns[0] must be an array"
      )
    }
    error(turns(Json.JArr(List(Json.JArr(Nil)))), s"$context.expectedTurns[0] must not be empty")

    val nonStrings = List(Json.JNull, Json.JBool(false), Json.JInt(3L), Json.JNum(4.5), Json.JArr(Nil), Json.JObj(Nil))
    nonStrings.foreach { value =>
      error(
        turns(Json.JArr(List(Json.JArr(List(value))))),
        s"$context.expectedTurns[0][0] must be a string"
      )
    }
    error(
      turns(Json.JArr(List(Json.JArr(List(Json.JStr("")))))),
      s"$context.expectedTurns[0][0] must not be empty"
    )
  }

  test("malformed turns and moves report the first indexed error with context") {
    val firstTurnBad = turns(Json.JArr(List(Json.JInt(1L), Json.JStr("later bad turn"))))
    error(firstTurnBad, "fixtures[9].expectedTurns[0] must be an array", "fixtures[9]")
    val secondTurnBad = turns(Json.JArr(List(Json.JArr(List(Json.JStr("valid"))), Json.JBool(true))))
    error(secondTurnBad, "fixtures[9].expectedTurns[1] must be an array", "fixtures[9]")

    val firstMoveBad = turns(
      Json.JArr(List(Json.JArr(List(Json.JInt(1L), Json.JStr(""), Json.JBool(false)))))
    )
    error(firstMoveBad, "fixtures[9].expectedTurns[0][0] must be a string", "fixtures[9]")
    val secondMoveBad = turns(Json.JArr(List(Json.JArr(List(Json.JStr("valid"), Json.JBool(false))))))
    error(secondMoveBad, "fixtures[9].expectedTurns[0][1] must be a string", "fixtures[9]")

    val emptyEarlierTurn = turns(Json.JArr(List(Json.JArr(Nil), Json.JStr("later bad turn"))))
    error(emptyEarlierTurn, s"$context.expectedTurns[0] must not be empty")
  }

  test("valid turn order and move order are preserved; only duplicate whole turns are rejected") {
    val allowed = Json.JArr(
      List(
        Json.JArr(List(Json.JStr("b2b3"), Json.JStr("a1a2"))),
        Json.JArr(List(Json.JStr("c3c4")))
      )
    )
    val metadata = List("id" -> Json.JStr("catalog-helper"), "dfen" -> Json.JStr("ignored by this parser"))
    assertEquals(
      parse(metadata ++ turns(allowed)),
      Right(SearchExpectation.Turns(List(List("b2b3", "a1a2"), List("c3c4"))))
    )

    val whitespace = turns(Json.JArr(List(Json.JArr(List(Json.JStr(" "))))))
    assertEquals(parse(whitespace), Right(SearchExpectation.Turns(List(List(" ")))))

    val repeatedMove = turns(Json.JArr(List(Json.JArr(List(Json.JStr("a1a2"), Json.JStr("a1a2"))))))
    assertEquals(parse(repeatedMove), Right(SearchExpectation.Turns(List(List("a1a2", "a1a2")))))

    val reorderedTurns = turns(
      Json.JArr(
        List(
          Json.JArr(List(Json.JStr("a1a2"), Json.JStr("b2b3"))),
          Json.JArr(List(Json.JStr("b2b3"), Json.JStr("a1a2")))
        )
      )
    )
    assertEquals(
      parse(reorderedTurns),
      Right(SearchExpectation.Turns(List(List("a1a2", "b2b3"), List("b2b3", "a1a2"))))
    )

    val duplicate = turns(
      Json.JArr(
        List(
          Json.JArr(List(Json.JStr("a1a2"), Json.JStr("b2b3"))),
          Json.JArr(List(Json.JStr("a1a2"), Json.JStr("b2b3")))
        )
      )
    )
    error(duplicate, s"$context.expectedTurns must not contain duplicates")
  }

  test("repeated parsing leaves the immutable input representation stable") {
    val fields = List(
      "id"            -> Json.JStr("scenario-meta"),
      "dfen"          -> Json.JStr("opaque dfen metadata"),
      "expectedTurns" -> Json.JArr(List(Json.JArr(List(Json.JStr("e2e4"), Json.JStr("g1f3")))))
    )
    val before = Json.render(Json.JObj(fields))
    val first  = parse(fields, "custom.context")
    val second = parse(fields, "custom.context")
    assertEquals(first, Right(SearchExpectation.Turns(List(List("e2e4", "g1f3")))))
    assertEquals(second, first)
    assertEquals(Json.render(Json.JObj(fields)), before)
  }
