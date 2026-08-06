#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""定位 yml 里 1785307777685 节点 + 引用它的位置"""
import re

def main(path):
    content = open(path, encoding='utf-8').read()
    # 找所有节点块
    node_blocks = re.findall(r'    - id: (\d+)\n(.*?)(?=\n    - id: |\n    edges:)', content, re.S)
    node_names = {}
    for nid, body in node_blocks:
        m = re.search(r'title: (.*)', body)
        name = m.group(1).strip() if m else '?'
        node_names[nid] = name
        if nid == '1785307777685':
            print(f"=== 目标节点 1785307777685: {name} ===")
            # 打印该节点的类型和输出相关配置
            tm = re.search(r'type: (\w+)', body)
            print("type:", tm.group(1) if tm else '?')
            # structured_output 相关
            for kw in ['structured_output', 'message']:
                for k in re.finditer(r'[^\n]*' + kw + r'[^\n]*', body):
                    print("  ", k.group(0).strip()[:120])
    print("\n=== 引用 1785307777685 的位置 ===")
    for m in re.finditer(r'[^\n]*1785307777685[^\n]*', content):
        line = m.group(0).strip()
        if 'id:' in line and '1785307777685' in line:
            continue
        print("  ", line[:160])

if __name__ == '__main__':
    main(r'E:\Desktop\AI-storyboard\AIStoryboardDify\Moon智能体v4.yml')
