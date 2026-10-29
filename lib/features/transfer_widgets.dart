import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../core/format.dart';
import '../services/transfer.dart';
import '../services/wifi_direct.dart';
import '../shared/widgets.dart';
import 'pairing_widget.dart';

class TransferControls extends ConsumerWidget {
  const TransferControls({super.key,this.receiving = false});
  final bool receiving;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final transfer = ref.watch(transferProvider);
    final controller = ref.read(transferProvider.notifier);
    final wifi = ref.watch(wifiProvider);
    return Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,children: [
      StatusPill(label:transfer.label),
      if(transfer.secure) const StatusPill(label:'Authenticated · TLS',icon:Icons.verified_user_outlined),
      if(transfer.secure && transfer.data['authenticatedPeerFingerprint'] != null) ExpansionTile(title:const Text('Authenticated peer fingerprint'),children:[SelectableText(transfer.data['authenticatedPeerFingerprint'].toString())]), const SizedBox(height:12),
      if(transfer.data['message'] != null) Text(transfer.data['message'].toString()),
      if(receiving) ...[
        Text(transfer.destinationChosen ? 'Destination folder selected' : 'Choose the folder where approved files will be saved.'),
        TextButton(onPressed:transfer.active || transfer.ready ? null : () => controller.command('chooseDestination'),child:const Text('Choose destination folder')),
      ],
      if(!transfer.ready && !transfer.active) const Padding(padding:EdgeInsets.symmetric(vertical:16),child:PairingPanel()),
      if(!transfer.ready) FilledButton(onPressed:!wifi.connected || transfer.active || (receiving && !transfer.destinationChosen) ? null : controller.openSession,
        child:Text(wifi.group?['isGroupOwner'] == true ? 'Open TCP listener' : 'Connect TCP session')),
      if(!transfer.ready && !transfer.active && wifi.connected) TextButton(onPressed:controller.reconnect,child:const Text('Retry secure connection · up to 3 attempts')),
      if(transfer.incoming) ...[
        Text('Authenticated peer: ${transfer.data['peer'] ?? 'Connected device'}',style:Theme.of(context).textTheme.titleMedium),
        Text('${transfer.count} files · ${formatBytes(transfer.total)}'),
        Text('Destination: ${transfer.data['destinationLabel'] ?? 'No folder selected'}'),
        for(final item in transfer.data['files'] as List? ?? const []) Text((item as Map)['name'].toString()),
        const SizedBox(height:12),
        FilledButton(onPressed:transfer.destinationChosen ? controller.accept : null,child:const Text('Accept files')),
        TextButton(onPressed:controller.reject,child:const Text('Reject')),
        if(!transfer.destinationChosen) const Text('No destination was selected. Reject, choose a folder, then reopen the session.'),
      ],
      if(transfer.active || transfer.phase == TransferPhase.completed) TextButton(onPressed:() => context.push('/transfer'),child:const Text('View transfer progress')),
      if(transfer.ready || transfer.active) OutlinedButton(onPressed:() => controller.command('cancel'),child:Text(transfer.active ? 'Cancel transfer' : 'Close TCP session')),
      TextButton(onPressed:() => controller.command('notificationPermission'),child:const Text('Allow transfer notifications')),
      const Text('Active transfers use a connected-device foreground service with notification controls. Android may still stop the process; only durable checkpoints can resume. Start the owner’s listener first.',style:TextStyle(fontSize:12)),
    ]));
  }
}

class TransferScreen extends ConsumerWidget {
  const TransferScreen({super.key});
  String _speed(double? bytes) => bytes == null ? '— MB/s' : '${(bytes/1000000).toStringAsFixed(2)} MB/s';
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(transferProvider);
    return Scaffold(appBar:AppBar(title:const Text('Transfer progress')),body:SafeArea(child:PageBody(children:[
      Semantics(liveRegion:true,child:StatusPill(label:state.label)),
      const SizedBox(height:12),
      Text(state.secure ? 'Connection: authenticated and encrypted' : state.active ? 'Connection: establishing or awaiting peer' : 'Connection: closed'),
      const SizedBox(height:24),
      Text(state.data['currentFile'] as String? ?? 'Your direct connection',style:Theme.of(context).textTheme.headlineMedium),
      const SizedBox(height:28),
      Center(child:SizedBox(width:220,height:220,child:Stack(alignment:Alignment.center,children:[
        SizedBox(width:210,height:210,child:CircularProgressIndicator(value:state.fraction,strokeWidth:10,
          semanticsLabel:state.label,backgroundColor:Theme.of(context).colorScheme.surfaceContainerHighest)),
        Text(state.data['id'] == null || state.phase == TransferPhase.preparing ? '—' : '${(state.fraction*100).toStringAsFixed(0)}%',style:Theme.of(context).textTheme.headlineLarge),
      ]))),
      const SizedBox(height:28),
      Panel(child:Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
        Text('${formatBytes(state.bytes)} / ${formatBytes(state.total)}',style:Theme.of(context).textTheme.titleLarge),
        Text('${state.completed} / ${state.count} files verified'),
        if(state.phase == TransferPhase.finalizing && state.data['finalizedBytes'] != null) Text('Saved to destination: ${formatBytes((state.data['finalizedBytes'] as num).toInt())}'),
        Text('Current file: ${formatBytes((state.data['fileBytes'] as num?)?.toInt() ?? 0)}'),
        const Divider(height:28),
        Text('Current speed: ${_speed(state.speed)}'), Text('Average speed: ${_speed(state.average)}'),
        Text('Elapsed: ${((state.data['elapsedSeconds'] as num?) ?? 0).toStringAsFixed(1)} s'),
        Text('Time remaining: ${state.eta == null || !state.active ? '—' : '${state.eta!.ceil()} s'}'),
        if(state.data['message'] != null) Text(state.data['message'].toString()),
      ])),
      const SizedBox(height:24),
      if(state.active) FilledButton.icon(onPressed:() => ref.read(transferProvider.notifier).command('cancel'),icon:const Icon(Icons.close_rounded),label:const Text('Cancel transfer')),
      if(const [TransferPhase.transferring,TransferPhase.verifying].contains(state.phase)) OutlinedButton(onPressed:() => ref.read(transferProvider.notifier).command('pause'),child:const Text('Pause at checkpoint')),
      if(const [TransferPhase.paused,TransferPhase.interrupted,TransferPhase.failed].contains(state.phase)) TextButton(onPressed:() => context.go('/history'),child:const Text('Open saved transfers to resume')),
      if(state.secure) const StatusPill(label:'Authenticated · TLS',icon:Icons.verified_user_outlined),
      const PhaseNotice(message:'Progress counts real streamed bytes. Interrupted/paused data stays private at durable checkpoints; Cancel removes partial data. Completion requires SHA-256 verification and receiver acknowledgement. Resume requires the same authenticated peer and approved manifest.'),
    ])));
  }
}
