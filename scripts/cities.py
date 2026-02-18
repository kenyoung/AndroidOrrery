#!/usr/bin/env python
import csv
import io
import urllib.request
import zipfile

def generateCityList():
    countryInfoUrl = "http://download.geonames.org/export/dump/countryInfo.txt"
    countryToContinent = {}
    
    # Fetch country to continent mapping
    with urllib.request.urlopen(countryInfoUrl) as response:
        lines = response.read().decode('utf-8').splitlines()
        for line in lines:
            if line.startswith('#') or not line.strip():
                continue
            parts = line.split('\t')
            countryToContinent[parts[0]] = parts[8]

    citiesUrl = "http://download.geonames.org/export/dump/cities15000.zip"
    citiesData = []

    # Fetch and parse cities data
    with urllib.request.urlopen(citiesUrl) as response:
        with zipfile.ZipFile(io.BytesIO(response.read())) as z:
            with z.open('cities15000.txt') as f:
                lines = f.read().decode('utf-8').splitlines()
                for line in lines:
                    parts = line.split('\t')
                    # Name(1), Lat(4), Lon(5), Country(8), Pop(14)
                    population = int(parts[14])
                    if population > 100000:
                        name = parts[1]
                        country = parts[8]
                        lat = parts[4]
                        lon = parts[5]
                        continent = countryToContinent.get(country, "Unknown")
                        citiesData.append([name, country, continent, lat, lon])

    with open('cities_over_250k.csv', 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(['Name', 'Country', 'Continent', 'Latitude', 'Longitude'])
        writer.writerows(citiesData)

if __name__ == '__main__':
    generateCityList()
