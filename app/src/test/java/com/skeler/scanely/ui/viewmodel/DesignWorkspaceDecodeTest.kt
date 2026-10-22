package com.skeler.scanely.ui.viewmodel

import com.skeler.scanely.core.network.StoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesignWorkspaceDecodeTest {
    /** What the old designer saved (3.5) still reads, so its last workspace can become a chat. */
    @Test fun anOldWorkspaceStillDecodes() {
        val old = StoreJson.decodeFromString<DesignWorkspace>(
            """{"prompt":"Menu","versionIndex":0,"versions":[{"uri":"file:///d/a.pdf","id":"a","pages":2,"billed":1}]}"""
        )
        assertEquals("Menu", old.prompt)
        assertNull(old.templateId)
        assertEquals(SavedDesignVersion("file:///d/a.pdf", "a", 2, 1), old.versions.single())
    }
}
