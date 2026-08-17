package com.example.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.GetCredentialRequest
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.GetPasswordOption

/**
 * Optional system credential integration. The actual provider may be Google Password Manager
 * or another Credential Manager provider configured by the user. The app never stores the
 * clear-text password in its own preferences or database.
 */
class CredentialManagerHelper(context: Context) {
    private val appContext = context.applicationContext
    private val manager = CredentialManager.create(appContext)

    suspend fun savePassword(username: String, password: String): Result<Unit> {
        return try {
            manager.createCredential(
                context = appContext,
                request = CreatePasswordRequest(id = username, password = password)
            )
            Result.success(Unit)
        } catch (error: CreateCredentialException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun getPassword(): Result<Pair<String, String>?> {
        return try {
            val response = manager.getCredential(
                context = appContext,
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
