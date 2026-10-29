import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../domain/models.dart';

class SettingsRepository {
  SettingsRepository(this.preferences);
  final SharedPreferences preferences;
  static const _key = 'flashlink.settings.v1';

  AppSettings read() {
    try {
      final encoded = preferences.getString(_key);
      if (encoded == null) return const AppSettings();
      final data = jsonDecode(encoded) as Map<String, dynamic>;
      final theme = ThemeMode.values.where((mode) => mode.name == data['theme']).firstOrNull ?? ThemeMode.system;
      final name = data['deviceName'];
      return AppSettings(theme: theme, deviceName: name is String && name.trim().isNotEmpty && name.runes.length <= 32
        ? name.trim() : 'My FlashLink');
    } on FormatException {
      return const AppSettings();
    } on TypeError {
      return const AppSettings();
    }
  }

  Future<void> write(AppSettings settings) async {
    // One preference prevents partial theme/name updates if a write fails.
    final saved = await preferences.setString(_key, jsonEncode({
      'theme': settings.theme.name, 'deviceName': settings.deviceName,
    }));
    if (!saved) throw StateError('Could not save settings');
  }
}
