package com.toolbox.ztefamily.adb

import com.toolbox.ztefamily.usb.UsbOtgManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class AdbState {
    object Disconnected : AdbState()
    object Connecting : AdbState()
    object AwaitingAuth : AdbState()
    data class Connected(val deviceBanner: String) : AdbState()
    data class Failed(val reason: String) : AdbState()
}

/**
 * Owns one ADB session over a UsbOtgManager transport: performs the
 * CNXN -> AUTH(token) -> AUTH(signature) -> [AUTH(pubkey) if needed] -> CNXN
 * handshake, then opens logical streams (e.g. "shell:<cmd>") multiplexed
 * over the single USB bulk pipe, exactly as adb's protocol defines.
 */
class AdbConnection(private val usb: UsbOtgManager, private val crypto: AdbCrypto) {

    private val localIdCounter = AtomicInteger(1)
    private val openStreams = ConcurrentHashMap<Int, AdbStream>()
    private val pendingOpens = ConcurrentHashMap<Int, LinkedBlockingQueue<AdbStream>>()
    @Volatile var state: AdbState = AdbState.Disconnected
        private set

    /** Performs the handshake. Call off the main thread. Returns the final state. */
    fun connect(): AdbState {
        state = AdbState.Connecting
        send(AdbProtocol.Message(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, AdbProtocol.connectPayload()))

        var signedAlready = false
        while (true) {
            val msg = receive(60_000) ?: return fail("USB read timed out during ADB authentication. Unlock the target and accept the USB debugging prompt.")
            when (msg.command) {
                AdbProtocol.A_CNXN -> {
                    val banner = String(msg.payload, Charsets.UTF_8).trimEnd('\u0000')
                    state = AdbState.Connected(banner)
                    return state
                }
                AdbProtocol.A_AUTH -> {
                    if (msg.arg0 != AdbProtocol.AUTH_TOKEN) continue
                    state = AdbState.AwaitingAuth
                    if (!signedAlready) {
                        val sig = crypto.signToken(msg.payload)
                        send(AdbProtocol.Message(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, sig))
                        signedAlready = true
                    } else {
                        // Device rejected the signature (key not yet trusted) -- offer the public key.
                        // The phone will show its "Allow USB debugging?" dialog with this key's fingerprint.
                        send(AdbProtocol.Message(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, crypto.encodedPublicKey()))
                    }
                }
                else -> { /* ignore stray frames before CNXN completes */ }
            }
        }
    }

    private fun fail(reason: String): AdbState {
        state = AdbState.Failed(reason)
        return state
    }

    /** Opens a stream after the background reader dispatches its A_OKAY response. */
    fun openStream(destination: String): AdbStream? {
        if (state !is AdbState.Connected) return null
        val localId = localIdCounter.getAndIncrement()
        val result = LinkedBlockingQueue<AdbStream>(1)
        pendingOpens[localId] = result
        try {
            send(AdbProtocol.Message(AdbProtocol.A_OPEN, localId, 0, AdbProtocol.openPayload(destination)))
        } catch (error: Exception) {
            pendingOpens.remove(localId)
            throw error
        }
        // During the brief period before AdbPump starts, retain compatibility with direct reads.
        val response = result.poll(12, TimeUnit.SECONDS)
        if (response != null && response.remoteId != 0) return response
        pendingOpens.remove(localId)
        return null
    }

    /** Pumps one frame to whichever open stream it belongs to; called by stream read loops too. */
    internal fun pumpOnce(): AdbProtocol.Message? {
        val msg = receive() ?: return null
        dispatchToStream(msg)
        return msg
    }

    private fun dispatchToStream(msg: AdbProtocol.Message) {
        if (msg.command == AdbProtocol.A_CLSE && !openStreams.containsKey(msg.arg1)) {
            pendingOpens.remove(msg.arg1)?.offer(AdbStream(msg.arg1, 0, this))
            return
        }
        if (msg.command == AdbProtocol.A_OKAY && !openStreams.containsKey(msg.arg1)) {
            val pending = pendingOpens.remove(msg.arg1)
            if (pending != null) {
                val stream = AdbStream(msg.arg1, msg.arg0, this)
                openStreams[msg.arg1] = stream
                pending.offer(stream)
                return
            }
        }
        val stream = openStreams[msg.arg1] ?: return
        when (msg.command) {
            AdbProtocol.A_WRTE -> {
                stream.onData(msg.payload)
                send(AdbProtocol.Message(AdbProtocol.A_OKAY, stream.localId, stream.remoteId))
            }
            AdbProtocol.A_CLSE -> {
                stream.onClosed()
                openStreams.remove(msg.arg1)
            }
            AdbProtocol.A_OKAY -> stream.onWriteAck()
        }
    }

    @Synchronized internal fun send(msg: AdbProtocol.Message) {
        val packet = AdbProtocol.pack(msg)
        val written = usb.bulkWrite(packet)
        check(written == packet.size) { "USB ADB write failed or was incomplete ($written/${packet.size} bytes)" }
    }

    internal fun closeStream(stream: AdbStream) {
        send(AdbProtocol.Message(AdbProtocol.A_CLSE, stream.localId, stream.remoteId))
        openStreams.remove(stream.localId)
    }

    private fun receive(timeoutMs: Int = 5000): AdbProtocol.Message? {
        val headerBuf = ByteArray(24)
        if (!readFully(headerBuf, timeoutMs)) return null
        val header = AdbProtocol.unpackHeader(headerBuf)
        require(header.dataLength in 0..AdbProtocol.MAX_PAYLOAD) { "Invalid ADB payload length: ${header.dataLength}" }
        val payload = if (header.dataLength > 0) ByteArray(header.dataLength).also { if (!readFully(it, timeoutMs) ) return null } else ByteArray(0)
        if (payload.isNotEmpty()) {
            var checksum = 0
            payload.forEach { checksum += it.toInt() and 0xff }
            check(checksum == header.dataCheck) { "ADB payload checksum mismatch" }
        }
        return AdbProtocol.Message(header.command, header.arg0, header.arg1, payload)
    }


    private fun readFully(buffer: ByteArray, timeoutMs: Int): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val requested = minOf(buffer.size - offset, 16_384)
            val chunk = ByteArray(requested)
            val count = usb.bulkRead(chunk, timeoutMs)
            if (count <= 0) return false
            System.arraycopy(chunk, 0, buffer, offset, count)
            offset += count
        }
        return true
    }

    fun disconnect() {
        openStreams.values.toList().forEach { runCatching { it.close() } }
        pendingOpens.clear()
        usb.close()
        state = AdbState.Disconnected
    }
}
