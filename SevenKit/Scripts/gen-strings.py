#!/usr/bin/env python3
"""Rebuild Sources/SevenKit/Resources/Localizable.xcstrings from the seven("key", "default") calls."""
import json, pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent.parent / "Sources" / "SevenKit"
call = re.compile(r'seven\(\s*"(seven\.[A-Za-z0-9_.]+)"\s*,\s*"((?:[^"\\]|\\.)*)"')
strings = {}
for f in sorted(root.glob("*.swift")):
    for key, value in call.findall(f.read_text()):
        value = json.loads('"' + value + '"')
        old = strings.get(key, {}).get("localizations", {}).get("en", {}).get("stringUnit", {}).get("value", value)
        if old != value:
            sys.exit(f"conflicting defaults for {key}")
        strings[key] = {
            "extractionState": "manual",
            "localizations": {"en": {"stringUnit": {"state": "translated", "value": value}}},
        }
out = {"sourceLanguage": "en", "strings": dict(sorted(strings.items())), "version": "1.0"}
(root / "Resources" / "Localizable.xcstrings").write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")
print(len(strings), "strings")
