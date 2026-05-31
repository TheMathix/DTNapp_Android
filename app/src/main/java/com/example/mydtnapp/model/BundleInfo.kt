package com.example.mydtnapp.model

data class BundleInfo(
    val bundleId: String,
    val birdNames: List<String>,
    val ackSent: Boolean,
    val audioPath: String? = null
)
