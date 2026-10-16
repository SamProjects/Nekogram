#!/usr/bin/env python3
"""Compile the production connection selector and exercise urgent/return paths."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
base = root / 'TMessagesProj/src/main/java/org/telegram/messenger'
s = (base / 'FileLoadOperation.java').read_text()
start = s.index('    static int selectDownloadConnection(')
end = s.index('\n    protected void startDownloadRequest(', start)
method = s[start:end]
# Offset zero must not be mistaken for "no urgent read" at any call site.
assert 'private long streamPriorityStartOffset = -1;' in s
assert 'streamPriorityStartOffset == 0' not in s
assert 'streamPriorityStartOffset != 0' not in s
assert 'streamPriorityStartOffset = 0;' not in s
stream = (base / 'FileStreamLoadOperation.java').read_text()
assert stream.count('parentObject, currentOffset, true, getCurrentPriority()') == 3
assert 'if (priorityChanged || stream != null)' in (base / 'FileLoader.java').read_text()
harness = '''class ConnectionsManager {
static final int ConnectionTypeDownload=2, DownloadConnectionsCount=8;
}
public class PlaybackPriorityTest {
METHOD
static void check(boolean b) { if (!b) throw new AssertionError(); }
public static void main(String[] args) {
int urgent=2|(7<<16);
int[] counts={Integer.MIN_VALUE,-1,0,1,2,7,8,Integer.MAX_VALUE};
for(int count:counts) for(int pool:new int[]{2,8}) {
  for(int previous:new int[]{-1,2,2|(1<<16),urgent}) {
    check(selectDownloadConnection(count,pool,previous,true)==urgent);
    int next=selectDownloadConnection(count,pool,previous,false);
    check((next&65535)==2);
    check((next>>>16)<pool);
    if(pool==2) check(next!=urgent);
    if(previous!=-1 && previous!=urgent) check(next==previous);
  }
}
System.out.println("Playback connection routing: 64 urgent/normal pairs passed");
}}
'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as d:
    p=Path(d)/'PlaybackPriorityTest.java';p.write_text(harness)
    subprocess.run(['javac',str(p)],check=True)
    subprocess.run(['java','-cp',d,'PlaybackPriorityTest'],check=True)
print('Zero-offset sentinel, all player read paths, same-priority seek scheduling passed')
