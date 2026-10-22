package com.skeler.scanely.docchat.data

import android.content.Context
import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.BuildConfig
import com.skeler.scanely.core.di.BaseHttpClient
import com.skeler.scanely.core.di.derive
import com.skeler.scanely.core.network.ApiJson
import com.skeler.scanely.core.network.BackendAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase =
        Room.databaseBuilder(context, ChatDatabase::class.java, "doc_chat.db").build()

    @Provides
    fun provideChatDao(database: ChatDatabase): ChatDao = database.chatDao()

    /**
     * History calls are small JSON and answer fast; a design's PDF is a recompile on the Worker's
     * container, a few seconds, so the whole call gets a minute.
     */
    @Provides
    @Singleton
    fun provideChatSyncApi(
        @BaseHttpClient base: OkHttpClient,
        authInterceptor: BackendAuthInterceptor
    ): ChatSyncApi = Retrofit.Builder()
        .baseUrl(BuildConfig.SCANLY_API_BASE_URL)
        .client(
            base.derive()
                .addInterceptor(authInterceptor)
                .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ChatSyncApi::class.java)

    /**
     * The same routes for the agent's live events (GET /v1/agent/turns/{id}/events), a stream that
     * stays open for the whole turn: no call timeout (60 s cut every stream short), and a read timeout
     * of three of the Worker's 15 s pings (worker/src/agent-turn.ts SSE_PING_MS), so a model thinking
     * quietly never ends it. OkHttp's default 10 s read timeout had: the app then polled every 3 s.
     */
    @Provides
    @Singleton
    @AgentEventsApi
    fun provideAgentEventsApi(
        @BaseHttpClient base: OkHttpClient,
        authInterceptor: BackendAuthInterceptor
    ): ChatSyncApi = Retrofit.Builder()
        .baseUrl(BuildConfig.SCANLY_API_BASE_URL)
        .client(
            base.derive()
                .addInterceptor(authInterceptor)
                .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(EVENTS_READ_SECONDS, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ChatSyncApi::class.java)

    private const val CONNECT_SECONDS = 15L
    private const val CALL_SECONDS = 60L
    private const val EVENTS_READ_SECONDS = 45L
}

/** The [ChatSyncApi] whose client keeps a live event stream open (ChatModule.provideAgentEventsApi). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AgentEventsApi
