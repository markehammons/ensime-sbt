package org.ensime.core

import akka.util.ByteString
import java.io.File
import java.util.jar.JarFile
import org.ensime.api.EnsimeConfig

import org.ensime.util.io._

trait DocJarReading {
  def config: EnsimeConfig

  def docJarContent(filename: String, entry: String): Option[ByteString] = for {
    file <- config.allDocJars.find(_.getName == filename)
    jar = new JarFile(file)
    entry <- Option(jar.getJarEntry(entry))
    stream = jar.getInputStream(entry)
  } yield ByteString(stream.toByteArray)

  def docJars(): Set[File] = config.allDocJars
}
