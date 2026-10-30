import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../data/settings_repository.dart';
import '../data/transfer_repository.dart';
import '../domain/contracts.dart';
import '../domain/models.dart';
import '../services/android_bridge.dart';

final settingsRepositoryProvider = Provider<SettingsRepository>((ref) => throw StateError('Settings must be initialized'));
final settingsProvider = NotifierProvider<SettingsController, AppSettings>(SettingsController.new);

class SettingsController extends Notifier<AppSettings> {
  @override
  AppSettings build() => ref.read(settingsRepositoryProvider).read();

  Future<void> _pending = Future<void>.value();

  Future<void> setTheme(ThemeMode theme) => _enqueue(() => state.copyWith(theme: theme));
  Future<void> setTransferBuffer(int bytes) {
    if (!AppSettings.transferBuffers.contains(bytes)) throw ArgumentError('Choose a supported transfer buffer size.');
    return _enqueue(() => state.copyWith(transferBufferBytes: bytes));
  }
  Future<void> setName(String name) {
    final clean = name.trim();
    if (clean.isEmpty || clean.runes.length > 32) {
      throw ArgumentError('Use a device name between 1 and 32 characters.');
    }
    return _enqueue(() => state.copyWith(deviceName: clean));
  }

  Future<void> _enqueue(AppSettings Function() update) {
    final repository = ref.read(settingsRepositoryProvider);
    final operation = _pending.then((_) async {
      final next = update();
      await repository.write(next);
      state = next;
    });
    // Keep the queue usable after a failed write; callers still receive errors.
    _pending = operation.then<void>((_) {}, onError: (Object error, StackTrace stack) {});
    return operation;
  }
}

final androidBridgeProvider = Provider((ref) => AndroidBridge());
final storageProvider = FutureProvider((ref) => ref.read(androidBridgeProvider).storageInfo());
final transferRepositoryProvider = Provider<TransferRepository>((ref) => SqliteTransferRepository());
final historyProvider = FutureProvider((ref) => ref.read(transferRepositoryProvider).readAll());
final selectionProvider = NotifierProvider<FileSelectionController, AsyncValue<List<SharedFile>>>(FileSelectionController.new);

class FileSelectionController extends Notifier<AsyncValue<List<SharedFile>>> {
  @override
  AsyncValue<List<SharedFile>> build() => const AsyncData([]);

  Future<String?> pick() async {
    if (state.isLoading) return null;
    final previous = state.asData?.value ?? <SharedFile>[];
    state = const AsyncLoading();
    try {
      final files = await ref.read(androidBridgeProvider).pickFiles();
      state = AsyncData(files ?? previous);
      return null;
    } catch (_) {
      state = AsyncData(previous);
      return 'File selection is unavailable. Try again on an Android device.';
    }
  }

  void clear() => state = const AsyncData([]);
}
