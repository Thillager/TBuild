import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.tools.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

// JGit
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.revwalk.RevCommit;

/**
* T-build — Simple Java Build Tool
*
* Changelog v2.0:
* [NEW] Test-Support: JUnit-Tests kompilieren und ausfuehren
* [NEW] JavaFX-Download Button (laedt aktuelle JavaFX SDK fuer die laufende JVM)
* [NEW] Export-Dialog: Auswahl ob Terminal / JavaFX benoetigt wird
* [NEW] Custom JVM/jpackage Parameter in T.xml gespeichert und wiedergeladen
* [NEW] UUID-Generieren-Button
* [NEW] Projekt-Init: Auswahl Standard vs. JavaFX
* [NEW] Terminal-Input: laufendes Programm akzeptiert stdin ueber Eingabefeld
* [NEW] Aufgeraeumt UI: Toolbar als Scrollpane, Tabs fuer Konsole/Tests
* [NEW] Custom Repositories: Benutzer können eigene Maven-kompatible Repositories definieren
* [FIX] Doppelte buildRunCommand-Methode entfernt
* [FIX] Alle Java 9+ SplitpackageWarnings unterdrueckt (--add-opens)
* [FIX] DocumentBuilderFactory ohne XML-Catalog-Warnings konfiguriert
* [FIX] Classpath-Separator-Bug auf Windows
* [FIX] Pool-Shutdown mit Timeout (kein Hang mehr im CLI-Modus)
* [FIX] Absoluter Pfad bei jar cfe
* [FIX] stripModuleInfo entfernt jetzt auch MANIFEST.MF-Signaturen korrekt
* [PERF] Daemon-Threads fuer alle Hintergrundaufgaben
* [PERF] Einmaliger DocumentBuilderFactory-Instance fuer alle POM-Parses
*/
@SuppressWarnings({"deprecation", "unchecked"})
public class TBuild {

	// ── Konstanten ──────────────────────────────────────────────────────────[...]
	private static final String DEFAULT_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
	private static final File   GIT_CREDS    = new File(System.getProperty("user.home"), ".git-credentials");

	// Einmaliger DocumentBuilderFactory (thread-safe nach Konfiguration)
	private static final javax.xml.parsers.DocumentBuilderFactory DBF;
	static {
		DBF = DocumentBuilderFactory.newInstance();
		// Unterdrückt XML-Catalog-Warnungen und externe DTD-Anfragen
		try { DBF.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignored) {}
		try { DBF.setFeature("http://xml.org/sax/features/external-general-entities", false); }         catch (Exception ignored) {}
		try { DBF.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }       catch (Exception ignored) {}
		DBF.setExpandEntityReferences(false);
	}

	// ── GUI-Felder ──────────────────────────────────────────────────────────[...]
	private JFrame        frame;
	private JTextPane     console;
	private JTextPane     testConsole;
	private JTextField    searchField;
	private DefaultListModel<String> resultModel;
	private JList<String> resultList;
	private JTextField    stdinField;
	private JButton       stdinSendBtn;
	private Process       runningProcess;   // aktuell laufender Prozess (fuer stdin)

	// ── State ─────────────────────────────────────────────────────────────[...]
	private ExecutorService          pool;
	private final Set<String>        downloaded         = ConcurrentHashMap.newKeySet();
	private final Set<String>        pomDownloadStarted = ConcurrentHashMap.newKeySet();
	private final Map<String, PomData> pomCache         = new ConcurrentHashMap<>();
	private final AtomicInteger      activeDownloads    = new AtomicInteger(0);
	private boolean                  isCliMode          = false;
	private List<String>             customRepositories = new ArrayList<>(); // [NEW] Custom Repositories

	// ════════════════════════════════════════════════════════════════[...]
	//  ENTRY POINT
	// ════════════════════════════════════════════════════════════════[...]

	public static void main(String[] args) {
		// Unterdrückt JGit-interne SLF4J-"No binding"-Warnung
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
		// Unterdrückt JAXP-Implementierungs-Warnungen
		System.setProperty("javax.xml.accessExternalDTD", "");
		System.setProperty("javax.xml.accessExternalSchema", "");

		if (args.length > 0) {
			System.setProperty("java.awt.headless", "true");
			new TBuild().runCli(args);
			return;
		}
		try {
			UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf");
		} catch (Exception e) {
			try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
			catch (Exception ignored) {}
		}
		SwingUtilities.invokeLater(() -> new TBuild().createUI());
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  CLI MODE
	// ═══════════════════════════════════════════════════════════════──[...]

	private void runCli(String[] args) {
		isCliMode = true;
		loadCustomRepositories(); // [NEW] Repositories laden
		switch (args[0].toLowerCase()) {
			case "init"             -> initProject(false);
			case "init-javafx"      -> initProject(true);
			case "build"            -> executeBuild(true);
			case "test"             -> runTests();
			case "export-small"     -> { ensureBuilt(); executeExport(false, false, false); }
			case "export"           -> { ensureBuilt(); executeExport(true, false, false); }
			case "build-export-fat" -> { executeBuild(false); executeExport(true, false, false); }
			case "jpackage"         -> { ensureBuilt(); executeExport(true, false, false); executeJPackage(false, false); }
			case "set-main"         -> {
				if (args.length > 1) { saveConfig(args[1], getAppName(), getVersion()); System.out.println("[INFO] Main-Klasse: " + args[1]); }
				else { System.err.println("[FEHLER] set-main <klasse>"); System.exit(1); }
			}
			case "set-version"      -> {
				if (args.length > 1) { saveConfig(getMainClass(), getAppName(), args[1]); System.out.println("[INFO] Version: " + args[1]); }
				else { System.err.println("[FEHLER] set-version <version>"); System.exit(1); }
			}
			default -> {
				System.out.println("T-build Befehle:");
				System.out.println("  init              Projektstruktur erstellen (Standard)");
				System.out.println("  init-javafx       Projektstruktur erstellen (JavaFX)");
				System.out.println("  build             Kompilieren + Ausfuehren");
				System.out.println("  test              JUnit-Tests ausfuehren");
				System.out.println("  export-small      JAR ohne Abhaengigkeiten");
				System.out.println("  export            Fat-JAR inkl. Abhaengigkeiten");
				System.out.println("  build-export-fat  Build + Fat-JAR (CI/CD)");
				System.out.println("  jpackage          Nativen Installer erstellen");
				System.out.println("  set-main <kl>     Main-Klasse setzen");
				System.out.println("  set-version <v>   Version setzen");
				System.exit(1);
			}
		}
		System.exit(0);
	}

	private void ensureBuilt() {
		File outDir = new File("out");
		boolean needsBuild = true;
		if (outDir.exists()) {
			try { needsBuild = !Files.walk(outDir.toPath()).anyMatch(p -> p.toString().endsWith(".class")); }
			catch (IOException ignored) {}
		}
		if (needsBuild) { log("[INFO] out/ leer – starte Build...\n", Color.CYAN); executeBuild(false); }
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  GUI
	// ═══════════════════════════════════════════════════════════════──[...]

	private void createUI() {
		loadCustomRepositories(); // [NEW] Repositories beim Start laden
		frame = new JFrame("TBuild");
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		frame.setSize(Math.max(1100, screen.width * 2 / 3), Math.max(700, screen.height * 2 / 3));
		frame.setLocationRelativeTo(null);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		JPanel root = new JPanel(new BorderLayout(6, 6));
		root.setBorder(new EmptyBorder(6, 6, 6, 6));
		frame.setContentPane(root);

		// ── Toolbar ──────────────────────────────────────────────────────────
		root.add(buildToolbar(), BorderLayout.NORTH);

		// ── Konsolen-Tabs ─────────────────────────────────────────────────────
		console     = makeConsole();
		testConsole = makeConsole();
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Konsole", new JScrollPane(console));
		tabs.addTab("Tests",   new JScrollPane(testConsole));

		// ── stdin-Zeile ───────────────────────────────────────────────────────
		stdinField   = new JTextField();
		stdinSendBtn = new JButton("Senden ↵");
		stdinSendBtn.setEnabled(false);
		stdinSendBtn.addActionListener(e -> sendStdin());
		stdinField.addActionListener(e -> sendStdin());
		JPanel stdinPanel = new JPanel(new BorderLayout(4, 0));
		stdinPanel.add(new JLabel(" Eingabe: "), BorderLayout.WEST);
		stdinPanel.add(stdinField, BorderLayout.CENTER);
		stdinPanel.add(stdinSendBtn, BorderLayout.EAST);

		JPanel centerLeft = new JPanel(new BorderLayout(4, 4));
		centerLeft.add(tabs, BorderLayout.CENTER);
		centerLeft.add(stdinPanel, BorderLayout.SOUTH);

		// ── Rechte Seite: Maven-Suche ─────────────────────────────────────────
		JPanel rightPanel = buildSearchPanel();

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerLeft, rightPanel);
		split.setResizeWeight(0.78);
		root.add(split, BorderLayout.CENTER);

		// Startup-Ausgaben
		log("[INFO] TBuild bereit.\n", Color.LIGHT_GRAY);
		String[] creds = loadGitCredentials();
		if (creds != null) log("[GIT]  Angemeldet als: " + creds[0] + "\n", new Color(255, 200, 80));
		else log("[GIT]  Nicht angemeldet. Git → Anmelden für Push/Pull.\n", Color.ORANGE);
		// [NEW] Custom Repositories anzeigen
		if (!customRepositories.isEmpty()) {
			log("[INFO] Custom Repositories geladen: " + customRepositories.size() + "\n", new Color(100, 150, 255));
		}

		frame.setVisible(true);
	}

	/** Erstellt die scrollbare Toolbar-Leiste mit gruppierten Buttons */
	private JScrollPane buildToolbar() {
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));

		// Gruppe: Projekt
		bar.add(makeLabel("Projekt:"));
		bar.add(btn("Init",       e -> initDialog()));
		bar.add(btn("Main-Kl.",   e -> setMainDialog()));
		bar.add(btn("Version",    e -> setVersionDialog()));
		bar.add(btn("Name",       e -> setNameDialog()));
		bar.add(btn("UUID",       e -> uuidDialog()));
		bar.add(btn("Params",     e -> customParamsDialog()));
		bar.add(btn("Repos",      e -> repositoriesDialog())); // [NEW] Repositories Button
		bar.add(new JSeparator(JSeparator.VERTICAL));

		// Gruppe: Build
		bar.add(makeLabel("Build:"));
		bar.add(btn("Bauen+Run",  e -> new Thread(() -> executeBuild(true)).start()));
		bar.add(btn("Nur Build",  e -> new Thread(() -> executeBuild(false)).start()));
		bar.add(btn("Tests",      e -> new Thread(this::runTests).start()));
		bar.add(btn("Konsole",    e -> { console.setText(""); testConsole.setText(""); }));
		bar.add(new JSeparator(JSeparator.VERTICAL));

		// Gruppe: Export
		bar.add(makeLabel("Export:"));
		bar.add(btn("Small JAR",  e -> exportDialog(false)));
		bar.add(btn("Fat JAR",    e -> exportDialog(true)));
		bar.add(btn("jpackage",   e -> jpackageDialog()));
		bar.add(new JSeparator(JSeparator.VERTICAL));

		// Gruppe: JavaFX
		bar.add(makeLabel("JavaFX:"));
		bar.add(btn("☕ JavaFX ↓",   e -> new Thread(this::downloadJavaFX).start()));
		bar.add(new JSeparator(JSeparator.VERTICAL));

		// Gruppe: Git
		JMenuBar mb = new JMenuBar();
		mb.setOpaque(false);
		mb.setBorder(null);
		JMenu gitMenu = new JMenu("⎇ Git ▾");
		gitMenu.setForeground(new Color(255, 200, 80));
		gitMenu.setFont(gitMenu.getFont().deriveFont(Font.BOLD));
		addMenuItem(gitMenu, "🔑 Anmelden / Konto wechseln",     e -> gitLogin());
		addMenuItem(gitMenu, "📋 Status anzeigen",                 e -> gitStatus());
		gitMenu.addSeparator();
		addMenuItem(gitMenu, "📁 Lokales Repo erstellen (init)",   e -> gitInitLocal());
		addMenuItem(gitMenu, "📥 Repo klonen",                     e -> gitClone());
		addMenuItem(gitMenu, "🌐 Neues GitHub-Repo erstellen",     e -> gitCreateGitHub());
		addMenuItem(gitMenu, "🔗 Remote hinzufügen",               e -> gitAddRemote());
		gitMenu.addSeparator();
		addMenuItem(gitMenu, "🌿 Branch anzeigen / wechseln",      e -> gitShowBranches());
		addMenuItem(gitMenu, "➕ Neuen Branch erstellen",           e -> gitCreateBranch());
		mb.add(gitMenu);
		bar.add(btn("+ custom Dep",    e -> addDependencyDialog())); // Neuer Button für manuelle Dependency
		bar.add(mb);

		JScrollPane scroll = new JScrollPane(bar,
			JScrollPane.VERTICAL_SCROLLBAR_NEVER,
			JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setPreferredSize(new Dimension(0, 44));
		return scroll;
	}

	/** Baut das Maven-Suchfeld + Ergebnisliste */
	private JPanel buildSearchPanel() {
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		panel.setPreferredSize(new Dimension(300, 0));
		panel.setBorder(new TitledBorder("Maven-Bibliotheken"));

		searchField = new JTextField();
		searchField.setToolTipText("z.B. gson, flatlaf, junit...");
		JButton searchBtn = new JButton("Suchen");

		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.add(searchField, BorderLayout.CENTER);
		top.add(searchBtn,   BorderLayout.EAST);

		resultModel = new DefaultListModel<>();
		resultList  = new JList<>(resultModel);
		resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		panel.add(top, BorderLayout.NORTH);
		panel.add(new JScrollPane(resultList), BorderLayout.CENTER);

		searchBtn.addActionListener(e -> searchLibraries());
		searchField.addActionListener(e -> searchLibraries());
		resultList.addListSelectionListener(e -> {
				if (!e.getValueIsAdjusting() && resultList.getSelectedValue() != null) {
					showVersionDialog();
					resultList.clearSelection();
				}
			});
		return panel;
	}

	private JTextPane makeConsole() {
		JTextPane p = new JTextPane();
		p.setBackground(new Color(28, 28, 28));
		p.setFont(new Font("Monospaced", Font.PLAIN, 12));
		p.setEditable(false);
		return p;
	}

	private JButton btn(String label, ActionListener al) {
		JButton b = new JButton(label);
		b.addActionListener(al);
		b.setMargin(new Insets(2, 5, 2, 5));
		return b;
	}

	private JLabel makeLabel(String txt) {
		JLabel l = new JLabel(txt);
		l.setForeground(Color.GRAY);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 10f));
		return l;
	}

	private void addMenuItem(JMenu m, String label, ActionListener al) {
		JMenuItem it = new JMenuItem(label);
		it.addActionListener(al);
		m.add(it);
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  LOGGING
	// ═══════════════════════════════════════════════════════════════──[...]

	private void log(String msg, Color color) {
		if (isCliMode) { System.out.print(msg); return; }
		SwingUtilities.invokeLater(() -> appendToPane(console, msg, color));
	}

	private void logTest(String msg, Color color) {
		if (isCliMode) { System.out.print(msg); return; }
		SwingUtilities.invokeLater(() -> appendToPane(testConsole, msg, color));
	}

	private void appendToPane(JTextPane pane, String msg, Color color) {
		StyledDocument doc = pane.getStyledDocument();
		Style style = pane.addStyle("s", null);
		StyleConstants.setForeground(style, color);
		try { doc.insertString(doc.getLength(), msg, style); pane.setCaretPosition(doc.getLength()); }
		catch (BadLocationException ignored) {}
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  STDIN (Terminal-Input fuer laufendes Programm)
	// ═══════════════════════════════════════════════════════════════──[...]

	private void sendStdin() {
		if (runningProcess == null || !runningProcess.isAlive()) return;
		String line = stdinField.getText();
		stdinField.setText("");
		try {
			runningProcess.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
			runningProcess.getOutputStream().flush();
			log("> " + line + "\n", new Color(100, 200, 255));
		} catch (IOException e) {
			log("[FEHLER] stdin: " + e.getMessage() + "\n", Color.RED);
		}
	}

	private void setRunningProcess(Process p) {
		runningProcess = p;
		boolean alive = (p != null && p.isAlive());
		SwingUtilities.invokeLater(() -> {
				stdinSendBtn.setEnabled(alive);
				stdinField.setEnabled(alive);
			});
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  PROJECT INIT
	// ═══════════════════════════════════════════════════════════════──[...]

	private void initDialog() {
		if (isCliMode) { initProject(false); return; }
		String[] options = {"Standard (Swing / Konsole)", "JavaFX"};
		int choice = JOptionPane.showOptionDialog(frame,
			"Welchen Projekttyp möchtest du erstellen?",
			"Projekt initialisieren",
			JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
			null, options, options[0]);
		if (choice < 0) return;
		new Thread(() -> initProject(choice == 1)).start();
	}

	private void initProject(boolean javafx) {
		try {
			new File("src/main/java").mkdirs();
			new File("src/test/java").mkdirs();
			new File("libs").mkdirs();
			File mainFile = new File("src/main/java/Main.java");
			if (!mainFile.exists()) {
				try (PrintWriter pw = new PrintWriter(mainFile)) {
					if (javafx) {
						pw.println("import javafx.application.Application;");
						pw.println("import javafx.scene.Scene;");
						pw.println("import javafx.scene.control.Label;");
						pw.println("import javafx.scene.layout.StackPane;");
						pw.println("import javafx.stage.Stage;");
						pw.println();
						pw.println("public class Main extends Application {");
							pw.println("    @Override");
							pw.println("    public void start(Stage stage) {");
								pw.println("        stage.setTitle(\"Meine JavaFX-App\");");
								pw.println("        stage.setScene(new Scene(new StackPane(new Label(\"Hallo JavaFX!\")), 400, 300));");
								pw.println("        stage.show();");
								pw.println("    }");
							pw.println("    public static void main(String[] args) { launch(args); }");
							pw.println("}");
					} else {
						pw.println("public class Main {");
							pw.println("    public static void main(String[] args) {");
								pw.println("        System.out.println(\"Hallo von T-build!\");");
								pw.println("    }");
							pw.println("}");
					}
				}
			}
			// Beispiel-Test anlegen
			File testFile = new File("src/test/java/MainTest.java");
			if (!testFile.exists()) {
				try (PrintWriter pw = new PrintWriter(testFile)) {
					pw.println("import org.junit.jupiter.api.Test;");
					pw.println("import static org.junit.jupiter.api.Assertions.*;");
					pw.println();
					pw.println("class MainTest {");
						pw.println("    @Test");
						pw.println("    void example() {");
							pw.println("        assertEquals(2, 1 + 1);");
							pw.println("    }");
						pw.println("}");
				}
			}
			saveConfig("Main", "MeinProjekt", "1.0.0");
			log("[ERFOLG] Projekt" + (javafx ? " (JavaFX)" : "") + " initialisiert.\n", Color.GREEN);
			if (javafx) {
				log("[INFO] JavaFX-JARs noch nicht vorhanden – drücke 'JavaFX ↓' um sie herunterzuladen.\n", Color.CYAN);
			}
			log("[INFO] Beispiel-Test in src/test/java/MainTest.java erstellt.\n", Color.LIGHT_GRAY);
		} catch (Exception e) {
			log("[FEHLER] Init fehlgeschlagen: " + e.getMessage() + "\n", Color.RED);
		}
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  BUILD & RUN
	// ═══════════════════════════════════════════════════════════════──[...]

	private void executeBuild(boolean runAfter) {
		try {
			File srcDir = new File("src/main/java");
			if (!srcDir.exists()) { log("[FEHLER] src/main/java fehlt.\n", Color.RED); if (isCliMode) System.exit(1); return; }

			File outDir = new File("out");
			outDir.mkdirs();

			List<String> javafxJars   = collectJavafxJars();
			String       classpath    = buildClasspath();
			JavaCompiler compiler     = ToolProvider.getSystemJavaCompiler();
			if (compiler == null) { log("[FEHLER] Kein Java-Compiler gefunden (JDK benötigt).\n", Color.RED); if (isCliMode) System.exit(1); return; }

			List<File> sources = new ArrayList<>();
			Files.walk(srcDir.toPath()).filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toFile()));
			if (sources.isEmpty()) { log("[FEHLER] Keine .java-Dateien in src/main/java.\n", Color.RED); return; }

			DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
			StandardJavaFileManager fm = compiler.getStandardFileManager(diag, null, StandardCharsets.UTF_8);

			List<String> opts = new ArrayList<>(Arrays.asList(
					"-d", outDir.getAbsolutePath(),
					"-sourcepath", srcDir.getAbsolutePath(),
					"-classpath", classpath,
					"-encoding", "UTF-8",
					"-Xlint:none"       // unterdrückt unkritische Warnungen
				));
			if (!javafxJars.isEmpty()) {
				opts.add("--module-path"); opts.add(String.join(File.pathSeparator, javafxJars));
				opts.add("--add-modules"); opts.add(detectJavafxModules(javafxJars));
			}

			boolean ok = compiler.getTask(null, fm, diag, opts, null, fm.getJavaFileObjectsFromFiles(sources)).call();
			fm.close();

			if (!ok) {
				log("[FEHLER] Kompilierung fehlgeschlagen:\n", Color.RED);
				for (Diagnostic<?> d : diag.getDiagnostics()) {
					if (d.getKind() == Diagnostic.Kind.ERROR)
					log("  Zeile " + d.getLineNumber() + ": " + d.getMessage(Locale.GERMAN) + "\n", Color.ORANGE);
				}
				if (isCliMode) System.exit(1);
				return;
			}
			log("[ERFOLG] Kompilierung erfolgreich.\n", Color.GREEN);
			File resourcesDir = new File("src/main/resources");
			if (resourcesDir.exists()) {
				Files.walk(resourcesDir.toPath()).forEach(src -> {
						try {
							Path dest = outDir.toPath().resolve(
								resourcesDir.toPath().relativize(src));

							if (Files.isDirectory(src)) {
								Files.createDirectories(dest);
							} else {
								Files.createDirectories(dest.getParent());
								Files.copy(src, dest,
									StandardCopyOption.REPLACE_EXISTING);
							}
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					});

				log("[INFO] Ressourcen kopiert.\n", Color.LIGHT_GRAY);
			}

			if (runAfter && !isCliMode) {
				log("▶ Starte Programm...\n", Color.GREEN);
				log("──────────────────────────────────────────\n", Color.GRAY);
				List<String> cmd = buildRunCommand(getJavaExe(), classpath, javafxJars, false);
				ProcessBuilder pb = new ProcessBuilder(cmd);
				pb.environment().put("JAVA_TOOL_OPTIONS", ""); // unterdrückt JAVA_TOOL_OPTIONS-Warnungen
				pb.redirectErrorStream(true);
				Process p = pb.start();
				setRunningProcess(p);
				Thread drain = drainAsync(p.getInputStream(), console);
				int exit = p.waitFor();
				drain.join(10_000);
				setRunningProcess(null);
				log("──────────────────────────────────────────\n", Color.GRAY);
				log("[INFO] Prozess beendet, Exit-Code: " + exit + "\n", exit == 0 ? Color.LIGHT_GRAY : Color.ORANGE);
			}
		} catch (Exception e) {
			log("[FEHLER] Build abgestürzt: " + e.getMessage() + "\n", Color.RED);
			if (isCliMode) System.exit(1);
		}
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  TESTS (JUnit 5)
	// ═══════════════════════════════════════════════════════════════──[...]

	private void runTests() {
		logTest("[TEST] Starte Test-Lauf...\n", Color.CYAN);

		File testSrcDir = new File("src/test/java");
		if (!testSrcDir.exists()) { logTest("[TEST] Kein src/test/java Ordner.\n", Color.ORANGE); return; }

		// JUnit-JARs suchen
		List<File> junitJars = new ArrayList<>();
		File libs = new File("libs");
		if (libs.exists()) {
			for (File f : Objects.requireNonNull(libs.listFiles())) {
				String n = f.getName().toLowerCase();
				if (f.getName().endsWith(".jar") && (
						n.contains("junit") || n.contains("junit-jupiter") ||
						n.contains("opentest") || n.contains("junit-platform") ||
						n.contains("apiguardian")))
				junitJars.add(f);
			}
		}
		if (junitJars.isEmpty()) {
			logTest("[TEST] Keine JUnit-JARs in libs/. Bitte junit-jupiter-api und junit-platform-launcher herunterladen.\n", Color.ORANGE);
			logTest("[TEST] Suche nach 'junit-jupiter' in der Maven-Suche und lade die gewünschte Version herunter.\n", Color.LIGHT_GRAY);
			return;
		}

		try {
			// Test-Quellen kompilieren
			File testOutDir = new File("out-test");
			testOutDir.mkdirs();

			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
			if (compiler == null) { logTest("[TEST] Kein Compiler gefunden.\n", Color.RED); return; }

			List<File> sources = new ArrayList<>();
			Files.walk(testSrcDir.toPath()).filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toFile()));
			if (sources.isEmpty()) { logTest("[TEST] Keine Test-Dateien gefunden.\n", Color.ORANGE); return; }

			// Classpath: out/ + libs/ + test-out
			StringBuilder cp = new StringBuilder(new File("out").getAbsolutePath());
			cp.append(File.pathSeparator).append(testOutDir.getAbsolutePath());
			for (File f : junitJars) cp.append(File.pathSeparator).append(f.getAbsolutePath());
			if (libs.exists()) for (File f : Objects.requireNonNull(libs.listFiles()))
			if (f.getName().endsWith(".jar") && !isJavafxJar(f.getName()))
			cp.append(File.pathSeparator).append(f.getAbsolutePath());

			List<String> javafxJars = collectJavafxJars();
			List<String> opts = new ArrayList<>(Arrays.asList(
					"-d", testOutDir.getAbsolutePath(),
					"-classpath", cp.toString(),
					"-encoding", "UTF-8",
					"-Xlint:none"
				));
			if (!javafxJars.isEmpty()) {
				opts.add("--module-path"); opts.add(String.join(File.pathSeparator, javafxJars));
				opts.add("--add-modules"); opts.add(detectJavafxModules(javafxJars));
			}

			DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
			StandardJavaFileManager fm = compiler.getStandardFileManager(diag, null, StandardCharsets.UTF_8);
			boolean ok = compiler.getTask(null, fm, diag, opts, null, fm.getJavaFileObjectsFromFiles(sources)).call();
			fm.close();

			if (!ok) {
				logTest("[TEST] Test-Kompilierung fehlgeschlagen:\n", Color.RED);
				for (Diagnostic<?> d : diag.getDiagnostics())
				if (d.getKind() == Diagnostic.Kind.ERROR)
				logTest("  " + d.getMessage(Locale.GERMAN) + "\n", Color.ORANGE);
				return;
			}
			logTest("[TEST] Test-Kompilierung erfolgreich.\n", Color.GREEN);

			// JUnit Platform Launcher per Prozess starten
			// Suche nach junit-platform-console-standalone oder launcher
			File launcher = null;
			for (File f : junitJars) {
				if (f.getName().contains("junit-platform-console-standalone") ||
					f.getName().contains("junit-platform-launcher")) {
					launcher = f; break;
				}
			}

			if (launcher == null) {
				// Fallback: ConsoleLauncher direkt aufrufen wenn JAR dabei ist
				logTest("[TEST] junit-platform-console-standalone nicht gefunden.\n", Color.ORANGE);
				logTest("[TEST] Bitte 'junit-platform-console-standalone' aus Maven suchen und laden.\n", Color.LIGHT_GRAY);
				return;
			}

			List<String> cmd = new ArrayList<>();
			cmd.add(getJavaExe());
			cmd.add("-cp");
			cmd.add(cp.toString() + File.pathSeparator + launcher.getAbsolutePath());
			cmd.add("org.junit.platform.console.ConsoleLauncher");
			cmd.add("--scan-classpath=" + testOutDir.getAbsolutePath());
			cmd.add("--details=tree");

			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			Thread drain = drainAsync(p.getInputStream(), testConsole);
			int exit = p.waitFor();
			drain.join(15_000);

			logTest("\n[TEST] Beendet, Exit-Code: " + exit + "\n",
				exit == 0 ? new Color(80, 200, 120) : Color.RED);

			// Aufräumen
			try { deleteDirectory(testOutDir); } catch (Exception ignored) {}
		} catch (Exception e) {
			logTest("[TEST] Fehler: " + e.getMessage() + "\n", Color.RED);
		}
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  JAVAFX DOWNLOAD
	// ═══════════════════════════════════════════════════════════════──[...]

	private void downloadJavaFX() {
		// Ermittle laufende JVM-Version für passende JavaFX-Version
		String jvmVersion = System.getProperty("java.version", "17");
		int major = 17;
		try {
			String v = jvmVersion.split("[._]")[0];
			major = Integer.parseInt(v);
		} catch (Exception ignored) {}
		final int javaMajor = major;

		// Bestimme Plattform
		String os = System.getProperty("os.name", "").toLowerCase();
		String arch = System.getProperty("os.arch", "").toLowerCase();
		String platform;
		if (os.contains("win"))       platform = "win";
		else if (os.contains("mac"))  platform = arch.contains("aarch") ? "osx-aarch64" : "osx";
		else                          platform = arch.contains("aarch") ? "linux-aarch64" : "linux";

		// Verfügbare JavaFX-Versionen (aktuell gepflegt)
		String[] versions = {
			"25",
			"24.0.2",
			"24.0.1",
			"24",
			"23.0.2",
			"23.0.1",
			"23",
			"22.0.2",
			"22.0.1",
			"22",
			"21.0.8",
			"21.0.7",
			"21.0.6",
			"21.0.5",
			"21.0.4",
			"17.0.16",
			"17.0.15",
			"17.0.14"
		};
		// Wähle passende Standardversion
		String defaultVersion = "21.0.4";
		for (String v : versions) {
			int vMajor = Integer.parseInt(v.split("\\.")[0]);
			if (vMajor <= javaMajor) { defaultVersion = v; break; }
		}

		if (!isCliMode) {
			String chosen = (String) JOptionPane.showInputDialog(frame,
				"JavaFX-Version wählen (JVM: " + jvmVersion + ", OS: " + platform + "):",
				"JavaFX herunterladen",
				JOptionPane.PLAIN_MESSAGE, null, versions, defaultVersion);
			if (chosen == null) return;
			final String fxVersion = chosen;
			new Thread(() -> doDownloadJavaFX(fxVersion, platform)).start();
		} else {
			doDownloadJavaFX(defaultVersion, platform);
		}
	}

	private void doDownloadJavaFX(String version, String platform) {
		log("[JavaFX] Lade JavaFX " + version + " für " + platform + "...\n", Color.CYAN);

		// Gluon-Download-URL (stabile Quelle)
		String[] modules = {
			"javafx.base",
			"javafx.controls",
			"javafx.fxml",
			"javafx.graphics",
			"javafx.media",
			"javafx.swing",
			"javafx.web"
		};

		String[] artifacts = {
			"javafx-base",
			"javafx-controls",
			"javafx-fxml",
			"javafx-graphics",
			"javafx-media",
			"javafx-swing",
			"javafx-web"
		};

		new File("libs").mkdirs();
		int downloaded = 0;
		for (int i = 0; i < artifacts.length; i++) {
			String artifact = artifacts[i];
			String jarName  = artifact + "-" + version + "-" + platform + ".jar";
			File   dest     = new File("libs/" + jarName);
			if (dest.exists() && dest.length() > 1000) {
				log("[JavaFX] Bereits vorhanden: " + jarName + "\n", Color.GRAY);
				downloaded++;
				continue;
			}
			// Maven Central URL
			String groupPath = "org/openjfx";
			String url = "https://repo1.maven.org/maven2/" + groupPath + "/" + artifact + "/" + version
			+ "/" + artifact + "-" + version + "-" + platform + ".jar";
			log("[JavaFX] Lade: " + jarName + "\n", Color.GRAY);
			if (downloadFile(url, dest.getPath())) downloaded++;
			else log("[JavaFX] Konnte nicht laden: " + jarName + " (evtl. nicht verfügbar für diese Plattform)\n", Color.ORANGE);
		}
		if (downloaded > 0) log("[JavaFX] ✓ " + downloaded + " JavaFX-Module heruntergeladen.\n", new Color(80, 200, 120));
		else log("[JavaFX] Keine Module heruntergeladen. Prüfe Version und Plattform.\n", Color.RED);
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  EXPORT DIALOGE
	// ═══════════════════════════════════════════════════════════════──[...]

	/** Dialog für JAR-Export mit Optionen */
	private void exportDialog(boolean fat) {
		JCheckBox javafxBox  = new JCheckBox("JavaFX wird benötigt (JavaFX-JARs nicht einbetten)", collectJavafxJars().size() > 0);
		JCheckBox termBox    = new JCheckBox("Terminal/Konsole benötigt (kein GUI-Launcher)", false);
		JPanel panel = new JPanel(new GridLayout(0, 1, 0, 6));
		panel.add(new JLabel(fat ? "Fat-JAR Export (inkl. Abhängigkeiten)" : "Small-JAR Export (ohne Abhängigkeiten)"));
		panel.add(javafxBox);
		panel.add(termBox);
		int r = JOptionPane.showConfirmDialog(frame, panel, fat ? "Export Fat-JAR" : "Export Small-JAR",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (r != JOptionPane.OK_OPTION) return;
		boolean javafx  = javafxBox.isSelected();
		boolean terminal = termBox.isSelected();
		new Thread(() -> { ensureBuilt(); executeExport(fat, javafx, terminal); }).start();
	}

	/** Dialog für jpackage mit Optionen */
	private void jpackageDialog() {
		JCheckBox javafxBox  = new JCheckBox("JavaFX wird benötigt", collectJavafxJars().size() > 0);
		JCheckBox termBox    = new JCheckBox("Terminal/Konsole-App (kein GUI-Subsystem)", false);
		JPanel panel = new JPanel(new GridLayout(0, 1, 0, 6));
		panel.add(new JLabel("jpackage – Native Installer erstellen"));
		panel.add(javafxBox);
		panel.add(termBox);
		int r = JOptionPane.showConfirmDialog(frame, panel, "jpackage",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (r != JOptionPane.OK_OPTION) return;
		boolean javafx   = javafxBox.isSelected();
		boolean terminal = termBox.isSelected();
		new Thread(() -> { ensureBuilt(); executeExport(true, javafx, terminal); executeJPackage(javafx, terminal); }).start();
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  EXPORT JAR
	// ═══════════════════════════════════════════════════════════════──[...]

	private void executeExport(boolean fat, boolean needsJavaFX, boolean needsTerminal) {
		try {
			String mainClass = getMainClass();
			String appName   = getAppName();
			String jarName   = fat ? appName + ".jar" : appName + "-small.jar";
			File   tempDir   = new File("build_temp");
			log("[INFO] Starte Export: " + jarName + "...\n", Color.CYAN);

			if (tempDir.exists()) deleteDirectory(tempDir);
			tempDir.mkdirs();

			File outDir = new File("out");
			if (!outDir.exists() || listFiles(outDir).isEmpty()) {
				log("[FEHLER] 'out' ist leer. Bitte erst bauen.\n", Color.RED);
				if (isCliMode) System.exit(1);
				return;
			}
			copyDirectory(outDir, tempDir);

			if (fat) {
				File libDir = new File("libs");
				if (libDir.exists()) {
					for (File f : listFiles(libDir)) {
						if (!f.getName().endsWith(".jar")) continue;
						if (isJavafxJar(f.getName())) {
							log("   → Überspringe JavaFX-JAR: " + f.getName() + "\n", Color.GRAY); continue;
						}
						log("   → Integriere: " + f.getName() + "\n", Color.GRAY);
						ProcessBuilder pb = new ProcessBuilder(getJarExe(), "xf", f.getAbsolutePath());
						pb.directory(tempDir);
						pb.redirectErrorStream(true);
						Process proc = pb.start();
						drainAsync(proc.getInputStream(), console).join(30_000);
						proc.waitFor();
					}
				}
			}

			stripModuleInfo(tempDir);
			writeManifest(tempDir, mainClass, needsJavaFX, needsTerminal);

			log("[INFO] Packe JAR...\n", Color.CYAN);
			String jarTarget = new File(jarName).getAbsolutePath();
			// cfm statt cfe, damit unser angepasstes Manifest verwendet wird
			ProcessBuilder pb = new ProcessBuilder(getJarExe(), "cfm", jarTarget,
				new File(tempDir, "META-INF/MANIFEST.MF").getAbsolutePath(), ".");
			pb.directory(tempDir);
			pb.redirectErrorStream(true);
			Process proc = pb.start();
			drainAsync(proc.getInputStream(), console).join(60_000);
			int exit = proc.waitFor();
			deleteDirectory(tempDir);

			if (exit != 0) {
				log("[FEHLER] jar beendet mit Exit-Code " + exit + ".\n", Color.RED);
				if (isCliMode) System.exit(1);
				return;
			}
			log("[ERFOLG] " + jarName + " erstellt!\n", Color.GREEN);

			// Starthinweis
			List<String> javafxJars = collectJavafxJars();
			if (needsJavaFX && !javafxJars.isEmpty()) {
				String mp = new File("libs").getAbsolutePath();
				String mods = detectJavafxModules(javafxJars);
				log("[INFO] Starten mit:\n       java --module-path " + mp + " --add-modules " + mods + " -jar " + jarName + "\n", Color.LIGHT_GRAY);
			} else {
				log("[INFO] Starten mit: java -jar " + jarName + "\n", Color.LIGHT_GRAY);
			}
		} catch (Exception e) {
			log("[FEHLER] Export fehlgeschlagen: " + e.getMessage() + "\n", Color.RED);
			if (isCliMode) System.exit(1);
		}
	}

	/** Schreibt ein sauberes MANIFEST.MF mit korrekten JavaFX/Terminal-Flags */
	private void writeManifest(File tempDir, String mainClass, boolean javafx, boolean terminal) throws IOException {
		File metaInf = new File(tempDir, "META-INF");
		metaInf.mkdirs();
		List<String> javafxJars = collectJavafxJars();
		try (PrintWriter pw = new PrintWriter(new File(metaInf, "MANIFEST.MF"))) {
			pw.println("Manifest-Version: 1.0");
			pw.println("Main-Class: " + mainClass);
			if (javafx && !javafxJars.isEmpty()) {
				String mods = detectJavafxModules(javafxJars);
				pw.println("Add-Modules: " + mods);
			}
			String extraParams = getCustomRunParams();
			if (!extraParams.isEmpty()) pw.println("X-Custom-JVM-Args: " + extraParams);
			pw.println();
		}
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  JPACKAGE
	// ═══════════════════════════════════════════════════════════════──[...]

	private void executeJPackage(boolean needsJavaFX, boolean needsTerminal) {
		try {
			String jpackage = getJPackageExe();
			if (jpackage == null) { log("[FEHLER] jpackage nicht gefunden (JDK 14+ benötigt).\n", Color.RED); if (isCliMode) System.exit(1); return; }

			File fatJar = new File(getAppName() + ".jar");
			if (!fatJar.exists()) { log("[FEHLER] " + getAppName() + ".jar nicht gefunden. Erst exportieren.\n", Color.RED); if (isCliMode) System.exit(1); return; }

			String appName   = getAppName();
			String mainClass = getMainClass();
			File distDir = new File("dist");
			if (distDir.exists()) deleteDirectory(distDir);
			distDir.mkdirs();

			String os = System.getProperty("os.name", "").toLowerCase();
			String type = os.contains("win") ? "msi" : os.contains("mac") ? "dmg" : "deb";

			log("[INFO] Starte jpackage (" + type + ") für: " + appName + "\n", Color.CYAN);

			List<String> cmd = new ArrayList<>(Arrays.asList(
					jpackage,
					"--input",       ".",
					"--main-jar",    appName + ".jar",
					"--main-class",  mainClass,
					"--name",        appName,
					"--dest",        "dist",
					"--type",        type,
					"--app-version", getVersion()
				));

			// JavaFX --java-options
			List<String> javafxJars = collectJavafxJars();
			List<String> javaOpts = new ArrayList<>();
			if (needsJavaFX && !javafxJars.isEmpty()) {
				String mods = detectJavafxModules(javafxJars);
				String libsPath = new File("libs").getAbsolutePath();
				javaOpts.add("--module-path \"" + libsPath + "\"");
				javaOpts.add("--add-modules " + mods);
				javaOpts.add("--enable-native-access=" + mods.split(",")[0]);
			}

			// Custom JVM-Parameter
			String extra = getCustomJPackageParams();
			if (!extra.isEmpty()) {
				for (String opt : extra.split("\\s+(?=--)")) javaOpts.add(opt.trim());
			}
			if (!javaOpts.isEmpty()) { cmd.add("--java-options"); cmd.add(String.join(" ", javaOpts)); }

			if (os.contains("win")) {
				cmd.add("--win-upgrade-uuid");
				cmd.add(getUpgradeUuid());
				cmd.add("--win-shortcut");
				cmd.add("--win-menu");
				if (needsTerminal) cmd.add("--win-console"); // Nur Konsole anzeigen, wenn explizit gewünscht!
			} else if (!os.contains("mac")) {
				cmd.add("--linux-shortcut");
				cmd.add("--linux-app-category"); cmd.add("Application");
				String pkg = appName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
				cmd.add("--linux-package-name"); cmd.add(pkg);
			}

			// Custom jpackage-Argumente aus T.xml (Fehlerbehebung für Option+Wert-Trennung)
		String customJPkg = getCustomJPackageArgs();
		if (!customJPkg.isEmpty()) {
			// Erkennt Argumente wie: --icon assets/icon.ico oder --name "Mein Name"
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'").matcher(customJPkg);
			while (m.find()) {
				String arg = m.group();
				// Entfernt umschließende Anführungszeichen, falls vorhanden
				if (arg.startsWith("\"") && arg.endsWith("\"")) arg = arg.substring(1, arg.length() - 1);
				else if (arg.startsWith("'") && arg.endsWith("'")) arg = arg.substring(1, arg.length() - 1);
				
				if (!arg.isEmpty()) {
					cmd.add(arg);
				}
			}
		}

			log("[INFO] Befehl: " + String.join(" ", cmd) + "\n", Color.CYAN);
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process proc = pb.start();
			drainAsync(proc.getInputStream(), console).join(300_000);
			int exit = proc.waitFor();

			if (exit != 0) {
				log("[FEHLER] jpackage Exit-Code " + exit + ".\n", Color.RED);
				log("[TIPP] Windows: WiX Toolset installieren → https://wixtoolset.org\n", Color.ORANGE);
				log("[TIPP] Linux (deb): sudo apt install fakeroot\n", Color.ORANGE);
				if (isCliMode) System.exit(1);
				return;
			}
			if (distDir.listFiles() != null) for (File f : distDir.listFiles())
			log("[ERFOLG] Installer: dist/" + f.getName() + "\n", Color.GREEN);
		} catch (Exception e) {
			log("[FEHLER] jpackage: " + e.getMessage() + "\n", Color.RED);
			if (isCliMode) System.exit(1);
		}
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  BUILD HELPER
	// ═══════════════════════════════════════════════════════════════──[...]

	private List<String> collectJavafxJars() {
		List<String> list = new ArrayList<>();
		File lib = new File("libs");
		if (lib.exists() && lib.listFiles() != null)
		for (File f : lib.listFiles())
		if (f.getName().endsWith(".jar") && isJavafxJar(f.getName()))
		list.add(f.getAbsolutePath());
		return list;
	}

	private boolean isJavafxJar(String name) {
		String n = name.toLowerCase();
		return n.startsWith("javafx-") || n.startsWith("javafx.");
	}

	private String detectJavafxModules(List<String> jars) {
		List<String> mods = new ArrayList<>();
		boolean hasBase = false;
		for (String jar : jars) {
			String n = new File(jar).getName().toLowerCase();
			if ((n.contains("javafx-base") || n.contains("javafx.base")) && !hasBase) { mods.add("javafx.base"); hasBase = true; }
			else if (n.contains("javafx-controls") || n.contains("javafx.controls")) mods.add("javafx.controls");
			else if (n.contains("javafx-fxml")     || n.contains("javafx.fxml"))     mods.add("javafx.fxml");
			else if (n.contains("javafx-graphics") || n.contains("javafx.graphics"))  mods.add("javafx.graphics");
			else if (n.contains("javafx-media")    || n.contains("javafx.media"))     mods.add("javafx.media");
			else if (n.contains("javafx-swing")    || n.contains("javafx.swing"))     mods.add("javafx.swing");
			else if (n.contains("javafx-web")      || n.contains("javafx.web"))       mods.add("javafx.web");
		}
		if (!hasBase && !mods.isEmpty()) mods.add(0, "javafx.base");
		if (mods.contains("javafx.controls") && !mods.contains("javafx.graphics")) mods.add(1, "javafx.graphics");
		return mods.isEmpty() ? "javafx.controls" : String.join(",", mods);
	}

	private List<String> buildRunCommand(String javaExe, String classpath, List<String> javafxJars, boolean terminal) {
		List<String> cmd = new ArrayList<>();
		cmd.add(javaExe);
		// Unterdrückt JVM-interne Warnungen (JDK 17+)
		cmd.add("-Djava.util.logging.config.file=");
		if (!javafxJars.isEmpty()) {
			String mp   = String.join(File.pathSeparator, javafxJars);
			String mods = detectJavafxModules(javafxJars);
			cmd.add("--module-path"); cmd.add(mp);
			cmd.add("--add-modules"); cmd.add(mods);
			cmd.add("--enable-native-access=" + mods.split(",")[0]);
			log("[INFO] JavaFX-Module: " + mods + "\n", Color.CYAN);
		}
		// Custom JVM-Params aus T.xml
		String extra = getCustomRunParams();
		if (!extra.isEmpty()) for (String p : extra.split("\\s+")) { String t = p.trim(); if (!t.isEmpty()) cmd.add(t); }
		cmd.add("-cp"); cmd.add(classpath);
		cmd.add(getMainClass());
		return cmd;
	}

	private String buildClasspath() {
		StringBuilder cp = new StringBuilder(new File("out").getAbsolutePath());
		File lib = new File("libs");
		if (lib.exists() && lib.listFiles() != null)
		for (File f : lib.listFiles())
		if (f.getName().endsWith(".jar")) cp.append(File.pathSeparator).append(f.getAbsolutePath());
		return cp.toString();
	}

	private String getJavaExe() {
		String home = System.getProperty("java.home");
		if (home != null) {
			for (String path : new String[]{
					home + File.separator + "bin" + File.separator + "java",
					home + File.separator + ".." + File.separator + "bin" + File.separator + "java"}) {
				File f = new File(path);
				if (f.exists()) { try { return f.getCanonicalPath(); } catch (IOException e) { return f.getAbsolutePath(); } }
				File fw = new File(path + ".exe");
				if (fw.exists()) { try { return fw.getCanonicalPath(); } catch (IOException e) { return fw.getAbsolutePath(); } }
			}
		}
		return "java";
	}

	private String getJarExe() {
		String home = System.getProperty("java.home");
		if (home != null) {
			for (String path : new String[]{
					home + File.separator + "bin" + File.separator + "jar",
					home + File.separator + ".." + File.separator + "bin" + File.separator + "jar"}) {
				File f = new File(path); if (f.exists()) return f.getAbsolutePath();
				File fw = new File(path + ".exe"); if (fw.exists()) return fw.getAbsolutePath();
			}
		}
		return "jar";
	}

	private String getJPackageExe() {
		String home = System.getProperty("java.home");
		if (home != null) {
			for (String path : new String[]{
					home + File.separator + "bin" + File.separator + "jpackage",
					home + File.separator + ".." + File.separator + "bin" + File.separator + "jpackage"}) {
				File f = new File(path); if (f.exists()) return f.getAbsolutePath();
				File fw = new File(path + ".exe"); if (fw.exists()) return fw.getAbsolutePath();
			}
		}
		try { Process p = new ProcessBuilder("jpackage", "--version").start(); p.waitFor(); if (p.exitValue() == 0) return "jpackage"; } catch (Exception ignored) {}
		return null;
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  MAVEN SUCHE mit CUSTOM REPOSITORIES
	// ═══════════════════════════════════════════════════════════════──[...]

	// [NEW] Intelligente Suche mit GitHub-Unterstützung
private void searchLibraries() {
	String q = searchField.getText().trim();
	if (q.isEmpty()) return;
	new Thread(() -> {
			try {
				SwingUtilities.invokeLater(() -> { resultModel.clear(); resultModel.addElement("Suche…"); });
				
				List<String> hits = new ArrayList<>();
				
				// 1. Versuche Maven Central (Standard)
				try {
					String enc = URLEncoder.encode(q, StandardCharsets.UTF_8.name());
					String url = "https://search.maven.org/solrsearch/select?q=a:" + enc + "+OR+" + enc + "&rows=30&wt=json";
					String json = fetchUrl(url);
					Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
					while (m.find()) hits.add(m.group(1));
					log("[SEARCH] Maven Central: " + hits.size() + " Ergebnisse\n", Color.CYAN);
				} catch (Exception e) {
					log("[SEARCH] Maven Central nicht verfügbar\n", Color.ORANGE);
				}
				
				// 2. Suche in Custom Repositories (ohne API - nur manuelle URLs)
				for (String repoUrl : customRepositories) {
					try {
						log("[SEARCH] Suche in Custom Repo: " + repoUrl + "\n", new Color(150, 150, 150));
						
						// Versuche Maven-Metadaten zu finden (funktioniert mit GitHub)
						List<String> repoHits = searchRepositoryStructure(repoUrl, q);
						for (String hit : repoHits) {
							if (!hits.contains(hit)) {
								hits.add(hit);
								log("[SEARCH] Found in Custom Repo: " + hit + "\n", new Color(100, 150, 255));
							}
						}
					} catch (Exception e) {
						log("[SEARCH] Custom Repo Fehler: " + e.getMessage() + "\n", Color.ORANGE);
					}
				}
				
				SwingUtilities.invokeLater(() -> {
						resultModel.clear();
						if (hits.isEmpty()) {
							resultModel.addElement("Keine Ergebnisse.");
							resultModel.addElement("--- Nutze 'Manual Dep' für direkte Installation ---");
						} else {
							hits.forEach(resultModel::addElement);
						}
					});
			} catch (Exception e) { 
				log("[FEHLER] Suche: " + e.getMessage() + "\n", Color.RED); 
			}
		}, "maven-search").start();
}

// [NEW] Durchsuche Repository-Struktur (für GitHub funktioniert das besser)
private List<String> searchRepositoryStructure(String repoUrl, String searchTerm) throws Exception {
	List<String> results = new ArrayList<>();
	if (!repoUrl.endsWith("/")) repoUrl += "/";
	
	// Versuche maven-metadata.xml zu finden (listet alle Versionen)
	try {
		String metadataUrl = repoUrl + "maven-metadata.xml";
		String metadata = fetchUrl(metadataUrl);
		
		// Suche nach dem Suchbegriff im Metadata
		if (metadata.toLowerCase().contains(searchTerm.toLowerCase())) {
			// Extrahiere GroupId, ArtifactId aus der Struktur
			// Das ist ein einfacher Workaround
			Pattern p = Pattern.compile("<artifactId>([^<]*" + Pattern.quote(searchTerm) + "[^<]*)</artifactId>");
			Matcher m = p.matcher(metadata);
			while (m.find()) {
				results.add("?:" + m.group(1) + ":?"); // Placeholder
			}
		}
	} catch (Exception ignored) {}
	
	return results;
}

// [NEW] Dialog für manuelle Dependency-Eingabe
private void addDependencyDialog() {
	JPanel p = new JPanel(new GridLayout(4, 2, 8, 8));
	p.setBorder(new EmptyBorder(10, 10, 10, 10));
	
	JTextField groupId = new JTextField("", 35);
	JTextField artifactId = new JTextField("", 35);
	JTextField version = new JTextField("", 35);
	JLabel infoLabel = new JLabel("<html><small>Beispiel: terminalfx 1.3.0 (Java 25) oder 1.0.8 (Java 8)</small></html>");
	
	p.add(new JLabel("GroupId:"));       p.add(groupId);
	p.add(new JLabel("ArtifactId:"));    p.add(artifactId);
	p.add(new JLabel("Version:"));       p.add(version);
	p.add(infoLabel);                    p.add(new JLabel(""));
	
	int r = JOptionPane.showConfirmDialog(frame, p, 
		"Abhängigkeit manuell hinzufügen", 
		JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
	
	if (r == JOptionPane.OK_OPTION) {
		String g = groupId.getText().trim();
		String a = artifactId.getText().trim();
		String v = version.getText().trim();
		
		if (g.isEmpty() || a.isEmpty() || v.isEmpty()) {
			log("[FEHLER] Alle Felder müssen ausgefüllt sein.\n", Color.RED);
			return;
		}
		
		log("[INFO] Starte Download: " + a + ":" + v + "\n", Color.CYAN);
		new Thread(() -> downloadAll(g, a, v), "manual-dep-download").start();
	}
}

	// [NEW] Suche in Custom Repository mit Debug-Output
	private String searchCustomRepository(String repoUrl, String query) throws Exception {
		if (!repoUrl.endsWith("/")) repoUrl += "/";

		// Versuche verschiedene Maven-API-Endpoints
		String[] endpoints = {
			repoUrl + ".well-known/maven-repository/search?q=",
			repoUrl + "search/solrsearch/select?q="
		};

		String searchUrl = endpoints[0] + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
		log("[DEBUG] Suche in Custom Repo: " + searchUrl + "\n", new Color(150, 150, 150));

		try {
			return fetchUrl(searchUrl);
		} catch (Exception e) {
			log("[DEBUG] Repository-Suche fehlgeschlagen: " + e.getMessage() + "\n", new Color(150, 150, 150));
			return null;
		}
	}

	// [NEW] Intelligente Repository-URL-Behandlung
	private boolean downloadFileFromRepository(String repoUrl, String g, String a, String v, String ext, String target) {
		if (!repoUrl.endsWith("/")) repoUrl += "/";

		// GitHub Raw-Content automatisch erkennen und korrigieren
		if (repoUrl.contains("github.com") && repoUrl.contains("/raw/")) {
			// URL ist bereits korrekt formatiert für Maven-Struktur
			// z.B. https://github.com/javaterminal/terminalfx/raw/master/releases/
			// Die Rest wird nach Standard Maven-Struktur angehängt
		}

		String path = g.replace(".", "/") + "/" + a + "/" + v + "/" + a + "-" + v + ext;
		String fullUrl = repoUrl + path;

		log("[DEBUG] Maven-Download-URL: " + fullUrl + "\n", new Color(150, 150, 150));

		try {
			HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
			conn.setRequestProperty("User-Agent", "TBuild");
			conn.setInstanceFollowRedirects(true); // Folge Redirects
			conn.setConnectTimeout(15000);
			conn.setReadTimeout(30000);

			int code = conn.getResponseCode();
			log("[DEBUG] HTTP " + code + " für " + fullUrl + "\n",
				code == 200 ? new Color(100, 150, 255) : Color.ORANGE);

			if (code == 404) {
				log("[WARN] Nicht im Custom Repository gefunden: " + a + "-" + v + ext + "\n", Color.ORANGE);
				return false;
			}
			if (code != 200) {
				log("[WARN] HTTP " + code + " beim Zugriff auf " + repoUrl + "\n", Color.ORANGE);
				return false;
			}

			log("[↓] Lade aus Custom Repo: " + new File(target).getName() + "\n", new Color(100, 150, 255));

			Path tmp = Paths.get(target + ".tmp");
			try (InputStream in = conn.getInputStream()) {
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			}
			Files.move(tmp, Paths.get(target), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

			log("[✓] Erfolgreich heruntergeladen: " + new File(target).getName() + "\n", new Color(80, 200, 120));
			return true;

		} catch (Exception e) {
			try {
				Files.deleteIfExists(Paths.get(target + ".tmp"));
			} catch (IOException ignored) {}
			log("[FEHLER] " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n", Color.ORANGE);
			return false;
		}
	}

	// [NEW] Dialog für manuelle Dependency-Eingabe
	private void manualDownloadDialog() {
		JPanel p = new JPanel(new GridLayout(4, 2, 8, 8));
		p.setBorder(new EmptyBorder(10, 10, 10, 10));

		JTextField groupId = new JTextField("", 30);
		JTextField artifactId = new JTextField("", 30);
		JTextField version = new JTextField("", 30);

		p.add(new JLabel("GroupId:"));       p.add(groupId);
		p.add(new JLabel("ArtifactId:"));    p.add(artifactId);
		p.add(new JLabel("Version:"));       p.add(version);
		p.add(new JLabel(""));               p.add(new JLabel(""));

		int r = JOptionPane.showConfirmDialog(frame, p,
			"Manuelle Abhängigkeit hinzufügen",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

		if (r == JOptionPane.OK_OPTION) {
			new Thread(() -> {
					downloadAll(groupId.getText().trim(),
						artifactId.getText().trim(),
						version.getText().trim());
				}, "manual-dep-download").start();
		}
	}


	private void showVersionDialog() {
		String sel = resultList.getSelectedValue();
		if (sel == null || sel.contains(" ")) return;
		new Thread(() -> {
				try {
					String[] parts = sel.split(":");
					if (parts.length < 2) return;
					String url = "https://search.maven.org/solrsearch/select?q=g:%22" + parts[0] + "%22+AND+a:%22" + parts[1] + "%22&rows=40&core=gav&wt=json";
					String json = fetchUrl(url);
					List<String> versions = new ArrayList<>();
					Matcher m = Pattern.compile("\"v\":\"([^\"]+)\"").matcher(json);
					while (m.find()) versions.add(m.group(1));
					if (versions.isEmpty()) { log("[WARN] Keine Versionen für " + sel + "\n", Color.ORANGE); return; }
					SwingUtilities.invokeLater(() -> {
							String choice = (String) JOptionPane.showInputDialog(frame, "Version für " + parts[1] + ":",
								"Version wählen", JOptionPane.PLAIN_MESSAGE, null, versions.toArray(), versions.get(0));
							if (choice != null) downloadAll(parts[0], parts[1], choice);
						});
				} catch (Exception e) { log("[FEHLER] Versionen: " + e.getMessage() + "\n", Color.RED); }
			}, "version-fetch").start();
	}

	private String fetchUrl(String urlStr) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
		conn.setRequestProperty("User-Agent", "TBuild/2.0");
		conn.setConnectTimeout(8000);
		conn.setReadTimeout(8000);
		int code = conn.getResponseCode();
		if (code != 200) throw new IOException("HTTP " + code);
		try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line; while ((line = r.readLine()) != null) sb.append(line);
			return sb.toString();
		}
	}

	// ═══════════════════════════════════════════════════════════════──[...]
	//  DEPENDENCY DOWNLOAD mit CUSTOM REPOSITORIES
	// ═══════════════════════════════════════════════════════════════──[...]

	private void downloadAll(String g, String a, String v) {
		if (isCliMode) downloadAllBlocking(g, a, v);
		else new Thread(() -> downloadAllBlocking(g, a, v), "dep-download").start();
	}

	private void downloadAllBlocking(String g, String a, String v) {
		try {
			pool = Executors.newFixedThreadPool(6, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
			downloaded.clear(); pomDownloadStarted.clear(); pomCache.clear(); activeDownloads.set(0);
			log("[INFO] Auflösung starten: " + a + ":" + v + "\n", Color.CYAN);
			resolve(g, a, v);
			long deadline = System.currentTimeMillis() + 300_000;
			while (activeDownloads.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(200);
			pool.shutdown();
			if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow();
			log("[ERFOLG] Alle Abhängigkeiten geladen.\n", Color.GREEN);
		} catch (Exception e) {
			log("[FEHLER] Download: " + e.getMessage() + "\n", Color.RED);
			if (pool != null && !pool.isShutdown()) pool.shutdownNow();
		}
	}

	private void resolve(String g, String a, String v) {
		if (g == null || a == null || v == null || v.startsWith("${")) return;
			String key = g + ":" + a + ":" + v;
			if (!downloaded.add(key)) return;
			activeDownloads.incrementAndGet();
			pool.submit(() -> {
					try {
						if (downloadPom(g, a, v)) {
							PomData data = parsePom(g, a, v);
							if (data != null) {
								if (!"pom".equalsIgnoreCase(data.packaging)) downloadJar(g, a, v);
								for (Dependency dep : data.dependencies) {
									if ("test".equals(dep.scope) || "provided".equals(dep.scope) || "system".equals(dep.scope) || dep.optional) continue;
									resolve(dep.groupId, dep.artifactId, dep.version);
								}
							}
						}
					} catch (Exception e) {
						log("[WARN] " + key + ": " + e.getMessage() + "\n", Color.ORANGE);
					} finally { activeDownloads.decrementAndGet(); }
				});
		}

		private PomData parsePom(String g, String a, String v) throws Exception {
			String cacheKey = g + ":" + a + ":" + v;
			if (pomCache.containsKey(cacheKey)) return pomCache.get(cacheKey);
			File pomFile = new File("libs/" + a + "-" + v + ".pom");
			if (!pomFile.exists()) return null;
			Document doc;
			try { doc = DBF.newDocumentBuilder().parse(pomFile); }
			catch (Exception e) { log("[WARN] POM nicht parsbar: " + pomFile.getName() + "\n", Color.ORANGE); return null; }
			Element root = doc.getDocumentElement();
			PomData data = new PomData();
			String pomG = direct(root, "groupId");
			String pomV = direct(root, "version");
			data.packaging = firstOrDefault(direct(root, "packaging"), "jar");
			Element parentEl = child(root, "parent");
			if (parentEl != null) {
				String pg = tag(parentEl, "groupId"), pa = tag(parentEl, "artifactId"), pv = tag(parentEl, "version");
				if (pg != null && pa != null && pv != null) {
					downloadPom(pg, pa, pv);
					PomData pd = parsePom(pg, pa, pv);
					if (pd != null) { data.properties.putAll(pd.properties); data.managedVersions.putAll(pd.managedVersions); }
				}
				if (pomG == null && pg != null) data.properties.put("project.groupId", pg);
				if (pomV == null && pv != null) data.properties.put("project.version", pv);
			}
			data.properties.putIfAbsent("project.groupId",    pomG != null ? pomG : g);
			data.properties.putIfAbsent("project.version",    pomV != null ? pomV : v);
			data.properties.put("project.artifactId", a);
			data.properties.putIfAbsent("revision", v);
			Element propsEl = child(root, "properties");
			if (propsEl != null) {
				NodeList kids = propsEl.getChildNodes();
				for (int i = 0; i < kids.getLength(); i++) {
					Node n = kids.item(i);
					if (n.getNodeType() == Node.ELEMENT_NODE) data.properties.put(n.getNodeName(), n.getTextContent().trim());
				}
			}
			Element dmEl = child(root, "dependencyManagement");
			if (dmEl != null) {
				Element depsEl = child(dmEl, "dependencies");
				if (depsEl != null) parseDepsManagement(depsEl, data);
			}
			Element depsEl = child(root, "dependencies");
			if (depsEl != null) parseDeps(depsEl, data);
			pomCache.put(cacheKey, data);
			return data;
		}

		private void parseDepsManagement(Element el, PomData data) throws Exception {
			NodeList nl = el.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node n = nl.item(i);
				if (n.getNodeType() != Node.ELEMENT_NODE || !"dependency".equals(n.getNodeName())) continue;
				Element e = (Element) n;
				String dg = rp(tag(e, "groupId"), data.properties);
				String da = rp(tag(e, "artifactId"), data.properties);
				String dv = rp(tag(e, "version"), data.properties);
				String ds = rp(tag(e, "scope"), data.properties);
				String dt = rp(tag(e, "type"), data.properties);
				if ("import".equals(ds) && "pom".equalsIgnoreCase(dt) && valid(dg, da, dv)) {
					downloadPom(dg, da, dv);
					PomData bom = parsePom(dg, da, dv);
					if (bom != null) bom.managedVersions.forEach(data.managedVersions::putIfAbsent);
				} else if (valid(dg, da, dv)) {
					data.managedVersions.put(dg + ":" + da, dv);
				}
			}
		}

		private void parseDeps(Element el, PomData data) {
			NodeList nl = el.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node n = nl.item(i);
				if (n.getNodeType() != Node.ELEMENT_NODE || !"dependency".equals(n.getNodeName())) continue;
				Element e = (Element) n;
				Dependency dep = new Dependency();
				dep.groupId    = rp(tag(e, "groupId"),    data.properties);
				dep.artifactId = rp(tag(e, "artifactId"), data.properties);
				dep.version    = rp(tag(e, "version"),    data.properties);
				dep.scope      = rp(tag(e, "scope"),      data.properties);
				dep.optional   = "true".equalsIgnoreCase(rp(tag(e, "optional"), data.properties));
				if ((dep.version == null || dep.version.startsWith("${")) && dep.groupId != null && dep.artifactId != null) {
						String managed = data.managedVersions.get(dep.groupId + ":" + dep.artifactId);
						if (managed != null && !managed.startsWith("${")) dep.version = managed;
						}
						if (dep.version != null && dep.version.startsWith("${")) dep.version = rp(dep.version, data.properties);
							if (valid(dep.groupId, dep.artifactId, dep.version)) data.dependencies.add(dep);
							else if (dep.groupId != null && dep.artifactId != null)
							log("[WARN] Version nicht auflösbar: " + dep.groupId + ":" + dep.artifactId + "\n", Color.ORANGE);
						}
					}

					private boolean downloadPom(String g, String a, String v) {
						if (!valid(g, a, v)) return false;
						new File("libs").mkdirs();
						File dest = new File("libs/" + a + "-" + v + ".pom");
						if (dest.exists() && dest.length() > 0) return true;
						String key = g + ":" + a + ":" + v + ":pom";
						if (!pomDownloadStarted.add(key)) {
							for (int i = 0; i < 40; i++) { if (dest.exists() && dest.length() > 0) return true; try { Thread.sleep(250); } catch (InterruptedException ignored) {} }
							return dest.exists() && dest.length() > 0;
						}

						// [NEW] Erst in Custom Repositories versuchen, dann Maven Central
						for (String repoUrl : customRepositories) {
							if (downloadFileFromRepository(repoUrl, g, a, v, ".pom", dest.getPath())) return true;
						}
						return downloadFile("https://repo1.maven.org/maven2/" + g.replace(".", "/") + "/" + a + "/" + v + "/" + a + "-" + v + ".pom", dest.getPath());
					}

					private boolean downloadJar(String g, String a, String v) {
						if (!valid(g, a, v)) return false;
						new File("libs").mkdirs();
						File dest = new File("libs/" + a + "-" + v + ".jar");
						if (dest.exists() && dest.length() > 0) return true;

						// [NEW] Erst in Custom Repositories versuchen, dann Maven Central
						for (String repoUrl : customRepositories) {
							if (downloadFileFromRepository(repoUrl, g, a, v, ".jar", dest.getPath())) return true;
						}
						return downloadFile("https://repo1.maven.org/maven2/" + g.replace(".", "/") + "/" + a + "/" + v + "/" + a + "-" + v + ".jar", dest.getPath());
					}

					

					private boolean downloadFile(String urlStr, String target) {
						try {
							HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
							conn.setRequestProperty("User-Agent", "TBuild");
							conn.setConnectTimeout(10000);
							conn.setReadTimeout(30000);
							int code = conn.getResponseCode();
							if (code == 404) return false;
							if (code != 200) { log("[WARN] HTTP " + code + " – " + new File(target).getName() + "\n", Color.ORANGE); return false; }
							log("[↓] " + new File(target).getName() + "\n", Color.GRAY);
							Path tmp = Paths.get(target + ".tmp");
							try (InputStream in = conn.getInputStream()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
							Files.move(tmp, Paths.get(target), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
							log("[URL] " + urlStr + "\n", Color.GRAY);
							return true;
						} catch (Exception e) {
							try { Files.deleteIfExists(Paths.get(target + ".tmp")); } catch (IOException ignored) {}
							return false;
						}
					}

					// ══════════════════════════════════════════════════════════════[...]
					//  CONFIG (T.xml) mit CUSTOM REPOSITORIES
					// ══════════════════════════════════════════════════════════════[...]

					private String getMainClass()    { return readXml("mainClass", "Main"); }
					private String getAppName()      { String n = readXml("appName", ""); return n.isEmpty() ? deriveAppName() : n; }
					private String getVersion()      { return readXml("version", "1.0.0"); }
					private String getUpgradeUuid()  { return readXml("winUpgradeUuid", DEFAULT_UUID); }
					private String getCustomRunParams()     { return readXml("customRunParams", ""); }
					private String getCustomJPackageParams(){ return readXml("customJPackageParams", ""); }
					private String getCustomJPackageArgs()  { return readXml("customJPackageArgs", ""); }

					private String deriveAppName() { String mc = getMainClass(); int d = mc.lastIndexOf('.'); return d >= 0 ? mc.substring(d + 1) : mc; }

					private String readXml(String tag, String fallback) {
						try {
							File f = new File("T.xml");
							if (!f.exists()) return fallback;
							Document doc = DBF.newDocumentBuilder().parse(f);
							NodeList nl = doc.getElementsByTagName(tag);
							if (nl.getLength() > 0) { String v = nl.item(0).getTextContent().trim(); if (!v.isEmpty()) return v; }
						} catch (Exception ignored) {}
						return fallback;
					}

					// [NEW] Custom Repositories laden
					private void loadCustomRepositories() {
						customRepositories.clear();
						try {
							File f = new File("repositories.txt");
							if (f.exists()) {
								for (String line : Files.readAllLines(f.toPath())) {
									line = line.trim();
									if (!line.isEmpty() && !line.startsWith("#")) {
										customRepositories.add(line);
									}
								}
							}
						} catch (IOException ignored) {}
					}

					private void saveConfig(String mc, String appName, String version) {
						saveFullConfig(mc, appName, version, getUpgradeUuid(), getCustomRunParams(), getCustomJPackageParams(), getCustomJPackageArgs());
					}

					private void saveFullConfig(String mc, String appName, String version, String uuid,
						String runParams, String jpackageParams, String jpackageArgs) {
						try (PrintWriter pw = new PrintWriter("T.xml", StandardCharsets.UTF_8)) {
							pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
							pw.println("<project>");
							pw.println("  <mainClass>"         + esc(mc)             + "</mainClass>");
							pw.println("  <appName>"            + esc(appName)        + "</appName>");
							pw.println("  <version>"            + esc(version)        + "</version>");
							pw.println("  <winUpgradeUuid>"     + esc(uuid)           + "</winUpgradeUuid>");
							pw.println("  <customRunParams>"    + esc(runParams)      + "</customRunParams>");
							pw.println("  <customJPackageParams>" + esc(jpackageParams) + "</customJPackageParams>");
							pw.println("  <customJPackageArgs>" + esc(jpackageArgs)   + "</customJPackageArgs>");
							pw.println("</project>");
						} catch (Exception e) { log("[FEHLER] T.xml speichern: " + e.getMessage() + "\n", Color.RED); }
					}

					private String esc(String s) {
						if (s == null) return "";
						return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
					}

					// ══════════════════════════════════════════════════════════════[...]
					//  CONFIG-DIALOGE mit REPOSITORIES
					// ══════════════════════════════════════════════════════════════[...]

					private void setMainDialog() {
						String v = (String) JOptionPane.showInputDialog(frame, "Main-Klasse (z.B. de.pkg.Main):", "Main-Klasse", JOptionPane.PLAIN_MESSAGE, null, null, getMainClass());
						if (v != null && !v.isBlank()) { saveConfig(v.trim(), getAppName(), getVersion()); log("[INFO] Main-Klasse: " + v.trim() + "\n", Color.LIGHT_GRAY); }
					}

					private void setVersionDialog() {
						String v = (String) JOptionPane.showInputDialog(frame, "Version (z.B. 1.2.0):", "Version", JOptionPane.PLAIN_MESSAGE, null, null, getVersion());
						if (v != null && !v.isBlank()) { saveConfig(getMainClass(), getAppName(), v.trim()); log("[INFO] Version: " + v.trim() + "\n", Color.LIGHT_GRAY); }
					}

					private void setNameDialog() {
						String v = (String) JOptionPane.showInputDialog(frame, "App-Name:", "Name", JOptionPane.PLAIN_MESSAGE, null, null, getAppName());
						if (v != null && !v.isBlank()) { saveConfig(getMainClass(), v.trim(), getVersion()); log("[INFO] Name: " + v.trim() + "\n", Color.LIGHT_GRAY); }
					}

					private void uuidDialog() {
						JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
						JTextField field = new JTextField(getUpgradeUuid(), 40);
						p.add(new JLabel("Windows MSI Upgrade-UUID:"));
						p.add(field);
						JButton gen = new JButton("🎲 Neue UUID generieren");
						gen.addActionListener(e -> field.setText(java.util.UUID.randomUUID().toString()));
						p.add(gen);
						int r = JOptionPane.showConfirmDialog(frame, p, "UUID festlegen", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
						if (r == JOptionPane.OK_OPTION && !field.getText().isBlank()) {
							saveFullConfig(getMainClass(), getAppName(), getVersion(), field.getText().trim(),
								getCustomRunParams(), getCustomJPackageParams(), getCustomJPackageArgs());
							log("[INFO] UUID: " + field.getText().trim() + "\n", Color.LIGHT_GRAY);
						}
					}

					private void customParamsDialog() {
						JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
						JTextField runField    = new JTextField(getCustomRunParams(), 50);
						JTextField jpField     = new JTextField(getCustomJPackageParams(), 50);
						JTextField jpArgsField = new JTextField(getCustomJPackageArgs(), 50);
						p.add(new JLabel("Custom JVM-Argumente beim Starten (z.B. -Xmx512m -Dfoo=bar):"));
						p.add(runField);
						p.add(new JLabel("Custom --java-options für jpackage:"));
						p.add(jpField);
						p.add(new JLabel("Custom jpackage-Argumente (z.B. --win-dir-chooser --resource-dir img):"));
						p.add(jpArgsField);
						int r = JOptionPane.showConfirmDialog(frame, p, "Custom Parameter", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
						if (r == JOptionPane.OK_OPTION) {
							saveFullConfig(getMainClass(), getAppName(), getVersion(), getUpgradeUuid(),
								runField.getText().trim(), jpField.getText().trim(), jpArgsField.getText().trim());
							log("[INFO] Custom-Parameter gespeichert.\n", Color.LIGHT_GRAY);
						}
					}

					// [NEW] Repositories-Dialog
					private void repositoriesDialog() {
						JPanel p = new JPanel(new BorderLayout(5, 5));
						JTextArea textArea = new JTextArea(8, 40);
						textArea.setText(String.join("\n", customRepositories));
						textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
						JScrollPane scroll = new JScrollPane(textArea);

						JPanel info = new JPanel(new BorderLayout());
						info.add(new JLabel("<html>Gib Maven-kompatible Repository-URLs ein (eine pro Zeile):<br>" +
								"z.B. https://repo.example.com/maven2/<br>" +
								"Zeilen mit # werden ignoriert.</html>"), BorderLayout.CENTER);

						p.add(info, BorderLayout.NORTH);
						p.add(scroll, BorderLayout.CENTER);

						int r = JOptionPane.showConfirmDialog(frame, p, "Custom Repositories", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
						if (r == JOptionPane.OK_OPTION) {
							List<String> repos = new ArrayList<>();
							for (String line : textArea.getText().split("\n")) {
								line = line.trim();
								if (!line.isEmpty() && !line.startsWith("#")) {
									repos.add(line);
								}
							}
							try {
								Files.write(Paths.get("repositories.txt"), String.join("\n", repos).getBytes(StandardCharsets.UTF_8));
								customRepositories = repos;
								log("[INFO] " + repos.size() + " Custom Repository/Repositories gespeichert.\n", new Color(100, 150, 255));
							} catch (IOException e) {
								log("[FEHLER] Repositories speichern: " + e.getMessage() + "\n", Color.RED);
							}
						}
					}

					// ══════════════════════════════════════════════════════════════[...]
					//  HELPER
					// ══════════════════════════════════════════════════════════════[...]

					private Thread drainAsync(InputStream is, JTextPane target) {
						Thread t = new Thread(() -> {
								try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
									String line;
									while ((line = r.readLine()) != null) {
										final String l = line;
										if (isCliMode) System.out.println(l);
										else SwingUtilities.invokeLater(() -> appendToPane(target, l + "\n", Color.LIGHT_GRAY));
									}
								} catch (IOException ignored) {}
							});
						t.setDaemon(true);
						t.start();
						return t;
					}

					private void stripModuleInfo(File dir) {
						try {
							Files.walk(dir.toPath()).filter(p -> {
									String path = p.toString().replace("\\", "/");
									String name = p.getFileName().toString().toUpperCase();
									if (name.equals("MODULE-INFO.CLASS")) return true;
									if (path.contains("META-INF/versions/")) return true;
									if (path.contains("META-INF/") && !p.toFile().isDirectory())
									return name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC");
									return false;
								}).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
							// Leere META-INF/versions aufräumen
							Files.walk(dir.toPath())
							.filter(p -> p.toString().replace("\\", "/").contains("META-INF/versions") && p.toFile().isDirectory())
							.sorted(Comparator.reverseOrder())
							.forEach(p -> { try { if (Objects.requireNonNull(p.toFile().list()).length == 0) Files.deleteIfExists(p); } catch (Exception ignored) {} });
						} catch (IOException e) { log("[WARN] Metadaten-Bereinigung: " + e.getMessage() + "\n", Color.ORANGE); }
					}

					private void deleteDirectory(File dir) throws IOException {
						if (dir.exists()) Files.walk(dir.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
					}

					private void copyDirectory(File src, File dst) throws IOException {
						Path s = src.toPath(), d = dst.toPath();
						Files.walk(s).forEach(n -> {
								try {
									Path t = d.resolve(s.relativize(n));
									if (Files.isDirectory(n)) Files.createDirectories(t);
									else Files.copy(n, t, StandardCopyOption.REPLACE_EXISTING);
								} catch (IOException e) { throw new RuntimeException(e); }
							});
					}

					private List<File> listFiles(File dir) {
						File[] arr = dir.listFiles();
						return arr != null ? Arrays.asList(arr) : Collections.emptyList();
					}

					// POM XML Helpers
					private Element child(Element p, String name) {
						if (p == null) return null;
						NodeList nl = p.getChildNodes();
						for (int i = 0; i < nl.getLength(); i++) { Node n = nl.item(i); if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) return (Element) n; }
						return null;
					}
					private String direct(Element p, String name) {
						NodeList nl = p.getChildNodes();
						for (int i = 0; i < nl.getLength(); i++) { Node n = nl.item(i); if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) return n.getTextContent().trim(); }
						return null;
					}
					private String tag(Element p, String name) { Element c = child(p, name); return c != null ? c.getTextContent().trim() : null; }
					private boolean valid(String g, String a, String v) { return g != null && a != null && v != null && !v.startsWith("${"); }
						private String firstOrDefault(String v, String def) { return (v != null && !v.isEmpty()) ? v : def; }

						private String rp(String val, Map<String, String> props) { return rp(val, props, 0); }
						private String rp(String val, Map<String, String> props, int depth) {
							if (val == null || depth > 10) return val;
							if (val.startsWith("${") && val.endsWith("}")) {
								String key = val.substring(2, val.length() - 1);
								String r = props.get(key);
								return (r != null && !r.equals(val)) ? rp(r, props, depth + 1) : val;
							}
							Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(val);
						if (!m.find()) return val;
						StringBuffer sb = new StringBuffer(); m.reset();
						while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group(0))));
						m.appendTail(sb);
						String res = sb.toString();
						return res.contains("${") && depth < 10 ? rp(res, props, depth + 1) : res;
						}

						// ══════════════════════════════════════════════════════════════[...]
						//  DATA CLASSES
						// ══════════════════════════════════════════════════════════════[...]

						private static class PomData {
							String packaging = "jar";
							final Map<String, String> properties      = new HashMap<>();
							final Map<String, String> managedVersions = new HashMap<>();
							final List<Dependency>    dependencies    = new ArrayList<>();
						}

						private static class Dependency {
							String groupId, artifactId, version, scope;
							boolean optional;
						}

						// ══════════════════════════════════════════════════════════════[...]
						//  GIT CREDENTIALS
						// ══════════════════════════════════════════════════════════════[...]

						private String[] loadGitCredentials() {
							if (GIT_CREDS.exists()) {
								try {
									for (String line : Files.readAllLines(GIT_CREDS.toPath())) {
										line = line.trim();
										if (line.contains("github.com") && line.startsWith("https://")) {
											String part = line.substring(8); int at = part.lastIndexOf('@');
											if (at > 0) { String up = part.substring(0, at); int col = up.indexOf(':');
												if (col > 0) return new String[]{up.substring(0, col), up.substring(col + 1)}; }
										}
									}
								} catch (IOException ignored) {}
							}
							try {
								Process p = new ProcessBuilder("git", "credential", "fill").redirectErrorStream(true).start();
								p.getOutputStream().write("protocol=https\nhost=github.com\n\n".getBytes());
								p.getOutputStream().flush(); p.getOutputStream().close();
								String out = new String(p.getInputStream().readAllBytes()).trim();
								p.waitFor(3, TimeUnit.SECONDS);
								String user = null, pass = null;
								for (String l : out.split("[\r\n]+")) {
									if (l.startsWith("username=")) user = l.substring(9).trim();
									if (l.startsWith("password=")) pass = l.substring(9).trim();
								}
								if (user != null && pass != null && !user.isEmpty() && !pass.isEmpty()) return new String[]{user, pass};
							} catch (Exception ignored) {}
							try { Process p = new ProcessBuilder("git", "config", "--global", "user.name").redirectErrorStream(true).start();
								String n = new String(p.getInputStream().readAllBytes()).trim(); p.waitFor(2, TimeUnit.SECONDS);
								if (!n.isEmpty()) return new String[]{n, null}; } catch (Exception ignored) {}
							return null;
						}

						private void saveGitCredentials(String u, String t) {
							try {
								List<String> lines = GIT_CREDS.exists() ? new ArrayList<>(Files.readAllLines(GIT_CREDS.toPath())) : new ArrayList<>();
								lines.removeIf(l -> l.contains("github.com"));
								lines.add("https://" + u + ":" + t + "@github.com");
								Files.write(GIT_CREDS.toPath(), lines);
								GIT_CREDS.setReadable(false, false); GIT_CREDS.setReadable(true, true);
								GIT_CREDS.setWritable(false, false); GIT_CREDS.setWritable(true, true);
							} catch (IOException e) { log("[GIT FEHLER] Credentials speichern: " + e.getMessage() + "\n", Color.RED); }
						}

						private UsernamePasswordCredentialsProvider getCredentialsProvider() {
							String[] c = loadGitCredentials();
							if (c == null || c[1] == null || c[1].isEmpty()) return null;
							return new UsernamePasswordCredentialsProvider(c[0], c[1]);
						}

						private Git openLocalRepo() throws IOException {
							return new Git(new FileRepositoryBuilder().findGitDir(new File(".")).readEnvironment().build());
						}

						public static String[] loadGitCredentialsStatic() {
							if (!GIT_CREDS.exists()) return null;
							try {
								for (String line : Files.readAllLines(GIT_CREDS.toPath())) {
									line = line.trim();
									if (line.contains("github.com") && line.startsWith("https://")) {
										String part = line.substring(8); int at = part.lastIndexOf('@');
										if (at > 0) { String up = part.substring(0, at); int col = up.indexOf(':');
											if (col > 0) return new String[]{up.substring(0, col), up.substring(col + 1)}; }
									}
								}
							} catch (IOException ignored) {}
							return null;
						}

						// ══════════════════════════════════════════════════════════════[...]
						//  GIT AKTIONEN
						// ══════════════════════════════════════════════════════════════[...]

						private void gitLogin() {
							String[] ex = loadGitCredentials();
							JPanel p = new JPanel(new GridLayout(3, 2, 8, 8));
							p.setBorder(new EmptyBorder(10, 10, 10, 10));
							JTextField user = new JTextField(ex != null ? ex[0] : "", 20);
							JPasswordField pass = new JPasswordField(20);
							p.add(new JLabel("GitHub-Benutzername:")); p.add(user);
							p.add(new JLabel("Personal Access Token:")); p.add(pass);
							p.add(new JLabel("<html><small>Token: github.com → Settings → Developer settings → PAT</small></html>"));
							p.add(ex != null ? new JLabel("<html><small style='color:green'>✓ Angemeldet als " + ex[0] + "</small></html>") : new JLabel(""));
							if (JOptionPane.showConfirmDialog(frame, p, "GitHub Anmeldung", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
							String u = user.getText().trim(), t = new String(pass.getPassword()).trim();
							if (u.isEmpty() || t.isEmpty()) { log("[GIT] Benutzername und Token dürfen nicht leer sein.\n", Color.RED); return; }
							new Thread(() -> {
									log("[GIT] Teste Verbindung...\n", Color.CYAN);
									try {
										HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/user").openConnection();
										c.setRequestProperty("Authorization", "token " + t); c.setRequestProperty("User-Agent", "TBuild");
										c.setConnectTimeout(8000); c.setReadTimeout(8000);
										if (c.getResponseCode() == 200) { saveGitCredentials(u, t); log("[GIT] ✓ Angemeldet als: " + u + "\n", new Color(80, 200, 120)); }
										else log("[GIT] Anmeldung fehlgeschlagen (HTTP " + c.getResponseCode() + ").\n", Color.RED);
									} catch (Exception e) { log("[GIT] Verbindungsfehler: " + e.getMessage() + "\n", Color.RED); }
								}, "git-login").start();
						}

						private void gitStatus() {
							new Thread(() -> {
									try (Git git = openLocalRepo()) {
										Status s = git.status().call();
										log("[GIT] Branch: " + git.getRepository().getBranch() + "\n", Color.CYAN);
										if (!s.getAdded().isEmpty())       log("[GIT] Neu (staged):  " + s.getAdded()       + "\n", new Color(80, 200, 120));
										if (!s.getModified().isEmpty())     log("[GIT] Geändert:      " + s.getModified()     + "\n", Color.ORANGE);
										if (!s.getUntracked().isEmpty())    log("[GIT] Untracked:     " + s.getUntracked()    + "\n", Color.LIGHT_GRAY);
										if (!s.getRemoved().isEmpty())      log("[GIT] Gelöscht:      " + s.getRemoved()      + "\n", Color.RED);
										if (!s.getConflicting().isEmpty())  log("[GIT] Konflikte:     " + s.getConflicting()  + "\n", Color.RED);
										if (s.isClean())                    log("[GIT] ✓ Alles sauber.\n", new Color(80, 200, 120));
									} catch (Exception e) { log("[GIT FEHLER] " + e.getMessage() + "\n", Color.RED); }
								}, "git-status").start();
						}

						private void gitInitLocal() {
							if (JOptionPane.showConfirmDialog(frame, "Lokales Git-Repo initialisieren?", "Init", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
							new Thread(() -> {
									try { Git.init().setDirectory(new File(".")).call().close(); log("[GIT] ✓ Repository initialisiert.\n", new Color(80, 200, 120)); }
									catch (GitAPIException e) { log("[GIT FEHLER] " + e.getMessage() + "\n", Color.RED); }
								}, "git-init").start();
						}

						private void gitClone() {
							JPanel p = new JPanel(new GridLayout(2, 2, 8, 8));
							p.setBorder(new EmptyBorder(10, 10, 10, 10));
							JTextField url = new JTextField("https://github.com/user/repo.git", 30);
							JTextField tgt = new JTextField(new File(".").getAbsolutePath(), 30);
							p.add(new JLabel("URL:")); p.add(url); p.add(new JLabel("Zielordner:")); p.add(tgt);
							if (JOptionPane.showConfirmDialog(frame, p, "Repo klonen", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
							new Thread(() -> {
									log("[GIT] Klone " + url.getText().trim() + "...\n", Color.CYAN);
									try {
										CloneCommand cmd = Git.cloneRepository().setURI(url.getText().trim()).setDirectory(new File(tgt.getText().trim()));
										UsernamePasswordCredentialsProvider cp = getCredentialsProvider();
										if (cp != null) cmd.setCredentialsProvider(cp);
										cmd.call().close();
										log("[GIT] ✓ Geklont nach: " + tgt.getText().trim() + "\n", new Color(80, 200, 120));
									} catch (GitAPIException e) { log("[GIT FEHLER] " + e.getMessage() + "\n", Color.RED); }
								}, "git-clone").start();
						}

						private void gitCreateGitHub() {
							String[] creds = loadGitCredentials();
							if (creds == null || creds[1] == null) { log("[GIT] Bitte erst anmelden.\n", Color.ORANGE); return; }
							JPanel p = new JPanel(new GridLayout(4, 2, 8, 8));
							p.setBorder(new EmptyBorder(10, 10, 10, 10));
							JTextField nameF = new JTextField("", 20), descF = new JTextField("", 20);
							try { nameF.setText(new File(".").getCanonicalFile().getName()); } catch (Exception ignored) {}
							JCheckBox privBox = new JCheckBox("Privates Repo"), initBox = new JCheckBox("Lokal init + Remote setzen", true);
							p.add(new JLabel("Repo-Name:")); p.add(nameF); p.add(new JLabel("Beschreibung:")); p.add(descF);
							p.add(privBox); p.add(initBox); p.add(new JLabel("")); p.add(new JLabel(""));
							if (JOptionPane.showConfirmDialog(frame, p, "Neues GitHub-Repo", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
							String repoName = nameF.getText().trim(), desc = descF.getText().trim();
							boolean priv = privBox.isSelected(), init = initBox.isSelected();
							new Thread(() -> {
									try {
										String body = "{\"name\":\"" + repoName + "\",\"description\":\"" + desc + "\",\"private\":" + priv + "}";
										HttpURLConnection conn = (HttpURLConnection) new URL("https://api.github.com/user/repos").openConnection();
										conn.setRequestMethod("POST"); conn.setDoOutput(true);
										conn.setRequestProperty("Authorization", "token " + creds[1]);
										conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("User-Agent", "TBuild");
										conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
										conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
										int code = conn.getResponseCode();
										if (code == 201) {
											String repoUrl = "https://github.com/" + creds[0] + "/" + repoName + ".git";
											log("[GIT] ✓ Repo erstellt: " + repoUrl + "\n", new Color(80, 200, 120));
											if (init) {
												Git git = new File(".git").exists() ? openLocalRepo() : Git.init().setDirectory(new File(".")).call();
												File gi = new File(".gitignore");
												if (!gi.exists()) Files.write(gi.toPath(), "out/\nbuild_temp/\ndist/\n*.class\n*.tmp\n".getBytes());
												git.add().addFilepattern(".").call();
												try { git.commit().setMessage("Initial commit").call(); } catch (Exception ignored) {}
												git.remoteAdd().setName("origin").setUri(new URIish(repoUrl)).call();
												git.push().setRemote("origin").setCredentialsProvider(new UsernamePasswordCredentialsProvider(creds[0], creds[1])).call();
												log("[GIT] ✓ Gepusht.\n", new Color(80, 200, 120));
												git.close();
											}
										} else { log("[GIT FEHLER] HTTP " + code + "\n", Color.RED); }
									} catch (Exception e) { log("[GIT FEHLER] " + e.getMessage() + "\n", Color.RED); }
								}, "git-create-gh").start();
						}

						private void gitAddRemote() {
							JPanel p = new JPanel(new GridLayout(2, 2, 8, 8));
							p.setBorder(new EmptyBorder(10, 10, 10, 10));
							JTextField name = new JTextField("origin", 15), url = new JTextField("https://github.com/user/repo.git", 30);
							p.add(new JLabel("Name:")); p.add(name); p.add(new JLabel("URL:")); p.add(url);
							if (JOptionPane.showConfirmDialog(frame, p, "Remote hinzufügen", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
							new Thread(() -> {
									try (Git git = openLocalRepo()) {
										git.remoteAdd().setName(name.getText().trim()).setUri(new URIish(url.getText().trim())).call();
										log("[GIT] ✓ Remote '" + name.getText().trim() + "' hinzugefügt.\n", new Color(80, 200, 120));
									} catch (Exception e) { log("[GIT FEHLER] " + e.getMessage() + "\n", Color.RED); }
								}, "git-add-remote").start();
						}

						private void gitShowBranches() {
							new Thread(() -> {
									try (Git git = openLocalRepo()) {
										List<Ref> branches = git.branchList().call();
										String current = git.getRepository().getBranch();
										log("[GIT] Branches:\n", new Color(255, 200, 80));
										List<String> names = new ArrayList<>();
										for (Ref r : branches) {
											String n = r.getName().replace("refs/heads/", "");
											names.add(n);
											log("[GIT]   " + n + (n.equals(current) ? " ← aktuell" : "") + "\n", n.equals(current) ? new Color(80, 200, 120) : Color.LIGHT_GRAY);
										}
										SwingUtilities.invokeLater(() -> {
												if (names.isEmpty()) return;
												String choice = (String) JOptionPane.showInputDialog(frame, "Branch wechseln:", "Branch", JOptionPane.PLAIN_MESSAGE, null, names.toArray(), current);
												if (choice != null && !choice.equals(current)) {
													new Thread(() -> {
															try (Git g = openLocalRepo()) { g.checkout().setName(choice).call(); log("[GIT] ✓ Wechsel zu: " + choice + "\n", new Color(80, 200, 120)); }
															catch (Exception ex) { log("[GIT FEHLER] " + ex.getMessage() + "\n", Color.RED); }
														}, "git-checkout").start();
												}
											});
									} catch (Exception e) { log("[GIT FEHLER] " + e.getMessage() + "\n", Color.RED); }
								}, "git-branches").start();
						}

						private void gitCreateBranch() {
							String name = (String) JOptionPane.showInputDialog(frame, "Name des neuen Branches:", "Neuer Branch", JOptionPane.PLAIN_MESSAGE, null, null, "feature/neu");
							if (name == null || name.isBlank()) return;
							new Thread(() -> {
									try (Git git = openLocalRepo()) { git.checkout().setCreateBranch(true).setName(name.trim()).call(); log("[GIT] ✓ Branch erstellt: " + name.trim() + "\n", new Color(80, 200, 120)); }
									catch (Exception e) { log("[GIT FEHLER] " + e.getMessage() + "\n", Color.RED); }
								}, "git-new-branch").start();
						}
					}