package com.example.data

data class User(
    val id: Int = 0,
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
    val dataQuotaMb: Int = 200,
    val createdAt: Long = System.currentTimeMillis()
)

data class BannedHardware(
    val hardwareValue: String,
    val banType: String = "IP", // "IP" or "MAC"
    val bannedAt: Long = System.currentTimeMillis()
)

