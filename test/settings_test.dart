import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flashlink/core/providers.dart';
import 'package:flashlink/data/settings_repository.dart';

void main() {
  late SettingsRepository repository;
  late ProviderContainer container;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    repository = SettingsRepository(await SharedPreferences.getInstance());
    container = ProviderContainer(overrides: [settingsRepositoryProvider.overrideWithValue(repository)]);
  });
  tearDown(() => container.dispose());

  test('theme and trimmed name survive a new controller', () async {
    await container.read(settingsProvider.notifier).setTheme(ThemeMode.dark);
    await container.read(settingsProvider.notifier).setName('  My Pixel  ');
    final restored = ProviderContainer(overrides: [settingsRepositoryProvider.overrideWithValue(repository)]);
    addTearDown(restored.dispose);
    expect(restored.read(settingsProvider).theme, ThemeMode.dark);
    expect(restored.read(settingsProvider).deviceName, 'My Pixel');
  });

  test('concurrent changes retain both preferences', () async {
    final controller = container.read(settingsProvider.notifier);
    await Future.wait([controller.setTheme(ThemeMode.light), controller.setName('Pixel')]);
    expect(repository.read().theme, ThemeMode.light);
    expect(repository.read().deviceName, 'Pixel');
  });

  test('empty and oversized names are rejected without saving', () {
    final controller = container.read(settingsProvider.notifier);
    expect(() => controller.setName('   '), throwsArgumentError);
    expect(() => controller.setName('x' * 33), throwsArgumentError);
    expect(repository.read().deviceName, 'My FlashLink');
  });

  test('corrupt saved settings recover to defaults', () async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString('flashlink.settings.v1', '{broken');
    expect(repository.read().theme, ThemeMode.system);
    expect(repository.read().deviceName, 'My FlashLink');
  });
  test('supported streaming buffers persist alongside concurrent appearance changes', () async {
    final controller = container.read(settingsProvider.notifier);
    for (final bytes in [65536, 262144, 1048576]) {
      await Future.wait([controller.setTransferBuffer(bytes), controller.setTheme(ThemeMode.dark), controller.setName('Pixel')]);
      expect(repository.read().transferBufferBytes, bytes);
      expect(repository.read().theme, ThemeMode.dark);
      expect(repository.read().deviceName, 'Pixel');
    }
    expect(() => controller.setTransferBuffer(12), throwsArgumentError);
  });
  test('older and malformed buffer preferences use the safe 256 KiB default', () async {
    final preferences = await SharedPreferences.getInstance();
    for (final raw in ['{"deviceName":"Existing"}', '{"transferBufferBytes":12}', '{"transferBufferBytes":"1048576"}']) {
      await preferences.setString('flashlink.settings.v1', raw);
      expect(repository.read().transferBufferBytes, 262144);
    }
  });

}
