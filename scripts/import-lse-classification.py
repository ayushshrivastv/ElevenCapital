#!/usr/bin/env python3
"""Refresh the GB share classification snapshot from an official LSE instrument workbook.

Usage: python3 scripts/import-lse-classification.py workbook.xlsx official_download_url
Downloads and their publication date must be retained as evidence; this imports metadata, never prices.
"""
import json
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

workbook, source = sys.argv[1:]
if not source.startswith('https://docs.londonstockexchange.com/'):
    raise ValueError('Expected official LSE document URL')
ns = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
records = {}
publication = None
with zipfile.ZipFile(workbook) as archive:
    strings = [''.join(row.itertext()) for row in ET.fromstring(archive.read('xl/sharedStrings.xml')).findall('m:si', ns)]
    with archive.open('xl/worksheets/sheet1.xml') as sheet:
        for _, row in ET.iterparse(sheet, events=['end']):
            if not row.tag.endswith('}row'):
                continue
            values = {}
            for cell in row:
                value = cell.find('m:v', ns)
                text = value.text if value is not None else ''
                values[re.sub(r'\d', '', cell.attrib['r'])] = strings[int(text)] if cell.attrib.get('t') == 's' and text else text
            if (values.get('A') or '').startswith('As at '):
                publication = values['A']
            if values.get('E') == 'SHRS' and re.fullmatch(r'[A-Z]{2}[A-Z0-9]{9}\d', values.get('D') or ''):
                isin = values['D']
                records[isin] = {'isin': isin, 'name': values['B'], 'country': 'GB', 'kind': 'company_share',
                    'reason': f"LSE MiFIR SHRS (Shares): {values['B']} — {values['C']}; {publication}.", 'source': source}
            row.clear()
if len(records) < 500 or not publication:
    raise ValueError('Official workbook layout changed or share classification is incomplete')
target = Path(__file__).resolve().parents[1] / 'backend/src/foreign-classification-directory.json'
data = json.loads(target.read_text())
data['records'] = [row for row in data['records'] if row['country'] != 'GB'] + list(records.values())
data['sources'] = list(dict.fromkeys(data['sources'] + [source]))
target.write_text(json.dumps(data, separators=(',', ':')) + '\n')
print(json.dumps({'country': 'GB', 'shareISINs': len(records), 'publication': publication, 'source': source}))
