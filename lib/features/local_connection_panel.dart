import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../core/providers.dart';
import '../services/local_network.dart';
import '../services/transfer.dart';
import '../shared/widgets.dart';
import 'pairing_widget.dart';

class LocalConnectionPanel extends ConsumerStatefulWidget {
  const LocalConnectionPanel({super.key});
  @override
  ConsumerState<LocalConnectionPanel> createState() => _LocalConnectionPanelState();
}
class _LocalConnectionPanelState extends ConsumerState<LocalConnectionPanel> {
  final _link = TextEditingController();
  final _host = TextEditingController();
  final _port = TextEditingController(text: '45871');
  final _session = TextEditingController();
  final _pin = TextEditingController();
  String? _address;
  bool _pending = false;
  String? _error;
  @override
  void dispose() { for (final c in [_link, _host, _port, _session, _pin]) { c.dispose(); } super.dispose(); }
  Future<void> _connect({Map<Object?, Object?>? discovered, bool manual = false}) async {
    String? fingerprint;
    String? invitation;
    try {
      invitation = discovered?['uri'] as String? ?? _link.text.trim();
      fingerprint = manual ? _pin.text.trim().replaceAll(RegExp(r'[:\s]'), '').toLowerCase() : Uri.parse(invitation).queryParameters['pin'];
      if (fingerprint == null || !RegExp(r'^[0-9a-f]{64}$').hasMatch(fingerprint)) { throw const FormatException(); }
    } catch (_) { setState(() => _error = 'Paste a complete FlashLink invitation or enter all manual pairing fields.'); return; }
    final accepted = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
      title: const Text('Confirm the other phone'),
      content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Text('Compare this complete fingerprint with the other phone in person. Discovery names and invitation links are not proof of identity.'),
        const SizedBox(height: 12), SelectableText(fingerprint!),
      ])),
      actions: [TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Reject')),
        FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Matches · connect'))],
    ));
    if (accepted != true || !mounted) { return; }
    setState(() { _pending = true; _error = null; });
    try {
      await ref.read(transferPlatformProvider).invoke('approvePairing', {'fingerprint': fingerprint});
      ref.invalidate(pairingProvider);
      final arguments = <String, Object?>{'name': ref.read(settingsProvider).deviceName, 'bufferSize': ref.read(settingsProvider).transferBufferBytes};
      if (manual) {
        arguments.addAll({'host': _host.text.trim(), 'port': int.parse(_port.text), 'id': _session.text.trim(), 'fingerprint': fingerprint});
      } else { arguments['invitation'] = invitation; }
      await ref.read(localProvider.notifier).command(manual ? 'connectManual' : 'connect', arguments);
    } catch (_) { if (mounted) { setState(() => _error = 'Could not approve this peer. Close the current session and retry.'); } }
    finally { if (mounted) { setState(() => _pending = false); } }
  }
  @override
  Widget build(BuildContext context) {
    final local = ref.watch(localProvider);
    final transfer = ref.watch(transferProvider);
    final blocked = _pending || transfer.active || transfer.ready;
    final controller = ref.read(localProvider.notifier);
    final ticket = local.invitation;
    final addresses = local.addresses.map((e) => e['address'] as String).toList();
    final chosen = addresses.contains(_address) ? _address : addresses.firstOrNull;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        StatusPill(label: local.label, icon: Icons.router_outlined),
        const SizedBox(height: 12),
        if (transfer.data['connectionMethod'] == 'localWifi') ...[
          Semantics(liveRegion: true, child: Text(transfer.label)),
          if (transfer.data['message'] != null) Text(transfer.data['message'].toString()),
          if (transfer.ready) Text('Peer: ${transfer.data['peer']} · ${transfer.data['connectionHost']}:${transfer.data['connectionPort']}'),
        ],
        const Text('Join the same Wi-Fi network, or enable one phone’s hotspot in Android settings and join it from the other. Internet is not required. FlashLink does not turn hotspots on automatically.'),
        if (local.message != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(local.message!)),
        if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        if (!local.available) const Text('No supported local IPv4 interface is visible. Join Wi-Fi / enable your hotspot, then refresh.'),
        if (addresses.isNotEmpty) ...[
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(initialValue: chosen, key: ValueKey(addresses.join(',')), isExpanded: true,
            decoration: const InputDecoration(labelText: 'Local listening address'),
            items: [for (final address in addresses) DropdownMenuItem(value: address, child: Text(address))],
            onChanged: blocked ? null : (value) => setState(() => _address = value)),
        ],
        const SizedBox(height: 12),
        FilledButton.icon(onPressed: blocked || !local.available ? null : () => controller.command('startHost', {'name': ref.read(settingsProvider).deviceName, 'address': chosen, 'bufferSize': ref.read(settingsProvider).transferBufferBytes}),
          icon: const Icon(Icons.qr_code_rounded), label: const Text('Host a local connection')),
        OutlinedButton(onPressed: blocked || !local.available ? null : () => controller.command(local.discovering ? 'stopDiscovery' : 'discover'),
          child: Text(local.discovering ? 'Stop local search' : 'Find FlashLink on local Wi-Fi')),
        TextButton(onPressed: () => controller.command('snapshot'), child: const Text('Refresh local status')),
        if (transfer.active || transfer.ready) TextButton(onPressed: () => ref.read(transferProvider.notifier).command('cancel'), child: const Text('Close current session')),
        if (local.available) const Text('Local Wi-Fi / hotspot is available as an alternative. Neither mode is claimed faster without measurements on your phones.'),
      ])),
      if (ticket != null) ...[
        const SizedBox(height: 16),
        Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(local.listening ? 'Listening · share this invitation' : 'Opening local listener…', style: Theme.of(context).textTheme.titleMedium),
          const Text('Expires after two minutes and can be accepted once. The host must confirm the incoming connection. Both phones must use this updated FlashLink version.'),
          const SizedBox(height: 12),
          LayoutBuilder(builder: (context, constraints) => Center(child: Container(color: Colors.white, padding: const EdgeInsets.all(10),
            child: QrImageView(data: ticket['uri'] as String, size: (constraints.maxWidth - 20).clamp(80.0, 220.0), backgroundColor: Colors.white)))),
          SelectableText(ticket['uri'] as String),
          Text('IP / port: ${ticket['host']}:${ticket['port']}'),
          SelectableText('Session: ${ticket['id']}'),
          SelectableText('Fingerprint: ${ticket['fingerprint']}'),
        ])),
      ],
      const SizedBox(height: 16),
      if (!blocked) const PairingPanel(),
      const SectionTitle('Local FlashLink listeners'),
      if (local.peers.isEmpty) const EmptyPanel(icon: Icons.wifi_find_rounded, title: 'No local listeners found', message: 'Open a local listener on the other phone. Some routers/hotspots block multicast or isolate clients; use an invitation link or manual pairing if discovery fails.'),
      for (final peer in local.peers) Padding(padding: const EdgeInsets.only(bottom: 12), child: Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(peer['name'] as String, style: Theme.of(context).textTheme.titleMedium), Text('${peer['host']}:${peer['port']} · identity not yet confirmed'),
        OutlinedButton(onPressed: blocked ? null : () => _connect(discovered: peer), child: const Text('Verify fingerprint & connect')),
      ]))),
      const SectionTitle('QR / invitation fallback'),
      const Text('Scan with a trusted QR reader and paste its result below. FlashLink does not request camera access.'),
      const SizedBox(height: 12),
      TextField(controller: _link, enabled: !blocked, maxLength: 2048, minLines: 1, maxLines: 4, autocorrect: false, enableSuggestions: false,
        decoration: const InputDecoration(labelText: 'FlashLink invitation link')),
      FilledButton(onPressed: blocked ? null : _connect, child: const Text('Review invitation & connect')),
      const SizedBox(height: 12),
      ExpansionTile(title: const Text('Manual IP / port pairing'), children: [
        const Text('Copy these public fields from the other phone’s active invitation. Expired session codes are rejected.'),
        TextField(controller: _host, enabled: !blocked, maxLength: 15, decoration: const InputDecoration(labelText: 'Local IPv4 address')),
        TextField(controller: _port, enabled: !blocked, maxLength: 5, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Port')),
        TextField(controller: _session, enabled: !blocked, maxLength: 36, decoration: const InputDecoration(labelText: 'Pairing session ID')),
        TextField(controller: _pin, enabled: !blocked, maxLength: 96, autocorrect: false, enableSuggestions: false, decoration: const InputDecoration(labelText: 'Other phone’s SHA-256 fingerprint')),
        OutlinedButton(onPressed: blocked ? null : () => _connect(manual: true), child: const Text('Review manual connection')),
      ]),
    ]);
  }
}
