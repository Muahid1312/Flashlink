import 'package:flutter/material.dart';
import '../core/theme.dart';

class PageBody extends StatelessWidget {
  const PageBody({super.key, required this.children});
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topCenter,
    child: SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: 0, end: 1),
        duration: Duration(milliseconds: MediaQuery.disableAnimationsOf(context) ? 0 : 220),
        builder: (context, value, child) => Opacity(opacity: value,
          child: Transform.translate(offset: Offset(0, 8 * (1 - value)), child: child)),
        child: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 680),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children)),
      ),
    ),
  );
}

class Panel extends StatelessWidget {
  const Panel({super.key, required this.child, this.padding = const EdgeInsets.all(22)});
  final Widget child;
  final EdgeInsetsGeometry padding;
  @override
  Widget build(BuildContext context) => Container(
    padding: padding,
    decoration: BoxDecoration(color: Theme.of(context).colorScheme.surface,
      borderRadius: BorderRadius.circular(24),
      border: Border.all(color: Theme.of(context).colorScheme.outlineVariant.withValues(alpha: 0.65))),
    child: child,
  );
}

class StatusPill extends StatelessWidget {
  const StatusPill({super.key, this.label = 'Not connected', this.icon = Icons.link_off_rounded});
  final String label;
  final IconData icon;
  @override
  Widget build(BuildContext context) => Wrap(children: [Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
    decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(30)),
    child: Row(mainAxisSize: MainAxisSize.min, children: [
      Icon(icon, size: 15, color: Theme.of(context).colorScheme.onSurfaceVariant),
      const SizedBox(width: 7),
      Flexible(child: Text(label, style: Theme.of(context).textTheme.labelMedium)),
    ]),
  )]);
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.title, {super.key, this.trailing});
  final String title;
  final Widget? trailing;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 28, bottom: 14),
    child: Row(children: [Expanded(child: Text(title, style: Theme.of(context).textTheme.titleMedium)),
      if (trailing != null) trailing!]),
  );
}

class EmptyPanel extends StatelessWidget {
  const EmptyPanel({super.key, required this.icon, required this.title, required this.message});
  final IconData icon;
  final String title;
  final String message;
  @override
  Widget build(BuildContext context) => Panel(child: Column(children: [
    Container(padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest, shape: BoxShape.circle),
      child: Icon(icon, size: 30, color: Theme.of(context).colorScheme.secondary)),
    const SizedBox(height: 18),
    Text(title, textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleMedium),
    const SizedBox(height: 8),
    Text(message, textAlign: TextAlign.center,
      style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, height: 1.5)),
  ]));
}

class PhaseNotice extends StatelessWidget {
  const PhaseNotice({super.key, this.message = 'Connect in Nearby, confirm both certificate fingerprints, then approve the exact file request. Active transfers use notification controls.'});
  final String message;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 18),
    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Icon(Icons.info_outline_rounded, size: 18, color: Neon.muted),
      const SizedBox(width: 10),
      Expanded(child: Text(message, style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 12, height: 1.5))),
    ]),
  );
}

class OrbitGraphic extends StatelessWidget {
  const OrbitGraphic({super.key, this.size = 180, this.child});
  final double size;
  final Widget? child;
  @override
  Widget build(BuildContext context) => SizedBox(width: size, height: size, child: Stack(alignment: Alignment.center, children: [
    for (final scale in [1.0, 0.75, 0.5])
      Container(width: size * scale, height: size * scale,
        decoration: BoxDecoration(shape: BoxShape.circle,
          border: Border.all(color: Neon.primary.withValues(alpha: 0.12 + (1 - scale) * 0.15)),
          gradient: scale == 0.5 ? LinearGradient(colors: [Neon.primary.withValues(alpha: 0.3), Neon.secondary.withValues(alpha: 0.08)]) : null)),
    child ?? const Icon(Icons.wifi_tethering_rounded, size: 35, color: Neon.secondary),
  ]));
}
