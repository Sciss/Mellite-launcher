/*
 *  Launcher.scala
 *  (Mellite-launcher)
 *
 *  Copyright (c) 2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite

import coursier._
import coursier.cache.CacheLogger
import coursier.util.Task
import net.harawata.appdirs.AppDirsFactory

import java.awt.EventQueue
import java.io.File
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration.DurationInt

object Launcher {
  // eventually these could become settings of a generic launcher
  final val groupId         = "de.sciss"
  final val artifactId      = "mellite-app_2.13"
  final val mainClass       = "de.sciss.mellite.Mellite"
  final val classPathFilter = "/org.openjfx" :: Nil

  final val DEBUG = false

  def main(args: Array[String]): Unit = {
//    val parent    = getClass.getClassLoader
//    val cl        = new URLClassLoader(new Array(0), parent)
//    val clLaunch  = Class.forName("de.sciss.mellite.Launcher", true, cl)
//    val cons      = clLaunch.getConstructor(classOf[ClassLoader])
//    cons.newInstance(parent)
//    val ph = ProcessHandle.current()
//    val pi = ph.info()
//    println(s"COMMAND: ${pi.command()}")
//    println(s"COMMAND LINE: ${pi.commandLine()}")
//    println(s"ARGS: ${pi.arguments()}")
//    println(pi)
    EventQueue.invokeLater { () =>
      val splash = new Splash
      run(splash)
    }
  }

  private def run(splash: Splash): Unit = {
    import splash.status
    val appDirs   = AppDirsFactory.getInstance
    val cacheBase = appDirs.getUserCacheDir ("mellite", /* version */ null, /* author */ "de.sciss")
    val dataBase  = appDirs.getUserDataDir  ("mellite", /* version */ null, /* author */ "de.sciss")
    if (DEBUG) {
      println(s"Cache path: $cacheBase")
      println(s"Data path : $dataBase" )
    }
    val cacheDir  = new java.io.File(cacheBase, "coursier")
    val artDir    = new java.io.File(dataBase , "coursier")
//    cacheDir.mkdirs()
//    artDir  .mkdirs()

    status = "Checking version..."
    val cacheResolve = cache.FileCache[Task](cacheDir)
      .withTtl(1.hour)
    //      .withLogger(RefreshLogger.create(System.out))
    //      .withPool(pool)

//    val appDep  = dep"$groupId:$artifactId:latest.release"
    val appMod  = Module(Organization(groupId), ModuleName(artifactId))
//    val appDep  = Dependency(appMod, "latest.release")
    val versions = Versions(cacheResolve).withModule(appMod)
//    val resolve = Resolve(cacheResolve)
//      .addDependencies(appDep)
//    val futRes = resolve.future()
    val futVer = versions.result().future()

//    val exit = new AnyRef

//    new Thread {
//      override def run(): Unit =
//        exit.synchronized(exit.wait())
//
//      start()
//    }

    val futLatest = futVer.map { req =>
      val vs = req.versions
//      status = "Resolved."
//        println(resolve)
//      if (DEBUG) {
//        println("------ Conflicts ------")
//        resolution.conflicts.foreach(println)
//        println("------ Dependencies ------")
//        resolution.orderedDependencies.foreach(println)
//        println("------ Root Dependencies ------")
//        resolution.rootDependencies.foreach(println)
//        println("------ Missing from Cache ------")
//        resolution.missingFromCache
//      }

      if (DEBUG) {
        println("------ Available Versions ------")
        vs.available.foreach(println)
      }

      vs.latest
    }

    val futFetch = futLatest.flatMap { v =>
      splash.version = s"version $v"
      status = "Resolving dependencies..."
      val appDep    = Dependency(appMod, v)
      val resolve   = Resolve(cacheResolve)
        .addDependencies(appDep)
      val cacheArt  = cache.FileCache[Task](artDir)
      resolve.future().flatMap { resolution =>
        status = "Fetching libraries..."
//        status = "Resolving artifacts..."
        val artifacts = Artifacts(cacheArt).withResolution(resolution)
        val dlLog = new CacheLogger {
          private val remain  = mutable.Set(resolution.artifacts().map(_.url): _*)
          private val size    = remain.size

          private def add(url: String): Unit = if (remain.remove(url)) {
            val done = size - remain.size
            val p = done.toFloat / size
            splash.progress = p
          }

          override def foundLocally       (url: String)                   : Unit = add(url)
          override def downloadedArtifact (url: String, success: Boolean) : Unit = add(url)
        }
        val cacheArtL = cacheArt.withLogger(dlLog)
        val fetch = new Fetch(resolve, artifacts, None).withCache(cacheArtL)
        fetch.futureResult()
      }
    }

    val futFiles = futFetch.map { fetched =>
      status = "Fetched libraries."
      if (DEBUG) {
        println("------ Artifacts ------")
        fetched.detailedArtifacts.foreach { case (dep, pub, art, f) =>
          println(s"DEP $dep | PUB $pub | ART $art | FILE $f")
        }
      }
//      fetched.fullDetailedArtifacts /*detailedArtifacts*/.collectFirst {
//        case (dep, pub, art, f) if
//      }

      fetched.files
    }

    val futInst = futFiles.map { files =>
      val addCP   = files.iterator.map(_.getPath)
      val ph      = ProcessHandle.current()
      val pi      = ph.info()
      val cmd     = pi.command().get()
      val argsIn  = pi.arguments().get()

      if (DEBUG)
      {
        println(s"CMD      = '$cmd''")
        println(s"ARGS IN  = ${argsIn.mkString("'", "', '", "'")}")
      }

      val idxCP   = {
        var i         = argsIn.indexOf("-classpath")
        if (i < 0) i  = argsIn.indexOf("-cp")
          require (i >= 0)
        i + 1
      }
      val oldCP   = argsIn(idxCP).split(File.pathSeparatorChar)
      val keepCP  = oldCP.filter(jar => classPathFilter.exists(jar.contains))
      val newCP   = keepCP ++ addCP
      val argsOut = argsIn.init.patch(idxCP, newCP.mkString(File.pathSeparator) :: Nil, 1) :+ mainClass

      if (DEBUG)
      {
        println(s"ARGS OUT = ${argsOut.mkString("'", "', '", "'")}")
      }

      val pb      = new ProcessBuilder(cmd +: argsOut: _*)
      pb.inheritIO()
      val p       = pb.start()
      p
    }

    val futErr = futInst.recover {
      case ex =>
        status = "Failed!"
        ex.printStackTrace()
        throw ex
    }

    futErr.foreach { p =>
      val alive = new Thread(() => {
        EventQueue.invokeLater(() => splash.dispose())
        val code = p.waitFor()  // join child process
        if (DEBUG) {
          println(s"EXIT CODE $code")
        }
        sys.exit(code)
      })
      alive.setDaemon(false)
      alive.start()
    }
  }
}
