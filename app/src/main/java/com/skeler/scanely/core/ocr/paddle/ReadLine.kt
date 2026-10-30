package com.skeler.scanely.core.ocr.paddle

/** One recognised line and where it sits in the bitmap that was read, in that bitmap's pixels. */
class ReadLine(
    val text: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
