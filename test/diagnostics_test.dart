import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flashlink/features/diagnostics_screen.dart';
import 'package:flashlink/features/transfer_widgets.dart';
import 'package:flashlink/services/transfer.dart';
import 'transfer_test.dart' show FakeTransferPlatform;

void main() {
  testWidgets('empty diagnostics never invent results and can be cleared', (tester) async {
    final platform = FakeTransferPlatform();
    addTearDown(platform.updates.close);
    await tester.pumpWidget(ProviderScope(overrides: [
      transferPlatformProvider.overrideWithValue(platform),
      diagnosticsProvider.overrideWith((ref) async => []),
    ], child: const MaterialApp(home: DiagnosticsScreen())));
    await tester.pumpAndSettle();
    expect(find.text('No measurements yet'), findsOneWidget);
    expect(find.textContaining('Application average:'), findsNothing);
    await tester.scrollUntilVisible(find.text('Clear measurements'), 250);
    await tester.tap(find.text('Clear measurements'));
    await tester.pumpAndSettle();
    expect(platform.calls, contains('clearDiagnostics'));
  });

  testWidgets('measured samples and real file queue render with large text', (tester) async {
    tester.view.physicalSize = const Size(320, 800);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.4;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final platform = FakeTransferPlatform();
    addTearDown(platform.updates.close);
    final container = ProviderContainer(overrides: [
      transferPlatformProvider.overrideWithValue(platform),
      diagnosticsProvider.overrideWith((ref) async => [
        {'status':'completed','direction':'received','connectionMethod':'localWifi','applicationBps':12500000.0,
          'attemptBytes':100000000,'total':100000000,'bufferSize':262144,'durationSeconds':8.0},
      ]),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: const MaterialApp(home: DiagnosticsScreen())));
    await tester.pumpAndSettle();
    expect(find.text('Application average: 12.50 MB/s'), findsOneWidget);
    expect(find.text('Peak sample (≥250 ms): —'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: const MaterialApp(home: Scaffold(body: SingleChildScrollView(child: TransferQueue())))));
    platform.emit({'status':'transferring','completedFiles':1,'currentFileIndex':1,'currentFile':'current.bin','fileBytes':4,
      'files': [{'name':'done.bin','size':10},{'name':'current.bin','size':8},{'name':'pending.bin','size':6}]});
    await tester.pumpAndSettle();
    await tester.tap(find.text('File queue · 1 / 3 verified'));
    await tester.pumpAndSettle();
    expect(find.text('pending.bin'), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
