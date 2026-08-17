@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.User
import com.example.data.Message
import com.example.data.UpdateManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

// Sealed class for Navigation routes
sealed class Screen(val route: String) {
    object AccountChooser : Screen("account_chooser")
    object SignIn : Screen("sign_in")
    object RegisterName : Screen("register_name")
    object RegisterBirthGender : Screen("register_birth_gender")
    object RegisterPassword : Screen("register_password")
    object RegisterTerms : Screen("register_terms")
    object Dashboard : Screen("dashboard")
}

@Composable
fun GoogleHeader(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String = ""
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // OrvexaAuth Beta stylized modern logo
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "OrvexaAuth Beta",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (subtitle.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
fun GoogleCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder(),
        content = content
    )
}

@Composable
fun LanguageSwitcher(viewModel: AccountViewModel) {
    val language by viewModel.language.collectAsStateWithLifecycle()
    TextButton(onClick = { viewModel.setLanguage(if (language == "ru") "en" else "ru") }) {
        Text(if (language == "ru") "EN" else "RU")
    }
}

fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("OrvexaAuth", value))
}

// 1. Account Chooser Screen (Clean, Client-Only Login Selection)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AccountChooserScreen(
    viewModel: AccountViewModel,
    onNavigate: (Screen) -> Unit
) {
    val users by viewModel.allUsers.collectAsStateWithLifecycle()
    val lang by viewModel.language.collectAsStateWithLifecycle()
    val context = LocalContext.current


    // Action Dialog States for Selected Account
    var selectedActionUser by remember { mutableStateOf<User?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = viewModel.t("select_account") ?: "Select Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    LanguageSwitcher(viewModel = viewModel)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                GoogleHeader(
                    title = viewModel.t("signin"),
                    subtitle = viewModel.t("select_account")
                )

                Spacer(modifier = Modifier.height(16.dp))

                GoogleCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        if (users.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Rounded.AccountBox,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = viewModel.t("no_accounts_server"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = viewModel.t("select_account"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Text(
                                    text = "Hold for actions / Удерживайте",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }

                            users.take(3).forEach { user ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .combinedClickable(
                                            enabled = true,
                                            onLongClick = { selectedActionUser = user },
                                            onClick = {
                                                viewModel.selectLoginUser(user)
                                                onNavigate(Screen.SignIn)
                                            }
                                        )
                                        .alpha(1f)
                                        .padding(12.dp)
                                        .testTag("account_item_${user.email}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val resolvedColor = if (user.avatarColor == -12543232 || user.avatarColor == 0) {
                                        val index = kotlin.math.abs(user.email.hashCode()) % viewModel.avatarColors.size
                                        Color(viewModel.avatarColors[index])
                                    } else {
                                        Color(user.avatarColor)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(resolvedColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = user.firstName.take(1).uppercase(),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${user.firstName} ${user.lastName}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = user.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            IconButton(
                                                onClick = { copyToClipboard(context, user.email) },
                                                modifier = Modifier.size(18.dp),
                                                enabled = true
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ContentCopy,
                                                    contentDescription = "Copy Email",
                                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Options menu button
                                    IconButton(
                                        onClick = { selectedActionUser = user },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = "Account Actions",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (true) {
                            Button(
                                onClick = {
                                    viewModel.setLoginEmail("")
                                    viewModel.setLoginPassword("")
                                    onNavigate(Screen.SignIn)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("use_another_account_button"),
                                colors = ButtonDefaults.outlinedButtonColors(),
                                enabled = true
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(viewModel.t("signin"))
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { onNavigate(Screen.RegisterName) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("create_account_button"),
                                enabled = true
                            ) {
                                Text(viewModel.t("register") ?: "Create account")
                            }
                        } else {
                            Text(
                                text = "OrvexaAuth Beta is unavailable. Check your internet connection and try again.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    // Action Menu Dialog for selected user - Only "Delete Cached Profile"
    if (selectedActionUser != null) {
        val user = selectedActionUser!!
        AlertDialog(
            onDismissRequest = { selectedActionUser = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val resolvedColor = if (user.avatarColor == -12543232 || user.avatarColor == 0) {
                        val index = kotlin.math.abs(user.email.hashCode()) % viewModel.avatarColors.size
                        Color(viewModel.avatarColors[index])
                    } else {
                        Color(user.avatarColor)
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(resolvedColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = user.firstName.take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Actions: ${user.firstName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Delete local profile cache for account: ${user.email}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            selectedActionUser = null
                            viewModel.removeLocalAccountCache(user)
                            android.widget.Toast.makeText(context, "Removed profile and cleared local session cache.", android.widget.Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Cached Profile / Удалить")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedActionUser = null }) {
                    Text("Close / Отмена")
                }
            }
        )
    }
}

// 2. Sign In Screen (Password check)
@Composable
fun SignInScreen(
    viewModel: AccountViewModel,
    onNavigate: (Screen) -> Unit,
    onLoginSuccess: () -> Unit
) {
    val email by viewModel.loginEmail.collectAsStateWithLifecycle()
    val password by viewModel.loginPassword.collectAsStateWithLifecycle()
    val error by viewModel.loginError.collectAsStateWithLifecycle()
    val lang by viewModel.language.collectAsStateWithLifecycle()
    val requireKeyProtect by viewModel.requireKeyProtect.collectAsStateWithLifecycle()
    val keyProtect by viewModel.loginKeyProtect.collectAsStateWithLifecycle()

    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            GoogleHeader(
                title = viewModel.t("signin"),
                subtitle = viewModel.t("welcome_subtitle")
            )

            Spacer(modifier = Modifier.height(24.dp))

            GoogleCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { viewModel.setLoginEmail(it) },
                        label = { Text(viewModel.t("enter_email")) },
                        leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_email_input"),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { viewModel.setLoginPassword(it) },
                        label = { Text(viewModel.t("enter_password") ?: "Enter password") },
                        leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                        trailingIcon = {
                            val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(image, contentDescription = if (passwordVisible) "Hide password" else "Show password")
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_password_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (requireKeyProtect) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = keyProtect,
                            onValueChange = { viewModel.setLoginKeyProtect(it) },
                            label = { Text("Key Protect") },
                            leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().testTag("login_key_protect_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            viewModel.useSavedCredential(
                                context = context,
                                onLoaded = { savedUsername, savedPassword ->
                                    viewModel.setLoginEmail(savedUsername)
                                    viewModel.setLoginPassword(savedPassword)
                                },
                                onUnavailable = { message ->
                                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Use saved password")
                    }

                    if (error != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (error!!.contains("Connection", ignoreCase = true) || error!!.contains("timeout", ignoreCase = true) || error!!.contains("refused", ignoreCase = true) || error!!.contains("unreachable", ignoreCase = true)) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = viewModel.t("connection_tip"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { onNavigate(Screen.AccountChooser) }) {
                            Text(viewModel.t("back") ?: "Back")
                        }

                        Button(
                            onClick = {
                                viewModel.performLogin {
                                    onLoginSuccess()
                                }
                            },
                            enabled = true,
                            modifier = Modifier.testTag("login_submit_button")
                        ) {
                            Text(viewModel.t("next") ?: "Next")
                        }
                    }
                }
            }
        }

    }
}

// 3. Register Name Screen
@Composable
fun RegisterNameScreen(
    viewModel: AccountViewModel,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit
) {
    val firstName by viewModel.regFirstName.collectAsStateWithLifecycle()
    val lastName by viewModel.regLastName.collectAsStateWithLifecycle()
    val error by viewModel.regError.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GoogleHeader(
            title = viewModel.t("register"),
            subtitle = viewModel.t("first_name_label")
        )

        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(progress = { 0.15f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        Spacer(modifier = Modifier.height(20.dp))

        GoogleCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { viewModel.setRegNames(it, lastName) },
                    label = { Text(viewModel.t("first_name_label")) },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reg_first_name_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = lastName,
                    onValueChange = { viewModel.setRegNames(firstName, it) },
                    label = { Text(viewModel.t("last_name_label")) },
                    leadingIcon = { Icon(Icons.Rounded.PersonOutline, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reg_last_name_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text(viewModel.t("signin"))
                    }

                    Button(
                        onClick = {
                            if (viewModel.validateNames()) {
                                viewModel.generateSuggestions()
                                onNavigate(Screen.RegisterBirthGender)
                            }
                        },
                        modifier = Modifier.testTag("reg_name_next_button")
                    ) {
                        Text(viewModel.t("next"))
                    }
                }
            }
        }
    }
}

// 4. Register Birth & Gender Screen
@Composable
fun RegisterBirthGenderScreen(
    viewModel: AccountViewModel,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit
) {
    val day by viewModel.regBirthDay.collectAsStateWithLifecycle()
    val month by viewModel.regBirthMonth.collectAsStateWithLifecycle()
    val year by viewModel.regBirthYear.collectAsStateWithLifecycle()
    val gender by viewModel.regGender.collectAsStateWithLifecycle()
    val error by viewModel.regError.collectAsStateWithLifecycle()

    val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    val genders = listOf("Female", "Male", "Rather not say", "Custom")

    var monthExpanded by remember { mutableStateOf(false) }
    var genderExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GoogleHeader(
            title = viewModel.t("personal_info"),
            subtitle = viewModel.t("birth_date")
        )

        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(progress = { 0.3f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        Spacer(modifier = Modifier.height(20.dp))

        GoogleCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(viewModel.t("birthday"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Day
                    OutlinedTextField(
                        value = day,
                        onValueChange = { viewModel.setRegBirthInfo(it, month, year, gender) },
                        label = { Text(viewModel.t("day")) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reg_birth_day"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Month Dropdown
                    Box(modifier = Modifier.weight(1.5f)) {
                        OutlinedButton(
                            onClick = { monthExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("reg_birth_month_dropdown"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(month, color = MaterialTheme.colorScheme.onSurface)
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(
                            expanded = monthExpanded,
                            onDismissRequest = { monthExpanded = false }
                        ) {
                            months.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        viewModel.setRegBirthInfo(day, m, year, gender)
                                        monthExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Year
                    OutlinedTextField(
                        value = year,
                        onValueChange = { viewModel.setRegBirthInfo(day, month, it, gender) },
                        label = { Text(viewModel.t("year")) },
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("reg_birth_year"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(viewModel.t("gender"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { genderExpanded = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("reg_gender_dropdown"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(gender, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(
                        expanded = genderExpanded,
                        onDismissRequest = { genderExpanded = false }
                    ) {
                        genders.forEach { g ->
                            DropdownMenuItem(
                                text = { Text(g) },
                                onClick = {
                                    viewModel.setRegBirthInfo(day, month, year, g)
                                    genderExpanded = false
                                }
                            )
                        }
                    }
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text(viewModel.t("back"))
                    }

                    Button(
                        onClick = {
                            if (viewModel.validateBirthInfo()) {
                                onNavigate(Screen.RegisterPassword)
                            }
                        },
                        modifier = Modifier.testTag("reg_birth_next_button")
                    ) {
                        Text(viewModel.t("next"))
                    }
                }
            }
        }
    }
}

// 6. Register Password Screen
@Composable
fun RegisterPasswordScreen(
    viewModel: AccountViewModel,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit
) {
    val password by viewModel.regPassword.collectAsStateWithLifecycle()
    val confirmPassword by viewModel.regConfirmPassword.collectAsStateWithLifecycle()
    val error by viewModel.regError.collectAsStateWithLifecycle()

    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GoogleHeader(
            title = viewModel.t("new_password_req"),
            subtitle = ""
        )

        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(progress = { 0.65f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        Spacer(modifier = Modifier.height(20.dp))

        GoogleCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { viewModel.setRegPassword(it, confirmPassword) },
                    label = { Text(viewModel.t("enter_password")) },
                    leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(image, contentDescription = if (passwordVisible) "Hide password" else "Show password")
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reg_password_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { viewModel.setRegPassword(password, it) },
                    label = { Text(viewModel.t("confirm")) },
                    leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reg_confirm_password_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text(viewModel.t("back"))
                    }

                    Button(
                        onClick = {
                            if (viewModel.validatePasswordStep()) {
                                onNavigate(Screen.RegisterTerms)
                            }
                        },
                        modifier = Modifier.testTag("reg_password_next_button")
                    ) {
                        Text(viewModel.t("next"))
                    }
                }
            }
        }
    }
}

// 8. Register Terms & Privacy Screen
@Composable
fun RegisterTermsScreen(
    viewModel: AccountViewModel,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
    onRegisterSuccess: () -> Unit
) {
    val error by viewModel.regError.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GoogleHeader(
            title = viewModel.t("privacy_terms_title"),
            subtitle = viewModel.t("privacy_terms_desc")
        )

        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(progress = { 1.0f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        Spacer(modifier = Modifier.height(20.dp))

        GoogleCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = viewModel.t("agreement_title"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = viewModel.t("agreement_body"),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp
                        )
                    }
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (error!!.contains("Connection", ignoreCase = true) || error!!.contains("timeout", ignoreCase = true) || error!!.contains("refused", ignoreCase = true) || error!!.contains("unreachable", ignoreCase = true)) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = viewModel.t("connection_tip"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text(viewModel.t("back"))
                    }

                    Button(
                        onClick = {
                            viewModel.performRegistration {
                                onRegisterSuccess()
                            }
                        },
                        enabled = true,
                        modifier = Modifier.testTag("reg_terms_agree_button")
                    ) {
                        Text(viewModel.t("i_agree"))
                    }
                }
            }
        }
    }
}

// 9. Main Google Dashboard Screen
@Composable
fun DashboardScreen(
    viewModel: AccountViewModel,
    onSignOut: () -> Unit
) {
    val user by viewModel.loggedInUser.collectAsStateWithLifecycle()
    val lang by viewModel.language.collectAsStateWithLifecycle()
    var activeTab by remember { mutableIntStateOf(0) }
    var availableUpdate by remember { mutableStateOf<UpdateManager.ReleaseUpdate?>(null) }

    LaunchedEffect(Unit) {
        availableUpdate = UpdateManager.check()
    }

    LaunchedEffect(user) {
        if (user == null) {
            onSignOut()
        }
    }

    LaunchedEffect(user?.id) {
        if (user != null) {
            viewModel.refreshConnectedAccountData()
        }
    }

    // Guard if user is null
    if (user == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val u = user!!

    val currentContext = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val resolvedColor = if (u.avatarColor == -12543232 || u.avatarColor == 0) {
                            val index = kotlin.math.abs(u.email.hashCode()) % viewModel.avatarColors.size
                            Color(viewModel.avatarColors[index])
                        } else {
                            Color(u.avatarColor)
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(resolvedColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = u.firstName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "OrvexaAuth Beta Account",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                actions = {
                    availableUpdate?.let { update ->
                        IconButton(
                            onClick = { UpdateManager.downloadAndOpenInstaller(currentContext, update) },
                            modifier = Modifier.testTag("dashboard_update_button")
                        ) {
                            Icon(Icons.Rounded.SystemUpdate, contentDescription = "Update OrvexaAuth")
                        }
                    }
                    IconButton(
                        onClick = { viewModel.logout(); onSignOut() },
                        modifier = Modifier.testTag("dashboard_signout_button")
                    ) {
                        Icon(Icons.Rounded.ExitToApp, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    modifier = Modifier.testTag("tab_home")
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Rounded.Person, contentDescription = "Personal Info") },
                    label = { Text(viewModel.t("tab_profile")) },
                    modifier = Modifier.testTag("tab_personal_info")
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Rounded.Security, contentDescription = "Security") },
                    label = { Text(viewModel.t("tab_security")) },
                    modifier = Modifier.testTag("tab_security")
                )
                NavigationBarItem(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    icon = { Icon(Icons.Rounded.Chat, contentDescription = viewModel.t("tab_messages")) },
                    label = { Text(viewModel.t("tab_messages")) },
                    modifier = Modifier.testTag("tab_messages")
                )
                NavigationBarItem(
                    selected = activeTab == 4,
                    onClick = { activeTab = 4 },
                    icon = { Icon(Icons.Rounded.Group, contentDescription = "Social") },
                    label = { Text("Social") },
                    modifier = Modifier.testTag("tab_social")
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (activeTab) {
                0 -> DashboardHomeTab(user = user!!, viewModel = viewModel, onNavigateToTab = { activeTab = it })
                1 -> DashboardPersonalInfoTab(user = user!!, viewModel = viewModel)
                2 -> DashboardSecurityTab(user = user!!, viewModel = viewModel, onSignOut = onSignOut)
                3 -> DashboardMessagesTab(viewModel = viewModel)
                4 -> DashboardSocialTab(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun DashboardHomeTab(
    user: User,
    viewModel: AccountViewModel,
    onNavigateToTab: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Big avatar
        val resolvedColor = if (user.avatarColor == -12543232 || user.avatarColor == 0) {
            val index = kotlin.math.abs(user.email.hashCode()) % viewModel.avatarColors.size
            Color(viewModel.avatarColors[index])
        } else {
            Color(user.avatarColor)
        }
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(resolvedColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.firstName.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = viewModel.t("account_home_kicker"),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF315DE5),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )

        Text(
            text = "${viewModel.t("account_home_greeting")}, ${user.firstName}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Text(
            text = user.email,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Info card 1
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .clickable { onNavigateToTab(1) },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = Color(0xFF1A73E8),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = viewModel.t("personal_info"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = viewModel.t("personal_info_desc"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
        }

        // Info card 2
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .clickable { onNavigateToTab(2) },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = Color(0xFFEA4335),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = viewModel.t("security_recommendations"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = viewModel.t("security_desc"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
        }

    }
}

@Composable
fun DashboardPersonalInfoTab(
    user: User,
    viewModel: AccountViewModel
) {
    var editMode by remember { mutableStateOf(false) }

    var draftFirstName by remember(user) { mutableStateOf(user.firstName) }
    var draftLastName by remember(user) { mutableStateOf(user.lastName) }
    var draftBirthDate by remember(user) { mutableStateOf(user.birthDate) }
    var draftGender by remember(user) { mutableStateOf(user.gender) }

    var updateError by remember { mutableStateOf<String?>(null) }
    var updateSuccess by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Personal info",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium
            )

            Button(
                onClick = {
                    if (editMode) {
                        // Save changes
                        viewModel.updateProfile(
                            firstName = draftFirstName,
                            lastName = draftLastName,
                            birthDate = draftBirthDate,
                            gender = draftGender,
                            onSuccess = {
                                editMode = false
                                updateSuccess = true
                                updateError = null
                            },
                            onError = {
                                updateError = it
                                updateSuccess = false
                            }
                        )
                    } else {
                        editMode = true
                        updateSuccess = false
                    }
                },
                modifier = Modifier.testTag("edit_profile_toggle_button")
            ) {
                Icon(
                    imageVector = if (editMode) Icons.Rounded.Save else Icons.Rounded.Edit,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (editMode) "Save" else "Edit")
            }
        }

        Text(
            text = "Info about you and your preferences in OrvexaAuth Beta services",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (updateSuccess) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = "Profile updated successfully!",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (updateError != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = updateError!!,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        GoogleCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Basic info",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (editMode) {
                    OutlinedTextField(
                        value = draftFirstName,
                        onValueChange = { draftFirstName = it },
                        label = { Text("First name") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("edit_first_name_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = draftLastName,
                        onValueChange = { draftLastName = it },
                        label = { Text("Last name") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("edit_last_name_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = draftBirthDate,
                        onValueChange = { draftBirthDate = it },
                        label = { Text("Birthday (YYYY-Month-DD)") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = draftGender,
                        onValueChange = { draftGender = it },
                        label = { Text("Gender") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    InfoRow(label = "NAME", value = "${user.firstName} ${user.lastName}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    InfoRow(label = "BIRTHDAY", value = user.birthDate)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    InfoRow(label = "GENDER", value = user.gender)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GoogleCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Device Access Security",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                InfoRow(label = "KEY PROTECT", value = user.keyProtect.ifEmpty { "Not Generated" })
                Text(
                    text = "Use this key to authorize access from a new device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(label = "MAC ADDRESS", value = user.macAddress.ifEmpty { "Unknown" })
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(label = "LAST IP ADDRESS", value = user.ipAddress.ifEmpty { "Unknown" })
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(label = "DATA QUOTA", value = "${user.dataQuotaMb} MB")
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun DashboardSecurityTab(
    user: User,
    viewModel: AccountViewModel,
    onSignOut: () -> Unit
) {
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var newPassword by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionSuccess by remember { mutableStateOf<String?>(null) }
    var deletePassword by remember { mutableStateOf("") }

    // Passcode lock states
    val passcode by viewModel.appPasscode.collectAsStateWithLifecycle()
    val lang by viewModel.language.collectAsStateWithLifecycle()
    var showPasscodeSetup by remember { mutableStateOf(false) }
    var setupPasscodeInput by remember { mutableStateOf("") }
    var setupPasscodeConfirm by remember { mutableStateOf("") }
    var setupPasscodeError by remember { mutableStateOf<String?>(null) }
    val activeSessions by viewModel.activeSessions.collectAsStateWithLifecycle()
    val securityEvents by viewModel.securityEvents.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    var qrRequestId by remember { mutableStateOf("") }
    var showTotpDialog by remember { mutableStateOf(false) }
    var totpSecret by remember { mutableStateOf<String?>(null) }
    var totpCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = viewModel.t("tab_security") ?: "Security",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (actionSuccess != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = actionSuccess!!,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        GoogleCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Signing in to OrvexaAuth Beta",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showPasswordDialog = true; actionSuccess = null; actionError = null }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Password", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text("Change your OrvexaAuth Beta password safely", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Passcode Protection
        GoogleCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = viewModel.t("passcode_title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = viewModel.t("enable_passcode"),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (passcode.isNotEmpty()) "Locked with active passcode" else "No passcode lock set",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = passcode.isNotEmpty(),
                        onCheckedChange = { checked ->
                            if (checked) {
                                setupPasscodeInput = ""
                                setupPasscodeConfirm = ""
                                setupPasscodeError = null
                                showPasscodeSetup = true
                            } else {
                                viewModel.setAppPasscode("")
                                actionSuccess = "App passcode lock disabled"
                            }
                        }
                    )
                }

                if (passcode.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            setupPasscodeInput = ""
                            setupPasscodeConfirm = ""
                            setupPasscodeError = null
                            showPasscodeSetup = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(viewModel.t("change_passcode"))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GoogleCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Server security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Protect every OrvexaAuth session, not only this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Two-factor authentication", fontWeight = FontWeight.SemiBold)
                        Text("Set up a TOTP authenticator before approving sensitive actions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        viewModel.beginTotpSetup { response, error ->
                            if (response != null) { totpSecret = response.secret; showTotpDialog = true } else actionError = error
                        }
                    }) { Text("Set up") }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text("Active devices: ${activeSessions.size}", fontWeight = FontWeight.SemiBold)
                activeSessions.take(3).forEach { session ->
                    Text("${session.deviceName.ifBlank { session.deviceType }} · ${session.tokenHint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                TextButton(onClick = {
                    viewModel.revokeAllServerSessions { success, message ->
                        actionSuccess = message
                        if (success) onSignOut()
                    }
                }) { Text("Close every server session") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GoogleCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Approve desktop login", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Open an orvexaauth://qr/ link from a scanned code or paste its request ID here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                OutlinedTextField(value = qrRequestId, onValueChange = { qrRequestId = it }, label = { Text("QR request ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                Button(onClick = {
                    viewModel.approveQrLogin(qrRequestId.trim()) { success, message ->
                        if (success) { qrRequestId = ""; actionSuccess = message } else actionError = message
                    }
                }, enabled = qrRequestId.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Approve login") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GoogleCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Security activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (securityEvents.isEmpty()) Text("No server security events yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                securityEvents.take(4).forEach { event -> Text("${event.type.replace('_', ' ')} · ${formatDate(event.createdAt)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (notifications.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text("Notifications", fontWeight = FontWeight.SemiBold)
                    notifications.take(3).forEach { note -> Text(note.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp)) }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GoogleCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Danger zone",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showDeleteDialog = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Delete your local account", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        Text("Irreversibly erase your details from local SQLite storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (showTotpDialog) {
            AlertDialog(
                onDismissRequest = { showTotpDialog = false },
                title = { Text("Set up two-factor authentication") },
                text = {
                    Column {
                        Text("Add this secret to an authenticator app, then enter the current six-digit code.")
                        Text(totpSecret.orEmpty(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                        OutlinedTextField(value = totpCode, onValueChange = { totpCode = it.filter(Char::isDigit).take(6) }, label = { Text("Authenticator code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    }
                },
                confirmButton = { Button(onClick = { viewModel.confirmTotp(totpCode) { success, message -> if (success) { showTotpDialog = false; actionSuccess = message } else actionError = message } }, enabled = totpCode.length == 6) { Text("Enable") } },
                dismissButton = { TextButton(onClick = { showTotpDialog = false }) { Text("Cancel") } }
            )
        }

        // Change Password Dialog
        if (showPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showPasswordDialog = false },
                title = { Text("Change Password") },
                text = {
                    Column {
                        Text("Enter a new strong password (minimum 6 characters):", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = { Text("New Password") },
                            modifier = Modifier.fillMaxWidth().testTag("new_password_dialog_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (actionError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(actionError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.updatePassword(
                                newPass = newPassword,
                                onSuccess = {
                                    showPasswordDialog = false
                                    actionSuccess = "Password updated successfully!"
                                    newPassword = ""
                                    actionError = null
                                },
                                onError = {
                                    actionError = it
                                }
                            )
                        },
                        modifier = Modifier.testTag("dialog_password_confirm_button")
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPasswordDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // App Passcode Setup Dialog
        if (showPasscodeSetup) {
            AlertDialog(
                onDismissRequest = { showPasscodeSetup = false },
                title = { Text(viewModel.t("passcode_title") ?: "Setup Passcode") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Set a 4-digit numeric passcode to protect your application access.")

                        OutlinedTextField(
                            value = setupPasscodeInput,
                            onValueChange = { if (it.all { char -> char.isDigit() } && it.length <= 4) setupPasscodeInput = it },
                            label = { Text("4-digit Passcode") },
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = setupPasscodeConfirm,
                            onValueChange = { if (it.all { char -> char.isDigit() } && it.length <= 4) setupPasscodeConfirm = it },
                            label = { Text("Confirm Passcode") },
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        if (setupPasscodeError != null) {
                            Text(setupPasscodeError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (setupPasscodeInput.length != 4) {
                                setupPasscodeError = "Passcode must be exactly 4 digits"
                            } else if (setupPasscodeInput != setupPasscodeConfirm) {
                                setupPasscodeError = "Passcodes do not match"
                            } else {
                                viewModel.setAppPasscode(setupPasscodeInput)
                                showPasscodeSetup = false
                                actionSuccess = "App passcode updated successfully!"
                            }
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPasscodeSetup = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Permanent deletion requires the current password; failed requests leave
        // the authenticated session unchanged.
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false; deletePassword = "" },
                title = { Text(viewModel.t("delete_confirm_title")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(viewModel.t("delete_confirm_description"))
                        OutlinedTextField(
                            value = deletePassword,
                            onValueChange = { deletePassword = it; actionError = null },
                            label = { Text(viewModel.t("delete_password_label")) },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            isError = actionError != null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            viewModel.deleteAccount(
                                currentPassword = deletePassword,
                                onSuccess = {
                                    deletePassword = ""
                                    showDeleteDialog = false
                                    onSignOut()
                                },
                                onError = { actionError = it }
                            )
                        }
                    ) {
                        Text(viewModel.t("delete_confirm_action"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false; deletePassword = "" }) {
                        Text(viewModel.t("cancel"))
                    }
                }
            )
        }
    }
}

// 10. Dashboard Messenger Tab
@Composable
fun DashboardMessagesTab(
    viewModel: AccountViewModel
) {
    val user by viewModel.loggedInUser.collectAsStateWithLifecycle()
    if (user == null) return

    val chatPartners by viewModel.getChatPartnersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedPartner by remember { mutableStateOf<String?>(null) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var newChatEmail by remember { mutableStateOf("") }
    var newChatError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    if (selectedPartner == null) {
        // Chat List View
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = viewModel.t("tab_messages") ?: "Messages",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (chatPartners.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ChatBubbleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                text = "No active conversations",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "Tap the button below to start messaging.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(chatPartners) { partner ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPartner = partner },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = partner.take(1).uppercase(),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = partner,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Tap to chat",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteChat(partner)
                                            android.widget.Toast.makeText(context, "Chat deleted", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Delete chat",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        newChatEmail = ""
                        newChatError = null
                        showNewChatDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.AddComment, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Message")
                }
            }
        }
    } else {
        // Chat Thread View
        val messages by viewModel.getMessagesForPartner(selectedPartner!!).collectAsStateWithLifecycle(initialValue = emptyList())
        var textToSend by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedPartner = null }) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = selectedPartner!!.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedPartner!!,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Active Conversation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                var showChatMenu by remember { mutableStateOf(false) }
                val blockedUsers by viewModel.blockedUsers.collectAsStateWithLifecycle()
                val isBlocked = blockedUsers.contains(selectedPartner!!.trim().lowercase())

                Box {
                    IconButton(onClick = { showChatMenu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More chat options")
                    }
                    DropdownMenu(
                        expanded = showChatMenu,
                        onDismissRequest = { showChatMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isBlocked) "Unblock user" else "Block user") },
                            onClick = {
                                if (isBlocked) {
                                    viewModel.unblockUser(selectedPartner!!)
                                } else {
                                    viewModel.blockUser(selectedPartner!!)
                                    selectedPartner = null
                                }
                                showChatMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    if (isBlocked) Icons.Rounded.CheckCircle else Icons.Rounded.Block,
                                    contentDescription = null
                                )
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Delete local chat") },
                            onClick = {
                                viewModel.deleteChat(selectedPartner!!)
                                selectedPartner = null
                                showChatMenu = false
                            },
                            leadingIcon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // Message Bubble list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    val isMine = message.senderEmail == user!!.email
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Card(
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isMine) 16.dp else 4.dp,
                                bottomEnd = if (isMine) 4.dp else 16.dp
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.widthIn(max = 280.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = message.text,
                                    color = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val formattedTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.timestamp))
                                    Text(
                                        text = formattedTime,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.deleteMessage(message.id) },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete message",
                                            tint = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = textToSend,
                    onValueChange = { textToSend = it },
                    placeholder = { Text("Write message...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (textToSend.trim().isNotEmpty()) {
                            viewModel.sendMessage(
                                recipientEmail = selectedPartner!!,
                                text = textToSend.trim(),
                                onResult = { success, error ->
                                    if (!success) {
                                        android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                            textToSend = ""
                        }
                    },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }

    // New Chat dialog
    if (showNewChatDialog) {
        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text("New Message") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Enter the registered OrvexaAuth email address of the person you want to message:")
                    OutlinedTextField(
                        value = newChatEmail,
                        onValueChange = { newChatEmail = it },
                        label = { Text("Recipient Email") },
                        leadingIcon = { Icon(Icons.Rounded.AlternateEmail, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (newChatError != null) {
                        Text(newChatError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newChatEmail.trim().isEmpty()) {
                            newChatError = "Please enter an email"
                        } else if (newChatEmail.trim().lowercase() == user!!.email.lowercase()) {
                            newChatError = "You cannot send messages to yourself"
                        } else {
                            viewModel.sendMessage(
                                recipientEmail = newChatEmail.trim(),
                                text = "Hello! Let's chat.",
                                onResult = { success, error ->
                                    if (!success) {
                                        newChatError = error
                                    } else {
                                        selectedPartner = newChatEmail.trim()
                                        showNewChatDialog = false
                                    }
                                }
                            )
                        }
                    }
                ) {
                    Text("Start Conversation")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// 11. App Passcode Lock Overlay
@Composable
fun PasscodeLockScreen(
    viewModel: AccountViewModel,
    onUnlock: () -> Unit
) {
    val passcode by viewModel.appPasscode.collectAsStateWithLifecycle()
    val lang by viewModel.language.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = viewModel.t("passcode_enter") ?: "Enter Passcode",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Dots representing entered code length
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { index ->
                val filled = index < input.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (errorMsg != null) {
            Text(
                text = errorMsg!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Spacer(modifier = Modifier.height(20.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Custom Numeric Keyboard
        val buttons = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "Delete")
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            buttons.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    row.forEach { label ->
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    errorMsg = null
                                    when (label) {
                                        "C" -> input = ""
                                        "Delete" -> if (input.isNotEmpty()) {
                                            input = input.dropLast(1)
                                        }
                                        else -> {
                                            if (input.length < 4) {
                                                input += label
                                                if (input.length == 4) {
                                                    if (input == passcode) {
                                                        onUnlock()
                                                    } else {
                                                        errorMsg = viewModel.t("passcode_error") ?: "Incorrect Passcode"
                                                        input = ""
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (label == "Delete") {
                                Icon(Icons.Rounded.Backspace, contentDescription = "Delete")
                            } else {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* Legacy cloud storage implementation removed from the Android identity client.
    val files by viewModel.userFiles.collectAsStateWithLifecycle()
    val isLoading by viewModel.isStorageLoading.collectAsStateWithLifecycle()

    var showUploadDialog by remember { mutableStateOf(false) }
    var uploadName by remember { mutableStateOf("") }
    var uploadContent by remember { mutableStateOf("") }

    var selectedFileContent by remember { mutableStateOf<String?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadUserFiles()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CloudQueue,
                    contentDescription = null,
                    tint = Color(0xFF34A853),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cloud Folder Files")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "These files are physically stored on the server inside your account directory. Swipe, view, or manage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF34A853))
                    }
                } else if (files.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No files uploaded yet. Create your first text file below!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files) { file ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        isDownloading = true
                                        coroutineScope.launch {
                                            try {
                                                val user = viewModel.loggedInUser.value ?: return@launch
                                                val client = okhttp3.OkHttpClient()
                                                val serverUrl = viewModel.serverUrl.value
                                                val requestUrl = if (serverUrl.endsWith("/")) {
                                                    "${serverUrl}api/users/${user.id}/storage/${java.net.URLEncoder.encode(file.name, "UTF-8")}"
                                                } else {
                                                    "$serverUrl/api/users/${user.id}/storage/${java.net.URLEncoder.encode(file.name, "UTF-8")}"
                                                }
                                                val req = okhttp3.Request.Builder()
                                                    .url(requestUrl)
                                                    .build()
                                                withContext(Dispatchers.IO) {
                                                    client.newCall(req).execute().use { response ->
                                                        if (response.isSuccessful) {
                                                            val body = response.body?.string() ?: ""
                                                            withContext(Dispatchers.Main) {
                                                                selectedFileName = file.name
                                                                selectedFileContent = body
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                // ignore
                                            } finally {
                                                isDownloading = false
                                            }
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Article,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${formatSize(file.size)} • ${formatDate(file.updatedAt)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteUserFile(file.name) { success, msg ->
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Delete File",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
                Button(
                    onClick = { showUploadDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853))
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Upload File")
                }
            }
        }
    )

    if (showUploadDialog) {
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("Upload Text File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uploadName,
                        onValueChange = { uploadName = it },
                        label = { Text("File Name (e.g., config.json)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = uploadContent,
                        onValueChange = { uploadContent = it },
                        label = { Text("File Content") },
                        modifier = Modifier.fillMaxWidth().height(140.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (uploadName.trim().isNotEmpty()) {
                            viewModel.uploadUserFile(uploadName.trim(), uploadContent) { success, msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                if (success) {
                                    showUploadDialog = false
                                    uploadName = ""
                                    uploadContent = ""
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853))
                ) {
                    Text("Upload")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUploadDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedFileContent != null) {
        AlertDialog(
            onDismissRequest = { selectedFileContent = null; selectedFileName = null },
            title = { Text(selectedFileName ?: "File Content") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = selectedFileContent ?: "",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = { selectedFileContent = null; selectedFileName = null }) {
                    Text("Dismiss")
                }
            }
        )
    }
*/

fun formatDate(millis: Long): String {
    val date = java.util.Date(millis)
    val sdf = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
    return sdf.format(date)
}
