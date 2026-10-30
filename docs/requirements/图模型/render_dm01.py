"""DM-01 local reproducible renderer: parse the editable Mermaid source, not a second graph.

Requires Pillow and an existing CJK font; no network, installation or image-generation service.
Only the DM-01 syntax in the paired Markdown is supported. Unsupported syntax fails explicitly.
"""
from __future__ import annotations
import hashlib
import math
from pathlib import Path
import re
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "模型源与图件.md"
OUTPUT = ROOT / "图件" / "DM-01.png"
SIZE = (3120, 2520)
BOX = (440, 200)
LAYOUT = {
    "自习空间": (130, 260), "座位": (130, 930),
    "投诉": (800, 260), "预约": (800, 930), "评价": (800, 1570),
    "用户": (1440, 260), "违规": (1440, 1410), "申诉": (1440, 1920),
    "预约限制": (2320, 930),
}

def read_model():
    document = SOURCE.read_text(encoding="utf-8")
    section = document.split("## DM-01 ", 1)[1].split("## SM-01 ", 1)[0]
    match = re.search(r"```mermaid\s*(classDiagram.*?)```", section, re.S)
    if not match:
        raise ValueError("DM-01 Mermaid block missing")
    model = match.group(1).strip()
    classes, edges, notes = {}, [], []
    for raw in model.splitlines():
        line = raw.strip()
        if not line or line == "classDiagram":
            continue
        if line.startswith("%% DM01-NOTE:"):
            notes.append(line.split(":", 1)[1].strip()); continue
        if line.startswith("%%"):
            continue
        declaration = re.fullmatch(r"class\s+(\S+)", line)
        if declaration:
            name = declaration.group(1)
            if name in classes: raise ValueError("Duplicate class: " + name)
            classes[name] = []; continue
        association = re.fullmatch(r'(\S+)\s+"([^\"]+)"\s+--\s+"([^\"]+)"\s+(\S+)(?:\s*:\s*(.+))?', line)
        if association:
            edges.append(association.groups()); continue
        attribute = re.fullmatch(r"(\S+)\s*:\s*(.+)", line)
        if attribute and attribute.group(1) in classes:
            classes[attribute.group(1)].append(attribute.group(2)); continue
        raise ValueError("Unsupported DM-01 syntax: " + line)
    if set(classes) != set(LAYOUT):
        raise ValueError("Update layout for the exact source class set")
    if any(a not in classes or b not in classes for a, _, _, b, _ in edges):
        raise ValueError("Association references undeclared class")
    if len(notes) != 2: raise ValueError("DM-01 must retain both source cardinality notes")
    return model, classes, edges, notes

def fonts():
    regular = next((p for p in [Path("C:/Windows/Fonts/msyh.ttc"), Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")] if p.exists()), None)
    if regular is None: raise RuntimeError("An existing CJK font is required; no font is downloaded")
    bold = Path("C:/Windows/Fonts/msyhbd.ttc")
    if not bold.exists(): bold = regular
    return regular, bold

def point_on_box(name, other):
    x, y = LAYOUT[name]; ox, oy = LAYOUT[other]
    cx, cy = x + BOX[0]/2, y + BOX[1]/2
    dx, dy = ox + BOX[0]/2 - cx, oy + BOX[1]/2 - cy
    factor = min(BOX[0]/2/abs(dx) if dx else math.inf, BOX[1]/2/abs(dy) if dy else math.inf)
    return (cx + factor*dx, cy + factor*dy)

def render():
    model, classes, edges, notes = read_model()
    regular, bold = fonts()
    font = lambda n, strong=False: ImageFont.truetype(str(bold if strong else regular), n)
    image = Image.new("RGB", SIZE, "#F8FAFC"); draw = ImageDraw.Draw(image)
    navy, ink, line, teal = "#17324D", "#233D56", "#6B829B", "#0F766E"
    draw.text((100, 58), "DM-01 核心领域类图", fill=navy, font=font(64, True))
    draw.text((100, 148), "配套规格 v0.25 · S20 新增自动处罚来源关联；其他核心类与属性保持原源", fill="#536C83", font=font(31))
    labels = []
    for a, left, right, b, caption in edges:
        p, q = point_on_box(a, b), point_on_box(b, a)
        highlight = caption == "自动处罚来源"
        color = teal if highlight else line
        draw.line([p, q], fill=color, width=8 if highlight else 5)
        dx, dy = q[0]-p[0], q[1]-p[1]; length = math.hypot(dx,dy)
        normal = (-dy/length, dx/length)
        amount = .30 if length <= 260 else .22 if length <= 650 else .15
        for value, t in [(left, amount), (right, 1-amount)]:
            labels.append((value, (p[0]+dx*t+normal[0]*38, p[1]+dy*t+normal[1]*38), color, 34, False))
        if caption:
            labels.append((caption, ((p[0]+q[0])/2+normal[0]*73, (p[1]+q[1])/2+normal[1]*73), color, 38, True))
    for name, attributes in classes.items():
        x, y = LAYOUT[name]; w, h = BOX
        if 77 + len(attributes)*43 + 28 > h: raise ValueError("Attributes exceed node height: " + name)
        emphasized = name in {"违规", "预约限制"}
        draw.rounded_rectangle([x,y,x+w,y+h], radius=19, fill="#F0FDFA" if emphasized else "white", outline=teal if emphasized else "#7A96B0", width=4)
        draw.line([(x+2,y+76),(x+w-2,y+76)], fill="#B8CCDB", width=2)
        draw.text((x+w/2,y+38), name, anchor="mm", fill=navy, font=font(42, True))
        for index, attribute in enumerate(attributes):
            if draw.textlength(attribute, font=font(29)) > w-48: raise ValueError("Attribute needs layout width: " + attribute)
            draw.text((x+24,y+96+index*43), attribute, fill=ink, font=font(29))
    for value, point, color, size, strong in labels:
        face = font(size, strong)
        box = draw.textbbox(point, value, font=face, anchor="mm")
        draw.rounded_rectangle((box[0]-8,box[1]-5,box[2]+8,box[3]+5), radius=7, fill="#F8FAFC")
        draw.text(point, value, anchor="mm", font=face, fill=color)
    draw.rounded_rectangle([100,2220,3020,2430], radius=18, fill="#EAF7F4", outline="#71AAA1", width=3)
    draw.text((125,2243), "基数与永久消费约束（0..3 为混合来源范围）", font=font(34, True), fill=teal)
    for index, note in enumerate(notes):
        draw.text((125,2300+index*48), note, font=font(30), fill=ink)
    digest = hashlib.sha256(model.encode("utf-8")).hexdigest()[:12]
    draw.text((100,2460), "由 render_dm01.py 从同页 DM-01 Mermaid 源逐一读取生成 · 源摘要 " + digest, font=font(23), fill="#627A91")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT, "PNG", optimize=True)
    print(f"Rendered DM-01: classes={len(classes)}, associations={len(edges)}, source={digest}, size={SIZE}")

if __name__ == "__main__":
    render()
