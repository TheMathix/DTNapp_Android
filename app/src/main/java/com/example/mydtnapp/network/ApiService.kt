// network/ApiService.kt
package com.example.mydtnapp.network

import com.example.mydtnapp.model.StatusBundle
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ApiService {
    /** 1) Lista todos os bundles com ID e endpoint */
    @GET("status/bundles/verbose")
    suspend fun listBundleStatus(): List<String>

    /** 2) Baixa o raw bundle (bytes) */
    @GET
    @Streaming
    suspend fun downloadBundle(
        @Url relativeUrl: String    // ex: "download?{bundleId}"
    ): Response<ResponseBody>

    /** 3) Deleta um bundle ou ACK */
    @GET
    suspend fun deleteBundle(
        @Url relativeUrl: String    // ex: "delete?{bundleId}"
    ): Response<ResponseBody>
}
