import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/providers.dart';
import '../domain/models.dart';

enum TransferPhase { idle, preparing, waitingForApproval, connecting, transferring, verifying, completed, cancelled, failed, paused, interrupted, reconnecting, connectionLost, finalizing }
class TransferView {
  const TransferView(this.data);
  final Map<Object?, Object?> data;
  TransferPhase get phase => TransferPhase.values.where((p) => p.name == data['status']).firstOrNull ?? TransferPhase.idle;
  bool get secure => ready && data['secure'] == true;
  bool get ready => data['ready'] == true;
  bool get destinationChosen => data['destinationChosen'] == true;
  bool get active => const [TransferPhase.preparing, TransferPhase.waitingForApproval, TransferPhase.connecting, TransferPhase.reconnecting, TransferPhase.transferring, TransferPhase.verifying, TransferPhase.finalizing].contains(phase);
  bool get incoming => data['direction'] == 'received' && phase == TransferPhase.waitingForApproval;
  int get bytes => (data['bytes'] as num?)?.toInt() ?? 0;
  int get total => (data['total'] as num?)?.toInt() ?? 0;
  int get count => (data['fileCount'] as num?)?.toInt() ?? 0;
  int get completed => (data['completedFiles'] as num?)?.toInt() ?? 0;
  double? get speed => phase == TransferPhase.transferring ? (data['currentBps'] as num?)?.toDouble() : null;
  double? get average => (data['averageBps'] as num?)?.toDouble();
  double? get eta => phase == TransferPhase.transferring && bytes < total ? (data['etaSeconds'] as num?)?.toDouble() : null;
  double get fraction => phase == TransferPhase.completed ? 1 : total > 0 ? (bytes / total).clamp(0.0,1.0) : count > 0 ? completed / count : 0;
  String get label => switch(phase) {
    TransferPhase.idle => ready ? 'TCP session ready' : 'No active transfer',
    TransferPhase.preparing => data['resuming'] == true ? 'Validating saved checkpoint' : 'Preparing files', TransferPhase.waitingForApproval => incoming ? 'Incoming transfer request' : 'Waiting for receiver approval',
    TransferPhase.paused => 'Paused · checkpoint retained', TransferPhase.interrupted => 'Interrupted · resumable data retained',
    TransferPhase.reconnecting => 'Reconnecting securely', TransferPhase.connectionLost => 'Connection lost',
    TransferPhase.finalizing => 'Saving verified files to destination',
    TransferPhase.connecting => 'Opening TCP session', TransferPhase.transferring => 'Transferring', TransferPhase.verifying => 'Verifying SHA-256',
    TransferPhase.completed => 'Completed · verified', TransferPhase.cancelled => 'Cancelled', TransferPhase.failed => 'Failed',
  };
}
abstract interface class TransferPlatform {
  Stream<TransferView> get events;
  Future<Object?> invoke(String method, [Map<String,Object?>? arguments]);
}
class NativeTransferPlatform implements TransferPlatform {
  static const methods = MethodChannel('app.flashlink/transfer.v1');
  static const channel = EventChannel('app.flashlink/transfer.events.v1');
  @override
  Stream<TransferView> get events => channel.receiveBroadcastStream().map((event) => TransferView(Map<Object?,Object?>.from(event as Map)));
  @override
  Future<Object?> invoke(String method, [Map<String,Object?>? arguments]) => methods.invokeMethod<Object?>(method,arguments);
}
final transferPlatformProvider = Provider<TransferPlatform>((ref) => NativeTransferPlatform());
final transferProvider = NotifierProvider<TransferController,TransferView>(TransferController.new);
class TransferController extends Notifier<TransferView> {
  bool _closed = false;
  bool _pending = false;
  @override
  TransferView build() {
    final subscription = ref.read(transferPlatformProvider).events.listen(_accept,onError: (Object error) => _error('Native transfers are unavailable. Run on Android.'));
    ref.onDispose(() { _closed = true; unawaited(subscription.cancel()); });
    Future.microtask(() => command('snapshot'));
    return const TransferView({'status':'idle','ready':false});
  }
  void _accept(TransferView next) {
    if(_closed) return;
    state = next;
    if(const [TransferPhase.completed,TransferPhase.failed,TransferPhase.cancelled,TransferPhase.paused,TransferPhase.interrupted,TransferPhase.connectionLost].contains(next.phase)) ref.invalidate(historyProvider);
  }
  void _error(String message) {
    if(!_closed) state = TransferView({...state.data,'message':message});
  }
  Future<void> command(String method, [Map<String,Object?>? arguments]) async {
    if(_pending || _closed) return;
    if(method == 'send' && (state.active || !state.secure)) return;
    if(const ['resume','retry'].contains(method) && (!state.secure || state.active)) return;
    if(method == 'pause' && !const [TransferPhase.transferring,TransferPhase.verifying].contains(state.phase)) return;
    if(method == 'accept' && (!state.incoming || !state.destinationChosen || !state.secure)) return;
    _pending = true;
    try {
      final result = await ref.read(transferPlatformProvider).invoke(method,arguments);
      if(method == 'snapshot' && result is Map) _accept(TransferView(Map<Object?,Object?>.from(result)));
    } on PlatformException catch(error) {
      if(const ['resume','retry','cancel','deleteHistory'].contains(method)) ref.invalidate(historyProvider);
      _error(error.message ?? 'Transfer operation failed.'); }
    catch (_) { _error('Transfer operation unavailable. Retry on an Android device.'); }
    finally { _pending = false; }
  }
  Future<void> openSession() => command('openSession',{'name': ref.read(settingsProvider).deviceName,'bufferSize':262144});
  Future<void> reconnect() => command('reconnect',{'name': ref.read(settingsProvider).deviceName});
  Future<void> resume(String id) => command('resume',{'id':id});
  Future<void> send(List<SharedFile> files) => command('send',{'files': files.map((file) => {'uri':file.uri}).toList()});
  Future<void> accept() => command('accept',{'id':state.data['id']});
  Future<void> reject() => command('reject',{'id':state.data['id']});
}

final pairingProvider = FutureProvider<Map<Object?,Object?>>((ref) async {
  final result = await ref.read(transferPlatformProvider).invoke('pairing');
  return Map<Object?,Object?>.from(result as Map);
});
