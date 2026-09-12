package org.example.overlay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.app.AppState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Логин, регистрация и карточка профиля (§3.1, фаза 1). */
@Composable
fun AccountScreen(state: AppState) {
    val session by state.session.collectAsState()
    val busy by state.busy.collectAsState()
    val message by state.accountMessage.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        ConsoleSection("Account") {
            if (session == null) {
                LoginForm(state, busy)
            } else {
                ProfileCard(state)
            }

            message?.let { text ->
                Spacer(Modifier.height(12.dp))
                Text(text, color = OverlayColors.Warn, fontSize = 13.sp)
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LoginForm(state: AppState, busy: Boolean) {
    val settings by state.settings.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(settings.rememberPassword) }

    Column {
        Text(
            "Your username becomes the MCP profile name: 5–20 Latin letters, digits, _ and -.",
            color = OverlayColors.TextDim,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.width(320.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(320.dp),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
            Text("Remember password", color = OverlayColors.TextDim, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { state.login(username.trim(), password, rememberPassword) },
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                shape = RectangleShape,
            ) {
                Text("Sign in")
            }
            OutlinedButton(
                onClick = { state.register(username.trim(), password, rememberPassword) },
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                shape = RectangleShape,
            ) {
                Text("Register")
            }
        }
    }
}

@Composable
private fun ProfileCard(state: AppState) {
    val session by state.session.collectAsState()
    val profile by state.profile.collectAsState()
    val current = session ?: return

    Column {
        InfoRow("User", current.username)

        val linked = profile?.bungieLinked
        InfoRow(
            label = "Bungie",
            value = when (linked) {
                true -> "linked"
                false -> "not linked"
                null -> "unknown"
            },
            valueColor = when (linked) {
                true -> OverlayColors.Ok
                false -> OverlayColors.Warn
                null -> OverlayColors.TextDim
            },
            // Отвязка — тихая ссылка прямо у статуса, а не отдельная кнопка: действие
            // редкое и живёт там же, где написано «привязан».
            trailing = if (linked == true) {
                {
                    Text(
                        "unlink",
                        color = OverlayColors.TextDim,
                        fontSize = 13.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { state.unlinkBungie() },
                    )
                }
            } else {
                null
            },
        )
        profile?.bungieProfile?.displayName?.let { InfoRow("Bungie name", it) }

        // Свежесть данных: ассистент отвечает по последнему слепку инвентаря, и игроку видно,
        // насколько тот отстал от игры.
        if (linked == true) {
            val syncedAt = profile?.bungieProfile?.inventorySyncedAt
            InfoRow(
                label = "Sync",
                value = syncedAt?.let(::formatSyncedAt) ?: "not synced yet",
                valueColor = if (syncedAt != null) OverlayColors.Text else OverlayColors.TextDim,
            )
        }

        if (linked == false) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Until the account is linked, every tool except authorize will return an error.",
                color = OverlayColors.Warn,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { state.linkBungie() }, shape = RectangleShape) { Text("Link Bungie") }
        }

        val authUrl by state.authUrl.collectAsState()
        val polling by state.authPolling.collectAsState()
        if (authUrl != null) {
            Spacer(Modifier.height(12.dp))
            Text("Open the link and return — the connection will update automatically.", color = OverlayColors.Warn, fontSize = 13.sp)
            Text(authUrl.orEmpty(), color = OverlayColors.TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { state.openAuthorizationLink() }, shape = RectangleShape) {
                    Text("Open in browser")
                }
                // Опрос профиля живёт 5 минут; дальше проверяем по кнопке.
                OutlinedButton(
                    onClick = { state.startProfilePolling() },
                    enabled = !polling,
                    shape = RectangleShape,
                ) {
                    Text(if (polling) "Checking…" else "Check again")
                }
                OutlinedButton(onClick = { state.dismissAuthorizationPrompt() }, shape = RectangleShape) {
                    Text("Hide")
                }
            }
        }
        if (current.isExpiringSoon()) {
            Spacer(Modifier.height(8.dp))
            Text("Your session expires soon — sign in again before it does.", color = OverlayColors.Warn, fontSize = 12.sp)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { state.refreshAccount() }, shape = RectangleShape) { Text("Refresh") }
            OutlinedButton(onClick = { state.logout() }, shape = RectangleShape) { Text("Sign out") }
        }
    }
}

/** «2 ч назад (31.07 21:15)»: расстояние до сейчас — чтобы оценить свежесть без арифметики,
 * точное локальное время — чтобы свериться при желании. Непарсибельная строка уходит как есть. */
private fun formatSyncedAt(iso: String): String = runCatching {
    val instant = Instant.parse(iso)
    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    val relative = when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
    val local = DateTimeFormatter.ofPattern("dd.MM HH:mm")
        .format(instant.atZone(ZoneId.systemDefault()))
    "$relative ($local)"
}.getOrDefault(iso)

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = OverlayColors.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(200.dp), color = OverlayColors.TextDim, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp)
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}
