package com.omniroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.theme.ClayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorCard(
    combos: List<String>,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    onToast: (String) -> Unit = {},
    transport: (suspend (String, String) -> Triple<Int, String, String?>)? = null,
) {
    val colors = ClayTheme.colors
    var selectedCombo by remember { mutableStateOf(combos.firstOrNull() ?: "") }
    var expanded by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ClayCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Simulator Failover",
            color = colors.onSurface,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))

        // combo dropdown
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (combos.isNotEmpty()) expanded = it },
        ) {
            OutlinedTextField(
                value = selectedCombo,
                onValueChange = {},
                readOnly = true,
                label = { Text("Combo") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                combos.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c) },
                        onClick = {
                            selectedCombo = c
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        if (combos.isEmpty()) {
            Text(
                text = "Tidak ada combo tersedia",
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }

        ClayButton(
            enabled = combos.isNotEmpty() && !loading,
            onClick = {
                loading = true
                status = "Memvalidasi Rantai..."
                result = ""
                scope.launch {
                    val payload = """{"model":"$selectedCombo","messages":[{"role":"user","content":"${prompt.replace("\"", "\\\"")}"}],"stream":false}"""
                    val (code, body, err) = withContext(Dispatchers.IO) {
                        if (transport != null) {
                            transport("$baseUrl/v1/chat/completions", payload)
                        } else {
                            runCatching {
                                val conn = URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection
                                conn.requestMethod = "POST"
                                conn.connectTimeout = 30000
                                conn.readTimeout = 30000
                                conn.doOutput = true
                                conn.setRequestProperty("Content-Type", "application/json")
                                conn.outputStream.use { it.write(payload.toByteArray()) }
                                val rc = conn.responseCode
                                val stream = if (rc in 200..299) conn.inputStream else conn.errorStream
                                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                                Triple(rc, text, null as String?)
                            }.getOrElse { e -> Triple(0, "", e.message) }
                        }
                    }
                    loading = false
                    if (err != null) {
                        status = "Gagal: $err"
                        result = err ?: ""
                        return@launch
                    }
                    try {
                        val json = JSONObject(body)
                        if (code in 200..299) {
                            val content = json.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("message")
                                ?.optString("content", "") ?: ""
                            status = "Sukses: Terlayani"
                            result = content
                        } else {
                            val msg = json.optJSONObject("error")?.optString("message")
                                ?: json.optString("message", body)
                            status = "Gagal: $msg"
                            result = msg
                        }
                    } catch (e: Exception) {
                        status = "Gagal: ${e.message}"
                        result = body
                    }
                }
            },
        ) {
            Text(text = "Kirim Uji")
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                color = colors.onSurface,
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
        }
        if (result.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = result,
                color = colors.onSurface,
                fontSize = 13.sp,
            )
        }
    }
}
