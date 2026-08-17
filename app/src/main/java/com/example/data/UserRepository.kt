package com.example.data

import kotlinx.coroutines.flow.Flow

class UserRepository(
    val userDao: UserDao,
    val messageDao: MessageDao
) {
    val allUsers: Flow<List<User>> = userDao.getAllUsers()

    suspend fun getUserByEmail(email: String): User? {
        return userDao.getUserByEmail(email)
    }

    suspend fun getUserByFirstAndLastName(firstName: String, lastName: String): User? {
        return userDao.getUserByFirstAndLastName(firstName, lastName)
    }

    suspend fun getUserById(id: Int): User? {
        return userDao.getUserById(id)
    }

    suspend fun insertUser(user: User): Long {
        return userDao.insertUser(user)
    }

    suspend fun updateUser(user: User) {
        userDao.updateUser(user)
    }

    suspend fun deleteUser(user: User, currentPasswordHash: String) {
        userDao.deleteUser(user, currentPasswordHash)
    }

    // Messaging operations
    fun getChatMessages(user1: String, user2: String): Flow<List<Message>> {
        return messageDao.getChatMessages(user1, user2)
    }

    fun getChatPartners(userEmail: String): Flow<List<String>> {
        return messageDao.getChatPartners(userEmail)
    }

    suspend fun insertMessage(message: Message) {
        messageDao.insertMessage(message)
    }

    suspend fun deleteChat(user1: String, user2: String) {
        messageDao.deleteChat(user1, user2)
    }

    suspend fun deleteMessage(messageId: Int) {
        messageDao.deleteMessage(messageId)
    }
}
