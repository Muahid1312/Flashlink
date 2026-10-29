String formatBytes(int bytes) {
  if (bytes < 1000) return '$bytes B';
  if (bytes < 1000000) return '${(bytes / 1000).toStringAsFixed(1)} KB';
  if (bytes < 1000000000) return '${(bytes / 1000000).toStringAsFixed(1)} MB';
  return '${(bytes / 1000000000).toStringAsFixed(1)} GB';
}
