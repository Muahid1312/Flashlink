import 'package:flutter/services.dart';
import 'package:path/path.dart' as path;
import 'package:sqflite/sqflite.dart';
import '../domain/contracts.dart';
import '../domain/models.dart';

/// Opened lazily; no history is seeded or inferred from selected files.
class SqliteTransferRepository implements TransferRepository {
  SqliteTransferRepository({this.nativeActions = true});
  final bool nativeActions;
  Future<Database>? _database;
  Future<Database> get database => _database ??= _open();

  Future<Database> _open() async {
    try {
      return await openDatabase(
        path.join(await getDatabasesPath(), 'flashlink.db'),
        version: 3,
        onUpgrade: (db, old, next) async { await db.execute(_sessions); await db.execute(_legacyMigration); },
        onCreate: (db, version) async { await db.execute('''
      CREATE TABLE transfers (
        id TEXT PRIMARY KEY,
        file_name TEXT NOT NULL,
        bytes INTEGER NOT NULL,
        direction TEXT NOT NULL CHECK(direction IN ('sent', 'received')),
        completed_at INTEGER NOT NULL
      )
    '''); await db.execute(_sessions); },
      );
    } catch (_) {
      _database = null;
      rethrow;
    }
  }

  @override
  Future<List<TransferRecord>> readAll() async {
    final rows = nativeActions
      ? (await const MethodChannel('app.flashlink/transfer.v1').invokeListMethod<Object?>('history') ?? []).map((row) => Map<String,Object?>.from(row as Map)).toList()
      : await (await database).query('transfer_sessions', where: 'ended IS NOT NULL', orderBy: 'ended DESC', limit: 500);
    return rows.map((row) => TransferRecord(
      id: row['id'] as String,
      fileName: row['file_name'] as String,
      bytes: row['total'] as int,
      transferredBytes: row['bytes'] as int,
      status: row['status'] as String,
      verified: row['verified'] == 1,
      resumable: row['resumable'] == true,
      direction: row['direction'] as String,
      completedAt: DateTime.fromMillisecondsSinceEpoch(row['ended'] as int),
    )).toList();
  }

  Future<void> close() async {
    final pending = _database;
    _database = null;
    if(pending != null) { await (await pending).close(); }
  }

  @override
  Future<void> save(TransferRecord record) async {
    await (await database).insert('transfer_sessions', {
      'id': record.id, 'file_name': record.fileName, 'total': record.bytes, 'bytes': record.transferredBytes,
      'direction': record.direction, 'ended': record.completedAt.millisecondsSinceEpoch,
      'started': record.completedAt.millisecondsSinceEpoch, 'status': record.status, 'verified': record.verified ? 1 : 0, 'file_count': 1,
    }, conflictAlgorithm: ConflictAlgorithm.abort);
  }
}

const _sessions = 'CREATE TABLE IF NOT EXISTS transfer_sessions (id TEXT PRIMARY KEY,direction TEXT,peer TEXT,file_name TEXT,file_count INTEGER,total INTEGER,bytes INTEGER,started INTEGER,ended INTEGER,average REAL,status TEXT,verified INTEGER)';

const _legacyMigration = "INSERT OR IGNORE INTO transfer_sessions (id,direction,file_name,file_count,total,bytes,started,ended,status,verified) SELECT id,direction,file_name,1,bytes,bytes,completed_at,completed_at,'completed',0 FROM transfers";
