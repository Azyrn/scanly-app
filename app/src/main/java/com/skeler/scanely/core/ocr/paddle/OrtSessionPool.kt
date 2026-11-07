package com.skeler.scanely.core.ocr.paddle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

/**
 * The ONNX sessions [PaddleOcrEngine] runs on: opened lazily from [store], kept across scans, and
 * closed together — never while inference is running on them.
 */
internal class OrtSessionPool(
    private val context: Context,
    private val store: PaddleModelStore
) {

    val env: OrtEnvironment get() = OrtEnvironment.getEnvironment()

    // XNNPACK and NNAPI both abort natively on these graphs; plain CPU is the safe EP.
    // Above 4 threads the little cores drag the batch out (~2x slower on big.LITTLE).
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    // The CPU arena keeps the largest buffers any crop or page shape ever needed and never gives
    // them back (scan_ar_wiki peaked at 1.25 GB); shrinking it after each run keeps its speed
    // within a run without the growth. Lives as long as the engine, like the environment.
    val runOptions: OrtSession.RunOptions = OrtSession.RunOptions().apply {
        addRunConfigEntry("memory.enable_memory_arena_shrinkage", "cpu:0")
    }

    // Closed as soon as the session is built: ORT copies the options into the session, and a
    // SessionOptions left open holds its native handle for the life of the process.
    private fun createSession(model: ByteArray): OrtSession =
        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(threads)
            // Memory patterns assume fixed shapes; every crop and page has its own.
            options.setMemoryPatternOptimization(false)
            env.createSession(model, options)
        }

    private val sessions = HashMap<String, OrtSession>()

    // Inference holds the read lock; close() needs the write lock, so it can never
    // free a session while native code is running on it. If close() arrives mid-run
    // it defers, and the last finishing inference performs it.
    private val sessionLock = ReentrantReadWriteLock()

    @Volatile
    private var closePending = false

    private val recCharsets = HashMap<ScriptPack, List<String>>()

    /**
     * Registered only while sessions are open, so memory pressure reaches the one component that
     * holds native memory without the Application having to build the OCR graph to ask.
     */
    private val trimCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) close()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Required by ComponentCallbacks; onTrimMemory covers the same signal.")
        override fun onLowMemory() = close()
    }

    private var trimCallbackRegistered = false

    /** The session for a bundled model asset. */
    @Synchronized
    fun asset(asset: String): OrtSession =
        openSession(asset) { createSession(store.asset(asset)) }

    /** The session for a downloaded model, read by [model] the first time. */
    @Synchronized
    fun downloaded(key: String, model: () -> ByteArray): OrtSession =
        openSession(key) { createSession(model()) }

    private fun openSession(key: String, create: () -> OrtSession): OrtSession {
        val session = sessions.getOrPut(key, create)
        if (!trimCallbackRegistered) {
            context.registerComponentCallbacks(trimCallback)
            trimCallbackRegistered = true
        }
        return session
    }

    // One session per pack, kept until close(). Closing the old session on a pack switch
    // would free native memory another thread may still be running inference on: the
    // read lock is shared, so it does not order concurrent recognize() calls.
    @Synchronized
    fun recognizer(pack: ScriptPack): Pair<OrtSession, List<String>> {
        val effective = if (pack.isBundled || store.isInstalled(pack)) pack else ScriptPack.DEFAULT
        val session = openSession("rec/${effective.id}") {
            val (bytes, charset) = store.recModel(effective)
            recCharsets[effective] = charset
            createSession(bytes)
        }
        return session to recCharsets.getValue(effective)
    }

    /** Runs [block] under the read lock, so no session it uses can be closed under it. */
    fun <T> withSessions(block: () -> T): T {
        try {
            return sessionLock.read(block)
        } finally {
            if (closePending) close()
        }
    }

    fun <T> run(
        session: OrtSession,
        input: FloatBuffer,
        shape: LongArray,
        inputName: String? = null,
        decode: (FloatBuffer, LongArray) -> T
    ): T {
        val name = inputName ?: session.inputNames.first()
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            session.run(mapOf(name to tensor), runOptions).use { result ->
                val output = result[0] as OnnxTensor
                return decode(output.floatBuffer, output.info.shape)
            }
        }
    }

    fun close() {
        val write = sessionLock.writeLock()
        if (!write.tryLock()) {
            closePending = true
            return
        }
        try {
            closePending = false
            synchronized(this) {
                sessions.values.forEach { runCatching { it.close() } }
                sessions.clear()
                recCharsets.clear()
                if (trimCallbackRegistered) {
                    context.unregisterComponentCallbacks(trimCallback)
                    trimCallbackRegistered = false
                }
            }
        } finally {
            write.unlock()
        }
    }

    fun openSessionCount(): Int = synchronized(this) { sessions.size }
}
