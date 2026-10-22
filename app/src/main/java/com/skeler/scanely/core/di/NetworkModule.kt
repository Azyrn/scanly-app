package com.skeler.scanely.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The client every other one is built from. Qualified on purpose: the unqualified OkHttpClient is
 * LookupModule's, and PaddleModelStore downloads OCR packs through it.
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
 * Each client had its own Dispatcher before they shared a base, and still does.
 */
fun OkHttpClient.derive(): OkHttpClient.Builder = newBuilder().dispatcher(Dispatcher())
