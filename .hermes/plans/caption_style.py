# -*- coding: utf-8 -*-
"""Pass 4 (caption style 统一): 全部图题/表题 -> 宋体 + 五号(10.5pt) + 居中 + 黑色。
作用于 v3，原地更新（幂等）。不改结构/图片。"""
import re
from docx import Document
from docx.shared import Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

SRC = OUT = r"E:\Desktop\项目计划书3_去AI版.docx"
d = Document(SRC)

CAP = re.compile(r"^(图|表)\s*[\dA-Z]+[\dA-Z\.]*\s")


def has_drawing(r):
    return r._r.find(qn("w:drawing")) is not None or r._r.find(qn("w:pict")) is not None


def style_para(p):
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for r in p.runs:
        if has_drawing(r):
            continue
        r.font.name = "SimSun"             # ascii / hAnsi
        r.font.size = Pt(10.5)             # 五号
        r.font.bold = False
        r.font.color.rgb = RGBColor(0, 0, 0)
        rPr = r._element.get_or_add_rPr()
        rf = rPr.get_or_add_rFonts()
        rf.set(qn("w:eastAsia"), "宋体")
        rf.set(qn("w:ascii"), "SimSun")
        rf.set(qn("w:hAnsi"), "SimSun")


count = 0
for p in d.paragraphs:
    if CAP.match(p.text.strip()):
        style_para(p)
        count += 1

d.save(OUT)
print("PASS4 caption style unified. captions styled:", count)
