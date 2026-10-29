import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../features/screens.dart';
import '../features/connection_widgets.dart';
import '../features/transfer_widgets.dart';
import '../features/settings_screen.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(routes: [
    ShellRoute(builder: (context, state, child) => AppShell(location: state.uri.path, child: child), routes: [
      GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
      GoRoute(path: '/nearby', builder: (context, state) => const NearbyScreen()),
      GoRoute(path: '/history', builder: (context, state) => const HistoryScreen()),
      GoRoute(path: '/settings', builder: (context, state) => const SettingsScreen()),
    ]),
    GoRoute(path: '/send', builder: (context, state) => const SendScreen()),
    GoRoute(path: '/receive', builder: (context, state) => const ReceiveScreen()),
    GoRoute(path: '/transfer', builder: (context, state) => const TransferScreen()),
  ]);
  ref.onDispose(router.dispose);
  return router;
});

class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.location, required this.child});
  final String location;
  final Widget child;
  static const paths = ['/', '/nearby', '/history', '/settings'];
  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(child: child),
    bottomNavigationBar: NavigationBar(
      selectedIndex: paths.indexOf(location).clamp(0, 3),
      onDestinationSelected: (index) => context.go(paths[index]),
      destinations: const [
        NavigationDestination(icon: Icon(Icons.grid_view_outlined), selectedIcon: Icon(Icons.grid_view_rounded), label: 'Home'),
        NavigationDestination(icon: Icon(Icons.radar_outlined), selectedIcon: Icon(Icons.radar_rounded), label: 'Nearby'),
        NavigationDestination(icon: Icon(Icons.history_rounded), label: 'History'),
        NavigationDestination(icon: Icon(Icons.tune_rounded), label: 'Settings'),
      ],
    ),
  );
}
