// Runs the scheduling method's Java control-flow subset as JS; no Android build.
// This validates peer selection only, not Telegram callbacks or Android lifecycle.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../TMessagesProj/src/main/java/org/telegram/ui/FilteredSearchView.java'), 'utf8');
const start = source.indexOf('private void startGlobalMediaDialogBatch(int generation) {');
const end = source.indexOf('\n    private ', start + 1);
assert.ok(start >= 0 && end > start);
let body = source.slice(start, end);
body = body.slice(body.indexOf('{') + 1, body.lastIndexOf('}'))
    .replace(/new ArrayList<>\(\)/g, '[]')
    .replace(/for \(GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches\)/g, 'for (const dialogSearch of globalMediaDialogSearches)')
    .replace(/int newestFloor/g, 'let newestFloor')
    .replace(/globalMediaDialogBatch\.isEmpty\(\)/g, '(globalMediaDialogBatch.length === 0)')
    .replace(/globalMediaDialogBatch\.add\(/g, 'globalMediaDialogBatch.push(');
const run = new Function('globalMediaDialogSearches', 'inFlight', `
    const generation = 1, requestIndex = 1, globalMediaSearchGeneration = 1;
    let globalMediaDialogBatch = null, globalMediaRequestsInFlight = inFlight;
    let globalMediaBatchCursor = 0, globalMediaBatchCompleted = 0, isLoading = false;
    const messages = { isEmpty: () => true }, emptyView = { showProgress() {} };
    let finished = false, dispatched = false;
    function onGlobalMediaSyncRoundFinished() { finished = true; }
    function dispatchGlobalMediaDialogSearches() { dispatched = true; }
    function showGlobalMediaSyncProgress() {}
    (() => { ${body} })();
    return { peers: globalMediaDialogBatch?.map(x => x.dialogId) ?? [], finished, dispatched };
`);
function peer(dialogId, floor, options = {}) {
    return { dialogId, progress: { initialized: true, catchingUp: false, historyEndReached: false, headFloorDate: floor, ...options } };
}
assert.deepEqual(run([peer(1, 300), peer(2, 200), peer(3, 10)], 0).peers, [1], 'Old channels must not advance ahead of the newest gap');
assert.deepEqual(run([peer(1, 300), peer(2, 300), peer(3, 10)], 0).peers, [1, 2], 'Equal frontiers must both advance');
assert.deepEqual(run([peer(1, 300), peer(2, 0, { initialized: false }), peer(3, 10, { catchingUp: true })], 0).peers, [2, 3], 'Unknown heads and catch-up gaps have priority over history');
assert.deepEqual(run([peer(1, 300, { historyEndReached: true }), peer(2, 200)], 0).peers, [2]);
assert.deepEqual(run([peer(1, 300, { historyEndReached: true })], 0), { peers: [], finished: true, dispatched: false });
assert.deepEqual(run([peer(1, 300)], 1), { peers: [], finished: false, dispatched: false }, 'Never overlap a request or storage write');
console.log('PASS: production scheduler control flow, newest-gap priority, head repair, tied frontiers, true-end and in-flight guards');

// Exercise production error handling with unavailable peers between healthy peers.
// Android widgets and network I/O are stubbed; checkpoints must remain unchanged.
function methodBody(name) {
    const methodStart = source.search(new RegExp('private (?:static )?(?:boolean|void|int) ' + name + '\\('));
    assert.ok(methodStart >= 0, name);
    const methodEnd = source.indexOf('\n    private ', methodStart + 1);
    const method = source.slice(methodStart, methodEnd < 0 ? source.length : methodEnd);
    return method.slice(method.indexOf('{') + 1, method.lastIndexOf('}'));
}
const isUnavailable = new Function('error', methodBody('isGlobalMediaPeerUnavailable')
    .replace(/"([A-Z_]+)"\.equals\(error\)/g, '(error === "$1")'));
for (const error of ['CHANNEL_PRIVATE', 'CHANNEL_INVALID', 'CHAT_ADMIN_REQUIRED', 'CHAT_ID_INVALID',
    'INPUT_USER_DEACTIVATED', 'PEER_ID_INVALID', 'PEER_ID_NOT_SUPPORTED', 'USER_ID_INVALID']) {
    assert.equal(isUnavailable(error), true, error);
}
for (const error of [null, 'TIMEOUT', 'FLOOD_WAIT_60', 'FLOOD_PREMIUM_WAIT_30', 'AUTH_KEY_UNREGISTERED',
    'INTERNAL', 'INPUT_FILTER_INVALID', 'SEARCH_QUERY_EMPTY', 'LOCAL_MEDIA_WRITE_FAILED']) {
    assert.equal(isUnavailable(error), false, error);
}
function list(items = []) {
    return { items: [...items], add(x) { this.items.push(x); }, clear() { this.items.length = 0; },
        remove(x) { const i = this.items.indexOf(x); if (i >= 0) this.items.splice(i, 1); },
        size() { return this.items.length; }, isEmpty() { return this.items.length === 0; },
        [Symbol.iterator]() { return this.items[Symbol.iterator](); } };
}
const exerciseSkip = new Function('peers', 'list', `
    const globalMediaDialogSearches = list(peers), globalMediaUnavailableDialogs = list();
    const globalMediaSnapshotHeadBoundaries = { remove() {} };
    const globalMediaSnapshotFloors = { remove() {} }, globalMediaSnapshotHistoryEnded = { remove() {} };
    let globalMediaRequestsInFlight = 1, globalMediaBatchCompleted = 0;
    let globalMediaDialogBatch = list(peers);
    const outcomes = [];
    function updateGlobalMediaTotalCount() {}
    function onGlobalMediaSyncRoundFinished(generation, success) { outcomes.push(success); }
    function dispatchGlobalMediaDialogSearches() { outcomes.push('next'); }
    function showGlobalMediaSyncProgress() {}
    function finishGlobalMediaNetworkPage(generation, success) { ${methodBody('finishGlobalMediaNetworkPage')} }
    function skipUnavailableGlobalMediaDialog(generation, dialogSearch) { ${methodBody('skipUnavailableGlobalMediaDialog')} }
    skipUnavailableGlobalMediaDialog(1, peers[0]);
    for (let i = 1; i < peers.length; i++) finishGlobalMediaNetworkPage(1, true);
    return { active: globalMediaDialogSearches.items, skipped: globalMediaUnavailableDialogs.items, outcomes };
`);
const blocked = peer(1, 0, { initialized: false });
const stateBefore = JSON.stringify(blocked.progress);
const healthy = peer(2, 300);
const skipped = exerciseSkip([blocked, healthy], list);
assert.deepEqual(skipped.active, [healthy]);
assert.deepEqual(skipped.skipped, [blocked]);
assert.deepEqual(skipped.outcomes, ['next', true], 'One inaccessible peer must not fail the entire round');
assert.equal(JSON.stringify(blocked.progress), stateBefore, 'Skipping must not certify coverage or history end');
assert.deepEqual(exerciseSkip([blocked], list).outcomes, [true], 'All inaccessible peers must terminate the round');

const exerciseRetry = new Function('peers', 'list', `
    let globalMediaLastErrorCode = 'MEDIA_CHATS_UNAVAILABLE', globalMediaUnavailableNoticeShown = true;
    let globalMediaSnapshotActive = true, globalMediaSearchGeneration = 1;
    const globalMediaUnavailableDialogs = list(peers), globalMediaDialogSearches = list();
    const messages = list();
    let request;
    function requestGlobalMediaDatabasePage(...args) { request = args; }
    (() => { ${methodBody('retryGlobalMediaSearch')
        .replace(/for \(GlobalMediaDialogSearch dialogSearch : globalMediaUnavailableDialogs\)/g, 'for (const dialogSearch of globalMediaUnavailableDialogs)')
        .replace(/MessagesStorage\.GlobalMediaSearchProgress progress/g, 'const progress')} })();
    return { active: globalMediaDialogSearches.items, skipped: globalMediaUnavailableDialogs.items,
        snapshot: globalMediaSnapshotActive, error: globalMediaLastErrorCode, request };
`);
const recovered = peer(3, 200, { headBoundaryId: 50, historyOffsetId: 10, historyEndReached: true });
const retried = exerciseRetry([recovered], list);
assert.deepEqual(retried.active, [recovered]);
assert.deepEqual(retried.skipped, []);
assert.equal(retried.snapshot, false);
assert.equal(retried.error, null);
assert.deepEqual(retried.request, [1, false, true], 'An empty retry must not depend on scroll or hasMore');
assert.equal(recovered.progress.catchingUp, true);
assert.equal(recovered.progress.catchupBoundaryId, 50);
assert.equal(recovered.progress.historyOffsetId, 10, 'Retry must preserve the saved history cursor');
console.log('PASS: inaccessible-peer isolation, strict error classification, unchanged checkpoints and explicit retry');

const alignWindow = new Function('windowStart', 'rawMessages', 'messages', 'columnsCount', `
    function key(id, dialogId) { return dialogId + ':' + id; }
    function findGlobalMediaMessageIndex(dialogId, id) {
        return rawMessages.findIndex(m => m.getId() === id && m.getDialogId() === dialogId);
    }
    ${methodBody('alignGlobalMediaWindowStart')
        .replace(/HashSet<MessageHashId> removed = new HashSet<>\(\)/, 'const removed = new Set()')
        .replace(/new MessageHashId\(/g, 'key(')
        .replace(/removed\.contains\(/g, 'removed.has(')
        .replace(/for \(MessageObject message : messages\)/, 'for (const message of messages)')
        .replace(/MessageObject (message|rowStart)/g, 'const $1')
        .replace(/\bint (i|removedVisible|partialRow|rawIndex)\b/g, 'let $1')
        .replace(/messages\.isEmpty\(\)/g, '(messages.length === 0)')
        .replace(/(rawMessages|messages)\.get\(([^)]+)\)/g, '$1[$2]')}
`);
const gridRaw = Array.from({ length: 120 }, (_, i) => ({ getId: () => i, getDialogId: () => 1 }));
for (const columns of [3, 6]) {
    for (const visible of [gridRaw, gridRaw.filter((_, i) => i % 2 === 0)]) {
        for (const cut of [1, 10, 31, 100]) {
            const aligned = alignWindow(cut, gridRaw, visible, columns);
            const removedCount = visible.filter(m => gridRaw.indexOf(m) < aligned).length;
            assert.ok(aligned <= cut);
            assert.equal(removedCount % columns, 0, 'Window eviction must preserve retained thumbnail columns');
        }
    }
}
console.log('PASS: complete visible-row eviction with filtered and unfiltered 3/6-column grids');
