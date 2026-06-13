# TBuild - Build Tool

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-100%25-orange)](https://www.java.com/)
[![Version](https://img.shields.io/badge/Version-latest-blue)](https://github.com/Thillager/TBuild/releases/latest)

TBuild ist ein **starkes, benutzerfreundliches Build-Tool**, das für die Verwaltung von Projektabhängigkeiten und das Verpacken von Anwendungen entwickelt wurde. Es vereinfacht den Build-Prozess und macht Projektmanagement so einfach wie möglich, ohne unnötige Komplexität.

## Features

- ✅ **Abhängigkeitsverwaltung** - Einfache Verwaltung von Projektabhängigkeiten
- ✅ **Projektverpackung** - Effizientes Verpacken deiner Anwendungen
- ✅ **Projekt-Initialisierung** - Schnelle Einrichtung neuer Projekte mit Standard-Strukturen
- ✅ **Leichtgewicht** - Schnelle Performance und geringer Ressourcenverbrauch
- ✅ **Plattformunabhängig** - Läuft auf Windows, Linux und macOS (alle Java-unterstützenden Systeme)
- ✅ **GUI mit Java Swing** - Native und responsive Benutzeroberfläche
- ✅ **Kommandozeilen-Interface** - Vollständige CLI-Unterstützung für Automatisierung und Scripting
- ✅ **Keine Telemetrie** - Deine Daten bleiben deine Daten, kein Tracking
- ✅ **Konfigurationsdateien** - Einfaches T.xml Konfigurationsformat
- ✅ **Integration** - Nahtlose Integration mit der TIDE IDE

## Anforderungen

- **Java development kit (JDK) 25 oder höher**
     - Oder die .msi/.deb Installer nutzen, dann ist die JDK-Version irrelevant
- **Mindestens 512 MB RAM**
- **50 MB freier Festplattenspeicher**

## Installation und Verwendung

### Option 1: Installer

#### Linux:

1. .deb aus dem letzten Release herunterladen.

2. Installieren:
```bash
   sudo apt install ./dateiname.deb
```

#### Windows:

1. .msi oder .exe (versionsabhängig) Installer aus dem letzten Release herunterladen

2. Per Doppelklick ausführen.

### Option 2: Vorkompiliertes JAR ausführen

1. Stelle sicher, dass Java auf deinem System installiert ist:
```bash
   java -version
```

2. Führe die JAR-Datei aus:
```bash
   java -jar TBuild.jar
```

3. Das TBuild Tool wird sich öffnen und ist bereit zur Verwendung.

## Wie funktioniert TBuild?

### Workflow-Beispiel

1. **Projekt initialisieren**: Starte TBuild und initialisiere ein neues Projekt
2. **Abhängigkeiten konfigurieren**: Füge Abhängigkeiten über die GUI oder T.xml hinzu
3. **Projekt bauen**: Nutze den Build-Button oder das Menü zum Kompilieren und Verpacken
4. **Bereitstellung**: Verpacke deine Anwendung für die Verteilung
5. **Ergebnisse überprüfen**: Sieh dir Build-Ergebnisse in der Konsole an

## Projektstruktur

```
Project/
├── src/                      # Quellcode
│   └── main/
│       └── java/             # Java-Source-Dateien
├── libs/                      # Externe Bibliotheken und Abhängigkeiten
├── production/                # Produktions-Artefakte und Builds
├── T.xml                      # Projektkonfiguration
```

## Konfiguration

Die Datei `T.xml` enthält die Projektkonfiguration:

```xml
<project>
  <mainClass>Main</mainClass>      <!-- Hauptklasse zum Ausführen -->
  <appName>MeineApp</appName>      <!-- Anwendungsname -->
  <version>1.0.0</version>         <!-- Versions-String -->
  <dependencies>
    <!-- Abhängigkeiten gehen hier hin -->
  </dependencies>
</project>
```

Du kannst diese Datei direkt bearbeiten, um deine Projektabhängigkeiten und Einstellungen zu konfigurieren. TBuild bietet eine grafische Benutzeroberfläche, um diesen Prozess zu erleichtern.

## Beispiel: Erstes Projekt mit TBuild

### Schritt 1: Neues Projekt initialisieren
Starte TBuild und klicke den "Projekt initialisieren"-Button oder nutze das Menü.

### Schritt 2: Projekt konfigurieren
Bearbeite die T.xml Datei, um deine Projektdetails und Abhängigkeiten hinzuzufügen.

### Schritt 3: Abhängigkeiten hinzufügen
Nutze TBuilds Abhängigkeitsmanager, um erforderliche Bibliotheken zu deinem Projekt hinzuzufügen.

### Schritt 4: Bauen
- Klicke auf den **Build**-Button, um dein Projekt zu kompilieren und zu verpacken

## Updates

### Häufigkeit
- Es kommen Updates wann immer ich Zeit finde, Ideen habe oder Fehler auftreten

### Wie installiere ich sie?
- TBuild als Administrator starten
- Auf den "Über"-Button klicken
- Auf den "Nach Updates suchen"-Button klicken
- Installieren
- Kurz warten (bis das Desktop-Icon neu lädt)
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
- Überprüfe deine T.xml Konfiguration
- Stelle sicher, dass alle Abhängigkeiten korrekt angegeben sind
- Sieh dir die Fehlerausgabe in der Konsole an
- Überprüfe deine Internetverbindung zum Herunterladen von Abhängigkeiten

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
