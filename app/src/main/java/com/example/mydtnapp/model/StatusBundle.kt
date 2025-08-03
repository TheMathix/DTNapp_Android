// model/StatusBundle.kt
package com.example.mydtnapp.model

import com.google.gson.annotations.SerializedName

data class StatusBundle(
    @SerializedName("bundle_id") val id: String,
    @SerializedName("endpoint")  val endpoint: String
)


data class ControlFlags(
    @SerializedName("is_admin_record") val isAdminRecord: Boolean,
    @SerializedName("ack_requested") val ackRequested: Boolean
)
