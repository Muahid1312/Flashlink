import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../core/providers.dart';
import '../services/local_network.dart';
import '../services/transfer.dart';
import '../shared/widgets.dart';
import 'connection_widgets.dart';
import 'qr_scanner_screen.dart';

class LocalConnectionPanel extends ConsumerStatefulWidget {
  const LocalConnectionPanel({super.key, this.receiving = false, this.advanced = false});
  final bool receiving;
  final bool advanced;
  @override
  ConsumerState<LocalConnectionPanel> createState() => _LocalConnectionPanelState();
}
class _LocalConnectionPanelState extends ConsumerState<LocalConnectionPanel> {
  final _link = TextEditingController();
  String? _address;
  bool _pending = false;
  String? _error;
  @override
  void dispose() { _link.dispose(); super.dispose(); }
  Future<void> _scan() async {
    final link = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => const QrScannerScreen()));
    if (link != null && mounted) { await _connect(link); }
  }
  Future<void> _connect(String text) async {
    LocalInvitation invitation;
    try { invitation = LocalInvitation.parse(text); }
    on FormatException catch (e) { setState(() => _error = e.message); return; }
    final accepted = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
      title: const Text('Connect to receiver?'),
      content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(invitation.name),
        const SizedBox(height: 12),
        const Text('Use a QR from the receiving phone. After connecting, compare the six-digit code on both screens and accept on the receiver only if it matches.'),
        ExpansionTile(title: const Text('Receiver fingerprint'), children: [SelectableText(invitation.fingerprint)]),
      ])),
      actions: [TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Connect'))],
    ));
    if (accepted != true || !mounted) { return; }
    setState(() { _pending = true; _error = null; });
    try {
      await ref.read(localProvider.notifier).connectInvitation(invitation,
        name: ref.read(settingsProvider).deviceName, bufferSize: ref.read(settingsProvider).transferBufferBytes);
      ref.invalidate(pairingProvider);
    } on PlatformException catch (e) { if (mounted) { setState(() => _error = e.message ?? 'Pairing failed. Scan a fresh receiver QR.'); } }
    catch (_) { if (mounted) { setState(() => _error = 'Pairing unavailable. Retry on Android.'); } }
    finally { if (mounted) { setState(() => _pending = false); } }
  }
  @override
  Widget build(BuildContext context) {
    final local = ref.watch(localProvider);
    final transfer = ref.watch(transferProvider);
    final blocked = _pending || transfer.active || transfer.ready;
    final controller = ref.read(localProvider.notifier);
    final ticket = local.listening ? local.invitation : null;
    final receiving = widget.receiving || widget.advanced;
    final sending = !widget.receiving || widget.advanced;
    final addresses = local.addresses.map((e) => e['address'] as String).toList();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (transfer.phase == TransferPhase.connectionApproval) ...[const IncomingConnectionPanel(), const SizedBox(height: 16)],
      Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Semantics(liveRegion: true, child: StatusPill(label: transfer.secure ? 'Connected · encrypted' : transfer.active ? transfer.label : local.label,
          icon: transfer.secure ? Icons.verified_user_outlined : Icons.wifi_rounded)),
        const SizedBox(height: 12),
        if (!transfer.secure && !transfer.active) ...[
          const Text('Use the same Wi-Fi, or enable a hotspot on one phone and join it from the other. No internet needed.'),
          Wrap(spacing: 8, children: [
            TextButton(onPressed: () => controller.command('openWifiSettings'), child: const Text('Wi-Fi settings')),
            TextButton(onPressed: () => controller.command('openHotspotSettings'), child: const Text('Hotspot settings')),
          ]),
        ],
        if (local.message != null) Text(local.message!),
        if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        if (transfer.data['connectionMethod'] == 'localWifi' && transfer.data['message'] != null) Text(transfer.data['message'].toString()),
        if (transfer.data['pairingCode'] != null && !transfer.secure && transfer.active && transfer.phase != TransferPhase.connectionApproval) ...[
          const SizedBox(height: 12),
          Text(transfer.data['pairingCode'].toString(), textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineLarge),
          const Text('Compare this code on both phones. Accept on the receiver only if they match.', textAlign: TextAlign.center),
        ],
        if (!local.available && !transfer.secure) Text(local.data['networkHint'] as String? ?? 'No local address found. Join Wi-Fi or enable a hotspot, then refresh.'),
        if (widget.advanced && addresses.isNotEmpty) DropdownButtonFormField<String>(
          initialValue: addresses.contains(_address) ? _address : null, key: ValueKey(addresses.join(',')), isExpanded: true,
          decoration: const InputDecoration(labelText: 'Listening address (automatic by default)'),
          items: [for (final address in addresses) DropdownMenuItem(value: address, child: Text(address))],
          onChanged: blocked ? null : (value) => setState(() => _address = value)),
        if (receiving && !transfer.secure) ...[
          const SizedBox(height: 12),
          FilledButton.icon(onPressed: blocked || !local.available ? null : () => controller.command('startHost', {
            'name': ref.read(settingsProvider).deviceName, 'address': _address, 'bufferSize': ref.read(settingsProvider).transferBufferBytes}),
            icon: const Icon(Icons.qr_code_rounded), label: const Text('Start receiving')),
        ],
        if (sending && !transfer.secure && !transfer.active) ...[
          const SizedBox(height: 12),
          FilledButton.icon(onPressed: blocked || !local.available ? null : _scan,
            icon: const Icon(Icons.qr_code_scanner_rounded), label: const Text('Scan receiver QR')),
          OutlinedButton(onPressed: blocked || !local.available ? null : () => controller.command(local.discovering ? 'stopDiscovery' : 'discover'),
            child: Text(local.discovering ? 'Stop search' : 'Find receiver')),
        ],
        if (!transfer.secure && !transfer.active) TextButton(onPressed: () => controller.command('snapshot'), child: const Text('Refresh Wi-Fi status')),
        if (transfer.active || transfer.ready) TextButton(onPressed: () => ref.read(transferProvider.notifier).command('cancel'), child: const Text('Disconnect')),
      ])),
      if (ticket != null) ...[
        const SizedBox(height: 16),
        Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('Ready to connect', style: Theme.of(context).textTheme.titleMedium),
          const Text('On the sender, tap Scan receiver QR. This invitation expires after two minutes.'),
          const SizedBox(height: 12),
          LayoutBuilder(builder: (context, constraints) => Center(child: Container(color: Colors.white, padding: const EdgeInsets.all(10),
            child: QrImageView(data: ticket['uri'] as String, size: (constraints.maxWidth - 20).clamp(80.0, 220.0), backgroundColor: Colors.white)))),
          TextButton.icon(onPressed: () => Clipboard.setData(ClipboardData(text: ticket['uri'] as String)), icon: const Icon(Icons.copy_rounded), label: const Text('Copy invitation')),
          if (widget.advanced) ...[Text('${ticket['host']}:${ticket['port']}'), SelectableText(ticket['fingerprint'] as String)],
        ])),
      ],
      if (sending && !blocked) ...[
        for (final peer in local.peers) Padding(padding: const EdgeInsets.only(top: 12), child: Panel(child: ListTile(
          contentPadding: EdgeInsets.zero, title: Text(peer['name'] as String), subtitle: const Text('Receiver found · compare codes after connecting'),
          trailing: const Icon(Icons.chevron_right_rounded), onTap: () => _connect(peer['uri'] as String)))),
        if (local.discovering && local.peers.isEmpty) const Padding(padding: EdgeInsets.all(12), child: Text('Searching… If no receiver appears, scan its QR. Some hotspots block discovery.')),
        ExpansionTile(title: const Text('Paste invitation instead'), children: [
          TextField(controller: _link, maxLength: 2048, minLines: 1, maxLines: 4, autocorrect: false, enableSuggestions: false,
            decoration: const InputDecoration(labelText: 'FlashLink invitation link')),
          FilledButton(onPressed: () => _connect(_link.text), child: const Text('Connect with invitation')),
        ]),
      ],
    ]);
  }
}
