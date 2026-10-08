#!/usr/bin/env python3
"""Check local Markdown links and JSON code examples without network access."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
errors = []
for file in root.rglob('*.md'):
    if any(p in {'.git', '.runtime', 'node_modules', 'target', 'dist'} for p in file.parts):
        continue
    text = file.read_text()
    if len(re.findall(r'^```', text, re.M)) % 2:
        errors.append(f'{file.relative_to(root)}: unbalanced code fences')
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)', text):
        target = target.split('#')[0]
        if not target or '://' in target or target.startswith(('mailto:', 'app:')):
            continue
        if not (file.parent / target).exists():
            errors.append(f'{file.relative_to(root)}: missing {target}')
    for example in re.findall(r'```json\s*\n(.*?)\n```', text, re.S):
        try:
            json.loads(example)
        except json.JSONDecodeError as error:
            errors.append(f'{file.relative_to(root)}: JSON {error}')
for error in errors:
    print(error)
if errors:
    raise SystemExit(1)
print('Markdown links, fences and JSON examples passed')
