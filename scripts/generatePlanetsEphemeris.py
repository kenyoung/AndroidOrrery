# Fetch planet ephemeri from JPL Horizons
import urllib.request
import urllib.parse
import csv
import time

# --- CONFIGURATION ---
START_TIME = "2021-01-01"
STOP_TIME  = "2031-01-01"
STEP_SIZE  = "6h" 

BODIES = [
    ('10', 'Sun'), 
    ('199', 'Mercury'), ('299', 'Venus'), ('499', 'Mars'),
    ('599', 'Jupiter'), ('699', 'Saturn'), ('799', 'Uranus'), ('899', 'Neptune'),
    ('90000030', 'Halley')
]
OUTPUT_FILENAME = "ephemeris_planets.csv"

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

def fetch_body_data(body_id, body_name):
    print(f"Fetching {body_name}...")
    
    # DYNAMIC QUANTITIES
    # Sun: 1 (RA/Dec), 20 (Geo Range). Helio coords will be forced to 0.
    # Others: 1, 18 (Helio Lon/Lat), 19 (Helio Range), 20 (Geo Range).
    if body_name == 'Sun':
        quantities = "'1,20'"
    else:
        quantities = "'1,18,19,20'"
        
    params = {
        'format': 'text', 'COMMAND': body_id, 'OBJ_DATA': 'NO', 'MAKE_EPHEM': 'YES',
        'EPHEM_TYPE': 'OBSERVER', 'CENTER': '500', 'START_TIME': START_TIME,
        'STOP_TIME': STOP_TIME, 'STEP_SIZE': STEP_SIZE, 
        'QUANTITIES': quantities, 
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
        
        try:
            jd = parts[0].strip(); float(jd)
            
            # --- PARSING LOGIC DEPENDS ON BODY ---
            if body_name == 'Sun':
                # Returned columns for '1,20':
                # 0:JD, 1:S, 2:L, 3:RA, 4:Dec, 5:GeoDist, 6:Rate
                if len(parts) < 6: continue
                
                ra = hms_to_deg(parts[3].strip())
                dec = dms_to_deg(parts[4].strip())
                g_dist = float(parts[5].strip())
                
                # Hardcode Helio coords for Sun
                h_dist, h_lon, h_lat = 0.0, 0.0, 0.0
                
            else:
                # Returned columns for '1,18,19,20':
                # 0:JD, 1:S, 2:L, 3:RA, 4:Dec, 5:HLon, 6:HLat, 7:HDist, 8:HRate, 9:GDist, 10:GRate
                if len(parts) < 10: continue

                ra = hms_to_deg(parts[3].strip())
                dec = dms_to_deg(parts[4].strip())
                h_lon = float(parts[5].strip())
                h_lat = float(parts[6].strip())
                h_dist = float(parts[7].strip())
                g_dist = float(parts[9].strip())

            # UNIFIED OUTPUT FORMAT
            # [RA, Dec, GeoDist, HelioDist, HelioLon, HelioLat]
            parsed_data[jd] = [
                f"{ra:.6f}", f"{dec:.6f}", f"{g_dist:.8f}", 
                f"{h_dist:.8f}", f"{h_lon:.6f}", f"{h_lat:.6f}"
            ]
            
        except ValueError: continue 
            
    return parsed_data

def main():
    all_data = []
    for bid, name in BODIES:
        data = fetch_body_data(bid, name)
        if not data: 
            print(f"Failed to fetch {name}")
            return
        all_data.append(data)
        time.sleep(0.2)
        
    master_jds = sorted(all_data[0].keys(), key=float)
    print(f"Merging {len(master_jds)} rows to {OUTPUT_FILENAME}...")
    
    with open(OUTPUT_FILENAME, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile)
        header = ["JD"]
        for _, name in BODIES: 
            header.extend([
                f"{name}_RA", f"{name}_Dec", f"{name}_GeoDist", 
                f"{name}_HelioDist", f"{name}_HelioLon", f"{name}_HelioLat"
            ])
        writer.writerow(header)
        
        for jd in master_jds:
            row = [jd]; valid = True
            for bd in all_data:
                if jd in bd: row.extend(bd[jd])
                else: valid = False; break
            if valid: writer.writerow(row)
    print("Done.")

if __name__ == "__main__": main()
