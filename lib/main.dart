import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'app.dart';
import 'core/providers.dart';
import 'data/settings_repository.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  try {
    final preferences = await SharedPreferences.getInstance();
    runApp(ProviderScope(overrides: [
      settingsRepositoryProvider.overrideWithValue(SettingsRepository(preferences)),
    ], child: const FlashLinkApp()));
  } catch (_) {
    runApp(MaterialApp(home: Scaffold(body: Center(child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Icon(Icons.settings_backup_restore_rounded, size: 48),
        const SizedBox(height: 16),
        const Text('FlashLink could not load your settings. Please try again.'),
        const SizedBox(height: 16),
        FilledButton(onPressed: main, child: const Text('Retry')),
      ]),
    )))));
  }
}
