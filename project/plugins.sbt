addSbtPlugin("org.scoverage"      % "sbt-scoverage" % "2.4.4")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"  % "2.6.2")
addSbtPlugin("ch.epfl.scala"      % "sbt-scalafix"  % "0.14.7")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"   % "1.22.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"       % "0.4.8")
// sbt-ci-release bundles sbt-pgp (for signing) and uses sbt's native Central Portal support.
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.0")
// Note: sbt-projectmatrix is built into sbt 2 core (see build.sbt).
