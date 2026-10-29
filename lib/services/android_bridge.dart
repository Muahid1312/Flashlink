import 'package:flutter/services.dart';
import '../domain/models.dart';

/// Versioned contract shared with FlashLinkBridge.kt. No discovery is started.
class AndroidBridge {
  static const _methods = MethodChannel('app.flashlink/native.v1');

  Future<List<SharedFile>?> pickFiles() async {
    final result = await _methods.invokeListMethod<Object?>('pickFiles');
    if (result == null) return null; // User cancelled; preserve prior selection.
    return result.map((item) {
      final map = Map<Object?, Object?>.from(item! as Map);
      return SharedFile(uri: map['uri']! as String, name: map['name']! as String,
        bytes: map['bytes'] as int?, mimeType: map['mimeType'] as String?);
    }).toList();
  }

  Future<StorageInfo> storageInfo() async {
    final map = await _methods.invokeMapMethod<String, Object?>('storageInfo');
    if (map == null) throw StateError('Storage information unavailable');
    return StorageInfo(available: map['available']! as int, total: map['total']! as int);
  }
}
