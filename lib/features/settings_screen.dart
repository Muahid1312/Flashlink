import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/format.dart';
import '../core/providers.dart';
import '../shared/widgets.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});
  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  bool _saving = false;

  Future<void> _save(Future<void> Function() action) async {
    setState(() => _saving = true);
    try {
      await action();
    } catch (_) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Settings could not be saved. Please try again.')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _editName() async {
    final name = await showDialog<String>(context: context, builder: (context) =>
      _DeviceNameDialog(initialName: ref.read(settingsProvider).deviceName));
    if (name != null && mounted) await _save(() => ref.read(settingsProvider.notifier).setName(name));
  }

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(settingsProvider);
    return PageBody(children: [
      const SizedBox(height: 20),
      Text('Make it yours.', style: Theme.of(context).textTheme.headlineLarge),
      const SizedBox(height: 10),
      Text('A few thoughtful details. All on your device.', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      const SectionTitle('Appearance'),
      Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('FlashLink Neon', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8), const Text('Choose your light.'), const SizedBox(height: 18),
        for (final mode in ThemeMode.values)
          Padding(padding: const EdgeInsets.only(bottom: 8), child: OutlinedButton.icon(
            key: ValueKey('theme-${mode.name}'),
            onPressed: _saving ? null : () => _save(() => ref.read(settingsProvider.notifier).setTheme(mode)),
            style: OutlinedButton.styleFrom(alignment: Alignment.centerLeft,
              backgroundColor: settings.theme == mode ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.12) : null,
              padding: const EdgeInsets.all(16)),
            icon: Icon(settings.theme == mode ? Icons.check_circle_rounded : switch (mode) {
              ThemeMode.system => Icons.brightness_auto_outlined,
              ThemeMode.light => Icons.light_mode_outlined,
              ThemeMode.dark => Icons.dark_mode_outlined,
            }),
            label: Text(switch (mode) { ThemeMode.system => 'Use system theme', ThemeMode.light => 'Light', ThemeMode.dark => 'Dark' }),
          )),
        if (_saving) const LinearProgressIndicator(),
      ])),
      const SectionTitle('This device'),
      Panel(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8), child: ListTile(
        leading: const Icon(Icons.smartphone_rounded), title: const Text('Display name'),
        subtitle: Text(settings.deviceName), trailing: const Icon(Icons.edit_outlined, size: 20),
        onTap: _saving ? null : _editName,
      )),
      const SectionTitle('Storage'),
      Panel(child: ref.watch(storageProvider).when(
        loading: () => const Text('Checking available storage…'),
        error: (error, stack) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Storage information unavailable'),
          TextButton(onPressed: () => ref.invalidate(storageProvider), child: const Text('Retry')),
        ]),
        data: (storage) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${formatBytes(storage.available)} available', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          LinearProgressIndicator(value: storage.total <= 0 ? 0 : (1 - storage.available / storage.total).clamp(0.0, 1.0), minHeight: 6, borderRadius: BorderRadius.circular(4)),
          const SizedBox(height: 10), Text('${formatBytes(storage.total)} total · app storage volume'),
        ]),
      )),
      const SectionTitle('About FlashLink'),
      const Panel(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('FlashLink 0.1.0 · Preview', style: TextStyle(fontWeight: FontWeight.w700)),
        SizedBox(height: 12), Text('Direct sharing, thoughtfully built.'), SizedBox(height: 8),
        Text('No account. No cloud. Background transfers use a visible foreground-service notification.'),
      ])),
      const PhaseNotice(message: 'Display names and appearance are saved locally. Wi-Fi Direct is available in Nearby. Transfers require mutual TLS authentication and receiver approval. Paused data is kept privately for validated resume.'),
    ]);
  }
}

class _DeviceNameDialog extends StatefulWidget {
  const _DeviceNameDialog({required this.initialName});
  final String initialName;
  @override
  State<_DeviceNameDialog> createState() => _DeviceNameDialogState();
}

class _DeviceNameDialogState extends State<_DeviceNameDialog> {
  late final _controller = TextEditingController(text: widget.initialName);
  final _formKey = GlobalKey<FormState>();

  void _submit() {
    if (_formKey.currentState!.validate()) Navigator.pop(context, _controller.text.trim());
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Device display name'),
    content: Form(key: _formKey, child: TextFormField(
      controller: _controller, autofocus: true, maxLength: 32,
      decoration: const InputDecoration(labelText: 'Device name', helperText: 'Local label; Android names peers separately'),
      validator: (value) => value == null || value.trim().isEmpty ? 'Enter a device name' : null,
      onFieldSubmitted: (_) => _submit(),
    )),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
      FilledButton(onPressed: _submit, child: const Text('Save')),
    ],
  );
}
