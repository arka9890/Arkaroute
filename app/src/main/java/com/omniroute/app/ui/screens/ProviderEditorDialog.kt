package com.omniroute.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.config.ProviderConfigJson
import com.omniroute.app.data.GatewayUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorDialog(
    state: GatewayUiState,
    onDismiss: () -> Unit,
    onSave: (GatewayConfigJson, AppMeta) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("OpenAiCompatible") }
    var kindExpanded by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }

    val existingIds = state.core.providers.map { it.id }
    val valid = id.isNotBlank() && baseUrl.isNotBlank() && id !in existingIds

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Tambah Provider") },
        text = {
            Column {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("id") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = kindExpanded,
                    onExpandedChange = { kindExpanded = !kindExpanded },
                ) {
                    OutlinedTextField(
                        value = if (kind == "OpenAiCompatible") "OpenAI-compatible" else "Anthropic",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("kind") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kindExpanded) },
                        modifier = Modifier
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = kindExpanded,
                        onDismissRequest = { kindExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("OpenAI-compatible") },
                            onClick = { kind = "OpenAiCompatible"; kindExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Anthropic") },
                            onClick = { kind = "Anthropic"; kindExpanded = false },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("base_url") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("api_key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = models,
                    onValueChange = { models = it },
                    label = { Text("models (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!valid) return@TextButton
                    val newProvider = ProviderConfigJson(
                        id = id,
                        kind = kind,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        models = models.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    )
                    val newCore = state.core.copy(providers = state.core.providers + newProvider)
                    onSave(newCore, state.app)
                },
                enabled = valid,
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}
