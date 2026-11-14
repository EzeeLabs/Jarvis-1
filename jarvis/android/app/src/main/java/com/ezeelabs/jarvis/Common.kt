@file:OptIn(ExperimentalMaterial3Api::class)

package com.ezeelabs.jarvis

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val Accent = Color(0xFF4FC3F7)
val Good = Color(0xFF7FDBCA)
val Bad = Color(0xFFFF6B6B)
val Muted = Color(0xFF8B9BB0)

private val Scheme = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF00212E), background = Color(0xFF0B0F14), onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF121922), onSurface = Color(0xFFE6EDF3), surfaceVariant = Color(0xFF1A2430), onSurfaceVariant = Muted,
    error = Bad, secondary = Good, outline = Color(0xFF2B3A4A)
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

fun inr(v: Double): String = "₹" + String.format(Locale("en", "IN"), "%,.0f", v)

private val timeFmt = DateTimeFormatter.ofPattern("h:mm:ss a").withZone(ZoneId.systemDefault())
private val dateFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault())
fun clock(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms))
fun stamp(iso: String?): String = if (iso.isNullOrEmpty() || iso == "null") "" else try { dateFmt.format(Instant.parse(iso)) } catch (e: Exception) { iso }
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    content = { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
)

@Composable
fun Choices(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(o) }) }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false) = OutlinedTextField(
    value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text)
)

@Composable
fun FormDialog(title: String, onDismiss: () -> Unit, onOk: () -> Unit, okEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { TextButton(onClick = onOk, enabled = okEnabled) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

@Composable
fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column { Text(label, color = Muted, style = MaterialTheme.typography.labelMedium); Text(value, color = color, style = MaterialTheme.typography.titleLarge) }
}
