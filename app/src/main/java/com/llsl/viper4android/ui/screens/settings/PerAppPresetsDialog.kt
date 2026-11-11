package com.llsl.viper4android.ui.screens.settings

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.llsl.viper4android.R
import com.llsl.viper4android.data.model.Preset
import com.llsl.viper4android.ui.components.DialogCard
import com.llsl.viper4android.ui.components.DialogEmptyState
import com.llsl.viper4android.ui.components.DialogList
import com.llsl.viper4android.ui.components.DialogListItemRow
import com.llsl.viper4android.ui.components.RowDivider
import com.llsl.viper4android.ui.components.ToggleRow
import com.llsl.viper4android.ui.components.UiDimens
import com.llsl.viper4android.ui.screens.main.InstalledAppInfo

@Composable
fun PerAppPresetsDialog(
    perAppPresets: Map<String, String>,
    presets: List<Preset>,
    onSelectPreset: (String, String?) -> Unit,
    loadInstalledApps: ((List<InstalledAppInfo>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var selectedAppForPreset by remember { mutableStateOf<InstalledAppInfo?>(null) }

    LaunchedEffect(Unit) {
        loadInstalledApps { installedApps = it }
    }

    if (selectedAppForPreset != null) {
        val app = selectedAppForPreset!!
        val currentPreset = perAppPresets[app.packageName]
        PresetPickerDialog(
            app = app,
            currentPreset = currentPreset,
            presets = presets,
            onPresetChosen = { chosen ->
                onSelectPreset(app.packageName, chosen)
                selectedAppForPreset = null
            },
            onDismiss = { selectedAppForPreset = null },
        )
    }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.per_app_presets_title))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = UiDimens.Medium),
                    placeholder = { Text(stringResource(R.string.per_app_presets_search)) },
                    singleLine = true,
                )
                val apps = installedApps
                if (apps == null) {
                    DialogEmptyState(text = stringResource(R.string.per_app_presets_loading))
                } else {
                    val all = apps.sortedBy { it.label.lowercase() }
                    val filtered =
                        all.filter { app ->
                            (showSystemApps || !app.isSystemApp) &&
                                (
                                    query.isBlank() ||
                                        app.label.contains(query, ignoreCase = true) ||
                                        app.packageName.contains(query, ignoreCase = true)
                                )
                        }
                    DialogCard {
                        ToggleRow(
                            label = stringResource(R.string.per_app_presets_show_system),
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                        )
                        RowDivider()
                        DialogList {
                            itemsIndexed(filtered, key = { _, app -> app.packageName }) { index, app ->
                                val assignedPreset = perAppPresets[app.packageName]
                                PerAppRow(
                                    app = app,
                                    assignedPreset = assignedPreset,
                                    onClick = { selectedAppForPreset = app },
                                )
                                if (index != filtered.lastIndex) {
                                    RowDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun PerAppRow(
    app: InstalledAppInfo,
    assignedPreset: String?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon: Drawable? =
        remember(app.packageName) {
            try {
                context.packageManager.getApplicationIcon(app.packageName)
            } catch (_: Exception) {
                null
            }
        }

    DialogListItemRow(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(UiDimens.AppIcon),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = assignedPreset ?: stringResource(R.string.per_app_presets_default),
                style = MaterialTheme.typography.bodySmall,
                color = if (assignedPreset != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = onClick,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(
                text = assignedPreset ?: stringResource(R.string.per_app_presets_default),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PresetPickerDialog(
    app: InstalledAppInfo,
    currentPreset: String?,
    presets: List<Preset>,
    onPresetChosen: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultLabel = stringResource(R.string.per_app_presets_default)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${app.label}: ${stringResource(R.string.per_app_presets_select)}")
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = currentPreset == null,
                                    onClick = { onPresetChosen(null) },
                                )
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentPreset == null,
                            onClick = { onPresetChosen(null) },
                        )
                        Text(
                            text = defaultLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    RowDivider()
                }

                items(presets, key = { it.id }) { preset ->
                    val isSelected = currentPreset == preset.name
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    onClick = { onPresetChosen(preset.name) },
                                )
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onPresetChosen(preset.name) },
                        )
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

