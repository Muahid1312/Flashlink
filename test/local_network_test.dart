import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flashlink/services/connection.dart';
import 'package:flashlink/services/local_network.dart';
import 'package:flashlink/services/transfer.dart';
import 'package:flashlink/services/wifi_direct.dart';
import 'wifi_test.dart';
import 'transfer_test.dart';

class FakeLocalPlatform implements LocalPlatform {
  final updates = StreamController<LocalSnapshot>.broadcast(sync: true);
  LocalSnapshot current = const LocalSnapshot({'revision': 0, 'available': false});
  bool fail = false;
  final calls = <String>[];
  @override
  Stream<LocalSnapshot> get events => updates.stream;
  void emit(Map<Object?, Object?> values) { current = LocalSnapshot(values); updates.add(current); }
  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) async {
    calls.add(method);
    if (fail) throw PlatformException(code: 'LOCAL_NETWORK', message: 'Guest Wi-Fi may isolate clients. Use the same hotspot.');
    return method == 'snapshot' ? current.data : null;
  }
}
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FakeLocalPlatform local;
  late FakeWifiPlatform wifi;
  late FakeTransferPlatform transfer;
  late ProviderContainer container;
  setUp(() async {
    local = FakeLocalPlatform(); wifi = FakeWifiPlatform(); transfer = FakeTransferPlatform();
    container = ProviderContainer(overrides: [localPlatformProvider.overrideWithValue(local), wifiPlatformProvider.overrideWithValue(wifi), transferPlatformProvider.overrideWithValue(transfer)]);
    container.read(localProvider); container.read(transferProvider); container.read(wifiProvider);
    await Future<void>.delayed(Duration.zero);
  });
  tearDown(() async { container.dispose(); await local.updates.close(); await wifi.updates.close(); await transfer.updates.close(); });
  test('a local interface and discovered peer never imply authentication', () {
    local.emit({'revision': 1, 'available': true, 'peers': [{'name': 'Unverified advertisement'}]});
    expect(container.read(localProvider).connected, isFalse);
    expect(container.read(localProvider).label, 'Local Wi-Fi available');
    expect(container.read(transferProvider).secure, isFalse);
    local.emit({'revision': 0, 'connected': true});
    expect(container.read(localProvider).connected, isFalse);
  });
  test('manual method selection cannot switch an active or authenticated connection', () {
    container.read(connectionMethodProvider.notifier).select(ConnectionMethod.localWifi);
    expect(container.read(effectiveConnectionMethodProvider), ConnectionMethod.localWifi);
    transfer.emit({'status': 'connecting', 'connectionMethod': 'localWifi'});
    container.read(connectionMethodProvider.notifier).select(ConnectionMethod.wifiDirect);
    expect(container.read(effectiveConnectionMethodProvider), ConnectionMethod.localWifi);
    transfer.emit({'status': 'idle', 'ready': true, 'secure': true, 'connectionMethod': 'localWifi'});
    container.read(connectionMethodProvider.notifier).select(ConnectionMethod.wifiDirect);
    expect(container.read(effectiveConnectionMethodProvider), ConnectionMethod.localWifi);
  });
  test('incoming connection requires an answer and is not yet transfer-ready', () async {
    transfer.emit({'status': 'connectionApproval', 'connectionMethod': 'localWifi', 'connectionRequestId': 'request-1'});
    final state = container.read(transferProvider);
    expect(state.active, isTrue); expect(state.ready, isFalse); expect(state.secure, isFalse);
    await container.read(transferProvider.notifier).send([]);
    expect(transfer.calls, ['snapshot']);
    await container.read(transferProvider.notifier).command('confirmConnection', {'id': 'request-1', 'accept': false});
    expect(transfer.calls.last, 'confirmConnection');
  });
  test('local errors keep actionable native details', () async {
    local.fail = true;
    await container.read(localProvider.notifier).command('discover');
    expect(container.read(localProvider).message, contains('isolate clients'));
  });
}
