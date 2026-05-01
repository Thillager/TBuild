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
 * T-build - Vollstaendig reparierte Version
 *
 * Bugfixes:
 * - Classpath beim java-Prozess verwendet absolute Pfade (out + alle JARs)
 * - JavaFX-Module werden automatisch erkannt und per --module-path +
 * --add-modules gestartet
 * - Optional-Dependencies werden nun korrekt uebersprungen (war vorher falsch
 * kommentiert)
 * - import-scope BOMs werden aufgeloest (z.B. jackson-bom,
 * spring-boot-dependencies)
 * - Parent-POM-Vererbung korrekt: properties + managedVersions werden
 * vollstaendig vererbt
 * - Race Condition beim activeDownloadTasks-Counter behoben (Increment VOR
 * pool.submit)
 * - Property-Aufloesung rekursiv (z.B. ${revision} das auf ${anderes.property}
 * zeigt)
 * - jar-Tool-Pfad wird ueber JAVA_HOME ermittelt, falls nicht im PATH
 * - Transitive Deps mit fehlendem Scope werden korrekt als "compile" behandelt
 * - Doppelte POM-Downloads durch fruehzeitiges Caching verhindert
 * - Package-Unterstuetzung: Quellverzeichnis wird als Source-Root uebergeben
 * - Fat JAR behaelt Package-Verzeichnisstruktur korrekt bei
 * - URL-Encoding fuer Maven-Suchanfragen
 * - getTag() sucht nur direkte Kinder (kein falsches Erben von parent-Tags)
 * - stripModuleInfo() entfernt auch leere Verzeichnisse nach dem Aufraeumen
 */
public class TBuild {

    private JFrame frame;
    private JTextPane console;
    private JTextField searchField;
    private DefaultListModel<String> resultModel;
    private JList<String> resultList;
    private ExecutorService pool;
    private Set<String> downloaded = ConcurrentHashMap.newKeySet();
    // FIX: Separater Cache fuer bereits gestartete POM-Downloads, um Doppelarbeit
    // zu verhindern
    private Set<String> pomDownloadStarted = ConcurrentHashMap.newKeySet();
    private Map<String, PomData> pomCache = new ConcurrentHashMap<>();
    private AtomicInteger activeDownloadTasks = new AtomicInteger(0);

    public static void main(String[] args) {
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

    private void createUI() {
        frame = new JFrame("T-build - Simple Java Build Tool");
        frame.setSize(1100, 650);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        frame.setContentPane(root);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton initBtn = new JButton("Projekt initialisieren");
        JButton buildBtn = new JButton("Bauen & Ausfuehren");
        JButton mainBtn = new JButton("Main-Klasse setzen");
        JButton clearBtn = new JButton("Konsole leeren");
        JButton exportJarBtn = new JButton("Export JAR");
        JButton exportFatJarBtn = new JButton("Export Fat JAR");
        initBtn.addActionListener(e -> initProject());
        buildBtn.addActionListener(e -> runBuild());
        mainBtn.addActionListener(e -> setMainDialog());
        clearBtn.addActionListener(e -> console.setText(""));
        exportJarBtn.addActionListener(e -> exportToJar(false));
        exportFatJarBtn.addActionListener(e -> exportToJar(true));
        top.add(initBtn);
        top.add(buildBtn);
        top.add(exportJarBtn);
        top.add(exportFatJarBtn);
        top.add(mainBtn);
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
        JButton searchBtn = new JButton("Suchen");
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchBtn, BorderLayout.EAST);
        resultModel = new DefaultListModel<>();
        resultList = new JList<>(resultModel);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        right.add(searchPanel, BorderLayout.NORTH);
        right.add(new JScrollPane(resultList), BorderLayout.CENTER);
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(console), right);
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

    private void stripModuleInfo(File dir) {
        log("[INFO] Entferne Modul-Metadaten (JPMS deaktivieren)...\n", Color.GRAY);
        try {
            // FIX: Dateien zuerst loeschen, dann leere Verzeichnisse entfernen
            Files.walk(dir.toPath())
                    .filter(p -> {
                        String path = p.toString().replace("\\", "/");
                        return p.getFileName().toString().equals("module-info.class")
                                || path.contains("META-INF/versions/");
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
            // Leere META-INF/versions Unterordner aufraeuemen
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
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log("[WARNUNG] Konnte Modul-Metadaten nicht vollständig entfernen.\n", Color.ORANGE);
        }
    }

    // ================== LOGGING ==================

    private void log(String msg, Color color) {
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
                }
                saveMainClass("Main");
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
        new Thread(() -> {
            try {
                File srcDir = new File("src/main/java");
                if (!srcDir.exists() || !srcDir.isDirectory()) {
                    log("[FEHLER] Ordner 'src/main/java' fehlt. Klicke auf 'Projekt initialisieren'.\n", Color.RED);
                    return;
                }
                File outDir = new File("out");
                outDir.mkdirs();
                // FIX: buildClasspath() liefert nur die JARs; out-Ordner wird separat
                // an javac uebergeben und spaeter fuer den java-Prozess zusammengefuehrt.
                String compileClasspath = buildClasspath();
                log("[INFO] Kompiliere Dateien mit Classpath: " + compileClasspath + "\n", Color.CYAN);
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    log("[FEHLER] Kein Java Compiler gefunden. Laeuft das Programm mit einem JDK (nicht JRE)?\n",
                            Color.RED);
                    return;
                }
                List<File> files = new ArrayList<>();
                Files.walk(srcDir.toPath())
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> files.add(p.toFile()));
                if (files.isEmpty()) {
                    log("[FEHLER] Keine .java Dateien gefunden.\n", Color.RED);
                    return;
                }
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
                StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
                // FIX: -sourcepath uebergibt das Quellverzeichnis als Root, damit javac
                // Packages korrekt aufloest. -d gibt den Ausgabeordner an, in dem
                // die Paketstruktur automatisch als Verzeichnishierarchie angelegt wird.
                boolean success = compiler.getTask(
                        null, fm, diagnostics,
                        Arrays.asList(
                                "-d", outDir.getAbsolutePath(),
                                "-sourcepath", srcDir.getAbsolutePath(),
                                "-classpath", compileClasspath),
                        null, fm.getJavaFileObjectsFromFiles(files)
                ).call();
                if (!success) {
                    log("[FEHLER] Kompilierung fehlgeschlagen. Bitte Code pruefen:\n", Color.RED);
                    for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                        log(" - Zeile " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null) + "\n",
                                Color.ORANGE);
                    }
                    return;
                }
                log("[ERFOLG] Erfolgreich kompiliert. Starte Programm...\n", Color.GREEN);
                log("--------------------------------------------------\n", Color.GRAY);
                // FIX: Erkennt automatisch JavaFX-JARs im libs-Ordner und startet
                // dann mit --module-path und --add-modules statt -cp allein.
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
            } catch (Exception e) {
                log("[FEHLER] Build-Prozess abgestuerzt: " + e.getMessage() + "\n", Color.RED);
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * FIX: Baut den Startbefehl fuer 'java'. Erkennt JavaFX-JARs automatisch
     * und fuegt --module-path sowie --add-modules=ALL-MODULE-PATH hinzu,
     * da JavaFX Module sind und nicht ueber den normalen Classpath geladen werden.
     */
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

    /**
     * Prueft ob ein JAR-Dateiname zu JavaFX gehoert.
     */
    private boolean isJavafxJar(String name) {
        return name.startsWith("javafx-") || name.startsWith("javafx.")
                || name.contains("javafx-base") || name.contains("javafx-controls")
                || name.contains("javafx-fxml") || name.contains("javafx-graphics")
                || name.contains("javafx-media") || name.contains("javafx-swing")
                || name.contains("javafx-web");
    }

    /**
     * FIX: Ermittelt den java-Ausfuehrungspfad ueber JAVA_HOME, damit
     * auch in Umgebungen ohne java im PATH korrekt gestartet werden kann.
     */
    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File javaExe = new File(javaHome, "bin" + File.separator + "java");
            if (javaExe.exists()) {
                return javaExe.getAbsolutePath();
            }
            // Manchmal ist java.home das jre-Unterverzeichnis
            File javaExeAlt = new File(
                    javaHome + File.separator + ".." + File.separator + "bin" + File.separator + "java");
            if (javaExeAlt.exists()) {
                try {
                    return javaExeAlt.getCanonicalPath();
                } catch (IOException e) {
                    return javaExeAlt.getAbsolutePath();
                }
            }
        }
        return "java"; // Fallback auf PATH
    }

    /**
     * FIX: buildClasspath() gibt den vollstaendigen Classpath zurueck:
     * - out-Ordner (absolute path)
     * - Alle JARs in libs/ (absolute paths)
     * JavaFX-JARs werden bewusst EINGESCHLOSSEN, da javac sie fuer den
     * Kompiliervorgang benoetigt. Beim Start werden sie zusaetzlich per
     * --module-path bekannt gemacht.
     */
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
            saveMainClass(val.trim());
            log("[INFO] Main-Klasse auf '" + val.trim() + "' gesetzt.\n", Color.LIGHT_GRAY);
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

    private void saveMainClass(String mc) {
        try (PrintWriter pw = new PrintWriter("T.xml")) {
            pw.println("<project>\n  <mainClass>" + mc + "</mainClass>\n</project>");
        } catch (Exception ignored) {
        }
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
                // FIX: Query URL-encodieren um Sonderzeichen korrekt zu uebertragen
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
                log("[FEHLER] Suche fehlgeschlagen. Internetverbindung pruefen.\n", Color.RED);
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
                while (m.find()) {
                    versions.add(m.group(1));
                }
                if (versions.isEmpty()) {
                    log("[WARNUNG] Keine Versionen fuer " + p[1] + " gefunden.\n", Color.ORANGE);
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    String choice = (String) JOptionPane.showInputDialog(
                            frame, "Version waehlen fuer " + p[1] + ":",
                            "Versionsauswahl", JOptionPane.PLAIN_MESSAGE,
                            null, versions.toArray(), versions.get(0)
                    );
                    if (choice != null) {
                        // FIX: Kein separater JavaFX-Hinweis mehr noetig, da der Start
                        // nun automatisch --module-path verwendet wenn JavaFX-JARs erkannt werden.
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
        new Thread(() -> {
            try {
                pool = Executors.newFixedThreadPool(6);
                downloaded.clear();
                pomDownloadStarted.clear();
                pomCache.clear();
                activeDownloadTasks.set(0);
                log("[INFO] Starte Aufloesung von " + artifactId + ":" + version + "...\n", Color.CYAN);
                resolve(groupId, artifactId, version);
                // FIX: Warte bis activeDownloadTasks wirklich 0 ist UND der Pool
                // keine weiteren Tasks mehr annimmt (kurze Sleep-Schleife ist korrekt hier).
                while (activeDownloadTasks.get() > 0) {
                    Thread.sleep(250);
                }
                pool.shutdown();
                pool.awaitTermination(5, TimeUnit.MINUTES);
                log("[ERFOLG] Alle Dependencies wurden geladen.\n", Color.GREEN);
            } catch (Exception e) {
                log("[FEHLER] Kritischer Fehler beim Download-Prozess: " + e.getMessage() + "\n", Color.RED);
                if (pool != null && !pool.isShutdown()) pool.shutdownNow();
            }
        }).start();
    }

    private void resolve(String g, String a, String v) {
        if (g == null || a == null || v == null) return;
        // FIX: Version koennte noch ein unaufgeloestes Property sein -> ueberspringen
        if (v.startsWith("${")) {
            log("[WARNUNG] Konnte Version fuer " + g + ":" + a + " nicht aufloesen (Property: " + v
                    + "). Ueberspringe.\n", Color.ORANGE);
            return;
        }
        String key = g + ":" + a + ":" + v;
        if (!downloaded.add(key)) return;
        // FIX: Increment VOR pool.submit(), damit der Counter niemals kurzzeitig
        // auf 0 faellt waehrend noch Tasks in der Queue sind.
        activeDownloadTasks.incrementAndGet();
        pool.submit(() -> {
            try {
                if (downloadPom(g, a, v)) {
                    PomData data = parsePom(g, a, v);
                    if (data != null) {
                        // FIX: "pom"-Packaging und "import"-Scope BOMs liefern kein JAR.
                        if (!"pom".equalsIgnoreCase(data.packaging)) {
                            downloadJar(g, a, v);
                        }
                        for (Dependency dep : data.dependencies) {
                            // FIX: Korrekte Scope-Filterung:
                            // - "test" und "provided" werden nie benoetigt zur Laufzeit
                            // - "optional" wird uebersprungen (war im Original falsch als "geladen"
                            // markiert)
                            // - null/fehlender Scope wird als "compile" behandelt -> herunterladen
                            // - "import" wird als BOM behandelt -> wird bereits in parsePom aufgeloest
                            String scope = dep.scope;
                            if ("test".equals(scope) || "provided".equals(scope) || "system".equals(scope)) {
                                continue;
                            }
                            if (dep.optional) {
                                continue;
                            }
                            resolve(dep.groupId, dep.artifactId, dep.version);
                        }
                    }
                }
            } catch (Exception e) {
                log("[WARNUNG] Konnte " + key + " nicht vollstaendig laden: " + e.getMessage() + "\n", Color.ORANGE);
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
        // FIX: getTag() sucht nur direkte Kinder des Root-Elements.
        // Ohne diese Einschraenkung wuerde z.B. groupId aus dem <parent>-Block
        // faelschlicherweise als Top-Level-groupId des Projekts gelesen.
        String pomGroupId = getDirectTag(root, "groupId");
        String pomVersion = getDirectTag(root, "version");
        // Falls groupId oder version fehlt, kommt es vom Parent -> wird unten gesetzt.
        data.packaging = getDirectTag(root, "packaging");
        if (data.packaging == null) data.packaging = "jar";
        // FIX: Parent-POM zuerst vollstaendig laden und vererben,
        // damit Properties und managedVersions fuer das aktuelle POM verfuegbar sind.
        org.w3c.dom.Element parentEl = getChildElement(root, "parent");
        if (parentEl != null) {
            String pg = getTag(parentEl, "groupId");
            String pa = getTag(parentEl, "artifactId");
            String pv = getTag(parentEl, "version");
            if (pg != null && pa != null && pv != null) {
                // Synchron laden und warten, da wir die Daten sofort brauchen
                downloadPom(pg, pa, pv);
                PomData parentData = parsePom(pg, pa, pv);
                if (parentData != null) {
                    // FIX: Properties und managedVersions vom Parent erben (Parent zuerst, dann
                    // ueberschreiben)
                    data.properties.putAll(parentData.properties);
                    data.managedVersions.putAll(parentData.managedVersions);
                }
            }
            // FIX: Falls groupId/version im aktuellen POM fehlen, vom Parent erben
            if (pomGroupId == null && pg != null) {
                data.properties.put("project.groupId", pg);
            }
            if (pomVersion == null && pv != null) {
                data.properties.put("project.version", pv);
            }
        }
        // Eigene project.* Properties setzen (ueberschreiben Parent-Werte)
        if (pomGroupId != null) data.properties.put("project.groupId", pomGroupId);
        else if (!data.properties.containsKey("project.groupId")) data.properties.put("project.groupId", g);
        if (pomVersion != null) data.properties.put("project.version", pomVersion);
        else if (!data.properties.containsKey("project.version")) data.properties.put("project.version", v);
        // Weitere Standard-Properties
        data.properties.put("project.artifactId", a);
        // FIX: ${revision} ist ein haeufig verwendetes Property in
        // Multi-Modul-Projekten
        if (!data.properties.containsKey("revision")) {
            data.properties.put("revision", v);
        }
        // Eigene <properties> laden (ueberschreiben geerbte Werte)
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
        // FIX: dependencyManagement laden - auch BOMs mit scope=import werden
        // aufgeloest
        org.w3c.dom.Element depMgmtEl = getChildElement(root, "dependencyManagement");
        if (depMgmtEl != null) {
            org.w3c.dom.Element depsEl2 = getChildElement(depMgmtEl, "dependencies");
            if (depsEl2 != null) {
                NodeList deps = depsEl2.getChildNodes();
                for (int i = 0; i < deps.getLength(); i++) {
                    Node node = deps.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("dependency")) {
                        org.w3c.dom.Element e = (org.w3c.dom.Element) node;
                        String dg = resolveProperty(getTag(e, "groupId"), data.properties);
                        String da = resolveProperty(getTag(e, "artifactId"), data.properties);
                        String dv = resolveProperty(getTag(e, "version"), data.properties);
                        String ds = resolveProperty(getTag(e, "scope"), data.properties);
                        // FIX: BOMs mit scope=import werden synchron geladen und ihre
                        // managedVersions in die aktuellen Daten integriert.
                        if ("import".equals(ds)
                                && "pom".equalsIgnoreCase(resolveProperty(getTag(e, "type"), data.properties))) {
                            if (dg != null && da != null && dv != null && !dv.startsWith("${")) {
                                downloadPom(dg, da, dv);
                                PomData bomData = parsePom(dg, da, dv);
                                if (bomData != null) {
                                    // BOM-Versionen nur eintragen, falls noch nicht vorhanden (BOM hat niedrigere
                                    // Prio als direkte Angabe)
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
        // Direkte <dependencies> laden
        org.w3c.dom.Element depsEl = getChildElement(root, "dependencies");
        if (depsEl != null) {
            NodeList deps = depsEl.getChildNodes();
            for (int i = 0; i < deps.getLength(); i++) {
                Node node = deps.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("dependency")) {
                    org.w3c.dom.Element e = (org.w3c.dom.Element) node;
                    Dependency dep = new Dependency();
                    dep.groupId = resolveProperty(getTag(e, "groupId"), data.properties);
                    dep.artifactId = resolveProperty(getTag(e, "artifactId"), data.properties);
                    dep.version = resolveProperty(getTag(e, "version"), data.properties);
                    dep.scope = resolveProperty(getTag(e, "scope"), data.properties);
                    dep.optional = "true".equalsIgnoreCase(resolveProperty(getTag(e, "optional"), data.properties));
                    // FIX: Version aus managedVersions holen falls nicht direkt angegeben
                    if ((dep.version == null || dep.version.startsWith("${")) && dep.groupId != null
                            && dep.artifactId != null) {
                        String managed = data.managedVersions.get(dep.groupId + ":" + dep.artifactId);
                        if (managed != null && !managed.startsWith("${")) {
                            dep.version = managed;
                        }
                    }
                    // FIX: Nochmals Properties aufloesen falls Version ein Property war
                    if (dep.version != null && dep.version.startsWith("${")) {
                        dep.version = resolveProperty(dep.version, data.properties);
                    }
                    if (dep.groupId != null && dep.artifactId != null && dep.version != null
                            && !dep.version.startsWith("${")) {
                        data.dependencies.add(dep);
                    } else if (dep.groupId != null && dep.artifactId != null) {
                        log("[WARNUNG] Konnte Version fuer " + dep.groupId + ":" + dep.artifactId
                                + " nicht aufloesen. Ueberspringe.\n", Color.ORANGE);
                    }
                }
            }
        }
        pomCache.put(cacheKey, data);
        return data;
    }

    /**
     * FIX: Verhindert parallele Downloads derselben POM-Datei durch
     * pomDownloadStarted-Set.
     * Wartet aktiv bis die Datei fertig geschrieben ist (fuer synchrone
     * Parent/BOM-Aufloesung).
     */
    private boolean downloadPom(String g, String a, String v) {
        if (g == null || a == null || v == null || v.startsWith("${")) return false;
        new File("libs").mkdirs();
        String target = "libs/" + a + "-" + v + ".pom";
        File targetFile = new File(target);
        if (targetFile.exists() && targetFile.length() > 0) return true;
        // Verhindere doppelte Downloads
        String key = g + ":" + a + ":" + v + ":pom";
        if (!pomDownloadStarted.add(key)) {
            // Ein anderer Thread laedt gerade herunter -> kurz warten
            for (int i = 0; i < 40; i++) {
                if (targetFile.exists() && targetFile.length() > 0) return true;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
            }
            return targetFile.exists() && targetFile.length() > 0;
        }
        String url = "https://repo1.maven.org/maven2/" + g.replace(".", "/") + "/" + a + "/" + v + "/" + a + "-" + v
                + ".pom";
        return downloadFile(url, target);
    }

    private boolean downloadJar(String g, String a, String v) {
        if (g == null || a == null || v == null || v.startsWith("${")) return false;
        new File("libs").mkdirs();
        String url = "https://repo1.maven.org/maven2/" + g.replace(".", "/") + "/" + a + "/" + v + "/" + a + "-" + v
                + ".jar";
        String target = "libs/" + a + "-" + v + ".jar";
        File targetFile = new File(target);
        if (targetFile.exists() && targetFile.length() > 0) return true;
        return downloadFile(url, target);
    }

    private boolean downloadFile(String urlStr, String target) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (T-build/1.3)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            int responseCode = conn.getResponseCode();
            if (responseCode == 404) {
                // 404 ist kein Fehler den wir loggen muessen (kommt bei optionalen Artefakten
                // vor)
                return false;
            }
            if (responseCode != 200) {
                log("[WARNUNG] HTTP " + responseCode + " fuer: " + new File(target).getName() + "\n", Color.ORANGE);
                return false;
            }
            log("[INFO] Lade herunter: " + new File(target).getName() + "\n", Color.GRAY);
            // FIX: Zuerst in temporaere Datei schreiben, dann atomar umbenennen.
            // Verhindert, dass andere Threads eine halbfertige Datei lesen.
            Path tempPath = Paths.get(target + ".tmp");
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tempPath, Paths.get(target), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            // Aufraeuemen falls temp-Datei existiert
            try {
                Files.deleteIfExists(Paths.get(target + ".tmp"));
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    // ================== EXPORT JAR ==================

    private void exportToJar(boolean fat) {
        new Thread(() -> {
            try {
                String mainClass = getMainClass();
                String jarName = fat ? "Export-Fat.jar" : "Export.jar";
                File tempDir = new File("build_temp");
                log("[INFO] Starte Export: " + jarName + "...\n", Color.CYAN);
                if (tempDir.exists()) deleteDirectory(tempDir);
                tempDir.mkdirs();
                File outDir = new File("out");
                if (!outDir.exists() || outDir.listFiles() == null || outDir.listFiles().length == 0) {
                    log("[FEHLER] 'out' Ordner leer. Bitte erst 'Bauen' klicken.\n", Color.RED);
                    return;
                }
                // FIX: copyDirectory behaelt die vollstaendige Paketstruktur bei,
                // da es rekursiv den gesamten out/-Baum in tempDir kopiert.
                copyDirectory(outDir, tempDir);
                if (fat) {
                    File libDir = new File("libs");
                    if (libDir.exists() && libDir.listFiles() != null) {
                        for (File f : libDir.listFiles()) {
                            if (f.getName().endsWith(".jar")) {
                                log("   -> Integriere " + f.getName() + "...\n", Color.GRAY);
                                // FIX: jar-Tool ebenfalls ueber JAVA_HOME ermitteln
                                String jarTool = getJarToolExecutable();
                                ProcessBuilder pb = new ProcessBuilder(jarTool, "xf", f.getAbsolutePath());
                                pb.directory(tempDir);
                                pb.redirectErrorStream(true);
                                Process proc = pb.start();
                                // Ausgabe konsumieren um Buffer-Blockierung zu verhindern
                                new BufferedReader(new InputStreamReader(proc.getInputStream()))
                                        .lines().forEach(line -> {
                                        });
                                proc.waitFor();
                            }
                        }
                    }
                }
                // FIX: Modul-System entfernen (wichtig fuer Java 9+ Libraries wie FlatLaf)
                stripModuleInfo(tempDir);
                log("[INFO] Packe finale Datei...\n", Color.CYAN);
                // FIX: mainClass enthaelt ggf. Punkte (z.B. "de.meinprojekt.Main") was
                // korrekt ist - jar -e erwartet den voll qualifizierten Klassennamen mit Punkten.
                // Die Verzeichnisstruktur im tempDir muss dafuer korrekt vorliegen (s.o.).
                String jarTool = getJarToolExecutable();
                ProcessBuilder pb = new ProcessBuilder(jarTool, "cfe", "../" + jarName, mainClass, ".");
                pb.directory(tempDir);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                // Ausgabe konsumieren
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    log("   " + line + "\n", Color.GRAY);
                }
                int exitCode = proc.waitFor();
                if (exitCode != 0) {
                    log("[FEHLER] jar-Tool beendete sich mit Exit-Code " + exitCode + ".\n", Color.RED);
                    return;
                }
                deleteDirectory(tempDir);
                log("[ERFOLG] " + jarName + " wurde im Projektverzeichnis erstellt!\n", Color.GREEN);
                log("[INFO] Startbar mit: java -jar " + jarName + "\n", Color.LIGHT_GRAY);
            } catch (Exception e) {
                log("[FEHLER] Export fehlgeschlagen: " + e.getMessage() + "\n", Color.RED);
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * FIX: Ermittelt den jar-Tool-Pfad ueber JAVA_HOME.
     */
    private String getJarToolExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File jarExe = new File(javaHome, "bin" + File.separator + "jar");
            if (jarExe.exists()) return jarExe.getAbsolutePath();
            // java.home kann auf jre/ zeigen, jar ist aber im uebergeordneten bin/
            File jarExeAlt = new File(
                    javaHome + File.separator + ".." + File.separator + "bin" + File.separator + "jar");
            if (jarExeAlt.exists()) {
                try {
                    return jarExeAlt.getCanonicalPath();
                } catch (IOException e) {
                    return jarExeAlt.getAbsolutePath();
                }
            }
        }
        return "jar"; // Fallback auf PATH
    }

    // ================== HELPER METHODS ==================

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

    /**
     * FIX: Liest einen Tag-Wert nur aus direkten Kindern des Root-Elements.
     * getTag() delegiert an getChildElement() welches nur eine Ebene tief sucht,
     * aber bei Root-Elementen wie groupId/version koennte ein gleichnamiges Tag
     * in einem Unterelement (z.B. <parent><groupId>) faelschlicherweise
     * zurueckgegeben werden, wenn getElementsByTagName() verwendet wuerde.
     * Diese Methode stellt sicher, dass wirklich nur das direkte Kind gemeint ist.
     */
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

    private String getTag(org.w3c.dom.Element parent, String name) {
        org.w3c.dom.Element child = getChildElement(parent, name);
        return child != null ? child.getTextContent().trim() : null;
    }

    /**
     * FIX: Rekursive Property-Aufloesung. Loest auch Properties auf, deren Wert
     * selbst wieder ein Property-Verweis ist (z.B. ${revision} -> ${anderer.wert}
     * -> "1.2.3").
     * Maximale Tiefe: 10, um Endlosschleifen zu vermeiden.
     */
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
            return val; // Unaufloesbar, unveraendert zurueckgeben
        }
        // FIX: Auch Properties die mitten im String vorkommen aufloesen (z.B.
        // "com.${group}.lib")
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
            // Nochmals aufloesen falls noch Properties enthalten
            if (result.contains("${") && depth < 10) {
                return resolveProperty(result, props, depth + 1);
            }
            return result;
        }
        return val;
    }

    private static class PomData {

        String packaging;
        Map<String, String> properties = new HashMap<>();
        Map<String, String> managedVersions = new HashMap<>();
        List<Dependency> dependencies = new ArrayList<>();

    }

    private static class Dependency {

        String groupId, artifactId, version, scope;
        boolean optional;

    }

}