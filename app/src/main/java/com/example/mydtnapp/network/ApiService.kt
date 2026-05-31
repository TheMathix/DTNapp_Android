// network/ApiService.kt
package com.example.mydtnapp.network

import com.example.mydtnapp.model.StatusBundle
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ApiService {
    @GET("status/bundles/verbose")
    suspend fun listBundleStatus(): List<String>

    @GET
    @Streaming
    suspend fun downloadBundle(@Url relativeUrl: String): Response<ResponseBody>

    @GET
    suspend fun deleteBundle(@Url relativeUrl: String): Response<ResponseBody>

    @POST("send")
    suspend fun sendBundle(
        @Query("dst") dst: String,
        @Query("lifetime") lifetime: String = "1h",
        @Body body: RequestBody
    ): Response<ResponseBody>
}
