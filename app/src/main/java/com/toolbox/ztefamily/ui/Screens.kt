package com.toolbox.ztefamily.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.toolbox.ztefamily.data.DeviceModels

private val diagnosticActions = listOf(
    "Battery / health" to "dumpsys battery; dumpsys health",
    "Memory / RAM" to "cat /proc/meminfo",
    "Storage" to "df -h",
    "CPU / architecture" to "cat /proc/cpuinfo; getprop ro.product.cpu.abi",
    "Kernel" to "uname -a",
    "Android / Build" to "getprop",
    "Display size / density" to "wm size; wm density",
    "USB debugging" to "settings get global adb_enabled",
    "Network" to "ip addr; ip route",
    "Installed packages" to "pm list packages -f",
    "Recent system log" to "logcat -d -t 300"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolboxApp(vm: ToolboxViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var darkMode by remember { mutableStateOf<Boolean?>(null) }
    val titles = listOf("Dashboard", "Devices", "ADB", "Diagnostics", "Apps", "Models", "Settings")
    val dark = darkMode ?: androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("ZTE Family Toolbox") }, actions = { Text("OTG", modifier = Modifier.padding(end = 16.dp), color = MaterialTheme.colorScheme.primary) }) }) { padding ->
            Column(Modifier.padding(padding)) {
                ScrollableTabRow(selectedTabIndex = tab) {
                    titles.forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
                }
                when (tab) {
                    0 -> DashboardScreen(vm) { tab = it }
                    1 -> DeviceScreen(vm)
                    2 -> ConsoleScreen(vm)
                    3 -> DiagnosticsScreen(vm)
                    4 -> AppManagerScreen(vm)
                    5 -> ModelLookupScreen(vm)
                    else -> SettingsScreen(darkMode) { darkMode = it }
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(vm: ToolboxViewModel, navigate: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device management", style = MaterialTheme.typography.headlineSmall)
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(vm.status, style = MaterialTheme.typography.titleMedium)
            Text(vm.deviceInfo?.let { "${it.manufacturer} ${it.model} · Android ${it.androidVersion}" } ?: "Connect a phone using USB OTG and USB debugging")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { navigate(1) }) { Text("Manage devices") }; OutlinedButton(onClick = { vm.refreshDevices() }) { Text("Scan USB") } }
        } }
        Text("Quick tools", style = MaterialTheme.typography.titleLarge)
        listOf("ADB Console" to 2, "Diagnostics" to 3, "Application Manager" to 4, "Model Database" to 5).forEach { (name, index) ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { navigate(index) }) { Text(name, Modifier.padding(16.dp)) }
        }
        Text("ADB requires USB debugging to be enabled on the target and the authorization prompt to be accepted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DeviceScreen(vm: ToolboxViewModel) {
    LaunchedEffect(Unit) { vm.refreshDevices() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("USB OTG device manager", style = MaterialTheme.typography.titleLarge)
        Text(vm.status, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshDevices() }, enabled = !vm.busy) { Text("Scan / Refresh") }
            OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(vm.devices) { entry -> ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.titleMedium)
                    Text("USB ${entry.device.vendorId.toString(16)}:${entry.device.productId.toString(16)} · ${entry.device.interfaceCount} interfaces", style = MaterialTheme.typography.bodySmall)
                    Text(entry.interfaceSummary, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.connectTo(entry) }, enabled = !vm.busy && entry.adbAvailable) { Text(if (entry.adbAvailable) "Connect via ADB" else "ADB unavailable") }
                }
            } }
        }
        vm.deviceInfo?.let { info -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text("Connected device", style = MaterialTheme.typography.titleMedium)
            InfoLine("Model", info.model); InfoLine("Manufacturer", info.manufacturer); InfoLine("Android", info.androidVersion); InfoLine("Platform", info.chipset); InfoLine("Serial", info.serial); InfoLine("Build", info.buildFingerprint)
            InfoLine("API level", info.apiLevel)
            InfoLine("Security patch", info.securityPatch)
            val known = DeviceModels.lookup(info.model)
            if (known != null) InfoLine("Commercial name", "${known.marketName} (${known.brand})")
            else InfoLine("Model identification", "Unknown model code — not in the local reference database")
        } } }
    }
}

@Composable
private fun InfoLine(label: String, value: String) { Text("$label: ${value.ifBlank { "Unknown / not exposed" }}", style = MaterialTheme.typography.bodySmall) }

@Composable
fun ConsoleScreen(vm: ToolboxViewModel) {
    var cmd by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("ADB shell", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f)); TextButton(onClick = { vm.clearConsole() }) { Text("Clear") }; TextButton(onClick = { val text = vm.consoleOutput; if (text.isNotEmpty()) clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("Copy") } }
        Text(vm.status, style = MaterialTheme.typography.bodySmall)
        if (vm.commandHistory.isNotEmpty()) {
            Text("Recent commands", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(vm.commandHistory.takeLast(8).reversed()) { previous ->
                    AssistChip(onClick = { cmd = previous }, label = { Text(previous.take(22)) })
                }
            }
        }
        Text(vm.consoleOutput.ifEmpty { "No output yet. Connect a device first." }, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = cmd, onValueChange = { cmd = it }, modifier = Modifier.weight(1f), label = { Text("Shell command") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { vm.runShellCommand(cmd); cmd = "" }))
            Button(onClick = { vm.runShellCommand(cmd); cmd = "" }, enabled = !vm.busy) { Text("Run") }
        }
    }
}

@Composable
fun DiagnosticsScreen(vm: ToolboxViewModel) {
    var pendingReboot by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Device diagnostics", style = MaterialTheme.typography.titleLarge)
        Text("Results are read from the connected device using ADB. Some values may be restricted by Android.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshDiagnostics() }, enabled = !vm.busy) { Text("Refresh report") }
            OutlinedButton(onClick = {
                val report = vm.consoleOutput.ifBlank { "No diagnostic output yet." }
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "ZTE Family Toolbox diagnostic report")
                    .putExtra(android.content.Intent.EXTRA_TEXT, report)
                context.startActivity(android.content.Intent.createChooser(send, "Share diagnostic report"))
            }) { Text("Export / Share") }
        }
        Text("Read-only checks", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(diagnosticActions) { (name, command) ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { vm.runDiagnostic(name, command) }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name); Text("Run", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Text("Device restart utilities", style = MaterialTheme.typography.titleMedium)
        listOf("Restart normally" to "reboot", "Restart to recovery" to "reboot recovery", "Restart to bootloader" to "reboot bootloader").forEach { (label, command) ->
            OutlinedButton(onClick = { pendingReboot = label to command }, modifier = Modifier.fillMaxWidth(), enabled = !vm.busy) { Text(label) }
        }
        Text("Restart commands interrupt the target device and may require OEM permission or root.", style = MaterialTheme.typography.bodySmall)
    }
    pendingReboot?.let { (label, command) ->
        AlertDialog(
            onDismissRequest = { pendingReboot = null },
            title = { Text("Confirm device restart") },
            text = { Text("$label? The USB ADB session will disconnect. Only continue if you own or are authorized to administer this device.") },
            confirmButton = { TextButton(onClick = { pendingReboot = null; vm.requestDeviceReboot(label, command) }) { Text("Restart") } },
            dismissButton = { TextButton(onClick = { pendingReboot = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun AppManagerScreen(vm: ToolboxViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Application manager", style = MaterialTheme.typography.titleLarge)
        Text("Read-only package inventory. App names and version metadata depend on the target Android build.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.runDiagnostic("Third-party packages", "pm list packages -3 -f") }, enabled = !vm.busy) { Text("List user-installed apps") }
        OutlinedButton(onClick = { vm.runDiagnostic("All packages", "pm list packages -f") }, enabled = !vm.busy) { Text("List all packages") }
        OutlinedButton(onClick = { vm.runDiagnostic("Package versions", "pm list packages -3 | while read p; do n=${'$'}{p#package:}; echo ===${'$'}n; dumpsys package ${'$'}n | grep -m1 versionName; done") }, enabled = !vm.busy) { Text("Package versions") }
        Text("View results in the ADB Console tab.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ModelLookupScreen(vm: ToolboxViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("ZTE / Nubia / RedMagic database", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = vm.modelQuery, onValueChange = { vm.modelQuery = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search model code or name") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "ZTE", "Nubia", "RedMagic").forEach { brand -> FilterChip(selected = vm.brandFilter == brand, onClick = { vm.brandFilter = brand }, label = { Text(brand) }) } }
        val matches = vm.filteredModels()
        Text("${matches.size} entries · Unknown codes are not guessed", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            items(matches) { model -> ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Column(Modifier.padding(12.dp)) { Text(model.marketName, style = MaterialTheme.typography.titleMedium); Text("${model.brand} · ${model.code}", style = MaterialTheme.typography.bodySmall) } } }
        }
    }
}


@Composable
private fun SettingsScreen(darkMode: Boolean?, onThemeChange: (Boolean?) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = darkMode == null, onClick = { onThemeChange(null) }, label = { Text("System") })
            FilterChip(selected = darkMode == false, onClick = { onThemeChange(false) }, label = { Text("Light") })
            FilterChip(selected = darkMode == true, onClick = { onThemeChange(true) }, label = { Text("Dark") })
        }
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        Text("The host phone must support USB OTG. The target phone must expose the standard ADB USB interface, have USB debugging enabled, and accept this app's RSA authorization prompt.")
        Text("Security", style = MaterialTheme.typography.titleMedium)
        Text("Commands run as the target Android shell user. Root-only functions and security bypasses are not included. Reboot operations require explicit confirmation.")
        Text("Version 0.2.0 · Minimum Android 8.0 (API 26)", style = MaterialTheme.typography.bodySmall)
    }
}
