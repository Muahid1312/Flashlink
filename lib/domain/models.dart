import 'package:flutter/material.dart';

class AppSettings {
  const AppSettings({this.theme = ThemeMode.system, this.deviceName = 'My FlashLink'});
  final ThemeMode theme;
  final String deviceName;

  AppSettings copyWith({ThemeMode? theme, String? deviceName}) => AppSettings(
    theme: theme ?? this.theme,
    deviceName: deviceName ?? this.deviceName,
  );
}

class SharedFile {
  const SharedFile({required this.uri, required this.name, this.bytes, this.mimeType});
  final String uri;
  final String name;
  final int? bytes;
  final String? mimeType;
}

class StorageInfo {
  const StorageInfo({required this.available, required this.total});
  final int available;
  final int total;
}

class Peer {
  const Peer({required this.id, required this.name});
  final String id;
  final String name;
}

enum ConnectionState { unavailable, disconnected, connecting, connected }

enum TransferState { preparing, running, paused, completed, cancelled, failed }

class TransferSnapshot {
  const TransferSnapshot({required this.id, required this.state, required this.totalBytes,
    required this.transferredBytes, this.bytesPerSecond, this.remaining});
  final String id;
  final TransferState state;
  final int totalBytes;
  final int transferredBytes;
  final double? bytesPerSecond;
  final Duration? remaining;
}

class TransferRecord {
  const TransferRecord({required this.id, required this.fileName, required this.bytes,
    required this.direction, required this.completedAt, this.status = 'completed', this.verified = false, this.transferredBytes = 0, this.resumable = false});
  final String id;
  final String fileName;
  final int bytes;
  final String direction;
  final DateTime completedAt;
  final String status;
  final bool verified;
  final int transferredBytes;
  final bool resumable;
}
