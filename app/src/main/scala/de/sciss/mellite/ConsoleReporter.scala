/*
 *  ConsoleReporter.scala
 *  (Mellite-launcher)
 *
 *  Copyright (c) 2020-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite

import scala.concurrent.{Future, blocking}
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits._

class ConsoleReporter extends Reporter {
  private var _status   = ""
  private var _version  = ""
  private var _progress = -1.0f
  private var _lastProg = 0f
  private val alive     = new KeepAlive

  override def status: String = _status

  override def status_=(v: String): Unit = if (_status != v) {
    _status = v
    printStatus(v)
  }

  private def printStatus(s: String): Unit =
    println(s"[launch] $s")

  override def version: String = _version

  override def version_=(v: String): Unit = if (_version != v) {
    _version = v
    printStatus(s"version $v")
  }

  override def progress: Float = _progress

  override def progress_=(v: Float): Unit = {
    _progress = v
    if (/*_lastProg > v ||*/ v - _lastProg >= 0.05f) {
      _lastProg = v
      printStatus(f"progress ${v * 100}%1.0f")
    }
  }

  override def dispose(): Unit = alive.dispose()

  override def showMessage(text: String, isError: Boolean): Future[Unit] = {
    val s = if (isError) "ERROR" else "INFO"
    printStatus(s"[$s] $text")
    Future.unit
  }

  // does not support 'extra'
  override def showConfirm(text: String, isYesNo: Boolean, extra: Future[Seq[String]]): Future[Boolean] = {
    printStatus(s"[Confirm] $text")
    Future {
      var res = Option.empty[Boolean]
      while ({
        Console.out.print("Y/N> ")
        Console.out.flush()
        res = Try(blocking(Console.in.readLine())).toOption.flatMap { s =>
          s.trim.toUpperCase() match {
            case "Y"  => Some(true)
            case "N"  => Some(true)
            case _    => None
          }
        }
        res.isEmpty
      }) ()
      res.get
    }
  }

  // does not support 'extra'
  override def showOptions(text: String, items: Seq[String], default: Option[String],
                           extra: String => Future[Seq[String]]): Future[Option[String]] = {
    printStatus(s"[Query] $text")
    val itemsI = items.map(s => if (default.contains(s)) s"* $s" else s"  $s")
    println(itemsI.mkString("\n"))
    Future {
      var res = Option.empty[String]
      while ({
        Console.out.print("> ")
        Console.out.flush()
        res = Try(blocking(Console.in.readLine())).toOption
        res.isEmpty || res.exists(items.contains)
      }) ()
      res
    }
  }
}
