# Wetter

Schlanke Android-Wetter-App für den Eigengebrauch – genaue Modelle, kein Schnickschnack,
gleiche Designsprache wie der Tempowarner (Dunkelmodus als Standard).

## Funktionen

- **Vorhersage für den aktuellen Standort** (GPS) oder jeden gesuchten Ort
- **Ortssuche** mit Region zur Unterscheidung (z. B. „Neustadt, Hessen“)
- **Favoriten mit Schnellzugriff**: Stern antippen → Ort landet in der Leiste oben.
  Langes Drücken auf einen Favoriten: verschieben oder entfernen.
  Erneutes Antippen des gewählten Ortes lädt neu.
- **Jetzt**: Temperatur, gefühlt, Wind/Böen mit Richtung, Feuchte, Luftdruck
- **Regen der nächsten 3 Stunden**: die ersten 2 Stunden aus dem **DWD-Radar** (Messung + Radar-Nowcast, 1 km,
  über Bright Sky), danach DWD ICON-D2. „Regen in 25 Min.“, Kurve mit Stufen leicht / mäßig / stark,
  antippen oder ziehen zeigt Uhrzeit und Menge
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
  Karte antippen zeigt Wind, Richtung, Böen (und Gewitterrisiko) an dieser Stelle
- **Lauf-Check** (siehe unten)
- **Fahrmodus** mit Google Maps (siehe unten)
- **Unwetter-Benachrichtigungen** (auch auf der Garmin-Uhr, siehe unten)
- **Widget** (siehe unten)
- **Wischen** zwischen Vorhersage, Radar und Wind (auf den Karten vom Rand aus oder auf den Bedienfeldern)
- **Eigenes Aussehen** (Einstellungen → Darstellung → Anpassen): Akzentfarbe, Hintergrund (neutral, Verlauf
  oder eigenes Bild – weichgezeichnet und automatisch abgedunkelt), Schrift (Modern/Kompakt/Rund, Größe), Ecken
- **Einstellungen** (Zahnrad oben rechts): Darstellung, Lauf-Check, Fahrmodus, Warnungen, Berechtigungen

## Lauf-Check

Karte in der Vorhersage (oder App-Symbol lange drücken → *Lauf-Check*), immer für die **nächsten 2 Stunden**:

- **Ampel** mit Urteil und Hauptgrund (z. B. „Leichter Regen ab ca. 12:30“)
- **Verlauf in 15-Minuten-Schritten**: Ampelfarbe, Regen-Tropfen, gefühlte Temperatur, Wind und Böen – antippen für Details
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
