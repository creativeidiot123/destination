package com.ankit.destination.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.ankit.destination.R

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val fontName = GoogleFont("Outfit")

val fontFamily = FontFamily(
    Font(googleFont = fontName, fontProvider = provider)
)

private val defaultTypography = Typography()

val AppTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = fontFamily, fontSize = 52.sp),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = fontFamily, fontSize = 44.sp),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = fontFamily, fontSize = 36.sp),

    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = fontFamily, fontSize = 32.sp),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = fontFamily, fontSize = 28.sp),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = fontFamily, fontSize = 24.sp),

    titleLarge = defaultTypography.titleLarge.copy(fontFamily = fontFamily),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = fontFamily),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = fontFamily),

    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = fontFamily),

    labelLarge = defaultTypography.labelLarge.copy(fontFamily = fontFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = fontFamily),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = fontFamily)
)
