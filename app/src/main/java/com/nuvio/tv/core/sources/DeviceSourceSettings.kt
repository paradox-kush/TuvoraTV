package com.nuvio.tv.core.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeviceSourcesEntryPoint {
    fun sources(): DeviceSources
    fun profiles(): com.nuvio.tv.core.profile.ProfileManager
    fun auth(): com.nuvio.tv.core.auth.AuthManager
    fun addons(): com.nuvio.tv.domain.repository.AddonRepository
    fun servers(): com.nuvio.tv.data.mediaserver.ServerRepository
    fun sync(): com.nuvio.tv.core.sync.StartupSyncService
    fun playlists(): com.nuvio.tv.data.local.XtreamAccountStore
}

@Composable
fun DeviceSourcesHost() {
    val context = LocalContext.current
    val deps = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext,DeviceSourcesEntryPoint::class.java) }
    val auth by deps.auth().authState.collectAsStateWithLifecycle()
    if (auth == com.nuvio.tv.domain.model.AuthState.Loading) return
    val profile by deps.profiles().activeProfileId.collectAsStateWithLifecycle()
    val engine = remember(auth) { deps.sources().engine() }
    val view by engine.state.collectAsStateWithLifecycle()
    val requested by DeviceSources.showSettings.collectAsStateWithLifecycle()
    var visible by remember(engine) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val addons by remember(deps) { deps.addons().getInstalledAddons() }.collectAsStateWithLifecycle(emptyList())
    val playlists by deps.playlists().accounts.collectAsStateWithLifecycle(emptyList())
    val servers by deps.servers().uiState.collectAsStateWithLifecycle()
    LaunchedEffect(engine,profile,lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            engine.refresh(profile)
            awaitCancellation()
        }
    }
    LaunchedEffect(engine) {
        engine.state.map { it.availabilityEpoch }.distinctUntilChanged().collect {
            DeviceSources.changed()
            (deps.addons() as? com.nuvio.tv.data.repository.AddonRepositoryImpl)?.onDeviceSourcesChanged()
            deps.servers().onDeviceSourcesChanged()
        }
    }
    LaunchedEffect(view.mode) { if (view.mode in listOf("select","replaced","storage_error")) visible = true }
    LaunchedEffect(requested) { if (requested) { visible = true; engine.refresh(profile) } }
    LaunchedEffect(engine,profile,addons,playlists,servers.connections) {
        if (engine.state.value.mode == "local") {
            val old = engine.state.value.disk.sources.values.flatten()
            fun source(kind: String, ref: String, label: String, enabled: Boolean) = DeviceSourceDescriptor(
                old.firstOrNull { it.kind == kind && it.sourceRef == ref }?.sourceId ?: java.util.UUID.randomUUID().toString(),kind,ref,label,enabled,profile)
            engine.localSources(profile,addons.map { source("addon",it.baseUrl,it.displayName,it.enabled) } +
                servers.connections.map { source("media_server","${it.providerId}|${it.remoteServerId}|${it.remoteUserId}",it.name,it.enabled) } +
                playlists.map { source("iptv",it.id,it.name,it.enabled) })
        }
    }
    if (visible) DeviceSourceSettings(engine,profile,auth !is com.nuvio.tv.domain.model.AuthState.FullAccount, onSetupComplete = { deps.sync().requestSyncNow() }) { visible = false; DeviceSources.showSettings.value = false }
}

@Composable
fun DeviceSourceSettings(engine: DeviceSourceEngine, profile: Int, localOnly: Boolean, onSetupComplete: () -> Unit = {}, onClose: () -> Unit) {
    val view by engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(com.nuvio.tv.core.auth.currentDeviceClientMetadata(context).deviceName.take(120).ifBlank { "My TV" }) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var managing by remember(engine) { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<DeviceSetupSummary?>(null) }
    var savedSetupsExpanded by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val continueFocus = remember { FocusRequester() }
    val choosing = view.mode != "unavailable" && !localOnly && (!view.ready || managing)
    LaunchedEffect(choosing,view.busy) {
        if (choosing && !view.busy) continueFocus.requestFocus()
    }

    fun action(block: suspend () -> Unit) {
        if (actionBusy) return
        actionBusy = true
        scope.launch { try { error = null; block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Could not save your choice. Please retry." }
            finally { actionBusy = false } }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp).widthIn(max = 520.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (choosing && !view.ready) "Set up this TV" else "Sources on this device",style = MaterialTheme.typography.titleLarge)
                if (view.ready) Text("${view.disk.binding?.label ?: "Local setup"} · Profile $profile")
                val status = error ?: view.message ?: when {
                    view.busy -> "Syncing…"
                    view.disk.pending.isNotEmpty() -> "Saved on this device · waiting to sync"
                    else -> null
                }
                status?.let { Text(it) }
                if (view.mode != "unavailable") {
                    if (view.ready) {
                        for (kind in listOf("addon","media_server","iptv")) {
                            Text(when(kind) { "addon" -> "Addons"; "iptv" -> "IPTV playlists"; else -> "Media servers" },style = MaterialTheme.typography.titleMedium)
                            view.disk.sources[profile].orEmpty().filter { it.kind == kind }.forEach { source ->
                                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(source.name)
                                        if (!source.accountEnabled) Text("Disabled for all devices")
                                    }
                                    Switch(modifier = Modifier.semantics { contentDescription = source.name + " on this device" },
                                        checked = source.accountEnabled && engine.choice(profile,source.sourceId),
                                        enabled = source.accountEnabled, onCheckedChange = { on -> action {
                                            engine.setChoice(profile,source.sourceId,on)
                                            engine.refresh(profile,refreshSources = false,forceDelta = false)
                                        } })
                                }
                                val pending = view.disk.pending.firstOrNull { it.profile == profile && it.source == source.sourceId && it.conflict }
                                if (pending != null) {
                                    Text("Changed elsewhere. Choose which setting to keep.")
                                    Row {
                                        TextButton(onClick = { action { engine.resolve(profile,source.sourceId,false) } }) { Text("Use saved choice") }
                                        TextButton(onClick = { action { engine.resolve(profile,source.sourceId,true); engine.refresh(profile,false,false) } }) { Text("Apply my choice") }
                                    }
                                }
                            }
                        }
                    }
                    if (view.mode == "storage_error" && localOnly) {
                        Text("Saved choices could not be read. Starting again uses account defaults.")
                        Button(onClick = { action { engine.startLocal() } }) { Text("Start local setup again") }
                    }
                    if (view.ready && !localOnly) TextButton(onClick = { managing = !managing }) { Text("Manage saved setups") }
                    if (choosing) {
                        Text("Use your account’s addons, servers and IPTV.")
                        if (!editingName) Text(name)
                        if (editingName) OutlinedTextField(value = name,onValueChange = { name = it.take(120) },
                            label = { Text("Device name") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); continueFocus.requestFocus() }))
                        Button(modifier = Modifier.focusRequester(continueFocus),
                            enabled = !view.busy && !actionBusy && name.isNotBlank(),onClick = {
                                keyboard?.hide()
                                action { if (view.disk.binding?.registration != null) engine.refresh(profile) else engine.select(name,profile = profile); if (engine.state.value.ready) { onSetupComplete(); onClose() } }
                            }) { Text(if (view.disk.binding?.registration != null) "Retry" else "Continue") }
                        TextButton(onClick = { editingName = !editingName }) { Text(if (editingName) "Keep this name" else "Change device name") }
                        if (view.setups.isNotEmpty()) {
                            TextButton(onClick = { savedSetupsExpanded = !savedSetupsExpanded }) {
                                Text(if (savedSetupsExpanded) "Hide saved settings" else "Use saved device settings")
                            }
                            if (savedSetupsExpanded) view.setups.forEach { setup ->
                                Column {
                                    Text(setup.label)
                                    Row {
                                        TextButton(enabled = !view.busy && !actionBusy,onClick = { keyboard?.hide(); replacing = setup }) { Text("Restore") }
                                        TextButton(enabled = !view.busy && !actionBusy && name.isNotBlank(),onClick = { action {
                                            engine.select(name,setup,true,profile); if (engine.state.value.ready) { onSetupComplete(); onClose() }
                                        } }) { Text("Copy to this device") }
                                    }
                                }
                            }
                        }
                    }
                }
                Row {
                    if (view.ready || status != null) TextButton(enabled = !view.busy && !actionBusy,
                        onClick = { action { engine.refresh(profile) } }) { Text("Refresh") }
                    if (view.ready || view.mode == "unavailable") TextButton(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
    replacing?.let { setup ->
        AlertDialog(onDismissRequest = { replacing = null },title = { Text("Replace previous installation?") },
            text = { Text("Restore ${setup.label}. Its previous installation will stop saving changes to this setup. Source sign-ins are not copied.") },
            confirmButton = { TextButton(onClick = { replacing = null; action { engine.select(setup.label,setup,false,profile); if (engine.state.value.ready) { onSetupComplete(); onClose() } } }) { Text("Restore setup") } },
            dismissButton = { TextButton(onClick = { replacing = null }) { Text("Cancel") } })
    }
}
