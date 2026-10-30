import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/format.dart';
import '../services/transfer.dart';
import '../shared/widgets.dart';

class DiagnosticsScreen extends ConsumerWidget {
  const DiagnosticsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final transfer = ref.watch(transferProvider);
    final live = transfer.data['metrics'];
    return Scaffold(
      appBar: AppBar(title: const Text('Performance diagnostics')),
      body: SafeArea(child: PageBody(children: [
        Text('Measure your connection.', style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 12),
        const Text('Actual transfer attempts, measured with monotonic clocks. MB/s uses 1,000,000 bytes. Average includes streaming, checkpoints and final saving; source preparation, approval waiting and earlier resumed bytes are excluded.'),
        const SizedBox(height: 12),
        const Text('File I/O rates measure calls into the storage provider or staging file; caches can affect them. Socket payload I/O time includes TLS and peer pacing. Raw radio throughput, signal and link rate are unavailable here. These values are measurements, not maximum-speed estimates.'),
        if(transfer.active && live is Map) ...[
          const SectionTitle('Current attempt'),
          _Sample(sample: {...Map<Object?,Object?>.from(live), ...transfer.data}),
        ],
        const SectionTitle('Local measurements'),
        ref.watch(diagnosticsProvider).when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_,_) => const Text('Diagnostics could not be loaded. Retry below.'),
          data: (rows) => rows.isEmpty
            ? const EmptyPanel(icon: Icons.speed_rounded, title: 'No measurements yet', message: 'Complete or interrupt a real file transfer to record a result. No benchmark data is generated.')
            : Column(children: [for(final sample in rows) Padding(padding: const EdgeInsets.only(bottom: 12), child: _Sample(sample: sample))]),
        ),
        Wrap(spacing: 12, children: [
          TextButton(onPressed: () => ref.invalidate(diagnosticsProvider), child: const Text('Refresh')),
          TextButton(onPressed: () async {
            try {
              await ref.read(transferPlatformProvider).invoke('clearDiagnostics');
              ref.invalidate(diagnosticsProvider);
            } catch(_) { if(context.mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Measurements could not be cleared.'))); }
          }, child: const Text('Clear measurements')),
        ]),
        const Text('Up to 100 attempts stay on this device, including interruptions. No filenames, file paths, hashes, addresses or peer identities are recorded. Use the same real files and compare buffer sizes and connection modes across repeated runs; results depend on both phones, storage and radio conditions.'),
      ])),
    );
  }
}

class _Sample extends StatelessWidget {
  const _Sample({required this.sample});
  final Map<Object?,Object?> sample;
  String speed(String key) {
    final value = sample[key];
    return value is num && value.isFinite && value >= 0 ? '${(value/1000000).toStringAsFixed(2)} MB/s' : '—';
  }
  String seconds(String key) {
    final value = sample[key];
    return value is num && value.isFinite && value >= 0 ? '${value.toStringAsFixed(3)} s' : '—';
  }
  String bytes(String key) {
    final value = sample[key];
    return value is num && value.isFinite && value >= 0 ? formatBytes(value.toInt()) : '—';
  }
  @override
  Widget build(BuildContext context) => Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
    Text('${sample['connectionMethod'] == 'localWifi' ? 'Local Wi-Fi / hotspot' : 'Wi-Fi Direct'} · ${sample['direction'] ?? 'attempt'}', style: Theme.of(context).textTheme.titleMedium),
    Text('${sample['status'] ?? 'Measuring'} · ${sample['fileCount'] ?? 0} files · ${formatBytes((sample['total'] as num?)?.toInt() ?? 0)}'),
    if(sample['recorded'] is num) Text(DateTime.fromMillisecondsSinceEpoch((sample['recorded'] as num).toInt()).toLocal().toString().split('.').first),
    const SizedBox(height: 12),
    Text('Application average: ${speed('applicationBps')}'),
    Text('Peak sample (≥250 ms): ${speed('peakSampleBps')}'),
    Text('Source read: ${speed('readBps')} · staging write: ${speed('writeBps')}'),
    Text('Streamed this attempt: ${formatBytes((sample['attemptBytes'] as num?)?.toInt() ?? 0)}'),
    Text('Duration: ${seconds('durationSeconds')}'),
    Text('Read / write time: ${seconds('readSeconds')} / ${seconds('writeSeconds')}'),
    Text('Socket payload I/O: ${seconds('socketIoSeconds')}'),
    Text('Streaming SHA-256: ${seconds('hashSeconds')}'),
    Text('Durable checkpoints: ${seconds('checkpointSeconds')}'),
    Text('Verification / resume validation: ${seconds('verificationSeconds')}'),
    Text('Verified destination saving: ${seconds('exportSeconds')}'),
    Text('Buffer: ${bytes('bufferSize')}'),
    Text('Actual socket buffers · send: ${bytes('socketSendBuffer')} · receive: ${bytes('socketReceiveBuffer')}'),
    Text('Resumed: ${sample['resumed'] == true || sample['resuming'] == true ? 'yes' : 'no'} · interruption in attempt: ${sample['interruptions'] ?? (sample['status'] == 'interrupted' || sample['status'] == 'paused' ? 1 : 0)}'),
    const Text('Stage times may overlap across the reader and writer; they should not be added together.', style: TextStyle(fontSize: 12)),
  ]));
}
