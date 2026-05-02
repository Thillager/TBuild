import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * T-build
 *
 * Bugfixes in dieser Version:
 *
 * [FIX-WIN-1] WINDOWS HANG in executeExport(): jar-Prozesse (xf, cfe) haben
 *   auf Windows volle stderr/stdout-Puffer (Manifest-Warnungen, Duplikat-
 *   Warnungen, Signaturwarnungen etc.). Der Puffer blockiert den Prozess bevor
 *   waitFor() zurueckkehrt. Fix: Output wird in einem dedizierten Daemon-Thread
 *   parallel konsumiert. drainAsync() startet diesen Thread und gibt ihn zurueck,
 *   sodass waitFor() nie blockiert.
 *
 * [FIX-WIN-2] Relativer Pfad "../Export-Fat.jar" in jar-cfe-Befehl funktioniert
 *   auf Windows nicht zuverlaessig wenn pb.directory() auf ein temp-Unterverzeichnis
 *   gesetzt ist. Fix: Absoluter Pfad des Ziels wird immer verwendet.
 *
 * [FIX-CLI-1] System.exit(0) am Ende von runCli() damit kein verbleibender
 *   nicht-Daemon-Thread den Prozess auf Windows haengen laesst.
 *
 * [FIX-CLI-2] downloadAllBlocking() wartet mit Timeout auf Pool-Shutdown.
 *   Bei Fehler wird shutdownNow() aufgerufen damit keine Threads haengen bleiben.
 */
public class TBuild {

    private JFrame frame;
    private JTextPane console;
    private JTextField searchField;
    private DefaultListModel<String> resultModel;
    private JList<String> resultList;
    private ExecutorService pool;
    private Set<String> downloaded = ConcurrentHashMap.newKeySet();
    private Set<String> pomDownloadStarted = ConcurrentHashMap.newKeySet();
    private Map<String, PomData> pomCache = new ConcurrentHashMap<>();
    private AtomicInteger activeDownloadTasks = new AtomicInteger(0);
    private boolean isCliMode = false;

    // ================== ENTRY POINT ==================

    public static void main(String[] args) {
        if (args.length > 0) {
            // CLI-Modus: AWT/Swing komplett deaktivieren BEVOR irgendeine AWT-Klasse
            // initialisiert wird. Auf headless Linux-Servern (kein X11/Display, wie
            // GitHub Actions) wuerde sonst die AWT-EventQueue starten und mit
            // HeadlessException abstuerzen, auch wenn --return-- den main-Thread stoppt.
            // Das Property muss als allererstes gesetzt werden, bevor der Classloader
            // AWT initialisiert.
            System.setProperty("java.awt.headless", "true");
            new TBuild().runCli(args);
            return;
        }
        try {
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf");
        } catch (Exception e) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }
        SwingUtilities.invokeLater(() -> new TBuild().createUI());
    }

    // ================== CLI MODE ==================

    private void runCli(String[] args) {
        this.isCliMode = true;
        String command = args[0].toLowerCase();

        switch (command) {
            case "init":
                initProject();
                break;

            case "build":
                executeBuild();
                break;

            case "export":
                ensureBuilt();
                executeExport(false);
                break;

            case "export-fat":
                ensureBuilt();
                executeExport(true);
                break;

            case "build-export-fat":
                executeBuild();
                executeExport(true);
                break;

            case "jpackage":
                ensureBuilt();
                executeExport(true);
                executeJPackage();
                break;

            case "set-main":
                if (args.length > 1) {
                    saveConfig(args[1], getAppName(), getVersion());
                    System.out.println("[INFO] Main-Klasse erfolgreich auf '" + args[1] + "' gesetzt.");
                } else {
                    System.out.println("[FEHLER] Bitte gib die Klasse an. Beispiel: set-main de.meinprojekt.Main");
                    System.exit(1);
                }
                break;

            case "set-version":
                if (args.length > 1) {
                    saveConfig(getMainClass(), getAppName(), args[1]);
                    System.out.println("[INFO] Version erfolgreich auf '" + args[1] + "' gesetzt.");
                } else {
                    System.out.println("[FEHLER] Bitte gib die Version an. Beispiel: set-version 1.0.0");
                    System.exit(1);
                }
                break;

            default:
                System.out.println("Unbekannter Befehl: " + command);
                System.out.println("Verfuegbare Befehle:");
                System.out.println("  init              - Initialisiert das Projekt");
                System.out.println("  build             - Kompiliert das Projekt");
                System.out.println("  export            - Erstellt Export.jar");
                System.out.println("  export-fat        - Erstellt Export-Fat.jar (inkl. Abhaengigkeiten)");
                System.out.println("  build-export-fat  - Build + Fat-Export in einem Schritt (fuer CI/CD)");
                System.out.println("  jpackage          - Erstellt nativen Installer via jpackage");
                System.out.println("  set-main <klasse> - Setzt die Main-Klasse (z.B. set-main de.pkg.Main)");
                System.exit(1);
        }

        // [FIX-CLI-1] Expliziter Exit damit keine verbleibenden Non-Daemon-Threads
        // den Prozess auf Windows haengen lassen.
        System.exit(0);
    }

    private void ensureBuilt() {
        File outDir = new File("out");
        boolean needsBuild = true;
        if (outDir.exists()) {
            try {
                needsBuild = !Files.walk(outDir.toPath())
                        .anyMatch(p -> p.toString().endsWith(".class"));
            } catch (IOException ignored) {
            }
        }
        if (needsBuild) {
            log("[INFO] out/ leer oder nicht vorhanden - starte Build...\n", Color.CYAN);
            executeBuild();
        }
    }

    // ================== GUI ==================

    private void createUI() {
        frame = new JFrame("T-build - Simple Java Build Tool");
        frame.setSize(1100, 650);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        frame.setContentPane(root);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton initBtn      = new JButton("Projekt initialisieren");
        JButton buildBtn     = new JButton("Bauen & Ausfuehren");
        JButton mainBtn      = new JButton("Main-Klasse setzen");
        JButton versionBtn   = new JButton("Version setzen");
        JButton clearBtn     = new JButton("Konsole leeren");
        JButton exportJarBtn = new JButton("Export JAR");
        JButton exportFatBtn = new JButton("Export Fat JAR");
        JButton jpackageBtn  = new JButton("jpackage Installer");

        initBtn.addActionListener(e -> initProject());
        buildBtn.addActionListener(e -> runBuild());
        mainBtn.addActionListener(e -> setMainDialog());
        versionBtn.addActionListener(e -> setVersionDialog());
        clearBtn.addActionListener(e -> console.setText(""));
        exportJarBtn.addActionListener(e -> exportToJar(false));
        exportFatBtn.addActionListener(e -> exportToJar(true));
        jpackageBtn.addActionListener(e -> new Thread(() -> {
            executeExport(true);
            executeJPackage();
        }).start());

        top.add(initBtn);
        top.add(buildBtn);
        top.add(exportJarBtn);
        top.add(exportFatBtn);
        top.add(jpackageBtn);
        top.add(mainBtn);
        top.add(versionBtn);
        top.add(clearBtn);
        root.add(top, BorderLayout.NORTH);

        console = new JTextPane();
        console.setBackground(new Color(30, 30, 30));
        console.setFont(new Font("Monospaced", Font.PLAIN, 13));
        console.setEditable(false);

        JPanel right = new JPanel(new BorderLayout(5, 5));
        right.setPreferredSize(new Dimension(350, 0));
        searchField = new JTextField();
        searchField.setToolTipText("Z.B. gson, flatlaf, rsyntaxtextarea...");
        JButton searchBtn  = new JButton("Suchen");
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchBtn,   BorderLayout.EAST);
        resultModel = new DefaultListModel<>();
        resultList  = new JList<>(resultModel);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        right.add(searchPanel,                BorderLayout.NORTH);
        right.add(new JScrollPane(resultList), BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(console), right);
        splitPane.setResizeWeight(0.8);
        root.add(splitPane, BorderLayout.CENTER);

        searchBtn.addActionListener(e -> searchLibraries());
        searchField.addActionListener(e -> searchLibraries());
        resultList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && resultList.getSelectedValue() != null) {
                showVersionDialog();
                resultList.clearSelection();
            }
        });

        log("[INFO] T-build gestartet. Bereit.\n", Color.LIGHT_GRAY);
        frame.setVisible(true);
    }

    // ================== LOGGING ==================

    private void log(String msg, Color color) {
        if (isCliMode) {
            System.out.print(msg);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = console.getStyledDocument();
            Style style = console.addStyle("style", null);
            StyleConstants.setForeground(style, color);
            try {
                doc.insertString(doc.getLength(), msg, style);
                console.setCaretPosition(doc.getLength());
            } catch (Exception ignored) {
            }
        });
    }

    // ================== PROJECT INIT ==================

    private void initProject() {
        try {
            File srcDir = new File("src/main/java");
            File libDir = new File("libs");
            srcDir.mkdirs();
            libDir.mkdirs();
            File mainFile = new File(srcDir, "Main.java");
            if (!mainFile.exists()) {
                try (PrintWriter pw = new PrintWriter(mainFile)) {
                    pw.println("public class Main {");
                    pw.println("    public static void main(String[] args) {");
                    pw.println("        System.out.println(\"Hallo Welt von T-build!\");");
                    pw.println("    }");
                    pw.println("}");
                } saveConfig("Main", "MeinProjekt", "1.0.0");
                log("[ERFOLG] Projektstruktur und Main.java erstellt.\n", Color.GREEN);
            } else {
                log("[INFO] Projektstruktur existiert bereits.\n", Color.LIGHT_GRAY);
            }
        } catch (Exception e) {
            log("[FEHLER] Konnte Projekt nicht initialisieren: " + e.getMessage() + "\n", Color.RED);
        }
    }

    // ================== BUILD & RUN ==================

    private void runBuild() {
        new Thread(this::executeBuild).start();
    }

    private void executeBuild() {
        try {
            File srcDir = new File("src/main/java");
            if (!srcDir.exists() || !srcDir.isDirectory()) {
                log("[FEHLER] Ordner 'src/main/java' fehlt.\n", Color.RED);
                if (isCliMode) System.exit(1);
                return;
            }
            File outDir = new File("out");
            outDir.mkdirs();
            String compileClasspath = buildClasspath();
            log("[INFO] Kompiliere Dateien mit Classpath: " + compileClasspath + "\n", Color.CYAN);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                log("[FEHLER] Kein Java Compiler gefunden. Laeuft das Programm mit einem JDK?\n", Color.RED);
                if (isCliMode) System.exit(1);
                return;
            }

            List<File> files = new ArrayList<>();
            Files.walk(srcDir.toPath())
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> files.add(p.toFile()));

            if (files.isEmpty()) {
                log("[FEHLER] Keine .java Dateien gefunden.\n", Color.RED);
                if (isCliMode) System.exit(1);
                return;
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
            boolean success = compiler.getTask(
                    null, fm, diagnostics,
                    Arrays.asList(
                            "-d",          outDir.getAbsolutePath(),
                            "-sourcepath", srcDir.getAbsolutePath(),
                            "-classpath",  compileClasspath),
                    null, fm.getJavaFileObjectsFromFiles(files)
            ).call();

            if (!success) {
                log("[FEHLER] Kompilierung fehlgeschlagen:\n", Color.RED);
                for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                    log(" - Zeile " + diagnostic.getLineNumber() + ": "
                            + diagnostic.getMessage(null) + "\n", Color.ORANGE);
                }
                if (isCliMode) System.exit(1);
                return;
            }

            log("[ERFOLG] Erfolgreich kompiliert.\n", Color.GREEN);

            if (!isCliMode) {
                log("Starte Programm...\n", Color.GREEN);
                log("--------------------------------------------------\n", Color.GRAY);
                String javaExe = getJavaExecutable();
                List<String> cmd = buildRunCommand(javaExe, compileClasspath);
                log("[INFO] Startbefehl: " + String.join(" ", cmd) + "\n", Color.CYAN);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    log(line + "\n", Color.WHITE);
                }
                int exitCode = p.waitFor();
                log("--------------------------------------------------\n", Color.GRAY);
                log("[INFO] Programm beendet mit Exit-Code: " + exitCode + "\n",
                        exitCode == 0 ? Color.LIGHT_GRAY : Color.ORANGE);
            }
        } catch (Exception e) {
            log("[FEHLER] Build-Prozess abgestuerzt: " + e.getMessage() + "\n", Color.RED);
            e.printStackTrace();
            if (isCliMode) System.exit(1);
        }
    }

    private List<String> buildRunCommand(String javaExe, String classpath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        List<String> javafxJars = new ArrayList<>();
        File lib = new File("libs");
        if (lib.exists() && lib.listFiles() != null) {
            for (File f : lib.listFiles()) {
                if (f.getName().endsWith(".jar") && isJavafxJar(f.getName())) {
                    javafxJars.add(f.getAbsolutePath());
                }
            }
        }
        if (!javafxJars.isEmpty()) {
            String modulePath = String.join(File.pathSeparator, javafxJars);
            cmd.add("--module-path");
            cmd.add(modulePath);
            cmd.add("--add-modules");
            cmd.add("ALL-MODULE-PATH");
            log("[INFO] JavaFX erkannt. Verwende --module-path.\n", Color.CYAN);
        }
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(getMainClass());
        return cmd;
    }

    private boolean isJavafxJar(String name) {
        return name.startsWith("javafx-") || name.startsWith("javafx.")
                || name.contains("javafx-base")     || name.contains("javafx-controls")
                || name.contains("javafx-fxml")      || name.contains("javafx-graphics")
                || name.contains("javafx-media")     || name.contains("javafx-swing")
                || name.contains("javafx-web");
    }

    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File exe = new File(javaHome, "bin" + File.separator + "java");
            if (exe.exists()) return exe.getAbsolutePath();
            File exeAlt = new File(javaHome + File.separator + ".."
                    + File.separator + "bin" + File.separator + "java");
            if (exeAlt.exists()) {
                try { return exeAlt.getCanonicalPath(); }
                catch (IOException e) { return exeAlt.getAbsolutePath(); }
            }
        }
        return "java";
    }

    private String buildClasspath() {
        StringBuilder cp = new StringBuilder(new File("out").getAbsolutePath());
        File lib = new File("libs");
        if (lib.exists() && lib.listFiles() != null) {
            for (File f : lib.listFiles()) {
                if (f.getName().endsWith(".jar")) {
                    cp.append(File.pathSeparator).append(f.getAbsolutePath());
                }
            }
        }
        return cp.toString();
    }

    // ================== MAIN CLASS MANAGEMENT ==================

    private void setMainDialog() {
        String current = getMainClass();
        String val = (String) JOptionPane.showInputDialog(
                frame, "Name der Main-Klasse (z.B. de.meinprojekt.Main):",
                "Main-Klasse festlegen", JOptionPane.PLAIN_MESSAGE, null, null, current);
        if (val != null && !val.trim().isEmpty()) {
            saveConfig(val.trim(), getAppName(), getVersion());
            log("[INFO] Main-Klasse auf '" + val.trim() + "' gesetzt.\n", Color.LIGHT_GRAY);
        }
    }

    private void setVersionDialog() {
        String current = getVersion();
        String val = (String) JOptionPane.showInputDialog(
                frame, "Version festlegen (z.B. 1.0.0):",
                "Version setzen", JOptionPane.PLAIN_MESSAGE, null, null, current);
        if (val != null && !val.trim().isEmpty()) {
            saveConfig(getMainClass(), getAppName(), val.trim());
            log("[INFO] Version auf '" + val.trim() + "' gesetzt.\n", Color.LIGHT_GRAY);
        }
    }

    private String getMainClass() {
        try {
            File f = new File("T.xml");
            if (!f.exists()) return "Main";
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
            return doc.getElementsByTagName("mainClass").item(0).getTextContent();
        } catch (Exception e) {
            return "Main";
        }
    }

    private String getVersion() {
        try {
            File f = new File("T.xml");
            if (!f.exists()) return "1.0.0";
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
            NodeList nl = doc.getElementsByTagName("version");
            if (nl.getLength() > 0) return nl.item(0).getTextContent().trim();
        } catch (Exception e) {}
        return "1.0.0";
    }

    private String getAppName() {
        try {
            File f = new File("T.xml");
            if (!f.exists()) return deriveAppName();
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
            NodeList nl = doc.getElementsByTagName("appName");
            if (nl.getLength() > 0 && !nl.item(0).getTextContent().trim().isEmpty()) {
                return nl.item(0).getTextContent().trim();
            }
        } catch (Exception ignored) {
        }
        return deriveAppName();
    }

    private String deriveAppName() {
        String mc = getMainClass();
        int dot = mc.lastIndexOf('.');
        return dot >= 0 ? mc.substring(dot + 1) : mc;
    }

    private void saveConfig(String mc, String appName, String version) {
        try (PrintWriter pw = new PrintWriter("T.xml")) {
            pw.println("<project>");
            pw.println("  <mainClass>" + mc + "</mainClass>");
            pw.println("  <appName>" + appName + "</appName>");
            pw.println("  <version>" + version + "</version>");
            pw.println("</project>");
        } catch (Exception ignored) {}
    }

    // ================== MAVEN SEARCH ==================

    private void searchLibraries() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) return;
        new Thread(() -> {
            try {
                SwingUtilities.invokeLater(() -> {
                    resultModel.clear();
                    resultModel.addElement("Suche laeuft...");
                });
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                String url = "https://search.maven.org/solrsearch/select?q=" + encodedQuery + "&rows=15&wt=json";
                String json = fetchUrl(url);
                SwingUtilities.invokeLater(() -> resultModel.clear());
                Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
                while (m.find()) {
                    String id = m.group(1);
                    SwingUtilities.invokeLater(() -> resultModel.addElement(id));
                }
                if (resultModel.isEmpty()) {
                    SwingUtilities.invokeLater(() -> resultModel.addElement("Keine Ergebnisse gefunden."));
                }
            } catch (Exception e) {
                log("[FEHLER] Suche fehlgeschlagen.\n", Color.RED);
            }
        }).start();
    }

    // ================== VERSION SELECTION ==================

    private void showVersionDialog() {
        String selected = resultList.getSelectedValue();
        if (selected == null || selected.contains(" ")) return;
        new Thread(() -> {
            try {
                String[] p = selected.split(":");
                if (p.length < 2) return;
                String url = "https://search.maven.org/solrsearch/select?q=g:%22"
                        + p[0] + "%22+AND+a:%22" + p[1] + "%22&rows=40&core=gav&wt=json";
                String json = fetchUrl(url);
                List<String> versions = new ArrayList<>();
                Matcher m = Pattern.compile("\"v\":\"([^\"]+)\"").matcher(json);
                while (m.find()) versions.add(m.group(1));
                if (versions.isEmpty()) {
                    log("[WARNUNG] Keine Versionen fuer " + p[1] + " gefunden.\n", Color.ORANGE);
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    String choice = (String) JOptionPane.showInputDialog(
                            frame, "Version waehlen fuer " + p[1] + ":",
                            "Versionsauswahl", JOptionPane.PLAIN_MESSAGE,
                            null, versions.toArray(), versions.get(0));
                    if (choice != null) {
                        downloadAll(p[0], p[1], choice);
                    }
                });
            } catch (Exception e) {
                log("[FEHLER] Konnte Versionen nicht laden: " + e.getMessage() + "\n", Color.RED);
            }
        }).start();
    }

    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (T-Build-Tool)");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("HTTP Error " + responseCode + " fuer URL: " + urlStr);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    // ================== DEPENDENCY DOWNLOAD ==================

    private void downloadAll(String groupId, String artifactId, String version) {
        if (isCliMode) {
            downloadAllBlocking(groupId, artifactId, version);
        } else {
            new Thread(() -> downloadAllBlocking(groupId, artifactId, version)).start();
        }
    }

    private void downloadAllBlocking(String groupId, String artifactId, String version) {
        try {
            pool = Executors.newFixedThreadPool(6);
            downloaded.clear();
            pomDownloadStarted.clear();
            pomCache.clear();
            activeDownloadTasks.set(0);
            log("[INFO] Starte Aufloesung von " + artifactId + ":" + version + "...\n", Color.CYAN);
            resolve(groupId, artifactId, version);
            while (activeDownloadTasks.get() > 0) {
                Thread.sleep(250);
            }
            pool.shutdown();
            // [FIX-CLI-2] Timeout + shutdownNow() falls ein Thread haengt
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                pool.shutdownNow();
            }
            log("[ERFOLG] Alle Dependencies wurden geladen.\n", Color.GREEN);
        } catch (Exception e) {
            log("[FEHLER] Kritischer Fehler beim Download-Prozess: " + e.getMessage() + "\n", Color.RED);
            if (pool != null && !pool.isShutdown()) pool.shutdownNow();
        }
    }

    private void resolve(String g, String a, String v) {
        if (g == null || a == null || v == null) return;
        if (v.startsWith("${")) {
            log("[WARNUNG] Konnte Version fuer " + g + ":" + a
                    + " nicht aufloesen (Property: " + v + "). Ueberspringe.\n", Color.ORANGE);
            return;
        }
        String key = g + ":" + a + ":" + v;
        if (!downloaded.add(key)) return;
        activeDownloadTasks.incrementAndGet();
        pool.submit(() -> {
            try {
                if (downloadPom(g, a, v)) {
                    PomData data = parsePom(g, a, v);
                    if (data != null) {
                        if (!"pom".equalsIgnoreCase(data.packaging)) {
                            downloadJar(g, a, v);
                        }
                        for (Dependency dep : data.dependencies) {
                            String scope = dep.scope;
                            if ("test".equals(scope) || "provided".equals(scope)
                                    || "system".equals(scope)) continue;
                            if (dep.optional) continue;
                            resolve(dep.groupId, dep.artifactId, dep.version);
                        }
                    }
                }
            } catch (Exception e) {
                log("[WARNUNG] Konnte " + key + " nicht vollstaendig laden: "
                        + e.getMessage() + "\n", Color.ORANGE);
            } finally {
                activeDownloadTasks.decrementAndGet();
            }
        });
    }

    private PomData parsePom(String g, String a, String v) throws Exception {
        String cacheKey = g + ":" + a + ":" + v;
        if (pomCache.containsKey(cacheKey)) return pomCache.get(cacheKey);
        File pomFile = new File("libs/" + a + "-" + v + ".pom");
        if (!pomFile.exists()) return null;
        org.w3c.dom.Document doc;
        try {
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile);
        } catch (Exception e) {
            log("[WARNUNG] POM-Datei konnte nicht geparst werden: " + pomFile.getName() + "\n", Color.ORANGE);
            return null;
        }
        org.w3c.dom.Element root = doc.getDocumentElement();
        PomData data = new PomData();
        String pomGroupId = getDirectTag(root, "groupId");
        String pomVersion = getDirectTag(root, "version");
        data.packaging = getDirectTag(root, "packaging");
        if (data.packaging == null) data.packaging = "jar";

        org.w3c.dom.Element parentEl = getChildElement(root, "parent");
        if (parentEl != null) {
            String pg = getTag(parentEl, "groupId");
            String pa = getTag(parentEl, "artifactId");
            String pv = getTag(parentEl, "version");
            if (pg != null && pa != null && pv != null) {
                downloadPom(pg, pa, pv);
                PomData parentData = parsePom(pg, pa, pv);
                if (parentData != null) {
                    data.properties.putAll(parentData.properties);
                    data.managedVersions.putAll(parentData.managedVersions);
                }
            }
            if (pomGroupId == null && pg != null) data.properties.put("project.groupId", pg);
            if (pomVersion == null && pv != null) data.properties.put("project.version", pv);
        }

        if (pomGroupId != null) data.properties.put("project.groupId", pomGroupId);
        else if (!data.properties.containsKey("project.groupId")) data.properties.put("project.groupId", g);
        if (pomVersion != null) data.properties.put("project.version", pomVersion);
        else if (!data.properties.containsKey("project.version")) data.properties.put("project.version", v);
        data.properties.put("project.artifactId", a);
        if (!data.properties.containsKey("revision")) data.properties.put("revision", v);

        org.w3c.dom.Element propsEl = getChildElement(root, "properties");
        if (propsEl != null) {
            NodeList props = propsEl.getChildNodes();
            for (int i = 0; i < props.getLength(); i++) {
                Node n = props.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    data.properties.put(n.getNodeName(), n.getTextContent().trim());
                }
            }
        }

        org.w3c.dom.Element depMgmtEl = getChildElement(root, "dependencyManagement");
        if (depMgmtEl != null) {
            org.w3c.dom.Element depsEl2 = getChildElement(depMgmtEl, "dependencies");
            if (depsEl2 != null) {
                NodeList deps = depsEl2.getChildNodes();
                for (int i = 0; i < deps.getLength(); i++) {
                    Node node = deps.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE
                            && node.getNodeName().equals("dependency")) {
                        org.w3c.dom.Element e = (org.w3c.dom.Element) node;
                        String dg = resolveProperty(getTag(e, "groupId"),    data.properties);
                        String da = resolveProperty(getTag(e, "artifactId"), data.properties);
                        String dv = resolveProperty(getTag(e, "version"),    data.properties);
                        String ds = resolveProperty(getTag(e, "scope"),      data.properties);
                        if ("import".equals(ds)
                                && "pom".equalsIgnoreCase(
                                resolveProperty(getTag(e, "type"), data.properties))) {
                            if (dg != null && da != null && dv != null && !dv.startsWith("${")) {
                                downloadPom(dg, da, dv);
                                PomData bomData = parsePom(dg, da, dv);
                                if (bomData != null) {
                                    for (Map.Entry<String, String> entry : bomData.managedVersions.entrySet()) {
                                        data.managedVersions.putIfAbsent(entry.getKey(), entry.getValue());
                                    }
                                }
                            }
                        } else if (dg != null && da != null && dv != null && !dv.startsWith("${")) {
                            data.managedVersions.put(dg + ":" + da, dv);
                        }
                    }
                }
            }
        }

        org.w3c.dom.Element depsEl = getChildElement(root, "dependencies");
        if (depsEl != null) {
            NodeList deps = depsEl.getChildNodes();
            for (int i = 0; i < deps.getLength(); i++) {
                Node node = deps.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE
                        && node.getNodeName().equals("dependency")) {
                    org.w3c.dom.Element e = (org.w3c.dom.Element) node;
                    Dependency dep = new Dependency();
                    dep.groupId    = resolveProperty(getTag(e, "groupId"),    data.properties);
                    dep.artifactId = resolveProperty(getTag(e, "artifactId"), data.properties);
                    dep.version    = resolveProperty(getTag(e, "version"),    data.properties);
                    dep.scope      = resolveProperty(getTag(e, "scope"),      data.properties);
                    dep.optional   = "true".equalsIgnoreCase(
                            resolveProperty(getTag(e, "optional"), data.properties));
                    if ((dep.version == null || dep.version.startsWith("${"))
                            && dep.groupId != null && dep.artifactId != null) {
                        String managed = data.managedVersions.get(dep.groupId + ":" + dep.artifactId);
                        if (managed != null && !managed.startsWith("${")) dep.version = managed;
                    }
                    if (dep.version != null && dep.version.startsWith("${")) {
                        dep.version = resolveProperty(dep.version, data.properties);
                    }
                    if (dep.groupId != null && dep.artifactId != null
                            && dep.version != null && !dep.version.startsWith("${")) {
                        data.dependencies.add(dep);
                    } else if (dep.groupId != null && dep.artifactId != null) {
                        log("[WARNUNG] Konnte Version fuer " + dep.groupId + ":"
                                + dep.artifactId + " nicht aufloesen. Ueberspringe.\n", Color.ORANGE);
                    }
                }
            }
        }
        pomCache.put(cacheKey, data);
        return data;
    }

    private boolean downloadPom(String g, String a, String v) {
        if (g == null || a == null || v == null || v.startsWith("${")) return false;
        new File("libs").mkdirs();
        String target = "libs/" + a + "-" + v + ".pom";
        File targetFile = new File(target);
        if (targetFile.exists() && targetFile.length() > 0) return true;
        String key = g + ":" + a + ":" + v + ":pom";
        if (!pomDownloadStarted.add(key)) {
            for (int i = 0; i < 40; i++) {
                if (targetFile.exists() && targetFile.length() > 0) return true;
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            }
            return targetFile.exists() && targetFile.length() > 0;
        }
        String url = "https://repo1.maven.org/maven2/"
                + g.replace(".", "/") + "/" + a + "/" + v + "/" + a + "-" + v + ".pom";
        return downloadFile(url, target);
    }

    private boolean downloadJar(String g, String a, String v) {
        if (g == null || a == null || v == null || v.startsWith("${")) return false;
        new File("libs").mkdirs();
        String url = "https://repo1.maven.org/maven2/"
                + g.replace(".", "/") + "/" + a + "/" + v + "/" + a + "-" + v + ".jar";
        String target = "libs/" + a + "-" + v + ".jar";
        File targetFile = new File(target);
        if (targetFile.exists() && targetFile.length() > 0) return true;
        return downloadFile(url, target);
    }

    private boolean downloadFile(String urlStr, String target) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (T-build/1.5)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            int responseCode = conn.getResponseCode();
            if (responseCode == 404) return false;
            if (responseCode != 200) {
                log("[WARNUNG] HTTP " + responseCode + " fuer: " + new File(target).getName() + "\n", Color.ORANGE);
                return false;
            }
            log("[INFO] Lade herunter: " + new File(target).getName() + "\n", Color.GRAY);
            Path tempPath = Paths.get(target + ".tmp");
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tempPath, Paths.get(target),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            try { Files.deleteIfExists(Paths.get(target + ".tmp")); } catch (IOException ignored) { }
            return false;
        }
    }

    // ================== EXPORT JAR ==================

    private void exportToJar(boolean fat) {
        new Thread(() -> executeExport(fat)).start();
    }

    private void executeExport(boolean fat) {
        try {
            String mainClass = getMainClass();
            String jarName   = fat ? "Export-Fat.jar" : "Export.jar";
            File tempDir = new File("build_temp");
            log("[INFO] Starte Export: " + jarName + "...\n", Color.CYAN);
            if (tempDir.exists()) deleteDirectory(tempDir);
            tempDir.mkdirs();

            File outDir = new File("out");
            if (!outDir.exists() || outDir.listFiles() == null || outDir.listFiles().length == 0) {
                log("[FEHLER] 'out' Ordner leer. Bitte erst bauen.\n", Color.RED);
                if (isCliMode) System.exit(1);
                return;
            }
            copyDirectory(outDir, tempDir);

            if (fat) {
                File libDir = new File("libs");
                if (libDir.exists() && libDir.listFiles() != null) {
                    for (File f : libDir.listFiles()) {
                        if (f.getName().endsWith(".jar")) {
                            log("   -> Integriere " + f.getName() + "...\n", Color.GRAY);
                            String jarTool = getJarToolExecutable();
                            ProcessBuilder pb = new ProcessBuilder(jarTool, "xf", f.getAbsolutePath());
                            pb.directory(tempDir);
                            pb.redirectErrorStream(true);
                            Process proc = pb.start();
                            // [FIX-WIN-1] Output parallel konsumieren damit der Prozess-Puffer
                            // nicht voll wird und den Prozess auf Windows nicht blockiert.
                            Thread drainThread = drainAsync(proc.getInputStream());
                            proc.waitFor();
                            drainThread.join();
                        }
                    }
                }
            }

            stripModuleInfo(tempDir);
            log("[INFO] Packe finale Datei...\n", Color.CYAN);

            String jarTool = getJarToolExecutable();
            // [FIX-WIN-2] Absoluter Pfad fuer das Ziel-JAR statt relativem "../jarName".
            // Relativer Pfad funktioniert auf Windows nicht wenn pb.directory() gesetzt ist.
            String jarTarget = new File(jarName).getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(jarTool, "cfe", jarTarget, mainClass, ".");
            pb.directory(tempDir);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // [FIX-WIN-1] Auch hier parallel drainieren
            Thread drainThread = drainAsync(proc.getInputStream());
            int exitCode = proc.waitFor();
            drainThread.join();

            if (exitCode != 0) {
                log("[FEHLER] jar-Tool beendete sich mit Exit-Code " + exitCode + ".\n", Color.RED);
                if (isCliMode) System.exit(1);
                return;
            }
            deleteDirectory(tempDir);
            log("[ERFOLG] " + jarName + " wurde im Projektverzeichnis erstellt!\n", Color.GREEN);
            log("[INFO] Startbar mit: java -jar " + jarName + "\n", Color.LIGHT_GRAY);
        } catch (Exception e) {
            log("[FEHLER] Export fehlgeschlagen: " + e.getMessage() + "\n", Color.RED);
            e.printStackTrace();
            if (isCliMode) System.exit(1);
        }
    }

    /**
     * [FIX-WIN-1] Startet einen Daemon-Thread der den InputStream des Prozesses
     * parallel liest und logged. Gibt den Thread zurueck damit der Aufrufer
     * join() aufrufen kann.
     *
     * Auf Windows fuellen jar und jpackage ihren stdout/stderr-Puffer mit
     * Warnungen (Manifest-Duplikate, Signaturdateien etc.). Wenn der Puffer
     * (~64KB) voll ist, blockiert der Prozess. waitFor() wartet aber darauf
     * dass der Prozess fertig ist -> Deadlock -> Hang. Fix: parallel lesen.
     */
    private Thread drainAsync(InputStream is) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log("   " + line + "\n", Color.GRAY);
                }
            } catch (IOException ignored) { }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ================== JPACKAGE ==================

    private void executeJPackage() {
        try {
            String jpackageTool = getJPackageExecutable();
            if (jpackageTool == null) {
                log("[FEHLER] jpackage nicht gefunden. Benoetigt JDK 14 oder neuer.\n", Color.RED);
                if (isCliMode) System.exit(1);
                return;
            }

            File fatJar = new File("Export-Fat.jar");
            if (!fatJar.exists()) {
                log("[FEHLER] Export-Fat.jar nicht gefunden.\n", Color.RED);
                if (isCliMode) System.exit(1);
                return;
            }

            String appName   = getAppName();
            String mainClass = getMainClass();
            File distDir = new File("dist");
            if (distDir.exists()) deleteDirectory(distDir);
            distDir.mkdirs();

            log("[INFO] Starte jpackage fuer App: " + appName + "\n", Color.CYAN);
            log("[INFO] Main-Klasse: " + mainClass + "\n", Color.CYAN);

            String os = System.getProperty("os.name", "").toLowerCase();
            String installerType;
            if (os.contains("win")) {
                installerType = "exe";
            } else if (os.contains("mac")) {
                installerType = "dmg";
            } else {
                installerType = "deb";
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(jpackageTool);
            cmd.add("--input");      cmd.add(".");
            cmd.add("--main-jar");   cmd.add("Export-Fat.jar");
            cmd.add("--main-class"); cmd.add(mainClass);
            cmd.add("--name");       cmd.add(appName);
            cmd.add("--dest");       cmd.add("dist");
            cmd.add("--type");       cmd.add(installerType);

// 1. Die Version aus der T.xml übergeben
            cmd.add("--app-version");
            cmd.add(getVersion());

// 2. Windows-spezifische Einstellungen für Suche und Desktop
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                cmd.add("--win-shortcut"); // Erstellt Icon auf dem Desktop
                cmd.add("--win-menu");     // Erstellt Eintrag im Startmenü (wichtig für die Windows-Suche)
            }

            log("[INFO] jpackage-Befehl: " + String.join(" ", cmd) + "\n", Color.CYAN);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // [FIX-WIN-1] jpackage hat ebenfalls viel Output auf Windows
            Thread drainThread = drainAsync(proc.getInputStream());
            int exitCode = proc.waitFor();
            drainThread.join();

            if (exitCode != 0) {
                log("[FEHLER] jpackage beendete sich mit Exit-Code " + exitCode + ".\n", Color.RED);
                log("[TIPP] Unter Windows: WiX Toolset installieren (https://wixtoolset.org)\n", Color.ORANGE);
                log("[TIPP] Unter Linux (deb): sudo apt install fakeroot\n", Color.ORANGE);
                log("[TIPP] Unter Linux (rpm): sudo dnf install rpm-build\n", Color.ORANGE);
                if (isCliMode) System.exit(1);
                return;
            }

            File[] distFiles = distDir.listFiles();
            if (distFiles != null) {
                for (File f : distFiles) {
                    log("[ERFOLG] Installer erstellt: dist/" + f.getName() + "\n", Color.GREEN);
                }
            }
            log("[INFO] Installer befindet sich im 'dist/' Ordner.\n", Color.LIGHT_GRAY);

        } catch (Exception e) {
            log("[FEHLER] jpackage fehlgeschlagen: " + e.getMessage() + "\n", Color.RED);
            e.printStackTrace();
            if (isCliMode) System.exit(1);
        }
    }

    private String getJPackageExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File exe = new File(javaHome, "bin" + File.separator + "jpackage");
            if (exe.exists()) return exe.getAbsolutePath();
            File exeWin = new File(javaHome, "bin" + File.separator + "jpackage.exe");
            if (exeWin.exists()) return exeWin.getAbsolutePath();
            File exeAlt = new File(javaHome + File.separator + ".."
                    + File.separator + "bin" + File.separator + "jpackage");
            if (exeAlt.exists()) {
                try { return exeAlt.getCanonicalPath(); }
                catch (IOException e) { return exeAlt.getAbsolutePath(); }
            }
            File exeAltWin = new File(javaHome + File.separator + ".."
                    + File.separator + "bin" + File.separator + "jpackage.exe");
            if (exeAltWin.exists()) {
                try { return exeAltWin.getCanonicalPath(); }
                catch (IOException e) { return exeAltWin.getAbsolutePath(); }
            }
        }
        try {
            Process p = new ProcessBuilder("jpackage", "--version").start();
            p.waitFor();
            if (p.exitValue() == 0) return "jpackage";
        } catch (Exception ignored) { }
        return null;
    }

    // ================== HELPER METHODS ==================

    private void stripModuleInfo(File dir) {
        log("[INFO] Entferne Modul-Metadaten (JPMS deaktivieren)...\n", Color.GRAY);
        try {
            Files.walk(dir.toPath())
                    .filter(p -> {
                        String path = p.toString().replace("\\", "/");
                        return p.getFileName().toString().equals("module-info.class")
                                || path.contains("META-INF/versions/");
                    })
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
            Files.walk(dir.toPath())
                    .filter(p -> p.toString().replace("\\", "/").contains("META-INF/versions"))
                    .filter(p -> p.toFile().isDirectory())
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            File f = p.toFile();
                            if (f.isDirectory() && f.list() != null && f.list().length == 0) {
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException ignored) { }
                    });
        } catch (IOException e) {
            log("[WARNUNG] Konnte Modul-Metadaten nicht vollstaendig entfernen.\n", Color.ORANGE);
        }
    }

    private org.w3c.dom.Element getChildElement(org.w3c.dom.Element parent, String name) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(name)) {
                return (org.w3c.dom.Element) n;
            }
        }
        return null;
    }

    private String getDirectTag(org.w3c.dom.Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(name)) {
                return n.getTextContent().trim();
            }
        }
        return null;
    }

    private String getTag(org.w3c.dom.Element parent, String name) {
        org.w3c.dom.Element child = getChildElement(parent, name);
        return child != null ? child.getTextContent().trim() : null;
    }

    private void deleteDirectory(File dir) throws IOException {
        if (dir.exists()) {
            Files.walk(dir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private void copyDirectory(File source, File target) throws IOException {
        Path sourcePath = source.toPath();
        Path targetPath = target.toPath();
        Files.walk(sourcePath).forEach(sourceNode -> {
            try {
                Path targetNode = targetPath.resolve(sourcePath.relativize(sourceNode));
                if (Files.isDirectory(sourceNode)) {
                    if (!Files.exists(targetNode)) Files.createDirectories(targetNode);
                } else {
                    Files.copy(sourceNode, targetNode, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private String getJarToolExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File jarExe = new File(javaHome, "bin" + File.separator + "jar");
            if (jarExe.exists()) return jarExe.getAbsolutePath();
            File jarExeAlt = new File(javaHome + File.separator + ".."
                    + File.separator + "bin" + File.separator + "jar");
            if (jarExeAlt.exists()) {
                try { return jarExeAlt.getCanonicalPath(); }
                catch (IOException e) { return jarExeAlt.getAbsolutePath(); }
            }
        }
        return "jar";
    }

    private String resolveProperty(String val, Map<String, String> props) {
        return resolveProperty(val, props, 0);
    }

    private String resolveProperty(String val, Map<String, String> props, int depth) {
        if (val == null || depth > 10) return val;
        if (val.startsWith("${") && val.endsWith("}")) {
            String key = val.substring(2, val.length() - 1);
            String resolved = props.get(key);
            if (resolved != null && !resolved.equals(val)) {
                return resolveProperty(resolved, props, depth + 1);
            }
            return val;
        }
        Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(val);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            m.reset();
            while (m.find()) {
                String key = m.group(1);
                String resolved = props.getOrDefault(key, m.group(0));
                m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
            }
            m.appendTail(sb);
            String result = sb.toString();
            if (result.contains("${") && depth < 10) {
                return resolveProperty(result, props, depth + 1);
            }
            return result;
        }
        return val;
    }

    // ================== DATA CLASSES ==================

    private static class PomData {
        String packaging;
        Map<String, String> properties      = new HashMap<>();
        Map<String, String> managedVersions = new HashMap<>();
        List<Dependency>    dependencies    = new ArrayList<>();
    }

    private static class Dependency {
        String groupId, artifactId, version, scope;
        boolean optional;
    }
}