// Runs the scheduling method's Java control-flow subset as JS; no Android build.
// This validates peer selection only, not Telegram callbacks or Android lifecycle.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../TMessagesProj/src/main/java/org/telegram/ui/FilteredSearchView.java'), 'utf8');
const start = source.indexOf('private void startGlobalMediaDialogBatch(int generation) {');
const end = source.indexOf('private void dispatchGlobalMediaDialogSearches(int generation)', start);
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
    let finished = false, dispatched = false;
    function onGlobalMediaSyncRoundFinished() { finished = true; }
    function dispatchGlobalMediaDialogSearches() { dispatched = true; }
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
