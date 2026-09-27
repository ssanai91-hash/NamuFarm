package com.example.namufarm.data.remote

import com.example.namufarm.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class JusoResponse(val results: JusoResults)
data class JusoResults(val common: JusoCommon, val juso: List<JusoItem>?)
data class JusoCommon(
    val totalCount: String,
    val errorCode: String,
    val errorMessage: String
)

data class JusoItem(
    val roadAddr: String,
    val jibunAddr: String,
    val zipNo: String
)

interface JusoApi {
    @GET("addrLinkApi.do")
    suspend fun searchAddress(
        @Query("confmKey") apiKey: String,
        @Query("keyword") keyword: String,
        @Query("resultType") resultType: String = "json",
        @Query("countPerPage") count: Int = 20
    ): JusoResponse
}

object RetrofitClient {
    private val BASE_URL = BuildConfig.GOOGLE_SCRIPT_URL

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: GoogleSheetApi = retrofit.create(GoogleSheetApi::class.java)

    private const val JUSO_URL = "https://business.juso.go.kr/addrlink/"

    val jusoApi: JusoApi by lazy {
        Retrofit.Builder()
            .baseUrl(JUSO_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JusoApi::class.java)
    }
}
