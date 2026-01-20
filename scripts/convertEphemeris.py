# Converts CSV ephemeris files to binary format
import pandas as pd
import struct
import os

# Paths
ASSETS_DIR = '../app/src/main/assets' # Adjust if running from root vs scripts folder
if not os.path.exists(ASSETS_DIR):
    # Fallback if running from project root
    ASSETS_DIR = 'app/src/main/assets'

def convert_csv_to_bin(filename, output_name, num_data_cols):
    csv_path = os.path.join(ASSETS_DIR, filename)
    bin_path = os.path.join(ASSETS_DIR, output_name)
    
    print(f"Reading {csv_path}...")
    try:
        df = pd.read_csv(csv_path)
    except FileNotFoundError:
        print(f"Error: Could not find {csv_path}")
        return

    # Total columns = 1 (JD) + Data Columns
    # Validate column count
    expected_cols = 1 + num_data_cols
    if len(df.columns) != expected_cols:
        print(f"Warning: Expected {expected_cols} columns, found {len(df.columns)}. Verifying...")

    print(f"Writing {bin_path}...")
    
    with open(bin_path, 'wb') as f:
        # We write row by row.
        # Format: Little Endian (<), all Doubles (d)
        # String format: e.g., "<55d" for planets
        fmt = f"<{expected_cols}d"
        
        count = 0
        for row in df.itertuples(index=False, name=None):
            # Pack the entire row (JD + Data) as doubles
            f.write(struct.pack(fmt, *row))
            count += 1
            
    print(f"Success! Wrote {count} rows. Size: {os.path.getsize(bin_path) / 1024:.1f} KB")

if __name__ == "__main__":
    # 1. Planets
    # 9 Bodies (Sun, Mercury, Venus, Mars, Jupiter, Saturn, Uranus, Neptune, Halley)
    # 6 columns each = 54 data columns
    convert_csv_to_bin('ephemeris_planets.csv', 'ephemeris_planets.bin', 54)

    # 2. Moon
    # 1 Body * 6 columns = 6 data columns
    convert_csv_to_bin('ephemeris_moon.csv', 'ephemeris_moon.bin', 6)
