#!/usr/bin/env python3
"""Generate index.html for ~/public_html with Docusaurus navbar labels."""

import re
import os
import html as H

public = os.path.expanduser("~/public_html")
works = os.path.expanduser("~/works")
projects = sorted(
    d for d in os.listdir(public) if os.path.isdir(os.path.join(public, d))
)


def extract_navbar_labels(conf_path):
    """Extract left-positioned navbar item labels from a Docusaurus config."""
    text = open(conf_path).read()
    m = re.search(r"items\s*:\s*\[", text)
    if not m:
        return []
    labels = []
    depth = 0
    block = ""
    for ch in text[m.end() :]:
        if ch == "{":
            depth += 1
        if depth > 0:
            block += ch
        if ch == "}":
            depth -= 1
            if depth == 0:
                if "left" in block:
                    lm = re.search(r"""label:\s*['"]([^'"]+)""", block)
                    if lm:
                        labels.append(lm.group(1))
                block = ""
        if depth == 0 and ch == "]":
            break
    return labels


# --- HTML template ---
out = """<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Documentation Sites</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, sans-serif;
       background: #f8fafc; color: #1e293b; padding: 40px 20px; }
.container { max-width: 800px; margin: 0 auto; }
h1 { font-size: 24px; font-weight: 700; margin-bottom: 8px; }
.subtitle { color: #64748b; font-size: 14px; margin-bottom: 32px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
        gap: 16px; }
.card { background: white; border-radius: 12px; padding: 20px;
        box-shadow: 0 1px 3px rgba(0,0,0,.08);
        transition: box-shadow .15s, transform .15s;
        text-decoration: none; color: inherit; display: block; }
.card:hover { box-shadow: 0 4px 12px rgba(0,0,0,.12);
              transform: translateY(-2px); }
.card-name { font-weight: 600; font-size: 15px; margin-bottom: 4px; }
.card-tags { font-size: 11px; color: #64748b; margin-top: 6px;
             line-height: 1.8; }
.card-tags span { background: #f1f5f9; border-radius: 4px;
                  padding: 2px 6px; margin-right: 3px;
                  display: inline-block; margin-bottom: 2px; }
</style>
</head>
<body>
<div class="container">
<h1>Documentation Sites</h1>
<p class="subtitle">Built Docusaurus projects</p>
<div class="grid">
"""

for p in projects:
    conf = None
    for ext in ["docusaurus.config.js", "docusaurus.config.ts"]:
        fp = os.path.join(works, p, ext)
        if os.path.exists(fp):
            conf = fp
            break
    labels = extract_navbar_labels(conf) if conf else []
    tags = ""
    if labels:
        spans = "".join(f"<span>{H.escape(l)}</span>" for l in labels)
        tags = f'<div class="card-tags">{spans}</div>'
    out += (
        f'<a class="card" href="{H.escape(p)}/">'
        f'<div class="card-name">{H.escape(p)}</div>'
        f"{tags}</a>\n"
    )

out += "</div>\n</div>\n</body>\n</html>\n"

output_path = os.path.join(public, "index.html")
with open(output_path, "w") as f:
    f.write(out)
print(f"Generated {output_path} with {len(projects)} projects")
