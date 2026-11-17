package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val GalkanDarkColorScheme = darkColorScheme(
  primary = EmeraldPrimary,
  onPrimary = EmeraldOnPrimary,
  primaryContainer = EmeraldContainer,
  onPrimaryContainer = EmeraldOnContainer,
  secondary = CyanSecondary,
  onSecondary = CyanOnSecondary,
  secondaryContainer = CyanContainer,
  onSecondaryContainer = CyanOnContainer,
  tertiary = AmberPro,
  onTertiary = AmberOnPro,
  tertiaryContainer = AmberContainer,
  onTertiaryContainer = AmberOnContainer,
  background = ObsidianBg,
  onBackground = TextPrimaryDark,
  surface = ObsidianSurface,
  onSurface = TextPrimaryDark,
  surfaceVariant = ObsidianSurfaceVariant,
  onSurfaceVariant = TextSecondaryDark,
  outline = ObsidianOutline,
  error = CoralError,
  onError = CoralOnError,
)

private val GalkanLightColorScheme = lightColorScheme(
  primary = EmeraldLightPrimary,
  onPrimary = Color.White,
  primaryContainer = EmeraldLightContainer,
  onPrimaryContainer = EmeraldLightOnContainer,
  secondary = CyanLightSecondary,
  onSecondary = Color.White,
  secondaryContainer = CyanLightContainer,
  onSecondaryContainer = CyanLightOnContainer,
  tertiary = AmberPro,
  onTertiary = AmberOnPro,
  tertiaryContainer = AmberLightContainer,
  onTertiaryContainer = AmberLightOnContainer,
  background = AlabasterBg,
  onBackground = TextPrimaryLight,
  surface = AlabasterSurface,
  onSurface = TextPrimaryLight,
  surfaceVariant = AlabasterSurfaceVariant,
  onSurfaceVariant = TextSecondaryLight,
  outline = AlabasterOutline,
  error = CoralError,
  onError = Color.White,
)

private val GalkanShapes = Shapes(
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(24.dp),
  extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun GalkanVpnTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) GalkanDarkColorScheme else GalkanLightColorScheme
  val view = LocalView.current

  if (!view.isInEditMode) {
    SideEffect {
      val window = view.context.findActivity()?.window ?: return@SideEffect
      val controller = WindowCompat.getInsetsController(window, view)
      controller.isAppearanceLightStatusBars = !darkTheme
      controller.isAppearanceLightNavigationBars = !darkTheme
    }
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    shapes = GalkanShapes,
    content = content,
  )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
