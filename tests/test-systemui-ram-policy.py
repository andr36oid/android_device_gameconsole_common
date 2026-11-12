#!/usr/bin/env python3
"""Compile the real filter and check the overlay against the actual service list."""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

src = Path(__file__).resolve().parents[4]
systemui = src / "frameworks/base/packages/SystemUI"
overlay = src / "device/gameconsole/common/overlay/frameworks/base/packages/SystemUI/res/values/config.xml"
base = ET.parse(systemui / "res/values/config.xml").getroot()
over = ET.parse(overlay).getroot()
array = lambda root, name: [x.text for x in root.find(f"string-array[@name='{name}']")]
components = array(base, "config_systemUIServiceComponents")
assert not array(base, "config_systemUIOptionalConsoleServices")
optional = array(over, "config_systemUIOptionalConsoleServices")
assert optional == ["com.android.systemui.keyboard.KeyboardUI"]
assert optional[0] in components
core = ET.parse(src / "frameworks/base/core/res/res/values/config.xml").getroot()
assert not core.find("string[@name='config_packagedKeyboardName']").text
test = '''package com.android.systemui;
import java.util.*;
public class SystemUIServicePolicyTest {
 public static void main(String[] args) {
  String k="com.android.systemui.keyboard.KeyboardUI";
  String[] all={"StatusBar",k,"PowerUI","ToastUI"};
  for(boolean enabled:new boolean[]{false,true}) for(boolean dock:new boolean[]{false,true}) {
   String[] got=SystemUIServicePolicy.filter(all,new String[]{k,"StatusBar"},enabled,dock);
   String[] want=enabled&&!dock?new String[]{"StatusBar","PowerUI","ToastUI"}:all;
   if(!Arrays.equals(got,want))throw new AssertionError("switch/dock/order");
   if(!Arrays.equals(SystemUIServicePolicy.filter(all,new String[0],enabled,dock),all))
    throw new AssertionError("non-console changed");
  }
  String[] perUser={"NotificationChannels"};
  if(!Arrays.equals(SystemUIServicePolicy.filter(perUser,new String[]{k},true,false),perUser))
   throw new AssertionError("secondary user changed");
  System.out.println("SystemUI RAM policy PASS: switch, dock, non-console, callbacks, overlay");
 }
}'''
jdk = src / "prebuilts/jdk/jdk11/linux-x86/bin"
with tempfile.TemporaryDirectory(prefix="sysui-ram-") as tmp:
    temp = Path(tmp)
    java = temp / "SystemUIServicePolicyTest.java"
    java.write_text(test)
    subprocess.run([str(jdk / "javac"), "-d", tmp,
                    str(systemui / "src/com/android/systemui/SystemUIServicePolicy.java"), str(java)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", tmp, "com.android.systemui.SystemUIServicePolicyTest"], check=True)
