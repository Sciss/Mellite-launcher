/*
 *  Splash.scala
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

import java.awt.{BorderLayout, Color, Desktop, Dimension, EventQueue, Font, Graphics, Graphics2D, RenderingHints}
import javax.swing.text.html.HTMLEditorKit
import javax.swing.{JComponent, JLabel, JOptionPane, JPanel, JScrollPane, JTextPane, JWindow, ScrollPaneConstants, SwingUtilities}
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.{Future, Promise, blocking}
import scala.math.min

class Splash extends Reporter { splash =>
  private var _status   = ""
  private var _version  = ""
  private var _progress = -1.0f
  private val fontHead  = new Font(Font.SANS_SERIF, Font.BOLD  , 18)
  private val fontBody  = new Font(Font.SANS_SERIF, Font.PLAIN , 16)

  private val sync      = new AnyRef
  private var hasWin    = false
  private var _disposed = false
  private val alive     = new KeepAlive
  private var hasDialog = false

  override def dispose(): Unit = {
    alive.dispose()
    sync.synchronized {
      _disposed = true
      if (hasWin) EventQueue.invokeLater(() => {
        win.setVisible(false)
        win.dispose()
      })
    }
  }

  private lazy val win: JWindow =
    sync.synchronized {
      hasWin = true
      new Window
    }

  private class Window extends JWindow {
    setSize(480, 160)
    setLocationRelativeTo(null)
    //  setVisible(true)
    setContentPane(contents)
  }

  private def repaintContents(): Unit =
    EventQueue.invokeLater(() => {
      sync.synchronized {
        if (!_disposed) {
          contents.repaint()
          if (!win.isVisible) {
            win.setVisible(true)
            if (hasDialog) win.toBack()
          }
          repaintContents()
        }
      }
    })

  override def status: String = _status
  override def status_=(value: String): Unit = if (_status != value) {
    _status = value
    repaintContents()
  }

  override def version: String = _version
  override def version_=(value: String): Unit = if (_version != value) {
    _version = value
    repaintContents()
  }

  override def progress: Float = _progress
  override def progress_=(value: Float): Unit = {
    val clip = min(value, 1f)
    if (_progress != clip) {
      _progress = clip
      repaintContents()
    }
  }

  private def foreground(): Unit = {
    if (Desktop.isDesktopSupported) {
      val d = Desktop.getDesktop
      if (d.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
        d.requestForeground(false)
      }
    }
  }

  private def withDialog[A](body: => A): A = {
    foreground()
    hasDialog = true
    val res = try {
      blocking(body)
    } finally  {
      hasDialog = false
    }
    res
  }

  override def showMessage(text: String, isError: Boolean): Future[Unit] = {
    val pr = Promise[Unit]()
    EventQueue.invokeLater(() => {
      val title = Launcher.name // if (isError) "Error" else "Information"
      val tpe   = if (isError) JOptionPane.ERROR_MESSAGE else JOptionPane.INFORMATION_MESSAGE
      withDialog {
        JOptionPane.showMessageDialog(null, text, title, tpe)
      }
      pr.success(())
    })
    pr.future
  }

  override def showConfirm(text: String, isYesNo: Boolean, extra: Future[Seq[String]]): Future[Boolean] = {
    val pr = Promise[Boolean]()
    EventQueue.invokeLater(() => {
      val title   = Launcher.name
      val tpe     = if (isYesNo) JOptionPane.YES_NO_OPTION else JOptionPane.OK_CANCEL_OPTION
      val msg     = new JPanel(new BorderLayout(0, 8))
      val text1   = if (!text.contains("\n")) text else {
        s"<html><body>${text.split("\n").mkString("<p>")}</body>"
      }
      val lbQuery = new JLabel(text1)  // XXX TODO if contains newlines, use HTML or text area
      msg.add(lbQuery, BorderLayout.NORTH)
      lazy val lbExtra = {
//        val lb = new JTextArea(12, 32)
//        lb.setLineWrap(true)
//        val lb = new JEditorPane("text/html", "")
        val lb = new JTextPane // ("text/html", "")
//        lb.setMaximumSize(new Dimension(400, 240))
        lb.setPreferredSize(new Dimension(400, 240))
        lb.setEditorKit(new HTMLEditorKit)
        lb.setEditable(false)
        val sc = new JScrollPane(lb,
          ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
          ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        )
        msg.add(sc, BorderLayout.CENTER)
        lb
      }
      extra.foreach { sq =>
        if (sq.nonEmpty) EventQueue.invokeLater(() => {
          val par   = sq.map(p => s"<li>$p</li>")
          val text  = par.mkString("<html><body><ul>", "", "</ul></body>")
          lbExtra.setText(text)
          lbExtra.setCaretPosition(0)
//          lbExtra.scrollToReference("top")
          Option(SwingUtilities.getWindowAncestor(lbExtra)).foreach { w =>
            w.pack()
            w.setLocationRelativeTo(null)
//            lbExtra.scrollRectToVisible(new Rectangle(0, 0, 4, 4))
          }
        })
      }
      // modal! make sure it comes after `extra.foreach`
      val code = withDialog {
        JOptionPane.showConfirmDialog(null, msg, title, tpe, JOptionPane.QUESTION_MESSAGE)
      }
      val res = if (isYesNo) code == JOptionPane.YES_OPTION else code == JOptionPane.OK_OPTION
      pr.success(res)
    })
    pr.future
  }

  override def showOptions(text: String, items: Seq[String], default: Option[String],
                           extra: String => Future[Seq[String]]): Future[Option[String]] = {
    val pr = Promise[Option[String]]()
    EventQueue.invokeLater(() => {
      val title   = Launcher.name
      val itemsA  = items.toArray[AnyRef]
      val initVal = default.orNull
      val code = withDialog {
        JOptionPane.showInputDialog(null, text, title,
          JOptionPane.QUESTION_MESSAGE, null, itemsA, initVal)
      }
      val res = if (code == null) None else items.find(_ == code) // if (code < 0) None else Some(items(code))
      // win.toFront()
      pr.success(res)
    })
    pr.future
  }

  private object contents extends JComponent {
    override def paintComponent(g: Graphics): Unit = {
      super.paintComponent(g)
//      super.paint(g)
      val g2 = g.asInstanceOf[Graphics2D]
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g2.setColor(Color.darkGray)
      val w = getWidth
      val h = getHeight
      g2.fillRect(0, 0, w, h)
      g2.setColor(Color.white)
      val str = "Launching Mellite"
      g2.setFont(fontHead)
      val fmHead  = g2.getFontMetrics()
      val tw      = fmHead.stringWidth(str)
      var ty      = 16
      g2.drawString(str, (w - tw) >> 1, ty + fmHead.getAscent)
      ty += fmHead.getHeight
      g2.setFont(fontBody)
      val fmBody  = g2.getFontMetrics()

      {
        ty += 8
        if (_version != "") {
          val bw = fmBody.stringWidth(_version)
          g2.drawString(_version, (w - bw) >> 1, ty + fmBody.getAscent)
        }
        ty += fmBody.getHeight
      }

      {
        ty += 8
        if (_status != "") {
          val bw = fmBody.stringWidth(_status)
          g2.drawString(_status, (w - bw) >> 1, ty + fmBody.getAscent)
        }
        ty += fmBody.getHeight
      }

      if (_progress >= 0.0) {
        val pwo = w - 96
        val pwi = (pwo * _progress + 0.5).toInt
        val px  = 48
        val ph  = 12
        ty += 16
        g2.fillRoundRect(px, ty, pwi, ph, 6, 6)
        g2.drawRoundRect(px, ty, pwo, ph, 6, 6)
      }
    }
  }
}
