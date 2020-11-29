/*
 *  Splash.scala
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

import java.awt.{Color, Font, Graphics, Graphics2D, RenderingHints}
import javax.swing.JWindow
import scala.math.min

class Splash extends JWindow {
  private var _status   = ""
  private var _version  = ""
  private var _progress = -1.0f
  private val fontHead  = new Font(Font.SANS_SERIF, Font.BOLD  , 18)
  private val fontBody  = new Font(Font.SANS_SERIF, Font.PLAIN , 16)

  setSize(480, 160)
  setLocationRelativeTo(null)
  setVisible(true)

  def status: String = _status
  def status_=(value: String): Unit = if (_status != value) {
    _status = value
    repaint()
  }

  def version: String = _version
  def version_=(value: String): Unit = if (_version != value) {
    _version = value
    repaint()
  }

  def progress: Float = _progress
  def progress_=(value: Float): Unit = {
    val clip = min(value, 1f)
    if (_progress != clip) {
      _progress = clip
      repaint()
    }
  }

  override def paint(g: Graphics): Unit = {
    super.paint(g)
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
