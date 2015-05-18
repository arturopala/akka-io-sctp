
name := "akka-io-sctp"

version := "0.7.1"

organization := "me.arturopala"

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

startYear := Some(2015)

description := "Akka I/O driver for SCTP protocol based on Oracle JDK 7/8 SCTP support"

scalaVersion := "2.11.6"

developers := List(Developer("arturopala","Artur Opala","opala.artur@gmail.com",url("https://pl.linkedin.com/in/arturopala")))

scmInfo := Some(
  ScmInfo(
    url("https://github.com/arturopala/akka-io-sctp"),
    "git@github.com:arturopala/akka-io-sctp.git"
  )
)

val akkaVersion = "2.4-SNAPSHOT"

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"
 
libraryDependencies ++= Seq(
	"com.typesafe.akka" %% "akka-actor" % akkaVersion,
	"com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
	"org.scalatest" %% "scalatest" % "2.1.3" % Test,
	"org.scalacheck" %% "scalacheck" % "1.12.2" % Test,
	"junit" % "junit" % "4.12" % Test,
	"com.novocode" % "junit-interface" % "0.10" % Test,
	"org.scalautils" % "scalautils_2.11" % "2.1.3" % Test,
	"org.scala-lang.modules" %% "scala-xml" % "1.0.3" % Test
)

fork in (Test,run) := true

scalariformSettings

publishMavenStyle := true