#!/usr/bin/env python3
"""
generateSolarEclipseCannon.py

Generates the solarEclipseCannon binary asset for the Android Orrery app
by fetching eclipse catalog data and Besselian elements from NASA's
Five Millennium Canon of Solar Eclipses (Fred Espenak).

Usage:
    python3 generateSolarEclipseCannon.py

Output:
    ../app/src/main/assets/solarEclipseCannon

Requires: Internet connection (fetches from eclipse.gsfc.nasa.gov)

Binary format per eclipse (110 bytes, little-endian):
    int32   date            YYYYMMDD packed (year*0x10000 + month*0x100 + day)
    float32 t0              Besselian element reference epoch T0 (seconds from 0h TD)
    int32   deltaT          Delta-T = TT - UT (seconds)
    int16   sarosNum        Saros cycle number
    byte    type            0=Partial, 1=Annular, 2=Total, 3=Hybrid
    float32 gamma           Shadow axis distance from Earth center (Earth radii)
    float32 magnitude       Greatest eclipse magnitude
    int16   pathWidthKm     Central path width (km), 0 for partial
    float32 centralDur      Central line duration (seconds), 0 for partial
    byte    padding         Alignment byte (0)
    float32 x[3]            Shadow axis x polynomial (fundamental plane, Earth radii)
    float32 y[3]            Shadow axis y polynomial
    float32 d[3]            Sun declination polynomial (degrees)
    float32 mu[3]           Greenwich hour angle polynomial (degrees)
    float32 l1[3]           Penumbral shadow radius polynomial (Earth radii)
    float32 l2[3]           Umbral shadow radius polynomial (Earth radii)
    float32 tanF1           Penumbral cone half-angle tangent
    float32 tanF2           Umbral cone half-angle tangent
"""

import struct
import re
import time
import os
import sys
from pathlib import Path
from urllib.request import urlopen, Request

SCRIPT_DIR = Path(__file__).parent
OUTPUT_PATH = SCRIPT_DIR / '..' / 'app' / 'src' / 'main' / 'assets' / 'solarEclipseCannon'
CACHE_DIR = SCRIPT_DIR / '.solar_eclipse_cache'
RECORD_SIZE = 110

MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
          'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

# Type codes matching SolarEclipseScreen.kt
TYPE_PARTIAL = 0
TYPE_ANNULAR = 1
TYPE_TOTAL = 2
TYPE_HYBRID = 3

# NASA catalog pages (Besselian element pages only exist through 2100)
CATALOG_URLS = [
    'https://eclipse.gsfc.nasa.gov/SEcat5/SE1901-2000.html',
    'https://eclipse.gsfc.nasa.gov/SEcat5/SE2001-2100.html',
]


def get_beselm_dir(year):
    """Get NASA Besselian elements directory name for a given year.

    NASA uses 50-year directories: SEbeselm1901 (1901-1950),
    SEbeselm1951 (1951-2000), SEbeselm2001 (2001-2050), etc.
    """
    dir_year = (year - 1) // 50 * 50 + 1
    return f'SEbeselm{dir_year}'


def fetch_url(url, cache_key=None, retries=3):
    """Fetch URL content with optional file caching and retries."""
    if cache_key:
        cache_file = CACHE_DIR / f'{cache_key}.html'
        if cache_file.exists():
            return cache_file.read_text(encoding='utf-8')

    for attempt in range(retries):
        try:
            req = Request(url, headers={
                'User-Agent': 'AndroidOrrery/1.0 (eclipse data generator)'
            })
            with urlopen(req, timeout=30) as resp:
                content = resp.read().decode('utf-8', errors='replace')
            if cache_key:
                CACHE_DIR.mkdir(parents=True, exist_ok=True)
                cache_file = CACHE_DIR / f'{cache_key}.html'
                cache_file.write_text(content, encoding='utf-8')
            time.sleep(0.5)  # rate limit
            return content
        except Exception as e:
            if attempt == retries - 1:
                print(f'  ERROR fetching {url}: {e}')
                return None
            time.sleep(2 * (attempt + 1))
    return None


def parse_catalog_page(html):
    """Parse a NASA catalog HTML page into a list of eclipse dicts.

    Each entry has: cat, year, month, month_str, day, td_hh, td_mm, td_ss,
    delta_t, saros, type_str, type_code, gamma, magnitude, width_km,
    duration_s
    """
    eclipses = []
    # Strip HTML tags to get plain text
    text = re.sub(r'<[^>]+>', ' ', html)

    for line in text.split('\n'):
        line = line.strip()
        if not line or not line[0:1].isdigit():
            continue

        # Match catalog entry lines
        # Format: 09511  2001 Jun 21  12:04:46  64  18  127  T  -p  -0.5701  1.0495  11S  3E  55  200  04m57s
        m = re.match(
            r'(\d{5})\s+'                                    # catalog number
            r'(-?\d{4})\s+'                                  # year
            r'(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+'  # month
            r'(\d{1,2})\s+'                                  # day
            r'(\d{2}):(\d{2}):(\d{2})\s+'                   # HH:MM:SS (TD)
            r'(-?\d+)\s+'                                    # delta-T
            r'(-?\d+)\s+'                                    # luna number
            r'(\d+)\s+'                                      # saros number
            r'([A-Za-z][A-Za-z+\-]*)\s+'                    # type string
            r'([a-z\-]+)\s+'                                 # QLE
            r'(-?[\d.]+)\s+'                                 # gamma
            r'([\d.]+)'                                      # magnitude
            , line
        )
        if not m:
            continue

        cat = int(m.group(1))
        year = int(m.group(2))
        month_str = m.group(3)
        day = int(m.group(4))
        hh, mm, ss = int(m.group(5)), int(m.group(6)), int(m.group(7))
        delta_t = int(m.group(8))
        saros = int(m.group(10))
        type_str = m.group(11)
        gamma = float(m.group(13))
        mag = float(m.group(14))

        month = MONTHS.index(month_str) + 1
        base = type_str[0].upper()

        if base == 'T':
            type_code = TYPE_TOTAL
        elif base == 'A':
            type_code = TYPE_ANNULAR
        elif base == 'H':
            type_code = TYPE_HYBRID
        else:
            type_code = TYPE_PARTIAL

        # Parse remaining fields after magnitude: lat lon alt width duration
        rest = line[m.end():].strip()
        parts = rest.split()
        width_km = 0
        duration_s = 0.0

        # Width is at index 2 (after lat, lon, alt), duration at index 3-4
        if len(parts) >= 4:
            w_str = parts[2]
            if w_str not in ('\u2014', '-', '\u2013'):
                try:
                    width_km = int(w_str)
                except ValueError:
                    pass

        if len(parts) >= 5:
            d_str = parts[-1]
            dm = re.match(r'(\d+)m(\d+)s', d_str)
            if dm:
                duration_s = float(int(dm.group(1)) * 60 + int(dm.group(2)))

        eclipses.append({
            'cat': cat,
            'year': year,
            'month': month,
            'month_str': month_str,
            'day': day,
            'td_hh': hh, 'td_mm': mm, 'td_ss': ss,
            'delta_t': delta_t,
            'saros': saros,
            'type_str': type_str,
            'type_code': type_code,
            'gamma': gamma,
            'magnitude': mag,
            'width_km': width_km,
            'duration_s': duration_s,
        })

    return eclipses


def build_besselian_url(eclipse):
    """Build the URL for a NASA Besselian element page."""
    year = eclipse['year']
    month_str = eclipse['month_str']
    day = eclipse['day']
    type_char = eclipse['type_str'][0].upper()
    beselm_dir = get_beselm_dir(year)

    return (f'https://eclipse.gsfc.nasa.gov/SEbeselm/{beselm_dir}/'
            f'SE{year}{month_str}{day:02d}{type_char}beselm.html')


def parse_besselian_elements(html):
    """Parse a NASA Besselian element page.

    Returns dict with: t0_seconds, coeffs (dict of 6 arrays), tanF1, tanF2.
    Returns None on parse failure.
    """
    if not html:
        return None

    result = {}

    # Find T0 reference time
    # Format 1: "06:00:00.0 TDT  (=t0)" in the polynomial header line
    t0_match = re.search(
        r'(\d{1,2}):(\d{2}):(\d{2})(?:\.\d+)?\s+TDT\s+\(=t0\)', html)
    if t0_match:
        h = int(t0_match.group(1))
        m = int(t0_match.group(2))
        s = int(t0_match.group(3))
        result['t0_seconds'] = h * 3600 + m * 60 + s
    else:
        # Format 2: "t0 =  6.000 TDT" in the formula explanation
        t0_match2 = re.search(r't0\s*=\s*([\d.]+)\s*TDT', html)
        if t0_match2:
            t0_hours = float(t0_match2.group(1))
            result['t0_seconds'] = t0_hours * 3600
        else:
            return None  # T0 is essential

    # Initialize polynomial coefficient arrays
    coeffs = {
        'x': [0.0, 0.0, 0.0],
        'y': [0.0, 0.0, 0.0],
        'd': [0.0, 0.0, 0.0],
        'mu': [0.0, 0.0, 0.0],
        'l1': [0.0, 0.0, 0.0],
        'l2': [0.0, 0.0, 0.0],
    }

    # Find polynomial coefficient rows
    # Format: "  0   -0.318157    0.219747     7.58620     0.535813   -0.010274    89.59122"
    # Row n=0 has 6 values: x0 y0 d0 l1_0 l2_0 mu0
    # Row n=1 has 6 values: x1 y1 d1 l1_1 l2_1 mu1
    # Row n=2 has 5-6 values: x2 y2 d2 l1_2 l2_2 [mu2]
    # Row n=3 has 2 values: x3 y3 (ignored - we only use quadratic)
    # Use [ \t]+ (not \s+) to avoid matching across newlines
    rows = re.findall(
        r'^[ \t]*([0-3])[ \t]+([-+]?[\d.]+(?:[ \t]+[-+]?[\d.]+)*)',
        html, re.MULTILINE
    )

    parsed_rows = 0
    for row_idx_str, values_str in rows:
        idx = int(row_idx_str)
        if idx > 2:
            continue
        vals = [float(v) for v in values_str.split()]

        if idx == 0 and len(vals) >= 6:
            coeffs['x'][0] = vals[0]
            coeffs['y'][0] = vals[1]
            coeffs['d'][0] = vals[2]
            coeffs['l1'][0] = vals[3]
            coeffs['l2'][0] = vals[4]
            coeffs['mu'][0] = vals[5]
            parsed_rows += 1
        elif idx == 1 and len(vals) >= 6:
            coeffs['x'][1] = vals[0]
            coeffs['y'][1] = vals[1]
            coeffs['d'][1] = vals[2]
            coeffs['l1'][1] = vals[3]
            coeffs['l2'][1] = vals[4]
            coeffs['mu'][1] = vals[5]
            parsed_rows += 1
        elif idx == 2:
            if len(vals) >= 1: coeffs['x'][2] = vals[0]
            if len(vals) >= 2: coeffs['y'][2] = vals[1]
            if len(vals) >= 3: coeffs['d'][2] = vals[2]
            if len(vals) >= 4: coeffs['l1'][2] = vals[3]
            if len(vals) >= 5: coeffs['l2'][2] = vals[4]
            if len(vals) >= 6: coeffs['mu'][2] = vals[5]
            parsed_rows += 1

    if parsed_rows < 2:
        return None  # need at least rows 0 and 1

    result['coeffs'] = coeffs

    # Find tan f1 and tan f2
    # NASA uses &#402; (ƒ) or plain f in different pages
    tf1 = re.search(r'[Tt]an\s*\S*1\s*=\s*([\d.]+)', html)
    tf2 = re.search(r'[Tt]an\s*\S*2\s*=\s*([\d.]+)', html)
    result['tanF1'] = float(tf1.group(1)) if tf1 else 0.0046750
    result['tanF2'] = float(tf2.group(1)) if tf2 else 0.0046513

    return result


def write_binary(eclipses, output_path):
    """Write the binary cannon file.

    Each record is RECORD_SIZE bytes, little-endian.
    Only eclipses with successfully parsed Besselian elements are written.
    """
    written = 0
    with open(output_path, 'wb') as f:
        for e in eclipses:
            be = e.get('besselian')
            if not be:
                continue

            c = be['coeffs']
            date = e['year'] * 0x10000 + e['month'] * 0x100 + e['day']

            record = struct.pack('<i', date)                         # date
            record += struct.pack('<f', float(be['t0_seconds']))     # t0
            record += struct.pack('<i', e['delta_t'])                # deltaT
            record += struct.pack('<h', e['saros'])                  # sarosNum
            record += struct.pack('<b', e['type_code'])              # type
            record += struct.pack('<f', e['gamma'])                  # gamma
            record += struct.pack('<f', e['magnitude'])              # magnitude
            record += struct.pack('<h', e['width_km'])               # pathWidthKm
            record += struct.pack('<f', e['duration_s'])             # centralDuration
            record += struct.pack('<b', 0)                           # padding

            # Polynomial coefficients: x, y, d, mu, l1, l2 (3 floats each)
            for key in ['x', 'y', 'd', 'mu', 'l1', 'l2']:
                for j in range(3):
                    record += struct.pack('<f', c[key][j])

            record += struct.pack('<f', be['tanF1'])                 # tanF1
            record += struct.pack('<f', be['tanF2'])                 # tanF2

            assert len(record) == RECORD_SIZE, \
                f"Record size {len(record)} != {RECORD_SIZE}"
            f.write(record)
            written += 1

    return written


def main():
    print("Solar Eclipse Cannon Generator")
    print("=" * 50)

    all_eclipses = []

    # Step 1: Fetch and parse catalog pages
    print("\nStep 1: Fetching eclipse catalog data...")
    for url in CATALOG_URLS:
        # Derive cache key from URL filename
        cache_key = 'catalog_' + url.split('/')[-1].replace('.html', '')
        html = fetch_url(url, cache_key=cache_key)
        if html:
            eclipses = parse_catalog_page(html)
            print(f"  Parsed {len(eclipses)} eclipses from {url.split('/')[-1]}")
            all_eclipses.extend(eclipses)
        else:
            print(f"  FAILED to fetch catalog from {url}")
            sys.exit(1)

    print(f"\nTotal catalog eclipses: {len(all_eclipses)}")

    # Step 2: Fetch Besselian elements for each eclipse
    print("\nStep 2: Fetching Besselian elements...")
    success = 0
    failed = 0
    for i, eclipse in enumerate(all_eclipses):
        url = build_besselian_url(eclipse)
        cache_key = (f"beselm_{eclipse['year']}_{eclipse['month']:02d}_"
                     f"{eclipse['day']:02d}_{eclipse['type_str']}")

        html = fetch_url(url, cache_key=cache_key)
        be = parse_besselian_elements(html)

        if be:
            eclipse['besselian'] = be
            success += 1
        else:
            failed += 1
            print(f"  WARNING: No Besselian elements for "
                  f"{eclipse['year']}-{eclipse['month']:02d}-{eclipse['day']:02d} "
                  f"({eclipse['type_str']})")

        if (i + 1) % 50 == 0:
            print(f"  Progress: {i + 1}/{len(all_eclipses)} "
                  f"(success={success}, failed={failed})")

    print(f"\n  Besselian elements: {success} parsed, {failed} failed")

    # Step 3: Write binary cannon
    print("\nStep 3: Writing binary cannon...")
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    n_written = write_binary(all_eclipses, OUTPUT_PATH)

    file_size = OUTPUT_PATH.stat().st_size
    print(f"\nWrote {n_written} eclipses ({file_size:,} bytes) to {OUTPUT_PATH}")
    print(f"Record size: {RECORD_SIZE} bytes")
    print(f"Expected size: {n_written} x {RECORD_SIZE} = {n_written * RECORD_SIZE:,}")

    # Print N_SOLAR_ECLIPSES constant for Kotlin code
    print(f"\nKotlin constant: private const val N_SOLAR_ECLIPSES = {n_written}")

    print("\nDone!")


if __name__ == '__main__':
    main()
