import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

enum WifiPhase { unsupported, disabled, permissionRequired, idle, discovering, connecting, connected, disconnecting, failed }

class WifiPeer {
  const WifiPeer({required this.name, required this.address, required this.status});
  final String name;
  final String address;
  final int status;
  String get statusLabel => switch (status) {
    0 => 'Connected', 1 => 'Invited', 2 => 'Failed', 3 => 'Available', _ => 'Unavailable',
  };
  factory WifiPeer.fromMap(Map<Object?, Object?> map) => WifiPeer(
    name: (map['name'] as String?)?.trim().isNotEmpty == true ? map['name'] as String : 'Unnamed Android device',
    address: map['address'] as String? ?? '', status: map['status'] as int? ?? 4);
}

class WifiSnapshot {
  const WifiSnapshot({this.phase = WifiPhase.idle, this.revision = -1, this.peers = const [], this.group,
    this.message, this.permissionGranted = false, this.locationRequired = false, this.locationEnabled = true});
  final WifiPhase phase;
  final int revision;
  final List<WifiPeer> peers;
  final Map<Object?, Object?>? group;
  final String? message;
  final bool permissionGranted;
  final bool locationRequired;
  final bool locationEnabled;
  bool get connected => phase == WifiPhase.connected && group != null;
  bool get busy => phase == WifiPhase.connecting || phase == WifiPhase.disconnecting;
  String get label => switch (phase) {
    WifiPhase.unsupported => 'Wi-Fi Direct unsupported', WifiPhase.disabled => 'Wi-Fi Direct disabled',
    WifiPhase.permissionRequired => 'Permission required', WifiPhase.idle => 'Not connected',
    WifiPhase.discovering => 'Discovering devices', WifiPhase.connecting => 'Connecting',
    WifiPhase.connected => 'Wi-Fi Direct connected', WifiPhase.disconnecting => 'Disconnecting', WifiPhase.failed => 'Connection needs attention',
  };
  List<String> get connectedNames {
    if (!connected) return const [];
    if (group!['isGroupOwner'] == true) {
      final clients = group!['clients'] as List? ?? const [];
      return clients.map((item) => (item as Map)['name'] as String? ?? 'Unnamed Android device').toList();
    }
    return [group!['ownerName'] as String? ?? 'Connected Android device'];
  }
  factory WifiSnapshot.fromMap(Map<Object?, Object?> map) => WifiSnapshot(
    phase: WifiPhase.values.where((p) => p.name == map['phase']).firstOrNull ?? WifiPhase.failed,
    revision: map['revision'] as int? ?? 0,
    peers: (map['peers'] as List? ?? const []).map((p) => WifiPeer.fromMap(Map<Object?, Object?>.from(p as Map))).toList(),
    group: map['group'] == null ? null : Map<Object?, Object?>.from(map['group'] as Map),
    message: map['message'] as String?, permissionGranted: map['permissionGranted'] == true,
    locationRequired: map['locationRequired'] == true, locationEnabled: map['locationEnabled'] != false,
  );
}

abstract interface class WifiPlatform {
  Stream<WifiSnapshot> get events;
  Future<WifiSnapshot> snapshot();
  Future<void> invoke(String method, [Map<String, Object?>? arguments]);
}

class AndroidWifiPlatform implements WifiPlatform {
  static const _methods = MethodChannel('app.flashlink/wifi.v1');
  static const _events = EventChannel('app.flashlink/wifi.events.v1');
  @override
  Stream<WifiSnapshot> get events => _events.receiveBroadcastStream().map((event) =>
    WifiSnapshot.fromMap(Map<Object?, Object?>.from(event as Map)));
  @override
  Future<WifiSnapshot> snapshot() async => WifiSnapshot.fromMap(
    await _methods.invokeMapMethod<Object?, Object?>('snapshot') ?? const {});
  @override
  Future<void> invoke(String method, [Map<String, Object?>? arguments]) => _methods.invokeMethod<void>(method, arguments);
}

final wifiPlatformProvider = Provider<WifiPlatform>((ref) => AndroidWifiPlatform());
final wifiProvider = NotifierProvider<WifiController, WifiSnapshot>(WifiController.new);

class WifiController extends Notifier<WifiSnapshot> {
  bool _commandPending = false;
  bool _closed = false;
  @override
  WifiSnapshot build() {
    final platform = ref.read(wifiPlatformProvider);
    final subscription = platform.events.listen(_accept, onError: (Object error) => _error('Native Wi-Fi Direct service is unavailable. Try again on Android.'));
    ref.onDispose(() { _closed = true; unawaited(subscription.cancel()); });
    // Start with a truthful uninitialized state, then reconcile with Android.
    Future.microtask(refresh);
    return const WifiSnapshot(phase: WifiPhase.permissionRequired, message: 'Checking Wi-Fi Direct availability…');
  }
  void _accept(WifiSnapshot next) {
    if (!_closed && next.revision >= state.revision) state = next;
  }
  void _error(String message) {
    if (!_closed) state = WifiSnapshot(phase: WifiPhase.failed, revision: state.revision,
      peers: state.peers, message: message, permissionGranted: state.permissionGranted);
  }
  Future<void> refresh() async {
    try { _accept(await ref.read(wifiPlatformProvider).snapshot()); }
    catch (_) { _error('Could not read Android Wi-Fi Direct state. Refresh on an Android device.'); }
  }
  Future<void> command(String method, [Map<String, Object?>? arguments]) async {
    if (_closed || _commandPending) return;
    if (method == 'connect' && (state.busy || state.connected)) return;
    if (method == 'startDiscovery' && (state.busy || state.connected || state.phase == WifiPhase.discovering)) return;
    _commandPending = true;
    try {
      await ref.read(wifiPlatformProvider).invoke(method, arguments);
      await refresh();
    } on PlatformException catch (error) {
      // Native events contain prerequisite/failure states; do not overwrite them.
      await refresh();
      if (state.phase == WifiPhase.idle) _error(error.message ?? 'Android could not complete the request.');
    } catch (_) { _error('Wi-Fi Direct operation unavailable. Refresh and retry.'); }
    finally { _commandPending = false; }
  }
}
