package com.example.data

import android.content.Context
import com.example.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// Network Data Models
data class StatusResponse(
    val status: String,
    val message: String,
    val serverTime: Long
)

data class NetworkLoginRequest(
    val email: String,
    val passwordHash: String
)

data class NetworkRegisterRequest(
    val email: String,
    val passwordHash: String,
    val firstName: String,
    val lastName: String,
    val birthDate: String,
    val gender: String,
    val avatarColor: Int,
    val ipAddress: String = "",
    val macAddress: String = "",
    val keyProtect: String = "",
    val dataQuotaMb: Int = 200
)

data class NetworkUpdateProfileRequest(
    val firstName: String,
    val lastName: String,
    val birthDate: String,
    val gender: String,
)

data class NetworkUpdatePasswordRequest(
    val passwordHash: String
)

data class NetworkUserResponse(
    val id: Int,
    val email: String,
    @com.squareup.moshi.Json(name = "firstName") val firstNameCamel: String? = null,
    @com.squareup.moshi.Json(name = "first_name") val firstNameSnake: String? = null,
    @com.squareup.moshi.Json(name = "lastName") val lastNameCamel: String? = null,
    @com.squareup.moshi.Json(name = "last_name") val lastNameSnake: String? = null,
    @com.squareup.moshi.Json(name = "birthDate") val birthDateCamel: String? = null,
    @com.squareup.moshi.Json(name = "birth_date") val birthDateSnake: String? = null,
    val gender: String? = null,
    @com.squareup.moshi.Json(name = "avatarColor") val avatarColorCamel: Int? = null,
    @com.squareup.moshi.Json(name = "avatar_color") val avatarColorSnake: Int? = null,
    @com.squareup.moshi.Json(name = "ipAddress") val ipAddressCamel: String? = null,
    @com.squareup.moshi.Json(name = "ip_address") val ipAddressSnake: String? = null,
    @com.squareup.moshi.Json(name = "macAddress") val macAddressCamel: String? = null,
    @com.squareup.moshi.Json(name = "mac_address") val macAddressSnake: String? = null,
    @com.squareup.moshi.Json(name = "keyProtect") val keyProtectCamel: String? = null,
    @com.squareup.moshi.Json(name = "key_protect") val keyProtectSnake: String? = null,
    @com.squareup.moshi.Json(name = "dataQuotaMb") val dataQuotaMbCamel: Int? = null,
    @com.squareup.moshi.Json(name = "data_quota_mb") val dataQuotaMbSnake: Int? = null,
    @com.squareup.moshi.Json(name = "createdAt") val createdAtCamel: Long? = null,
    @com.squareup.moshi.Json(name = "created_at") val createdAtSnake: Long? = null
) {
    fun toLocalUser(passwordHash: String): User {
        val fName = firstNameCamel ?: firstNameSnake ?: ""
        val lName = lastNameCamel ?: lastNameSnake ?: ""
        val bDate = birthDateCamel ?: birthDateSnake ?: ""
        val gdr = gender ?: "Rather not say"
        val avColor = avatarColorCamel ?: avatarColorSnake ?: -12543232
        val ip = ipAddressCamel ?: ipAddressSnake ?: ""
        val mac = macAddressCamel ?: macAddressSnake ?: ""
        val kp = keyProtectCamel ?: keyProtectSnake ?: ""
        val quota = dataQuotaMbCamel ?: dataQuotaMbSnake ?: 200
        val created = createdAtCamel ?: createdAtSnake ?: System.currentTimeMillis()

        return User(
            id = id,
            email = email,
            passwordHash = passwordHash,
            firstName = fName,
            lastName = lName,
            birthDate = bDate,
            gender = gdr,
            avatarColor = avColor,
            ipAddress = ip,
            macAddress = mac,
            keyProtect = kp,
            dataQuotaMb = quota,
            createdAt = created
        )
    }
}

// Retrofit API Endpoints
interface NetAuthService {
    @GET("api/status")
    suspend fun checkStatus(): StatusResponse

    @POST("api/login")
    suspend fun login(@Body request: NetworkLoginRequest): NetworkUserResponse

    @POST("api/register")
    suspend fun register(@Body request: NetworkRegisterRequest): NetworkUserResponse

    @PUT("api/users/{id}")
    suspend fun updateProfile(
        @Path("id") id: Int,
        @Body request: NetworkUpdateProfileRequest
    ): NetworkUserResponse

    @PUT("api/users/{id}/password")
    suspend fun updatePassword(
        @Path("id") id: Int,
        @Body request: NetworkUpdatePasswordRequest
    ): StatusResponse

    @DELETE("api/users/{id}")
    suspend fun deleteAccount(@Path("id") id: Int): StatusResponse

    @GET("api/users")
    suspend fun getUsers(): List<NetworkUserResponse>

    @GET("api/users/{id}/storage")
    suspend fun getFiles(@Path("id") id: Int): List<NetworkFileResponse>

    @GET("api/users/{id}/storage/{fileName}")
    suspend fun downloadFile(
        @Path("id") id: Int,
        @Path("fileName") fileName: String
    ): okhttp3.ResponseBody

    @POST("api/users/{id}/storage")
    suspend fun uploadFile(
        @Path("id") id: Int,
        @Body request: NetworkUploadFileRequest
    ): StatusResponse

    @DELETE("api/users/{id}/storage/{fileName}")
    suspend fun deleteFile(
        @Path("id") id: Int,
        @Path("fileName") fileName: String
    ): StatusResponse

    @GET("api/messages")
    suspend fun getMessages(
        @Query("user1") user1: String,
        @Query("user2") user2: String
    ): List<NetworkMessage>

    @POST("api/messages")
    suspend fun sendMessage(@Body request: SendMessageRequest): StatusResponse

    @POST("api/database/clear")
    suspend fun clearDatabase(): StatusResponse
}

data class NetworkMessage(
    val id: Int,
    @com.squareup.moshi.Json(name = "senderEmail") val senderEmailCamel: String? = null,
    @com.squareup.moshi.Json(name = "sender_email") val senderEmailSnake: String? = null,
    @com.squareup.moshi.Json(name = "receiverEmail") val receiverEmailCamel: String? = null,
    @com.squareup.moshi.Json(name = "receiver_email") val receiverEmailSnake: String? = null,
    val text: String,
    val timestamp: Long
) {
    val senderEmail: String get() = senderEmailCamel ?: senderEmailSnake ?: ""
    val receiverEmail: String get() = receiverEmailCamel ?: receiverEmailSnake ?: ""
}

data class SendMessageRequest(
    val senderEmail: String,
    val receiverEmail: String,
    val text: String
)

data class NetworkFileResponse(
    val name: String,
    val size: Long,
    @com.squareup.moshi.Json(name = "updatedAt") val updatedAtCamel: Long? = null,
    @com.squareup.moshi.Json(name = "updated_at") val updatedAtSnake: Long? = null
) {
    val updatedAt: Long get() = updatedAtCamel ?: updatedAtSnake ?: 0L
}

data class NetworkUploadFileRequest(
    val fileName: String,
    val content: String = "",
    val contentBase64: String = ""
)

// Client configuration & dynamic Retrofit manager
class NetAuthClientManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("net_auth_prefs", Context.MODE_PRIVATE)
    private val secureStore = SecureStore(context)

    init {
        // Beta 0.1.1 never persists custom endpoints or server lists.
        prefs.edit()
            .remove("server_url")
            .remove("active_database")
            .apply()
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://orvexaauth-api.bot724524.workers.dev/"
    }

    /** The beta client has one public Cloudflare endpoint and does not save server settings. */
    val serverUrl: String
        get() = DEFAULT_SERVER_URL

    var language: String
        get() = prefs.getString("app_lang", "en") ?: "en"
        set(value) {
            prefs.edit().putString("app_lang", value).apply()
        }

    var appPasscode: String
        get() = secureStore.getString("app_passcode") ?: ""
        set(value) {
            if (value.isBlank()) secureStore.remove("app_passcode") else secureStore.putString("app_passcode", value)
        }

    val deviceMac: String
        get() {
            var mac = prefs.getString("device_mac", null)
            if (mac == null) {
                mac = (1..6).joinToString(":") { String.format("%02X", (0..255).random()) }
                prefs.edit().putString("device_mac", mac).apply()
            }
            return mac
        }

    val deviceIp: String
        get() {
            var ip = prefs.getString("device_ip", null)
            if (ip == null) {
                ip = "192.168.49.${(2..254).random()}"
                prefs.edit().putString("device_ip", ip).apply()
            }
            return ip
        }

    var blockedUsers: Set<String>
        get() = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet("blocked_users", value).apply()
        }

    fun saveSession(userEmail: String, passwordHash: String) {
        secureStore.putString("session_email", userEmail)
        secureStore.putString("session_password_hash", passwordHash)
    }

    fun clearSession() {
        secureStore.remove("session_email")
        secureStore.remove("session_password_hash")
    }

    fun getSessionEmail(): String = secureStore.getString("session_email") ?: ""
    fun getSessionPasswordHash(): String = secureStore.getString("session_password_hash") ?: ""

    fun saveLoggedInUser(user: User) {
        prefs.edit()
            .putInt("user_id", user.id)
            .putString("user_email", user.email)
            // The password hash is secret session material; it is stored below in Keystore.
            .putString("user_first_name", user.firstName)
            .putString("user_last_name", user.lastName)
            .putString("user_birth_date", user.birthDate)
            .putString("user_gender", user.gender)
            .putInt("user_avatar_color", user.avatarColor)
            .putString("user_ip", user.ipAddress)
            .putString("user_mac", user.macAddress)
            .putString("user_key_protect", user.keyProtect)
            .putInt("user_quota", user.dataQuotaMb)
            .putLong("user_created_at", user.createdAt)
            .apply()
        secureStore.putString("user_password_hash", user.passwordHash)
    }

    fun getLoggedInUser(): User? {
        val email = prefs.getString("user_email", null) ?: return null
        return User(
            id = prefs.getInt("user_id", 0),
            email = email,
            passwordHash = secureStore.getString("user_password_hash") ?: "",
            firstName = prefs.getString("user_first_name", "") ?: "",
            lastName = prefs.getString("user_last_name", "") ?: "",
            birthDate = prefs.getString("user_birth_date", "") ?: "",
            gender = prefs.getString("user_gender", "") ?: "",
            avatarColor = prefs.getInt("user_avatar_color", 0),
            ipAddress = prefs.getString("user_ip", "") ?: "",
            macAddress = prefs.getString("user_mac", "") ?: "",
            keyProtect = prefs.getString("user_key_protect", "") ?: "",
            dataQuotaMb = prefs.getInt("user_quota", 200),
            createdAt = prefs.getLong("user_created_at", 0L)
        )
    }

    fun clearLoggedInUser() {
        secureStore.remove("user_password_hash")
        prefs.edit()
            .remove("user_id")
            .remove("user_email")
            .remove("user_first_name")
            .remove("user_last_name")
            .remove("user_birth_date")
            .remove("user_gender")
            .remove("user_avatar_color")
            .remove("user_ip")
            .remove("user_mac")
            .remove("user_key_protect")
            .remove("user_quota")
            .remove("user_created_at")
            .apply()
    }

    private var cachedService: NetAuthService? = null

    @Synchronized
    fun getService(): NetAuthService {
        return cachedService ?: rebuildService()
    }

    private fun rebuildService(): NetAuthService {
        val logging = HttpLoggingInterceptor().apply {
            // Never log request metadata for authentication traffic, even in debug builds.
            level = HttpLoggingInterceptor.Level.NONE
        }

        // Interceptor to inject headers dynamically
        val headerInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-App-Name", "OrvexaAuthAndroid-Beta")
                .build()
            chain.proceed(request)
        }
        
        val okHttpClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(headerInterceptor)
            .addInterceptor(logging)
            .build()

        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()

        val baseUrl = try {
            serverUrl
        } catch (e: Exception) {
            DEFAULT_SERVER_URL
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val service = retrofit.create(NetAuthService::class.java)
        cachedService = service
        return service
    }
}
