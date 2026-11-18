package com.skeler.scanely.startup

import androidx.work.Configuration
import com.skeler.scanely.App
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * work-runtime registers WorkManagerInitializer with androidx.startup, which runs it on the main
 * thread in every process start, before Application.onCreate. The app removes it and initializes
 * WorkManager on demand (developer.android.com, "Custom WorkManager configuration and
 * initialization"): that needs both the manifest removal and Configuration.Provider on the
 * Application, or WorkManager.getInstance throws.
 */
class WorkManagerOnDemandTest {

    private val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .first { it.isFile }

    @Test
    fun `the default WorkManager initializer is removed from the manifest`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val metas = doc.getElementsByTagName("meta-data")
        val removed = (0 until metas.length).map { metas.item(it) }.any { node ->
            val attrs = node.attributes
            attrs.getNamedItemNS(ANDROID_NS, "name")?.nodeValue == WORK_INITIALIZER &&
                attrs.getNamedItemNS(TOOLS_NS, "node")?.nodeValue == "remove"
        }
        assertTrue("$WORK_INITIALIZER is not removed in $manifest", removed)
    }

    @Test
    fun `the application supplies WorkManager's configuration`() {
        assertTrue(Configuration.Provider::class.java.isAssignableFrom(App::class.java))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"
        const val WORK_INITIALIZER = "androidx.work.WorkManagerInitializer"
    }
}
