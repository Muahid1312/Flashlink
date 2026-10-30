import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class LocalSnapshot {
  const LocalSnapshot(this.data);
  final Map<Object?, Object?> data;
  bool get available => data['available'] == true;
  bool get connected => data['connected'] == true;
  bool get discovering => data['discovering'] == true;
  bool get listening => data['listening'] == true;
  int get revision => (data['revision'] as num?)?.toInt() ?? -1;
  String? get message => data['message'] as String?;
  List<Map<Object?, Object?>> get addresses => (data['addresses'] as List? ?? const []).map((e) => Map<Object?, Object?>.from(e as Map)).toList();
  List<Map<Object?, Object?>> get peers => (data['peers'] as List? ?? const []).map((e) => Map<Object?, Object?>.from(e as Map)).toList();
  Map<Object?, Object?>? get invitation => data['invitation'] is Map ? Map<Object?, Object?>.from(data['invitation'] as Map) : null;
  String get label => connected ? 'Local Wi-Fi · authenticated' : listening ? 'Local listener open' : discovering ? 'Searching local Wi-Fi' : available ? 'Local Wi-Fi available' : 'No local Wi-Fi detected';
}
abstract interface class LocalPlatform {
  Stream<LocalSnapshot> get events;
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]);
}
class AndroidLocalPlatform implements LocalPlatform {
  static const methods = MethodChannel('app.flashlink/local.v1');
  static const channel = EventChannel('app.flashlink/local.events.v1');
  @override
  Stream<LocalSnapshot> get events => channel.receiveBroadcastStream().map((e) => LocalSnapshot(Map<Object?, Object?>.from(e as Map)));
  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) => methods.invokeMethod<Object?>(method, arguments);
}
final localPlatformProvider = Provider<LocalPlatform>((ref) => AndroidLocalPlatform());
final localProvider = NotifierProvider<LocalController, LocalSnapshot>(LocalController.new);
class LocalController extends Notifier<LocalSnapshot> {
  bool _closed = false;
  bool _pending = false;
  @override
  LocalSnapshot build() {
    final subscription = ref.read(localPlatformProvider).events.listen(_accept, onError: (Object error) => _error('Local networking is unavailable. Run on Android.'));
    ref.onDispose(() { _closed = true; unawaited(subscription.cancel()); });
    Future.microtask(() => command('snapshot'));
    return const LocalSnapshot({});
  }
  void _accept(LocalSnapshot next) {
    if (!_closed && next.revision >= state.revision) { state = next; }
  }
  void _error(String text) { if (!_closed) { state = LocalSnapshot({...state.data, 'message': text}); } }
  Future<void> command(String method, [Map<String, Object?>? arguments]) async {
    if (_pending || _closed) { return; }
    _pending = true;
    try {
      final result = await ref.read(localPlatformProvider).invoke(method, arguments);
      if (method == 'snapshot' && result is Map) { _accept(LocalSnapshot(Map<Object?, Object?>.from(result))); }
    } on PlatformException catch (e) { _error(e.message ?? 'Local connection failed. Check Wi-Fi and retry.'); }
    catch (_) { _error('Local networking is unavailable. Retry on Android.'); }
    finally { _pending = false; }
  }
}
