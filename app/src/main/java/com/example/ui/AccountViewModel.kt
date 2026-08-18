package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.File
import java.security.MessageDigest

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val clientManager = NetAuthClientManager(application)
    private val credentialManager = CredentialManagerHelper(application)
    val userDao = UserDao(application)
    val messageDao = MessageDao(application)
    val allBannedHardware = userDao.getAllBannedHardware()

    val googleAccountEmail: StateFlow<String?> = MutableStateFlow(null)
    val googleAvatarUrl: StateFlow<String?> = MutableStateFlow(null)

    // OrvexaAuth Beta is public-API-only; no local backend or third-party auth fallback.

    private val _blockedUsers = MutableStateFlow<Set<String>>(clientManager.blockedUsers)
    val blockedUsers: StateFlow<Set<String>> = _blockedUsers.asStateFlow()

    fun blockUser(email: String) {
        val updated = clientManager.blockedUsers + email.trim().lowercase()
        clientManager.blockedUsers = updated
        _blockedUsers.value = updated
    }

    fun unblockUser(email: String) {
        val updated = clientManager.blockedUsers - email.trim().lowercase()
        clientManager.blockedUsers = updated
        _blockedUsers.value = updated
    }

    fun isUserBlocked(email: String): Boolean {
        return _blockedUsers.value.contains(email.trim().lowercase())
    }

    // The beta client exposes one fixed public HTTPS API and no server configuration UI.
    val serverUrl: StateFlow<String> = MutableStateFlow(clientManager.serverUrl).asStateFlow()

    // Remote users fetched dynamically (no local user caching)
    private val _allUsers = MutableStateFlow<List<User>>(emptyList())
    val allUsers: StateFlow<List<User>> = _allUsers.asStateFlow()

    // Security and social data are server-driven and are never persisted as local authority.
    private val _activeSessions = MutableStateFlow<List<NetworkDeviceSession>>(emptyList())
    val activeSessions: StateFlow<List<NetworkDeviceSession>> = _activeSessions.asStateFlow()
    private val _securityEvents = MutableStateFlow<List<NetworkSecurityEvent>>(emptyList())
    val securityEvents: StateFlow<List<NetworkSecurityEvent>> = _securityEvents.asStateFlow()
    private val _notifications = MutableStateFlow<List<NetworkNotification>>(emptyList())
    val notifications: StateFlow<List<NetworkNotification>> = _notifications.asStateFlow()
    private val _friends = MutableStateFlow<List<NetworkFriendRelation>>(emptyList())
    val friends: StateFlow<List<NetworkFriendRelation>> = _friends.asStateFlow()
    private val _serverBlocks = MutableStateFlow<List<NetworkBlockRelation>>(emptyList())
    val serverBlocks: StateFlow<List<NetworkBlockRelation>> = _serverBlocks.asStateFlow()
    private val _groups = MutableStateFlow<List<NetworkGroup>>(emptyList())
    val groups: StateFlow<List<NetworkGroup>> = _groups.asStateFlow()

    // Current logged-in user state
    private val _loggedInUser = MutableStateFlow<User?>(null)
    val loggedInUser: StateFlow<User?> = _loggedInUser.asStateFlow()
    private val _rememberedAccounts = MutableStateFlow(clientManager.getRememberedAccounts())
    val rememberedAccounts: StateFlow<List<User>> = _rememberedAccounts.asStateFlow()

    init {
        // Enforce strictly online/server-driven data
        viewModelScope.launch {
            // Fetch remote users list from the server dynamically
            refreshServerUsers()
        }

        // Restore only a server-issued bearer session; legacy password-hash sessions are discarded.
        // A network failure must never be treated as a logout.
        val savedUser = clientManager.getLoggedInUser()
        val savedToken = clientManager.getSessionToken()
        if (savedUser != null && savedToken.isNotBlank()) {
            _loggedInUser.value = savedUser
            viewModelScope.launch {
                try {
                    val session = clientManager.getService().validateSession(savedToken)
                    val validForUser = session.valid == true &&
                        (session.userId == null || session.userId == savedUser.id)
                    if (validForUser) {
                        refreshConnectedAccountData()
                    } else {
                        clientManager.clearSession()
                        clientManager.clearLoggedInUser()
                        _loggedInUser.value = null
                    }
                } catch (error: Exception) {
                    // Only an explicit server rejection invalidates a local session.
                    // Timeouts, a temporarily offline device or a server outage keep
                    // the encrypted token for the next validation attempt.
                    val status = (error as? retrofit2.HttpException)?.code()
                    if (status == 401 || status == 403) {
                        clientManager.clearSession()
                        clientManager.clearLoggedInUser()
                        _loggedInUser.value = null
                    }
                }
            }
        }

    }

    // App settings, language and security
    private val _language = MutableStateFlow(clientManager.language)
    val language: StateFlow<String> = _language.asStateFlow()

    var currentLanguageState by androidx.compose.runtime.mutableStateOf(clientManager.language)
        private set

    private val _appPasscode = MutableStateFlow(clientManager.appPasscode)
    val appPasscode: StateFlow<String> = _appPasscode.asStateFlow()

    private val _isAppLocked = MutableStateFlow(clientManager.appPasscode.isNotEmpty())
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    fun setLanguage(lang: String) {
        clientManager.language = lang
        _language.value = lang
        currentLanguageState = lang
    }

    fun t(key: String): String {
        return Translation.get(key, currentLanguageState)
    }

    fun setAppPasscode(passcode: String) {
        clientManager.appPasscode = passcode
        _appPasscode.value = passcode
        _isAppLocked.value = passcode.isNotEmpty()
    }

    fun unlockApp() {
        _isAppLocked.value = false
    }

    fun lockApp() {
        if (_appPasscode.value.isNotEmpty()) {
            _isAppLocked.value = true
        }
    }

    // Server users list loading
    fun refreshServerUsers() {
        viewModelScope.launch {
            try {
                val api = clientManager.getService()
                val serverUsers = api.getUsers().map { it.toLocalUser("") }
                _allUsers.value = serverUsers
            } catch (e: Exception) {
                _allUsers.value = emptyList()
            }
        }
    }

    // Messaging operations (Server-side dynamic communication)
    fun getChatPartnersFlow(): kotlinx.coroutines.flow.Flow<List<String>> {
        val user = _loggedInUser.value ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return kotlinx.coroutines.flow.flow {
            messageDao.getChatPartners(user.email).collect { list ->
                val filtered = list.filter { it.isNotEmpty() && !isUserBlocked(it) }
                emit(filtered)
            }
        }
    }

    fun getMessagesForPartner(partnerEmail: String): kotlinx.coroutines.flow.Flow<List<Message>> {
        val user = _loggedInUser.value ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return kotlinx.coroutines.flow.flow {
            try {
                val remote = clientManager.getService().getMessages(user.email, partnerEmail)
                emit(remote.map {
                    Message(
                        id = it.id,
                        senderEmail = it.senderEmail,
                        receiverEmail = it.receiverEmail,
                        text = it.text,
                        timestamp = it.timestamp
                    )
                })
            } catch (_: Exception) {
                emit(emptyList())
            }
        }
    }

    fun sendMessage(recipientEmail: String, text: String, onResult: (Boolean, String) -> Unit) {
        val sender = _loggedInUser.value ?: return
        if (text.trim().isEmpty()) {
            onResult(false, t("empty_msg_error"))
            return
        }
        viewModelScope.launch {
            try {
                clientManager.getService().sendMessage(
                    SendMessageRequest(sender.email, recipientEmail.trim().lowercase(), text.trim())
                )
                onResult(true, "")
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "Remote messaging is unavailable")
            }
        }
    }

    fun deleteChat(partnerEmail: String) {
        // The public beta API does not expose message deletion; do not delete locally.
    }

    fun deleteMessage(messageId: Int) {
        viewModelScope.launch {
            try {
                // Delete locally
                messageDao.deleteMessage(messageId)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // Login screen state
    private val _loginEmail = MutableStateFlow("")
    val loginEmail: StateFlow<String> = _loginEmail.asStateFlow()

    private val _loginPassword = MutableStateFlow("")
    val loginPassword: StateFlow<String> = _loginPassword.asStateFlow()

    private val _loginKeyProtect = MutableStateFlow("")
    val loginKeyProtect: StateFlow<String> = _loginKeyProtect.asStateFlow()

    private val _requireKeyProtect = MutableStateFlow(false)
    val requireKeyProtect: StateFlow<Boolean> = _requireKeyProtect.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    // Registration draft state
    private val _regFirstName = MutableStateFlow("")
    val regFirstName: StateFlow<String> = _regFirstName.asStateFlow()

    private val _regLastName = MutableStateFlow("")
    val regLastName: StateFlow<String> = _regLastName.asStateFlow()

    private val _regBirthYear = MutableStateFlow("")
    val regBirthYear: StateFlow<String> = _regBirthYear.asStateFlow()

    private val _regBirthMonth = MutableStateFlow("January")
    val regBirthMonth: StateFlow<String> = _regBirthMonth.asStateFlow()

    private val _regBirthDay = MutableStateFlow("")
    val regBirthDay: StateFlow<String> = _regBirthDay.asStateFlow()

    private val _regGender = MutableStateFlow("Rather not say")
    val regGender: StateFlow<String> = _regGender.asStateFlow()

    private val _regEmailOption = MutableStateFlow("")
    val regEmailOption: StateFlow<String> = _regEmailOption.asStateFlow()

    private val _regCustomEmail = MutableStateFlow("")
    val regCustomEmail: StateFlow<String> = _regCustomEmail.asStateFlow()

    private val _regPassword = MutableStateFlow("")
    val regPassword: StateFlow<String> = _regPassword.asStateFlow()

    private val _regConfirmPassword = MutableStateFlow("")
    val regConfirmPassword: StateFlow<String> = _regConfirmPassword.asStateFlow()

    private val _regError = MutableStateFlow<String?>(null)
    val regError: StateFlow<String?> = _regError.asStateFlow()

    // Email suggestions generator
    private val _emailSuggestions = MutableStateFlow<List<String>>(emptyList())
    val emailSuggestions: StateFlow<List<String>> = _emailSuggestions.asStateFlow()

    // Colors
    val avatarColors = listOf(
        0xFF1A73E8.toInt(),
        0xFFEA4335.toInt(),
        0xFFFBBC05.toInt(),
        0xFF34A853.toInt(),
        0xFF8E24AA.toInt(),
        0xFF00ACC1.toInt(),
        0xFFD81B60.toInt(),
        0xFFF4511E.toInt()
    )

    // Sync helpers
    fun setConnectionMode(mode: String) {
        // Forced remote
    }


    fun selectLoginUser(user: User) {
        _loginEmail.value = user.email
        _loginPassword.value = ""
        _loginError.value = null
    }

    fun setLoginEmail(email: String) {
        _loginEmail.value = email
        _loginError.value = null
    }

    fun setLoginPassword(password: String) {
        _loginPassword.value = password
        _loginError.value = null
    }

    fun setLoginKeyProtect(key: String) {
        _loginKeyProtect.value = key
        _loginError.value = null
    }

    fun performLogin(foregroundContext: Context, onSuccess: () -> Unit) {
        val email = _loginEmail.value.trim()
        val password = _loginPassword.value
        val keyProtectInput = _loginKeyProtect.value

        if (email.isEmpty() || password.isEmpty()) {
            _loginError.value = t("login_error_fill_fields")
            return
        }

        _loginError.value = null
        viewModelScope.launch {
            if (isCurrentDeviceBanned()) {
                _loginError.value = "Access denied: This device has been banned from the database (Hardware/IP Ban)."
                return@launch
            }
            try {
                val passwordHash = sha256(password)
                val response = clientManager.getService().login(
                    NetworkLoginRequest(email = email, passwordHash = passwordHash)
                )
                val sessionToken = response.sessionToken?.trim().orEmpty()
                if (sessionToken.isBlank()) {
                    throw IllegalStateException("Server did not return a session token")
                }
                val remoteUser = response.toLocalUser(passwordHash)

                // OrvexaAuth Beta uses the public API as the only account backend.
                _loggedInUser.value = remoteUser
                saveCredentialInProvider(foregroundContext, email, password)
                _loginError.value = null
                _requireKeyProtect.value = false
                _loginKeyProtect.value = ""

                // Save only the server-issued bearer token for session restoration.
                clientManager.saveSession(email, sessionToken)
                clientManager.saveLoggedInUser(remoteUser)
                _rememberedAccounts.value = clientManager.getRememberedAccounts()

                refreshServerUsers()
                onSuccess()
            } catch (e: Exception) {
                _loginError.value = when ((e as? retrofit2.HttpException)?.code()) {
                    401 -> t("login_error_wrong_password")
                    404 -> t("login_error_account_not_found")
                    429 -> t("login_error_rate_limited")
                    else -> t("login_error_connection")
                }
            }
        }
    }

    private fun saveCredentialInProvider(foregroundContext: Context, username: String, password: String) {
        viewModelScope.launch {
            // Failure is intentionally non-fatal: a provider may be unavailable or disabled.
            credentialManager.savePassword(foregroundContext, username, password)
        }
    }

    fun useSavedCredential(context: Context, onLoaded: (String, String) -> Unit, onUnavailable: (String) -> Unit = {}) {
        viewModelScope.launch {
            val result = credentialManager.getPassword(context)
            result.getOrNull()?.let { (username, password) ->
                onLoaded(username, password)
            } ?: result.exceptionOrNull()?.let { error ->
                onUnavailable(error.localizedMessage ?: "No saved credential available")
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun logout() {
        val token = clientManager.getSessionToken()
        if (token.isNotBlank()) {
            viewModelScope.launch {
                try { clientManager.getService().revokeSession(token) } catch (_: Exception) { }
            }
        }
        clientManager.clearSession()
        clientManager.clearLoggedInUser()
        _loggedInUser.value = null
        _loginPassword.value = ""

    }


    // Setters for Registration
    fun setRegNames(first: String, last: String) {
        _regFirstName.value = first
        _regLastName.value = last
        _regError.value = null
    }

    fun setRegBirthInfo(day: String, month: String, year: String, gender: String) {
        _regBirthDay.value = day
        _regBirthMonth.value = month
        _regBirthYear.value = year
        _regGender.value = gender
        _regError.value = null
    }

    fun generateSuggestions() {
        val first = _regFirstName.value.trim().lowercase().filter { it.isLetterOrDigit() && !it.isWhitespace() }
        val last = _regLastName.value.trim().lowercase().filter { it.isLetterOrDigit() && !it.isWhitespace() }
        val suffix = ""

        val validFirst = first.isNotBlank()
        val validLast = last.isNotBlank()

        if (!validFirst && !validLast) {
            _emailSuggestions.value = listOf("user123$suffix", "account$suffix")
            return
        }
        val s1 = if (!validLast) "$first$suffix" else if (!validFirst) "$last$suffix" else "${first}.${last}$suffix"
        val s2 = if (!validLast) "${first}1$suffix" else if (!validFirst) "${last}1$suffix" else "${last}.${first}$suffix"
        val s3 = "${first}${last}${(10..99).random()}$suffix"
        _emailSuggestions.value = listOf(s1, s2, s3)
        _regEmailOption.value = s1
    }

    fun setRegEmailOption(option: String) {
        _regEmailOption.value = option
        _regError.value = null
    }

    fun setRegCustomEmail(email: String) {
        _regCustomEmail.value = email
        _regError.value = null
    }

    fun setRegPassword(pass: String, confirm: String) {
        _regPassword.value = pass
        _regConfirmPassword.value = confirm
        _regError.value = null
    }

    fun validateNames(): Boolean {
        if (_regFirstName.value.trim().isEmpty()) {
            _regError.value = "Enter first name"
            return false
        }
        _regError.value = null
        return true
    }

    fun validateBirthInfo(): Boolean {
        val day = _regBirthDay.value.toIntOrNull()
        val year = _regBirthYear.value.toIntOrNull()

        if (day == null || day !in 1..31) {
            _regError.value = "Enter a valid day of the month"
            return false
        }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        if (year == null || year !in 1900..currentYear) {
            _regError.value = "Enter a valid four-digit year"
            return false
        }
        _regError.value = null
        return true
    }

    fun validateEmailStep(): Boolean {
        val option = _regEmailOption.value
        val email = if (option == "custom") _regCustomEmail.value.trim() else option

        if (email.isEmpty()) {
            _regError.value = "Choose or create an account email"
            return false
        }
        if (!email.contains("@")) {
            _regError.value = "Invalid email format. Suffix required."
            return false
        }
        _regError.value = null
        return true
    }

    fun validatePasswordStep(): Boolean {
        val pass = _regPassword.value
        val confirm = _regConfirmPassword.value

        if (pass.length < 6) {
            _regError.value = "Try a mix of letters, numbers, and symbols with at least 6 characters"
            return false
        }
        if (pass != confirm) {
            _regError.value = "These passwords don't match. Try again."
            return false
        }
        _regError.value = null
        return true
    }

    fun performRegistration(foregroundContext: Context, onSuccess: () -> Unit) {
        val option = _regEmailOption.value
        val email = if (option == "custom") _regCustomEmail.value.trim() else option
        val password = _regPassword.value
        val randomColor = avatarColors.random()
        val monthStr = _regBirthMonth.value
        val dayStr = _regBirthDay.value.padStart(2, '0')
        val yearStr = _regBirthYear.value
        val dob = "$yearStr-$monthStr-$dayStr"
        val firstName = _regFirstName.value.trim()
        val lastName = _regLastName.value.trim()

        _regError.value = null
        viewModelScope.launch {
            if (isCurrentDeviceBanned()) {
                _regError.value = "Access denied: This device has been banned from registering (Hardware/IP Ban)."
                return@launch
            }
            if (_allUsers.value.size >= 3) {
                _regError.value = t("max_accounts_reached")
                return@launch
            }

            val emailExists = _allUsers.value.any { it.email.equals(email, ignoreCase = true) }
            if (emailExists) {
                _regError.value = "An account with this email is already registered."
                return@launch
            }

            val nameExists = _allUsers.value.any { it.firstName.equals(firstName, ignoreCase = true) && it.lastName.equals(lastName, ignoreCase = true) }
            if (nameExists) {
                _regError.value = "An account with the same name ($firstName $lastName) already exists."
                return@launch
            }

            try {
                val passwordHash = sha256(password)
                val response = clientManager.getService().register(
                    NetworkRegisterRequest(
                        email = email,
                        passwordHash = passwordHash,
                        firstName = firstName,
                        lastName = lastName,
                        birthDate = dob,
                        gender = _regGender.value,
                        avatarColor = randomColor,
                        ipAddress = clientManager.deviceIp,
                        macAddress = clientManager.deviceMac,
                        keyProtect = ""
                    )
                )
                val sessionToken = response.sessionToken?.trim().orEmpty()
                if (sessionToken.isBlank()) {
                    throw IllegalStateException("Server did not return a session token")
                }
                val localUser = response.toLocalUser(passwordHash)

                // Keep only the local session record; all account data remains server-authoritative.
                _loggedInUser.value = localUser
                saveCredentialInProvider(foregroundContext, email, password)
                _regError.value = null
                resetRegDraft()

                clientManager.saveSession(email, sessionToken)
                clientManager.saveLoggedInUser(localUser)

                refreshServerUsers()
                onSuccess()
            } catch (e: Exception) {
                _regError.value = "Registration failed: ${e.localizedMessage ?: "Connection refused"}"
            }
        }
    }

    private fun resetRegDraft() {
        _regFirstName.value = ""
        _regLastName.value = ""
        _regBirthYear.value = ""
        _regBirthMonth.value = "January"
        _regBirthDay.value = ""
        _regGender.value = "Rather not say"
        _regEmailOption.value = ""
        _regCustomEmail.value = ""
        _regPassword.value = ""
        _regConfirmPassword.value = ""
        _regError.value = null
    }

    fun updateProfile(
        firstName: String,
        lastName: String,
        birthDate: String,
        gender: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val currentUser = _loggedInUser.value ?: return
        if (firstName.trim().isEmpty()) {
            onError("First name cannot be empty")
            return
        }

        viewModelScope.launch {
            try {
                val apiService = clientManager.getService()
                val request = NetworkUpdateProfileRequest(
                    firstName = firstName.trim(),
                    lastName = lastName.trim(),
                    birthDate = birthDate,
                    gender = gender
                )
                val response = apiService.updateProfile(currentUser.id, request)

                val updatedUser = response.toLocalUser(currentUser.passwordHash)
                _loggedInUser.value = updatedUser
                clientManager.saveLoggedInUser(updatedUser)
                onSuccess()
            } catch (e: Exception) {
                onError("Remote update failed: ${e.localizedMessage ?: "Server offline"}")
            }
        }
    }

    fun updatePassword(newPass: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val currentUser = _loggedInUser.value ?: return
        if (newPass.length < 6) {
            onError("Password must be at least 6 characters")
            return
        }

        viewModelScope.launch {
            try {
                val apiService = clientManager.getService()
                val newPasswordHash = sha256(newPass)
                apiService.updatePassword(currentUser.id, NetworkUpdatePasswordRequest(newPasswordHash))

                val updatedUser = currentUser.copy(passwordHash = newPasswordHash)
                _loggedInUser.value = updatedUser
                onSuccess()
            } catch (e: Exception) {
                onError("Remote password update failed: ${e.localizedMessage ?: "Server offline"}")
            }
        }
    }

    fun removeLocalAccountCache(user: User) {
        // Account removal on a device must never delete the remote OrvexaAuth account.
        clientManager.removeRememberedAccount(user.email)
        _rememberedAccounts.value = clientManager.getRememberedAccounts()
        if (_loggedInUser.value?.id == user.id) {
            logout()
        }
    }

    fun deleteAccount(currentPassword: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val currentUser = _loggedInUser.value ?: return onError(t("delete_error_not_signed_in"))
        if (currentPassword.isBlank()) return onError(t("delete_error_password_required"))

        viewModelScope.launch {
            try {
                val apiService = clientManager.getService()
                apiService.deleteAccount(currentUser.id, NetworkDeleteAccountRequest(sha256(currentPassword)))
            } catch (e: Exception) {
                val message = when ((e as? retrofit2.HttpException)?.code()) {
                    401, 403 -> t("delete_error_wrong_password")
                    404 -> t("delete_error_not_found")
                    else -> t("delete_error_connection")
                }
                onError(message)
                return@launch
            }
            clientManager.clearSession()
            clientManager.clearLoggedInUser()
            _loggedInUser.value = null
            onSuccess()
        }
    }

    fun checkServerStatus(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val apiService = clientManager.getService()
                val response = apiService.checkStatus()
                refreshServerUsers()
                onResult(true, "OrvexaAuth API is online")
            } catch (e: Exception) {
                onResult(false, "Server unreachable: ${e.localizedMessage ?: "Connection timed out"}")
            }
        }
    }

    fun scanLocalFiles(extension: String): List<File> {
        val filesList = mutableListOf<File>()
        try {
            val downloadsDir = File("/storage/emulated/0/Download")
            if (downloadsDir.exists() && downloadsDir.isDirectory) {
                downloadsDir.listFiles()?.filter { it.isFile && it.name.endsWith(extension, ignoreCase = true) }?.let {
                    filesList.addAll(it)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            getApplication<Application>().getExternalFilesDir(null)?.let { extDir ->
                extDir.listFiles()?.filter { it.isFile && it.name.endsWith(extension, ignoreCase = true) }?.let {
                    filesList.addAll(it)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return filesList
    }

    suspend fun isCurrentDeviceBanned(): Boolean {
        return false
    }

    fun banHardware(value: String, banType: String) {
        viewModelScope.launch {
            userDao.insertBan(BannedHardware(value, banType))
        }
    }

    fun unbanHardware(value: String) {
        viewModelScope.launch {
            userDao.deleteBan(BannedHardware(value))
        }
    }

    fun importServiceKeyFromJson(file: File, callback: (Boolean, String) -> Unit) {
        // Legacy service-key files are intentionally unsupported in the public beta.
        callback(false, "Service-key files are not supported. OrvexaAuth Beta uses its public Cloudflare Worker.")
    }

    fun exportAccountsToAf(file: File, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val usersList = _allUsers.value
                val moshi = com.squareup.moshi.Moshi.Builder().build()
                val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, User::class.java)
                val adapter = moshi.adapter<List<User>>(listType)
                val jsonStr = adapter.toJson(usersList)
                file.writeText(jsonStr, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    onResult(true, "Accounts exported successfully to ${file.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Export failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun exportSingleUserFullAf(user: User, file: File, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiService = clientManager.getService()

                // Fetch chat conversations. File storage belongs to the desktop Launcher,
                // not the mobile identity client.
                val chats = mutableListOf<Map<String, Any>>()
                val otherUsers = _allUsers.value.filter { it.email.lowercase() != user.email.lowercase() }
                otherUsers.forEach { other ->
                    try {
                        val serverMsgs = try {
                            apiService.getMessages(user.email, other.email)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        val allMsgs = serverMsgs.map { networkMsg ->
                            Message(
                                id = networkMsg.id,
                                senderEmail = networkMsg.senderEmail,
                                receiverEmail = networkMsg.receiverEmail,
                                text = networkMsg.text,
                                timestamp = networkMsg.timestamp
                            )
                        }.distinctBy { it.id }

                        if (allMsgs.isNotEmpty()) {
                            val msgMaps = allMsgs.map { m ->
                                mapOf(
                                    "id" to m.id,
                                    "senderEmail" to m.senderEmail,
                                    "receiverEmail" to m.receiverEmail,
                                    "text" to m.text,
                                    "timestamp" to m.timestamp
                                )
                            }
                            chats.add(mapOf(
                                "partnerEmail" to other.email,
                                "messages" to msgMaps
                            ))
                        }
                    } catch (e: Exception) {
                        // ignore chat retrieval errors
                    }
                }

                // User profile information
                val userProfile = mapOf(
                    "email" to user.email,
                    "passwordHash" to user.passwordHash,
                    "firstName" to user.firstName,
                    "lastName" to user.lastName,
                    "birthDate" to user.birthDate,
                    "gender" to user.gender,
                    "avatarColor" to user.avatarColor,
                    "ipAddress" to user.ipAddress,
                    "macAddress" to user.macAddress,
                    "keyProtect" to user.keyProtect,
                    "dataQuotaMb" to user.dataQuotaMb
                )

                // Local blocklist
                val blocklist = _blockedUsers.value.toList()

                // Combine into master backup map
                val backupMap = mapOf(
                    "fileFormat" to "NetAuthAccountBackup",
                    "version" to "1.0",
                    "exportTime" to System.currentTimeMillis(),
                    "userProfile" to userProfile,
                    "blocklist" to blocklist,
                    "chats" to chats
                )

                val moshi = com.squareup.moshi.Moshi.Builder().build()
                val adapter = moshi.adapter(Any::class.java)
                val jsonStr = adapter.toJson(backupMap)
                file.writeText(jsonStr, Charsets.UTF_8)

                withContext(Dispatchers.Main) {
                    onResult(true, "Full account backup for ${user.email} exported successfully to ${file.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Export failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun exportAllChatsOfUserToChat(userEmail: String, file: File, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Fetch all other users
                val otherUsers = _allUsers.value.filter { it.email.lowercase() != userEmail.lowercase() }
                val allUserMsgs = mutableListOf<Message>()
                otherUsers.forEach { other ->
                    try {
                        val messages = clientManager.getService().getMessages(userEmail, other.email).map { networkMsg ->
                            Message(
                                id = networkMsg.id,
                                senderEmail = networkMsg.senderEmail,
                                receiverEmail = networkMsg.receiverEmail,
                                text = networkMsg.text,
                                timestamp = networkMsg.timestamp
                            )
                        }
                        allUserMsgs.addAll(messages)
                    } catch (e: Exception) {
                        // ignore individual failures
                    }
                }

                // Remove potential duplicate messages
                val distinctMsgs = allUserMsgs.distinctBy { it.id }

                // Find primary conversation partner
                val interlocutors = distinctMsgs.flatMap { listOf(it.senderEmail, it.receiverEmail) }
                    .distinct()
                    .filter { it.lowercase() != userEmail.lowercase() }
                val primaryPartner = interlocutors.firstOrNull() ?: "Friend"

                val chatTranscript = mapOf(
                    "fileFormat" to "NetAuthChatTranscript",
                    "version" to "1.0",
                    "participants" to listOf(userEmail, primaryPartner),
                    "messageCount" to distinctMsgs.size,
                    "messages" to distinctMsgs.map { m ->
                        mapOf(
                            "id" to m.id,
                            "senderEmail" to m.senderEmail,
                            "receiverEmail" to m.receiverEmail,
                            "text" to m.text,
                            "timestamp" to m.timestamp
                        )
                    }
                )

                val moshi = com.squareup.moshi.Moshi.Builder().build()
                val adapter = moshi.adapter(Any::class.java)
                val jsonStr = adapter.toJson(chatTranscript)
                file.writeText(jsonStr, Charsets.UTF_8)

                withContext(Dispatchers.Main) {
                    onResult(true, "All chat transcripts for $userEmail exported successfully to ${file.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Export chats failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun importAccountsFromAf(file: File, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = file.readText(Charsets.UTF_8)
                val moshi = com.squareup.moshi.Moshi.Builder().build()
                val adapter = moshi.adapter(Any::class.java)
                val parsed = adapter.fromJson(jsonStr)

                val apiService = clientManager.getService()

                if (parsed is Map<*, *> && parsed["fileFormat"] == "NetAuthAccountBackup") {
                    val profile = parsed["userProfile"] as? Map<*, *> ?: throw Exception("Profile data not found in backup")
                    val email = profile["email"] as? String ?: ""
                    val passwordHash = profile["passwordHash"] as? String ?: ""
                    val firstName = profile["firstName"] as? String ?: ""
                    val lastName = profile["lastName"] as? String ?: ""
                    val birthDate = profile["birthDate"] as? String ?: ""
                    val gender = profile["gender"] as? String ?: ""
                    val avatarColor = (profile["avatarColor"] as? Double)?.toInt() ?: (profile["avatarColor"] as? Int) ?: 0
                    val ipAddress = profile["ipAddress"] as? String ?: ""
                    val macAddress = profile["macAddress"] as? String ?: ""
                    val keyProtect = profile["keyProtect"] as? String ?: ""
                    val dataQuotaMb = (profile["dataQuotaMb"] as? Double)?.toInt() ?: (profile["dataQuotaMb"] as? Int) ?: 200

                    // 1. Register User on the active Database Partition
                    try {
                        apiService.register(NetworkRegisterRequest(
                            email = email,
                            passwordHash = passwordHash,
                            firstName = firstName,
                            lastName = lastName,
                            birthDate = birthDate,
                            gender = gender,
                            avatarColor = avatarColor,
                            ipAddress = ipAddress,
                            macAddress = macAddress,
                            keyProtect = keyProtect,
                            dataQuotaMb = dataQuotaMb
                        ))
                    } catch (e: Exception) {
                        // User might exist, continue with restoring assets
                    }

                    refreshServerUsers()

                    // Restore local blocklist
                    val blocklist = parsed["blocklist"] as? List<*> ?: emptyList<Any>()
                    blocklist.forEach { item ->
                        if (item is String) {
                            withContext(Dispatchers.Main) {
                                blockUser(item)
                            }
                        }
                    }

                    // Restore chat messaging transcripts
                    val chatsList = parsed["chats"] as? List<*> ?: emptyList<Any>()
                    chatsList.forEach { chatItem ->
                        if (chatItem is Map<*, *>) {
                            val messages = chatItem["messages"] as? List<*> ?: emptyList<Any>()
                            messages.forEach { msgItem ->
                                if (msgItem is Map<*, *>) {
                                    val id = (msgItem["id"] as? Double)?.toInt() ?: (msgItem["id"] as? Int) ?: 0
                                    val senderEmail = msgItem["senderEmail"] as? String ?: ""
                                    val receiverEmail = msgItem["receiverEmail"] as? String ?: ""
                                    val text = msgItem["text"] as? String ?: ""
                                    val timestamp = (msgItem["timestamp"] as? Double)?.toLong() ?: (msgItem["timestamp"] as? Long) ?: System.currentTimeMillis()

                                    try {
                                        apiService.sendMessage(SendMessageRequest(
                                            senderEmail = senderEmail,
                                            receiverEmail = receiverEmail,
                                            text = text
                                        ))
                                    } catch (e: Exception) {
                                        // ignore message upload failure
                                    }
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        logout()
                        refreshServerUsers()
                        onResult(true, "Full account $email imported and restored successfully from ${file.name}!")
                    }
                } else {
                    // Fallback to legacy List<User> import
                    val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, User::class.java)
                    val listAdapter = moshi.adapter<List<User>>(listType)
                    val usersList = listAdapter.fromJson(jsonStr) ?: emptyList()

                    val apiServiceLeg = clientManager.getService()
                    var importedCount = 0
                    usersList.forEach { user ->
                        try {
                            apiServiceLeg.register(NetworkRegisterRequest(
                                email = user.email,
                                passwordHash = user.passwordHash,
                                firstName = user.firstName,
                                lastName = user.lastName,
                                birthDate = user.birthDate,
                                gender = user.gender,
                                avatarColor = user.avatarColor,
                                ipAddress = user.ipAddress,
                                macAddress = user.macAddress,
                                keyProtect = user.keyProtect,
                                dataQuotaMb = user.dataQuotaMb
                            ))
                            importedCount++
                        } catch (e: Exception) {
                            // ignore duplicate registration
                        }
                    }

                    withContext(Dispatchers.Main) {
                        logout()
                        refreshServerUsers()
                        onResult(true, "Imported $importedCount of ${usersList.size} accounts to server from ${file.name}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Import failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun exportChatToChat(partnerEmail: String, file: File, onResult: (Boolean, String) -> Unit) {
        val user = _loggedInUser.value
        if (user == null) {
            onResult(false, "Not logged in")
            return
        }
        exportUserChatToChat(user.email, partnerEmail, file, onResult)
    }

    fun exportUserChatToChat(userEmail: String, partnerEmail: String, file: File, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val messagesList = clientManager.getService().getMessages(userEmail, partnerEmail).map { networkMsg ->
                    Message(
                        id = networkMsg.id,
                        senderEmail = networkMsg.senderEmail,
                        receiverEmail = networkMsg.receiverEmail,
                        text = networkMsg.text,
                        timestamp = networkMsg.timestamp
                    )
                }
                val moshi = com.squareup.moshi.Moshi.Builder().build()
                val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, Message::class.java)
                val adapter = moshi.adapter<List<Message>>(listType)
                val jsonStr = adapter.toJson(messagesList)
                file.writeText(jsonStr, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    onResult(true, "Chat exported successfully to ${file.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Export failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun importChatFromChat(file: File, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = file.readText(Charsets.UTF_8)
                val moshi = com.squareup.moshi.Moshi.Builder().build()
                val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, Message::class.java)
                val adapter = moshi.adapter<List<Message>>(listType)
                val messagesList = adapter.fromJson(jsonStr) ?: emptyList()

                val apiService = clientManager.getService()
                var importedCount = 0
                messagesList.forEach { message ->
                    runCatching {
                        apiService.sendMessage(SendMessageRequest(
                            senderEmail = message.senderEmail,
                            receiverEmail = message.receiverEmail,
                            text = message.text
                        ))
                    }.onSuccess { importedCount++ }
                }

                withContext(Dispatchers.Main) {
                    onResult(true, "Uploaded $importedCount of ${messagesList.size} messages to OrvexaAuth from ${file.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Import failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun clearRemoteDatabase(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val api = clientManager.getService()
                val response = api.clearDatabase()
                logout() // Log out since all user profiles on server are deleted
                onResult(true, response.message ?: "Database partition cleared successfully")
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "Failed to clear database partition")
            }
        }
    }

    fun refreshConnectedAccountData() {
        val user = _loggedInUser.value ?: return
        viewModelScope.launch {
            val api = clientManager.getService()
            runCatching { api.getActiveSessions(user.id) }.onSuccess { _activeSessions.value = it }
            runCatching { api.getSecurityEvents(user.id) }.onSuccess { _securityEvents.value = it }
            runCatching { api.getNotifications(user.id) }.onSuccess { _notifications.value = it }
            runCatching { api.getFriends(user.id) }.onSuccess { _friends.value = it }
            runCatching { api.getBlocks(user.id) }.onSuccess {
                _serverBlocks.value = it
                val emails = it.mapNotNull { relation -> relation.user?.email?.trim()?.lowercase() }.toSet()
                clientManager.blockedUsers = emails
                _blockedUsers.value = emails
            }
            runCatching { api.getGroups() }.onSuccess { _groups.value = it }
        }
    }

    fun approveQrLogin(requestId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            runCatching { clientManager.getService().approveQrSession(requestId) }
                .onSuccess { onResult(true, "Desktop login approved") }
                .onFailure { onResult(false, it.localizedMessage ?: "Could not approve QR login") }
        }
    }

    fun beginTotpSetup(onResult: (NetworkTotpSetupResponse?, String?) -> Unit) {
        val user = _loggedInUser.value ?: return onResult(null, "Not logged in")
        viewModelScope.launch {
            runCatching { clientManager.getService().setupTotp(user.id) }
                .onSuccess { onResult(it, null) }
                .onFailure { onResult(null, it.localizedMessage ?: "Could not start 2FA setup") }
        }
    }

    fun confirmTotp(code: String, onResult: (Boolean, String) -> Unit) {
        val user = _loggedInUser.value ?: return onResult(false, "Not logged in")
        viewModelScope.launch {
            runCatching { clientManager.getService().confirmTotp(user.id, NetworkVerificationCodeRequest(code.trim())) }
                .onSuccess { onResult(true, "Two-factor protection enabled") }
                .onFailure { onResult(false, it.localizedMessage ?: "Invalid code") }
        }
    }

    fun disableTotp(code: String, onResult: (Boolean, String) -> Unit) {
        val user = _loggedInUser.value ?: return onResult(false, "Not logged in")
        viewModelScope.launch {
            runCatching { clientManager.getService().disableTotp(user.id, NetworkVerificationCodeRequest(code.trim())) }
                .onSuccess { onResult(true, "Two-factor protection disabled") }
                .onFailure { onResult(false, it.localizedMessage ?: "Invalid code") }
        }
    }

    fun revokeAllServerSessions(onResult: (Boolean, String) -> Unit) {
        val user = _loggedInUser.value ?: return onResult(false, "Not logged in")
        viewModelScope.launch {
            runCatching { clientManager.getService().revokeAllSessions(user.id) }
                .onSuccess {
                    _activeSessions.value = emptyList()
                    clientManager.clearSession()
                    clientManager.clearLoggedInUser()
                    _loggedInUser.value = null
                    onResult(true, "All server sessions have been closed")
                }
                .onFailure { onResult(false, it.localizedMessage ?: "Could not close sessions") }
        }
    }

    fun requestFriend(email: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            runCatching { clientManager.getService().requestFriend(NetworkFriendRequest(targetEmail = email.trim())) }
                .onSuccess { refreshConnectedAccountData(); onResult(true, "Friend request sent") }
                .onFailure { onResult(false, it.localizedMessage ?: "Could not send friend request") }
        }
    }

    fun acceptFriend(requesterId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            runCatching { clientManager.getService().acceptFriend(requesterId) }
                .onSuccess { refreshConnectedAccountData(); onResult(true, "Friend request accepted") }
                .onFailure { onResult(false, it.localizedMessage ?: "Could not accept friend request") }
        }
    }

    fun removeFriendServer(otherId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            runCatching { clientManager.getService().removeFriend(otherId) }
                .onSuccess { refreshConnectedAccountData(); onResult(true, "Friend removed") }
                .onFailure { onResult(false, it.localizedMessage ?: "Could not remove friend") }
        }
    }

    fun blockUserServer(email: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            runCatching { clientManager.getService().blockUser(NetworkBlockRequest(targetEmail = email.trim())) }
                .onSuccess { refreshConnectedAccountData(); onResult(true, "User blocked") }
                .onFailure { onResult(false, it.localizedMessage ?: "Could not block user") }
        }
    }

    fun unblockUserServer(targetId: String, onResult: (Boolean, String) -> Unit) {
        val user = _loggedInUser.value ?: return onResult(false, "Not logged in")
        viewModelScope.launch {
            runCatching { clientManager.getService().unblockUser(user.id, targetId) }
                .onSuccess { refreshConnectedAccountData(); onResult(true, "User unblocked") }
                .onFailure { onResult(false, it.localizedMessage ?: "Could not unblock user") }
        }
    }

    fun createServerGroup(name: String, description: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            runCatching { clientManager.getService().createGroup(NetworkCreateGroupRequest(name.trim(), description.trim())) }
                .onSuccess { refreshConnectedAccountData(); onResult(true, "Group created") }
                .onFailure { onResult(false, it.localizedMessage ?: "Could not create group") }
        }
    }

}
