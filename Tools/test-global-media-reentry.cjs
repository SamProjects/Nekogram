// Execute production cache-first and short-lived budget logic without Android.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const source = fs.readFileSync(require('node:path').join(__dirname,
    '../TMessagesProj/src/main/java/org/telegram/ui/FilteredSearchView.java'), 'utf8');
function body(name) {
    const start = source.indexOf(`private void ${name}(`);
    assert.ok(start >= 0);
    const end = source.indexOf('\n    private ', start + 1);
    const method = source.slice(start, end);
    return method.slice(method.indexOf('{') + 1, method.lastIndexOf('}'));
}
const restoreBody = body('restoreGlobalMediaHistoryBudget')
    .replace(/SharedPreferences preferences/g, 'const preferences')
    .replace(/String key/g, 'const key').replace(/long savedAt/g, 'const savedAt')
    .replace(/long age/g, 'const age').replace(/System.currentTimeMillis\(\)/g, 'now');
function restore(savedAt, remaining, completed, now) {
    const run = new Function('inputSavedAt', 'remaining', 'completed', 'now', `
        let globalMediaHistoryPagesRemaining = 200, globalMediaHistoryPagesCompleted = 0;
        const GLOBAL_MEDIA_HEAD_STALENESS_MS = 300000, MEDIA_FILTER_PREFERENCES = '', Context = { MODE_PRIVATE: 0 };
        function globalMediaHistoryBudgetKey() { return 'budget'; }
        const ApplicationLoader = { applicationContext: { getSharedPreferences() {
            return { getLong() { return inputSavedAt; }, getInt(key) { return key.endsWith('_completed') ? completed : remaining; } };
        } } };
        ${restoreBody}
        return [globalMediaHistoryPagesRemaining, globalMediaHistoryPagesCompleted];
    `);
    return run(savedAt, remaining, completed, now);
}
assert.deepEqual(restore(10000, 15, 185, 11000), [15, 185], 'Immediate reentry retains actual used budget');
assert.deepEqual(restore(10000, 0, 200, 11000), [0, 200], 'Reentry must not silently grant another 200 pages');
assert.deepEqual(restore(10000, 0, 200, 310000), [200, 0], 'Stale session receives normal automatic allowance');
assert.deepEqual(restore(0, 0, 0, 11000), [200, 0], 'New session continues initial automatic scan');
assert.deepEqual(restore(12000, 0, 200, 11000), [200, 0], 'Clock rollback must not extend saved budget indefinitely');
const resumeBody = body('resumeGlobalMediaWindow')
    .replace(/PhotoViewer.getInstance\(\).isVisible\(\)/g, 'false');
const resume = new Function('cached', 'coverage', 'empty', `
    const globalMediaSearchGeneration = 1, globalMediaPageRequestInFlight = false,
        globalMediaPendingPage = null, globalMediaProgressLoading = false,
        globalMediaCoverageWaiting = false, globalMediaHeadRefreshRunning = false,
        globalMediaDialogBatch = null, globalMediaFailurePaused = false,
        globalMediaOlderHasMore = cached, globalMediaProgressLoaded = true,
        GLOBAL_MEDIA_WINDOW_SIZE = 1000;
    const rawMessages = {size: () => 0, isEmpty: () => empty};
    let action = '';
    function hasMoreGlobalMediaPages() { return true; }
    function isGlobalMediaCoverageComplete() { return coverage; }
    function requestGlobalMediaDatabasePage() { action = 'database'; }
    function startGlobalMediaDialogBatch() { action = 'network'; }
    ${resumeBody.replace(/globalMediaCoverageWaiting = true;/g, '')}
    return action;
`);
assert.equal(resume(true, false, false), 'database', 'Cached next page must precede coverage network');
assert.equal(resume(false, false, false), 'network', 'Exhausted cache still automatically fills coverage gaps');
assert.equal(resume(false, false, true), 'database', 'Initial empty window first checks database');
assert.match(source, /UserConfig.getInstance\(globalMediaSearchAccount\).getClientUserId\(\)/, 'Budget isolates reused account slots');
assert.match(body('saveGlobalMediaHistoryBudget'), /putLong\(key \+ "_at", System.currentTimeMillis\(\)\)/);
assert.doesNotMatch(body('restoreGlobalMediaHistoryBudget'), /\.edit\(/, 'Opening cannot slide budget expiry');
assert.doesNotMatch(body('requestGlobalMediaDatabasePage'), /globalMediaCoverageWaiting\s*=|startGlobalMediaDialogBatch/, 'DB request must not take ownership of network coverage');
assert.match(body('onGlobalMediaSyncRoundFinished'), /if \(!isGlobalMediaCoverageComplete\(0\) && globalMediaHistoryPagesRemaining > 0\)/, 'Background coverage repair must continue even when cached pages remain');
const coverageDecision = body('onGlobalMediaSyncRoundFinished').match(/if \(!isGlobalMediaCoverageComplete\(0\) && globalMediaHistoryPagesRemaining > 0\) \{[\s\S]*?\n        \}/)[0];
const repair = new Function('coverage', 'budget', 'cachedHasMore', `
    const generation = 1, globalMediaHistoryPagesRemaining = budget;
    let globalMediaOlderHasMore = cachedHasMore;
    const rawMessages = { isEmpty: () => false };
    let globalMediaCoverageWaiting = false, repaired = false;
    function isGlobalMediaCoverageComplete() { return coverage; }
    function startGlobalMediaDialogBatch() { repaired = true; }
    function requestGlobalMediaDatabasePage() {}
    function flushGlobalMediaCacheDirty() {}
    (() => { ${coverageDecision} })();
    return repaired;
`);
assert.equal(repair(false, 50, true), true, 'Cached content cannot postpone background gap repair');
assert.equal(repair(false, 0, true), false, 'Restored exhausted budget pauses instead of silently renewing');
assert.equal(repair(true, 50, true), false, 'Complete coverage must not scan history unnecessarily');
const network = body('dispatchGlobalMediaDialogSearches');
const putAt = network.indexOf('storage.putGlobalMediaSearchMessagesWithCount(');
assert.ok(putAt > 0);
const storedCallback = network.slice(putAt);
assert.match(storedCallback, /generation != requestIndex[\s\S]*return;[\s\S]*saveGlobalMediaHistoryBudget\(\)/, 'Delayed old-UI storage completion must not mutate new-session budget');
assert.match(network, /generation != requestIndex[\s\S]*return;[\s\S]*MessagesStorage.GlobalMediaSearchProgress after/, 'Old network generation must not overwrite a newer checkpoint');
console.log('PASS: production immediate-reentry budget, expiry, fresh sessions, cache-first resume and account identity isolation');
