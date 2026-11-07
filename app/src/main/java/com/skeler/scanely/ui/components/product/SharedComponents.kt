package com.skeler.scanely.ui.components.product

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
fun InfoColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ScoreCard(title: String, value: String, color: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

// Open Food Facts' own Nutri-Score and NOVA badge colours, so a grade reads as it does on the pack.
private val GRADE_DARK_GREEN = Color(0xFF038141)
private val GRADE_LIGHT_GREEN = Color(0xFF85BB2F)
private val GRADE_YELLOW = Color(0xFFFECB02)
private val GRADE_ORANGE = Color(0xFFEE8100)
private val GRADE_RED = Color(0xFFE63E11)

private const val NOVA_UNPROCESSED = 1
private const val NOVA_INGREDIENTS = 2
private const val NOVA_PROCESSED = 3
private const val NOVA_ULTRA_PROCESSED = 4

fun nutriScoreColor(score: String): Color {
    return when (score.uppercase()) {
        "A" -> GRADE_DARK_GREEN
        "B" -> GRADE_LIGHT_GREEN
        "C" -> GRADE_YELLOW
        "D" -> GRADE_ORANGE
        "E" -> GRADE_RED
        else -> Color.Gray
    }
}

fun novaColor(group: Int): Color {
    return when (group) {
        NOVA_UNPROCESSED -> GRADE_DARK_GREEN
        NOVA_INGREDIENTS -> GRADE_LIGHT_GREEN
        NOVA_PROCESSED -> GRADE_ORANGE
        NOVA_ULTRA_PROCESSED -> GRADE_RED
        else -> Color.Gray
    }
}
