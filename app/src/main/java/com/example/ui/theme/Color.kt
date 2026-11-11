package com.example.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Reactive theme state holder that synchronizes custom semantic Finance Vault colors
 * across Light, Dark, and System Default appearance modes.
 */
object VaultThemeState {
    var isLightTheme by mutableStateOf(false)
}

// Dynamic Semantic Surface & Text Tokens (Revolut & Linear Minimal Slate System)
val VaultObsidianBg: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFF8FAFC) else Color(0xFF090D16)

val VaultSurface: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFFFFFFF) else Color(0xFF0F172A)

val VaultCardSurface: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFFFFFFF) else Color(0xFF111827)

val VaultCardElevated: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFF1F5F9) else Color(0xFF182234)

val VaultBorderSubtle: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFE2E8F0) else Color(0xFF1F2D46)

val VaultEmerald: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFF059669) else Color(0xFF10B981)

val VaultEmeraldSoft: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFD1FAE5) else Color(0xFF064E3B)

val VaultEmeraldLight: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFF047857) else Color(0xFF6EE7B7)

val VaultGold: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFD97706) else Color(0xFFF59E0B)

val VaultGoldSoft: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFFEF3C7) else Color(0xFF451A03)

val VaultCyan: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFF0284C7) else Color(0xFF38BDF8)

val VaultCyanSoft: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFE0F2FE) else Color(0xFF0C4A6E)

val VaultCoral: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFE11D48) else Color(0xFFF43F5E)

val VaultCoralSoft: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFFFFE4E6) else Color(0xFF4C0519)

val VaultPurple: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFF6366F1) else Color(0xFF818CF8)

val VaultTextPrimary: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFF0F172A) else Color(0xFFF8FAFC)

val VaultTextSecondary: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFF475569) else Color(0xFF94A3B8)

val VaultTextMuted: Color
    get() = if (VaultThemeState.isLightTheme) Color(0xFF64748B) else Color(0xFF64748B)
