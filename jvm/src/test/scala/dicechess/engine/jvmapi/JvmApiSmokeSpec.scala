package dicechess.engine.jvmapi

import munit.FunSuite

/** Runs [[JvmApiSmokeCheck]] — a plain-Java exercise of [[JvmApi]] — under the ordinary test runner. MUnit only
  * discovers Scala test classes, so this one-line wrapper is what makes the Java-source check actually execute; the
  * assertions themselves live in the Java class, where the Scala compiler can't paper over a Java-unfriendly signature.
  */
class JvmApiSmokeSpec extends FunSuite:

  test("JvmApi is callable from Java without reflection") {
    JvmApiSmokeCheck.run()
  }

  test("a Java autonomous bot can play a full game through JvmApi alone") {
    JvmApiSelfPlayCheck.run()
  }
