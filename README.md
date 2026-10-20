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
- **Regen der nächsten 3 Stunden** in 15-Minuten-Schritten (DWD ICON-D2)
- **Stündlich** bis 15 Tage: Symbol, Temperaturkurve, Niederschlag (mm), Regenwahrscheinlichkeit, Wind.
  Eine grüne Linie markiert, wo von ICON-D2 auf ECMWF umgeschaltet wird.
- **15 Tage**: Min/Max mit Temperaturbalken, Niederschlag, Wahrscheinlichkeit.
  Tippen auf einen Tag springt im Stundenstreifen dorthin.
- **Regenradar** des Deutschen Wetterdienstes: letzte 90 Minuten gemessen + 2 Stunden DWD-Vorhersage,
  abspielbar, mit Zeitleiste
- **Windkarte**: farbige Windstärke und fließende Partikel, 48 Stunden per Zeitleiste,
  Wert unter dem Fadenkreuz in der Kartenmitte
- **Widget** (siehe unten)
- **Darstellung**: Dunkel (Standard), Automatisch oder Hell – Zahnrad oben rechts

## Wettermodelle

| Zeitraum | Modell | Auflösung |
|---|---|---|
| 0–48 h | DWD **ICON-D2** | 2 km, Deutschland und Nachbarländer |
| danach bis 15 Tage (und außerhalb des D2-Gebiets) | **ECMWF IFS HRES** | 9 km |
| Regenwahrscheinlichkeit | ECMWF-Ensemble | – |
| Radar | DWD Radarverbund + Nowcast | 1 km, 5 min |
| Windkarte | DWD ICON (D2/EU/global, nahtlos) | – |

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
| `RadarPage.kt` | DWD-Radar mit Zeitleiste |
| `WindPage.kt` | Windkarte mit Partikel-Animation |
| `SearchPanel.kt` | Ortssuche |
| `Locator.kt` | Standort und Ortsname |
| `widget/` | Widget, Einstellungen, Zeichnen |
| `Ui.kt`, `Glyph.kt`, `MapSupport.kt` | Farben, Symbole, Kartengrundlage |

Datenquellen: Open-Meteo (CC BY 4.0), Deutscher Wetterdienst, ECMWF.
Karten: © OpenStreetMap-Mitwirkende, © CARTO.
