import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../core/format.dart';
import '../services/transfer.dart';
import '../services/wifi_direct.dart';
import '../shared/widgets.dart';
import 'pairing_widget.dart';
import 'connection_widgets.dart';
import '../services/connection.dart';

class TransferControls extends ConsumerWidget {
  const TransferControls({super.key,this.receiving = false});
  final bool receiving;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final transfer = ref.watch(transferProvider);
    final controller = ref.read(transferProvider.notifier);
    final wifi = ref.watch(wifiProvider);
    final localMode = ref.watch(effectiveConnectionMethodProvider) == ConnectionMethod.localWifi;
    return Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,children: [
      StatusPill(label:transfer.label),
      if(transfer.secure) const StatusPill(label:'Authenticated · TLS',icon:Icons.verified_user_outlined),
      if(transfer.secure && transfer.data['authenticatedPeerFingerprint'] != null) ExpansionTile(title:const Text('Authenticated peer fingerprint'),children:[SelectableText(transfer.data['authenticatedPeerFingerprint'].toString())]), const SizedBox(height:12),
      if(transfer.data['message'] != null) Text(transfer.data['message'].toString()),
      if(receiving && !transfer.incoming) ...[
        Text(transfer.destinationChosen ? 'Destination folder selected' : 'Choose the folder where approved files will be saved.'),
        TextButton(onPressed:transfer.canChooseDestination ? () => controller.command('chooseDestination') : null,child:Text(transfer.choosingDestination ? 'Choosing folder…' : 'Choose destination folder')),
      ],
      if(!transfer.ready && !transfer.active) const Padding(padding:EdgeInsets.symmetric(vertical:16),child:PairingPanel()),
      if (transfer.phase == TransferPhase.connectionApproval) const IncomingConnectionPanel(),
      if (localMode && !transfer.ready && !transfer.active) TextButton(onPressed: () => context.go('/nearby'), child: const Text('Open Local Wi-Fi / hotspot pairing')),
      if(!localMode && !transfer.ready) FilledButton(onPressed:!wifi.connected || transfer.active || (receiving && !transfer.destinationChosen) ? null : controller.openSession,
        child:Text(wifi.group?['isGroupOwner'] == true ? 'Open TCP listener' : 'Connect TCP session')),
      if(!localMode && !transfer.ready && !transfer.active && wifi.connected) TextButton(onPressed:controller.reconnect,child:const Text('Retry secure connection · up to 3 attempts')),
      if(transfer.incoming) const IncomingFilesPanel(),
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
        if (state.phase == TransferPhase.preparing && state.data['preparingBytes'] != null) ...[
          Text('Preparing: ${state.data['preparingFile'] ?? 'selected files'}'),
          Text('${formatBytes((state.data['preparingBytes'] as num).toInt())} source bytes checked · ${state.data['preparedFiles'] ?? 0} / ${state.count} files prepared'),
        ],
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
      const TransferQueue(),
      TextButton(onPressed: () => context.push('/diagnostics'), child: const Text('View performance diagnostics')),
      if (state.phase == TransferPhase.connectionApproval) const IncomingConnectionPanel(),
      if (state.incoming) const IncomingFilesPanel(),
      if(state.active) FilledButton.icon(onPressed:() => ref.read(transferProvider.notifier).command('cancel'),icon:const Icon(Icons.close_rounded),label:const Text('Cancel transfer')),
      if(const [TransferPhase.transferring,TransferPhase.verifying].contains(state.phase)) OutlinedButton(onPressed:() => ref.read(transferProvider.notifier).command('pause'),child:const Text('Pause at checkpoint')),
      if(const [TransferPhase.paused,TransferPhase.interrupted,TransferPhase.failed].contains(state.phase)) TextButton(onPressed:() => context.go('/history'),child:const Text('Open saved transfers to resume')),
      Text('Method: ${state.data['connectionMethod'] == 'localWifi' ? 'Local Wi-Fi / hotspot' : 'Wi-Fi Direct'}'),
      if(state.secure) const StatusPill(label:'Authenticated · TLS',icon:Icons.verified_user_outlined),
      const PhaseNotice(message:'Progress counts real streamed bytes. Interrupted/paused data stays private at durable checkpoints; Cancel removes partial data. Completion requires SHA-256 verification and receiver acknowledgement. Resume requires the same authenticated peer and approved manifest.'),
    ])));
  }
}

class IncomingFilesPanel extends ConsumerWidget {
  const IncomingFilesPanel({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final transfer = ref.watch(transferProvider);
    final controller = ref.read(transferProvider.notifier);
    return Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text('Review incoming files', style: Theme.of(context).textTheme.titleMedium),
      Text('Authenticated peer: ${transfer.data['peer'] ?? 'Connected device'}'),
      Text('${transfer.count} files · ${formatBytes(transfer.total)}'),
      Text('Destination: ${transfer.data['destinationLabel'] ?? 'Choose a folder below'}'),
      for (final item in transfer.data['files'] as List? ?? const [])
        ListTile(contentPadding: EdgeInsets.zero, title: Text((item as Map)['name'].toString()),
          subtitle: Text('${formatBytes((item['size'] as num).toInt())} · ${item['mime']}')),
      TextButton(onPressed: transfer.canChooseDestination ? () => controller.command('chooseDestination') : null,
        child: Text(transfer.choosingDestination ? 'Choosing folder…' : 'Choose destination folder')),
      const Text('No file bytes are accepted until you approve this exact request. Folder selection alone does not approve it.'),
      FilledButton(onPressed: transfer.secure && transfer.destinationChosen && !transfer.choosingDestination ? controller.accept : null, child: const Text('Accept files')),
      TextButton(onPressed: controller.reject, child: const Text('Reject')),
    ]));
  }
}


class TransferQueue extends ConsumerWidget {
  const TransferQueue({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(transferProvider);
    final files = state.data['files'] as List? ?? const [];
    if(files.isEmpty) return const SizedBox.shrink();
    final index = (state.data['currentFileIndex'] as num?)?.toInt();
    return Panel(child: ExpansionTile(tilePadding: EdgeInsets.zero,
      title: Text('File queue · ${state.completed} / ${files.length} verified'),
      children: [for(var i = 0; i < files.length; i++) Builder(builder: (context) {
        final file = files[i] as Map;
        final size = (file['size'] as num).toInt();
        final verified = i < state.completed;
        final current = !verified && i == index && state.data['currentFile'] != null;
        final copied = verified ? size : current ? (state.data['fileBytes'] as num?)?.toInt() ?? 0 : 0;
        return ListTile(contentPadding: EdgeInsets.zero,
          leading: Icon(verified ? Icons.check_circle_outline : current ? Icons.sync_rounded : Icons.schedule_rounded),
          title: Text(file['name'].toString()), subtitle: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text('${verified ? 'Verified' : current ? state.label : state.active ? 'Queued' : 'Unfinished'} · ${formatBytes(copied)} / ${formatBytes(size)}'),
            if(current) LinearProgressIndicator(value: size > 0 ? (copied/size).clamp(0.0,1.0) : null),
          ]));
      })]));
  }
}
