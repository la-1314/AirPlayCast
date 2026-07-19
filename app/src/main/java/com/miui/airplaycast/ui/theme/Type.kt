package com.miui.airplaycast.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MIUI 圆角体系
data class MiShapes(
    val extraSmall: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(8.dp),
    val small: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(12.dp),
    val medium: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(16.dp),
    val large: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(20.dp),
    val extraLarge: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(28.dp),
    val cardShape: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(20.dp),
    val buttonShape: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(16.dp),
    val pillShape: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(50)
)

val LocalMiShapes = staticCompositionLocalOf { MiShapes() }

// MIUI 字体体系
val MiTypography = Typography(
    displayLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
    displayMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    headlineLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    headlineMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
)
