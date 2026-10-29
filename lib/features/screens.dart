import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../core/format.dart';
import '../core/providers.dart';
import '../core/theme.dart';
import '../domain/models.dart';
import '../shared/widgets.dart';
import 'connection_widgets.dart';
import 'transfer_widgets.dart';
import '../services/transfer.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return PageBody(children: [
      Padding(padding: const EdgeInsets.symmetric(vertical: 18), child: Row(children: [
        Container(padding: const EdgeInsets.all(10), decoration: BoxDecoration(
          gradient: const LinearGradient(colors: [Neon.primary, Color(0xFFAB84FF)]), borderRadius: BorderRadius.circular(14)),
          child: const Icon(Icons.bolt_rounded, color: Colors.white, size: 26)),
        const SizedBox(width: 12),
        Expanded(child: Text('FlashLink', style: theme.textTheme.titleLarge)),
        const Tooltip(message: 'Offline direct file sharing', child: Icon(Icons.offline_bolt_outlined, color: Neon.secondary)),
      ])),
      const SizedBox(height: 22),
      Text('Your files.\nNo limits on possibility.', style: theme.textTheme.headlineLarge),
      const SizedBox(height: 12),
      Text('A direct route from one phone to another.\nPrivate by design. Built for what’s next.',
        style: TextStyle(color: theme.colorScheme.onSurfaceVariant, height: 1.6)),
      const SizedBox(height: 28),
      LayoutBuilder(builder: (context, constraints) {
        final cards = [
          _ActionCard(title: 'Send files', subtitle: 'Choose what moves next', icon: Icons.north_east_rounded,
            primary: true, onTap: () => context.push('/send')),
          _ActionCard(title: 'Receive files', subtitle: 'Make room for more', icon: Icons.south_west_rounded,
            onTap: () => context.push('/receive')),
        ];
        if (constraints.maxWidth < 360 || MediaQuery.textScalerOf(context).scale(1) > 1.3) {
          return Column(children: [cards[0], const SizedBox(height: 14), cards[1]]);
        }
        return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Expanded(child: cards[0]), const SizedBox(width: 14), Expanded(child: cards[1])]);
      }),
      const SectionTitle('Your connection'),
      const ConnectionPanel(),
      SectionTitle('Recent transfers', trailing: TextButton(onPressed: () => context.go('/history'), child: const Text('View all'))),
      const HistoryContent(compact: true),
      const PhaseNotice(),
    ]);
  }
}

class _ActionCard extends StatelessWidget {
  const _ActionCard({required this.title, required this.subtitle, required this.icon, required this.onTap, this.primary = false});
  final String title;
  final String subtitle;
  final IconData icon;
  final VoidCallback onTap;
  final bool primary;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final foreground = primary ? Colors.white : scheme.onSurface;
    return Material(color: Colors.transparent, child: Ink(
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(24),
        gradient: primary ? const LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight,
          colors: [Color(0xFF8868FF), Color(0xFF5F3DD1)]) : null,
        color: primary ? null : scheme.surface,
        border: Border.all(color: primary ? Colors.white.withValues(alpha: 0.16) : scheme.outlineVariant)),
      child: InkWell(onTap: onTap, borderRadius: BorderRadius.circular(24),
        child: Padding(padding: const EdgeInsets.all(22), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(icon, color: primary ? Colors.white : scheme.secondary, size: 34),
          const SizedBox(height: 28),
          Text(title, style: Theme.of(context).textTheme.titleLarge?.copyWith(color: foreground)),
          const SizedBox(height: 6),
          Text(subtitle, style: TextStyle(color: primary ? Colors.white.withValues(alpha: 0.85) : scheme.onSurfaceVariant, fontSize: 12)),
        ])),
      ),
    ));
  }
}

class SendScreen extends ConsumerWidget {
  const SendScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(selectionProvider);
    final files = selected.asData?.value ?? <SharedFile>[];
    final knownBytes = files.fold<int>(0, (total, file) => total + (file.bytes ?? 0));
    final unknown = files.any((file) => file.bytes == null);
    return Scaffold(appBar: AppBar(title: const Text('Send files')), body: SafeArea(child: PageBody(children: [
      Text('Good things\nare worth sharing.', style: Theme.of(context).textTheme.headlineLarge),
      const SizedBox(height: 12),
      Text('Select files securely with the Android file picker.', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      const SizedBox(height: 24),
      Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        const Icon(Icons.add_to_photos_outlined, size: 44, color: Neon.secondary),
        const SizedBox(height: 18),
        FilledButton.icon(onPressed: selected.isLoading || ref.watch(transferProvider).active ? null : () async {
          final error = await ref.read(selectionProvider.notifier).pick();
          if (context.mounted && error != null) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(error)));
        }, icon: const Icon(Icons.add_rounded), label: Text(selected.isLoading ? 'Opening picker…' : 'Choose files')),
        const SizedBox(height: 10),
        const Text('Photos, videos, documents & more', textAlign: TextAlign.center),
      ])),
      SectionTitle('Selection', trailing: files.isEmpty ? null : TextButton(onPressed: ref.read(selectionProvider.notifier).clear, child: const Text('Clear'))),
      if (files.isEmpty) const EmptyPanel(icon: Icons.file_copy_outlined, title: 'Your selection starts here', message: 'Files you choose will appear here. Nothing is sent automatically.')
      else Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('${files.length} selected · ${formatBytes(knownBytes)}${unknown ? ' + unknown size' : ''}', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 12),
        for (final file in files) ListTile(contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.insert_drive_file_outlined), title: Text(file.name, maxLines: 2, overflow: TextOverflow.ellipsis),
          subtitle: Text(file.bytes == null ? 'Size unavailable' : formatBytes(file.bytes!))),
      ])),
      const SectionTitle('Destination'),
      const ConnectionPanel(),
      const SizedBox(height: 24),
      const TransferControls(),
      const SizedBox(height:12),
      FilledButton(onPressed:files.isEmpty || !ref.watch(transferProvider).ready || ref.watch(transferProvider).active ? null :
        () async { await ref.read(transferProvider.notifier).send(files); if(context.mounted) context.push('/transfer'); },child:const Text('Request file transfer')),
      TextButton(onPressed: () => context.push('/transfer'), child: const Text('View transfer screen')),
      const PhaseNotice(message: 'The authenticated receiver must approve the exact manifest before file bytes are sent. Pause retains private checkpoints.'),
    ])));
  }
}

class ReceiveScreen extends ConsumerWidget {
  const ReceiveScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(title: const Text('Receive files')),
    body: SafeArea(child: PageBody(children: [
      const SizedBox(height: 16),
      Text('A little closer.\nA lot more connected.', style: Theme.of(context).textTheme.headlineLarge),
      const SizedBox(height: 28),
      const Center(child: OrbitGraphic(size: 210)),
      const SizedBox(height: 24),
      Text(ref.watch(settingsProvider).deviceName, textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 12),
      const SectionTitle('Connection'),
      const ConnectionPanel(receiving: true),
      const SectionTitle('Incoming transfer'),
      const TransferControls(receiving:true),
      const PhaseNotice(),
    ])),
  );
}

class HistoryScreen extends ConsumerWidget {
  const HistoryScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => PageBody(children: [
    const SizedBox(height: 20), Text('Transfer history', style: Theme.of(context).textTheme.headlineLarge),
    const SizedBox(height: 10),
    Text('Every connection leaves a story.', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
    const SizedBox(height: 30), const HistoryContent(),
    const PhaseNotice(message: 'Actual outcomes are stored only on this device. Resume uses validated checkpoints. Reconnect and authenticate the original peer before resuming from the sending phone.'),
  ]);
}

class HistoryContent extends ConsumerWidget {
  const HistoryContent({super.key, this.compact = false});
  final bool compact;
  @override
  Widget build(BuildContext context, WidgetRef ref) => ref.watch(historyProvider).when(
    loading: () => const Center(child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator())),
    error: (error, stack) => Panel(child: Column(children: [
      const Text('Transfer history could not be loaded.'),
      TextButton(onPressed: () => ref.invalidate(historyProvider), child: const Text('Retry')),
    ])),
    data: (records) {
      if (records.isEmpty) return const EmptyPanel(icon: Icons.history_rounded, title: 'A fresh start', message: 'No transfers yet. Actual transfer outcomes will live here.');
      final visible = compact ? records.take(3) : records;
      return Panel(child: Column(children: [for (final record in visible)
        ListTile(contentPadding: EdgeInsets.zero,
          leading: Icon(record.direction == 'sent' ? Icons.north_east_rounded : Icons.south_west_rounded),
          title: Text(record.fileName, maxLines: 2, overflow: TextOverflow.ellipsis),
          subtitle: Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
            Text('${record.status} · ${record.direction} · ${formatBytes(record.transferredBytes)} / ${formatBytes(record.bytes)}'),
            if(record.verified) const Text('SHA-256 verified'),
            if(record.resumable && record.direction == 'sent') TextButton(onPressed:ref.watch(transferProvider).secure && !ref.watch(transferProvider).active ? () => ref.read(transferProvider.notifier).resume(record.id) : null,child:Text(record.status == 'failed' ? 'Validate & retry' : 'Validate & resume')),
            if(record.resumable && record.direction == 'received') const Text('Ready for the original sender to resume after reconnection'),
            if(!record.resumable && const ['paused','interrupted','failed'].contains(record.status)) const Text('Resume data is unavailable; select files for a new approved request.'),
            Wrap(children:[
              if(record.resumable) TextButton(onPressed:() async { await ref.read(transferProvider.notifier).command('cancel',{'id':record.id}); ref.invalidate(historyProvider); },child:const Text('Cancel saved transfer')),
              TextButton(onPressed:() async {
                final confirmed = await showDialog<bool>(context:context,builder:(dialogContext) => AlertDialog(
                  title:const Text('Delete this transfer?'),
                  content:const Text('History and unfinished data will be removed. Completed destination files are kept.'),
                  actions:[TextButton(onPressed:() => Navigator.pop(dialogContext,false),child:const Text('Keep transfer')),FilledButton(onPressed:() => Navigator.pop(dialogContext,true),child:const Text('Delete'))],
                ));
                if(confirmed == true && context.mounted) {
                  await ref.read(transferProvider.notifier).command('deleteHistory',{'id':record.id}); ref.invalidate(historyProvider);
                }
              },child:const Text('Delete history & partials')),
            ]),
          ])),
      ]));
    },
  );
}
