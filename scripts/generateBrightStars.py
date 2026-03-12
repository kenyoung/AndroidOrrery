#!/usr/bin/env python3
"""
Generate bright_stars.bin — positions and magnitudes of stars brighter than
magnitude 3.0, for the Android Orrery star map.

Fetches data from the Hipparcos catalog (I/239/hip_main) via VizieR,
precesses J2000 coordinates to J2025.0, and writes a compact binary file.

Binary output format (little-endian):
    uint16  starCount
    Repeated starCount times (12 bytes each):
        float32  ra   (hours, J2025.0)
        float32  dec  (degrees, J2025.0)
        float32  vmag (visual magnitude)
"""

import struct
import os
from astroquery.vizier import Vizier
from astropy.coordinates import SkyCoord, FK5
import astropy.units as u

ASSETS_DIR = '../app/src/main/assets'
MAG_LIMIT = 3.5


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    output_path = os.path.join(script_dir, ASSETS_DIR, 'bright_stars.bin')

    # Query Hipparcos catalog for bright stars
    print(f"Querying VizieR for Hipparcos stars with Vmag < {MAG_LIMIT}...")
    v = Vizier(columns=['RAICRS', 'DEICRS', 'Vmag', 'HIP'],
               column_filters={'Vmag': f'<{MAG_LIMIT}'},
               row_limit=-1)
    result = v.query_constraints(catalog='I/239/hip_main',
                                 Vmag=f'<{MAG_LIMIT}')

    if not result:
        print("ERROR: No results from VizieR query")
        return

    table = result[0]
    print(f"Retrieved {len(table)} stars")

    # Build SkyCoord from the catalog RA/Dec (J2000, ICRS)
    coords = SkyCoord(ra=table['RAICRS'], dec=table['DEICRS'],
                      unit=(u.deg, u.deg), frame='icrs')

    # Precess to J2025.0
    print("Precessing to J2025.0...")
    j2025 = coords.transform_to(FK5(equinox='J2025.0'))

    # Collect and sort by magnitude (brightest first)
    stars = []
    for i in range(len(table)):
        ra_hours = j2025[i].ra.hour
        dec_deg = j2025[i].dec.deg
        vmag = float(table['Vmag'][i])
        stars.append((ra_hours, dec_deg, vmag))

    stars.sort(key=lambda s: s[2])
    print(f"Brightest: mag {stars[0][2]:.2f}, faintest: mag {stars[-1][2]:.2f}")

    # Write binary
    star_count = len(stars)
    with open(output_path, 'wb') as f:
        f.write(struct.pack('<H', star_count))
        for ra, dec, vmag in stars:
            f.write(struct.pack('<fff', ra, dec, vmag))

    file_size = os.path.getsize(output_path)
    print(f"\nWrote {output_path}")
    print(f"  Stars: {star_count}")
    print(f"  Size:  {file_size} bytes ({2 + star_count * 12} expected)")


if __name__ == '__main__':
    main()
