# TBuild - Build Tool

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-100%25-orange)](https://www.java.com/)
[![Version](https://img.shields.io/badge/Version-latest-blue)](https://github.com/Thillager/TBuild/releases/latest)

TBuild ist ein **starkes, benutzerfreundliches Build-Tool**, das für die Verwaltung von Projektabhängigkeiten und das Verpacken von Anwendungen entwickelt wurde. Es vereinfacht den Build-Prozess mit einer vollständigen grafischen Benutzeroberfläche, so dass du niemals XML-Dateien manuell bearbeiten musst. Für die Automatisierung bietet TBuild auch einen umfassenden CLI-Modus. Keine Komplexität, nur Einfachheit.

## Features

- ✅ **Abhängigkeitsverwaltung** - Einfache Verwaltung von Projektabhängigkeiten über die GUI
- ✅ **Projektverpackung** - Effizientes Verpacken deiner Anwendungen
- ✅ **Grafische Benutzeroberfläche** - Vollständige GUI-Unterstützung - kein manuelles XML-Bearbeiten nötig
- ✅ **CLI-Modus** - Vollständiges Kommandozeilen-Interface für Automatisierung und Scripting
- ✅ **Projekt-Initialisierung** - Schnelle Einrichtung neuer Projekte mit Standard-Strukturen
- ✅ **Leichtgewicht** - Schnelle Performance und geringer Ressourcenverbrauch
- ✅ **Plattformunabhängig** - Läuft auf Windows, Linux und macOS (alle Java-unterstützenden Systeme)
- ✅ **GUI mit Java Swing** - Native und responsive Benutzeroberfläche
- ✅ **Keine Telemetrie** - Deine Daten bleiben deine Daten, kein Tracking
- ✅ **Integration** - Nahtlose Integration mit der TIDE IDE

## Anforderungen

- **Java Runtime Environment (JRE) 25 oder höher**
- **Mindestens 512 MB RAM**
- **50 MB freier Festplattenspeicher**

## Installation und Verwendung

### JAR herunterladen und ausführen

1. Stelle sicher, dass Java auf deinem System installiert ist:
   ```bash
   java -version
   ```

2. Lade die neueste JAR-Datei von der [Releases-Seite](https://github.com/Thillager/TBuild/releases/latest) herunter

3. Führe die JAR-Datei aus:
   ```bash
   java -jar TBuild.jar
   ```

4. Das TBuild Tool wird sich öffnen und ist bereit zur Verwendung.

## Wie funktioniert TBuild?

### GUI-Modus - Projektmanagement

Die grafische Benutzeroberfläche von TBuild macht Projektmanagement intuitiv:

1. **Projekt initialisieren**: Klicke in der GUI auf "Neues Projekt"
2. **Abhängigkeiten konfigurieren**: Füge Abhängigkeiten über die Benutzeroberfläche hinzu und verwalte sie
3. **Build-Optionen einstellen**: Konfiguriere Ausgabe, Hauptklasse und weitere Einstellungen
4. **Projekt bauen**: Nutze den Build-Button zum Kompilieren und Verpacken
5. **Ergebnisse einsehen**: Sieh dir Build-Ausgabe und eventuelle Fehler in der Konsole an

### CLI-Modus - Automatisierung

Für Automatisierung und CI/CD-Pipelines nutze TBuilds CLI:

```bash
# Neues Projekt initialisieren
java -jar TBuild.jar --init <project-name>

# Projekt bauen
java -jar TBuild.jar --build

# Für Verteilung verpacken
java -jar TBuild.jar --build --package

# Abhängigkeiten von der Kommandozeile hinzufügen
java -jar TBuild.jar --add-dependency <dependency-name> <version>
```

## Projektstruktur

```
Project/
├── src/                      # Quellcode
│   └── main/
│       └── java/             # Java-Source-Dateien
├── libs/                      # Externe Bibliotheken und Abhängigkeiten
├── production/                # Produktions-Artefakte und Builds
├── T.xml                      # Projektkonfiguration (auto-verwaltet)
```

## Konfiguration

TBuild verwaltet die `T.xml`-Datei automatisch über seine GUI. Kein manuelles Bearbeiten erforderlich!

Die T.xml enthält deine Projektkonfiguration:

```xml
<project>
  <mainClass>Main</mainClass>      <!-- Hauptklasse zum Ausführen -->
  <appName>MeineApp</appName>      <!-- Anwendungsname -->
  <version>1.0.0</version>         <!-- Versions-String -->
  <dependencies>
    <!-- Abhängigkeiten werden automatisch verwaltet -->
  </dependencies>
</project>
```

### GUI-Konfiguration

Nutze einfach die grafische Benutzeroberfläche von TBuild, um:
- Projektnamen und Version zu setzen
- Abhängigkeiten zu verwalten
- Build-Optionen zu konfigurieren
- Ausgabeformate zu wählen

Alle Änderungen werden automatisch in T.xml gespeichert.

## Beispiel: Erstes Projekt mit TBuild

### Schritt 1: TBuild starten
```bash
java -jar TBuild.jar
```

### Schritt 2: Neues Projekt erstellen
Klicke auf "Neues Projekt" in der GUI und gib deine Projektdetails ein.

### Schritt 3: Abhängigkeiten hinzufügen
Nutze den Abhängigkeits-Manager, um Bibliotheken zu suchen und hinzuzufügen, die dein Projekt benötigt.

### Schritt 4: Deinen Code schreiben
Füge deine Java-Source-Dateien zum `src/main/java/`-Verzeichnis hinzu.

### Schritt 5: Bauen und Verpacken
- Klicke den **Build**-Button zum Kompilieren deines Projekts
- Klicke den **Package**-Button, um Verteilungspakete zu erstellen

## Updates

### Häufigkeit
- Es kommen Updates wann immer ich Zeit finde, Ideen habe oder Fehler auftreten

### Wie installiere ich sie?
- TBuild als Administrator starten
- Auf den "Über"-Button klicken
- Auf den "Nach Updates suchen"-Button klicken
- Installieren
- Kurz warten (bis die Anwendung neu lädt)
- Starten

## Troubleshooting

### Problem: "Java nicht gefunden"
**Lösung**: Installiere Java Runtime Environment (JRE) von [java.com](https://www.java.com)

### Problem: JAR-Datei lässt sich nicht ausführen
**Lösung**:
```bash
# Überprüfe Java-Version
java -version

# Führe mit explizitem Pfad aus
java -jar /pfad/zu/TBuild.jar
```

### Problem: Build schlägt mit Abhängigkeitsfehlern fehl
**Lösung**:
- Nutze den Abhängigkeits-Manager der GUI, um alle Abhängigkeiten zu überprüfen
- Sieh dir das Build-Log in der Konsole für detaillierte Fehlermeldungen an
- Überprüfe deine Internetverbindung zum Herunterladen von Abhängigkeiten

### Problem: CLI-Befehle werden nicht erkannt
**Lösung**:
```bash
# Hilfe zu verfügbaren CLI-Optionen anzeigen
java -jar TBuild.jar --help

# Überprüfe, dass du die richtige JAR-Datei ausführst
java -jar TBuild.jar --version
```

## Dokumentation und Links

- **Java Dokumentation**: https://docs.oracle.com/en/java/
- **GitHub Repository**: https://github.com/Thillager/TBuild
- **TIDE IDE**: https://github.com/Thillager/TIDE

## Lizenz

Dieses Projekt ist unter der **MIT-Lizenz** lizenziert. Siehe [LICENSE](LICENSE) für Details.
In diesem Projekt werden Dependencies genutzt. Die nötigen Lizenzen stehen in der THIRD_PARTY_LICENSES.md.

## Built With
TBuild nutzt die Power bewährter Open-Source-Bibliotheken für zuverlässiges Abhängigkeitsmanagement und Build-Fähigkeiten.

## Beitragen

Beiträge sind willkommen! Um beizutragen:

1. Forke das Repository
2. Erstelle einen Feature-Branch (`git checkout -b feature/AmazingFeature`)
3. Committe deine Änderungen (`git commit -m 'Add some AmazingFeature'`)
4. Pushe zu dem Branch (`git push origin feature/AmazingFeature`)
5. Öffne einen Pull Request

## Fragen und Support

Falls du Fragen oder Probleme hast:
- Öffne ein [GitHub Issue](https://github.com/Thillager/TBuild/issues)
- Sieh dir existierende Issues an für häufig gestellte Fragen

---
**Maintainer:** [@Thillager](https://github.com/Thillager)

Viel Erfolg beim Bauen mit TBuild!
