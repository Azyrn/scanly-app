package com.skeler.scanely.testing

import android.content.Context
import com.skeler.scanely.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock

/**
 * A Context whose getString answers with the app's real English text from res/values, so JVM
 * tests assert the message a user would read rather than a resource id.
 */
object EnglishStrings {

    private val byName: Map<String, String> by lazy {
        val dir = listOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }
        val factory = DocumentBuilderFactory.newInstance()
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
            val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { i ->
                val node = nodes.item(i)
                node.attributes.getNamedItem("name").nodeValue to unescape(node.textContent)
            }
        }.toMap()
    }

    private val nameById: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    fun get(id: Int, vararg args: Any?): String {
        val text = byName.getValue(nameById.getValue(id))
        return if (args.isEmpty()) text else String.format(text, *args)
    }

    /** Every getString overload answers from res/values; any other call returns the type's default. */
    fun context(): Context = mock(Context::class.java, ::answer)

    private fun answer(call: InvocationOnMock): Any? = when {
        call.method.name != "getString" -> RETURNS_DEFAULTS.answer(call)
        call.arguments.size == 1 -> get(call.arguments[0] as Int)
        // Mockito expands the vararg, so the format arguments follow the id.
        else -> get(call.arguments[0] as Int, *call.arguments.drop(1).toTypedArray())
    }

    private fun unescape(raw: String): String =
        raw.trim().removeSurrounding("\"").replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@")
}
