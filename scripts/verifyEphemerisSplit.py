# Print coordinates at this time from CSV format ephemeri
import csv
import datetime
import math
import sys

# --- CONFIGURATION ---
MOON_FILE = "ephemeris_moon.csv"
PLANETS_FILE = "ephemeris_planets.csv"

# Bodies list: (Name, FileSource, ColumnOffsetInFile)
# Column Offset: 0=JD, 1=RA, 2=Dec, 3=Dist...
# Moon File: JD, RA, Dec, Dist (Offset 1 for RA)
# Planets File: JD, Sun_RA, Sun_Dec, Sun_Dist, Mercury_RA... 
#   Sun is at offset 1. Mercury at 4. Venus at 7. etc.
BODIES = [
    ("Sun",     PLANETS_FILE, 1),
    ("Moon",    MOON_FILE,    1),
    ("Mercury", PLANETS_FILE, 4),
    ("Venus",   PLANETS_FILE, 7),
    ("Mars",    PLANETS_FILE, 10),
    ("Jupiter", PLANETS_FILE, 13),
    ("Saturn",  PLANETS_FILE, 16),
    ("Uranus",  PLANETS_FILE, 19),
    ("Neptune", PLANETS_FILE, 22),
    ("Halley",  PLANETS_FILE, 25)
]

def get_current_jd():
    # Calculate JD for current UTC time
    now = datetime.datetime.now(datetime.timezone.utc)
    year, month, day = now.year, now.month, now.day
    hour, minute, second = now.hour, now.minute, now.second + now.microsecond/1e6
    
    if month <= 2:
        year -= 1
        month += 12
        
    A = math.floor(year / 100)
    B = 2 - A + math.floor(A / 4)
    jd = math.floor(365.25 * (year + 4716)) + math.floor(30.6001 * (month + 1)) + day + B - 1524.5
    day_fraction = (hour + minute/60.0 + second/3600.0) / 24.0
    return jd + day_fraction

def deg_to_hms(deg):
    deg = deg % 360
    if deg < 0: deg += 360
    hours = deg / 15.0
    h = int(hours)
    rem = (hours - h) * 60.0
    m = int(rem)
    s = (rem - m) * 60.0
    return f"{h:02d}:{m:02d}:{s:05.2f}"

def deg_to_dms(deg):
    sign = "+" if deg >= 0 else "-"
    deg = abs(deg)
    d = int(deg)
    rem = (deg - d) * 60.0
    m = int(rem)
    s = (rem - m) * 60.0
    return f"{sign}{d:02d}:{m:02d}:{s:05.2f}"

def load_file(filename):
    times = []
    data_rows = []
    try:
        with open(filename, 'r') as f:
            reader = csv.reader(f)
            next(reader) # Skip header
            for row in reader:
                if not row: continue
                try:
                    # Parse all cols as floats
                    vals = [float(x) for x in row]
                    times.append(vals[0]) # JD is col 0
                    data_rows.append(vals)
                except ValueError:
                    continue
    except FileNotFoundError:
        print(f"[ERROR] Could not find file: {filename}")
        return None, None
    return times, data_rows

def interpolate(target_jd, times, rows, col_offset):
    # Binary search for interval
    # (Linear search OK for script, but let's be robust)
    if target_jd < times[0] or target_jd > times[-1]:
        return None 
        
    # Bisect manually
    low = 0
    high = len(times) - 1
    
    while high - low > 1:
        mid = (low + high) // 2
        if times[mid] <= target_jd:
            low = mid
        else:
            high = mid
            
    # Interval found: low..high
    t1 = times[low]
    t2 = times[high]
    frac = (target_jd - t1) / (t2 - t1)
    
    row1 = rows[low]
    row2 = rows[high]
    
    # Extract values: RA (col_offset), Dec (col_offset+1), Dist (col_offset+2)
    ra1 = row1[col_offset]
    ra2 = row2[col_offset]
    
    # Handle RA wrap (359 -> 1)
    dRa = ra2 - ra1
    if dRa < -180: dRa += 360
    if dRa > 180: dRa -= 360
    ra = ra1 + dRa * frac
    if ra < 0: ra += 360
    if ra >= 360: ra -= 360
    
    dec = row1[col_offset+1] + (row2[col_offset+1] - row1[col_offset+1]) * frac
    dist = row1[col_offset+2] + (row2[col_offset+2] - row1[col_offset+2]) * frac
    
    return ra, dec, dist

def main():
    print("--- Split Ephemeris Sanity Check ---")
    now_jd = get_current_jd()
    print(f"Current Time (UTC): {datetime.datetime.now(datetime.timezone.utc)}")
    print(f"Current JD:         {now_jd:.6f}\n")
    
    # Load Data
    print(f"Loading {MOON_FILE}...")
    moon_times, moon_rows = load_file(MOON_FILE)
    if not moon_times: return

    print(f"Loading {PLANETS_FILE}...")
    planet_times, planet_rows = load_file(PLANETS_FILE)
    if not planet_times: return
    
    print("\n{:<10} | {:<16} | {:<16} | {:<12}".format("Object", "RA (J2000)", "Dec (J2000)", "Dist (AU)"))
    print("-" * 62)
    
    for name, filename, col_idx in BODIES:
        if filename == MOON_FILE:
            res = interpolate(now_jd, moon_times, moon_rows, col_idx)
        else:
            res = interpolate(now_jd, planet_times, planet_rows, col_idx)
            
        if res:
            ra, dec, dist = res
            print("{:<10} | {} | {} | {:.8f}".format(
                name, deg_to_hms(ra), deg_to_dms(dec), dist
            ))
        else:
            print("{:<10} | [Out of Range]".format(name))

if __name__ == "__main__":
    main()
    
