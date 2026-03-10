package com.kenyoung.orrery

import android.content.Context
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lazy-loads and caches IAU constellation boundary data (Roman 1987, B1875.0 equatorial coords).
 * Provides lookup: given B1875.0 RA (hours) and Dec (degrees), returns the constellation name.
 */
object ConstellationBoundary {

    private data class BoundaryRecord(
        val raLow: Float,
        val raHigh: Float,
        val decLow: Float,
        val constellationIndex: Int
    )

    private var records: List<BoundaryRecord>? = null

    // 88 IAU constellation full names, ordered alphabetically by abbreviation.
    // Indices match the Python script's ABBREV_SORTED ordering.
    private val constellationNames = arrayOf(
        "Andromeda",            // And
        "Antlia",               // Ant
        "Apus",                 // Aps
        "Aquila",               // Aql
        "Aquarius",             // Aqr
        "Ara",                  // Ara
        "Aries",                // Ari
        "Auriga",               // Aur
        "Boötes",               // Boo
        "Caelum",               // Cae
        "Camelopardalis",       // Cam
        "Capricornus",          // Cap
        "Carina",               // Car
        "Cassiopeia",           // Cas
        "Centaurus",            // Cen
        "Cepheus",              // Cep
        "Cetus",                // Cet
        "Chamaeleon",           // Cha
        "Circinus",             // Cir
        "Canis Major",          // CMa
        "Canis Minor",          // CMi
        "Cancer",               // Cnc
        "Columba",              // Col
        "Coma Berenices",       // Com
        "Corona Australis",     // CrA
        "Corona Borealis",      // CrB
        "Crater",               // Crt
        "Crux",                 // Cru
        "Corvus",               // Crv
        "Canes Venatici",       // CVn
        "Cygnus",               // Cyg
        "Delphinus",            // Del
        "Dorado",               // Dor
        "Draco",                // Dra
        "Equuleus",             // Equ
        "Eridanus",             // Eri
        "Fornax",               // For
        "Gemini",               // Gem
        "Grus",                 // Gru
        "Hercules",             // Her
        "Horologium",           // Hor
        "Hydra",                // Hya
        "Hydrus",               // Hyi
        "Indus",                // Ind
        "Lacerta",              // Lac
        "Leo",                  // Leo
        "Lepus",                // Lep
        "Libra",                // Lib
        "Leo Minor",            // LMi
        "Lupus",                // Lup
        "Lynx",                 // Lyn
        "Lyra",                 // Lyr
        "Mensa",                // Men
        "Microscopium",         // Mic
        "Monoceros",            // Mon
        "Musca",                // Mus
        "Norma",                // Nor
        "Octans",               // Oct
        "Ophiuchus",            // Oph
        "Orion",                // Ori
        "Pavo",                 // Pav
        "Pegasus",              // Peg
        "Perseus",              // Per
        "Phoenix",              // Phe
        "Pictor",               // Pic
        "Piscis Austrinus",     // PsA
        "Pisces",               // Psc
        "Puppis",               // Pup
        "Pyxis",                // Pyx
        "Reticulum",            // Ret
        "Sculptor",             // Scl
        "Scorpius",             // Sco
        "Scutum",               // Sct
        "Serpens",              // Ser
        "Sextans",              // Sex
        "Sagitta",              // Sge
        "Sagittarius",          // Sgr
        "Taurus",               // Tau
        "Telescopium",          // Tel
        "Triangulum Australe",  // TrA
        "Triangulum",           // Tri
        "Tucana",               // Tuc
        "Ursa Major",           // UMa
        "Ursa Minor",           // UMi
        "Vela",                 // Vel
        "Virgo",                // Vir
        "Volans",               // Vol
        "Vulpecula",            // Vul
    )

    /**
     * Loads boundary data from assets if not already cached.
     */
    fun ensureLoaded(context: Context) {
        if (records != null) return
        val buf = ByteArray(13) // 3 floats (4 bytes each) + 1 byte index
        val byteBuffer = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val loaded = mutableListOf<BoundaryRecord>()
        context.assets.open("constellation_boundaries.bin").use { raw ->
            BufferedInputStream(raw).use { stream ->
                while (true) {
                    val bytesRead = stream.read(buf)
                    if (bytesRead < 13) break
                    byteBuffer.rewind()
                    val raLow = byteBuffer.float
                    val raHigh = byteBuffer.float
                    val decLow = byteBuffer.float
                    val idx = buf[12].toInt() and 0xFF
                    loaded.add(BoundaryRecord(raLow, raHigh, decLow, idx))
                }
            }
        }
        records = loaded
    }

    /**
     * Finds the constellation containing the given position in B1875.0 equatorial coordinates.
     * @param raHours Right ascension in hours (0–24)
     * @param decDeg  Declination in degrees (-90 to +90)
     * @return Full constellation name, or "Unknown" if no match (should not happen for valid coords)
     */
    fun findConstellation(raHours: Double, decDeg: Double): String {
        val recs = records ?: return "Unknown"
        // Records are sorted by decreasing decLow.
        // First match where dec >= decLow and ra is within [raLow, raHigh) is correct.
        for (rec in recs) {
            if (decDeg >= rec.decLow && raHours >= rec.raLow && raHours < rec.raHigh) {
                return constellationNames[rec.constellationIndex]
            }
        }
        return "Unknown"
    }

}
