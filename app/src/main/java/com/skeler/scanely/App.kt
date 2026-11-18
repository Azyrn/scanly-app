package com.skeler.scanely

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.skeler.scanely.core.ai.ConversionCharges
import com.skeler.scanely.core.common.AppLanguage
import com.skeler.scanely.core.di.BaseHttpClient
import com.skeler.scanely.core.reports.FailureReporter
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Inject
import kotlin.concurrent.thread

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject
    lateinit var failureReporter: FailureReporter

    @Inject
    @BaseHttpClient
    lateinit var baseHttpClient: dagger.Lazy<OkHttpClient>

    @Inject
    lateinit var conversionCharges: dagger.Lazy<ConversionCharges>

    override fun onCreate() {
        super.onCreate()
        failureReporter.install()
        warmUpHttpClient()
        initializeWorkManager()
    }

    /** What WorkManagerInitializer used: the default configuration. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /**
     * WorkManager used to initialize in androidx.startup's provider, on the main thread, before
     * Application.onCreate. It is now on demand, but still started at every process start, as
     * before: initialization is also what reschedules pending work after a force stop. Any other
     * caller of getInstance waits for this one instead of initializing twice.
     */
    private fun initializeWorkManager() {
        thread(name = WORK_MANAGER_INIT_THREAD, isDaemon = true) { WorkManager.getInstance(this) }
    }

    /**
     * Builds the shared OkHttpClient off the main thread. Its first construction sets up the
     * platform TLS stack; the Retrofit services build their clients on first request
     * (lazyCallFactory), and the first requests start right after Home is composed — some from the
     * main thread. Started here, the work is normally done before then; if not, the caller waits
     * only for what is left, never longer than building it itself.
     */
    private fun warmUpHttpClient() {
        thread(name = HTTP_WARM_UP_THREAD, isDaemon = true) {
            baseHttpClient.get()
            // Created at start so it asks again for refunds an earlier run kept (credits for a read
            // whose answer was lost); nothing on Home injects it, so it would otherwise wait for a
            // PDF tool. It only reads a file unless a refund is kept.
            conversionCharges.get()
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_FRACTION)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(IMAGE_CACHE_BYTES)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()

    private companion object {
        const val IMAGE_CACHE_BYTES = 100L * 1024 * 1024
        const val MEMORY_CACHE_FRACTION = 0.25
        const val HTTP_WARM_UP_THREAD = "http-warm-up"
        const val WORK_MANAGER_INIT_THREAD = "work-manager-init"
    }
}
