import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'local_network.dart';
import 'transfer.dart';
import 'wifi_direct.dart';

enum ConnectionMethod { wifiDirect, localWifi }
final connectionMethodProvider = NotifierProvider<ConnectionMethodController, ConnectionMethod>(ConnectionMethodController.new);
class ConnectionMethodController extends Notifier<ConnectionMethod> {
  @override
  ConnectionMethod build() => ConnectionMethod.wifiDirect;
  void select(ConnectionMethod method) {
    final transfer = ref.read(transferProvider);
    if (transfer.ready || transfer.active || ref.read(wifiProvider).busy) return;
    state = method;
  }
}
final effectiveConnectionMethodProvider = Provider<ConnectionMethod>((ref) {
  final transfer = ref.watch(transferProvider);
  if (transfer.ready || transfer.active) {
    return transfer.data['connectionMethod'] == 'localWifi' ? ConnectionMethod.localWifi : ConnectionMethod.wifiDirect;
  }
  return ref.watch(connectionMethodProvider);
});
final connectionLabelProvider = Provider<String>((ref) => ref.watch(effectiveConnectionMethodProvider) == ConnectionMethod.localWifi
  ? ref.watch(localProvider).label : ref.watch(wifiProvider).label);
