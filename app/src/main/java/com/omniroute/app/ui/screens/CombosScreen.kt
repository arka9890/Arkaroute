package com.omniroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ClientKey
import com.omniroute.app.config.ComboJson
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.config.ProviderConfigJson
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.components.ClayChip
import com.omniroute.app.ui.theme.ClayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CombosScreen(
    state: GatewayUiState,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    onSaveConfigAndRestart: (GatewayConfigJson, AppMeta) -> Unit = { _, _ -> },
    onToast: (String) -> Unit = {},
) {
    val colors = ClayTheme.colors
    var segment by remember { mutableStateOf("combos") } // combos | keys
    var showEditor by remember { mutableStateOf(false) }
    var editingCombo by remember { mutableStateOf<ComboJson?>(null) }
    var showAddKey by remember { mutableStateOf(false) }

    val resolvedBaseUrl = baseUrl.ifBlank {
        val core = state.core
        val host = if (core.bind == "0.0.0.0") "127.0.0.1" else core.bind
        "http://$host:${core.port}"
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // Segmented switch
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ClayChip(
                selected = segment == "combos",
                onClick = { segment = "combos" },
                label = "Model Combos",
            )
            ClayChip(
                selected = segment == "keys",
                onClick = { segment = "keys" },
                label = "Client API Keys",
            )
        }

        if (segment == "combos") {
            val activeCount = state.core.combos.count { it.enabled }
            ClayCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Rantai Failover Cerdas",
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$activeCount Aktif",
                        color = colors.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ClayButton(onClick = {
                    editingCombo = null
                    showEditor = true
                }) {
                    Text(text = "+ Combo")
                }
            }

            state.core.combos.forEach { combo ->
                ComboCard(
                    combo = combo,
                    defaultCombo = state.core.defaultCombo,
                    healthProviders = state.health?.providers.orEmpty().associateBy { it.id },
                    onToggle = { checked ->
                        val updated = state.core.combos.map {
                            if (it.name == combo.name) it.copy(enabled = checked) else it
                        }
                        onSaveConfigAndRestart(state.core.copy(combos = updated), state.app)
                    },
                    onClick = {
                        editingCombo = combo
                        showEditor = true
                    },
                )
            }

            SimulatorCard(
                combos = state.core.combos.map { it.name },
                baseUrl = resolvedBaseUrl,
                onToast = onToast,
            )

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Enkripsi AES-256-GCM Hardware Android Keystore",
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        } else {
            // Client API Keys
            ClayCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Client API Keys",
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    ClayButton(onClick = { showAddKey = true }) {
                        Text(text = "+ Tambah Key")
                    }
                }
            }

            state.app.clientKeys.forEach { key ->
                ClientKeyRow(
                    key = key,
                    onCopy = { onToast("Disalin") },
                    onDelete = {
                        val newKeys = state.app.clientKeys.filterNot {
                            it.label == key.label && it.secret == key.secret
                        }
                        val newGateway = state.core.gatewayKeys.filterNot { it == key.secret }
                        onSaveConfigAndRestart(
                            state.core.copy(gatewayKeys = newGateway),
                            state.app.copy(clientKeys = newKeys),
                        )
                    },
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Enkripsi AES-256-GCM Hardware Android Keystore",
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
    }

    if (showEditor) {
        ComboEditorScreen(
            combo = editingCombo,
            providers = state.core.providers,
            onSave = { newCombo ->
                val combos = if (editingCombo == null) {
                    state.core.combos + newCombo
                } else {
                    state.core.combos.map { if (it.name == editingCombo!!.name) newCombo else it }
                }
                onSaveConfigAndRestart(state.core.copy(combos = combos), state.app)
                showEditor = false
            },
            onCancel = { showEditor = false },
        )
    }

    if (showAddKey) {
        AddKeyDialog(
            onDismiss = { showAddKey = false },
            onSave = { label, secret ->
                val newCore = state.core.copy(gatewayKeys = state.core.gatewayKeys + secret)
                val newApp = state.app.copy(clientKeys = state.app.clientKeys + ClientKey(label = label, secret = secret))
                onSaveConfigAndRestart(newCore, newApp)
                showAddKey = false
            },
        )
    }
}

@Composable
private fun ComboCard(
    combo: ComboJson,
    defaultCombo: String?,
    healthProviders: Map<String, com.omniroute.app.data.ProviderStat>,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val colors = ClayTheme.colors
    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = combo.name,
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            val badge = if (defaultCombo == combo.name) "Default" else "${combo.steps.size}"
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = badge,
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Switch(checked = combo.enabled, onCheckedChange = { onToggle(it) })
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            combo.steps.forEachIndexed { index, step ->
                val stat = healthProviders[step.provider]
                val healthLine = when (stat?.healthy) {
                    true -> "Sehat"
                    false -> "Tidak sehat"
                    null -> "—"
                }
                Text(
                    text = "${index + 1}. ${step.provider} • ${step.model}",
                    color = colors.onSurface,
                    fontSize = 13.sp,
                )
                Text(
                    text = "   $healthLine",
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
            if (combo.steps.isEmpty()) {
                Text(
                    text = "Belum ada step",
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ClayButton(onClick = onClick) {
            Text(text = "Edit")
        }
    }
}

@Composable
private fun ClientKeyRow(
    key: ClientKey,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ClayTheme.colors
    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${key.label} · ••••${key.secret.takeLast(4)}",
                color = colors.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            ClayButton(onClick = onCopy) {
                Text(text = "Salin")
            }
            Spacer(Modifier.width(8.dp))
            ClayButton(onClick = onDelete) {
                Text(text = "Hapus")
            }
        }
    }
}

@Composable
private fun AddKeyDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = label, onValueChange = { label = it }, label = { Text("Label") })
                TextField(value = secret, onValueChange = { secret = it }, label = { Text("Secret") })
            }
        },
        confirmButton = {
            TextButton(onClick = { if (label.isNotBlank() && secret.isNotBlank()) onSave(label, secret) }) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}
