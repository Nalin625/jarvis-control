#!/usr/bin/env python3
"""Makes ThemeHomeActivity the home screen: removes HOME from every activity, then adds a fresh ThemeHomeActivity block.
Run from the jarvis-control folder. Safe to run any number of times."""
import re, sys, os
import xml.dom.minidom as md
P = "app/src/main/AndroidManifest.xml"
if not os.path.exists(P):
    sys.exit("Run this from the jarvis-control folder.")
s = open(P, encoding="utf-8").read()
# 1. drop any old ThemeHomeActivity block so it is always re-added cleanly
s = re.sub(r'\s*<activity\s+android:name="\.ThemeHomeActivity".*?</activity>', "", s, flags=re.S)
# 2. drop HOME intent-filters from every other activity
HOME_RE = re.compile(r'\s*<intent-filter>(?:(?!</intent-filter>).)*?category\.HOME(?:(?!</intent-filter>).)*?</intent-filter>', re.S)
s = HOME_RE.sub("", s)
# 3. insert the new home activity
block = '''
        <activity
            android:name=".ThemeHomeActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:label="Jarvis Home"
            android:stateNotNeeded="true"
            android:theme="@style/Theme.JarvisControl">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
'''
if "</application>" not in s:
    sys.exit("no </application> tag - stopping, nothing changed")
s = s.replace("</application>", block + "    </application>", 1)
# 4. verify: XML parses and HOME is on exactly ThemeHomeActivity
doc = md.parseString(s.encode())
homes = []
for a in doc.getElementsByTagName("activity"):
    for c in a.getElementsByTagName("category"):
        if c.getAttribute("android:name") == "android.intent.category.HOME":
            homes.append(a.getAttribute("android:name"))
if homes != [".ThemeHomeActivity"]:
    sys.exit("check failed, HOME is on: %s - nothing changed" % homes)
open(P, "w", encoding="utf-8").write(s)
print("VERIFIED: HOME is on .ThemeHomeActivity only")
