import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../services/wifi_direct.dart';
import '../services/connection.dart';
import '../services/local_network.dart';
import '../services/transfer.dart';
import 'local_connection_panel.dart';
import '../shared/widgets.dart';

class ConnectionPanel extends ConsumerWidget {
  const ConnectionPanel({super.key, this.receiving = false});
  final bool receiving;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(wifiProvider);
    final localMode = ref.watch(effectiveConnectionMethodProvider) == ConnectionMethod.localWifi;
    final local = ref.watch(localProvider);
    if (localMode) {
      return Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      StatusPill(label: local.label, icon: Icons.router_outlined),
      const SizedBox(height: 12),
      const Text('Connect through Local Wi-Fi / hotspot in Nearby. Compare both fingerprints, use a fresh invitation and confirm the incoming connection.'),
      TextButton(onPressed: () => context.go('/nearby'), child: const Text('Manage nearby devices →')),
      ]));
    }
    return Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      StatusPill(label: state.label, icon: state.connected ? Icons.link_rounded : Icons.link_off_rounded),
      const SizedBox(height: 12),
      if (state.connected) ...[
        Text(state.connectedNames.isEmpty ? 'Wi-Fi Direct group formed' : state.connectedNames.join(', '), style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Text(receiving ? 'Choose a destination and open a TCP session below to receive approved files.' : 'Phone connection established. Open a TCP session to request a file transfer.'),
      ] else Text(receiving ? 'Open Nearby on both phones and start discovery. Android manages visibility and pairing.' : 'Choose a nearby phone to establish a Wi-Fi Direct connection.'),
      const SizedBox(height: 8),
      TextButton(onPressed: () => context.go('/nearby'), child: const Text('Manage nearby devices →')),
      if (state.connected) TextButton(onPressed: state.busy ? null : () => ref.read(wifiProvider.notifier).command('disconnect'), child: const Text('Disconnect')),
    ]));
  }
}

class NearbyScreen extends ConsumerWidget {
  const NearbyScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(wifiProvider);
    final controller = ref.read(wifiProvider.notifier);
    final allowed = state.permissionGranted && state.phase != WifiPhase.disabled && state.phase != WifiPhase.unsupported;
    final transfer = ref.watch(transferProvider);
    final localMode = ref.watch(effectiveConnectionMethodProvider) == ConnectionMethod.localWifi;
    return PageBody(children: [
      const SizedBox(height: 20),
      Text('Nearby devices', style: Theme.of(context).textTheme.headlineLarge),
      const SizedBox(height: 10), const Text('Great connections start close by.'),
      const SizedBox(height: 24),
      const ConnectionMethodPicker(),
      const SizedBox(height: 16),
      if (localMode) const LocalConnectionPanel()
      else ...[
      Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        const Center(child: OrbitGraphic()), const SizedBox(height: 16),
        StatusPill(label: state.label, icon: state.connected ? Icons.link_rounded : Icons.radar_rounded),
        if (state.busy) const Padding(padding: EdgeInsets.symmetric(vertical: 12), child: LinearProgressIndicator()),
        if (state.message != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(state.message!, semanticsLabel: state.message)),
        const SizedBox(height: 12),
        if (!state.permissionGranted && state.phase != WifiPhase.unsupported) ...[
          const Text('Android 13+: allow nearby devices. Older Android: allow precise location and enable location services. FlashLink does not derive your physical location.'),
          const SizedBox(height: 12),
          FilledButton(onPressed: () => controller.command('requestPermission'), child: const Text('Allow discovery access')),
          const Text('If access was permanently denied, enable it in Android app settings.'),
        ],
        if (state.locationRequired && !state.locationEnabled) const Text('Enable location services in Android settings, then refresh.'),
        if (state.phase == WifiPhase.discovering)
          OutlinedButton(onPressed: () => controller.command('stopDiscovery'), child: const Text('Stop discovery'))
        else FilledButton(onPressed: !allowed || state.busy || state.connected ? null : () => controller.command('startDiscovery'), child: const Text('Find devices')),
        if (state.phase == WifiPhase.connecting) OutlinedButton(onPressed: () => controller.command('cancel'), child: const Text('Cancel connection')),
        if (state.connected) OutlinedButton(onPressed: transfer.active || transfer.ready ? null : () => controller.command('disconnect'), child: const Text('Disconnect')),
        TextButton(onPressed: state.busy ? null : controller.refresh, child: const Text('Refresh status')),
      ])),
      if (state.connected) ...[
        const SectionTitle('Connected group'),
        Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(state.connectedNames.isEmpty ? 'Wi-Fi Direct group formed' : state.connectedNames.join(', ')),
          Text('Local role: ${state.group!['isGroupOwner'] == true ? 'Group owner' : 'Client'}'),
          Text('Group owner IP: ${state.group!['ownerAddress'] ?? 'Not reported by Android'}'),
          if (state.group!['interfaceName'] != null) Text('Interface: ${state.group!['interfaceName']}'),
          const SizedBox(height: 8), Text(transfer.ready && transfer.secure ? 'FlashLink application connectivity verified through authenticated TLS.' : 'P2P group formed. Confirm both fingerprints and open the secure TCP connection in Send or Receive.'),
        ])),
      ],
      const SectionTitle('Available devices'),
      if (state.peers.isEmpty) EmptyPanel(icon: Icons.devices_other_rounded, title: 'No devices to show',
        message: state.phase == WifiPhase.discovering ? 'Searching with Android Wi-Fi Direct. Open Nearby and start discovery on the other phone too.' : 'Start discovery on both phones. Visibility and pairing behavior vary by manufacturer.')
      else for (final peer in state.peers) Padding(padding: const EdgeInsets.only(bottom: 12), child: Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(peer.name, style: Theme.of(context).textTheme.titleMedium),
        Text(peer.statusLabel), SelectableText(peer.address), const SizedBox(height: 8),
        OutlinedButton(onPressed: state.busy || state.connected || !allowed || peer.address.isEmpty || peer.status == 4 ? null :
          () => controller.command('connect', {'address': peer.address}), child: const Text('Connect')),
      ]))),
      ],
      if (transfer.phase == TransferPhase.connectionApproval) const IncomingConnectionPanel(),
      const PhaseNotice(message: 'Android handles pairing confirmation. Your FlashLink display name does not change the Android Wi-Fi Direct device name. File transfers require a TCP handshake and explicit receiver approval.'),
    ]);
  }
}

class ConnectionMethodPicker extends ConsumerWidget {
  const ConnectionMethodPicker({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final method = ref.watch(effectiveConnectionMethodProvider);
    final transfer = ref.watch(transferProvider);
    final blocked = transfer.active || transfer.ready || ref.watch(wifiProvider).busy;
    final local = ref.watch(localProvider);
    return Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text('Connection method', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 12),
      DropdownButtonFormField<ConnectionMethod>(key: ValueKey(method), initialValue: method, isExpanded: true,
        items: const [
          DropdownMenuItem(value: ConnectionMethod.wifiDirect, child: Text('Wi-Fi Direct')),
          DropdownMenuItem(value: ConnectionMethod.localWifi, child: Text('Local Wi-Fi / hotspot')),
        ], onChanged: blocked ? null : (value) { if (value != null) ref.read(connectionMethodProvider.notifier).select(value); }),
      const SizedBox(height: 8),
      Text(blocked ? 'Close the current session before changing methods.' : local.available
        ? 'A local Wi-Fi interface is available. Use it if Wi-Fi Direct fails; speed depends on your phones and network.'
        : 'Wi-Fi Direct can create a direct group. Alternatively, join the same Wi-Fi or hotspot for local mode.'),
    ]));
  }
}
class IncomingConnectionPanel extends ConsumerWidget {
  const IncomingConnectionPanel({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(transferProvider);
    return Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text('Allow this connection?', style: Theme.of(context).textTheme.titleMedium),
      Text('Peer: ${state.data['peer'] ?? 'Approved peer'}'),
      SelectableText(state.data['candidateFingerprint'] as String? ?? ''),
      Text('Session: ${state.data['connectionRequestId'] ?? ''}'),
      const Text('The certificate matches your prior approval. Compare it with the other phone and answer within 40 seconds. File requests still require separate approval.'),
      FilledButton(onPressed: () => ref.read(transferProvider.notifier).command('confirmConnection', {'id': state.data['connectionRequestId'], 'accept': true}), child: const Text('Accept connection')),
      TextButton(onPressed: () => ref.read(transferProvider.notifier).command('confirmConnection', {'id': state.data['connectionRequestId'], 'accept': false}), child: const Text('Reject connection')),
    ]));
  }
}
