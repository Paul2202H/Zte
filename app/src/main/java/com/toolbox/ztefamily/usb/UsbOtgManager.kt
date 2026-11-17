package com.toolbox.ztefamily.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import androidx.core.content.ContextCompat

class UsbOtgManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var adbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var deviceMonitor: BroadcastReceiver? = null

    companion object {
        private const val ACTION_USB_PERMISSION = "com.toolbox.ztefamily.USB_PERMISSION"
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
    }

    data class DeviceEntry(
        val device: UsbDevice,
        val label: String,
        val adbAvailable: Boolean,
        val interfaceSummary: String
    )


    fun startMonitoring(onChanged: () -> Unit) {
        if (deviceMonitor != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { onChanged() }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        deviceMonitor = receiver
    }

    fun stopMonitoring() {
        deviceMonitor?.let { runCatching { context.unregisterReceiver(it) } }
        deviceMonitor = null
    }

    fun listAttachedDevices(): List<DeviceEntry> = usbManager.deviceList.values.map { d ->
        val adb = findAdbInterface(d)
        val summary = if (adb == null) "No standard ADB interface" else (0 until adb.endpointCount).joinToString { index ->
            val ep = adb.getEndpoint(index)
            val direction = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
            val type = if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "bulk" else "type-${ep.type}"
            "$direction $type ${ep.maxPacketSize}B"
        }.ifBlank { "ADB interface has no endpoints" }
        DeviceEntry(d, "${d.manufacturerName ?: "USB device"} ${d.productName ?: d.deviceName}", adb != null, summary)
    }

    fun listAdbCapableDevices(): List<DeviceEntry> = listAttachedDevices().filter { it.adbAvailable }

    private fun findAdbInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount)
        .map { d.getInterface(it) }
        .firstOrNull { it.interfaceClass == ADB_CLASS && it.interfaceSubclass == ADB_SUBCLASS && it.interfaceProtocol == ADB_PROTOCOL }

    fun requestPermissionAndOpen(target: UsbDevice, onResult: (Boolean, String?) -> Unit) {
        val intf = findAdbInterface(target)
        if (intf == null) { onResult(false, "No standard ADB USB interface detected. Enable USB debugging and select a data/ADB USB mode."); return }
        if (usbManager.hasPermission(target)) { openInterface(target, intf, onResult); return }
        val requestCode = (target.deviceId * 31 + target.vendorId) and 0x7fffffff
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName).putExtra("deviceId", target.deviceId)
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val returned = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (granted && (returned == null || returned.deviceId == target.deviceId)) openInterface(target, intf, onResult)
                else onResult(false, "USB permission denied or device changed during authorization")
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        usbManager.requestPermission(target, pending)
    }

    private fun openInterface(d: UsbDevice, intf: UsbInterface, onResult: (Boolean, String?) -> Unit) {
        close()
        val conn = usbManager.openDevice(d)
        if (conn == null) { onResult(false, "Android could not open the USB device"); return }
        if (!conn.claimInterface(intf, true)) { conn.close(); onResult(false, "Could not claim the ADB interface"); return }
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val endpoint = intf.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
        }
        if (input == null || output == null) {
            conn.releaseInterface(intf); conn.close(); onResult(false, "ADB interface does not expose both bulk endpoints"); return
        }
        connection = conn; device = d; adbInterface = intf; epIn = input; epOut = output
        onResult(true, null)
    }

    fun bulkWrite(data: ByteArray, timeoutMs: Int = 5000): Int =
        connection?.bulkTransfer(epOut, data, data.size, timeoutMs) ?: -1

    fun bulkRead(buffer: ByteArray, timeoutMs: Int = 5000): Int =
        connection?.bulkTransfer(epIn, buffer, buffer.size, timeoutMs) ?: -1

    fun maxPacketSize(): Int = epOut?.maxPacketSize ?: 512
    fun attachedDevice(): UsbDevice? = device

    @Synchronized fun close() {
        val oldConnection = connection
        val oldInterface = adbInterface
        if (oldConnection != null && oldInterface != null) runCatching { oldConnection.releaseInterface(oldInterface) }
        runCatching { oldConnection?.close() }
        connection = null; device = null; adbInterface = null; epIn = null; epOut = null
    }
}
