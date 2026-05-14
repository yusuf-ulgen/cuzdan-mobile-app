package com.yusufulgen.cuzdan.data.remote.api

import com.yusufulgen.cuzdan.data.remote.model.YahooFinanceResponse
import com.yusufulgen.cuzdan.data.remote.model.YahooQuoteResponse
import com.yusufulgen.cuzdan.data.remote.model.YahooSearchResponse
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

interface YahooFinanceApi {
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept: */*",
        "Connection: keep-alive"
    )
    @GET("v8/finance/chart/{symbol}")
    suspend fun getChartData(
        @Path("symbol") symbol: String,
        @Query("range") range: String = "1d",
        @Query("interval") interval: String = "1m"
    ): YahooFinanceResponse

    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept: */*",
        "Connection: keep-alive"
    )
    @GET("v1/finance/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("quotesCount") quotesCount: Int = 10,
        @Query("newsCount") newsCount: Int = 0
    ): YahooSearchResponse

    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept: */*",
        "Connection: keep-alive"
    )
    @GET("v7/finance/quote")
    suspend fun getQuotes(
        @Query("symbols") symbols: String,
        @Query("fields") fields: String = "regularMarketPrice,regularMarketChangePercent,regularMarketPreviousClose,regularMarketOpen,regularMarketTime,shortName,longName,currency"
    ): YahooQuoteResponse
}
