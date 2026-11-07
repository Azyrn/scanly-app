package com.skeler.scanely.core.food

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FoodProduct(
    val barcode: String,
    val name: String,
    val brand: String?,
    val imageUrl: String?,
    val nutriScore: String?,
    val novaGroup: Int?,
    val ingredients: String?,
    val calories: String?,
    val fat: String?,
    val carbs: String?,
    val proteins: String?,
    val sugar: String?,
    val salt: String?,
    val categories: String?
)

@Serializable
data class OpenFoodFactsResponse(
    val status: String = "",
    val product: ProductDto? = null
) {
    val isFound: Boolean get() = status == OFF_STATUS_SUCCESS && product != null
}

// API v3 reports outcome as a string; a missing product still returns HTTP 200.
const val OFF_STATUS_SUCCESS = "success"

@Serializable
data class ProductDto(
    @SerialName("code") val barcode: String? = null,
    @SerialName("product_name") val name: String? = null,
    @SerialName("brands") val brand: String? = null,
    @SerialName("image_front_url") val imageUrl: String? = null,
    @SerialName("image_url") val imageFallbackUrl: String? = null,
    @SerialName("nutriscore_grade") val nutriScore: String? = null,
    @SerialName("nova_group") val novaGroup: Int? = null,
    @SerialName("ingredients_text") val ingredients: String? = null,
    @SerialName("categories") val categories: String? = null,
    val nutriments: NutrimentsDto? = null
)

@Serializable
data class NutrimentsDto(
    @SerialName("energy-kcal_100g") val calories: Double? = null,
    @SerialName("fat_100g") val fat: Double? = null,
    @SerialName("carbohydrates_100g") val carbs: Double? = null,
    @SerialName("proteins_100g") val proteins: Double? = null,
    @SerialName("sugars_100g") val sugar: Double? = null,
    @SerialName("salt_100g") val salt: Double? = null
)

fun ProductDto.toDomain(): FoodProduct? {
    val productName = name?.takeIf { it.isNotBlank() } ?: return null

    return FoodProduct(
        barcode = barcode ?: "",
        name = productName,
        brand = brand.nonBlank(),
        imageUrl = imageUrl ?: imageFallbackUrl,
        nutriScore = nutriScore?.uppercase(),
        novaGroup = novaGroup,
        ingredients = ingredients.nonBlank(),
        calories = nutriments?.calories?.let { "${it.toInt()} kcal" },
        fat = nutriments?.fat.formatted("%.1fg"),
        carbs = nutriments?.carbs.formatted("%.1fg"),
        proteins = nutriments?.proteins.formatted("%.1fg"),
        sugar = nutriments?.sugar.formatted("%.1fg"),
        salt = nutriments?.salt.formatted("%.2fg"),
        categories = categories?.split(",")?.firstOrNull()?.trim()
    )
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun Double?.formatted(pattern: String): String? = this?.let { pattern.format(it) }
