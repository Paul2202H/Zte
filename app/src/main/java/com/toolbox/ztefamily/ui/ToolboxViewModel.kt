package com.toolbox.ztefamily.ui

import android.content.Context
import android.hardware.usb.UsbDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toolbox.ztefamily.adb.*
import com.toolbox.ztefamily.data.DeviceModels
import com.toolbox.ztefamily.usb.UsbOtgManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ToolboxViewModel(private val appContext: Context) : ViewModel() {
    var devices by mutableStateOf<List<UsbOtgManager.DeviceEntry>>(emptyList()); private set
    var status by mutableStateOf("Not connected"); private set
    var deviceInfo by mutableStateOf<DeviceInfo?>(null); private set
    var consoleOutput by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var selectedDevice by mutableStateOf<UsbDevice?>(null); private set
    var commandHistory by mutableStateOf<List<String>>(emptyList()); private set
    var modelQuery by mutableStateOf(""); var brandFilter by mutableStateOf("All")

    private var usb = UsbOtgManager(appContext)
    private var conn: AdbConnection? = null
    private var pump: AdbPump? = null

    init { usb.startMonitoring { refreshDevices() } }

    fun refreshDevices() {
        devices = usb.listAttachedDevices()
        val adbCount = devices.count { it.adbAvailable }
        status = if (devices.isEmpty()) "No USB devices detected. Connect a target phone using a data-capable OTG cable." else "${devices.size} USB device(s) detected; $adbCount expose a standard ADB interface"
    }

    fun connectTo(entry: UsbOtgManager.DeviceEntry) {
        if (busy) return
        busy = true
        status = "Requesting USB permission…"
        usb.requestPermissionAndOpen(entry.device) { granted, error ->
            if (!granted) { status = "USB error: ${error ?: "permission denied"}"; busy = false; return@requestPermissionAndOpen }
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val crypto = AdbCrypto.loadOrGenerate(appContext)
                        val connection = AdbConnection(usb, crypto)
                        val state = withTimeoutOrNull(70_000) { connection.connect() }
                            ?: AdbState.Failed("ADB handshake timed out. Check the target phone and accept its USB debugging prompt.")
                        connection to state
                    }
                }
                busy = false
                result.onSuccess { (connection, state) ->
                    if (state is AdbState.Connected) {
                        conn = connection
                        selectedDevice = entry.device
                        pump = AdbPump(connection).also { it.start() }
                        status = "Connected: ${entry.label}"
                        refreshDiagnostics()
                    } else {
                        connection.disconnect()
                        status = (state as? AdbState.Failed)?.reason ?: "Connection failed"
                    }
                }.onFailure {
                    usb.close()
                    status = "Connection error: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
    }

    fun runShellCommand(command: String, label: String = command) {
        val safe = command.trim()
        if (safe.isEmpty()) return
        val connection = conn ?: run { status = "Connect an ADB device first"; return }
        if (busy) return
        busy = true
        commandHistory = (commandHistory + safe).takeLast(50)
        viewModelScope.launch {
            val output = withContext(Dispatchers.IO) {
                withTimeoutOrNull(30_000) {
                    runCatching {
                        val stream = connection.openStream("shell:$safe") ?: error("ADB shell stream could not be opened")
                        try { stream.readAllUtf8(25_000) } finally { stream.close() }
                    }.fold({ it }, { "ERROR: ${it.message ?: it.javaClass.simpleName}" })
                } ?: "ERROR: Command timed out after 30 seconds"
            }
            consoleOutput += "\n$ $label\n${output.ifBlank { "(no output)" }}\n"
            if (consoleOutput.length > 60_000) consoleOutput = consoleOutput.takeLast(60_000)
            busy = false
        }
    }

    fun runDiagnostic(title: String, command: String) = runShellCommand(command, title)

    fun requestDeviceReboot(label: String, command: String) {
        val connection = conn ?: run { status = "Connect an ADB device first"; return }
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    connection.openStream("shell:$command") ?: error("ADB could not open the restart command")
                    delay(900)
                    runCatching { usb.close() }
                    "Restart request sent. The target device should leave ADB while it restarts."
                }.getOrElse { "Restart request failed: ${it.message ?: it.javaClass.simpleName}" }
            }
            conn = null; pump?.stop(); pump = null; selectedDevice = null
            status = "$label: $result"
            consoleOutput += "\n$ $label\n$result\n"
            busy = false
        }
    }

    fun refreshDiagnostics() {
        runShellCommand("getprop ro.product.model; echo '---'; getprop ro.product.manufacturer; echo '---'; getprop ro.build.version.release; echo '---'; getprop ro.build.fingerprint; echo '---'; getprop ro.board.platform; echo '---'; getprop ro.serialno; echo '---'; getprop ro.build.version.sdk; echo '---'; getprop ro.build.version.security_patch", "Device diagnostic summary")
        val c = conn ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val info = runCatching { DeviceInfoReader.read(c) }.getOrNull()
            withContext(Dispatchers.Main) { deviceInfo = info }
        }
    }

    fun disconnect() {
        runCatching { pump?.stop() }
        runCatching { conn?.disconnect() }
        conn = null; pump = null; selectedDevice = null; deviceInfo = null
        usb.close()
        status = "Disconnected"
    }

    fun filteredModels() = DeviceModels.reference.filter {
        (brandFilter == "All" || it.brand.equals(brandFilter, true)) &&
            (modelQuery.isBlank() || it.code.contains(modelQuery, true) || it.marketName.contains(modelQuery, true) || it.brand.contains(modelQuery, true))
    }

    fun clearConsole() { consoleOutput = "" }
    override fun onCleared() { disconnect(); usb.stopMonitoring() }
}
