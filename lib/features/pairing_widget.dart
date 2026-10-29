import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../services/transfer.dart';
import '../shared/widgets.dart';

class PairingPanel extends ConsumerStatefulWidget {
  const PairingPanel({super.key});
  @override
  ConsumerState<PairingPanel> createState() => _PairingPanelState();
}
class _PairingPanelState extends ConsumerState<PairingPanel> {
  final _pin = TextEditingController();
  bool _saving = false;
  String? _error;
  @override
  void dispose() { _pin.dispose(); super.dispose(); }
  Future<void> _approve() async {
    final normalized = _pin.text.trim().replaceFirst('flashlink:cert:','').replaceAll(RegExp(r'[:\s]'),'').toLowerCase();
    if(!RegExp(r'^[0-9a-f]{64}$').hasMatch(normalized)) { setState(() => _error = 'Enter the complete 64-digit fingerprint from the other phone.'); return; }
    setState(() { _saving = true; _error = null; });
    try {
      await ref.read(transferPlatformProvider).invoke('approvePairing',{'fingerprint':normalized});
      ref.invalidate(pairingProvider);
    } catch (_) { if(mounted) setState(() => _error = 'Pairing approval failed. Close the current session and retry.'); }
    finally { if(mounted) setState(() => _saving = false); }
  }
  @override
  Widget build(BuildContext context) {
    final transfer = ref.watch(transferProvider);
    return Panel(child:ref.watch(pairingProvider).when(
      loading:() => const Text('Loading this phone’s certificate fingerprint…'),
      error:(error,stack) => Column(children:[const Text('Pairing identity unavailable.'),TextButton(onPressed:() => ref.invalidate(pairingProvider),child:const Text('Retry identity'))]),
      data:(data) {
        final own = data['fingerprint'] as String;
        final paired = data['approvedPeer'] as String?;
        return Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
          Text('Confirm the other phone',style:Theme.of(context).textTheme.titleMedium),
          const SizedBox(height:12),
          const Text('Compare fingerprints on both phones in person. This QR contains this phone’s full public certificate fingerprint; scan it with a trusted QR reader and paste the result on the other phone. No camera permission is requested by FlashLink.'),
          const SizedBox(height:16),
          LayoutBuilder(builder:(context,constraints) => Center(child:Container(color:Colors.white,padding:const EdgeInsets.all(12),
            child:QrImageView(data:'flashlink:cert:$own',size:(constraints.maxWidth-24).clamp(80.0,180.0),backgroundColor:Colors.white)))),
          const SizedBox(height:12),
          const Text('This phone’s SHA-256 fingerprint'),SelectableText(own),
          if(paired != null) ...[const SizedBox(height:12),const Text('Locally approved peer fingerprint'),SelectableText(paired)],
          const SizedBox(height:16),
          TextField(controller:_pin,maxLength:160,autocorrect:false,enableSuggestions:false,enabled:!_saving && !transfer.ready && !transfer.active,
            decoration:InputDecoration(labelText:'Other phone’s full fingerprint',errorText:_error),maxLines:3,minLines:1),
          const SizedBox(height:12),
          FilledButton(onPressed:_saving || transfer.ready || transfer.active ? null : _approve,child:const Text('I compared and approve this peer')),
          const Text('Both users must approve. A different peer key cannot resume old transfers. Approval alone is not a secure connection: the badge appears only after mutual TLS authentication succeeds.'),
        ]);
      },
    ));
  }
}
