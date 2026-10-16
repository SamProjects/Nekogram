#!/usr/bin/env python3
"""Run the production source-forward class against small Android/transport stubs.

Requires Python 3 and a JDK. No messages are sent and no account is needed.
"""
from pathlib import Path
import subprocess
import tempfile
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java_root = root / "TMessagesProj/src/main/java"
stubs = {
    "org/telegram/tgnet/SerializedData.java": """
package org.telegram.tgnet;
public class SerializedData {
    public int[] data = new int[3]; int i;
    public SerializedData(int size) {}
    public SerializedData(byte[] bytes) { java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes); for(int j=0;j<3;j++) data[j]=b.getInt(); }
    public void writeInt32(int n) { data[i++]=n; }
    public int readInt32(boolean strict) { return data[i++]; }
    public byte[] toByteArray() { java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(12); for(int n:data)b.putInt(n); return b.array(); }
    public void cleanup() {}
}
""",
    "org/telegram/tgnet/TLRPC.java": """
package org.telegram.tgnet;
import java.util.ArrayList;
public class TLRPC {
    public static class Chat { public long id; public boolean channel=true, protectedContent; public String username; }
    public static class Message {
        public int id, date, flags; public long dialog_id, grouped_id;
        public Object peer_id, from_id, action, rich_message, fwd_from;
        public String message="", attachPath=""; public boolean noforwards;
        public MessageMedia media; public ArrayList<MessageEntity> entities=new ArrayList<>();
    }
    public static class TL_message extends Message {}
    public static class MessageMedia { public Object photo, document; public int ttl_seconds; public boolean spoiler; }
    public static class TL_messageMediaEmpty extends MessageMedia {}
    public static class TL_messageMediaWebPage extends MessageMedia {}
    public static class TL_messageMediaPhoto extends MessageMedia {}
    public static class TL_messageMediaDocument extends MessageMedia {}
    public static class WebPage {}
    public static class ReplyMarkup {}
    public static class TL_photo {}
    public static class TL_document {}
    public static class MessageEntity {
        public int offset,length;
        public int getObjectSize() { return 12; }
        public void serializeToStream(SerializedData s) { s.writeInt32(this instanceof TL_messageEntityUrl?1:2); s.writeInt32(offset); s.writeInt32(length); }
        public static MessageEntity TLdeserialize(SerializedData s,int id,boolean strict) {
            MessageEntity e=id==1?new TL_messageEntityUrl():new MessageEntity(); e.offset=s.readInt32(strict); e.length=s.readInt32(strict); return e;
        }
    }
    public static class TL_messageEntityUrl extends MessageEntity {}
}
""",
    "org/telegram/messenger/MessageObject.java": """
package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessageObject {
    public final int currentAccount; public final TLRPC.Message messageOwner;
    public boolean sticker,voice,round;
    public static class SendAnimationData {}
    public MessageObject(int account,TLRPC.Message m,boolean layout,boolean media) { currentAccount=account; messageOwner=m; }
    public int getId() { return messageOwner.id; }
    public long getDialogId() { return messageOwner.dialog_id; }
    public long getGroupId() { return messageOwner.grouped_id; }
    public boolean isAnyKindOfSticker() { return sticker; }
    public boolean isVoice() { return voice; }
    public boolean isRoundVideo() { return round; }
}
""",
    "org/telegram/messenger/MessagesController.java": """
package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessagesController {
    public static final MessagesController INSTANCE=new MessagesController();
    public final java.util.HashMap<Long,TLRPC.Chat> chats=new java.util.HashMap<>();
    public int maxMessageLength=4096, captionLimit=1024;
    public static MessagesController getInstance(int account) { return INSTANCE; }
    public TLRPC.Chat getChat(long id) { return chats.get(id); }
    public boolean isPeerNoForwards(long did) { return chats.get(-did).protectedContent; }
    public int getCaptionMaxLengthLimit() { return captionLimit; }
}
""",
    "org/telegram/messenger/ChatObject.java": """
package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class ChatObject {
    public static String getPublicUsername(TLRPC.Chat c) { return c.username; }
    public static boolean isChannel(TLRPC.Chat c) { return c.channel; }
}
""",
    "org/telegram/messenger/LocaleController.java": """
package org.telegram.messenger;
public class LocaleController { public static String getString(int id) { return "转发自："; } }
""",
    "org/telegram/messenger/R.java": """
package org.telegram.messenger;
public class R { public static class string { public static int ForwardSourcePrefix=1; } }
""",
    "org/telegram/messenger/MessageSuggestionParams.java": """
package org.telegram.messenger;
public class MessageSuggestionParams {}
""",
    "org/telegram/messenger/VideoEditedInfo.java": "package org.telegram.messenger; public class VideoEditedInfo {}",
    "org/telegram/messenger/SendMessagesHelper.java": """
package org.telegram.messenger;
import java.util.ArrayList;
import java.util.HashMap;
import org.telegram.tgnet.TLRPC;
public class SendMessagesHelper {
    public final java.util.ArrayList<SendMessageParams> sent=new java.util.ArrayList<>();
    public void sendMessage(SendMessageParams p) { sent.add(p); }
    public static class SendMessageParams {
        public Object[] args; public long payStars,monoForumPeer; public MessageSuggestionParams suggestionParams;
        private static SendMessageParams capture(Object... args) { SendMessageParams p=new SendMessageParams(); p.args=args; return p; }
        SIGNATURES
    }
}
""",
}

# Compile against the actual overload signatures used by production, not a
# permissive varargs stand-in, so API drift fails this test as well.
helper_source = (java_root / "org/telegram/messenger/SendMessagesHelper.java").read_text()
signatures = []
for signature in re.findall(r"public static SendMessageParams of\(([^\n]+)\) \{", helper_source):
    parameters = re.split(r", (?![^<]*>)", signature)
    if (signature.startswith("String message, long peer,") or
            signature.startswith("TLRPC.TL_photo photo,") and len(parameters) == 16 or
            signature.startswith("TLRPC.TL_document document,") and len(parameters) == 18):
        names = [parameter.rsplit(" ", 1)[1] for parameter in parameters]
        signatures.append("public static SendMessageParams of(" + signature + ") { return capture(" + ",".join(names) + "); }")
assert len(signatures) == 3
key = "org/telegram/messenger/SendMessagesHelper.java"
stubs[key] = stubs[key].replace("SIGNATURES", "\n".join(signatures))

harness = r'''
package tw.nekomimi.nekogram.forward;
import java.util.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.*;
public class SourceForwardTest {
    static int checks;
    static void check(boolean value,String reason) { checks++; if(!value)throw new AssertionError(reason); }
    static MessageObject original(String text) {
        TLRPC.Message m=new TLRPC.TL_message(); m.id=406; m.dialog_id=-2553010221L; m.message=text;
        m.from_id=new Object(); m.peer_id=new Object(); m.fwd_from=new Object();
        return new MessageObject(0,m,false,false);
    }
    static SourceForwardMessage prepare(MessageObject m) { return (SourceForwardMessage)SourceForwardMessage.prepare(new ArrayList<>(List.of(m))).get(0); }
    static SendMessagesHelper send(SourceForwardMessage m) {
        SendMessagesHelper h=new SendMessagesHelper();
        m.send(h,99,null,false,12345,17,7,101,new MessageSuggestionParams(),null);
        for(var p:h.sent)check(p.payStars==7&&p.monoForumPeer==101&&p.suggestionParams!=null,"send context");
        return h;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var controller=MessagesController.INSTANCE;
        var chat=new TLRPC.Chat(); chat.id=2553010221L; controller.chats.put(chat.id,chat);
        var m=original("原文😀粗体");
        var bold=new TLRPC.MessageEntity();bold.offset=4;bold.length=2;m.messageOwner.entities.add(bold);
        check(SourceForwardMessage.getSourceLink(m).equals("https://t.me/c/2553010221/406"),"64-bit private link");
        chat.username="public_group";
        check(SourceForwardMessage.getSourceLink(m).equals("https://t.me/public_group/406"),"public username");
        chat.channel=false;
        check(SourceForwardMessage.canPrepare(m),"public group link");
        chat.username=null;
        check(!SourceForwardMessage.canPrepare(m),"private basic group has no permalink");
        chat.channel=true;
        var copy=prepare(m);String expected="原文😀粗体\n\n转发自：https://t.me/c/2553010221/406";
        check(copy.messageOwner.message.equals(expected),"exact footer and two newlines");
        check(m.messageOwner.message.equals("原文😀粗体")&&m.messageOwner.entities.size()==1,"original unchanged");
        check(copy.messageOwner.fwd_from==null,"no forward header");
        check(copy.messageOwner.entities.get(0)!=bold,"independent entity objects");
        var url=copy.messageOwner.entities.get(1);
        check(expected.substring(url.offset,url.offset+url.length).equals("https://t.me/c/2553010221/406"),"UTF-16 clickable URL offset");
        check(SourceForwardMessage.prepare(new ArrayList<>(List.of(copy))).get(0)==copy,"preparation is idempotent");
        var h=send(copy);check(h.sent.size()==1,"one text message");
        var p=h.sent.get(0);
        check(p.args[0].equals(expected)&&p.args[1].equals(99L),"send composed text to destination");
        check(p.args[2]==null&&p.args[4]==null&&p.args[5].equals(false),"no old reply or source link preview");
        check(p.args[9].equals(false)&&p.args[10].equals(12345)&&p.args[11].equals(17),"silent scheduled/repeating send");
        // Split at a surrogate boundary and keep each URL together when possible.
        controller.maxMessageLength=40;
        String longText="a".repeat(39)+"😀"+"b".repeat(70);
        var longParts=SourceForwardMessage.prepare(new ArrayList<>(List.of(original(longText))));
        var split=new SendMessagesHelper();
        Set<Integer> ids=new HashSet<>();
        for(var part:longParts) {
            check(ids.add(part.getId()),"split preview IDs unique");
            split.sent.addAll(send((SourceForwardMessage)part).sent);
        }
        check(split.sent.size()==longParts.size(),"prepared count matches billed send count");
        StringBuilder joined=new StringBuilder();
        for(var part:split.sent) {
            String text=(String)part.args[0];joined.append(text);
            check(text.length()<=40,"message length limit");
            check(!Character.isHighSurrogate(text.charAt(text.length()-1)),"surrogate not cut");
            for(var e:(ArrayList<TLRPC.MessageEntity>)part.args[6])check(e.offset>=0&&e.offset+e.length<=text.length(),"entity slice bounds");
        }
        check(joined.toString().equals(longText+"\n\n转发自：https://t.me/c/2553010221/406"),"split loses no text");
        check(bold.offset==4&&bold.length==2,"original formatting offsets unchanged");
        controller.maxMessageLength=4096;
        var photo=original("说明");photo.messageOwner.media=new TLRPC.TL_messageMediaPhoto();photo.messageOwner.media.photo=new TLRPC.TL_photo();
        photo.messageOwner.media.spoiler=true;photo.messageOwner.grouped_id=888;
        var photoCopy=prepare(photo);var ph=send(photoCopy);
        check(ph.sent.size()==1&&ph.sent.get(0).args[0]==photo.messageOwner.media.photo,"photo copied");
        check(((String)ph.sent.get(0).args[5]).startsWith("说明\n\n转发自："),"caption footer");
        check(ph.sent.get(0).args[15].equals(true),"spoiler preserved");
        check(photoCopy.getGroupId()==888,"album identity preserved");
        var doc=original("文件");doc.messageOwner.media=new TLRPC.TL_messageMediaDocument();doc.messageOwner.media.document=new TLRPC.TL_document();
        var dh=send(prepare(doc));check(dh.sent.size()==1&&dh.sent.get(0).args[0]==doc.messageOwner.media.document,"document copied");
        controller.captionLimit=4;
        var overflowParts=SourceForwardMessage.prepare(new ArrayList<>(List.of(photo)));
        var overflow=new SendMessagesHelper();
        for(var part:overflowParts)overflow.sent.addAll(send((SourceForwardMessage)part).sent);
        check(overflow.sent.size()==2&&overflow.sent.get(0).args[5].equals("")&&overflow.sent.get(1).args[0].equals(photoCopy.messageOwner.message),"caption overflow keeps full text");
        var photo2=original("第二张");photo2.messageOwner.id=407;photo2.messageOwner.media=photo.messageOwner.media;photo2.messageOwner.grouped_id=888;
        var album=SourceForwardMessage.prepare(new ArrayList<>(List.of(photo,photo2)));
        check(album.size()==4&&album.get(0).getGroupId()==888&&album.get(1).getGroupId()==888&&album.get(2).getGroupId()==0,"caption overflow follows intact album");
        chat.protectedContent=true;check(!SourceForwardMessage.canPrepare(m),"protected chat excluded");chat.protectedContent=false;
        m.messageOwner.noforwards=true;check(!SourceForwardMessage.canPrepare(m),"protected message excluded");m.messageOwner.noforwards=false;
        m.messageOwner.id=0;check(!SourceForwardMessage.canPrepare(m),"unsent message excluded");m.messageOwner.id=406;
        m.messageOwner.rich_message=new Object();check(!SourceForwardMessage.canPrepare(m),"rich content not flattened");m.messageOwner.rich_message=null;
        m.sticker=true;check(!SourceForwardMessage.canPrepare(m),"stickers have no caption");m.sticker=false;
        m.voice=true;check(!SourceForwardMessage.canPrepare(m),"voice excluded");m.voice=false;
        m.round=true;check(!SourceForwardMessage.canPrepare(m),"round video excluded");m.round=false;
        m.messageOwner.dialog_id=10;check(!SourceForwardMessage.canPrepare(m),"private user chat excluded");
        System.out.println("PASS: "+checks+" source forwarding assertions");
    }
}
'''

context = (java_root / "tw/nekomimi/nekogram/forward/ForwardContext.java").read_text()
reset_methods = re.findall(r"default void setForwardParams\([^}]+\}", context)
assert len(reset_methods) == 2
params_class = re.search(r"class ForwardParams \{[^}]+\}", context).group(0)
mode_class = "static class ForwardMode { final ForwardParams forwardParams = new ForwardParams();" + params_class + "\n".join(reset_methods).replace("default void", "public void") + "}"
harness = harness.replace("    static int checks;", mode_class + "\n    static int checks;")
harness = harness.replace("        var controller=MessagesController.INSTANCE;", """
+        var mode=new ForwardMode();
+        mode.forwardParams.preserveSource=true;mode.setForwardParams(false,false);
+        check(!mode.forwardParams.preserveSource,"normal forward resets source mode");
+        mode.forwardParams.preserveSource=true;mode.setForwardParams(true);
+        check(!mode.forwardParams.preserveSource&&mode.forwardParams.noQuote,"no-quote mode resets source mode");
+        var controller=MessagesController.INSTANCE;
+""".replace("\n+", "\n"))

with tempfile.TemporaryDirectory(prefix="source-forward-") as tmp:
    folder = Path(tmp)
    for path, content in stubs.items():
        target = folder / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
    production = Path("tw/nekomimi/nekogram/forward/SourceForwardMessage.java")
    target = folder / production
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text((java_root / production).read_text())
    (target.parent / "SourceForwardTest.java").write_text(harness)
    subprocess.run(["javac", "-d", tmp, *map(str, folder.rglob("*.java"))], check=True)
    subprocess.run(["java", "-cp", tmp, "tw.nekomimi.nekogram.forward.SourceForwardTest"], check=True)

for locale in ("values", "values-zh-rCN", "values-b+zh+Dg", "values-zh-rTW"):
    tree = ET.parse(root / "TMessagesProj/src/main/res" / locale / "strings_neko.xml")
    names = [item.attrib.get("name") for item in tree.getroot()]
    assert names.count("SourceForward") == names.count("ForwardSourcePrefix") == 1
print("PASS: source forwarding translations parse and have unique resource names")
