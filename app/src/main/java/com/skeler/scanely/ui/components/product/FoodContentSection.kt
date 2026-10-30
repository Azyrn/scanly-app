package com.skeler.scanely.ui.components.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.lookup.FoodData

@Composable
fun FoodContentSection(data: FoodData) {
    var hasContent = false

    if (data.nutriScore != null || data.novaGroup != null) {
        hasContent = true
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            data.nutriScore?.let { score ->
                ScoreCard(
                    title = "Nutri-Score",
                    value = score.uppercase(),
                    color = nutriScoreColor(score)
                )
            }
            data.novaGroup?.let { nova ->
                ScoreCard(
                    title = "NOVA Group",
                    value = nova.toString(),
                    color = novaColor(nova)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    val hasNutrition = listOfNotNull(
        data.calories,
        data.fat,
        data.carbs,
        data.protein,
        data.sugar,
        data.salt,
        data.fiber
    ).isNotEmpty()

    if (hasNutrition) {
        hasContent = true
        SectionHeader(stringResource(R.string.cmp_food_nutrition_facts))
        NutritionGrid(data)
        Spacer(modifier = Modifier.height(16.dp))
    }

    data.servingSize?.takeIf { it.isNotBlank() }?.let { serving ->
        hasContent = true
        Text(
            text = stringResource(R.string.cmp_food_serving_size, serving),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    data.ingredients?.takeIf { it.isNotBlank() }?.let { ingredients ->
        hasContent = true
        SectionHeader(stringResource(R.string.cmp_food_ingredients))
        Text(
            text = ingredients,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (data.allergens.isNotEmpty()) {
        hasContent = true
        SectionHeader(stringResource(R.string.cmp_food_allergens))
        Text(
            text = data.allergens.joinToString(", ") {
                it.removePrefix("en:").replace("-", " ").replaceFirstChar { c -> c.uppercase() }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    if (!hasContent) {
        Text(
            text = stringResource(R.string.cmp_food_no_nutrition_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun NutritionGrid(data: FoodData) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        data.calories?.let { NutritionRow(stringResource(R.string.cmp_food_calories), it) }
        data.fat?.let { NutritionRow(stringResource(R.string.cmp_food_fat), it) }
        data.carbs?.let { NutritionRow(stringResource(R.string.cmp_food_carbs), it) }
        data.protein?.let { NutritionRow(stringResource(R.string.cmp_food_protein), it) }
        data.sugar?.let { NutritionRow(stringResource(R.string.cmp_food_sugar), it) }
        data.salt?.let { NutritionRow(stringResource(R.string.cmp_food_salt), it) }
        data.fiber?.let { NutritionRow(stringResource(R.string.cmp_food_fiber), it) }
    }
}

@Composable
private fun NutritionRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
