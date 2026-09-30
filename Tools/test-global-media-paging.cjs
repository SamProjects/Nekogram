// Executes production local-paging guards with network work in progress.
// This is source/control-flow regression coverage, not Android device testing.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const source = fs.readFileSync(require('node:path').join(__dirname,
    '../TMessagesProj/src/main/java/org/telegram/ui/FilteredSearchView.java'), 'utf8');
function method(name) {
    const at = source.search(new RegExp('private (?:void|boolean) ' + name + '\\('));
    assert.ok(at >= 0, name);
    const end = source.indexOf('\n    private ', at + 1);
    const text = source.slice(at, end);
    return text.slice(text.indexOf('{') + 1, text.lastIndexOf('}'));
}
const resume = new Function('busy', 'pending', `
    const globalMediaSearchGeneration = 1, globalMediaPageRequestInFlight = busy,
        globalMediaPendingPage = pending, globalMediaProgressLoading = false,
        globalMediaCoverageWaiting = true, globalMediaDialogBatch = [{}],
        globalMediaRequestsInFlight = 10, globalMediaFailurePaused = false,
        globalMediaOlderHasMore = true, globalMediaProgressLoaded = true,
        GLOBAL_MEDIA_WINDOW_SIZE = 1000;
    const rawMessages = {size: () => 200, isEmpty: () => false};
    const PhotoViewer = {getInstance: () => ({isVisible: () => false})};
    let read = 0;
    function hasMoreGlobalMediaPages() {return true;}
    function requestGlobalMediaDatabasePage() {read++;}
    function isGlobalMediaCoverageComplete() {return false;}
    function startGlobalMediaDialogBatch() {throw Error('cached page must not await network');}
    ${method('resumeGlobalMediaWindow')}
    return read;
`);
assert.equal(resume(false, null), 1, 'Network round must not block cached older paging');
assert.equal(resume(true, null), undefined, 'Serialize opposite-direction DB reads');
assert.equal(resume(false, {}), undefined, 'Do not overwrite a pending page direction');
const newer = method('requestGlobalMediaNewerPage');
assert.ok(!newer.includes('globalMediaCoverageWaiting'), 'Cached newer paging must ignore network coverage');
assert.ok(newer.includes('globalMediaPendingPage != null'));
const finished = method('onGlobalMediaSyncRoundFinished');
assert.ok(!/globalMediaPage(?:Newer|Replace)\s*=/.test(finished), 'Network completion cannot mutate local request direction');
assert.ok(finished.includes('requestGlobalMediaDatabasePage(generation, false, rawMessages.isEmpty())'));
const callback = method('onGlobalMediaDatabasePage');
assert.ok(callback.indexOf('token != globalMediaPageRequestToken') < callback.indexOf('globalMediaPageRequestInFlight = false'));
assert.ok(callback.includes('generation != requestIndex') && callback.includes('generation != globalMediaSearchGeneration'));
const safeAppend = method('canAppendGlobalMediaPageWhileScrolling');
for (const guard of ['globalMediaPageNewer', 'globalMediaPageReplace', 'GLOBAL_MEDIA_WINDOW_SIZE',
    'groupMessages', 'message.grouped_id != 0', 'compareGlobalMediaLiveMessage']) {
    assert.ok(safeAppend.includes(guard), 'Safe append must guard ' + guard);
}
const inspectAppend = new Function('page', 'newer = false', 'replace = false', 'windowSize = 200', `
    const globalMediaPageNewer = newer, globalMediaPageReplace = replace,
        globalMediaPendingPage = page, GLOBAL_MEDIA_WINDOW_SIZE = 1000;
    const rawMessages = {size: () => windowSize, isEmpty: () => false,
        get: () => ({messageOwner: {date: 200}, getDialogId: () => 1, getId: () => 1})};
    function compareGlobalMediaLiveMessage(m, oldest) {return oldest.messageOwner.date - m.date;}
    return (() => {${safeAppend.replace(/for \(TLRPC.Message message : globalMediaPendingPage.(groupMessages|messages)\)/g,
        'for (const message of globalMediaPendingPage.$1)' ).replace(/\.messages.size\(\)/g, '.messages.length')
        .replace(/MessageObject oldest/g, 'const oldest')}})();
`);
const plain = {date: 100, grouped_id: 0};
const ordinaryPage = {messages: [plain], groupMessages: [plain]};
assert.equal(inspectAppend(ordinaryPage), true, 'Storage includes ordinary page rows in groupMessages');
assert.equal(inspectAppend({...ordinaryPage, groupMessages: [{...plain, grouped_id: 5}]}), false,
    'Real album context must wait for idle');
assert.equal(inspectAppend({messages: [{...plain, date: 300}], groupMessages: [plain]}), false,
    'Older paging may not prepend a newer row while dragging');
assert.equal(inspectAppend(ordinaryPage, true), false, 'Newer paging waits for idle');
assert.equal(inspectAppend(ordinaryPage, false, false, 1000), false, 'Window trim waits for idle');
const schedule = method('scheduleGlobalMediaPageApply');
assert.ok(schedule.includes('canAppendGlobalMediaPageWhileScrolling()'));
assert.ok(schedule.includes('applyGlobalMediaDatabasePage(generation, page, appendWhileScrolling)'));
assert.ok(!schedule.includes('applyGlobalMediaDatabasePage(generation, page);'));
const apply = method('applyGlobalMediaDatabasePage');
assert.ok(apply.includes('updateGlobalMediaResults(generation, previousItemCount, appendWhileScrolling)'));
assert.ok(apply.includes('messages.size() - previousVisibleCount < columnsCount * 6'));
assert.ok(apply.includes('globalMediaEmptyPageAutoLoads < GLOBAL_MEDIA_EMPTY_PAGE_AUTO_LOAD_LIMIT'));
assert.ok(apply.includes('rawMessages.size() < GLOBAL_MEDIA_WINDOW_SIZE'));
assert.ok(apply.includes('!globalMediaPageNewer && globalMediaOlderHasMore'));
assert.ok(schedule.includes('recyclerListView.isComputingLayout()'));
const update = source.slice(source.indexOf('private void updateGlobalMediaResults(int generation, int previousItemCount, boolean appendWhileScrolling)'),
    source.indexOf('boolean viewerAppend =', source.indexOf('private void updateGlobalMediaResults(int generation, int previousItemCount, boolean appendWhileScrolling)')));
assert.ok(update.includes('globalMediaScrollState != RecyclerView.SCROLL_STATE_IDLE && !appendWhileScrolling'));
const updateGuard = new Function('scrolling', 'append', 'state', `
    const appendWhileScrolling = append;
    const generation = 1, requestIndex = 1, globalMediaSearchGeneration = 1,
        globalMediaScrollState = scrolling ? 1 : 0, RecyclerView = {SCROLL_STATE_IDLE: 0};
    const PhotoViewer = {getInstance: () => ({isVisible: () => false})};
    function scheduleGlobalMediaResultsUpdate() {state.deferred = true;}
    ${update.slice(update.indexOf('{') + 1).replace(/globalMediaResultsDirty = true;/, '')}
`);
const safeState = {deferred: false};
updateGuard(true, true, safeState);
assert.equal(safeState.deferred, false, 'Safe tail append reaches visible-results update while scrolling');
const unsafeState = {deferred: false};
updateGuard(true, false, unsafeState);
assert.equal(unsafeState.deferred, true, 'Other changes retain scroll deferral');
console.log('PASS: network-independent cached paging, serialized directions, stale-token isolation, safe scrolling appends and bounded sparse-page refill');
