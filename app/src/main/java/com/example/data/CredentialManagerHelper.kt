package com.example.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.GetCredentialRequest
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.GetPasswordOption

/**
 * Optional system credential integration. The active provider may be Google Password Manager
 * or another Credential Manager provider selected by the user. The app never writes the clear-text
 * password to its own preferences, files, database, logs, or network payloads beyond the login call.
 */
class CredentialManagerHelper(context: Context) {
    private val appContext = context.applicationContext
    private val manager = CredentialManager.create(appContext)

    suspend fun savePassword(username: String, password: String): Result<Unit> {
        return try {
            manager.createCredential(
                context = appContext,
                request = CreatePasswordRequest(id = username.trim(), password = password)
            )
            Result.success(Unit)
        } catch (error: CreateCredentialException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun getPassword(foregroundContext: Context): Result<Pair<String, String>?> {
        return try {
            val response = manager.getCredential(
                context = foregroundContext,
                request = GetCredentialRequest(
                    credentialOptions = listOf(GetPasswordOption())
                )
            )
            val credential = response.credential
            if (credential is PasswordCredential) {
                Result.success(credential.id to credential.password)
            } else {
                Result.success(null)
            }
        } catch (error: GetCredentialException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
