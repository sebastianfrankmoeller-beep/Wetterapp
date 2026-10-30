package de.basti.wetter

import org.junit.Test

/** Einmalige Erkundung: welche EUMETView-Ebenen gibt es (Name, Zeit-Dimension)? */
class SatLayersTest {
    @Test fun ebenen() {
        val xml = runCatching { Net.get("https://view.eumetsat.int/geoserver/ows?service=WMS&request=GetCapabilities&version=1.3.0", 60000) }
            .getOrElse { println("SAT FEHLER ${it.message}"); return }
        println("SAT Größe ${xml.length}")
        val re = Regex("<Layer[^>]*>\\s*<Name>([^<]+)</Name>\\s*<Title>([^<]*)</Title>")
        val names = re.findAll(xml).map { it.groupValues[1] + " | " + it.groupValues[2] }.toList()
        println("SAT Ebenen gesamt ${names.size}")
        names.filter { n -> val l = n.lowercase(); (l.startsWith("mtg") || l.startsWith("msg")) &&
            (l.contains("rgb") || l.contains("ir1") || l.contains("colour") || l.contains("color") || l.contains("cloud")) }
            .take(80).chunked(8).forEach { println("SAT " + it.joinToString(" ;; ")) }
    }
}
