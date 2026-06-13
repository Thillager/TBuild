# TBuild - Build Tool

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-100%25-orange)](https://www.java.com/)
[![Version](https://img.shields.io/badge/Version-latest-blue)](https://github.com/Thillager/TBuild/releases/latest)

TBuild is a **powerful, user-friendly build tool** designed for managing project dependencies and packaging applications. It simplifies the build process and makes project management as easy as possible, without unnecessary complexity.

## Features

- ✅ **Dependency Management** - Easy management of project dependencies
- ✅ **Project Packaging** - Package your applications efficiently
- ✅ **Project Initialization** - Quickly set up new projects with standard structures
- ✅ **Lightweight** - Fast performance and low resource consumption
- ✅ **Platform Independent** - Runs on Windows, Linux and macOS (all Java-supporting systems)
- ✅ **GUI with Java Swing** - Native and responsive user interface
- ✅ **Command Line Interface** - Full CLI support for automation and scripting
- ✅ **No Telemetry** - Your data stays yours, no tracking
- ✅ **Configuration Files** - Simple T.xml configuration format
- ✅ **Integration** - Seamless integration with TIDE IDE

## Requirements

- **Java development kit (JDK) 25 or higher**
     - Or use the .msi/.deb installers, then the jdk version is irrelevant
- **At least 512 MB RAM**
- **50 MB free disk space**

## Installation and Usage

### Option 1: Installer

#### Linux:

1. Download the .deb file from the latest release.

2. Install:
   ```bash
   sudo apt install ./filename.deb
   ```

#### Windows:

1. Download the .msi or .exe (version dependent) installer from the latest release

2. Run by double-clicking.

### Option 2: Run Pre-compiled JAR

1. Make sure Java is installed on your system:
   ```bash
   java -version
   ```

2. Run the JAR file:
   ```bash
   java -jar TBuild.jar
   ```

3. The TBuild tool will open and be ready to use.

## How TBuild Works

### Workflow Example

1. **Initialize Project**: Start TBuild and initialize a new project
2. **Configure Dependencies**: Add dependencies via the GUI or T.xml
3. **Build Project**: Use the Build button or menu to compile and package
4. **Deploy**: Package your application for distribution
5. **Check Output**: View build results in the console

## Project Structure

```
Project/
├── src/                      # Source code
│   └── main/
│       └── java/             # Java source files
├── libs/                      # External libraries and dependencies
├── production/                # Production artifacts and builds
├── T.xml                      # Project configuration
```

## Configuration

The `T.xml` file contains the project configuration:

```xml
<project>
  <mainClass>Main</mainClass>      <!-- Main class to run -->
  <appName>MyApp</appName>         <!-- Application name -->
  <version>1.0.0</version>         <!-- Version string -->
  <dependencies>
    <!-- Dependencies go here -->
  </dependencies>
</project>
```

You can edit this file directly to configure your project dependencies and settings. TBuild provides a graphical interface to make this process easier.

## Example: Your First Project with TBuild

### Step 1: Initialize a New Project
Start TBuild and click the "Initialize Project" button or use the menu.

### Step 2: Configure Your Project
Edit the T.xml file to add your project details and dependencies.

### Step 3: Add Dependencies
Use TBuild's dependency manager to add required libraries to your project.

### Step 4: Build
- Click the **Build** button to compile and package your project

## Updates

### Frequency
- Updates come whenever I have time, ideas, or bugs to fix

### How do I install them?
- Start TBuild as administrator
- Click the "About" button
- Click the "Check for Updates" button
- Install
- Wait a moment (until the desktop icon reloads)
- Start

## Troubleshooting

### Problem: "Java not found"
**Solution**: Install Java Runtime Environment (JRE) from [java.com](https://www.java.com)

### Problem: JAR file won't run
**Solution**:
```bash
# Check Java version
java -version

# Run with explicit path
java -jar /path/to/TBuild.jar
```

### Problem: Build fails with dependency errors
**Solution**:
- Check your T.xml configuration
- Make sure all dependencies are correctly specified
- Check the error output in the console
- Verify internet connection for downloading dependencies

## Documentation and Links

- **Java Documentation**: https://docs.oracle.com/en/java/
- **GitHub Repository**: https://github.com/Thillager/TBuild
- **TIDE IDE**: https://github.com/Thillager/TIDE

## License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE) for details.
This project uses dependencies. The necessary licenses are in the THIRD_PARTY_LICENSES.md

## Built With
TBuild uses the power of proven open-source libraries for reliable dependency management and building capabilities.

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
