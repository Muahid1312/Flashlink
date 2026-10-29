import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:flashlink/data/transfer_repository.dart';
import 'package:flashlink/domain/models.dart';

void main() {
  late Directory temporary;
  late SqliteTransferRepository repository;
  setUp(() async {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    temporary = await Directory.systemTemp.createTemp('flashlink-history-test-');
    await databaseFactory.setDatabasesPath(temporary.path);
    repository = SqliteTransferRepository(nativeActions:false);
  });
  tearDown(() async {
    await repository.close();
    await temporary.delete(recursive:true);
  });
  test('actual terminal outcomes and verification survive closing and reopening SQLite',() async {
    for(final status in ['completed','failed','cancelled','paused','interrupted']) {
      await repository.save(TransferRecord(id:status,fileName:'actual-fixture.bin',bytes:5000000000,
        transferredBytes:status == 'completed' ? 5000000000 : 1234,
        direction:'received',completedAt:DateTime.fromMillisecondsSinceEpoch(1000),
        status:status,verified:status == 'completed'));
    }
    await repository.close();
    repository = SqliteTransferRepository(nativeActions:false);
    final saved = await repository.readAll();
    expect(saved.length,5);
    final completed = saved.singleWhere((row) => row.status == 'completed');
    expect(completed.verified,isTrue); expect(completed.bytes,5000000000);
    expect(saved.singleWhere((row) => row.status == 'failed').transferredBytes,1234);
    expect(saved.singleWhere((row) => row.status == 'cancelled').verified,isFalse);
  });
  test('version-one history migrates without inventing verification',() async {
    final file = '${temporary.path}/flashlink.db';
    final legacy = await databaseFactory.openDatabase(file,options:OpenDatabaseOptions(version:1,onCreate:(db,version) async {
      await db.execute('CREATE TABLE transfers (id TEXT PRIMARY KEY,file_name TEXT NOT NULL,bytes INTEGER NOT NULL,direction TEXT NOT NULL,completed_at INTEGER NOT NULL)');
      await db.insert('transfers',{'id':'old-fixture','file_name':'legacy.txt','bytes':123,'direction':'sent','completed_at':1000});
    }));
    await legacy.close();
    final rows = await repository.readAll();
    expect(rows.single.id,'old-fixture'); expect(rows.single.transferredBytes,123);
    expect(rows.single.status,'completed'); expect(rows.single.verified,isFalse);
  });
}
