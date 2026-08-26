# -*- coding: utf-8 -*-
"""통합 명세서 .docx를 저장소 기준 Markdown으로 변환한다.

기준 문서는 Markdown이다(docs/specifications/README.md "기준 형식" 절). Google Docs·Word에서
개정하면 이 스크립트로 변환한 결과를 커밋하고, .docx 자체는 커밋하지 않는다(.gitignore).
변환을 손으로 하면 사람마다 결과가 달라져 diff가 개정 내용이 아니라 변환기 차이를 보여주므로,
변환 경로를 저장소에 고정해 둔다.

    python scripts/docx_to_markdown.py <입력.docx> <출력.md>

표준 라이브러리만 쓴다(pandoc·python-docx 불필요). 변환 범위는 제목(Heading1~4)·문단·표·
글머리표·굵게/기울임/고정폭이다. 표는 GFM 표로, 셀 안 줄바꿈은 <br>로 옮긴다.
"""
import sys, zipfile, re
import xml.etree.ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
BS = chr(92)


def q(t):
    return W + t


def esc(s):
    return s.replace(BS, BS + BS).replace('|', BS + '|')


def run_text(r):
    parts = []
    for node in r.iter():
        if node.tag == q('t'):
            parts.append(node.text or '')
        elif node.tag == q('tab'):
            parts.append('\t')
        elif node.tag == q('br'):
            parts.append('\n')
    return ''.join(parts)


def run_props(r):
    rPr = r.find(q('rPr'))
    bold = italic = mono = False
    if rPr is not None:
        b = rPr.find(q('b'))
        bold = b is not None and b.get(q('val'), '1') not in ('0', 'false')
        i = rPr.find(q('i'))
        italic = i is not None and i.get(q('val'), '1') not in ('0', 'false')
        f = rPr.find(q('rFonts'))
        if f is not None:
            name = (f.get(q('ascii')) or '') + (f.get(q('hAnsi')) or '')
            mono = any(k in name for k in ('Mono', 'Consol', 'Courier', 'Code'))
    return bold, italic, mono


def para_inline(p):
    segs = []
    for child in p:
        if child.tag == q('r'):
            runs = [child]
        elif child.tag == q('hyperlink'):
            runs = child.findall(q('r'))
        else:
            continue
        for r in runs:
            t = run_text(r)
            if not t:
                continue
            b, i, m = run_props(r)
            if segs and segs[-1][:3] == (b, i, m):
                segs[-1][3] += t
            else:
                segs.append([b, i, m, t])
    out = []
    for b, i, m, t in segs:
        if not t.strip():
            out.append(t)
            continue
        lead = len(t) - len(t.lstrip())
        trail = len(t) - len(t.rstrip())
        pre = t[:lead]
        core = t[lead:len(t) - trail] if trail else t[lead:]
        post = t[len(t) - trail:] if trail else ''
        if m:
            core = '`' + core.replace('`', '') + '`'
        else:
            core = esc(core)
            if b and i:
                core = '***' + core + '***'
            elif b:
                core = '**' + core + '**'
            elif i:
                core = '*' + core + '*'
        out.append(pre + core + post)
    return ''.join(out).strip()


def heading_level(p):
    pPr = p.find(q('pPr'))
    if pPr is None:
        return 0
    st = pPr.find(q('pStyle'))
    if st is None:
        return 0
    v = st.get(q('val')) or ''
    m = re.fullmatch(r'Heading(\d)', v)
    if m:
        return int(m.group(1))
    if v == 'Title':
        return 1
    return 0


def list_level(p):
    pPr = p.find(q('pPr'))
    if pPr is None:
        return None
    numPr = pPr.find(q('numPr'))
    if numPr is None:
        return None
    ilvl = numPr.find(q('ilvl'))
    return int(ilvl.get(q('val'))) if ilvl is not None else 0


def cell_text(tc):
    bits = []
    for p in tc.iter(q('p')):
        s = para_inline(p)
        if s:
            bits.append(s)
    return '<br>'.join(bits).replace('\n', ' ')


def table_md(tbl):
    rows = []
    for tr in tbl.findall(q('tr')):
        rows.append([cell_text(tc) for tc in tr.findall(q('tc'))])
    if not rows:
        return ''
    width = max(len(r) for r in rows)
    rows = [r + [''] * (width - len(r)) for r in rows]
    lines = ['| ' + ' | '.join(rows[0]) + ' |',
             '|' + '|'.join(['---'] * width) + '|']
    for r in rows[1:]:
        lines.append('| ' + ' | '.join(r) + ' |')
    return '\n'.join(lines)


def convert(path):
    z = zipfile.ZipFile(path)
    root = ET.fromstring(z.read('word/document.xml'))
    body = root.find(q('body'))
    out = []
    for el in body:
        if el.tag == q('p'):
            lvl = heading_level(el)
            txt = para_inline(el)
            li = list_level(el)
            if lvl:
                if txt:
                    out.append('#' * lvl + ' ' + txt.strip('*'))
            elif li is not None:
                if txt:
                    out.append('  ' * li + '- ' + txt)
            else:
                if txt:
                    out.append(txt)
        elif el.tag == q('tbl'):
            t = table_md(el)
            if t:
                out.append(t)
    res = []
    for i, blk in enumerate(out):
        if i and not (blk.lstrip().startswith('- ') and res[-1].lstrip().startswith('- ')):
            res.append('')
        res.append(blk)
    text = '\n'.join(res)
    text = re.sub(r'\n{3,}', '\n\n', text)
    return text.strip() + '\n'


if __name__ == '__main__':
    md = convert(sys.argv[1])
    open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(md)
    print('lines:', md.count('\n') + 1, 'chars:', len(md))
