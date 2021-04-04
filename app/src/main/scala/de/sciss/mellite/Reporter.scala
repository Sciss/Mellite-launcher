/*
 *  Reporter.scala
 *  (Mellite-launcher)
 *
 *  Copyright (c) 2020-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite

import scala.concurrent.Future

trait Reporter {
  var status: String

  var version: String

  var progress: Float

  // thread-safe
  def dispose(): Unit

  def showMessage(text: String, isError: Boolean): Future[Unit]

  def showConfirm(text: String, isYesNo: Boolean): Future[Boolean]

  def showOptions(text: String, items: Seq[String], default: Option[String]): Future[Option[String]]
}
