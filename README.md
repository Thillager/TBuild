# TBuild - Build Tool

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-100%25-orange)](https://www.java.com/)
[![Version](https://img.shields.io/badge/Version-latest-blue)](https://github.com/Thillager/TBuild/releases/latest)

TBuild is a **powerful, user-friendly build tool** designed for managing project dependencies and packaging applications. It simplifies the build process with a full graphical interface, so you never have to manually edit XML files. For automation, TBuild also offers a comprehensive CLI mode. No complexity, just simplicity.

## Features

- ✅ **Dependency Management** - Easy management of project dependencies through the GUI
- ✅ **Project Packaging** - Package your applications efficiently
- ✅ **Graphical Interface** - Complete GUI support - no manual XML editing needed
- ✅ **CLI Mode** - Full command-line interface for automation and scripting
- ✅ **Project Initialization** - Quickly set up new projects with standard structures
- ✅ **Lightweight** - Fast performance and low resource consumption
- ✅ **Platform Independent** - Runs on Windows, Linux and macOS (all Java-supporting systems)
- ✅ **GUI with Java Swing** - Native and responsive user interface
- ✅ **No Telemetry** - Your data stays yours, no tracking
- ✅ **Integration** - Seamless integration with ThIDE IDE

## Requirements

- **Java 21 or higher** (with jpackage support for installers)
- **At least 512 MB RAM**
- **50 MB free disk space**

## Installation and Usage

### Download and Run JAR

1. Make sure Java is installed on your system:
   ```bash
   java -version
   ```

2. Download the latest TBuild.jar from the [releases page](https://github.com/Thillager/TBuild/releases/latest) or use the pre-compiled JAR in this repository

3. Run the JAR file:
   ```bash
   java -jar TBuild.jar
   ```

4. The TBuild tool will open and be ready to use.

## How TBuild Works

### GUI Mode - Project Management

TBuild's graphical interface makes project management intuitive:

1. **Initialize Project**: Click "Init" in the GUI
2. **Configure Dependencies**: Add and manage dependencies through the interface
3. **Set Build Options**: Configure output, main class, and other settings
4. **Build Project**: Use the Build button to compile and package
5. **View Results**: See build output and any errors in the console

### CLI Mode - Automation

For automation and CI/CD pipelines, use TBuild's CLI:

```bash
# Initialize a new standard project
java -jar TBuild.jar init

# Initialize a new JavaFX project
java -jar TBuild.jar init-javafx

# Build a project
java -jar TBuild.jar build

# Run tests
java -jar TBuild.jar test

# Export as small JAR (without dependencies)
java -jar TBuild.jar export-small

# Export as fat JAR (with dependencies)
java -jar TBuild.jar export

# Build and export as fat JAR (useful for CI/CD)
java -jar TBuild.jar build-export-fat

# Create native installer (Windows MSI, Linux DEB, macOS DMG)
java -jar TBuild.jar jpackage

# Set main class
java -jar TBuild.jar set-main com.example.MyMainClass

# Set version
java -jar TBuild.jar set-version 2.0.0
```
## Example projects

The following projects are getting packaged with TBuild.
1. ThIDE (https://github.com/Thillager/ThIDE)
2. TBuild (https://github.com/Thillager/TBuild)
3. leer
4. leer

## Project Structure

```
Project/
├── src/
│   ├── main/
│   │   └── java/             # Java source files
│   └── test/
│       └── java/             # JUnit test files
├── libs/                      # External libraries and dependencies
├── out/                       # Compiled output and build artifacts
├── T.xml                      # Project configuration (auto-managed)
```

## Configuration

TBuild manages the `T.xml` file automatically through its GUI. No manual editing required!

The T.xml contains your project configuration:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <mainClass>Main</mainClass>             <!-- Main class to run -->
  <appName>MyApp</appName>                <!-- Application name -->
  <version>1.0.0</version>                <!-- Version string -->
  <winUpgradeUuid>...</winUpgradeUuid>    <!-- Windows installer UUID -->
</project>
```

### GUI Configuration

Simply use TBuild's graphical interface to:
- Set project name and version
- Manage dependencies (search Maven Central)
- Configure build options
- Choose output formats

All changes are automatically saved to T.xml.

## Example: Your First Project with TBuild

### Step 1: Launch TBuild
```bash
java -jar TBuild.jar
```

### Step 2: Create a New Project
Click "Init" in the GUI to initialize a new project.

### Step 3: Add Dependencies
Use the Maven library search to find and add dependencies your project needs.

### Step 4: Write Your Code
Add your Java source files to the `src/main/java/` directory.

### Step 5: Build and Package
- Click the **Nur Build** button to compile your project
- Click the **Fat JAR** button to create a distribution package

## Updates

### Frequency
- Updates come whenever I have time, ideas, or bugs to

## Troubleshooting

### Problem: "Java not found"
**Solution**: Install Java Runtime Environment (JRE) 21 or higher from [java.com](https://www.java.com)

### Problem: JAR file won't run
**Solution**:
```bash
# Check Java version
java -version

# Make sure you have Java 21 or higher
# Run with explicit path
java -jar /path/to/TBuild.jar
```

### Problem: Build fails with dependency errors
**Solution**:
- Use the Maven search panel on the right side to verify and add dependencies
- Check the build log in the console for detailed error messages
- Verify your internet connection for downloading dependencies

### Problem: CLI commands not recognized
**Solution**:
```bash
# List available CLI commands by running init without a project
java -jar TBuild.jar invalid-command
```

## Documentation and Links

- **Java Documentation**: https://docs.oracle.com/en/java/
- **GitHub Repository**: https://github.com/Thillager/TBuild
- **ThIDE IDE**: https://github.com/Thillager/ThIDE

## License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE) for details.
This project uses dependencies. The necessary licenses are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)

## Built With
TBuild uses the power of proven open-source libraries for reliable dependency management and build capabilities.

## Contributing

Contributions are welcome! To contribute:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## Questions and Support

If you have questions or issues:
- Open a [GitHub Issue](https://github.com/Thillager/TBuild/issues)
- Check existing issues for frequently asked questions

---
**Maintainer:** [@Thillager](https://github.com/Thillager)

Good luck building with TBuild!
