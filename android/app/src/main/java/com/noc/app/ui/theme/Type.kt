package com.noc.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.noc.app.R

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_semibold, FontWeight.Bold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/** Serifada editorial: só para momentos de voz (saudação, títulos grandes, marca). */
val Serif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

val NocTypography = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.01).em),
    displayMedium = TextStyle(fontFamily = Serif, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em),
    displaySmall = TextStyle(fontFamily = Serif, fontSize = 30.sp, lineHeight = 34.sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontSize = 30.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.015).em),
    headlineSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.003.em),
    bodyMedium = TextStyle(fontFamily = Geist, fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = tight),
    labelMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, lineHeightStyle = tight),
    labelSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.02.em, lineHeightStyle = tight),
)

val MonoStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 20.sp)
val MonoSmall = TextStyle(fontFamily = GeistMono, fontSize = 11.5.sp, lineHeight = 15.sp)

val NocShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
