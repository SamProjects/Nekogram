// Run with Node.js 24+: node Tools/test-global-media-sql.cjs
// Executes the SQL expressions from MessagesStorage.java against real SQLite.
// Does not validate Android lifecycle, rendering, or Telegram network responses.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const storagePath = path.join(__dirname, '../TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java');
const source = fs.readFileSync(storagePath, 'utf8');
const method = source.slice(source.indexOf('public void loadGlobalMediaPage('), source.indexOf('private TLRPC.Message deserializeGlobalMediaMessage('));
assert.ok(method.length > 0, 'Cannot locate production pagination method');
function expression(pattern, label) {
    const match = method.match(pattern);
    assert.ok(match, `Production ${label} expression changed; update the SQL test adapter`);
    return match[1];
}
const whereExpression = expression(/String where = ([\s\S]*?);/, 'where');
const cursorExpression = expression(/cursorWhere = newer([\s\S]*?);/, 'cursor');
const selectExpression = expression(/SQLiteCursor cursor = database\.queryFinalized\(([^;]+)\);/, 'select');
const makeWhere = new Function('ids', 'mediaType', 'minDate', 'maxDate', `return ${whereExpression};`);
const makeCursor = new Function('newer', 'cursorDate', 'cursorDialogId', 'cursorMessageId', `return newer${cursorExpression};`);
const makeSelect = new Function('where', 'cursorWhere', 'order', 'limit', `return ${selectExpression};`);
const boundsExpression = expression(/where \+= ([^;]+);/, 'snapshot bounds').replace(/bounds\.length\(\)/g, 'bounds.length');
const makeBounds = new Function('bounds', `return ${boundsExpression};`);

const db = new DatabaseSync(':memory:');
db.exec('CREATE TABLE media_v4(mid INTEGER, uid INTEGER, date INTEGER, type INTEGER, data BLOB, PRIMARY KEY(mid,uid,type)); CREATE INDEX uid_type_date_mid_idx_media_v4 ON media_v4(uid,type,date DESC,mid DESC);');
db.exec(source.match(/database\.executeFast\("(CREATE INDEX IF NOT EXISTS type_date_uid_mid_idx_media_v4[^"\n]+)"\)/)[1]);
const put = db.prepare('INSERT INTO media_v4 VALUES(?,?,?,?,NULL)');
const recent = 1790380800;
for (let i = 1; i <= 1300; i++) put.run(i, -101, recent - 1300 + i, 0);
for (let i = 1; i <= 140; i++) put.run(i, -202, 1695686400 + i, 0);
for (let i = 1; i <= 130; i++) {
    put.run(i, -303, recent + 1, 0);
    put.run(i, -404, recent + 1, 0);
}
put.run(1, -999, recent + 100, 0); // Out-of-scope dialog.
put.run(9999, -101, recent + 100, 1); // Non-photo/video.
put.run(-1, -101, recent + 100, 0); // Unsent message.
const dialogs = [-101, -202, -303, -404];
const key = row => `${row.uid}:${row.mid}`;
function page({ cursor = null, newer = false, min = 0, max = 0, limit = 100 } = {}) {
    const where = makeWhere(dialogs.join(','), 0, min, max);
    const seek = cursor ? makeCursor(newer, cursor.date, cursor.uid, cursor.mid) : '';
    const rows = db.prepare(makeSelect(where, seek, newer ? 'ASC' : 'DESC', limit)).all();
    const hasMore = rows.length > limit;
    return { rows: rows.slice(0, limit), hasMore };
}

const expected = db.prepare('SELECT uid,mid,date FROM media_v4 WHERE uid IN (-101,-202,-303,-404) AND type=0 AND mid>0 ORDER BY date DESC,uid DESC,mid DESC').all();
const queryPlan = db.prepare('EXPLAIN QUERY PLAN ' + makeSelect(makeWhere(dialogs.join(','), 0, 0, 0), '', 'DESC', 100)).all();
assert.ok(queryPlan.every(row => !row.detail.includes('TEMP B-TREE')), 'Global page should use the time index, not sort the entire media cache');
let loaded = [];
let cursor = null;
for (;;) {
    const result = page({ cursor });
    loaded.push(...result.rows);
    if (!result.hasMore) break;
    assert.ok(result.rows.length > 0, 'Non-advancing page');
    cursor = result.rows.at(-1);
}
assert.deepEqual(loaded.map(key), expected.map(key), 'Global seek pages must have no omissions or duplicates');
assert.equal(new Set(loaded.map(key)).size, loaded.length);
assert.ok(loaded.slice(0, 1560).every(row => row.date >= recent - 1300), 'Old channel must not precede recent media from busy group');

// A global synchronization frontier must hide the much older channel.
const covered = page({ min: recent - 200 });
assert.ok(covered.rows.every(row => row.date >= recent - 200));
assert.ok(covered.rows.every(row => row.uid !== -202));
const range = page({ min: recent - 150, max: recent - 100 });
assert.equal(range.rows.length, 51);

const snapshotRows = db.prepare(makeSelect(makeWhere(dialogs.join(','), 0, 0, 0) + makeBounds(' WHEN -101 THEN 1000 WHEN -303 THEN 50'), '', 'DESC', 100)).all();
assert.ok(snapshotRows.length > 0);
assert.ok(snapshotRows.every(row => row.uid === -101 && row.mid <= 1000 || row.uid === -303 && row.mid <= 50), 'Old snapshot must exclude new heads and unknown dialogs');
assert.equal(db.prepare(makeSelect(makeWhere(dialogs.join(','), 0, 0, 0) + makeBounds(''), '', 'DESC', 100)).all().length, 0);
const manyBounds = Array.from({ length: 1500 }, (_, i) => ` WHEN ${-10000 - i} THEN 10`).join('');
assert.equal(db.prepare(makeSelect(makeWhere(dialogs.join(','), 0, 0, 0) + makeBounds(manyBounds), '', 'DESC', 100)).all().length, 0, 'Thousands of dialogs must not exceed SQL expression depth');

// Read back a released window by seeking in the opposite direction.
const earlier = page({ cursor: expected[1000], newer: true });
assert.deepEqual(earlier.rows.slice().reverse().map(key), expected.slice(900, 1000).map(key));

// A new head message must not shift an existing older-page cursor.
const boundary = expected[99];
const beforeInsert = page({ cursor: boundary }).rows.map(key);
put.run(9998, -101, recent + 200, 0);
assert.deepEqual(page({ cursor: boundary }).rows.map(key), beforeInsert);

// Deleting the boundary itself must not invalidate the seek position.
db.prepare('DELETE FROM media_v4 WHERE uid=? AND mid=? AND type=0').run(boundary.uid, boundary.mid);
assert.deepEqual(page({ cursor: boundary }).rows.map(key), beforeInsert);

// Execute the production cache insert and transaction primitives. Existing local
// read flags and parameters must survive a background history cache fill.
const messageSchema = source.match(/database\.executeFast\("(CREATE TABLE messages_v2[^"\n]+)"\)/)[1];
db.exec(messageSchema);
const writer = source.slice(source.indexOf('public void putGlobalMediaSearchMessages('), source.indexOf('public void loadGlobalMediaPage('));
const messageInsert = writer.match(/messageStatement = database\.executeFast\("([^"\n]+)"\)/)[1];
const mediaInsert = writer.match(/mediaStatement = database\.executeFast\("([^"\n]+)"\)/)[1];
const insertMessage = db.prepare(messageInsert);
const insertMedia = db.prepare(mediaInsert);
const progressSchema = source.match(/database\.executeFast\("(CREATE TABLE IF NOT EXISTS global_media_search_state[^"\n]+)"\)/)[1];
db.exec(progressSchema);
const progressInsert = source.match(/database\.executeFast\("(REPLACE INTO global_media_search_state[^"\n]+)"\)/)[1];
const insertProgress = db.prepare(progressInsert);
const params = [50000, -101, 0, 0, recent, new Uint8Array([1]), 0, 0, 0, 1, 0, 0, 0, 101, 0, 99, 0];
db.exec('SAVEPOINT global_media_page');
insertMessage.run(...params);
insertMedia.run(50000, -101, recent, 0, new Uint8Array([1]));
db.exec('RELEASE global_media_page');
db.prepare('UPDATE messages_v2 SET read_state=3,custom_params=? WHERE uid=-101 AND mid=50000').run(new Uint8Array([7]));
insertMessage.run(...params);
const preserved = db.prepare('SELECT read_state,custom_params FROM messages_v2 WHERE uid=-101 AND mid=50000').get();
assert.equal(preserved.read_state, 3);
assert.deepEqual(Array.from(preserved.custom_params), [7]);

// Album context must include members beyond the 100-row page boundary, scoped
// to its dialog even when another dialog happens to use the same group id.
const groupExpression = expression(/SQLiteCursor groupCursor = database\.queryFinalized\(([^;]+)\);/, 'album lookup');
const groupSelect = new Function('mediaType', 'groupDialogId', 'groupedId', `return ${groupExpression};`);
const groupId = 9223372036854775000n;
for (let i = 51000; i < 51010; i++) {
    const row = params.slice();
    row[0] = i;
    row[15] = groupId;
    insertMessage.run(...row);
    insertMedia.run(i, -101, recent, 0, new Uint8Array([1]));
}
const otherGroup = params.slice();
otherGroup[0] = 51000;
otherGroup[1] = -202;
otherGroup[15] = groupId;
insertMessage.run(...otherGroup);
insertMedia.run(51000, -202, recent, 0, new Uint8Array([1]));
assert.equal(db.prepare(groupSelect(0, -101, groupId)).all().length, 10);
db.exec('SAVEPOINT global_media_page');
insertMessage.run(50001, ...params.slice(1));
insertMedia.run(50001, -101, recent, 0, new Uint8Array([1]));
insertProgress.run(-101, 1, 1, 50001, 50001, recent, Date.now(), 0, 0, 0, 0, 0);
db.exec('ROLLBACK TO global_media_page; RELEASE global_media_page');
assert.equal(db.prepare('SELECT COUNT(*) AS n FROM messages_v2 WHERE mid=50001').get().n, 0);
assert.equal(db.prepare('SELECT COUNT(*) AS n FROM media_v4 WHERE mid=50001').get().n, 0);
assert.equal(db.prepare('SELECT COUNT(*) AS n FROM global_media_search_state').get().n, 0);
// A restarted controller need not have any in-memory dialogs: discover persisted
// media peers directly, respecting archive membership and deleted dialogs.
db.exec('CREATE TABLE dialogs(did INTEGER PRIMARY KEY, date INTEGER, folder_id INTEGER)');
db.exec('CREATE TABLE global_media_preview_peers(uid INTEGER, folder_id INTEGER, PRIMARY KEY(uid, folder_id))');
db.exec('INSERT INTO dialogs VALUES(-101,300,0),(-202,200,1),(-303,100,0),(-404,50,0)');
for (const [uid, initialized, version] of [[-101,1,1],[-202,1,1],[-303,0,1],[-404,1,0],[-999,1,1]]) {
    insertProgress.run(uid, version, initialized, 1, 10, recent, 1, 0, 0, 0, 0, 0);
}
const bootstrapMethod = source.slice(source.indexOf('public void loadGlobalMediaCachedDialogs('), source.indexOf('private void ensureGlobalMediaSearchTables('));
const bootstrapExpr = bootstrapMethod.match(/cursor = database\.queryFinalized\(([^;]+)\);/)[1];
const bootstrapSelect = new Function('folderId', 'GlobalMediaSearchProgress', `return ${bootstrapExpr};`);
assert.deepEqual(db.prepare(bootstrapSelect(0, { VERSION: 1 })).all().map(x => x.uid), [-101]);
assert.deepEqual(db.prepare(bootstrapSelect(1, { VERSION: 1 })).all().map(x => x.uid), [-202]);
db.exec('INSERT INTO global_media_preview_peers VALUES(-505,0),(-606,1)');
assert.deepEqual(db.prepare(bootstrapSelect(0, { VERSION: 1 })).all().map(x => x.uid).sort(), [-101,-505]);
assert.deepEqual(db.prepare(bootstrapSelect(1, { VERSION: 1 })).all().map(x => x.uid).sort(), [-202,-606]);
// Network seek positions remain valid even when the corresponding media is
// deleted, ephemeral, filtered out, or the entire retained media set is empty.
const loader = source.slice(source.indexOf('public void loadGlobalMediaSearchProgress('), source.indexOf('public void saveGlobalMediaSearchProgress('));
const validation = loader.match(/private static boolean isValidGlobalMediaSearchProgress\([^)]*\)\s*\{\s*return ([\s\S]*?);/)[1];
const validProgress = new Function('state', `return ${validation};`);
const progressSelect = loader.match(/SQLiteCursor cursor = progressCursor = database.queryFinalized\(([^;]+)\);/)[1];
const loadProgressRows = new Function('ids', `return ${progressSelect};`);
const tailSql = loader.match(/SQLiteCursor boundaryCursor = database.queryFinalized\("([^"]+)"/)[1];
assert.ok(!loader.includes('valid = false'), 'Missing retained boundary must not invalidate network progress');
assert.ok(loader.includes('isValidGlobalMediaSearchProgress(state)'), 'Production loader must use the tested validator');
const checkpoint = { initialized:true, historyOffsetId:49000, headBoundaryId:60000, headFloorDate:recent,
    lastHeadSyncAt:1, historyEndReached:false, catchingUp:false, catchupOffsetId:0,
    catchupBoundaryId:0, pendingHeadBoundaryId:0 };
insertProgress.run(-707, 1, 1, checkpoint.historyOffsetId, checkpoint.headBoundaryId, recent, 1, 0, 0, 0, 0, 0);
const rawState = db.prepare(loadProgressRows('-707')).get();
assert.equal(rawState.history_offset_id, 49000);
assert.equal(validProgress(checkpoint), true, 'All-filtered/empty media retains legitimate progress');
assert.equal(db.prepare(tailSql).get(-707, checkpoint.historyOffsetId), undefined);
insertMedia.run(checkpoint.historyOffsetId, -707, recent, 1, new Uint8Array([1]));
assert.equal(db.prepare(tailSql).get(-707, checkpoint.historyOffsetId), undefined, 'Non-media boundary still has no display tail');
assert.equal(validProgress(checkpoint), true);
db.prepare('DELETE FROM media_v4 WHERE uid=-707').run();
assert.equal(validProgress(checkpoint), true, 'Deleting a boundary does not change network coverage');
assert.equal(db.prepare(tailSql).get(-101, 49000).mid, 50000, 'Missing boundary restores nearest retained album context');
assert.equal(validProgress({ ...checkpoint, historyOffsetId:-1 }), false);
assert.equal(validProgress({ ...checkpoint, headFloorDate:0 }), false);
assert.equal(validProgress({ ...checkpoint, headBoundaryId:100 }), false);
assert.equal(validProgress({ ...checkpoint, catchingUp:true }), false);
assert.equal(validProgress({ ...checkpoint, catchingUp:true, catchupOffsetId:55000, pendingHeadBoundaryId:61000 }), true);
assert.equal(validProgress({ ...checkpoint, historyEndReached:true, historyOffsetId:0, headBoundaryId:0, headFloorDate:0 }), true);
assert.ok(loader.includes('cursor.intValue(1) != GlobalMediaSearchProgress.VERSION'), 'Incompatible versions are not reused');
const invalidation = source.match(/database.executeFast\("(DELETE FROM global_media_search_state WHERE uid = )" \+ dialogId\)/)[1];
db.exec(invalidation + '-707');
assert.equal(db.prepare(loadProgressRows('-707')).get(), undefined, 'Explicit cache clearing invalidates checkpoint');
db.close();
console.log('PASS: production checkpoint query and validator, absent/non-media/deleted tails, empty cache, catch-up legality and explicit invalidation');
console.log('PASS: persistent media peer discovery without network, folder isolation and checkpoint version filtering');
console.log('PASS: production SQL, global ordering, equal-second ties, coverage cutoff, date range, bidirectional readback, insert/delete-stable cursors, cache rollback and local-state preservation');
