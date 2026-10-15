#!/usr/bin/env python3
"""Compile and exercise the production cache fallback block without Android.

Requires Python 3 and JDK 21. The clock, UI queue, and network loader are stubs;
the reload decision, request arguments, and early return come from production.
Pass an alternate MessagesController.java path to check a negative control.
"""
import pathlib
import re
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
source_path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else (
    root / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
source = source_path.read_text()
method = source[source.index("    public void processLoadedMessages("):]
start = method.index("        // Storage may discard") if "        // Storage may discard" in method else method.index(
    "        if (mode == ChatActivity.MODE_SCHEDULED)")
block = method[start:method.index("        int size = messagesRes.messages.size();")]
chat = (root / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").read_text()
constants = "\n".join(re.findall(r"public static final int MODE_\w+ = \d+;", chat))

harness = r'''
import java.util.*;
public class MessageJumpCacheTest {
    static class ChatActivity { CONSTANTS }
    static class TLRPC {
        static final int MESSAGE_FLAG_EDITED = 1 << 15;
        static class Message {
            int id, edit_date, date, flags;
            long peer_id = -2553010221L;
            boolean pinned;
            Message(int id) { this.id = id; }
        }
    }
    static class Messages {
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
    }
    static class SystemClock { static long elapsedRealtime() { return 120000; } }
    static class DialogObject {
        static boolean encrypted;
        static boolean isEncryptedDialog(long id) { return encrypted; }
        static long getPeerDialogId(long id) { return id; }
    }
    static class MediaDataController {
        static long calcHash(long hash, long value) { return hash * 31 + value; }
    }
    static class AndroidUtilities {
        static void runOnUIThread(Runnable callback) { callback.run(); }
    }
    static class Times extends HashMap<Long, Long> {
        long get(long key, long fallback) { return getOrDefault(key, fallback); }
    }
    final Times lastScheduledServerQueryTime = new Times(),
        lastQuickReplyServerQueryTime = new Times(), lastWelcomeMessagesServerQueryTime = new Times(),
        lastSavedServerQueryTime = new Times(), lastServerQueryTime = new Times();
    static final int LOAD_AROUND_MESSAGE = 3, LOAD_FROM_UNREAD = 2;
    int requests, delivered;
    Object[] request;
    void loadMessagesInternal(Object... args) { requests++; request = args; }
    void process(boolean isCache, boolean needProcess, int resCount, int mode,
                 int load_type, int max_id, int offset_date, boolean isTopic, int... ids) {
        final long dialogId = -2553010221L, mergeDialogId = 0, threadMessageId = isTopic ? 77 : 0;
        final int count = 20, classGuid = 123, last_message_id = 722, loadIndex = 9,
            first_unread = 0, unread_count = 0, last_date = 0, mentionsCount = 0;
        final boolean queryFromServer = false;
        final Object loaderLogger = null;
        final boolean isInitialLoading = offset_date == 0 && max_id == 0;
        final boolean reload;
        Messages messagesRes = new Messages();
        for (int id : ids) messagesRes.messages.add(new TLRPC.Message(id));
        PRODUCTION
        delivered++;
    }
    static int checked;
    static void check(String name, boolean cache, boolean process, int count, int mode,
                      int type, int target, boolean topic, boolean encrypted,
                      int expectedRequests, int expectedDelivered, int... ids) {
        MessageJumpCacheTest test = new MessageJumpCacheTest();
        DialogObject.encrypted = encrypted;
        test.process(cache, process, count, mode, type, target, 0, topic, ids);
        if (test.requests != expectedRequests || test.delivered != expectedDelivered) {
            throw new AssertionError(name + ": requests=" + test.requests + ", delivered=" + test.delivered);
        }
        if (test.requests > 0) {
            Object[] req = test.request;
            if (!req[0].equals(-2553010221L) || !req[4].equals(target) || !req[6].equals(false)
                    || !req[8].equals(123) || !req[9].equals(type) || !req[11].equals(mode)
                    || !req[12].equals(topic ? 77L : 0L) || !req[13].equals(9)) {
                throw new AssertionError(name + ": fallback lost request context");
            }
        }
        checked++;
    }
    public static void main(String[] args) {
        // A storage query can read rows, then clear an unusable window.
        check("discarded cache window", true, true, 20, 0, 3, 406, false, false, 1, 0);
        check("single discarded row", true, true, 1, 0, 3, 406, false, false, 1, 0);
        check("neighbors without target", true, true, 3, 0, 3, 406, false, false, 1, 0, 405, 407, 408);
        check("target cached", true, true, 3, 0, 3, 406, false, false, 0, 1, 405, 406, 407);
        check("cold cache", true, true, 0, 0, 3, 406, false, false, 1, 0);
        check("saved history miss", true, true, 2, 3, 3, 406, false, false, 1, 0, 405, 407);
        check("saved history hit", true, true, 2, 3, 3, 406, false, false, 0, 1, 406, 407);
        check("topic history miss", true, true, 2, 0, 3, 406, true, false, 1, 0, 405, 407);
        check("topic history hit", true, true, 2, 0, 3, 406, true, false, 0, 1, 406, 407);
        // A server miss must reach the UI, without another fallback loop.
        check("server says deleted", false, true, 2, 0, 3, 406, false, false, 0, 1, 405, 407);
        check("server empty", false, true, 0, 0, 3, 406, false, false, 0, 1);
        check("server hit", false, true, 1, 0, 3, 406, false, false, 0, 1, 406);
        check("secret chat stays local", true, true, 2, 0, 3, 406, false, true, 0, 1, 405, 407);
        check("count-only load", true, false, 2, 0, 3, 406, false, false, 0, 1);
        check("pending local message", true, true, 2, 0, 3, -4, false, false, 0, 1, -3, -5);
        check("initial load", true, true, 2, 0, 2, 0, false, false, 0, 1, 721, 722);
        check("backward pagination", true, true, 2, 0, 0, 406, false, false, 0, 1, 404, 405);
        check("forward pagination", true, true, 2, 0, 1, 406, false, false, 0, 1, 407, 408);
        check("pinned mode", true, true, 2, 2, 3, 406, false, false, 0, 1, 405, 407);
        // These modes retain their periodic refresh and cached delivery.
        check("scheduled refresh", true, true, 2, 1, 3, 406, false, false, 1, 1, 405, 407);
        check("quick replies refresh", true, true, 2, 5, 3, 406, false, false, 1, 1, 405, 407);
        check("welcome messages refresh", true, true, 2, 9, 3, 406, false, false, 1, 1, 405, 407);
        System.out.println("Passed " + checked + " message jump cache cases");
    }
}
'''.replace("CONSTANTS", constants).replace("PRODUCTION", block)

with tempfile.TemporaryDirectory(prefix="message-jump-cache-") as directory:
    java = pathlib.Path(directory) / "MessageJumpCacheTest.java"
    java.write_text(harness)
    subprocess.run(["javac", str(java)], check=True)
    subprocess.run(["java", "-cp", directory, "MessageJumpCacheTest"], check=True)
