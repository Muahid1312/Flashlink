import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flashlink/services/transfer.dart';
import 'package:flashlink/domain/models.dart';

class FakeTransferPlatform implements TransferPlatform {
  final updates = StreamController<TransferView>.broadcast(sync:true);
  TransferView current = const TransferView({'status':'idle','ready':false});
  final calls = <String>[];
  Completer<void>? pending;
  @override
  Stream<TransferView> get events => updates.stream;
  void emit(Map<Object?,Object?> data) { current = TransferView(data); updates.add(current); }
  @override
  Future<Object?> invoke(String method,[Map<String,Object?>? arguments]) async {
    calls.add(method);
    if(pending != null) await pending!.future;
    return method == 'snapshot' ? current.data : null;
  }
}
void main() {
  late FakeTransferPlatform platform;
  late ProviderContainer container;
  setUp(() async {
    platform = FakeTransferPlatform();
    container = ProviderContainer(overrides:[transferPlatformProvider.overrideWithValue(platform)]);
    container.read(transferProvider); await Future<void>.delayed(Duration.zero);
  });
  tearDown(() async { container.dispose(); await platform.updates.close(); });
  test('file send requires a real TCP-ready snapshot',() async {
    await container.read(transferProvider.notifier).send([const SharedFile(uri:'content://test/1',name:'test')]);
    expect(platform.calls,['snapshot']);
    platform.emit({'status':'idle','ready':true,'secure':true});
    await container.read(transferProvider.notifier).send([const SharedFile(uri:'content://test/1',name:'test')]);
    expect(platform.calls.last,'send');
  });
  test('approval requires an incoming manifest and chosen destination',() async {
    platform.emit({'status':'waitingForApproval','direction':'received','ready':true,'secure':true,'id':'test-id'});
    await container.read(transferProvider.notifier).accept();
    expect(platform.calls,['snapshot']);
    platform.emit({...platform.current.data,'destinationChosen':true});
    await container.read(transferProvider.notifier).accept(); expect(platform.calls.last,'accept');
  });
  test('duplicate start is prevented while command is pending or active',() async {
    platform.emit({'status':'idle','ready':true,'secure':true}); platform.pending = Completer<void>();
    final controller = container.read(transferProvider.notifier);
    final first = controller.send([]); await controller.send([]);
    expect(platform.calls.where((call) => call == 'send').length,1);
    platform.pending!.complete(); await first;
    platform.emit({'status':'transferring','ready':true,'secure':true}); await controller.send([]);
    expect(platform.calls.where((call) => call == 'send').length,1);
  });
  test('secure badge and resume require authenticated encryption, not just TCP readiness',() async {
    platform.emit({'status':'idle','ready':true});
    expect(container.read(transferProvider).secure,isFalse);
    await container.read(transferProvider.notifier).resume('saved-id');
    expect(platform.calls,['snapshot']);
    platform.emit({'status':'idle','ready':true,'secure':true});
    expect(container.read(transferProvider).secure,isTrue);
    await container.read(transferProvider.notifier).resume('saved-id');
    expect(platform.calls.last,'resume');
    platform.emit({'status':'paused','ready':false,'secure':false,'bytes':1234});
    expect(container.read(transferProvider).label,'Paused · checkpoint retained');
    await container.read(transferProvider.notifier).send([]);
    expect(platform.calls.last,'resume');
  });
  test('pause is offered only while streaming and invalidates history on actual pause',() async {
    final controller = container.read(transferProvider.notifier);
    await controller.command('pause'); expect(platform.calls,['snapshot']);
    platform.emit({'status':'transferring','ready':true,'secure':true});
    await controller.command('pause'); expect(platform.calls.last,'pause');
    platform.emit({'status':'paused','ready':false,'bytes':1234});
    expect(container.read(transferProvider).active,isFalse);
    expect(container.read(transferProvider).bytes,1234);
  });
  test('measured 64-bit progress and decimal speeds map without invented ETA',() {
    final state = TransferView({'status':'transferring','bytes':2500000000,'total':5000000000,'fileCount':2,'completedFiles':1,'currentBps':12500000.0,'averageBps':10000000.0});
    expect(state.fraction,0.5); expect(state.bytes,2500000000); expect(state.speed,12500000);
    expect(state.eta,isNull);
    expect(const TransferView({'status':'verifying','total':0,'fileCount':1,'completedFiles':0}).fraction,0);
  });
  test('paused dashboards retain averages without claiming a current transfer speed',() {
    const paused = TransferView({'status':'paused','bytes':1000000,'total':2000000,'currentBps':5000000.0,'averageBps':4000000.0});
    expect(paused.speed,isNull); expect(paused.average,4000000.0); expect(paused.eta,isNull);
    expect(paused.fraction,0.5);
  });
  test('terminal cancellation cannot send again without reopening the session',() async {
    platform.emit({'status':'cancelled','ready':false}); await container.read(transferProvider.notifier).send([]);
    expect(platform.calls,['snapshot']);
  });
}
