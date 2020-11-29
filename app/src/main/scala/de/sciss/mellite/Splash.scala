package de.sciss.mellite

import java.awt.{Color, Font, Graphics, Graphics2D, RenderingHints}
import javax.swing.JWindow

class Splash extends JWindow {
  private var _status   = ""
  private var _version  = ""
  private val fontHead = new Font(Font.SANS_SERIF, Font.BOLD  , 18)
  private val fontBody = new Font(Font.SANS_SERIF, Font.PLAIN , 16)

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

//    if (_version != "")
    {
      val bw = fmBody.stringWidth(_version)
      ty += 8
      g2.drawString(_version, (w - bw) >> 1, ty + fmBody.getAscent)
      ty += fmBody.getHeight
    }

    if (_status != "") {
      val bw = fmBody.stringWidth(_status)
      ty += 8
      g2.drawString(_status, (w - bw) >> 1, ty + fmBody.getAscent)
      ty += fmBody.getHeight
    }
  }
}
