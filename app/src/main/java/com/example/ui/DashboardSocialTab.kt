package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Orvexa Orbit social panel: friends, blocks, groups and Minecraft access cards. */
@Composable
fun DashboardSocialTab(viewModel: AccountViewModel) {
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val blocks by viewModel.serverBlocks.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val minecraftSessions by viewModel.minecraftSessions.collectAsStateWithLifecycle()
    var friendEmail by remember { mutableStateOf("") }
    var blockEmail by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var sessionTitle by remember { mutableStateOf("") }
    var sessionAddress by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshConnectedAccountData() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Social", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Your shared Orvexa network and Minecraft access controls.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 12.dp)) }

        SocialCard(Icons.Rounded.PersonAdd, "Friends") {
            OutlinedTextField(friendEmail, { friendEmail = it }, label = { Text("Friend email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.requestFriend(friendEmail) { _, message -> status = message; friendEmail = "" } }, enabled = friendEmail.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Send request") }
            friends.forEach { relation ->
                val profile = relation.user
                val profileName = profile?.firstNameCamel ?: profile?.firstNameSnake ?: ""
                val name = profileName.ifBlank { profile?.email ?: relation.requesterId }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$name · ${relation.status}", modifier = Modifier.weight(1f))
                    if (relation.status == "pending" && relation.direction == "incoming") TextButton(onClick = { viewModel.acceptFriend(relation.requesterId) { _, message -> status = message } }) { Text("Accept") }
                    else TextButton(onClick = { viewModel.removeFriendServer(if (relation.direction == "outgoing") relation.targetId else relation.requesterId) { _, message -> status = message } }) { Text("Remove") }
                }
            }
        }
        SocialCard(Icons.Rounded.Block, "Blocked accounts") {
            OutlinedTextField(blockEmail, { blockEmail = it }, label = { Text("Account email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.blockUserServer(blockEmail) { _, message -> status = message; blockEmail = "" } }, enabled = blockEmail.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Block") }
            blocks.forEach { relation -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(relation.user?.email ?: relation.targetId, modifier = Modifier.weight(1f)); TextButton(onClick = { viewModel.unblockUserServer(relation.targetId) { _, message -> status = message } }) { Text("Unblock") } } }
        }
        SocialCard(Icons.Rounded.Group, "Groups") {
            OutlinedTextField(groupName, { groupName = it }, label = { Text("New group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.createServerGroup(groupName, "") { _, message -> status = message; groupName = "" } }, enabled = groupName.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Create group") }
            groups.forEach { group -> Text("${group.name} · ${group.memberIds.size} members", modifier = Modifier.padding(top = 8.dp)) }
        }
        SocialCard(Icons.Rounded.SportsEsports, "Minecraft sessions") {
            OutlinedTextField(sessionTitle, { sessionTitle = it }, label = { Text("Session name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sessionAddress, { sessionAddress = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = { viewModel.createMinecraftAccessSession(sessionTitle, sessionAddress, 25565, "invite") { _, message -> status = message; sessionTitle = ""; sessionAddress = "" } }, enabled = sessionTitle.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Create invite-only session") }
            minecraftSessions.forEach { session -> Text("${session.title} · ${session.address}:${session.port} · ${session.accessMode}", modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun SocialCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(16.dp)) {
            Row { Icon(icon, null, tint = Color(0xFF315DE5)); Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
