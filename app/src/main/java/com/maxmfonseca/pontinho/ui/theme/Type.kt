package com.maxmfonseca.pontinho.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

val PontinhoTypography = Typography(
    displayLarge = textStyle(36, 44, FontWeight.Bold),
    displayMedium = textStyle(32, 40, FontWeight.Bold),
    displaySmall = textStyle(28, 36, FontWeight.Bold),
    headlineLarge = textStyle(28, 36, FontWeight.SemiBold),
    headlineMedium = textStyle(24, 32, FontWeight.SemiBold),
    headlineSmall = textStyle(22, 30, FontWeight.SemiBold),
    titleLarge = textStyle(20, 28, FontWeight.SemiBold),
    titleMedium = textStyle(16, 24, FontWeight.SemiBold),
    titleSmall = textStyle(14, 20, FontWeight.Medium),
    bodyLarge = textStyle(16, 24),
    bodyMedium = textStyle(14, 20),
    bodySmall = textStyle(12, 18),
    labelLarge = textStyle(14, 20, FontWeight.SemiBold),
    labelMedium = textStyle(12, 18, FontWeight.Medium),
    labelSmall = textStyle(11, 16, FontWeight.Medium),
)
