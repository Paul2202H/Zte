
package com.toolbox.ztefamily.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

class UsbOtgManager(private val context: Context) {

    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var adbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private var deviceMonitor: BroadcastReceiver? = null
    private var permissionReceiver: BroadcastReceiver? = null

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.toolbox.ztefamily.USB_PERMISSION"

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

    // USB DEVICE MONITORING

    fun startMonitoring(onChanged: () -> Unit) {
        if (deviceMonitor != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onChanged()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        deviceMonitor = receiver
    }

    fun stopMonitoring() {
        deviceMonitor?.let {
            runCatching {
                context.unregisterReceiver(it)
            }
        }

        deviceMonitor = null
    }

    // DEVICE DISCOVERY

    fun listAttachedDevices(): List<DeviceEntry> {
        return usbManager.deviceList.values.map { usbDevice ->

            val adb = findAdbInterface(usbDevice)

            val summary = if (adb == null) {
                "No standard ADB interface"
            } else {
                (0 until adb.endpointCount).joinToString { index ->

                    val endpoint = adb.getEndpoint(index)

                    val direction =
                        if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                            "IN"
                        } else {
                            "OUT"
                        }

                    val type =
                        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            "bulk"
                        } else {
                            "type-${endpoint.type}"
                        }

                    "$direction $type ${endpoint.maxPacketSize}B"
                }.ifBlank {
                    "ADB interface has no endpoints"
                }
            }

            val manufacturer =
                usbDevice.manufacturerName ?: "USB device"

            val product =
                usbDevice.productName ?: usbDevice.deviceName

            DeviceEntry(
                device = usbDevice,
                label = "$manufacturer $product",
                adbAvailable = adb != null,
                interfaceSummary = summary
            )
        }
    }

    fun listAdbCapableDevices(): List<DeviceEntry> {
        return listAttachedDevices().filter {
            it.adbAvailable
        }
    }

    private fun findAdbInterface(
        usbDevice: UsbDevice
    ): UsbInterface? {

        return (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == ADB_CLASS &&
                it.interfaceSubclass == ADB_SUBCLASS &&
                it.interfaceProtocol == ADB_PROTOCOL
            }
    }

    // USB PERMISSION

    fun requestPermissionAndOpen(
        target: UsbDevice,
        onResult: (Boolean, String?) -> Unit
    ) {

        val intf = findAdbInterface(target)

        if (intf == null) {
            onResult(
                false,
                "No standard ADB USB interface detected. " +
                "Enable USB debugging and select a data/ADB USB mode."
            )
            return
        }

        if (usbManager.hasPermission(target)) {
            openInterface(target, intf, onResult)
            return
        }

        val requestCode =
            (target.deviceId * 31 + target.vendorId) and 0x7fffffff

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

        val permissionIntent = Intent(ACTION_USB_PERMISSION)
            .setPackage(context.packageName)
            .putExtra("deviceId", target.deviceId)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            permissionIntent,
            flags
        )

        permissionReceiver?.let { oldReceiver ->
            runCatching {
                context.unregisterReceiver(oldReceiver)
            }
        }

        val receiver = object : BroadcastReceiver() {

            override fun onReceive(
                ctx: Context?,
                intent: Intent?
            ) {

                if (intent?.action != ACTION_USB_PERMISSION) return

                permissionReceiver?.let {
                    runCatching {
                        context.unregisterReceiver(it)
                    }
                }

                permissionReceiver = null

                val granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                )

                val returnedDevice: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE,
                            UsbDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE
                        )
                    }

                if (
                    granted &&
                    (
                        returnedDevice == null ||
                        returnedDevice.deviceId == target.deviceId
                    )
                ) {
                    openInterface(target, intf, onResult)
                } else {
                    onResult(
                        false,
                        "USB permission denied or device disconnected."
                    )
                }
            }
        }

        permissionReceiver = receiver

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            usbManager.requestPermission(target, pendingIntent)
        } catch (e: Exception) {
            runCatching {
                context.unregisterReceiver(receiver)
            }
            permissionReceiver = null

            onResult(
                false,
                "USB permission request failed: ${e.message}"
            )
        }
    }

    // OPEN ADB INTERFACE

    private fun openInterface(
        usbDevice: UsbDevice,
        intf: UsbInterface,
        onResult: (Boolean, String?) -> Unit
    ) {

        close()

        val conn = usbManager.openDevice(usbDevice)

        if (conn == null) {
            onResult(
                false,
                "Android could not open the USB device."
            )
            return
        }

        if (!conn.claimInterface(intf, true)) {
            conn.close()

            onResult(
                false,
                "Could not claim the ADB USB interface."
            )
            return
        }

        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null

        for (i in 0 until intf.endpointCount) {
            val endpoint = intf.getEndpoint(i)

            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> input = endpoint
                    UsbConstants.USB_DIR_OUT -> output = endpoint
                }
            }
        }

        if (input == null || output == null) {
            conn.releaseInterface(intf)
            conn.close()

            onResult(
                false,
                "ADB interface does not expose both bulk endpoints."
            )
            return
        }

        connection = conn
        device = usbDevice
        adbInterface = intf
        epIn = input
        epOut = output

        onResult(true, null)
    }

    // USB BULK TRANSFER

    @Synchronized
    fun bulkWrite(
        data: ByteArray,
        timeoutMs: Int = 5000
    ): Int {

        val conn = connection ?: return -1
        val endpoint = epOut ?: return -1

        return try {
            conn.bulkTransfer(
                endpoint,
                data,
                data.size,
                timeoutMs
            )
        } catch (_: Exception) {
            -1
        }
    }

    @Synchronized
    fun bulkRead(
        buffer: ByteArray,
        timeoutMs: Int = 5000
    ): Int {

        val conn = connection ?: return -1
        val endpoint = epIn ?: return -1

        return try {
            conn.bulkTransfer(
                endpoint,
                buffer,
                buffer.size,
                timeoutMs
            )
        } catch (_: Exception) {
            -1
        }
    }

    // CONNECTION INFORMATION

    fun maxPacketSize(): Int {
        return epOut?.maxPacketSize ?: 512
    }

    fun attachedDevice(): UsbDevice? {
        return device
    }

    fun hasActiveConnection(): Boolean {
        return connection != null &&
            epIn != null &&
            epOut != null
    }

    // RESOURCE CLEANUP

    @Synchronized
    fun close() {

        val oldConnection = connection
        val oldInterface = adbInterface

        connection = null
        device = null
        adbInterface = null
        epIn = null
        epOut = null

        if (oldConnection != null) {
            if (oldInterface != null) {
                runCatching {
                    oldConnection.releaseInterface(oldInterface)
                }
            }

            runCatching {
                oldConnection.close()
            }
        }
    }

    fun release() {
        stopMonitoring()

        permissionReceiver?.let {
            runCatching {
                context.unregisterReceiver(it)
            }
        }

        permissionReceiver = null

        close()
    }
}
