#!/usr/bin/env python3
"""
Compute the maximum possible geocentric angular diameter for each planet.

Max angular size occurs at minimum Earth-planet distance:
- Inferior planets (Mercury, Venus): inferior conjunction with Earth at
  perihelion and planet at aphelion. dMin = aE*(1-eE) - aP*(1+eP).
- Superior planets (Mars..Neptune): opposition with Earth at aphelion and
  planet at perihelion. dMin = aP*(1-eP) - aE*(1+eE).

These are theoretical extremes; in any given year the planet may get
nowhere near this max. The values are intended for fixed layout sizing,
so that lanes are guaranteed to fit the worst case.

Orbital elements match getOrreryPlanets() in AstroModels.kt.
Planet radii and Saturn ring outer edge match PlanetAngularSizeScreen.kt.
"""

AU_KM = 149597870.7
ARCSEC_PER_RAD = 206264.806

EARTH_A = 1.00000
EARTH_E = 0.01671

# (name, equatorial radius km, semi-major axis AU, eccentricity)
PLANETS = [
    ("Mercury",  2439.7,  0.38710, 0.20563),
    ("Venus",    6051.8,  0.72333, 0.00677),
    ("Mars",     3389.5,  1.52368, 0.09340),
    ("Jupiter", 69911.0,  5.20260, 0.04849),
    ("Saturn",  58232.0,  9.55490, 0.05555),
    ("Uranus",  25362.0, 19.1817,  0.04731),
    ("Neptune", 24622.0, 30.0582,  0.00860),
]

SATURN_RING_OUTER_KM = 136775.0  # outer edge of A ring
SATURN_A = 9.55490
SATURN_E = 0.05555


def minDistanceAu(aPlanet, ePlanet):
    earthPeri = EARTH_A * (1 - EARTH_E)
    earthApo = EARTH_A * (1 + EARTH_E)
    planetPeri = aPlanet * (1 - ePlanet)
    planetApo = aPlanet * (1 + ePlanet)
    if aPlanet < 1.0:
        return earthPeri - planetApo
    return planetPeri - earthApo


def angularDiameterArcsec(radiusKm, distAu):
    return 2.0 * radiusKm / (distAu * AU_KM) * ARCSEC_PER_RAD


def main():
    print("Maximum possible geocentric angular diameters")
    print("=" * 60)
    rows = []
    for name, rKm, a, e in PLANETS:
        dMin = minDistanceAu(a, e)
        arcsec = angularDiameterArcsec(rKm, dMin)
        rows.append((name, dMin, arcsec))
        print(f"  {name:<8}  min dist {dMin:8.4f} AU  ->  {arcsec:6.2f}\"")

    ringDist = minDistanceAu(SATURN_A, SATURN_E)
    ringArcsec = angularDiameterArcsec(SATURN_RING_OUTER_KM, ringDist)
    print(f"  {'Rings':<8}  min dist {ringDist:8.4f} AU  ->  {ringArcsec:6.2f}\"  (Saturn A-ring outer)")

    print()
    print("Kotlin constants (paste into PlanetAngularSizeScreen.kt):")
    print()
    print("private val MAX_DIAMETER_ARCSEC = mapOf(")
    for name, _, arcsec in rows:
        extent = ringArcsec if name == "Saturn" else arcsec
        note = "  // includes rings" if name == "Saturn" else ""
        print(f'    "{name}" to {extent:.2f},{note}')
    print(")")


if __name__ == "__main__":
    main()
