package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.NetworkUserResponse

/**
 * Administrative controls are an explicitly opened, temporary surface. Its
 * Retrofit client and the operator token are memory-only and are destroyed as
 * soon as this dialog leaves composition.
 */
@Composable
fun AdminManagementDialog(
    viewModel: AccountViewModel,
    onDismiss: () -> Unit
) {
    val authorized by viewModel.adminAuthorized.collectAsStateWithLifecycle()
    val stats by viewModel.adminStats.collectAsStateWithLifecycle()
    val users by viewModel.adminUsers.collectAsStateWithLifecycle()
    val selectedSessions by viewModel.adminSelectedSessions.collectAsStateWithLifecycle()
    var temporaryToken by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingBanUser by remember { mutableStateOf<NetworkUserResponse?>(null) }
    var banReason by remember { mutableStateOf("") }
    var pendingDeleteUser by remember { mutableStateOf<NetworkUserResponse?>(null) }

    DisposableEffect(Unit) {
        onDispose { viewModel.endAdminSession() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxHeight().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Administration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (authorized) "Temporary operator session" else "Access requires a temporary operator token",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close administration")
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (!authorized) {
                    Text(
                        "The token is used only for this open panel. It is not saved on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = temporaryToken,
                        onValueChange = { temporaryToken = it; status = null },
                        label = { Text("Temporary administrator token") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    status?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.beginAdminSession(temporaryToken) { success, message ->
                                if (success) temporaryToken = ""
                                status = message
                            }
                        },
                        enabled = temporaryToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Verify and open controls") }
                } else {
                    stats?.let { summary ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                AdminMetric("Accounts", summary.users.toString())
                                AdminMetric("Sessions", summary.sessions.toString())
                                AdminMetric("Active", summary.activeSessions.toString())
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search by ID, email or name") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        ElevatedButton(
                            onClick = { viewModel.refreshAdminData(searchQuery) { success, message -> status = if (success) null else message } },
                            modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Rounded.Search, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Search") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { viewModel.refreshAdminData() { success, message -> status = if (success) null else message } }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                    status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

                    selectedSessions?.let { selected ->
                        Spacer(Modifier.height(10.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Sessions: ${selected.user.email}", fontWeight = FontWeight.Bold)
                                if (selected.sessions.isEmpty()) {
                                    Text("No active sessions returned.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    selected.sessions.take(5).forEach { session ->
                                        Text(
                                            "${session.deviceName.ifBlank { session.deviceType }} · ${session.appName} · ${session.tokenHint}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Accounts (${users.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(users, key = { it.id }) { user ->
                            AdminUserRow(
                                user = user,
                                onSessions = { viewModel.inspectAdminUserSessions(user.id) { success, message -> status = if (success) null else message } },
                                onBan = { pendingBanUser = user; banReason = user.banReason.orEmpty() },
                                onDelete = { pendingDeleteUser = user }
                            )
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("End administrator session") }
                }
            }
        }
    }

    pendingBanUser?.let { user ->
        val isBanned = user.isBanned
        AlertDialog(
            onDismissRequest = { pendingBanUser = null },
            title = { Text(if (isBanned) "Remove account block?" else "Block account?") },
            text = {
                Column {
                    Text(user.email)
                    if (!isBanned) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = banReason,
                            onValueChange = { banReason = it },
                            label = { Text("Reason (optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setAdminUserBan(user.id, !isBanned, banReason) { success, message ->
                        status = if (success) null else message
                        if (success) pendingBanUser = null
                    }
                }) { Text(if (isBanned) "Remove block" else "Block account") }
            },
            dismissButton = { TextButton(onClick = { pendingBanUser = null }) { Text("Cancel") } }
        )
    }

    pendingDeleteUser?.let { user ->
        AlertDialog(
            onDismissRequest = { pendingDeleteUser = null },
            title = { Text("Permanently delete account?") },
            text = { Text("${user.email} and its server records, sessions, social links and hosted sessions will be deleted. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAdminUser(user.id) { success, message ->
                            status = if (success) null else message
                            if (success) pendingDeleteUser = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteUser = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AdminMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AdminUserRow(
    user: NetworkUserResponse,
    onSessions: () -> Unit,
    onBan: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.email, fontWeight = FontWeight.SemiBold)
                    val fullName = listOfNotNull(user.firstNameCamel, user.firstNameSnake, user.lastNameCamel, user.lastNameSnake)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" ")
                    Text(
                        if (fullName.isBlank()) "ID: ${user.id}" else "$fullName · ID: ${user.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (user.isBanned) {
                    Text("BLOCKED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
            if (user.isBanned && !user.banReason.isNullOrBlank()) {
                Text(user.banReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSessions) { Text("Sessions") }
                TextButton(onClick = onBan) { Text(if (user.isBanned) "Unblock" else "Block") }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
