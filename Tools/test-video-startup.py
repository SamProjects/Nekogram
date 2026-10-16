#!/usr/bin/env python3
"""Exercise production stream reads with real files and queue-use assertions.

Requires Python 3 and a JDK. Android/Telegram queues are small stubs; the byte
availability, range publication, cached-file opening, reading and duration label
logic are extracted from production Java without translating their control flow.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
base = root / "TMessagesProj/src/main/java/org/telegram"
operation_path = Path(sys.argv[1]) if len(sys.argv) > 1 else base / "messenger/FileLoadOperation.java"
operation = operation_path.read_text()
stream = (base / "messenger/FileStreamLoadOperation.java").read_text()
viewer = (base / "ui/PhotoViewer.java").read_text()


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


range_methods = "\n".join(method(operation, name) for name in (
    "private long getDownloadedLengthFromOffsetInternal(",
    "protected long[] getDownloadedLengthFromOffset(",
    "private void copyNotLoadedRanges(",
))
stream_methods = "\n".join(method(stream, name) for name in (
    "private boolean tryOpenCachedFile(", "public int read(", "public void close(",
    "public boolean isDownloadCancelled(", "private void checkDownloadCancelled(", "public void newDataAvailable(",
))
start = viewer.index("            long duration = videoPlayer.getDuration();", viewer.index("private void updateVideoPlayerTime()"))
end = viewer.index("            if (!inPreview", viewer.index("            long total =", start))
duration_block = viewer[start:end]

harness = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public class VideoStartupTest {
    static int assertions;
    static void check(boolean value,String why) { assertions++;if(!value)throw new AssertionError(why); }
    static class Queue {
        int calls;boolean forbidden;
        void postRunnable(Runnable r) { calls++;if(forbidden)throw new AssertionError("blocked queue was used");r.run(); }
    }
    static class Utilities { static Queue stageQueue=new Queue(); }
    static class FileLog { static void e(Object e) {} static void d(String s) {} }
    static class BuildVars { static boolean LOGS_ENABLED; }
    static class Range { long start,end;Range(long s,long e){start=s;end=e;} }
    static class Operation {
        static final int stateDownloading=1,stateFailed=2,stateFinished=3;
        volatile int state=stateDownloading;
        long totalBytesCount=1000,downloadedBytes=700;
        ArrayList<Range> notLoadedBytesRanges,notLoadedBytesRangesCopy;
        RANGE_METHODS
    }
    static class C { static final int RESULT_END_OF_INPUT=-1,RESULT_NOTHING_READ=-3,LENGTH_UNSET=-1; }
    static class Document { long size,id=42; }
    static class FileLoadOperation {
        volatile boolean cancelled;
        boolean allowReads;
        boolean isCancelled(){return cancelled;}
        long[] getDownloadedLengthFromOffset(long p,long n){if(allowReads)return new long[]{0,0};throw new AssertionError("cached read entered download queue");}
        File getCurrentFileFast(){return null;}
        void removeStreamListener(Object listener){}
        boolean isFinished(){return false;}
        String getFileName(){return "video";}
    }
    static class FileLoader {
        static File normal,cache;
        static FileLoadOperation pending;
        static Runnable onLoad;
        static int requests;
        static FileLoader getInstance(int account){return new FileLoader();}
        File getPathToAttach(Document d,boolean force){return force?cache:normal;}
        FileLoadOperation loadStreamFile(Object... params){
            if(pending==null)throw new AssertionError("cached read started download");
            requests++;if(onLoad!=null)onLoad.run();
            return ((Stream)params[0]).isDownloadCancelled()?null:pending;
        }
        void cancelLoadFile(String name){}
    }
    static class Stream {
        FileLoadOperation loadOperation;
        long bytesRemaining,bytesTransferred,requestedLength=C.LENGTH_UNSET,currentOffset;
        volatile boolean downloadCancelled;
        boolean opened=true,cachedFileRead,isNetwork=true;
        CountDownLatch countDownLatch;
        RandomAccessFile file;
        Document document=new Document();
        Object parentObject,uri;
        int currentAccount,notifiedBytes;
        File currentFile;
        static Map<Long,Stream> allStreams=new HashMap<>();
        int getCurrentPriority(){return 3;}
        void bytesTransferred(int n){notifiedBytes+=n;}
        void transferEnded(){}
        STREAM_METHODS
    }
    static class Player { long duration;long getDuration(){return duration;} }
    static class Message { double duration;double getDuration(){return duration;} }
    static long labelDuration(long actual,double metadata,boolean inPreview,boolean localGallery) {
        Player videoPlayer=new Player();videoPlayer.duration=actual;
        Message currentMessageObject=new Message();currentMessageObject.duration=metadata;
        ArrayList<Object> imagesArrLocals=new ArrayList<>();if(localGallery)imagesArrLocals.add(new Object());
        DURATION_BLOCK
        check(videoPlayer.getDuration()==actual,"fallback must not fabricate the player's timeline");
        return total;
    }
    public static void main(String[] args) throws Exception {
        Operation op=new Operation();
        op.notLoadedBytesRanges=new ArrayList<>(List.of(new Range(100,200),new Range(300,500)));
        op.copyNotLoadedRanges();
        Utilities.stageQueue.forbidden=true;
        check(op.getDownloadedLengthFromOffset(0,64)[0]==64,"cached prefix bypasses queue");
        check(op.getDownloadedLengthFromOffset(90,64)[0]==10,"read stops at missing boundary");
        check(op.getDownloadedLengthFromOffset(250,100)[0]==50,"discontinuous buffered range");
        check(op.getDownloadedLengthFromOffset(500,800)[0]==500,"clamp to EOF");
        for(int i=0;i<1000;i++)check(op.getDownloadedLengthFromOffset(0,32)[0]==32,"repeated reads never queue");
        op.notLoadedBytesRanges.get(0).start=150;
        check(op.notLoadedBytesRangesCopy.get(0).start==100,"published ranges do not share mutable Range objects");
        Utilities.stageQueue.forbidden=false;
        check(op.getDownloadedLengthFromOffset(120,40)[0]==30,"missing snapshot bytes use authoritative queue");
        check(Utilities.stageQueue.calls==1,"one fallback queue request");
        op.copyNotLoadedRanges();Utilities.stageQueue.forbidden=true;
        check(op.getDownloadedLengthFromOffset(120,40)[0]==30,"newly published data bypasses queue");
        op.state=Operation.stateFinished;
        check(Arrays.equals(op.getDownloadedLengthFromOffset(0,100),new long[]{100,1}),"finished file bypasses queue");
        op.state=Operation.stateDownloading;op.notLoadedBytesRanges=op.notLoadedBytesRangesCopy=null;op.downloadedBytes=70;
        Utilities.stageQueue.forbidden=false;
        check(op.getDownloadedLengthFromOffset(60,30)[0]==10,"sequential download keeps queued fallback");
        op.totalBytesCount=8_000_000_000L;
        op.notLoadedBytesRanges=new ArrayList<>(List.of(new Range(5_000_000_000L,6_000_000_000L)));
        op.copyNotLoadedRanges();Utilities.stageQueue.forbidden=true;
        check(op.getDownloadedLengthFromOffset(4_999_999_990L,30)[0]==10,"64-bit video offsets");

        Path dir=Files.createTempDirectory("video-startup-files-");
        try {
            File normal=dir.resolve("video.mp4").toFile(),cache=dir.resolve("cache.mp4").toFile();
            byte[] data=new byte[32];for(int i=0;i<data.length;i++)data[i]=(byte)i;
            Files.write(normal.toPath(),data);FileLoader.normal=normal;FileLoader.cache=cache;
            Stream s=new Stream();s.document.size=32;s.currentOffset=5;s.requestedLength=9;
            check(s.tryOpenCachedFile(),"completed tg URI opens locally");
            check(s.cachedFileRead&&!s.isNetwork&&s.loadOperation==null,"no download operation for local cache");
            byte[] buffer=new byte[30];
            check(s.read(buffer,2,20)==9,"bounded byte-range read");
            check(Arrays.equals(Arrays.copyOfRange(buffer,2,11),Arrays.copyOfRange(data,5,14)),"byte-range contents");
            check(s.read(buffer,0,1)==C.RESULT_END_OF_INPUT,"requested range EOF");
            check(s.read(buffer,0,0)==0,"zero-length read");
            check(s.notifiedBytes==9&&s.bytesTransferred==9,"transfer accounting");s.close();
            s=new Stream();s.document.size=32;s.currentOffset=32;
            check(s.tryOpenCachedFile()&&s.read(buffer,0,1)==C.RESULT_END_OF_INPUT,"open at EOF");s.close();
            s=new Stream();s.document.size=32;s.currentOffset=33;
            try{s.tryOpenCachedFile();throw new AssertionError("past EOF accepted");}catch(EOFException expected){assertions++;}
            s=new Stream();s.document.size=40;
            check(!s.tryOpenCachedFile(),"size mismatch keeps streaming fallback");
            s.document.size=0;check(!s.tryOpenCachedFile(),"unknown size cannot masquerade as complete");
            Files.move(normal.toPath(),cache.toPath());s.document.size=32;
            check(s.tryOpenCachedFile()&&s.currentFile.equals(cache),"force-cache directory lookup");s.close();
            File encrypted=dir.resolve("video.enc").toFile();Files.move(cache.toPath(),encrypted.toPath());
            FileLoader.normal=encrypted;check(!s.tryOpenCachedFile(),"encrypted cache never read as cleartext");
            FileLoader.normal=normal;Files.write(normal.toPath(),data);s=new Stream();s.document.size=32;
            check(s.tryOpenCachedFile(),"reopen complete cache");Files.write(normal.toPath(),new byte[0]);
            try{s.read(buffer,0,1);throw new AssertionError("truncation ignored");}catch(EOFException expected){assertions++;}s.close();
        } finally {
            try(var files=Files.list(dir)){for(Path p:files.toList())Files.delete(p);}Files.delete(dir);
        }
        FileLoadOperation cancelled=new FileLoadOperation();cancelled.cancelled=true;
        Stream stopped=new Stream();stopped.loadOperation=cancelled;stopped.bytesRemaining=10;
        try{stopped.read(new byte[1],0,1);throw new AssertionError("cancel ignored");}
        catch(FileNotFoundException expected){assertions++;}
        check(stopped.isDownloadCancelled(),"cancellation is sticky across close/open retries");
        stopped.loadOperation=null;
        check(stopped.isDownloadCancelled(),"retry cannot erase cancellation by clearing operation");
        check(!new Stream().isDownloadCancelled(),"new playback attempt can restart");
        FileLoadOperation pending=new FileLoadOperation();pending.allowReads=true;FileLoader.pending=pending;
        Stream racing=new Stream();racing.loadOperation=pending;racing.bytesRemaining=10;
        FileLoader.onLoad=()->pending.cancelled=true;
        try{racing.read(new byte[1],0,1);throw new AssertionError("cancel/load race ignored");}
        catch(FileNotFoundException expected){assertions++;}
        check(FileLoader.requests==1,"cancelled reader does not retry loader");
        FileLoadOperation waiting=new FileLoadOperation();waiting.allowReads=true;FileLoader.pending=waiting;
        Stream blocked=new Stream();blocked.loadOperation=waiting;blocked.bytesRemaining=10;
        CountDownLatch entered=new CountDownLatch(1);FileLoader.onLoad=entered::countDown;
        Throwable[] outcome=new Throwable[1];
        Thread reader=new Thread(()->{try{blocked.read(new byte[1],0,1);}catch(Throwable e){outcome[0]=e;}});
        reader.start();check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"reader entered loader");
        waiting.cancelled=true;blocked.newDataAvailable();reader.join(2000);
        check(!reader.isAlive(),"cancel wakes blocked reader");
        check(outcome[0] instanceof FileNotFoundException,"cancel uses non-retryable exception directly");
        check(FileLoader.requests==2,"woken reader never creates replacement download");
        FileLoader.pending=null;FileLoader.onLoad=null;
        check(labelDuration(-9223372036854775807L,120.25,false,false)==120250,"known duration available before extractor");
        check(labelDuration(99000,120.25,false,false)==99000,"parsed duration is authoritative");
        check(labelDuration(-9223372036854775807L,120.25,true,false)==0,"editing preview excludes metadata fallback");
        check(labelDuration(-9223372036854775807L,120.25,false,true)==0,"local gallery excludes stale chat metadata");
        check(labelDuration(-9223372036854775807L,0,false,false)==0,"unknown duration stays unknown");
        System.out.println("PASS: "+assertions+" playback startup assertions");
    }
}
'''.replace("RANGE_METHODS", range_methods).replace("STREAM_METHODS", stream_methods).replace("DURATION_BLOCK", duration_block)

with tempfile.TemporaryDirectory(prefix="video-startup-java-") as tmp:
    java = Path(tmp) / "VideoStartupTest.java"
    java.write_text(harness)
    subprocess.run(["javac", str(java)], check=True)
    subprocess.run(["java", "-cp", tmp, "VideoStartupTest"], check=True)
