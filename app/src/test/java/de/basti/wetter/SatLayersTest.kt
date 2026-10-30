package de.basti.wetter

import org.junit.Test

/** Erkundung: Zeit-Dimension und Kachelabruf der EUMETView-Ebene für das Wolkenbild. */
class SatLayersTest {
    @Test fun ebenen() {
        for (layer in listOf("mtg_fd/rgb_geocolour", "mtg_fd/ir105_hrfi")) {
            val url = "https://view.eumetsat.int/geoserver/$layer/ows?service=WMS&request=GetCapabilities&version=1.3.0"
            val xml = runCatching { Net.get(url, 60000) }.getOrElse { println("SAT $layer FEHLER ${it.message}"); continue }
            println("SAT $layer Größe ${xml.length}")
            val dim = Regex("<Dimension[^>]*name=\"time\"[^>]*>([^<]*)</Dimension>").find(xml)
            val tag = Regex("<Dimension[^>]*name=\"time\"[^>]*>").find(xml)?.value
            println("SAT $layer Tag ${tag}")
            val v = dim?.groupValues?.get(1) ?: ""
            println("SAT $layer Werte (${v.length}) Anfang ${v.take(200)}")
            println("SAT $layer Werte Ende ${v.takeLast(300)}")
        }
        // Kachel über Norddeutschland (z 6) abrufen
        val bbox = Tiles.bbox3857(33, 20, 6)
        val tile = "https://view.eumetsat.int/geoserver/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=mtg_fd:rgb_geocolour" +
            "&STYLES=&FORMAT=image/png&TRANSPARENT=true&CRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX=$bbox"
        val b = Net.getBytes(tile, 30000)
        println("SAT Kachel Bytes ${b?.size} Kopf ${b?.take(8)?.joinToString(",")}")
        val jpg = Net.getBytes(tile.replace("image/png", "image/jpeg").replace("&TRANSPARENT=true", ""), 30000)
        println("SAT Kachel JPEG Bytes ${jpg?.size}")
    }
}
