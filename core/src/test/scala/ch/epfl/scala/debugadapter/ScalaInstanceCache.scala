package ch.epfl.scala.debugadapter

import scala.collection.mutable
import java.net.URLClassLoader
import coursier._
import java.nio.file.Path
import java.io.File

case class ScalaInstance(
    scalaVersion: ScalaVersion,
    libraryJars: Seq[ClassPathEntry],
    compilerJars: Seq[ClassPathEntry],
    expressionCompilerJar: ClassPathEntry,
    stepFilterJar: Option[ClassPathEntry]
) {
  val libraryClassLoader =
    new URLClassLoader(libraryJars.map(_.toURL).toArray, null)
  val compilerClassLoader =
    new URLClassLoader(compilerJars.map(_.toURL).toArray, libraryClassLoader)
  val debugToolsClassLoader = {
    val toolingJars =
      (Seq(expressionCompilerJar) ++ stepFilterJar).map(_.toURL).toArray
    if (toolingJars.isEmpty) compilerClassLoader
    else new URLClassLoader(toolingJars, compilerClassLoader)
  }

  def compile(
      classDir: Path,
      classPath: Seq[ClassPathEntry],
      sourceFiles: Seq[Path]
  ): Unit = {
    val args = Array(
      "-d",
      classDir.toString,
      "-classpath",
      classPath.map(_.absolutePath).mkString(File.pathSeparator),
      "-deprecation"
    ) ++ sourceFiles.map(_.toString)
    scalaVersion match {
      case Scala2(_) => compileScala2(args)
      case Scala3(_) => compileScala3(args)
    }
  }

  private def compileScala2(args: Array[String]): Unit = {
    val main = compilerClassLoader.loadClass("scala.tools.nsc.Main")
    val process = main.getMethod("process", classOf[Array[String]])
    val success = process.invoke(null, args).asInstanceOf[Boolean]
    if (!success) throw new Exception("compilation failed")
  }

  private def compileScala3(args: Array[String]): Unit = {
    val main = compilerClassLoader.loadClass("dotty.tools.dotc.Main")
    val process = main.getMethod("process", classOf[Array[String]])
    val classOfReporter =
      compilerClassLoader.loadClass("dotty.tools.dotc.reporting.Reporter")
    val hasErrors = classOfReporter.getMethod("hasErrors")
    val reporter = process.invoke(null, args)
    val success = !(hasErrors.invoke(reporter).asInstanceOf[Boolean])
    if (!success) throw new Exception("compilation failed")
  }
}

object ScalaInstanceCache {
  private val cache = mutable.Map.empty[ScalaVersion, ScalaInstance]

  def get(scalaVersion: ScalaVersion): ScalaInstance = {
    if (!cache.contains(scalaVersion)) {
      val scalaInstance = scalaVersion match {
        case scala2: Scala2 => fetch(scala2)
        case scala3: Scala3 => fetch(scala3)
      }
      cache.update(scalaVersion, scalaInstance)
    }
    cache(scalaVersion)
  }

  private def fetch(scalaVersion: Scala2): ScalaInstance = {
    val expressionCompilerArtifact =
      s"${BuildInfo.expressionCompilerName}_${scalaVersion.version}"
    val expressionCompilerDep = Dependency(
      Module(
        Organization(BuildInfo.organization),
        ModuleName(expressionCompilerArtifact)
      ),
      BuildInfo.version
    )

    val jars = Coursier.fetch(expressionCompilerDep)

    val libraryJars = jars.filter(jar => jar.name.startsWith("scala-library"))
    val expressionCompilerJar =
      jars.find(jar => jar.name.startsWith(expressionCompilerArtifact)).get
    val compilerJars = jars.filter(jar =>
      !libraryJars.contains(jar) && jar != expressionCompilerJar
    )

    ScalaInstance(
      scalaVersion,
      libraryJars,
      compilerJars,
      expressionCompilerJar,
      None
    )
  }

  private def fetch(scalaVersion: Scala3): ScalaInstance = {
    val expressionCompilerArtifact =
      s"${BuildInfo.expressionCompilerName}_${scalaVersion.version}"
    val expressionCompilerDep = Dependency(
      Module(
        Organization(BuildInfo.organization),
        ModuleName(expressionCompilerArtifact)
      ),
      BuildInfo.version
    )

    val stepFilterArtifact =
      s"${BuildInfo.scala3StepFilterName}_${scalaVersion.binaryVersion}"
    val stepFilterDep = Dependency(
      Module(
        Organization(BuildInfo.organization),
        ModuleName(stepFilterArtifact)
      ),
      BuildInfo.version
    )

    val jars = Coursier.fetch(expressionCompilerDep)
    val stepFilterJars = Coursier.fetch(stepFilterDep)

    val libraryJars = jars.filter { jar =>
      jar.name.startsWith("scala-library") ||
      jar.name.startsWith("scala3-library_3")
    }
    val expressionCompilerJar =
      jars.find(jar => jar.name.startsWith(expressionCompilerArtifact)).get
    val stepFilterJar =
      stepFilterJars.find(jar => jar.name.startsWith(stepFilterArtifact)).get
    val compilerJars = jars.filter { jar =>
      !libraryJars.contains(jar) && jar != expressionCompilerJar
    }

    ScalaInstance(
      scalaVersion,
      libraryJars,
      compilerJars,
      expressionCompilerJar,
      Some(stepFilterJar)
    )
  }
}
