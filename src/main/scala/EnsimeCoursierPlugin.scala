// Copyright (C) 2014 - 2016 ENSIME Contributors
// Licence: Apache-2.0
package org.ensime

import scala.util.Properties.{versionNumberString => sbtScalaVersion}

import EnsimeCoursierKeys._
import EnsimeKeys._
import sbt._
import sbt.IO._
import sbt.Keys._
import scalaz._
import scalaz.concurrent.Task

object EnsimeCoursierKeys {
  val ensimeServerVersion = settingKey[String](
    "The ensime server version"
  )

  // can't include Coursier keys in our public API because it is shaded
  val ensimeRepositoryUrls = settingKey[Seq[String]](
    "The maven repositories to download the scala compiler, ensime-server and ensime-plugins jars"
  )

  def addEnsimeScalaPlugin(module: ModuleID, args: String = ""): Seq[Setting[_]] = {
    ensimeScalacOptions += {
      val jar = EnsimeCoursierPlugin.resolveSingleJar(module, ensimeScalaVersion.value, ensimeRepositoryUrls.value)
      s"-Xplugin:${jar.getCanonicalFile}${args}"
    }
  }

}

/**
 * Defines the tasks that resolve all the jars needed to start the
 * ensime-server.
 *
 * Intentionally separated from EnsimePlugin to allow corporate users
 * to avoid a dependency on coursier and provide hard coded jar paths.
 */
object EnsimeCoursierPlugin extends AutoPlugin {

  override def requires = EnsimePlugin
  override def trigger = allRequirements
  val autoImport = EnsimeCoursierKeys

  override lazy val buildSettings = Seq(
    ensimeServerVersion := "1.0.0",
    ensimeRepositoryUrls := Seq(
      // intentionally not using the ivy cache because it's very unreliable
      "https://repo1.maven.org/maven2/",
      // including snapshots by default makes it easier to use dev ensime
      "https://oss.sonatype.org/content/repositories/snapshots/"
    ),

    ensimeScalaJars := resolveScalaJars(scalaOrganization.value, ensimeScalaVersion.value)(resolvers(ensimeRepositoryUrls.value)),
    ensimeScalaProjectJars := resolveScalaJars("org.scala-lang", sbtScalaVersion)(resolvers(ensimeRepositoryUrls.value)),
    ensimeServerJars := resolveEnsimeJars(scalaOrganization.value, ensimeScalaVersion.value, ensimeServerVersion.value)(resolvers(ensimeRepositoryUrls.value)),
    ensimeServerProjectJars := resolveEnsimeJars("org.scala-lang", sbtScalaVersion, ensimeServerVersion.value)(resolvers(ensimeRepositoryUrls.value))
  )

  private[this] def resolvers(urls: Seq[String]) = urls.map(coursier.MavenRepository(_))

  private[this] def resolve(modules: ModuleID*)(implicit repos: Seq[coursier.Repository]): Seq[File] = {
    val resolution = coursier.Resolution(
      modules.map { module =>
      coursier.Dependency(
        coursier.Module(
          module.organization,
          module.name
        ),
        module.revision,
        configuration = module.configurations.getOrElse(""),
        transitive = module.isTransitive
      )
    }.toSet
    )

    val fetch = coursier.Fetch.from(repos, coursier.Cache.fetch())
    val resolved = resolution.process.run(fetch).unsafePerformSync
    resolved.errors.foreach { err =>
      throw new RuntimeException(s"failed to resolve $err")
    }

    Task.gatherUnordered(
      resolved.artifacts.map(coursier.Cache.file(_).run)
    ).unsafePerformSync.flatMap {
        case -\/(err)                                    => throw new RuntimeException(err.message)
        case \/-(file) if !file.getName.endsWith(".jar") => None
        case \/-(file)                                   => Some(file)
      }
  }

  private[this] def resolveScalaJars(org: String, version: String)(implicit repos: Seq[coursier.Repository]): Seq[File] = resolve(
    org % "scalap" % version intransitive,
    org % "scala-compiler" % version intransitive,
    org % "scala-reflect" % version intransitive,
    org % "scala-library" % version intransitive
  )

  private[this] def resolveEnsimeJars(org: String, scala: String, ensime: String)(implicit repos: Seq[coursier.Repository]): Seq[File] = {
    val Some((major, minor)) = CrossVersion.partialVersion(scala)
    resolve(
      "org.ensime" % s"server_$major.$minor" % ensime,
      org % "scalap" % scala intransitive
    )
  }

  private[ensime] def resolveSingleJar(module: ModuleID, scalaVersion: String, repositories: Seq[String]): File = {
    val mod = CrossVersion(
      scalaVersion,
      CrossVersion.binaryScalaVersion(scalaVersion)
    )(module.intransitive)
    resolve(mod)(resolvers(repositories)).head.getCanonicalFile
  }
}
