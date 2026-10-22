package com.skeler.scanely.core.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.core.network.ApiJson
import com.skeler.scanely.core.network.CustomProviderApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
    fun provideCustomProviderApi(@BaseHttpClient base: OkHttpClient): CustomProviderApi =
        api(base, connectSeconds = 30, readSeconds = 300, writeSeconds = 120)

    @Provides
    @Singleton
    @ProviderProbe
    fun provideProbeApi(@BaseHttpClient base: OkHttpClient): CustomProviderApi =
        api(
            base,
            connectSeconds = TEST_TIMEOUT_SECONDS,
            readSeconds = TEST_TIMEOUT_SECONDS,
            writeSeconds = TEST_TIMEOUT_SECONDS
        )

    private fun api(
        base: OkHttpClient,
        connectSeconds: Long,
        readSeconds: Long,
        writeSeconds: Long
    ): CustomProviderApi =
        Retrofit.Builder()
            .baseUrl(CustomProviderApi.BASE_URL)
            .client(
                // No auth interceptor: the base client has none, and this must never gain one.
                base.derive()
                    .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                    .readTimeout(readSeconds, TimeUnit.SECONDS)
                    .writeTimeout(writeSeconds, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CustomProviderApi::class.java)

    private const val TEST_TIMEOUT_SECONDS = 20L
}
