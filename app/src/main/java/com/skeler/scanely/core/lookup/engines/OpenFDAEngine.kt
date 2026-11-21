package com.skeler.scanely.core.lookup.engines

import android.util.Log
import com.skeler.scanely.core.lookup.LookupEngine
import com.skeler.scanely.core.lookup.LookupJson
import com.skeler.scanely.core.lookup.LookupResult
import com.skeler.scanely.core.lookup.MedicineData
import com.skeler.scanely.core.lookup.ProductCategory
import com.skeler.scanely.core.lookup.ProductInfo
import com.skeler.scanely.core.lookup.await
import com.skeler.scanely.core.lookup.lookupBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OpenFDAEngine"
private const val MAX_DESCRIPTION_CHARS = 500
private const val LABELS_URL = "https://api.fda.gov/drug/label.json"

@Singleton
class OpenFDAEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) : LookupEngine {

    override val name = "OpenFDA"
    override val priority = 3
    override val category = ProductCategory.MEDICINE

    override fun supports(barcode: String): Boolean {
        val digits = barcode.filter { it.isDigit() }
        return digits.length in 10..13
    }

    override suspend fun lookup(barcode: String): LookupResult = withContext(Dispatchers.IO) {
        // UPC first, then package NDC.
        val urls = OpenFdaSearch.queries(barcode).map { "$LABELS_URL?search=$it&limit=1" }

        for (url in urls) {
            Log.d(TAG, "Trying: $url")

            val request = Request.Builder().url(url).build()
            // A 404 is openFDA's "no match", so the other identifier is tried.
            val body = okHttpClient.newCall(request).await().use { it.lookupBody(name) } ?: continue

            val product = parseProduct(barcode, url, body)
            if (product != null) {
                Log.d(TAG, "Found: ${product.name}")
                return@withContext LookupResult.Found(product, name)
            }
        }

        Log.d(TAG, "Not found: $barcode")
        LookupResult.NotFound(name)
    }

    /** The first label in [body], or null when it has none or does not parse. */
    private fun parseProduct(barcode: String, url: String, body: String): ProductInfo? = try {
        LookupJson.decodeFromString<OpenFDAResponse>(body).results?.firstOrNull()?.let {
            mapToProductInfo(barcode, it)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Parse error for $url: ${e.message}")
        null
    }

    private fun mapToProductInfo(barcode: String, result: DrugLabel): ProductInfo {
        val openFda = result.openfda

        return ProductInfo(
            barcode = barcode,
            source = name,
            category = ProductCategory.MEDICINE,
            name = openFda?.brandName?.firstOrNull() ?: result.splProductDataElements?.firstOrNull(),
            brand = openFda?.manufacturerName?.firstOrNull(),
            description = result.description?.firstOrNull()?.take(MAX_DESCRIPTION_CHARS),
            imageUrl = null,
            medicineData = MedicineData(
                genericName = openFda?.genericName?.firstOrNull(),
                activeIngredients = result.activeIngredient ?: emptyList(),
                dosageForm = openFda?.dosageForm?.firstOrNull(),
                route = openFda?.route?.firstOrNull(),
                manufacturer = openFda?.manufacturerName?.firstOrNull(),
                warnings = result.warnings ?: emptyList(),
                contraindications = result.contraindications ?: emptyList(),
                indications = result.indicationsAndUsage?.firstOrNull(),
                fdaApprovalDate = null,
                isRecalled = false
            )
        )
    }
}

/**
 * The searches openFDA's drug labels are asked, in order, for one scanned barcode.
 *
 * openFDA keeps a label's barcodes as 13-digit GTINs (`0342126631011`) and its package NDCs with dashes
 * (`42126-6310-1`). A US medicine box carries a 12-digit UPC-A, which scanners report without the
 * leading 0, and whose middle ten digits are the NDC with the dashes taken out. Searching the digits
 * as scanned found neither, so most US medicines came back "not found".
 */
internal object OpenFdaSearch {

    fun queries(barcode: String): List<String> {
        val digits = barcode.filter { it.isDigit() }
        val ndcs = dashedNdcs(digits)
        return listOfNotNull(
            "openfda.upc:${anyOf(upcForms(digits))}",
            if (ndcs.isEmpty()) null else "openfda.package_ndc:${anyOf(ndcs)}"
        )
    }

    /** The barcode as scanned, and as the other of its 12- and 13-digit spellings. */
    private fun upcForms(digits: String): List<String> = buildList {
        add(digits)
        if (digits.length == UPC_A) add("0$digits")
        if (digits.length == GTIN_13 && digits.startsWith('0')) add(digits.drop(1))
    }

    /** Every dashed package NDC the barcode can stand for; none when it carries no NDC. */
    fun dashedNdcs(digits: String): List<String> = when {
        digits.length == NDC_10 -> layouts(digits)
        digits.length == NDC_11 -> fromNdc11(digits)
        digits.length == UPC_A && digits.startsWith(US_DRUG_PREFIX) -> layouts(digits.substring(1, 1 + NDC_10))
        digits.length == GTIN_13 && digits.startsWith("0$US_DRUG_PREFIX") -> layouts(digits.substring(2, 2 + NDC_10))
        else -> emptyList()
    }

    /** A 10-digit NDC's three layouts (4-4-2, 5-3-2, 5-4-1): the digits alone do not say which. */
    private fun layouts(ndc: String): List<String> = NDC10_LAYOUTS.map { layout ->
        val productEnd = layout.labeler + layout.product
        "${ndc.take(layout.labeler)}-${ndc.substring(layout.labeler, productEnd)}-${ndc.substring(productEnd)}"
    }

    /**
     * The 11-digit (5-4-2) billing form pads one part of the label's NDC with leading zeros; a layout
     * fits when only zeros are dropped to reach it.
     */
    private fun fromNdc11(ndc: String): List<String> {
        val parts = listOf(
            ndc.take(LABELER_11),
            ndc.substring(LABELER_11, LABELER_11 + PRODUCT_11),
            ndc.substring(LABELER_11 + PRODUCT_11)
        )
        return NDC10_LAYOUTS.mapNotNull { layout ->
            val sizes = listOf(layout.labeler, layout.product, layout.packageSize)
            val trimmed = parts.zip(sizes) { part, size ->
                part.takeLast(size).takeIf { part.dropLast(size).all { it == '0' } }
            }
            trimmed.takeIf { null !in it }?.joinToString("-")
        }
    }

    /** One exact value, or any of several (openFDA's `field:("a"+"b")`). */
    private fun anyOf(values: List<String>): String {
        val quoted = values.distinct().map { "\"$it\"" }
        return quoted.singleOrNull() ?: quoted.joinToString("+", "(", ")")
    }

    private const val NDC_10 = 10
    private const val NDC_11 = 11
    private const val UPC_A = 12
    private const val GTIN_13 = 13

    /** The 11-digit billing NDC's labeler and product lengths (5-4-2). */
    private const val LABELER_11 = 5
    private const val PRODUCT_11 = 4

    /** UPC-A number system 3: drugs and health products, the NDC in the next ten digits. */
    private const val US_DRUG_PREFIX = '3'

    private class Layout(val labeler: Int, val product: Int) {
        val packageSize: Int get() = NDC_10 - labeler - product
    }

    private val NDC10_LAYOUTS = listOf(
        Layout(labeler = 4, product = 4),
        Layout(labeler = 5, product = 3),
        Layout(labeler = 5, product = 4)
    )
}

@Serializable
data class OpenFDAResponse(
    val results: List<DrugLabel>? = null
)

@Serializable
data class DrugLabel(
    @SerialName("spl_product_data_elements")
    val splProductDataElements: List<String>? = null,
    val description: List<String>? = null,
    @SerialName("active_ingredient")
    val activeIngredient: List<String>? = null,
    val warnings: List<String>? = null,
    val contraindications: List<String>? = null,
    @SerialName("indications_and_usage")
    val indicationsAndUsage: List<String>? = null,
    val openfda: OpenFDAData? = null
)

@Serializable
data class OpenFDAData(
    @SerialName("brand_name")
    val brandName: List<String>? = null,
    @SerialName("generic_name")
    val genericName: List<String>? = null,
    @SerialName("manufacturer_name")
    val manufacturerName: List<String>? = null,
    @SerialName("dosage_form")
    val dosageForm: List<String>? = null,
    val route: List<String>? = null
)
