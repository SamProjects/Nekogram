#!/usr/bin/env python3
"""Exercise actual details callbacks before/during/after recycler construction."""
from pathlib import Path
import subprocess
import tempfile
root=Path(__file__).resolve().parents[1]
base=root/'TMessagesProj/src/main/java/tw/nekomimi/nekogram'
s=(base/'settings/BaseNekoSettingsActivity.java').read_text()
a=s.index('    protected void notifyItemChanged(int itemId, Object payload)')
b=s.index('\n    protected void notifyItemRemoved',a)
notify=s[a:b]
s=(base/'MessageDetailsActivity.java').read_text()
a=s.index('            var item = TextDetailSettingsCellFactory.of(languageRow, "Language", "Loading...");')
b=s.index('\n        }',a)
block=s[a:b]
harness=r'''
import java.util.*;
public class DetailsTest {
static class Item {String subtext;Item(String s){subtext=s;}}
static class TextDetailSettingsCellFactory {static Item of(int id,String a,String b){return new Item(b);}}
static class TextUtils {static boolean isEmpty(String s){return s==null||s.isEmpty();}}
static class R {static class string {static int LanguageUnknownTitle=1;}}
static class LocaleController {static String getString(int i){return "Insufficient data";}}
interface Success {void run(String s);} interface Failure {void run(Exception e);}
static class LanguageDetector {
static int mode;static Runnable pending;
static void detectLanguage(String text,Success ok,Failure fail){
Runnable action=()->{if(mode==1)ok.run("zh");else if(mode==2)fail.run(null);else fail.run(new IllegalStateException("unavailable"));};
if(mode==3)pending=action;else action.run();
}}
static class Adapter {int calls,last=-1;void notifyItemChanged(int p,Object payload){calls++;last=p;}}
static class ListView {Adapter adapter;int position=0;int findPositionByItemId(int id){return position;}}
ListView listView;boolean isFinished;int languageRow=1;Object messageObject;
static class Helper {String getMessagePlainText(Object o){return "text";}}
Helper getMessageHelper(){return new Helper();}
void notifyItemChanged(int id){notifyItemChanged(id,null);}
NOTIFY
void fill(ArrayList<Item> items){BLOCK}
static void check(boolean b){if(!b)throw new AssertionError();}
public static void main(String[] args){
for(int mode:new int[]{0,1,2}){
LanguageDetector.mode=mode;DetailsTest d=new DetailsTest();ArrayList<Item> items=new ArrayList<>();
d.fill(items);check(items.size()==1);check(!items.get(0).subtext.equals("Loading..."));
// First render uses the already updated item even when synchronous refresh was skipped.
d.listView=new ListView();d.notifyItemChanged(1); // adapter not assigned yet
}
LanguageDetector.mode=3;DetailsTest d=new DetailsTest();ArrayList<Item> items=new ArrayList<>();d.fill(items);
d.listView=new ListView();d.listView.adapter=new Adapter();LanguageDetector.pending.run();
check(d.listView.adapter.calls==1);check(items.get(0).subtext.equals("unavailable"));
d.listView.position=-1;d.notifyItemChanged(1);check(d.listView.adapter.calls==1);
d.listView.position=0;d.isFinished=true;LanguageDetector.pending.run();check(d.listView.adapter.calls==1);
d.listView=null;LanguageDetector.pending.run();
System.out.println("PASS: synchronous success/failure/null error, constructor ordering, delayed callback, missing row and destroyed page");
}}
'''.replace('NOTIFY',notify).replace('BLOCK',block)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'DetailsTest.java';p.write_text(harness)
 subprocess.run(['javac',str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'DetailsTest'],check=True)
