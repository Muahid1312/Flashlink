import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'core/providers.dart';
import 'core/router.dart';
import 'core/theme.dart';

class FlashLinkApp extends ConsumerWidget {
  const FlashLinkApp({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
    title: 'FlashLink', debugShowCheckedModeBanner: false,
    theme: Neon.theme(Brightness.light), darkTheme: Neon.theme(Brightness.dark),
    themeMode: ref.watch(settingsProvider).theme,
    themeAnimationDuration: const Duration(milliseconds: 240),
    routerConfig: ref.watch(routerProvider),
  );
}
