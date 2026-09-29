// Production-source regression checks and comparator execution, not device profiling.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const source = fs.readFileSync(require('node:path').join(__dirname,
    '../TMessagesProj/src/main/java/org/telegram/ui/FilteredSearchView.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert.ok(start >= 0, signature);
    const end = source.indexOf('\n    private ', start + 1);
    return source.slice(start, end < 0 ? source.length : end);
}
const merge = method('private void scheduleGlobalMediaLiveMerge(');
assert.ok(source.includes('GLOBAL_MEDIA_LIVE_MERGE_INTERVAL_MS = 1500'));
assert.ok(merge.includes('rawMessages.isEmpty() ? 250 : GLOBAL_MEDIA_LIVE_MERGE_INTERVAL_MS'));
assert.ok(!merge.includes(', 150)'));
assert.ok(merge.includes('|| !isAttachedToWindow()) return'));
const loop = merge.slice(merge.indexOf('for (TLRPC.Message message : pending)'));
const allocation = loop.indexOf('new MessageObject(');
for (const rejection of ['globalMediaMessageIds.contains(id)', 'date < currentSearchMinDate',
    'compareGlobalMediaLiveMessage(message, initialOldest)', 'compareGlobalMediaLiveMessage(message, initialNewest)']) {
    assert.ok(loop.indexOf(rejection) < allocation, rejection + ' must precede allocation');
}
const context = method('private boolean mergeGlobalMediaGroupContext(');
assert.ok(context.indexOf('if (!known)') < context.indexOf('new MessageObject('),
    'Repeated album context must not allocate duplicate MessageObjects');
assert.ok(source.includes('if (!recoveredWindow) globalMediaLiveOverflow = true;'));
assert.ok(merge.includes('page -> queueGlobalMediaStoredPage(generation, page, true)'),
    'Recovery album expansion must not trigger a recursive recovery read');
const comparator = method('private int compareGlobalMediaLiveMessage(');
const compare = new Function('message', 'other', comparator.slice(comparator.indexOf('{') + 1,
    comparator.lastIndexOf('}')).replace(/int (date|dialog)/g, 'const $1')
    .replace(/Integer.compare|Long.compare/g, 'cmp')
    .replace(/^/, 'const cmp = (a,b) => a < b ? -1 : a > b ? 1 : 0;\n'));
const item = (date, dialog, id) => ({messageOwner: {date}, getDialogId: () => dialog, getId: () => id});
for (let date = 1; date <= 3; date++) for (let dialog = 1; dialog <= 3; dialog++) for (let id = 1; id <= 3; id++) {
    const m = {date, dialog_id: dialog, id};
    const expected = 2 - date || 2 - dialog || 2 - id;
    assert.equal(Math.sign(compare(m, item(2, 2, 2))), Math.sign(expected));
}
const detach = source.slice(source.indexOf('protected void onDetachedFromWindow()'),
    source.indexOf('public void didReceivedNotification('));
assert.ok(detach.includes('cancelRunOnUIThread(globalMediaLiveMergeRunnable)'));
assert.ok(source.includes('scheduleGlobalMediaLiveMerge(globalMediaSearchGeneration);'));
console.log('PASS: amortized live merges, allocation-before-rejection prevention, album dedup, overflow recovery isolation, date ordering and lifecycle pause/resume');
