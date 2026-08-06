#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打印 v4 两个'传到公共变量'code 节点的代码 + 输入变量"""
import re

content = open(r'E:\Desktop\AI-storyboard\AIStoryboardDify\Moon智能体v4.yml', encoding='utf-8').read()
idx = content.find('    nodes:')
end = content.find('    edges:', idx)
seg = content[idx:end]

# 按节点块切分（- data: 开头）
blocks = re.split(r'\n    - data:', seg)
for b in blocks:
    m = re.search(r'title: (.*)', b)
    if not m or '传到公共变量' not in m.group(1):
        continue
    title = m.group(1).strip()
    print('=' * 20, title)
    # code 字段
    cm = re.search(r'code: \|(.*?)\n(?=\s+[a-z_]+:|\Z)', b, re.S)
    if cm:
        print('--- code ---')
        print(cm.group(1))
    # 输入变量
    print('--- 输入变量 ---')
    for v in re.finditer(r'- variable: (\w+)\n\s+value_selector:\n\s+- \'(\d+)\'\n\s+- (\w+)', b):
        print(f'  {v.group(1)} <- {v.group(2)}.{v.group(3)}')
    for v in re.finditer(r"- variable: (\w+)\n\s+value_selector:\n\s+- conversation\n\s+- (\w+)", b):
        print(f'  {v.group(1)} <- conversation.{v.group(2)}')
    print()
