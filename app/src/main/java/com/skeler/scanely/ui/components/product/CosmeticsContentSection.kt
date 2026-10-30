package com.skeler.scanely.ui.components.product

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.lookup.CosmeticsData

@Composable
fun CosmeticsContentSection(data: CosmeticsData) {
    var hasContent = false

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
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (data.labels.isNotEmpty()) {
        hasContent = true
        SectionHeader(stringResource(R.string.cmp_cosmetics_labels))
        Text(
            text = data.labels.joinToString(", ") {
                it.removePrefix("en:").replace("-", " ").replaceFirstChar { c -> c.uppercase() }
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (data.categories.isNotEmpty()) {
        hasContent = true
        SectionHeader(stringResource(R.string.cmp_book_categories))
        Text(
            text = data.categories.joinToString(", ") {
                it.removePrefix("en:").replace("-", " ").replaceFirstChar { c -> c.uppercase() }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (!hasContent) {
        Text(
            text = stringResource(R.string.cmp_cosmetics_no_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
