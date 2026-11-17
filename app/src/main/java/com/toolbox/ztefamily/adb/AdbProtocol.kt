package com.toolbox.ztefamily.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implements the wire format of Android's ADB protocol (the same protocol the
 * official `adb` host tool speaks to adbd on the phone). This is a transport
 * only: every connection still goes through the standard CNXN/AUTH handshake,
 * so it only works against a device whose screen the user has unlocked and
 * on which they tap "Allow" for this app's RSA key fingerprint -- exactly
 * the same authorization step stock adb requires. There is no bypass here.
 */
object AdbProtocol {

    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val A_VERSION = 0x01000000
    const val MAX_PAYLOAD = 256 * 1024

    data class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray = ByteArray(0)
    )

    /** Packs a message into the 24-byte header + payload wire format. */
    fun pack(msg: Message): ByteArray {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var checksum = 0
        for (b in msg.payload) checksum += (b.toInt() and 0xFF)

        header.putInt(msg.command)
        header.putInt(msg.arg0)
        header.putInt(msg.arg1)
        header.putInt(msg.payload.size)
        header.putInt(checksum)
        header.putInt(msg.command.inv())

        return header.array() + msg.payload
    }

    /** Parses the 24-byte header. Caller reads `dataLength` more bytes separately. */
    fun unpackHeader(header: ByteArray): Header {
        require(header.size == 24) { "ADB header must be 24 bytes" }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        val dataCheck = buf.int
        val magic = buf.int
        check(magic == command.inv()) { "Bad ADB header magic" }
        return Header(command, arg0, arg1, dataLength, dataCheck)
    }

    data class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataCheck: Int
    )

    /** "host::" connect payload adb sends to identify itself. */
    fun connectPayload(systemIdentity: String = "host::toolbox"): ByteArray =
        (systemIdentity + "\u0000").toByteArray(Charsets.UTF_8)

    fun openPayload(destination: String): ByteArray =
        (destination + "\u0000").toByteArray(Charsets.UTF_8)
}
