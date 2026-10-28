package io.github.carnager.jot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.carnager.jot.data.ApiException
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.data.ThemeMode
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun LoginScreen() {
    val prefs = Repo.settings.current
    var server by remember { mutableStateOf(prefs.server) }
    var user by remember { mutableStateOf(prefs.username) }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy || server.isBlank() || user.isBlank() || pass.isEmpty()) return
        busy = true; error = null
        scope.launch {
            try {
                Repo.signIn(server, user, pass)
            } catch (e: ApiException) {
                error = if (e.status == 401) "Wrong username or password" else e.message
            } catch (e: Exception) {
                error = "Can't reach the server: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandMark(72.dp)
                Spacer(Modifier.height(20.dp))
                Text("Jot", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("Notes and tasks, on your own server.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                OutlinedTextField(
                    server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Server") },
                    placeholder = { Text("jot.example.com") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPass) "Hide" else "Show")
                        }
                    },
                )
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (busy) "Signing in…" else "Sign in")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { Repo.useOffline() }) { Text("Use without a server") }
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, requestNotifications: () -> Unit) {
    val prefs by Repo.settings.prefs.collectAsState()
    val ctx = LocalContext.current
    var timeDialog by remember { mutableStateOf(false) }
    var signOut by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Group("Account") {
                    if (prefs.signedIn) {
                        Text(prefs.username, style = MaterialTheme.typography.titleMedium)
                        Text(prefs.server, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val status = when {
                            prefs.syncError.isNotEmpty() -> "Sync problem: ${prefs.syncError}"
                            prefs.lastSync > 0 -> "Synced " + Fmt.edited(prefs.lastSync).removePrefix("Edited ")
                            else -> "Not synced yet"
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = if (prefs.syncError.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { Repo.requestSync(0) }) { Text("Sync now") }
                            OutlinedButton(onClick = { signOut = true }) { Text("Sign out") }
                        }
                    } else {
                        Text("Offline only. Notes stay on this phone.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { Repo.settings.update { it.copy(offline = false) } }) { Text("Connect a server") }
                    }
                }
                Group("Reminders") {
                    val granted = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    SettingRow("Notifications", if (granted) "Allowed" else "Not allowed — reminders can't show") {
                        if (!granted) TextButton(onClick = {
                            requestNotifications()
                            ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
                        }) { Text("Allow") }
                    }
                    SettingRow("All-day tasks remind at", Fmt.time(prefs.allDayTime), onClick = { timeDialog = true })
                    Text("Snooze", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60, 120, 180).forEach { m ->
                            FilterChip(
                                selected = prefs.snoozeMinutes == m,
                                onClick = { Repo.settings.update { it.copy(snoozeMinutes = m) } },
                                label = { Text(if (m < 60) "$m min" else "${m / 60} h") },
                            )
                        }
                    }
                }
                Group("Appearance") {
                    ThemeSegments(listOf("System", "Light", "Dark"), prefs.theme.ordinal) { i ->
                        Repo.settings.update { it.copy(theme = ThemeMode.entries[i]) }
                    }
                    SettingRow("Use wallpaper colours", "Material You instead of Jot’s marigold") {
                        Switch(prefs.dynamicColor, { on -> Repo.settings.update { it.copy(dynamicColor = on) } })
                    }
                }
                Group("Quick capture") {
                    Text(
                        "Add the Jot tile to Quick Settings, long-press the launcher icon for shortcuts, or add the Today widget. " +
                            "Type naturally: “dentist fri 10am #health”, “water plants every 3 days”, “Shopping: milk, eggs”.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (timeDialog) TimeDialog(
        prefs.allDayTime, onDismiss = { timeDialog = false }, onAllDay = { timeDialog = false },
        onTime = { t: LocalTime -> timeDialog = false; Repo.settings.update { it.copy(allDayMinutes = t.hour * 60 + t.minute) } },
    )
    if (signOut) AlertDialog(
        onDismissRequest = { signOut = false },
        title = { Text("Sign out?") },
        text = { Text("Local copies are removed from this phone. Everything stays on the server.") },
        confirmButton = { TextButton(onClick = { signOut = false; Repo.signOut(keepData = false) }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { signOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(title: String, sub: String, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}
