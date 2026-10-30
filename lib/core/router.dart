import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../features/screens.dart';
import '../features/connection_widgets.dart';
import '../features/transfer_widgets.dart';
import '../features/settings_screen.dart';
import '../features/diagnostics_screen.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(routes: [
    ShellRoute(builder: (context, state, child) => AppShell(location: state.uri.path, child: child), routes: [
      GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
      GoRoute(path: '/history', builder: (context, state) => const HistoryScreen()),
      GoRoute(path: '/settings', builder: (context, state) => const SettingsScreen()),
    ]),
    GoRoute(path: '/nearby', builder: (context, state) => Scaffold(appBar: AppBar(title: const Text('Advanced connections')), body: const SafeArea(child: NearbyScreen()))),
    GoRoute(path: '/send', builder: (context, state) => const SendScreen()),
    GoRoute(path: '/receive', builder: (context, state) => const ReceiveScreen()),
    GoRoute(path: '/diagnostics', builder: (context, state) => const DiagnosticsScreen()),
    GoRoute(path: '/transfer', builder: (context, state) => const TransferScreen()),
  ]);
  ref.onDispose(router.dispose);
  return router;
});

class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.location, required this.child});
  final String location;
  final Widget child;
  static const paths = ['/', '/history', '/settings'];
  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(child: child),
    bottomNavigationBar: NavigationBar(
      selectedIndex: paths.indexOf(location).clamp(0, 2),
      onDestinationSelected: (index) => context.go(paths[index]),
      destinations: const [
        NavigationDestination(icon: Icon(Icons.grid_view_outlined), selectedIcon: Icon(Icons.grid_view_rounded), label: 'Home'),
        NavigationDestination(icon: Icon(Icons.history_rounded), label: 'History'),
        NavigationDestination(icon: Icon(Icons.tune_rounded), label: 'Settings'),
      ],
    ),
  );
}
