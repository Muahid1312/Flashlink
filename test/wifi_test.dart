import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flashlink/services/wifi_direct.dart';

class FakeWifiPlatform implements WifiPlatform {
  final updates = StreamController<WifiSnapshot>.broadcast(sync: true);
  WifiSnapshot current = const WifiSnapshot(phase: WifiPhase.permissionRequired, revision: 0);
  final calls = <String>[];
  Completer<void>? pending;
  bool deny = false;
  @override
  Stream<WifiSnapshot> get events => updates.stream;
  @override
  Future<WifiSnapshot> snapshot() async => current;
  void emit(WifiPhase phase, {List<WifiPeer> peers = const [], Map<Object?, Object?>? group}) {
    current = WifiSnapshot(phase: phase, revision: current.revision + 1, peers: peers,
      group: group, permissionGranted: phase != WifiPhase.permissionRequired);
    updates.add(current);
  }
  @override
  Future<void> invoke(String method, [Map<String, Object?>? arguments]) async {
    calls.add(method);
    if (deny) { throw PlatformException(code: 'NOT_READY'); }
    if (pending != null) { await pending!.future; }
    switch (method) {
      case 'requestPermission': emit(WifiPhase.idle);
      case 'startDiscovery': emit(WifiPhase.discovering);
      case 'stopDiscovery': emit(WifiPhase.idle);
      case 'connect': emit(WifiPhase.connecting);
      case 'cancel': emit(WifiPhase.idle);
      case 'disconnect': emit(WifiPhase.idle);
    }
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FakeWifiPlatform platform;
  late ProviderContainer container;
  setUp(() async {
    platform = FakeWifiPlatform();
    container = ProviderContainer(overrides: [wifiPlatformProvider.overrideWithValue(platform)]);
    container.read(wifiProvider);
    await Future<void>.delayed(Duration.zero);
  });
  tearDown(() async { container.dispose(); await platform.updates.close(); });

  test('permission denial retains actionable prerequisite state', () async {
    platform.deny = true;
    await container.read(wifiProvider.notifier).command('requestPermission');
    expect(container.read(wifiProvider).phase, WifiPhase.permissionRequired);
  });
  test('permission grant, discovery start, peer update and stop', () async {
    final controller = container.read(wifiProvider.notifier);
    await controller.command('requestPermission');
    expect(container.read(wifiProvider).phase, WifiPhase.idle);
    await controller.command('startDiscovery');
    expect(container.read(wifiProvider).phase, WifiPhase.discovering);
    platform.emit(WifiPhase.discovering, peers: [const WifiPeer(name: 'Test phone', address: 'test-address', status: 3)]);
    expect(container.read(wifiProvider).peers.single.statusLabel, 'Available');
    await controller.command('stopDiscovery');
    expect(container.read(wifiProvider).phase, WifiPhase.idle);
  });
  test('connect acknowledgement does not imply connected; actual group event does', () async {
    final controller = container.read(wifiProvider.notifier);
    await controller.command('connect', {'address': 'test-address'});
    expect(container.read(wifiProvider).connected, isFalse);
    platform.emit(WifiPhase.connected, group: {'isGroupOwner': false, 'ownerAddress': '192.0.2.9', 'ownerName': 'Test owner'});
    expect(container.read(wifiProvider).connected, isTrue);
    expect(container.read(wifiProvider).group!['ownerAddress'], '192.0.2.9');
    expect(container.read(wifiProvider).connectedNames, ['Test owner']);
    await controller.command('disconnect');
    expect(container.read(wifiProvider).group, isNull);
  });
  test('duplicate connection and discovery commands are suppressed while pending', () async {
    platform.pending = Completer<void>();
    final controller = container.read(wifiProvider.notifier);
    final first = controller.command('connect', {'address': 'test-address'});
    await controller.command('connect', {'address': 'test-address'});
    expect(platform.calls, ['connect']);
    platform.pending!.complete(); await first;
    await controller.command('connect', {'address': 'test-address'});
    await controller.command('startDiscovery');
    expect(platform.calls, ['connect']);
  });
  test('failure, retry and cancellation', () async {
    platform.emit(WifiPhase.failed);
    final controller = container.read(wifiProvider.notifier);
    await controller.command('connect', {'address': 'test-address'});
    await controller.command('cancel');
    expect(container.read(wifiProvider).phase, WifiPhase.idle);
    platform.emit(WifiPhase.disabled);
    expect(container.read(wifiProvider).phase, WifiPhase.disabled);
  });
  test('refresh reconciles a preserved group after foreground recovery', () async {
    platform.current = const WifiSnapshot(phase: WifiPhase.connected, revision: 20, group: {'isGroupOwner': true, 'ownerAddress': '192.0.2.4'});
    await container.read(wifiProvider.notifier).refresh();
    expect(container.read(wifiProvider).connected, isTrue);
    platform.updates.add(const WifiSnapshot(phase: WifiPhase.idle, revision: 19));
    expect(container.read(wifiProvider).connected, isTrue);
    platform.emit(WifiPhase.idle);
    expect(container.read(wifiProvider).group, isNull);
  });
  test('native snapshot maps peer status, roles and unavailable information', () {
    final value = WifiSnapshot.fromMap({'phase': 'connected', 'revision': 2,
      'permissionGranted': true, 'peers': [{'name': '', 'address': 'test', 'status': 1}],
      'group': {'isGroupOwner': true, 'clients': [{'name': 'Test client'}]}});
    expect(value.peers.single.name, 'Unnamed Android device');
    expect(value.peers.single.statusLabel, 'Invited');
    expect(value.connectedNames, ['Test client']);
    expect(value.group!['ownerAddress'], isNull);
  });
  test('MethodChannel maps a real native envelope without assuming owner IP', () async {
    const channel = MethodChannel('app.flashlink/wifi.v1');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'snapshot') { return {'phase': 'idle', 'revision': 7, 'peers': []}; }
      expect(call.method, 'connect'); expect(call.arguments, {'address': 'test'}); return null;
    });
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null));
    final android = AndroidWifiPlatform();
    expect((await android.snapshot()).revision, 7);
    await android.invoke('connect', {'address': 'test'});
  });
}
