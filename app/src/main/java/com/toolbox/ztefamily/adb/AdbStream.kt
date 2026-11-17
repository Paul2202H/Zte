package com.toolbox.ztefamily.adb

import java.util.concurrent.LinkedBlockingQueue

/**
 * One logical ADB stream, e.g. a "shell:<cmd>" invocation. Multiple streams
 * share the single USB bulk pipe; AdbConnection demultiplexes frames by
 * their arg1 (local id) back to the matching AdbStream instance.
 */
class AdbStream(val localId: Int, val remoteId: Int, private val conn: AdbConnection) {

    private val inbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false

    /** Blocks until the peer ACKs our previous write or the stream closes. */
    private val writeAcks = LinkedBlockingQueue<Unit>()

    fun write(data: ByteArray) {
        if (closed) return
        conn.send(AdbProtocol.Message(AdbProtocol.A_WRTE, localId, remoteId, data))
        writeAcks.take() // simple flow control: one outstanding WRTE at a time, per the ADB spec
    }

    fun writeUtf8(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /** Blocks for the next chunk of output, or returns null once the stream has closed. */
    fun readChunk(): ByteArray? {
        while (true) {
            if (closed && inbox.isEmpty()) return null
            val chunk = inbox.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (chunk != null) return chunk
            if (closed) return null
        }
    }

    /** Convenience: drains the stream to completion and returns it as one string (use for short commands). */
    fun readAllUtf8(timeoutMs: Long = 30_000): String {
        val sb = StringBuilder()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (System.nanoTime() >= deadline) throw java.util.concurrent.TimeoutException("ADB shell stream timed out")
            val chunk = inbox.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (chunk != null) sb.append(String(chunk, Charsets.UTF_8))
            else if (closed && inbox.isEmpty()) break
        }
        return sb.toString()
    }

    internal fun onData(payload: ByteArray) {
        inbox.put(payload)
    }

    internal fun onWriteAck() {
        writeAcks.offer(Unit)
    }

    internal fun onClosed() {
        closed = true
    }

    fun close() {
        if (!closed) {
            closed = true
            conn.closeStream(this)
        }
    }
}
