import 'models.dart';

abstract interface class DiscoveryService {
  Stream<List<Peer>> get peers;
  Future<void> start();
  Future<void> stop();
}

abstract interface class ConnectionService {
  Stream<ConnectionState> get states;
  Future<void> connect(Peer peer);
  Future<void> disconnect();
}

/// Implement with bounded buffers and content-URI streams in a later phase.
abstract interface class FileStreamingService {
  Stream<TransferSnapshot> send(List<SharedFile> files, Peer peer);
  Future<void> cancel(String transferId);
  Future<void> pause(String transferId);
}

abstract interface class TransferRepository {
  Future<List<TransferRecord>> readAll();
  Future<void> save(TransferRecord record);
}
