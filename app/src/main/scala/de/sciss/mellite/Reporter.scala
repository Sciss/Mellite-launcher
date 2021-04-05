/*
 *  Reporter.scala
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

import scala.concurrent.Future

trait Reporter {
  var status: String

  var version: String

  var progress: Float

  // thread-safe
  def dispose(): Unit

  /** Shows an information or error message to the user. When using a UI, the future
    * completes when the dialog is closed. When using a text terminal, the future
    * probably completes immediately.
    *
    * @param  text    can contain line feeds
    */
  def showMessage(text: String, isError: Boolean): Future[Unit]

  /** Requests confirmation from the user. The result is a future that when its
    * value is `true`, it signifies "yes" or "ok", and the value `false` signifies
    * "no" or "cancel".
    *
    * @param  text      can contain line feeds
    * @param  isYesNo   if true, the options to the user are Yes/No, otherwise they are Ok/Cancel.
    * @param  extra     a possibly asynchronously computed additional information, segmented into
    *                   a sequence of paragraphs. Feature is optionally supported by the reporter.
    *                   When the invoker does not want to provide extra information, use the default
    *                   of `Future.successful(Nil)`.
    */
  def showConfirm(text: String, isYesNo: Boolean, extra: Future[Seq[String]] = Future.successful(Nil)): Future[Boolean]

  /** Requests a choice from the user among a sequence of options. The result is a future
    * containing the selected option, or `None` if the dialog was aborted.
    *
    * @param  text      can contain line feeds
    * @param  items     sequence of options. This should be simple items without line breaks.
    * @param  extra     a possibly asynchronously computed additional information, segmented into
    *                   a sequence of paragraphs. Feature is optionally supported by the reporter.
    *                   When the invoker does not want to provide extra information, use the default
    *                   of `_ => Future.successful(Nil)`. The `extra` function is called whenever the user
    *                   selects a particular item (in a dialog), passing that item as argument, and avoiding
    *                   having to calculate the extra information for every individual item.
    */
  def showOptions(text: String, items: Seq[String], default: Option[String],
                  extra: String => Future[Seq[String]] = _ => Future.successful(Nil)): Future[Option[String]]
}
