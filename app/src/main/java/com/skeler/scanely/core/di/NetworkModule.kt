package com.skeler.scanely.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The client every other one is built from, and the one PaddleModelStore downloads OCR models
 * through. Qualified on purpose: the unqualified OkHttpClient is LookupModule's.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @BaseHttpClient
    fun provideBaseHttpClient(): OkHttpClient = OkHttpClient()
}

/**
 * A builder that shares this client's connection pool but keeps its own request queue.
 *
 * `newBuilder()` alone would share the Dispatcher too, and OkHttp's Dispatcher admits five
 * concurrent calls per host: a few five-minute scans would then queue a plan check behind them.
 */
fun OkHttpClient.derive(): OkHttpClient.Builder = newBuilder().dispatcher(Dispatcher())

/**
 * A Retrofit call factory that builds its client on the first request, not when the API is made.
 *
 * The Retrofit services are injected on the main thread before the first frame — App.onCreate's
 * FailureReporter, MainActivity's licence and entitlement repositories — and the first
 * OkHttpClient sets up the platform TLS stack (trust manager, SSLContext). Requests run on
 * background dispatchers, so that cost moves there. The client [build] returns is the same one a
 * `.client(...)` call would have been given.
 */
fun lazyCallFactory(build: () -> OkHttpClient): Call.Factory = object : Call.Factory {
    private val client by lazy(build)

    override fun newCall(request: Request): Call = client.newCall(request)
}
