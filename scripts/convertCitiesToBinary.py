#!/usr/bin/env python3
"""
Convert cities_over_100k.csv to a binary file for fast loading on Android.

Record format (64 bytes each, little-endian):
  - name:       48 bytes (UTF-8, zero-padded)
  - country:     2 bytes (ASCII country code, e.g. "US")
  - continent:   2 bytes (ASCII continent code, e.g. "NA")
  - latitude:    4 bytes (float32)
  - longitude:   4 bytes (float32)
  - population:  4 bytes (int32)

The file has no header. Records are in the same order as the input CSV
(assumed pre-sorted by population descending).
"""

import csv
import struct
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ASSETS_DIR = os.path.join(SCRIPT_DIR, '..', 'app', 'src', 'main', 'assets')
CSV_PATH = os.path.join(ASSETS_DIR, 'cities_over_100k.csv')
BIN_PATH = os.path.join(ASSETS_DIR, 'cities.bin')

NAME_SIZE = 48
RECORD_FMT = f'<{NAME_SIZE}s2s2sffi'  # little-endian
RECORD_SIZE = struct.calcsize(RECORD_FMT)
assert RECORD_SIZE == 64

count = 0
with open(CSV_PATH, encoding='utf-8') as fin, open(BIN_PATH, 'wb') as fout:
    reader = csv.reader(fin)
    next(reader)  # skip header
    for row in reader:
        name = row[0]
        country = row[1]
        continent = row[2]
        lat = float(row[3])
        lon = float(row[4])
        pop = int(row[5])

        name_bytes = name.encode('utf-8')[:NAME_SIZE].ljust(NAME_SIZE, b'\x00')
        country_bytes = country.encode('ascii')[:2].ljust(2, b'\x00')
        continent_bytes = continent.encode('ascii')[:2].ljust(2, b'\x00')

        fout.write(struct.pack(RECORD_FMT, name_bytes, country_bytes,
                               continent_bytes, lat, lon, pop))
        count += 1

print(f'Wrote {count} records ({count * RECORD_SIZE} bytes) to {BIN_PATH}')
