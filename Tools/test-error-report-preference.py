#!/usr/bin/env python3
"""Exercise the actual exception reporting method for every privacy switch state."""
from pathlib import Path
import subprocess
import tempfile
source=(Path(__file__).resolve().parents[1]/'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/AnalyticsHelper.java').read_text()
a=source.index('    public static void captureException(Exception e)')
b=source.index('\n    public static void trackFragmentLifecycle',a)
method=source[a:b]
s='''class Sentry {static int calls;static void captureException(Exception e){calls++;}}
public class ReportPreferenceTest {
static boolean analyticsDisabled,sendBugReport;
METHOD
public static void main(String[] args){
for(boolean disabled:new boolean[]{false,true}) for(boolean consent:new boolean[]{false,true}) for(boolean empty:new boolean[]{false,true}){
analyticsDisabled=disabled;sendBugReport=consent;Sentry.calls=0;
captureException(empty?null:new RuntimeException("test"));
if(Sentry.calls!=(!disabled&&consent&&!empty?1:0))throw new AssertionError("Reporting ignored consent");
}
System.out.println("PASS: 8 exception-report consent cases");
}}
'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'ReportPreferenceTest.java';p.write_text(s)
 subprocess.run(['javac',str(p)],check=True)
 subprocess.run(['java','-cp',d,'ReportPreferenceTest'],check=True)
