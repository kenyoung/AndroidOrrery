`# Fetch Moon ephemeris from JPL Horizonsimport urllib.request
import urllib.parse
import csv
import time

# --- CONFIGURATION ---
START_TIME = "2021-01-01"
STOP_TIME  = "2031-01-01"
STEP_SIZE  = "1h"

BODIES = [ ('301', 'Moon') ]
OUTPUT_FILENAME = "ephemeris_moon.csv"

def hms_to_deg(hms_str):
    try:
        parts = hms_str.strip().split()
        if len(parts) != 3: return 0.0
        h, m, s = float(parts[0]), float(parts[1]), float(parts[2])
        return (h + m/60 + s/3600) * 15.0
    except: return 0.0

def dms_to_deg(dms_str):
    try:
        parts = dms_str.strip().split()
        if len(parts) != 3: return 0.0
        d, m, s = float(parts[0]), float(parts[1]), float(parts[2])
        sign = -1 if dms_str.strip().startswith('-') else 1
        return sign * (abs(d) + m/60 + s/3600)
    except: return 0.0

def fetch_body_data(body_id):
    print(f"Fetching Moon data...")
    params = {
        'format': 'text', 'COMMAND': body_id, 'OBJ_DATA': 'NO', 'MAKE_EPHEM': 'YES',
        'EPHEM_TYPE': 'OBSERVER', 'CENTER': '500', 'START_TIME': START_TIME,
        'STOP_TIME': STOP_TIME, 'STEP_SIZE': STEP_SIZE, 
        'QUANTITIES': "'1,18,19,20'", 
        'CSV_FORMAT': 'YES', 'CAL_FORMAT': 'JD'
    }
    url = "https://ssd.jpl.nasa.gov/api/horizons.api?" + urllib.parse.urlencode(params)
    
    try:
        with urllib.request.urlopen(url) as response: content = response.read().decode('utf-8')
    except Exception as e: print(f"Error: {e}"); return {}

    lines = content.splitlines(); data_lines = []; in_block = False
    for line in lines:
        if "$$EOE" in line: in_block = False
        if in_block: data_lines.append(line)
        if "$$SOE" in line: in_block = True
            
    parsed_data = {} 
    for row in data_lines:
        parts = row.split(',')
        if len(parts) >= 10:
            try:
                jd = parts[0].strip(); float(jd)
                ra = hms_to_deg(parts[3].strip())
                dec = dms_to_deg(parts[4].strip())
                h_lon = float(parts[5].strip())
                h_lat = float(parts[6].strip())
                h_dist = float(parts[7].strip())
                g_dist = float(parts[9].strip())
                
                parsed_data[jd] = [
                    f"{ra:.6f}", f"{dec:.6f}", f"{g_dist:.8f}", 
                    f"{h_dist:.8f}", f"{h_lon:.6f}", f"{h_lat:.6f}"
                ]
            except ValueError: continue 
    return parsed_data

def main():
    data = fetch_body_data(BODIES[0][0])
    if not data: return
    
    jds = sorted(data.keys(), key=float)
    print(f"Writing {len(jds)} rows to {OUTPUT_FILENAME}...")
    
    with open(OUTPUT_FILENAME, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerow(["JD", "Moon_RA", "Moon_Dec", "Moon_GeoDist", "Moon_HelioDist", "Moon_HelioLon", "Moon_HelioLat"])
        for jd in jds:
            writer.writerow([jd] + data[jd])
    print("Done.")

if __name__ == "__main__": main()
