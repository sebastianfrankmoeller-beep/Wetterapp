# Wetter

Schlanke Android-Wetter-App für den Eigengebrauch – genaue Modelle, kein Schnickschnack,
gleiche Designsprache wie der Tempowarner (Dunkelmodus als Standard).

## Funktionen

- **Vorhersage für den aktuellen Standort** (GPS) oder jeden gesuchten Ort
- **Ortssuche** mit Region zur Unterscheidung (z. B. „Neustadt, Hessen“)
- **Favoriten mit Schnellzugriff**: Stern antippen → Ort landet in der Leiste oben.
  Langes Drücken auf einen Favoriten: verschieben oder entfernen.
  Erneutes Antippen des gewählten Ortes lädt neu.
- **Jetzt**: Temperatur, gefühlt, Wind/Böen mit Richtung, Feuchte, Luftdruck – **gemessen an der nächsten
  DWD-Station** (Bright Sky, bis 25 km), mit Quelle und Uhrzeit („gemessen 14:00 · Glücksburg-Meierwik (3 km)“).
  Die Stationen melden etwa stündlich; ob es gerade regnet, entscheidet das Radar (alle 5 Min.);
  Lauf-Check und Stundenstreifen übernehmen die gemessene Abweichung (bis +2 Std. auslaufend)
- **Bewölkung in Achteln und Prozent** – gemessen an Stationen bis 60 km („4/8 (50 %) · gemessen Schleswig, 32 km“),
  sonst Modell. **Sonne gemessen** (Einstrahlung der letzten 10 Min. gegenüber wolkenlos, sonst Sonnenscheindauer):
  0/8 klar · 1–2 sonnig · 3–5 wolkig bzw. „Sonnig, teils wolkig“ · 6–7 stark bewölkt bzw. „Wolkig mit Sonne“ · 8/8 bedeckt.
  Antippen zeigt Sonne und Schichten tief / mittel / hoch (Modell)
- **Nebel und Sicht**: gemessene Sichtweite der DWD-Station (stündlich) – „Sicht 700 m · Nebel · gemessen 09:00“;
  unter 1 km „Nebel“, unter 200 m „Dichter Nebel“. Bei sehr feuchter, windstiller Luft und abnehmender Sicht
  „örtlich Nebel möglich“ (Nebel ist oft örtlicher als die Station). Nebel-/Dunst-Vorhersage aus DWD-MOSMIX
  („Nebel möglich ca. 6–10 Uhr“), Nebelsymbol im Stundenstreifen, Hinweis im Lauf-Check
- **Stundenstreifen antippen**: Details der Stunde (Zustand, Bewölkung mit Schichten, Regen, Wind)
- **Regen der nächsten 3 Stunden**: die ersten 2 Stunden aus dem **DWD-Radar** (Messung + Radar-Nowcast, 1 km,
  über Bright Sky), danach DWD ICON-D2. „Regen in 25 Min.“, Kurve mit Stufen leicht / mäßig / stark,
  antippen oder ziehen zeigt Uhrzeit und Menge.
  Regnet es nicht genau am Ort, aber im Umkreis von 3 km: „Schauer in der Nähe ab ca. 15:50“;
  Spuren unter „leicht“ (z. B. Niesel): „Vereinzelte Tropfen möglich“. Stunden, die Radar/Nowcast abdecken,
  zeigen auch im Stundenstreifen und Lauf-Check die Radar-Menge.
  **Niesel-Lage**: Niesel aus tiefen Wolken erfasst das Radar kaum. Sieht es nur schwache Echos an der Messgrenze
  (oder meldet eine Station bis 40 km Regen) und rechnet das Modell mit Niederschlag, gilt der höhere Wert aus
  Radar und Modell – Jetzt-Karte „Leichter Nieselregen“, Lauf-Check gelb mit Hinweis
- **Stündlich** bis 15 Tage: Symbol, Temperaturkurve, Niederschlag (mm), Regenwahrscheinlichkeit, Wind.
  Eine grüne Linie markiert, wo von ICON-D2 auf ECMWF umgeschaltet wird.
- **15 Tage**: Min/Max mit Temperaturbalken, Niederschlag, Wahrscheinlichkeit.
  Tippen auf einen Tag springt im Stundenstreifen dorthin.
- **Gewitter**: Risiko gering / mäßig / hoch pro Stunde und Tag (Blitz-Symbol), Hinweis „Gewitterrisiko ab …“
- **Schnee**: Neuschnee, Schneehöhe (ersetzt bei liegendem Schnee die Luftdruck-Kachel), Schneefallgrenze
- **Amtliche DWD-Warnungen** als farbige Karten oben in der Vorhersage, antippen für den vollen Text
- **Regenradar** des Deutschen Wetterdienstes: letzte 90 Minuten gemessen + 2 Stunden DWD-Vorhersage,
  abspielbar, mit Zeitleiste. **Weltweit**: außerhalb des DWD-Gebiets automatisch Radar von Rain Viewer
  (nur Messungen, gröber). Küsten, Grenzen und Ortsnamen liegen über dem Radar; **Deckkraft-Regler** im Bedienfeld. Zuschaltbare **Warnkarte** mit Legende –
  Karte antippen zeigt die Warnungen an dieser Stelle
- **Windkarte**: Ebenen **Wind | Böen | Gewitter**, fließende Partikel, 48 Stunden per Zeitleiste.
  Grobe **Europa-Übersicht** (alle 3 Std.) als Grundlage, beim Hineinzoomen feiner Ausschnitt darüber.
  Die App zählt ihren Open-Meteo-Verbrauch; ab 7.000 am Tag nur noch Übersicht (Hinweis auf der Karte).
  Karte antippen zeigt Wind, Richtung, Böen (und Gewitterrisiko) an dieser Stelle
- **Lauf-Check** (siehe unten)
- **Fahrmodus** mit Google Maps (siehe unten)
- **Unwetter-Benachrichtigungen** (auch auf der Garmin-Uhr, siehe unten)
- **Widget** (siehe unten)
- **Aktualisieren**: Vorhersage-Seite ganz oben nach unten ziehen – lädt Radar, Stationen, Modell und Warnungen neu
  (höchstens alle 30 s), danach kurz „Aktualisiert 21:20 · Station 21:00, nächste ca. 22:15“.
  Radar und Stationen werden außerdem bei offener App alle 5 Minuten nachgeladen
- **Regen oder Schauer nach dem Radar**: seit einer Stunde durchgehend und flächig nass → „Regen“, sonst „Schauer“;
  Stärke überall nach DWD (leicht < 2,5 · mäßig < 10 · stark ≥ 10 mm/h). Lauf-Check nennt die Regenphase
  („Mäßiger Regen bis ca. 22:15“) und eine **Regenpause** ab 30 Min. („Regenpause ca. 22:15–23:00 (45 Min.)“);
  die Regenkarte nennt den Wiederbeginn („Pause bis ca. 23:00, dann wieder leicht“)
- **Wischen** zwischen Vorhersage, Radar und Wind (auf den Karten vom Rand aus oder auf den Bedienfeldern)
- **Eigenes Aussehen** (Einstellungen → Darstellung → Anpassen): Akzentfarbe und Hintergrundton
  frei wählbar per Farbwähler (Farbfeld + Farbton, stufenlos, Live-Vorschau beim Ziehen, Hinweis bei schlechter Lesbarkeit), Hintergrund (neutral, Verlauf oder eigenes Bild – auch HEIC, richtig gedreht,
  weichgezeichnet und automatisch abgedunkelt), Schrift (Modern/Kompakt/Rund, Größe), Ecken
- **Absturzbericht**: Nach einem Absturz zeigt die App beim nächsten Start einmal den Bericht
  (Fehlerstelle, Version, letzte Aktionen) mit „Bericht kopieren“. Fehler in Hintergrund-Abläufen und beim
  Anzeigen der Daten werden abgefangen, die App läuft weiter und speichert einen Bericht
- **Einstellungen** (Zahnrad oben rechts): Darstellung, Lauf-Check, Fahrmodus, Warnungen, Berechtigungen

## Lauf-Check

**Laufwetter-Skala in 5 Stufen** (feste Farben): Dunkelgrün *Top-Laufwetter* · Hellgrün *Gute Bedingungen*
(trocken, frisch/etwas Wind) · Gelb *Geht so* (leichter Regen bei warmem Wetter, Böen ab 45, warm ab 26 °C) ·
Orange *Ungemütlich* (bei gefühlt unter 15 °C schon ab den ersten Tropfen) · Rot *Lieber verschieben*
(ab 4 mm/h, kalt und nass unter 5 °C gefühlt, Gewitter, Glatteis, Sturmböen, Hitze, dichter Nebel im Dunkeln).

Der Lauf-Check nutzt nur Daten, die die App ohnehin zeigt: die nächsten 2 Stunden aus Regenkarte und Jetzt-Werten,
alles danach aus dem Stundenstreifen (Symbol, Menge, Regenwahrscheinlichkeit). Texte abgestuft, z. B.
„Meist trocken, kurzer Schauer um ca. 12:30“, „Leichter Regen, kurze Pause ca. 12:30–12:45“,
„Wechselhaft – immer wieder leichter Regen“, darunter „Danach weiter leichter Niesel, trocken ab ca. 18:00“.

Karte in der Vorhersage (oder App-Symbol lange drücken → *Lauf-Check*), immer für die **nächsten 2 Stunden**:

- **Skala** mit Urteil und Hauptgrund (z. B. „Leichter Regen ab ca. 12:30“)
- **Verlauf in 15-Minuten-Schritten**: Stufenfarbe, Regen-Tropfen, gefühlte Temperatur, Wind und Böen – antippen für Details
- **Kleidung** nach gefühlter Temperatur und **Läufertyp** (Läuft warm / Normal / Friert schnell –
  in den Einstellungen oder per Pille in der Karte)
- **Hinweise**: Gegenwind-Tipp, Gewitter, Glätte, Dunkelheit, Hitze, UV, Warnungen
- **Besseres Zeitfenster**, wenn die nächsten 2 Stunden ungünstig sind – nur bei spürbarer Verbesserung
  (z. B. trocken, Böen ≥ 10 km/h schwächer, kein Gewitter mehr), mit Grund: „Besser 14:00–16:00: Böen nur noch bis 32 km/h“
- Auch als Widget-Inhalt **„Lauf“**

## Fahrmodus

- Startet **automatisch**, sobald Google Maps navigiert (Einstellungen → Fahrmodus → Benachrichtigungszugriff erlauben),
  und endet mit der Navigation. Manuell: App-Symbol lange drücken → *Fahrmodus*
- Prüft das Wetter **60 km voraus** in Fahrtrichtung, jeweils zur voraussichtlichen Ankunftszeit:
  Starkregen, Nebel, Schnee, Glätte/Glatteis, Sturmböen, Gewitter und amtliche DWD-Warnungen
- **Sprachansagen** („In 12 Kilometern Starkregen, etwa 8 Kilometer lang.“), Google Maps wird dabei leiser;
  dazu eine laufende Benachrichtigung mit dem aktuellen Stand
- **Routengenau**: In Google Maps Ziel antippen → *Teilen* → *Wetter-Fahrmodus*. Dann wird die Strecke bis zum Ziel
  geprüft (Route über OSRM), inkl. Wetter am Ziel. Beim Abweichen von der Route wird neu berechnet.
- Hinweis: Google Maps gibt seine Route nicht an andere Apps weiter – ohne Teilen gilt der Korridor in Fahrtrichtung.

## Unwetter-Benachrichtigungen

- Quelle: amtliche Warnungen des DWD für deine Gemeinde
- Geprüft wird **stündlich**, bei Gewitterlage (Vorhersage oder aktive Gewitterwarnung) **alle 15 Minuten**
- Gemeldet wird jede Warnung nur einmal – erneut nur, wenn sie hochgestuft wird
- Einstellbar: ab Stufe Gelb / **Orange** (Standard) / Rot, für Standort und ausgewählte Favoriten,
  Nachtruhe 22–7 Uhr (Rot und Violett kommen immer durch)
- „Test-Benachrichtigung senden“ zum Einrichten
- **Garmin**: In der Garmin-Connect-App die Benachrichtigungen für „Wetter“ freigeben
- Für Warnungen unterwegs unter *Berechtigungen* „Standort im Hintergrund“ erlauben,
  sonst gilt die Position, an der die App zuletzt geöffnet wurde

## Wettermodelle

| Zeitraum | Modell | Auflösung |
|---|---|---|
| 0–48 h | DWD **ICON-D2** | 2 km, Deutschland und Nachbarländer |
| danach bis 15 Tage (und außerhalb des D2-Gebiets) | **ECMWF IFS HRES** | 9 km |
| Regenwahrscheinlichkeit | ECMWF-Ensemble | – |
| Radar | DWD Radarverbund + Nowcast; weltweit Rain Viewer | 1 km, 5 min |
| Regen nächste 2 Std. | DWD-Radar (RADOLAN RV) via Bright Sky | 1 km, 5 min |
| Jetzt-Werte | DWD-Stationsmessungen (SYNOP) via Bright Sky | nächste Station ≤ 25 km |
| Windkarte, Böen, Gewitter-Potenzial | DWD ICON (D2/EU/global, nahtlos) | – |
| Gewitterrisiko Vorhersage | ICON-D2 Blitz-Potenzial, danach ECMWF Blitzdichte/CAPE | – |
| Warnungen | DWD (amtlich) | Gemeinde |

Alle Wetterdaten kommen über die kostenlose Schnittstelle von [Open-Meteo](https://open-meteo.com)
(nur nicht-kommerziell kostenlos). Ab Tag 10 ist jede Vorhersage unsicher – die Tagesliste zeigt das an.

## Widget

Lange auf den Startbildschirm drücken → *Widgets* → *Wetter*. Beim Hinzufügen öffnen sich die Einstellungen
mit Live-Vorschau:

- **Ort**: Standort oder ein Favorit
- **Inhalt**: Automatisch (richtet sich nach der Größe), Jetzt, Stunden, Tage, Alles
- **Hintergrund**: 100 %, 90 %, 70 %, 40 % oder ohne
- **Design**: Dunkel, Wie System, Hell

Größe ändern: Widget lange drücken und an den Rändern ziehen. Einstellungen später ändern:
Widget lange drücken → *Widget anpassen* (Android 12+). Antippen öffnet die App beim Ort des Widgets.
Das Widget aktualisiert sich etwa alle 30 Minuten und jedes Mal, wenn du die App öffnest.
Für den Standort nutzt es die letzte bekannte Position des Handys.

## Auf GitHub bauen

1. Bei jedem Push startet automatisch der Workflow **Build APK** (Reiter *Actions*).
   Manuell geht es auch über *Run workflow*.
2. Nach ca. 3–5 Minuten im fertigen Lauf unter **Artifacts** den Eintrag `Wetter-APK`
   herunterladen, entpacken, `app-debug.apk` aufs Handy kopieren und installieren.

Updates: Alle Builds sind mit demselben Schlüssel (`app/debug.keystore`) signiert und die
Versionsnummer steigt mit jedem Lauf – eine neue APK lässt sich einfach über die alte installieren.

## Projektaufbau

| Datei | Aufgabe |
|---|---|
| `MainActivity.kt` | Navigation, Ortswahl, Laden, Favoriten, Einstellungen |
| `Forecast.kt` | Abruf und Zusammenführung ICON-D2 + ECMWF, Tageswerte, Ortssuche, Texte |
| `ForecastPage.kt` | Vorhersage-Seite |
| `Sky.kt` | Bewölkung (Achtel, Stufen, Schleierwolken), Stationsmessungen, Jetzt-Werte |
| `ForecastViews.kt` | Stundenstreifen, Tagesliste, Regen-Nowcast (selbst gezeichnet) |
| `WeatherIcons.kt` | Eigene Wettersymbole (App und Widget) |
| `RadarPage.kt` | DWD-Radar mit Zeitleiste, Warnkarte |
| `Warnings.kt`, `WarnService.kt` | DWD-Warnungen, Hintergrundprüfung, Benachrichtigungen |
| `SettingsActivity.kt` | Einstellungen |
| `RunCheck.kt`, `RunViews.kt` | Lauf-Check |
| `Drive.kt`, `DriveService.kt`, `MapsNavListener.kt`, `ShareRouteActivity.kt` | Fahrmodus |
| `WindPage.kt` | Windkarte (Wind, Böen, Gewitter) mit Partikel-Animation |
| `SearchPanel.kt` | Ortssuche |
| `Locator.kt` | Standort und Ortsname |
| `widget/` | Widget, Einstellungen, Zeichnen |
| `Ui.kt`, `Glyph.kt`, `MapSupport.kt` | Farben, Symbole, Kartengrundlage |

Datenquellen: Open-Meteo (CC BY 4.0), Deutscher Wetterdienst, ECMWF, Bright Sky, Weather data by Rain Viewer.
Karten: © OpenStreetMap-Mitwirkende.
