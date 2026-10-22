package com.skeler.scanely.core.network

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * A user-supplied endpoint, of whatever protocol.
 *
 * The full URL always arrives via `@Url`; [BASE_URL] exists only because Retrofit demands a base.
 * See `EndpointUrl` for why a scheme-less URL must never reach here. Headers are a map because the
 * auth header name is the user's choice, and the body is a [JsonObject] because the three supported
 * protocols share no schema.
 *
 * Returns a raw [Response] so a 4xx is a value to classify, not an exception to catch — its body is
 * what tells a bad key from a bad model.
 */
interface CustomProviderApi {

    @POST
    suspend fun post(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: JsonObject
    ): Response<ResponseBody>

    companion object {
        const val BASE_URL = "https://api.openai.com/"
    }
}
