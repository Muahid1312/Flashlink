import 'package:flashlink/services/local_network.dart';
import 'package:flashlink/services/connection.dart';
import 'local_network_test.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flashlink/app.dart';
import 'package:flashlink/services/wifi_direct.dart';
import 'wifi_test.dart' show FakeWifiPlatform;
import 'transfer_test.dart' show FakeTransferPlatform;
import 'package:flashlink/services/transfer.dart';
import 'package:flashlink/core/providers.dart';
import 'package:flashlink/core/router.dart';
import 'package:flashlink/data/settings_repository.dart';
import 'package:flashlink/domain/models.dart';

void main() {
  Future<ProviderContainer> launch(WidgetTester tester, {Size size = const Size(430, 932), double textScale = 1}) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = textScale;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    SharedPreferences.setMockInitialValues({});
    final repository = SettingsRepository(await SharedPreferences.getInstance());
    final wifi = FakeWifiPlatform();
    final local = FakeLocalPlatform();
    addTearDown(local.updates.close);
    final transfer = FakeTransferPlatform();
    addTearDown(transfer.updates.close);
    addTearDown(wifi.updates.close);
    final container = ProviderContainer(overrides: [
      wifiPlatformProvider.overrideWithValue(wifi),
      localPlatformProvider.overrideWithValue(local),
      transferPlatformProvider.overrideWithValue(transfer),
      settingsRepositoryProvider.overrideWithValue(repository),
      historyProvider.overrideWith((ref) async => <TransferRecord>[]),
      storageProvider.overrideWith((ref) async => const StorageInfo(available: 64000000, total: 128000000)),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: const FlashLinkApp()));
    await tester.pumpAndSettle();
    return container;
  }

  testWidgets('home routes to send, picker entry and disabled transfer controls', (tester) async {
    await launch(tester);
    expect(find.text('FlashLink'), findsOneWidget);
    await tester.tap(find.text('Send files'));
    await tester.pumpAndSettle();
    expect(find.text('Choose files'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Request file transfer'), 300, scrollable: find.byType(Scrollable).last);
    final button = tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Request file transfer'));
    expect(button.onPressed, isNull);
    await tester.ensureVisible(find.text('View transfer screen'));
    await tester.tap(find.text('View transfer screen'));
    await tester.pumpAndSettle();
    expect(find.text('Current speed: — MB/s'), findsOneWidget);
    expect(find.text('No active transfer'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('all screens render on a narrow phone with large text and no fake data', (tester) async {
    final container = await launch(tester, size: const Size(320, 800), textScale: 1.4);
    for (final route in ['/', '/nearby', '/history', '/settings', '/send', '/receive', '/transfer', '/diagnostics']) {
      container.read(routerProvider).go(route);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull, reason: route);
    }
  });

  testWidgets('settings switches light and dark themes and edits device name', (tester) async {
    final container = await launch(tester);
    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('theme-dark')));
    await tester.pumpAndSettle();
    expect(container.read(settingsProvider).theme, ThemeMode.dark);
    expect(Theme.of(tester.element(find.text('Make it yours.'))).brightness, Brightness.dark);
    await tester.tap(find.byKey(const ValueKey('theme-light')));
    await tester.pumpAndSettle();
    expect(Theme.of(tester.element(find.text('Make it yours.'))).brightness, Brightness.light);
    await tester.scrollUntilVisible(find.text('Display name'), 250, scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('Display name'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), 'Kitchen Pixel');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(container.read(settingsProvider).deviceName, 'Kitchen Pixel');
    expect(find.text('Kitchen Pixel'), findsOneWidget);
  });

  testWidgets('receive and nearby advertise unavailable states', (tester) async {
    final container = await launch(tester);
    container.read(routerProvider).go('/receive');
    await tester.pumpAndSettle();
    expect(find.text('Manage nearby devices →'), findsOneWidget);
    container.read(routerProvider).go('/nearby');
    await tester.pumpAndSettle();
    expect(find.text('Permission required'), findsOneWidget);
    expect(find.text('No devices to show'), findsOneWidget);
  });
  testWidgets('local pairing controls remain usable on a narrow phone with large text', (tester) async {
    final container = await launch(tester, size: const Size(320, 800), textScale: 1.4);
    container.read(connectionMethodProvider.notifier).select(ConnectionMethod.localWifi);
    container.read(routerProvider).go('/nearby');
    await tester.pumpAndSettle();
    expect(find.text('Host a local connection'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Manual IP / port pairing'), 300, scrollable: find.byType(Scrollable).last);
    await tester.tap(find.text('Manual IP / port pairing'));
    await tester.pumpAndSettle();
    expect(find.text('Review manual connection'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('incoming files preview shows sizes and supports choosing a folder before approval', (tester) async {
    final container = await launch(tester, size: const Size(320, 800), textScale: 1.4);
    final platform = container.read(transferPlatformProvider) as FakeTransferPlatform;
    platform.emit({'status':'waitingForApproval','direction':'received','ready':true,'secure':true,'id':'incoming',
      'fileCount':1,'total':1234,'peer':'Approved peer','files':[{'name':'image.jpg','size':1234,'mime':'image/jpeg'}]});
    container.read(routerProvider).go('/transfer');
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Choose destination folder'), 300, scrollable: find.byType(Scrollable).last);
    expect(find.text('1.2 KB · image/jpeg'), findsOneWidget);
    final choose = tester.widget<TextButton>(find.widgetWithText(TextButton, 'Choose destination folder'));
    final accept = tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Accept files'));
    expect(choose.onPressed, isNotNull); expect(accept.onPressed, isNull);
    expect(tester.takeException(), isNull);
  });

}
