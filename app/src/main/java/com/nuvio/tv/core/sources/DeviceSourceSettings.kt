package com.nuvio.tv.core.sources

import androidx.compose.foundation.layout.*
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
    if (visible) DeviceSourceSettings(engine,profile,auth !is com.nuvio.tv.domain.model.AuthState.FullAccount) { visible = false; DeviceSources.showSettings.value = false }
}

@Composable
fun DeviceSourceSettings(engine: DeviceSourceEngine, profile: Int, localOnly: Boolean, onClose: () -> Unit) {
    val view by engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("My TV") }
    var error by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var managing by remember(engine) { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<DeviceSetupSummary?>(null) }
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
                Text("Sources on this device",style = MaterialTheme.typography.titleLarge)
                Text("${view.disk.binding?.label ?: if (view.mode == "local") "Local setup" else "Choose a setup"} · Profile $profile")
                Text("Changes here affect only this device and profile. Your current video keeps playing.")
                Text(error ?: view.message ?: when {
                    view.mode == "local" -> "Saved only on this device · account setups are separate"
                    view.busy -> "Syncing…"
                    view.disk.pending.isNotEmpty() -> "Saved on this device · waiting to sync"
                    view.ready -> "Saved to account"
                    else -> "Choose a saved setup or start a new one."
                })
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
                    if (!localOnly && (!view.ready || managing)) {
                        OutlinedTextField(value = name,onValueChange = { name = it.take(120) },label = { Text("Device name") })
                        if (view.setups.isNotEmpty()) Text("Restore preferences only. Sign in to media servers again if asked.")
                        view.setups.forEach { setup ->
                            Column {
                                Text(setup.label)
                                Row {
                                    TextButton(enabled = !view.busy,onClick = { replacing = setup }) { Text("Restore") }
                                    TextButton(enabled = !view.busy && name.isNotBlank(),onClick = { action { engine.select(name,setup,true,profile) } }) { Text("Copy to new device") }
                                }
                            }
                        }
                        Text("A new setup uses the account defaults shown in your source list.")
                        Button(enabled = !view.busy && name.isNotBlank(),onClick = { action { engine.select(name,profile = profile) } }) { Text("Use account defaults on a new setup") }
                    }
                }
                Row {
                    TextButton(enabled = !view.busy,onClick = { action { engine.refresh(profile) } }) { Text("Retry / refresh") }
                    TextButton(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
    replacing?.let { setup ->
        AlertDialog(onDismissRequest = { replacing = null },title = { Text("Replace previous installation?") },
            text = { Text("Restore ${setup.label}. Its previous installation will stop saving changes to this setup. Source sign-ins are not copied.") },
            confirmButton = { TextButton(onClick = { replacing = null; action { engine.select(setup.label,setup,false,profile) } }) { Text("Restore setup") } },
            dismissButton = { TextButton(onClick = { replacing = null }) { Text("Cancel") } })
    }
}
