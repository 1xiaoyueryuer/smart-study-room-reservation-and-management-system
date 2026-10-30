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
    if in_note or set(states) != {"submitted", "approved", "rejected"} or {edge[:2] for edge in edges} != {("[*]", "submitted"), ("submitted", "approved"), ("submitted", "rejected")} or len(edges) != 3:
        raise ValueError("SM-04 structure changed; update this renderer explicitly")
    return raw, states, edges, notes

def render():
    raw, states, edges, notes = model()
    font = Path("C:/Windows/Fonts/msyh.ttc")
    bold = Path("C:/Windows/Fonts/msyhbd.ttc")
    if not font.exists() or not bold.exists():
        raise FileNotFoundError("Existing Microsoft YaHei fonts required")
    fonts = {n: ImageFont.truetype(str(bold if n == 66 else font), n) for n in (28, 34, 38, 42, 50, 66)}
    image = Image.new("RGB", (2200, 1600), "#f8fafc")
    draw = ImageDraw.Draw(image)
    draw.text((100, 60), "SM-04 申诉审核状态图", font=fonts[66], fill="#173653")
    draw.text((100, 160), "FR-06.4 / UC-04 / BR-17 · ST-33/34/35 · 关联违规ST-36", font=fonts[34], fill="#475569")
    positions = {"submitted": (660, 410), "approved": (1720, 310), "rejected": (1720, 650)}
    colors = {"submitted": ("#e0efff", "#2563a6"), "approved": ("#e3f5eb", "#29845c"), "rejected": ("#fff0e7", "#b96735")}
    width, height = 520, 190
    draw.ellipse((110, 365, 200, 455), fill="#173653")
    for start, end, label in edges:
        if start == "[*]":
            points = [(200,410), (400,410)]
            text_at = (210,225)
        else:
            target_y = positions[end][1]
            points = [(920,410),(1180,410),(1180,target_y),(1460,target_y)]
            text_at = (1200, target_y-105)
        draw.line(points, fill="#2563a6", width=6)
        x,y=points[-1]
        draw.polygon([(x,y),(x-26,y-15),(x-26,y+15)], fill="#2563a6")
        parts=label.split(" ",1)
        for line_index, part in enumerate(parts):
            draw.text((text_at[0],text_at[1]+line_index*42),part,font=fonts[34],fill="#173653")
    for alias, (cx, cy) in positions.items():
        fill, outline=colors[alias]
        draw.rounded_rectangle((cx-width/2,cy-height/2,cx+width/2,cy+height/2),radius=25,fill=fill,outline=outline,width=4)
        for label, size, dy in ((states[alias],50,-60),(alias,38,20)):
            tw=draw.textlength(label,font=fonts[size])
            draw.text((cx-tw/2,cy+dy),label,font=fonts[size],fill="#173653")
    draw.rounded_rectangle((100,800,2100,1480),radius=20,fill="#fff8e6",outline="#b99132",width=3)
    y=835
    for note in notes:
        if draw.textlength(note,font=fonts[38])>1920:
            raise ValueError("Source note too wide; wrap source explicitly")
        draw.text((140,y),note,font=fonts[38],fill="#4c421e")
        y+=66
    if y>1480:
        raise ValueError("Source notes overflow")
    digest=hashlib.sha256(raw.encode()).hexdigest()[:12]
    draw.text((100,1530),f"本地Pillow直接解析源 · {len(states)}状态 / {len(edges)}转换 / {len(notes)}注释 · 源摘要 {digest}",font=fonts[28],fill="#64748b")
    OUTPUT.parent.mkdir(exist_ok=True)
    image.save(OUTPUT)
    print(f"SM-04 rendered: {image.width}x{image.height}; states={len(states)} transitions={len(edges)} notes={len(notes)} sha={digest}")

if __name__ == "__main__":
    render()
