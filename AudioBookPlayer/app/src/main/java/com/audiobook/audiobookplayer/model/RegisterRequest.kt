package com.audiobook.audiobookplayer.model

data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String
)

