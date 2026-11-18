package com.skeler.scanely.core.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.BuildConfig
import com.skeler.scanely.core.network.ApiJson
import com.skeler.scanely.core.network.BackendAuthInterceptor
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.ScanlyPublicApi
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * [ScanlyApi] for `/v1/me` and the licence calls. They answer small JSON in well under a second,
 * so they must not share the scan client's five-minute read timeout: a stalled Worker left the
 * paywall spinning for that long.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackendControl

@Module
@InstallIn(SingletonComponent::class)
object GenerativeAiModule {

    @Provides
    @Singleton
    fun provideScanlyApi(
        @BaseHttpClient base: Lazy<OkHttpClient>,
        authInterceptor: BackendAuthInterceptor
    ): ScanlyApi = retrofit(BuildConfig.SCANLY_API_BASE_URL) {
        base.get().derive()
            .addInterceptor(authInterceptor)
            // Only the application knows which billed operations have replay keys.
            .retryOnConnectionFailure(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Large PDF scans can keep the Worker connection open for several minutes.
            .readTimeout(SCAN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(SCAN_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }.create(ScanlyApi::class.java)

    @Provides
    @Singleton
    @BackendControl
    fun provideBackendControlApi(
        @BaseHttpClient base: Lazy<OkHttpClient>,
        authInterceptor: BackendAuthInterceptor
    ): ScanlyApi = backendControlApi(base::get, authInterceptor, BuildConfig.SCANLY_API_BASE_URL)

    /**
     * 20 seconds for the whole call — connect, TLS, request and answer. It is the connection
     * test's budget too: long enough for a cold mobile connection and a Worker cold start, short
     * enough that the user is still looking at the screen when the error replaces the spinner.
     */
    internal fun backendControlApi(
        base: OkHttpClient,
        authInterceptor: Interceptor,
        baseUrl: String
    ): ScanlyApi = backendControlApi({ base }, authInterceptor, baseUrl)

    private fun backendControlApi(
        base: () -> OkHttpClient,
        authInterceptor: Interceptor,
        baseUrl: String
    ): ScanlyApi = retrofit(baseUrl) {
        base().derive()
            .addInterceptor(authInterceptor)
            // As the scan client: activation writes a row, so a silent replay is not free.
            .retryOnConnectionFailure(false)
            .connectTimeout(CONTROL_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }.create(ScanlyApi::class.java)

    /** Unauthenticated client for small public API calls. */
    @Provides
    @Singleton
    fun provideScanlyPublicApi(@BaseHttpClient base: Lazy<OkHttpClient>): ScanlyPublicApi =
        retrofit(BuildConfig.SCANLY_API_BASE_URL) {
            base.get().derive()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PUBLIC_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }.create(ScanlyPublicApi::class.java)

    /** [client] is built on the first request: see [lazyCallFactory]. */
    private fun retrofit(baseUrl: String, client: () -> OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .callFactory(lazyCallFactory(client))
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()

    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val SCAN_READ_TIMEOUT_SECONDS = 300L
    private const val SCAN_WRITE_TIMEOUT_SECONDS = 120L
    private const val PUBLIC_READ_TIMEOUT_SECONDS = 30L
    private const val CONTROL_CONNECT_TIMEOUT_SECONDS = 10L
    private const val CONTROL_TIMEOUT_SECONDS = 20L
}
