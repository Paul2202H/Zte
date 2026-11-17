package com.toolbox.ztefamily.adb

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val buildFingerprint: String,
    val chipset: String,
    val serial: String,
    val apiLevel: String = "",
    val securityPatch: String = ""
)

object DeviceInfoReader {
    fun read(conn: AdbConnection): DeviceInfo? {
        val keys = listOf(
            "ro.product.model", "ro.product.manufacturer", "ro.build.version.release",
            "ro.build.fingerprint", "ro.board.platform", "ro.serialno",
            "ro.build.version.sdk", "ro.build.version.security_patch"
        )
        val command = keys.joinToString("; ") { "getprop $it" }
        val stream = conn.openStream("shell:$command") ?: return null
        val output = try { stream.readAllUtf8() } finally { stream.close() }
        val p = output.lineSequence().map { it.trim() }.toList()
        if (p.size < keys.size) return null
        return DeviceInfo(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7])
    }
}
