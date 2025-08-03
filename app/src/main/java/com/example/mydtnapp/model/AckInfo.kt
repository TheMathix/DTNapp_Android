package com.example.mydtnapp.model

data class AckInfo(
    val id: String,         // ID do próprio bundle de ACK
    val bundle_id: String    // ID do bundle original que foi confirmado
)
