package com.skeler.scanely.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.skeler.scanely.ui.theme.GestureDefaults
import kotlinx.coroutines.launch

/**
 * Shrinks the element to [GestureDefaults.PRESS_SCALE] while [interactionSource] is pressed, from
 * the press itself, and back on release or cancel. Critically damped: a press applies no force, so
 * nothing bounces. The scale lives in a graphicsLayer, so a press never recomposes the element.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            val target = when (interaction) {
                is PressInteraction.Press -> GestureDefaults.PRESS_SCALE
                is PressInteraction.Release, is PressInteraction.Cancel -> 1f
                else -> return@collect
            }
            launch { scale.animateTo(target, GestureDefaults.press()) }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
