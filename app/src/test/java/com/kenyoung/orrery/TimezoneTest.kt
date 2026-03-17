package com.kenyoung.orrery

import org.junit.BeforeClass
import org.junit.Test
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

/**
 * Tests TimezoneLookup against a web timezone API (timeapi.io).
 *
 * Run from Android Studio: click the green arrow next to testAllCities.
 * Run from command line:
 *   ./gradlew test --tests "com.kenyoung.orrery.TimezoneTest" --info
 *
 * First run fetches timezone data from the web and caches it locally.
 * Subsequent runs use the cache (instant). Delete timezone_test_cache.csv to re-fetch.
 */
class TimezoneTest {

    companion object {
        private val projectRoot = findProjectRoot()
        private val assetsDir = File(projectRoot, "app/src/main/assets")
        private val cacheFile = File(projectRoot, "timezone_test_cache.csv")

        private fun findProjectRoot(): File {
            for (candidate in listOf(File("."), File(".."))) {
                if (File(candidate, "app/src/main/assets/tz_h3_index.bin").exists())
                    return candidate.canonicalFile
            }
            error("Cannot find project root (looked for app/src/main/assets/tz_h3_index.bin)")
        }

        @JvmStatic
        @BeforeClass
        fun setup() {
            val indexPath = File(assetsDir, "tz_h3_index.bin").absolutePath
            val loaded = loadTimezoneIndexFromFile(indexPath)
            assert(loaded) { "Failed to load timezone index from $indexPath" }
            println("Timezone index loaded successfully")
        }

        @JvmStatic
        fun main(args: Array<String>) {
            setup()
            TimezoneTest().testAllCities()
        }
    }

    @Test
    fun testAllCities() {
        val cache = loadCache()
        println("Cache: ${cache.size} entries loaded")

        val lines = File(assetsDir, "cities_over_100k.csv").readLines().drop(1)
        println("Testing ${lines.size} cities\n")

        var correct = 0
        var incorrect = 0
        var noResult = 0
        var noReference = 0
        var fetched = 0
        val mismatches = mutableListOf<String>()

        val writer = BufferedWriter(FileWriter(cacheFile, true))
        try {
            for ((i, line) in lines.withIndex()) {
                val parts = line.split(",")
                if (parts.size < 5) continue
                val name = parts[0]
                val country = parts[1]
                val lat = parts[3].toDoubleOrNull() ?: continue
                val lon = parts[4].toDoubleOrNull() ?: continue

                val calculated = lookupTimezone(lat, lon)
                val key = "${parts[3]},${parts[4]}"

                var expected = cache[key]
                if (expected == null) {
                    expected = fetchTimezoneFromWeb(lat, lon)
                    if (expected != null) {
                        writer.write("$key,$expected")
                        writer.newLine()
                        writer.flush()
                        cache[key] = expected
                        fetched++
                    }
                }

                when {
                    expected == null -> noReference++
                    calculated == null -> {
                        noResult++
                        if (noResult <= 20) mismatches.add(
                            "  NO RESULT: $name ($country) ($lat, $lon) expected=$expected"
                        )
                    }
                    timezonesMatch(calculated, expected) -> correct++
                    else -> {
                        incorrect++
                        if (incorrect <= 50) mismatches.add(
                            "  MISMATCH: $name ($country) ($lat, $lon) calc=$calculated exp=$expected"
                        )
                    }
                }

                if ((i + 1) % 100 == 0 || i == lines.size - 1) {
                    val tested = correct + incorrect
                    val pct = if (tested > 0) "%.1f%%".format(correct * 100.0 / tested) else "-"
                    println("[${i + 1}/${lines.size}] correct=$correct ($pct) incorrect=$incorrect" +
                            " noResult=$noResult noRef=$noReference" +
                            if (fetched > 0) " fetched=$fetched" else "")
                }
            }
        } finally {
            writer.close()
        }

        println("\n======= GRAND TOTAL =======")
        println("Cities tested:  ${lines.size}")
        val tested = correct + incorrect
        val pct = if (tested > 0) "%.1f%%".format(correct * 100.0 / tested) else "-"
        println("Correct:        $correct ($pct)")
        println("Incorrect:      $incorrect")
        println("No result:      $noResult  (H3 lookup returned null)")
        println("No reference:   $noReference  (web API returned no timezone)")

        if (mismatches.isNotEmpty()) {
            println("\nDetails (first ${mismatches.size}):")
            mismatches.forEach { println(it) }
        }
    }

    private fun loadCache(): MutableMap<String, String> {
        val cache = mutableMapOf<String, String>()
        if (!cacheFile.exists()) return cache
        cacheFile.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val idx1 = line.indexOf(',')
            if (idx1 < 0) return@forEachLine
            val idx2 = line.indexOf(',', idx1 + 1)
            if (idx2 < 0) return@forEachLine
            val key = line.substring(0, idx2)
            val tz = line.substring(idx2 + 1)
            cache[key] = tz
        }
        return cache
    }

    private fun timezonesMatch(calc: String, expected: String): Boolean {
        if (calc == expected) return true
        // Normalize via ZoneId to handle aliases (Asia/Calcutta → Asia/Kolkata, etc.)
        return try {
            ZoneId.of(calc).normalized().id == ZoneId.of(expected).normalized().id
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchTimezoneFromWeb(lat: Double, lon: Double): String? {
        return try {
            Thread.sleep(150) // Rate limiting
            val url = URL("https://timeapi.io/api/timezone/coordinate?latitude=$lat&longitude=$lon")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val match = Regex("\"timeZone\"\\s*:\\s*\"([^\"]+)\"").find(body)
            match?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }
}
