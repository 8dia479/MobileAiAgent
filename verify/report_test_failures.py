#!/usr/bin/env python3
"""Emit GitHub Actions ::error:: annotations for failing JVM tests.

Gradle's console output for a *runtime* failure only says
':app:testDebugUnitTest FAILED'; which test failed, and why, is only in
app/build/test-results/**/*.xml. The Actions job log is not downloadable from
every environment, but annotations are, so surface the details there.
"""
import glob
import sys
import xml.etree.ElementTree as ET

found = 0
for path in sorted(glob.glob("app/build/test-results/**/*.xml", recursive=True)):
    try:
        root = ET.parse(path).getroot()
    except Exception:
        continue
    for case in root.iter("testcase"):
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is None:
                continue
            found += 1
            name = "%s.%s" % (case.get("classname", "?"), case.get("name", "?"))
            message = (node.get("message") or "").strip().replace("::", ":")
            stack = [
                line.strip()
                for line in (node.text or "").splitlines()
                if line.strip().startswith("at ")
            ]
            where = stack[0] if stack else ""
            line = "FAILED %s — %s | %s" % (name, message[:400], where[:200])
            print("::error::" + line.replace("\n", " "))

if found == 0:
    print("No failing test cases found in app/build/test-results")
else:
    print("%d failing test case(s) reported as annotations" % found)
sys.exit(0)
