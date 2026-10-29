import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flashlink/core/providers.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('app.flashlink/native.v1');
  late ProviderContainer container;
  setUp(() => container = ProviderContainer());
  tearDown(() {
    container.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('reads content URI metadata, preserves selection on cancel and clears', () async {
    var cancel = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'pickFiles');
      return cancel ? null : [{'uri': 'content://documents/42', 'name': 'paper.pdf', 'bytes': 1234, 'mimeType': 'application/pdf'}];
    });
    final controller = container.read(selectionProvider.notifier);
    expect(await controller.pick(), isNull);
    expect(container.read(selectionProvider).requireValue.single.name, 'paper.pdf');
    expect(container.read(selectionProvider).requireValue.single.bytes, 1234);
    cancel = true;
    await controller.pick();
    expect(container.read(selectionProvider).requireValue.length, 1);
    controller.clear();
    expect(container.read(selectionProvider).requireValue, isEmpty);
  });

  test('platform errors are surfaced and do not invent selected files', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (_) async {
      throw PlatformException(code: 'NO_PICKER');
    });
    expect(await container.read(selectionProvider.notifier).pick(), isNotNull);
    expect(container.read(selectionProvider).requireValue, isEmpty);
  });
}
