package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Compatibility adapter retained for the existing UI/repository layer.
 * OrvexaAuth Beta has no local database and no Firebase dependency: user reads and
 * profile writes are routed to the public Cloudflare Worker through Retrofit.
 */
class UserDao(context: Context) {
    private val clientManager = NetAuthClientManager(context)

    private suspend fun fetchUsers(): List<User> = runCatching {
        clientManager.getService().getUsers().map { it.toLocalUser("") }
    }.getOrDefault(emptyList())

    suspend fun deleteAllUsers() {
        // Deliberately disabled: a public beta client must not expose a destructive
        // database-wide operation.
    }

    fun getAllUsers(): Flow<List<User>> = flow {
        emit(fetchUsers())
    }

    suspend fun getUserByEmail(email: String): User? =
        fetchUsers().firstOrNull { it.email.equals(email.trim(), ignoreCase = true) }

    suspend fun getUserByFirstAndLastName(firstName: String, lastName: String): User? =
        fetchUsers().firstOrNull {
            it.firstName.equals(firstName, ignoreCase = true) &&
                it.lastName.equals(lastName, ignoreCase = true)
        }

    suspend fun getUserById(id: Int): User? =
        fetchUsers().firstOrNull { it.id == id }

    suspend fun insertUser(user: User): Long = runCatching {
        val response = clientManager.getService().register(
            NetworkRegisterRequest(
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
            )
        )
        response.id.toLong()
    }.getOrDefault(0L)

    suspend fun updateUser(user: User) {
        runCatching {
            clientManager.getService().updateProfile(
                user.id,
                NetworkUpdateProfileRequest(
                    firstName = user.firstName,
                    lastName = user.lastName,
                    birthDate = user.birthDate,
                    gender = user.gender,
                )
            )
        }
    }

    suspend fun deleteUser(user: User, currentPasswordHash: String) {
        clientManager.getService().deleteAccount(user.id, NetworkDeleteAccountRequest(currentPasswordHash))
    }

    /**
     * Hardware-ban endpoints are not part of the public beta API. Return an empty
     * remote result instead of falling back to Firestore or a local database.
     */
    fun getAllBannedHardware(): Flow<List<BannedHardware>> = flow {
        emit(emptyList())
    }

    suspend fun insertBan(ban: BannedHardware) {
        // Unsupported by the public OrvexaAuth Beta contract.
    }

    suspend fun deleteBan(ban: BannedHardware) {
        // Unsupported by the public OrvexaAuth Beta contract.
    }

    suspend fun isHardwareBanned(value: String): Boolean = false
}
