package com.omniroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniroute.app.config.ComboJson
import com.omniroute.app.config.ComboStepJson
import com.omniroute.app.config.ProviderConfigJson
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.theme.ClayTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboEditorScreen(
    combo: ComboJson? = null,
    providers: List<ProviderConfigJson> = emptyList(),
    onSave: (ComboJson) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val colors = ClayTheme.colors
    var name by remember { mutableStateOf(combo?.name ?: "") }
    val steps = remember { mutableStateListOf<ComboStepJson>().apply { combo?.steps?.let { addAll(it) } } }
    var expandedProviderIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (combo == null) "Combo Baru" else "Edit Combo",
                color = colors.onSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nama") },
            modifier = Modifier.fillMaxWidth(),
        )

        steps.forEachIndexed { index, step ->
            ClayCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Step ${index + 1}",
                    color = colors.onSurface,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                // provider dropdown
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = step.provider,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        providers.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.id) },
                                onClick = {
                                    steps[index] = step.copy(provider = p.id)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = step.model,
                    onValueChange = { steps[index] = step.copy(model = it) },
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClayButton(onClick = {
                        if (index > 0) {
                            val tmp = steps[index - 1]
                            steps[index - 1] = steps[index]
                            steps[index] = tmp
                        }
                    }) { Text("Naik") }
                    ClayButton(onClick = {
                        if (index < steps.lastIndex) {
                            val tmp = steps[index + 1]
                            steps[index + 1] = steps[index]
                            steps[index] = tmp
                        }
                    }) { Text("Turun") }
                    ClayButton(onClick = { steps.removeAt(index) }) { Text("Hapus") }
                }
            }
        }

        ClayButton(onClick = { steps.add(ComboStepJson(provider = "", model = "")) }) {
            Text(text = "+ Step")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClayButton(onClick = {
                onSave(ComboJson(name = name, steps = steps.toList(), enabled = combo?.enabled ?: true))
            }) {
                Text(text = "Simpan")
            }
            ClayButton(onClick = onCancel) {
                Text(text = "Batal")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
