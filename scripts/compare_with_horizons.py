#!/usr/bin/env python3
"""
Compare Orrery test data export with JPL Horizons results.

Usage: python compare_with_horizons.py <orrery_test_file.txt>

Reads the test data file written by the app's "Export Test Data" feature,
queries JPL Horizons for the same time and location, and prints a
side-by-side comparison with differences.
"""

import sys
import re
import urllib.request
import json
import time

# JPL Horizons body IDs
BODY_IDS = {
    "Sun": "10",
    "Moon": "301",
    "Mercury": "199",
    "Venus": "299",
    "Mars": "499",
    "Jupiter": "599",
    "Saturn": "699",
    "Uranus": "799",
    "Neptune": "899",
}

def parse_orrery_file(filename):
    """Parse the Orrery test data export file."""
    with open(filename, "r") as f:
        lines = f.readlines()

    data = {"bodies": {}}
    current_body = None

    for line in lines:
        line = line.strip()
        if not line:
            continue

        # Header values
        m = re.match(r"Epoch Day \(UT\): ([\d.\-]+)", line)
        if m:
            data["epoch_day"] = float(m.group(1))
        m = re.match(r"Julian Date: ([\d.]+)", line)
        if m:
            data["jd"] = float(m.group(1))
        m = re.match(r"Latitude: ([\d.\-]+) deg", line)
        if m:
            data["lat"] = float(m.group(1))
        m = re.match(r"Longitude: ([\d.\-]+) deg", line)
        if m:
            data["lon"] = float(m.group(1))
        m = re.match(r"GMST: ([\d.]+) hours", line)
        if m:
            data["gmst"] = float(m.group(1))
        m = re.match(r"LST: ([\d.]+) hours", line)
        if m:
            data["lst"] = float(m.group(1))
        m = re.match(r"Equation of Time: ([\d.\-]+) minutes", line)
        if m:
            data["eot"] = float(m.group(1))
        m = re.match(r"UT Date/Time: (.+) UT$", line)
        if m:
            data["ut_string"] = m.group(1)

        # Body sections
        m = re.match(r"--- (\w+) ---", line)
        if m:
            name = m.group(1)
            if name == "Jovian":
                current_body = None  # Skip Jovian Moons section for Horizons comparison
            else:
                current_body = name
                data["bodies"][current_body] = {}
            continue

        if current_body is None:
            continue

        body = data["bodies"][current_body]

        # RA and Dec (use geocentric for Moon, regular for others)
        if current_body == "Moon":
            m = re.match(r"RA \(geocentric\): ([\d.\-]+) deg", line)
            if m:
                body["ra"] = float(m.group(1))
            m = re.match(r"Dec \(geocentric\): ([\d.\-]+) deg", line)
            if m:
                body["dec"] = float(m.group(1))
            m = re.match(r"RA \(topocentric\): ([\d.\-]+) deg", line)
            if m:
                body["ra_topo"] = float(m.group(1))
            m = re.match(r"Dec \(topocentric\): ([\d.\-]+) deg", line)
            if m:
                body["dec_topo"] = float(m.group(1))
        else:
            m = re.match(r"RA: ([\d.\-]+) deg", line)
            if m:
                body["ra"] = float(m.group(1))
            m = re.match(r"Dec: ([\d.\-]+) deg", line)
            if m:
                body["dec"] = float(m.group(1))

        m = re.match(r"Distance: ([\d.\-]+) AU", line)
        if m:
            body["dist"] = float(m.group(1))
        m = re.match(r"Distance from Earth: ([\d.\-]+) AU", line)
        if m:
            body["dist"] = float(m.group(1))
        m = re.match(r"Distance from Sun: ([\d.\-]+) AU", line)
        if m:
            body["dist_sun"] = float(m.group(1))

        m = re.match(r"Ecliptic Lon: ([\d.\-]+) deg\s+Lat: ([\d.\-]+) deg", line)
        if m:
            body["ecl_lon"] = float(m.group(1))
            body["ecl_lat"] = float(m.group(2))

        m = re.match(r"Azimuth: ([\d.\-]+) deg\s+Altitude: ([\d.\-]+) deg", line)
        if m:
            body["az"] = float(m.group(1))
            body["alt"] = float(m.group(2))

        m = re.match(r"Phase Angle: ([\d.\-]+) deg\s+Illumination: ([\d.\-]+)%", line)
        if m:
            body["phase"] = float(m.group(1))
            body["illum"] = float(m.group(2))

    return data


def query_horizons(body_name, jd, lat, lon):
    """Query JPL Horizons for a single body at a specific time and location.

    Horizons API requires single-quoted values for several parameters.
    These must be URL-encoded as %27 in the query string.
    """
    body_id = BODY_IDS.get(body_name)
    if body_id is None:
        return None

    # Horizons API expects longitude in 0-360 east-positive format
    horizons_lon = lon % 360.0

    # Build URL manually because Horizons needs %27-encoded single quotes
    # around COMMAND, SITE_COORD, TLIST, and QUANTITIES values.
    q = "%27"  # URL-encoded single quote
    base = "https://ssd.jpl.nasa.gov/api/horizons.api"
    params = (
        f"?format=json"
        f"&COMMAND={q}{body_id}{q}"
        f"&OBJ_DATA=NO"
        f"&MAKE_EPHEM=YES"
        f"&EPHEM_TYPE=OBSERVER"
        f"&CENTER=coord@399"
        f"&COORD_TYPE=GEODETIC"
        f"&SITE_COORD={q}{horizons_lon},{lat},0{q}"
        f"&TLIST={q}{jd}{q}"
        f"&CAL_FORMAT=JD"
        f"&ANG_FORMAT=DEG"
        f"&EXTRA_PREC=YES"
        f"&QUANTITIES={q}1,2,4,20,31{q}"
        # 1 = Astrometric RA/Dec
        # 2 = Apparent RA/Dec
        # 4 = Apparent Az/El
        # 20 = Observer range, range-rate
        # 31 = Observer ecliptic lon/lat
    )
    url = base + params

    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=30) as resp:
            result = json.loads(resp.read().decode())
    except Exception as e:
        print(f"  ERROR querying Horizons for {body_name}: {e}")
        return None

    if "error" in result:
        print(f"  Horizons error for {body_name}: {result['error']}")
        return None

    raw = result.get("result", "")

    # Find the data block between $$SOE and $$EOE
    soe = raw.find("$$SOE")
    eoe = raw.find("$$EOE")
    if soe == -1 or eoe == -1:
        print(f"  Could not find data block for {body_name}")
        return None

    data_block = raw[soe + 5:eoe].strip()

    # Data line format (with QUANTITIES='1,2,4,20,31', ANG_FORMAT=DEG):
    #   JD  flags  RA_ICRF  DEC_ICRF  RA_app  DEC_app  Az  Elev  delta  deldot  ObsEcLon  ObsEcLat
    # The flags column contains tokens like "*m", "*", "C" etc. which are non-numeric.
    # Strategy: collect all tokens, keep only those that parse as float.
    all_tokens = data_block.split()
    nums = []
    for token in all_tokens:
        try:
            nums.append(float(token))
        except ValueError:
            pass  # skip flag tokens like "*m", "n.a.", etc.

    horizons_data = {}

    # First numeric value is the JD; the remaining 10 are our data columns
    if nums and abs(nums[0] - jd) < 1.0:
        vals = nums[1:]  # skip JD
    else:
        vals = nums

    if len(vals) >= 10:
        horizons_data["ra_icrf"] = vals[0]
        horizons_data["dec_icrf"] = vals[1]
        horizons_data["ra_app"] = vals[2]
        horizons_data["dec_app"] = vals[3]
        horizons_data["az"] = vals[4]
        horizons_data["alt"] = vals[5]
        horizons_data["delta"] = vals[6]
        horizons_data["deldot"] = vals[7]
        horizons_data["ecl_lon"] = vals[8]
        horizons_data["ecl_lat"] = vals[9]
    else:
        print(f"  Warning: expected 10+ values for {body_name}, got {len(vals)}")
        print(f"  Data block: {data_block[:200]}")

    return horizons_data


def angle_diff(a, b):
    """Compute smallest angular difference between two angles in degrees."""
    d = (a - b) % 360.0
    if d > 180.0:
        d -= 360.0
    return d


def compare_value(label, orrery_val, horizons_val, tolerance, unit="deg"):
    """Compare two values and print the result."""
    if orrery_val is None:
        print(f"  {label:25s}  Orrery: {'N/A':>14s}  Horizons: {horizons_val:>14.6f} {unit}")
        return
    if horizons_val is None:
        print(f"  {label:25s}  Orrery: {orrery_val:>14.6f}  Horizons: {'N/A':>14s} {unit}")
        return

    if "lon" in label.lower() or "ra" in label.lower() or "az" in label.lower():
        diff = angle_diff(orrery_val, horizons_val)
    else:
        diff = orrery_val - horizons_val

    flag = " ***" if abs(diff) > tolerance else ""
    print(f"  {label:25s}  Orrery: {orrery_val:>14.6f}  Horizons: {horizons_val:>14.6f}  diff: {diff:>+10.4f} {unit}{flag}")


def main():
    if len(sys.argv) < 2:
        print("Usage: python compare_with_horizons.py <orrery_test_file.txt> [--body Sun|Moon|...]")
        sys.exit(1)

    filename = sys.argv[1]

    # Optional: filter to a single body
    only_body = None
    if "--body" in sys.argv:
        idx = sys.argv.index("--body")
        if idx + 1 < len(sys.argv):
            only_body = sys.argv[idx + 1]

    print(f"Reading {filename}...")
    data = parse_orrery_file(filename)

    jd = data.get("jd")
    lat = data.get("lat")
    lon = data.get("lon")

    if jd is None or lat is None or lon is None:
        print("ERROR: Could not parse JD, latitude, or longitude from file")
        sys.exit(1)

    print(f"UT: {data.get('ut_string', '?')}")
    print(f"JD: {jd}")
    print(f"Location: {lat:.4f} lat, {lon:.4f} lon")
    print(f"GMST: {data.get('gmst', 0):.6f} hours")
    print(f"LST:  {data.get('lst', 0):.6f} hours")
    print()
    print("Querying JPL Horizons (this may take a minute)...")
    print()
    print("Tolerances: *** = exceeds threshold")
    print("  RA/Dec:    0.01 deg (36 arcsec)")
    print("  Az/Alt:    0.05 deg")
    print("  Distance:  0.0001 AU")
    print("  Ecl coords: 0.01 deg")
    print()

    bodies_to_check = ["Sun", "Moon", "Mercury", "Venus", "Mars",
                       "Jupiter", "Saturn", "Uranus", "Neptune"]

    if only_body:
        bodies_to_check = [only_body]

    for body_name in bodies_to_check:
        orrery_body = data["bodies"].get(body_name)
        if orrery_body is None:
            print(f"--- {body_name}: not in Orrery file ---")
            continue

        print(f"--- {body_name} ---")
        hz = query_horizons(body_name, jd, lat, lon)
        if hz is None:
            print("  Could not get Horizons data")
            print()
            continue

        # Compare RA/Dec
        # Orrery outputs geocentric RA/Dec; Horizons ICRF (astrometric) is closest
        orrery_ra = orrery_body.get("ra")
        orrery_dec = orrery_body.get("dec")
        hz_ra = hz.get("ra_icrf") if hz.get("ra_icrf") is not None else hz.get("ra_app")
        hz_dec = hz.get("dec_icrf") if hz.get("dec_icrf") is not None else hz.get("dec_app")

        compare_value("RA (geocentric)", orrery_ra, hz_ra, 0.01)
        compare_value("Dec (geocentric)", orrery_dec, hz_dec, 0.01)

        # For Moon, also compare topocentric
        if body_name == "Moon":
            compare_value("RA (topocentric)", orrery_body.get("ra_topo"), hz.get("ra_app"), 0.01)
            compare_value("Dec (topocentric)", orrery_body.get("dec_topo"), hz.get("dec_app"), 0.01)

        # Compare Az/Alt
        compare_value("Azimuth", orrery_body.get("az"), hz.get("az"), 0.05)
        compare_value("Altitude", orrery_body.get("alt"), hz.get("alt"), 0.05)

        # Compare distance
        compare_value("Distance (AU)", orrery_body.get("dist"), hz.get("delta"), 0.0001, "AU")

        # Compare ecliptic coords
        compare_value("Ecliptic Lon", orrery_body.get("ecl_lon"), hz.get("ecl_lon"), 0.01)
        compare_value("Ecliptic Lat", orrery_body.get("ecl_lat"), hz.get("ecl_lat"), 0.01)

        # Moon-specific
        if body_name == "Moon" and "phase" in orrery_body:
            print(f"  {'Phase Angle':25s}  Orrery: {orrery_body['phase']:>14.2f} deg  (no Horizons comparison)")
            print(f"  {'Illumination':25s}  Orrery: {orrery_body['illum']:>14.1f} %  (no Horizons comparison)")

        print()

        # Be polite to the API
        time.sleep(0.5)

    print("Done.")


if __name__ == "__main__":
    main()
