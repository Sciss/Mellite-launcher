package de.sciss.mellite

class ConsoleReporter extends Reporter {
  private var _status   = ""
  private var _version  = ""
  private var _progress = -1.0f
  private var _lastProg = 0f

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
    if (_lastProg > v || v - _lastProg >= 0.05f) {
      _lastProg = v
      printStatus(f"progress ${v * 100}%1.0f")
    }
  }

  override def dispose(): Unit = ()
}
