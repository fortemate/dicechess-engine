package dicechess.engine

/** JVM-language-agnostic facade over the engine, for callers not written in Scala.
  *
  * This is the JVM row's counterpart to the JS row's `EngineFacade`: the surface a Java or Kotlin consumer binds to
  * instead of the Scala API, which leans on `Either` returns, extension methods, and opaque types that do not survive
  * the language boundary intact. Everything outside this package is internal to such a consumer, whatever its Scala
  * visibility says.
  *
  * ## Key Components
  *
  *   - [[dicechess.engine.jvmapi.JvmApi]]: DFEN parsing, active color, and legal-turn enumeration, exposed through
  *     `java.util` types and primitives only.
  *   - `JvmApi.Turn`: one legal turn as its UCI micro-moves plus the position they lead to.
  *
  * The facade's Java-callability is pinned by a Java-source test (`jvm/src/test/java/`) — signatures can stop being
  * reachable from Java without any Scala-side compiler error.
  */
package object jvmapi
