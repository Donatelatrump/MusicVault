package com.musicvault.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicvault.app.R
import com.musicvault.app.ui.theme.VaultColors
import com.musicvault.app.updates.AppUpdateViewModel
import java.io.File

@Composable
internal fun AppUpdateHost(vm: AppUpdateViewModel, online: Boolean, enabled: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var showDownloadProgress by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(state.downloading) { if (state.downloading) showDownloadProgress = true }
    var pendingInstall by rememberSaveable { mutableStateOf<String?>(null) }
    fun install(path: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            vm.installationMessage(context.getString(R.string.update_install_failed))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val path = pendingInstall
        pendingInstall = null
        if (path != null && context.packageManager.canRequestPackageInstalls()) install(path)
        else vm.installationMessage(context.getString(R.string.update_permission_declined))
    }
    LaunchedEffect(state.readyPath) {
        val path = state.readyPath ?: return@LaunchedEffect
        vm.consumeReady()
        if (context.packageManager.canRequestPackageInstalls()) install(path)
        else {
            pendingInstall = path
            try {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } catch (e: Exception) {
                pendingInstall = null
                vm.installationMessage(context.getString(R.string.update_install_failed))
            }
        }
    }
    DisposableEffect(lifecycle, online, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && online && enabled) vm.check()
        }
        lifecycle.lifecycle.addObserver(observer)
        if (online && enabled && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.check()
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    if (enabled && state.downloading && showDownloadProgress) {
        AlertDialog(
            onDismissRequest = { showDownloadProgress = false },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.update_downloading, state.progress))
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { showDownloadProgress = false }) {
                Text(stringResource(R.string.update_continue))
            } }
        )
    }
    val release = state.release
    if (enabled && state.prompt && release != null) {
        AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.update_available_body, release.versionName))
                    Text(stringResource(R.string.update_optional_body))
                }
            },
            confirmButton = { TextButton(onClick = vm::download) { Text(stringResource(R.string.update_now)) } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.update_later)) } }
        )
    }
}

@Composable
internal fun AppUpdateSettings(vm: AppUpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsSection(stringResource(R.string.update_settings_title)) {
        Text(stringResource(R.string.update_installed, vm.installedVersion), color = VaultColors.TextPrimary)
        state.release?.let {
            Text(stringResource(R.string.update_available_version, it.versionName), color = VaultColors.Accent)
        }
        state.message?.let { Text(it, color = VaultColors.TextSecondary) }
        if (state.downloading) {
            Text(stringResource(R.string.update_downloading, state.progress), color = VaultColors.TextSecondary)
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !state.checking && !state.downloading, onClick = { vm.check(manual = true) }) {
                Text(stringResource(if (state.checking) R.string.update_checking else R.string.update_check))
            }
            if (state.release != null) {
                Button(enabled = !state.downloading && !state.checking, onClick = vm::download) {
                    Text(stringResource(R.string.update_now))
                }
            }
        }
        Text(stringResource(R.string.update_optional_body), color = VaultColors.TextSecondary)
    }
}
