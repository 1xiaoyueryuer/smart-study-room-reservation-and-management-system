"""Reproduce SM-04 PNG from its editable Mermaid block using local Pillow/CJK fonts.
Unsupported syntax fails rather than silently omitting a transition. No network or imagegen.
"""
from pathlib import Path
import hashlib
import re
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "模型源与图件.md"
OUTPUT = ROOT / "图件" / "SM-04.png"

def model():
    document = SOURCE.read_text(encoding="utf-8")
    section = document.split("## SM-04 ", 1)[1].split("## AD-01 ", 1)[0]
    match = re.search(r"```mermaid\s*(stateDiagram-v2.*?)```", section, re.S)
    if not match:
        raise ValueError("Missing SM-04 Mermaid")
    raw = match.group(1).strip()
    states, edges, notes = {}, [], []
    in_note = False
    for line in raw.splitlines():
        line = line.strip()
        if not line or line == "stateDiagram-v2":
            continue
        if line == "end note":
            if not in_note:
                raise ValueError("Unexpected end note")
            in_note = False
            continue
        if in_note:
            notes.append(line)
            continue
        found = re.fullmatch(r'state "([^"]+)" as (\w+)', line)
        if found:
            states[found.group(2)] = found.group(1)
            continue
        found = re.fullmatch(r'(\[\*\]|\w+) --> (\w+): (.+)', line)
        if found:
            edges.append(found.groups())
            continue
        if line == "note right of submitted":
            in_note = True
            continue
        raise ValueError("Unsupported Mermaid line: " + line)
    if in_note or set(states) != {"submitted"} or len(edges) != 1 or edges[0][:2] != ("[*]", "submitted"):
        raise ValueError("SM-04 structure changed; update this renderer explicitly")
    return raw, states, edges, notes

def render():
    raw, states, edges, notes = model()
    font = Path("C:/Windows/Fonts/msyh.ttc")
    bold = Path("C:/Windows/Fonts/msyhbd.ttc")
    if not font.exists() or not bold.exists():
        raise FileNotFoundError("Existing Microsoft YaHei fonts required")
    fonts = {n: ImageFont.truetype(str(bold if n == 62 else font), n) for n in (28, 34, 38, 42, 50, 62)}
    image = Image.new("RGB", (1800, 1180), "#f8fafc")
    draw = ImageDraw.Draw(image)
    draw.text((100, 70), "SM-04 申诉提交状态图", font=fonts[62], fill="#173653")
    draw.text((100, 170), "FR-06.4 / UC-04 / BR-17 / SC-05 / ST-33 · S21提交范围", font=fonts[34], fill="#475569")
    draw.ellipse((220, 385, 310, 475), fill="#173653")
    draw.line((310, 430, 800, 430), fill="#2563a6", width=7)
    draw.polygon([(800, 430), (770, 413), (770, 447)], fill="#2563a6")
    draw.text((340, 340), edges[0][2], font=fonts[38], fill="#173653")
    draw.rounded_rectangle((800, 300, 1540, 540), radius=28, fill="#e0efff", outline="#2563a6", width=5)
    label = states["submitted"]
    width = draw.textlength(label, font=fonts[50])
    draw.text((1170-width/2, 350), label, font=fonts[50], fill="#173653")
    width = draw.textlength("submitted", font=fonts[38])
    draw.text((1170-width/2, 435), "submitted", font=fonts[38], fill="#475569")
    draw.rounded_rectangle((100, 630, 1700, 1030), radius=20, fill="#fff8e6", outline="#b99132", width=3)
    y = 665
    for note in notes:
        if draw.textlength(note, font=fonts[38]) > 1500:
            raise ValueError("Source note too wide; wrap source explicitly")
        draw.text((140, y), note, font=fonts[38], fill="#4c421e")
        y += 65
    if y > 1030:
        raise ValueError("Source notes overflow")
    digest = hashlib.sha256(raw.encode()).hexdigest()[:12]
    draw.text((100, 1095), f"本地Pillow直接解析可编辑源 · 1状态 / 1转换 / {len(notes)}注释 · 源摘要 {digest}", font=fonts[28], fill="#64748b")
    OUTPUT.parent.mkdir(exist_ok=True)
    image.save(OUTPUT)
    print(f"SM-04 rendered: {image.width}x{image.height}; state=1 transition=1 notes={len(notes)} sha={digest}")

if __name__ == "__main__":
    render()
