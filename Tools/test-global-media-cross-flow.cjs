// Executes the production scheduling methods as JavaScript control flow and
// checks the storage existence query against SQLite. No Android runtime here.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const ui = fs.readFileSync(path.join(__dirname, '../TMessagesProj/src/main/java/org/telegram/ui/FilteredSearchView.java'), 'utf8');
const storage = fs.readFileSync(path.join(__dirname, '../TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java'), 'utf8');
function body(source, name) {
    const at = source.search(new RegExp('private void ' + name + '\\('));
    assert.ok(at >= 0, name);
    const open = source.indexOf('{', at);
    let depth = 0;
    for (let i = open; i < source.length; i++) {
        if (source[i] === '{') depth++;
        if (source[i] === '}' && --depth === 0) return source.slice(open + 1, i);
    }
    throw Error('Unclosed ' + name);
}

const prefetch = body(ui, 'maybePrefetchGlobalMedia').replace('int generation =', 'const generation =')
    .replace('int lastVisible =', 'const lastVisible =').replace(/\(\) ->/g, '() =>');
function runPrefetch(method, count, nearEnd = true) {
    const run = new Function('initialCount', 'nearEnd', `
        let globalMediaEmptyPageAutoLoads = initialCount, globalMediaWindowResumeScheduled = false;
        const globalMediaSearchGeneration = 1, requestIndex = 1, globalMediaFailurePaused = false,
            globalMediaPageRequestInFlight = false, globalMediaPendingPage = null,
            globalMediaProgressLoading = false, GLOBAL_MEDIA_EMPTY_PAGE_AUTO_LOAD_LIMIT = 5,
            GLOBAL_MEDIA_REQUEST_INTERVAL_MS = 200;
        const PhotoViewer = {getInstance: () => ({isVisible: () => false})};
        const messages = {isEmpty: () => false}, adapter = {getItemCount: () => 100};
        const layoutManager = {findLastVisibleItemPosition: () => nearEnd ? 99 : 0};
        let requested = 0;
        function isAttachedToWindow() {return true;}
        function hasMoreGlobalMediaPages() {return true;}
        function resumeGlobalMediaWindow() {requested++;}
        const AndroidUtilities = {runOnUIThread: fn => fn()};
        function maybePrefetchGlobalMedia() {${method}}
        maybePrefetchGlobalMedia();
        return {requested, count: globalMediaEmptyPageAutoLoads};
    `);
    return run(count, nearEnd);
}
for (let i = 0; i < 6; i++) {
    assert.deepEqual(runPrefetch(prefetch, 0), {requested: 1, count: 0}, 'Normal page ' + i + ' must not spend the sparse-page budget');
}
assert.equal(runPrefetch(prefetch, 5).requested, 0, 'Five consecutive pages without visible progress stop automatic fetches');
assert.equal(runPrefetch(prefetch, 0, false).requested, 0, 'Far-from-end pages do not prefetch');
const oldPrefetch = prefetch.replace('resumeGlobalMediaWindow();', 'globalMediaEmptyPageAutoLoads++; resumeGlobalMediaWindow();');
assert.throws(() => assert.deepEqual(runPrefetch(oldPrefetch, 0), {requested: 1, count: 0}),
    'Negative control: the former prefetch increment must fail the same assertion');

const progress = body(ui, 'applyGlobalMediaDatabasePage').match(/boolean visibleProgress = false;[\s\S]*?globalMediaEmptyPageAutoLoads\+\+;\s*\}/);
assert.ok(progress, 'Production visible-identity progress block');
const countProgress = new Function('before', 'after', 'newer', 'initial', `
    const previousVisibleIds = {contains: item => before.includes(item.id)};
    const messages = after.map(id => ({getId: () => id, getDialogId: () => 1}));
    function MessageHashId(id) {this.id = id;}
    const globalMediaPageNewer = newer;
    let globalMediaEmptyPageAutoLoads = initial;
    ${progress[0].replace('boolean visibleProgress', 'let visibleProgress')
        .replace(/for \(MessageObject visible : messages\)/g, 'for (const visible of messages)')}
    return globalMediaEmptyPageAutoLoads;
`);
assert.equal(countProgress([1, 2], [1, 2, 3], false, 4), 0, 'Visible append resets sparse streak');
assert.equal(countProgress([1, 2], [2, 3], false, 4), 0, 'Full-window eviction with one new visible ID is progress');
assert.equal(countProgress([1, 2], [1, 2], false, 4), 5, 'Invisible cache page increments sparse streak');
assert.equal(countProgress([1, 2], [1, 2], true, 4), 4, 'Newer seek does not spend older-page budget');

const flush = body(ui, 'flushGlobalMediaCacheDirty');
const runFlush = new Function('dirty', 'eof', 'busy', `
    let globalMediaCacheDirty = dirty, globalMediaPageRequestInFlight = busy;
    const globalMediaPendingPage = null, requestIndex = 1, globalMediaSearchGeneration = 1;
    const globalMediaOlderHasMore = !eof;
    let reads = 0;
    function requestGlobalMediaDatabasePage() {reads++; globalMediaPageRequestInFlight = true;}
    function flushGlobalMediaCacheDirty(generation) {${flush}}
    flushGlobalMediaCacheDirty(1);
    return {dirty: globalMediaCacheDirty, reads};
`);
assert.deepEqual(runFlush(false, true, false), {dirty: false, reads: 0}, 'Empty network round never rereads DB EOF');
assert.deepEqual(runFlush(true, true, false), {dirty: false, reads: 1}, 'New persisted row reopens EOF once');
assert.deepEqual(runFlush(true, true, true), {dirty: true, reads: 0}, 'Busy DB keeps dirty signal for its apply');
assert.deepEqual(runFlush(true, false, false), {dirty: false, reads: 0}, 'Normal cached pagination owns its next read');
const delayed = body(ui, 'onGlobalMediaDatabasePage').replace('MessagesController controller =', 'const controller =');
const runDelayed = new Function('callbackGeneration', 'callbackToken', `
    const requestIndex = 2, globalMediaSearchGeneration = 2, globalMediaPageRequestToken = 9;
    let globalMediaPageRequestInFlight = true, globalMediaOlderHasMore = true,
        globalMediaNewerHasMore = false, globalMediaPageHasMore = true,
        globalMediaPendingPage = null, globalMediaPendingPageGeneration = -1,
        globalMediaPendingPageToken = -1;
    const globalMediaPageNewer = false;
    const MessagesController = {getInstance: () => ({putUsers() {}, putChats() {}})};
    const globalMediaSearchAccount = 0;
    function scheduleGlobalMediaPageApply() {}
    function onGlobalMediaDatabasePage(generation, token, page) {${delayed}}
    onGlobalMediaDatabasePage(callbackGeneration, callbackToken,
        {failed: false, hasMore: false, users: [], chats: [], messages: []});
    return {busy: globalMediaPageRequestInFlight, hasMore: globalMediaOlderHasMore,
        pending: globalMediaPendingPage !== null};
`);
assert.deepEqual(runDelayed(1, 8), {busy: true, hasMore: true, pending: false}, 'Detached old generation cannot mutate a new session');
assert.deepEqual(runDelayed(2, 8), {busy: true, hasMore: true, pending: false}, 'Superseded DB token cannot mutate direction or EOF');
assert.deepEqual(runDelayed(2, 9), {busy: false, hasMore: false, pending: true}, 'Current DB callback owns EOF and queues apply');
const busyHandoff = new Function(`
    let globalMediaCacheDirty = true, globalMediaPageRequestInFlight = true;
    const globalMediaPendingPage = null, requestIndex = 1, globalMediaSearchGeneration = 1;
    const globalMediaOlderHasMore = false;
    let reads = 0;
    function requestGlobalMediaDatabasePage() {reads++; globalMediaPageRequestInFlight = true;}
    function flushGlobalMediaCacheDirty(generation) {${flush}}
    flushGlobalMediaCacheDirty(1); // Network completion while DB read is pending.
    globalMediaPageRequestInFlight = false; // DB callback/application completes.
    flushGlobalMediaCacheDirty(1);
    flushGlobalMediaCacheDirty(1);
    return {reads, dirty: globalMediaCacheDirty};
`);
assert.deepEqual(busyHandoff(), {reads: 1, dirty: false}, 'Busy completion consumes exactly one dirty reread after apply');

const batch = body(ui, 'startGlobalMediaDialogBatch')
    .replace(/new ArrayList<[^>]*>\(\)/g, '[]')
    .replace(/for \(GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches\)/g, 'for (const dialogSearch of globalMediaDialogSearches)')
    .replace(/int targetDate/g, 'const targetDate').replace(/int newestFloor/g, 'let newestFloor')
    .replace(/globalMediaDialogBatch\.isEmpty\(\)/g, '(globalMediaDialogBatch.length === 0)')
    .replace(/globalMediaDialogBatch\.add\(/g, 'globalMediaDialogBatch.push(');
const emptyBatch = new Function(`
    const generation = 1, requestIndex = 1, globalMediaSearchGeneration = 1;
    const globalMediaDialogSearches = [{progress: {initialized: true, catchingUp: false, historyEndReached: true}}];
    let globalMediaDialogBatch = null, globalMediaRequestsInFlight = 0, globalMediaCoverageWaiting = true;
    let globalMediaCacheDirty = false, globalMediaPageRequestInFlight = false, globalMediaPendingPage = null;
    let globalMediaBatchCursor = 0, globalMediaBatchCompleted = 0, isLoading = true, roundCalls = 0, dbReads = 0;
    const globalMediaHistoryPagesRemaining = 200;
    function getGlobalMediaCoverageTargetDate() {return 0;}
    function onGlobalMediaSyncRoundFinished() {roundCalls++;}
    function flushGlobalMediaCacheDirty() {if (globalMediaCacheDirty) dbReads++;}
    function updateGlobalMediaSyncLabel() {}
    function showGlobalMediaSyncProgress() {}
    function dispatchGlobalMediaDialogSearches() {throw Error('no peer');}
    function startGlobalMediaDialogBatch(generation) {${batch}}
    startGlobalMediaDialogBatch(1);
    return {roundCalls, dbReads, waiting: globalMediaCoverageWaiting};
`);
assert.deepEqual(emptyBatch(), {roundCalls: 0, dbReads: 0, waiting: false}, 'Empty network batch terminates without DB round-trip');
const oldBatch = batch.replace('globalMediaCoverageWaiting = false;', 'onGlobalMediaSyncRoundFinished(generation, true); globalMediaCoverageWaiting = false;');
assert.throws(() => assert.deepEqual(new Function(emptyBatch.toString().slice(emptyBatch.toString().indexOf('{') + 1, -1).replace(batch, oldBatch))(),
    {roundCalls: 0, dbReads: 0, waiting: false}), 'Negative control: old empty-batch completion fails');

const query = storage.match(/"(SELECT m\.mid FROM media_v4 m INNER JOIN messages_v2 g ON g\.uid = m\.uid AND g\.mid = m\.mid WHERE m\.uid = )"/);
assert.ok(query, 'Actual production existence query');
const db = new DatabaseSync(':memory:');
db.exec('CREATE TABLE media_v4(mid INTEGER, uid INTEGER, type INTEGER); CREATE TABLE messages_v2(mid INTEGER, uid INTEGER);');
db.exec('INSERT INTO media_v4 VALUES (1,7,0); INSERT INTO messages_v2 VALUES (1,7);');
const sql = query[1] + '7 AND m.type = 0 AND m.mid IN (1,2)';
assert.deepEqual(db.prepare(sql).all().map(row => row.mid), [1], 'Existing queryable ID is not a new row');
db.exec('INSERT INTO media_v4 VALUES (2,7,0); INSERT INTO messages_v2 VALUES (2,7);');
assert.deepEqual(db.prepare(sql).all().map(row => row.mid), [1,2], 'Only newly queryable row increments the dirty count');
db.close();
console.log('PASS: production prefetch, progress, dirty/EOF handoff, empty network batch, negative controls and SQLite row existence');
