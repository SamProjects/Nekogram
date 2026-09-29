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
    .replace(/int targetDate/g, 'const targetDate')
    .replace(/globalMediaDialogBatch\.isEmpty\(\)/g, '(globalMediaDialogBatch.length === 0)')
    .replace(/globalMediaDialogBatch\.add\(/g, 'globalMediaDialogBatch.push(');
const run = new Function('globalMediaDialogSearches', 'inFlight', 'targetDate = 0', 'budget = 200', `
    const generation = 1, requestIndex = 1, globalMediaSearchGeneration = 1;
    let globalMediaDialogBatch = null, globalMediaRequestsInFlight = inFlight;
    let globalMediaBatchCursor = 0, globalMediaBatchCompleted = 0, isLoading = false;
    const globalMediaHistoryPagesRemaining = budget;
    function getGlobalMediaCoverageTargetDate() { return targetDate; }
    const messages = { isEmpty: () => true }, emptyView = { showProgress() {} };
    let finished = false, dispatched = false;
    function onGlobalMediaSyncRoundFinished() { finished = true; }
    function dispatchGlobalMediaDialogSearches() { dispatched = true; }
    function showGlobalMediaSyncProgress() {}
    function updateGlobalMediaSyncLabel() {}
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
assert.deepEqual(run([peer(1, 300), peer(2, 200)], 0, 250).peers, [1], 'Only fill peers that have not covered the displayed date range');
assert.deepEqual(run([peer(1, 300)], 0, 300).peers, [1], 'A tied boundary second still needs coverage');
assert.equal(run([peer(1, 300)], 0, 400).finished, true, 'Stop once dynamic window target is covered');
assert.equal(run([peer(1, 300)], 0, 200, 0).finished, true, 'History work must stop at its budget');
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
    function updateGlobalMediaSyncLabel() {}
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

const snapshot = new Function('globalMediaDialogSearches', `
    const globalMediaSnapshotHeadBoundaries = new Map(), globalMediaSnapshotFloors = new Map();
    const globalMediaSnapshotHistoryEnded = new Set(), globalMediaSnapshotPendingGroupKeys = new Set();
    let globalMediaSnapshotActive = false;
    (() => { ${methodBody('refreshGlobalMediaSnapshotBounds')
        .replace(/boolean canUseSnapshot/g, 'let canUseSnapshot')
        .replace(/canUseSnapshot \|=/g, 'canUseSnapshot ||=')
        .replace(/for \(GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches\)/g, 'for (const dialogSearch of globalMediaDialogSearches)')
        .replace(/MessagesStorage\.GlobalMediaSearchProgress progress/g, 'const progress')
        .replace(/\.put\(/g, '.set(')} })();
    return { active: globalMediaSnapshotActive, ids: [...globalMediaSnapshotHeadBoundaries.keys()] };
`);
const cachedPeer = peer(1, 200, { headBoundaryId: 80, pendingGroupId: 0 });
cachedPeer.progressLoaded = true;
const unknownPeer = peer(2, 0, { initialized: false, headBoundaryId: 0, pendingGroupId: 0 });
unknownPeer.progressLoaded = true;
assert.deepEqual(snapshot([cachedPeer, unknownPeer]), { active: true, ids: [1] }, 'One unknown peer must not block a valid cached snapshot');
assert.deepEqual(snapshot([unknownPeer]), { active: false, ids: [] });
const emptyPeer = peer(3, 0, { historyEndReached: true, headBoundaryId: 0, pendingGroupId: 0 });
emptyPeer.progressLoaded = true;
assert.equal(snapshot([emptyPeer, unknownPeer]).active, false, 'Empty checkpoints must not freeze the first actual media sync');
const cacheReady = new Function('cached', 'dialogsReady', 'visible', `
    let globalMediaProgressLoaded = false, globalMediaFailurePaused = true;
    let globalMediaSnapshotActive = cached, globalMediaSearchAccount = 0, globalMediaSearchFolder = 0;
    const rawMessages = { isEmpty: () => !visible }, calls = [], generation = 1;
    const MessagesController = { getInstance: () => ({ isServerDialogsEndReached: () => dialogsReady }) };
    function refreshGlobalMediaSnapshotBounds() {}
    function requestGlobalMediaDatabasePage() { calls.push('cache'); }
    function startGlobalMediaHeadRefresh() { calls.push('refresh'); }
    function continueGlobalMediaSearch() { calls.push('discover'); }
    function startGlobalMediaDialogBatch() { calls.push('batch'); }
    (() => { ${methodBody('onGlobalMediaProgressReady').replace(/boolean dialogsReady/g, 'const dialogsReady')} })();
    return calls;
`);
assert.deepEqual(cacheReady(true, false, false), ['cache', 'discover'], 'Cache must display before server dialog discovery completes');
assert.deepEqual(cacheReady(false, false, false), ['cache', 'discover', 'batch'], 'Without cache, read available rows and start scanning known peers');
assert.deepEqual(cacheReady(true, true, true), ['refresh'], 'Background discovery must not replace visible cached rows');
console.log('PASS: cache-first startup and partial valid snapshots without visible-list replacement');

const liveStart = source.indexOf('private void queueGlobalMediaLiveMessages(');
const liveEnd = source.indexOf('\n    private int compareGlobalMediaLiveMessage', liveStart + 1);
const liveSource = source.slice(liveStart, liveEnd);
assert.ok(liveSource.includes('globalMediaPendingLiveMessages.size() > GLOBAL_MEDIA_WINDOW_SIZE'), 'Pending network rows must be bounded');
assert.ok(liveSource.includes('globalMediaScrollState != RecyclerView.SCROLL_STATE_IDLE')
    && liveSource.includes('PhotoViewer.getInstance().isVisible()'), 'Merge must wait while dragging or viewing a photo');
assert.ok(!/globalMediaOlderCursorDate\s*=(?!=)/.test(liveSource), 'Sparse live results must never advance the older DB cursor');
assert.ok(source.includes('if (!viewerAppend && (!sameVisibleIds'), 'Duplicate batches must not redraw the media grid');
assert.ok(source.includes('storage.loadGlobalMediaStoredMessages(result.messages'), 'Per-peer updates must read persisted rows before display');
console.log('PASS: bounded and deferred DB-backed live merge, unchanged-grid suppression and stable seek cursor');

// Execute the production coverage predicate, including the previously wrong
// valid-snapshot shortcut: the official preview/cache can have very old holes.
const coverage = new Function('peers', 'target', `
    const globalMediaProgressLoaded = true, globalMediaDialogSearches = peers;
    const globalMediaSnapshotActive = true;
    function getGlobalMediaCoverageTargetDate() { return target; }
    ${methodBody('isGlobalMediaCoverageComplete')
        .replace(/int targetDate/g, 'const targetDate')
        .replace(/for \(GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches\)/g, 'for (const dialogSearch of globalMediaDialogSearches)')
        .replace(/MessagesStorage\.GlobalMediaSearchProgress progress/g, 'const progress')}
`);
function coveredPeer(id, floor, extra = {}) {
    return { ...peer(id, floor, extra), progressLoaded: true };
}
assert.equal(coverage([coveredPeer(1, 300), coveredPeer(2, 100)], 200), false,
    'A valid partial snapshot must not certify a preview date gap as covered');
assert.equal(coverage([coveredPeer(1, 200)], 200), false, 'Scan through tied timestamps and boundary albums');
assert.equal(coverage([coveredPeer(1, 199)], 200), true);
assert.equal(coverage([coveredPeer(1, 0, { historyEndReached: true })], 200), true);
assert.equal(coverage([coveredPeer(1, 100, { catchingUp: true })], 200), false);
assert.equal(coverage([coveredPeer(1, 100, { initialized: false })], 200), false);
assert.equal(coverage([coveredPeer(1, 300)], 400), true,
    'New rows shrinking the bounded window must stop unnecessary older history work');

const discoverySource = methodBody('continueGlobalMediaSearch');
const staleLoop = discoverySource.slice(discoverySource.indexOf('long now ='),
    discoverySource.indexOf('onGlobalMediaProgressReady(generation);'));
const recheckHeads = new Function('peers', 'checked', 'now', `
    const globalMediaDialogSearches = peers, globalMediaHeadCheckedDialogs = checked;
    const GLOBAL_MEDIA_HEAD_STALENESS_MS = 300000;
    let globalMediaHeadRefreshPending = false;
    ${staleLoop.replace(/long now = System.currentTimeMillis\(\);/, '')
        .replace(/for \(GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches\)/g, 'for (const dialogSearch of globalMediaDialogSearches)')
        .replace(/MessagesStorage\.GlobalMediaSearchProgress progress/g, 'const progress')
        .replace(/boolean stale/g, 'const stale')
        .replace(/globalMediaHeadRefreshPending \|=/g, 'globalMediaHeadRefreshPending ||=')
        .replace(/globalMediaHeadCheckedDialogs.add\(dialogSearch.dialogId\)/g,
            '(!checked.has(dialogSearch.dialogId) && (checked.add(dialogSearch.dialogId), true))')}
    return globalMediaHeadRefreshPending;
`);
const longRunning = coveredPeer(1, 100, { lastHeadSyncAt: 1000 });
assert.equal(recheckHeads([longRunning], new Set([1]), 601001), false,
    'Dialog rediscovery after five minutes must not restart a head already checked in this search');
assert.equal(recheckHeads([longRunning], new Set(), 601001), true,
    'A newly discovered stale peer still needs exactly one head refresh');
console.log('PASS: real coverage predicate, preview gaps, tied timestamps, dynamic target and one head refresh per search');
// Execute the production actual-insertion branch twice with the same key.
const counterLiveSource = methodBody('scheduleGlobalMediaLiveMerge');
const insertion = counterLiveSource.match(/if \(globalMediaMessageIds.add\(id\)\) \{([\s\S]*?)\n\s*\}/)[1];
const countLive = new Function(`
    const globalMediaMessageIds = new Set(), addedIds = new Set(), rawMessages = [];
    let globalMediaLiveAddedCount = 0, changed = false;
    const item = { setQuery() {} }, id = 'same-row';
    for (let attempt = 0; attempt < 2; attempt++) {
        if (!globalMediaMessageIds.has(id)) {
            globalMediaMessageIds.add(id);
            ${insertion.replace(/MessageObject item = new MessageObject\([^;]+;/g, '')
                .replace(/rawMessages.add\(item\)/g, 'rawMessages.push(item)')}
        }
    }
    return [globalMediaLiveAddedCount, rawMessages.length, addedIds.size];
`);
assert.deepEqual(countLive(), [1,1,1], 'Repeated retained rows must not inflate actual live additions');
assert.ok(counterLiveSource.includes('addedIds.remove(new MessageHashId(visible.getId(), visible.getDialogId()))'),
    'Visible additions consume each actual added key only once');
assert.ok(source.includes('globalMediaStoredCount += page.messages.size()'),
    'Storage count must exclude extra album context rows');
console.log('PASS: actual live insertion counters do not duplicate retained rows; stored statistics exclude album context');
