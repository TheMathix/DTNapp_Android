// model/IncomingPayload.kt
package com.example.mydtnapp.model

data class Detection(
    val Date: String,
    val Time: String,
    val Sci_Name: String,
    val Com_Name: String,
    val Confidence: Double,
    val Lat: Double,
    val Lon: Double,
    val Cutoff: Double,
    val Week: Int,
    val Sens: Double,
    val Overlap: Double,
    val File_Name: String
)

data class IncomingPayload(
    val source_eid: String,
    val bundle_id: String,
    val detections: List<Detection>,
    val mp3_data: String
)
