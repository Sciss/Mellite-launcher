package de.sciss.mellite

class KeepAlive extends Thread {
  private val sync      = new AnyRef
  private var _disposed = false

  override def run(): Unit = sync.synchronized(while (!_disposed) sync.wait())

  setDaemon(false)
  start()

  def dispose(): Unit = {
    sync.synchronized {
      _disposed = true
      sync.notify()
    }
  }
}
