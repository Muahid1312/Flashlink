import 'package:flutter/material.dart';

abstract final class Neon {
  static const primary = Color(0xFF7C5CFF);
  static const secondary = Color(0xFF20D6E8);
  static const success = Color(0xFF36D7A0);
  static const muted = Color(0xFF94A3B8);

  static ThemeData theme(Brightness brightness) {
    final dark = brightness == Brightness.dark;
    final scheme = ColorScheme.fromSeed(seedColor: primary, brightness: brightness).copyWith(
      primary: dark ? primary : const Color(0xFF6240DA),
      onPrimary: Colors.white,
      secondary: dark ? secondary : const Color(0xFF007E8B),
      surface: dark ? const Color(0xFF141D30) : Colors.white,
      surfaceContainerHighest: dark ? const Color(0xFF1D2940) : const Color(0xFFE9ECF6),
      onSurface: dark ? const Color(0xFFF8FAFC) : const Color(0xFF111B30),
      onSurfaceVariant: dark ? muted : const Color(0xFF53627A),
      outlineVariant: dark ? const Color(0xFF29364D) : const Color(0xFFDCE2EE),
    );
    final base = ThemeData(useMaterial3: true, colorScheme: scheme, brightness: brightness);
    return base.copyWith(
      scaffoldBackgroundColor: dark ? const Color(0xFF080D19) : const Color(0xFFF4F6FC),
      textTheme: base.textTheme.copyWith(
        headlineLarge: base.textTheme.headlineLarge?.copyWith(fontWeight: FontWeight.w800, letterSpacing: -1.3),
        headlineMedium: base.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w700, letterSpacing: -0.8),
        titleLarge: base.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700, letterSpacing: -0.4),
        titleMedium: base.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
        bodyMedium: base.textTheme.bodyMedium?.copyWith(height: 1.5),
      ),
      appBarTheme: AppBarTheme(backgroundColor: Colors.transparent, surfaceTintColor: Colors.transparent,
        foregroundColor: scheme.onSurface, centerTitle: false),
      filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 18),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)))),
      inputDecorationTheme: InputDecorationTheme(filled: true, fillColor: scheme.surfaceContainerHighest,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(16), borderSide: BorderSide.none)),
      navigationBarTheme: NavigationBarThemeData(backgroundColor: scheme.surface,
        indicatorColor: scheme.primary.withValues(alpha: 0.17), height: 78),
      snackBarTheme: SnackBarThemeData(behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
    );
  }
}
