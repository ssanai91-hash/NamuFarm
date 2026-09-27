package com.example.namufarm.data.remote

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// 🚨 1. 주소 데이터 클래스 (행안부 규격)
data class JusoResponse(val results: JusoResults)
data class JusoResults(val common: JusoCommon, val juso: List<JusoItem>?)
data class JusoCommon(val totalCount: String, val errorCode: String, val errorMessage: String)
data class JusoItem(
    val roadAddr: String,      // 전체 도로명 주소
    val jibunAddr: String,     // 지번 주소
    val zipNo: String          // 우편번호
)

// 🚨 2. 주소 API 인터페이스
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
    // 🚨 브로의 최신 구글 웹앱 URL 반영 완료! (반드시 /로 끝나야 함)
    private const val BASE_URL = "https://example.com/"

    // 🚨 [핵심] 구글 서버의 느린 응답을 30초까지 기다려주는 인내심 엔진! (SocketTimeoutException 방어)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 🌳 구글 시트 API용 레트로핏 객체
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient) // 👈 30초 방패 장착 완료!
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: GoogleSheetApi = retrofit.create(GoogleSheetApi::class.java)

    // 🏠 행안부 주소 API 주소
    private const val JUSO_URL = "https://business.juso.go.kr/addrlink/"

    // 🏠 행안부 주소 검색용 레트로핏 객체
    val jusoApi: JusoApi by lazy {
        Retrofit.Builder()
            .baseUrl(JUSO_URL)
            .client(okHttpClient) // 👈 여기도 혹시 모르니 30초 방패 같이 달아줌!
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JusoApi::class.java)
    }
}