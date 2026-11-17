package com.toolbox.ztefamily.adb

/** Single reader for the USB ADB transport; all logical stream frames are dispatched here. */
class AdbPump(private val conn: AdbConnection) {
    @Volatile private var running = false
    private var thread: Thread? = null
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                try { conn.pumpOnce() } catch (_: InterruptedException) { if (!running) break }
                catch (_: Exception) { if (!running) break; running = false }
            }
        }, "adb-usb-reader").apply { isDaemon = true; start() }
    }
    fun stop() { running = false; thread?.interrupt() }
}
