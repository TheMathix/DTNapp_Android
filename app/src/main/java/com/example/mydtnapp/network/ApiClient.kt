// network/ApiClient.kt
package com.example.mydtnapp.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

object ApiClient {
    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .build()

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1:3000/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())     // para JSON de StatusBundle
            .addConverterFactory(ScalarsConverterFactory.create()) // para bytes/texto puro
            .build()
            .create(ApiService::class.java)
    }
}
