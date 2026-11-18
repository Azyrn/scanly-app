package com.skeler.scanely.core.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.core.network.ApiJson
import com.skeler.scanely.core.network.CustomProviderApi
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The short-timeout client the connection test uses. Scans inject the unqualified one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProviderProbe

/**
 * Clients for user-supplied endpoints. Separate from [GenerativeAiModule] because these carry no
 * `BackendAuthInterceptor`: the destination is a URL the user typed, and a Firebase ID token must
 * never reach a third-party host.
 *
 * Two clients because one timeout cannot serve both callers. Completions are non-streaming and can
 * take minutes; a connection test the user is watching has to fail fast enough to read as an answer.
 */
@Module
@InstallIn(SingletonComponent::class)
object CustomProviderModule {

    @Provides
    @Singleton
    fun provideCustomProviderApi(@BaseHttpClient base: Lazy<OkHttpClient>): CustomProviderApi =
        api(base, connectSeconds = 30, readSeconds = 300, writeSeconds = 120)

    @Provides
    @Singleton
    @ProviderProbe
    fun provideProbeApi(@BaseHttpClient base: Lazy<OkHttpClient>): CustomProviderApi =
        api(
            base,
            connectSeconds = TEST_TIMEOUT_SECONDS,
            readSeconds = TEST_TIMEOUT_SECONDS,
            writeSeconds = TEST_TIMEOUT_SECONDS
        )

    private fun api(
        base: Lazy<OkHttpClient>,
        connectSeconds: Long,
        readSeconds: Long,
        writeSeconds: Long
    ): CustomProviderApi =
        Retrofit.Builder()
            .baseUrl(CustomProviderApi.BASE_URL)
            .callFactory(
                lazyCallFactory {
                    // No auth interceptor: the base client has none, and this must never gain one.
                    base.get().derive()
                        .addInterceptor(CallerHeaders)
                        .addNetworkInterceptor(SameOriginCredentials)
                        .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                        .readTimeout(readSeconds, TimeUnit.SECONDS)
                        .writeTimeout(writeSeconds, TimeUnit.SECONDS)
                        .build()
                }
            )
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CustomProviderApi::class.java)

    private const val TEST_TIMEOUT_SECONDS = 20L
}

/** Where a call to a user's endpoint was sent, and the headers the app gave it (its key among them). */
private class CallerOrigin(val url: HttpUrl, val headerNames: Set<String>)

/** Records [CallerOrigin] on the call, before OkHttp adds headers of its own; redirects keep the tag. */
private object CallerHeaders : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = CallerOrigin(request.url, request.headers.names())
        return chain.proceed(request.newBuilder().tag(CallerOrigin::class.java, origin).build())
    }
}

/**
 * OkHttp follows a redirect to another host and drops only `Authorization` on the way
 * (RetryAndFollowUpInterceptor.buildRedirectRequest), so a key in `x-api-key`, `x-goog-api-key`,
 * Azure's `api-key` or the user's own header went to whatever host the endpoint redirected to. Every
 * hop to another origin (scheme, host or port) now goes without the headers the app set; a redirect
 * within the endpoint's own origin keeps them.
 */
private object SameOriginCredentials : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = request.tag(CallerOrigin::class.java) ?: return chain.proceed(request)
        val url = request.url
        if (url.scheme == origin.url.scheme && url.host == origin.url.host && url.port == origin.url.port) {
            return chain.proceed(request)
        }
        val stripped = request.newBuilder()
        origin.headerNames.forEach { stripped.removeHeader(it) }
        return chain.proceed(stripped.build())
    }
}
