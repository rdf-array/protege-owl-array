/*
 * protege-owl-array, https://github.com/rdf-array/protege-owl-array
 * Licensed under the Apache License, Version 2.0 and the LGPL 3.0, as the OWL API is.
 */
package org.rdfarray.protege.walkthrough;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.protege.editor.core.editorkit.plugin.EditorKitHook;
import org.protege.editor.core.ui.workspace.WorkspaceTab;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.OWLWorkspace;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.ui.renderer.OWLRendererPreferences;
import org.protege.editor.owl.ui.renderer.plugin.RendererPlugin;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.RDFParserMetaData;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Drives the walk-through of rdf-array's examples/fhir (README §4) in a running Protégé when the
 * system property {@code rdfarray.walkthrough} names an output directory, and does nothing
 * otherwise. It waits for {@code subjects.ttl} to be the active ontology, records what was loaded,
 * shows amy:Obs1, picks the OWL-array HermiT in the reasoner menu and starts it, then types each DL
 * query of README §5 into the DL Query tab and records the instances the tab lists. The window is
 * painted to PNG files by Swing; {@code walkthrough.txt} has the text. Protégé exits at the end.
 */
public class Walkthrough extends EditorKitHook {

    static final AtomicBoolean STARTED = new AtomicBoolean();
    static final String DL_QUERY_TAB = "org.coode.dlquery.DLQueryTab";
    static final String INDIVIDUALS_TAB = "org.protege.editor.owl.OWLIndividualsTab";
    static final String ONTOLOGY_TAB = "org.protege.editor.owl.OWLOntologyTab";
    static final Map<String, String> PREFIXES = new LinkedHashMap<>();
    static {
        PREFIXES.put("fhir:", "http://hl7.org/fhir/");
        PREFIXES.put("sct:", "http://snomed.info/id/");
        PREFIXES.put("loinc:", "https://loinc.org/rdf/");
        PREFIXES.put("ra:", "https://rdf-array.github.io/rdf-array/vocab#");
        PREFIXES.put("xsd:", "http://www.w3.org/2001/XMLSchema#");
        PREFIXES.put(":", "https://rdf-array.github.io/rdf-array/examples/fhir/arthritis.ttl#");
        PREFIXES.put("bp:", "https://rdf-array.github.io/rdf-array/examples/fhir/blood-pressure.ttl#");
        PREFIXES.put("amy:", "https://rdf-array.github.io/rdf-array/examples/fhir/subject-amy.ttl#");
        PREFIXES.put("bob:", "https://rdf-array.github.io/rdf-array/examples/fhir/subject-bob.ttl#");
        PREFIXES.put("sue:", "https://rdf-array.github.io/rdf-array/examples/fhir/subject-sue.ttl#");
    }

    private PrintWriter log;
    private File out;
    private OWLEditorKit kit;
    private OWLModelManager mm;

    @Override
    public void initialise() {
        String dir = System.getProperty("rdfarray.walkthrough");
        if (dir == null || !(getEditorKit() instanceof OWLEditorKit) || !STARTED.compareAndSet(false, true)) {
            return;
        }
        kit = (OWLEditorKit) getEditorKit();
        out = new File(dir);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(Long.getLong("rdfarray.walkthrough.timeout", 600) * 1000);
                Runtime.getRuntime().halt(2);
            } catch (InterruptedException e) {
                // Protégé is going away
            }
        }, "rdf-array walkthrough watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread t = new Thread(this::runSafely, "rdf-array walkthrough");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void dispose() {}

    private void runSafely() {
        try {
            out.mkdirs();
            log = new PrintWriter(new FileOutputStream(new File(out, "walkthrough.txt")), true);
            run();
        } catch (Throwable e) {
            if (log != null) {
                log.println("WALKTHROUGH FAILED: " + e);
                e.printStackTrace(log);
            }
        } finally {
            if (log != null) {
                log.close();
            }
            if (!Boolean.getBoolean("rdfarray.walkthrough.stay")) {
                Runtime.getRuntime().halt(0);
            }
        }
    }

    private void run() throws Exception {
        mm = kit.getOWLModelManager();
        OWLOntology active = waitForActive();
        Window frame = waitForWindow();
        onEdt(() -> {
            frame.setSize(1500, 950);
            frame.validate();
        });
        pause(3000);

        section("1. Loaded");
        OWLOntologyManager om = mm.getOWLOntologyManager();
        log.println("active ontology: " + iriOf(active));
        log.println("imports closure: " + active.getImportsClosure().size() + " ontologies, "
            + active.getAxiomCount(Imports.INCLUDED) + " axioms");
        int ladder = 0;
        for (OWLOntology o : active.getImportsClosure()) {
            OWLDocumentFormat f = om.getOntologyFormat(o);
            String unparsed = "?";
            if (f != null && f.getOntologyLoaderMetaData() instanceof RDFParserMetaData) {
                RDFParserMetaData md = (RDFParserMetaData) f.getOntologyLoaderMetaData();
                unparsed = Integer.toString(md.getUnparsedTriples().size());
                for (Object tr : md.getUnparsedTriples()) {
                    log.println("    unparsed: " + tr);
                }
            }
            log.println("  " + iriOf(o) + "  " + (f == null ? "?" : f.getKey()) + ", " + o.getAxiomCount()
                + " axioms, unparsed triples: " + unparsed);
            for (OWLEntity e : o.getSignature()) {
                if (e.getIRI().toString().equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#first")) {
                    ladder++;
                }
            }
        }
        log.println("rdf:first in a signature: " + ladder);
        TreeSet<String> sequences = new TreeSet<>();
        List<OWLAxiom> withSequence = new ArrayList<>();
        Pattern seq = Pattern.compile("_:seq_[A-Za-z0-9_-]+");
        for (OWLAxiom ax : active.getAxioms(Imports.INCLUDED)) {
            Matcher m = seq.matcher(ax.toString());
            boolean any = false;
            while (m.find()) {
                sequences.add(m.group());
                any = true;
            }
            if (any) {
                withSequence.add(ax);
            }
        }
        log.println("distinct sequence terms: " + sequences.size() + ", in " + withSequence.size() + " axioms");
        selectTab(ONTOLOGY_TAB);
        shot("01-loaded");

        section("2. How Protégé renders an axiom holding a sequence");
        withSequence.sort((a, b) -> a.toString().length() - b.toString().length());
        for (int i = 0; i < Math.min(3, withSequence.size()); i++) {
            log.println("  " + mm.getRendering(withSequence.get(i)));
        }
        OWLNamedIndividual amyObs1 = mm.getOWLDataFactory().getOWLNamedIndividual(IRI.create(PREFIXES.get("amy:") + "Obs1"));
        selectTab(INDIVIDUALS_TAB);
        onEdt(() -> kit.getOWLWorkspace().getOWLSelectionModel().setSelectedEntity(amyObs1));
        pause(2000);
        shot("02-amy-obs1");
        for (OWLAxiom ax : active.getReferencingAxioms(amyObs1, Imports.INCLUDED)) {
            log.println("  amy:Obs1: " + mm.getRendering(ax));
        }
        OWLNamedIndividual suePatient = mm.getOWLDataFactory().getOWLNamedIndividual(IRI.create(PREFIXES.get("sue:") + "asPatient"));
        onEdt(() -> kit.getOWLWorkspace().getOWLSelectionModel().setSelectedEntity(suePatient));
        pause(2000);
        shot("02b-sue-patient");
        for (OWLAxiom ax : active.getReferencingAxioms(suePatient, Imports.INCLUDED)) {
            log.println("  sue:asPatient: " + mm.getRendering(ax));
        }

        section("3. The reasoner");
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        String hermit = null;
        for (ProtegeOWLReasonerInfo info : rm.getInstalledReasonerFactories()) {
            log.println("  reasoner menu: " + info.getReasonerName() + "  (" + info.getReasonerId() + ")");
            if (info.getReasonerName().contains("OWL-array") && info.getReasonerName().startsWith("HermiT")) {
                hermit = info.getReasonerId();
            }
        }
        if (hermit == null) {
            log.println("no OWL-array HermiT in the reasoner menu");
            return;
        }
        String id = hermit;
        onEdt(() -> rm.setCurrentReasonerFactoryId(id));
        long t0 = System.currentTimeMillis();
        onEdt(() -> rm.classifyAsynchronously(rm.getReasonerPreferences().getPrecomputedInferences()));
        pause(500);
        while (rm.isClassificationInProgress() && System.currentTimeMillis() - t0 < 120000) {
            pause(250);
        }
        pause(1500);
        log.println("current reasoner: " + rm.getCurrentReasonerName() + "; status: " + rm.getReasonerStatus()
            + "; " + (System.currentTimeMillis() - t0) + " ms");
        String d = dialogs("03-dialog");
        if (!d.isEmpty()) {
            log.println("dialog after starting the reasoner: " + d);
        }
        log.println("consistent: " + rm.getCurrentReasoner().isConsistent());
        shot("03-reasoner-started");

        section("4. The DL Query tab, the names typed as the example spells them");
        List<String[]> queries = queries();
        setRenderer("prefixed");
        selectTab(DL_QUERY_TAB);
        pause(1500);
        log.println("query: " + queries.get(0)[0]);
        log.println("  " + query(queries.get(0)[0], null));

        section("5. The DL Query tab, the names typed as Protégé displays them");
        PrefixDocumentFormat pf = om.getOntologyFormat(active).asPrefixOWLOntologyFormat();
        log.println("prefixes of the active ontology before: " + pf.getPrefixName2PrefixMap().keySet());
        boolean addPrefixes = Boolean.getBoolean("rdfarray.walkthrough.prefixes");
        log.println("prefixes added to the active ontology by hand: " + addPrefixes);
        onEdt(() -> {
            if (addPrefixes) {
                for (Map.Entry<String, String> e : PREFIXES.entrySet()) {
                    pf.setPrefix(e.getKey(), e.getValue());
                }
            }
            mm.refreshRenderer();
        });
        setRenderer("prefixed");
        selectTab(DL_QUERY_TAB);
        pause(1500);
        for (String qn : new String[] {"fhir:Observation", "sct:Rheumatoid_arthritis-disorder", "sct:69896004",
            ":Coding-in-ICD-10", "bp:Observation-of-blood-pressure", "ra:member", "amy:Obs1"}) {
            OWLEntity e = entity(qn);
            log.println("  rendering of " + qn + ": " + (e == null ? "(no such entity)" : mm.getRendering(e)));
        }
        int n = 0, pass = 0;
        for (String[] q : queries) {
            n++;
            String name = String.format("10-query-%02d", n);
            TreeSet<String> expected = new TreeSet<>();
            for (String e : q[1].trim().split(" +")) {
                if (!e.isEmpty()) {
                    expected.add(e);
                }
            }
            String typed = asRendered(q[0]);
            if (!typed.equals(q[0])) {
                log.println("      typed as: " + typed);
            }
            String got = query(typed, name);
            boolean ok = got.equals(String.join(" ", expected));
            if (ok) {
                pass++;
            }
            log.println((ok ? "PASS " : "DIFF ") + name + "  " + q[0]);
            log.println("      tab lists: " + (got.isEmpty() ? "(none)" : got) + (ok ? "" : "    expected: " + expected));
        }
        log.println(pass + " of " + n + " queries list the expected instances");

        section("6. The same queries with ELK (OWL-array), closure off");
        String elk = null;
        for (ProtegeOWLReasonerInfo info : rm.getInstalledReasonerFactories()) {
            if (info.getReasonerName().startsWith("ELK") && info.getReasonerName().contains("OWL-array")) {
                elk = info.getReasonerId();
            }
        }
        if (elk != null) {
            String elkId = elk;
            onEdt(() -> rm.setCurrentReasonerFactoryId(elkId));
            long t1 = System.currentTimeMillis();
            onEdt(() -> rm.classifyAsynchronously(rm.getReasonerPreferences().getPrecomputedInferences()));
            pause(500);
            while (rm.isClassificationInProgress() && System.currentTimeMillis() - t1 < 120000) {
                pause(250);
            }
            pause(1500);
            String d2 = dialogs("20-elk-dialog");
            log.println("current reasoner: " + rm.getCurrentReasonerName() + "; status: " + rm.getReasonerStatus()
                + "; " + (System.currentTimeMillis() - t1) + " ms" + (d2.isEmpty() ? "" : "; dialog: " + d2));
            selectTab(DL_QUERY_TAB);
            int en = 0, ep = 0;
            for (String[] q : queries) {
                en++;
                TreeSet<String> expected = new TreeSet<>();
                for (String e : q[1].trim().split(" +")) {
                    if (!e.isEmpty()) {
                        expected.add(e);
                    }
                }
                String got = query(asRendered(q[0]), String.format("21-elk-query-%02d", en));
                boolean ok = got.equals(String.join(" ", expected));
                if (ok) {
                    ep++;
                }
                log.println((ok ? "PASS " : "DIFF ") + String.format("%02d ", en) + q[0]);
                if (!ok) {
                    log.println("      tab lists: " + (got.isEmpty() ? "(none)" : got) + "    expected: " + expected);
                }
            }
            log.println(ep + " of " + en + " queries list the expected instances with ELK");
        }

        section("7. Saving");
        save(om, "arthritis.ttl");
        save(om, "subject-sue.ttl");
        log.println("format the active ontology would be saved in: " + om.getOntologyFormat(active).getKey());
        log.println("done");
    }

    private void save(OWLOntologyManager om, String name) {
        for (OWLOntology o : om.getOntologies()) {
            if (iriOf(o).endsWith(name)) {
                for (OWLDocumentFormat f : new OWLDocumentFormat[] {new TurtleDocumentFormat(), new RDFXMLDocumentFormat()}) {
                    try {
                        StringDocumentTarget t = new StringDocumentTarget();
                        om.saveOntology(o, f, t);
                        OWLOntologyManager fresh = OWLManager.createOWLOntologyManager();
                        addCatalog(fresh, new File(om.getOntologyDocumentIRI(o).toURI()).getParentFile());
                        fresh.setOntologyLoaderConfiguration(new OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
                        OWLOntology back = fresh.loadOntologyFromOntologyDocument(new StringDocumentSource(t.toString()));
                        // blank nodes are relabelled on reading, and the writer adds the
                        // declarations it needs for typing: compare the axioms by type, without them
                        Map<String, Integer> before = countByType(o), after = countByType(back);
                        boolean same = before.equals(after);
                        log.println("  " + name + " as " + f.getKey() + ": saved, reopened, axioms "
                            + (same ? "the same by type (" + before + "), declarations aside" : "DIFFER " + before + " / " + after));
                        try (PrintWriter w = new PrintWriter(new File(out, name + "." + (f instanceof TurtleDocumentFormat ? "ttl" : "rdf")), "UTF-8")) {
                            w.print(t.toString());
                        }
                    } catch (Exception e) {
                        Throwable c = e;
                        while (c.getCause() != null && c.getCause() != c) {
                            c = c.getCause();
                        }
                        log.println("  " + name + " as " + f.getKey() + ": " + c.getClass().getSimpleName() + ": " + c.getMessage());
                    }
                }
            }
        }
    }

    /** Map the imports as catalog-v001.xml beside the documents does, as Protégé reads it. */
    private static void addCatalog(OWLOntologyManager m, File dir) throws Exception {
        org.w3c.dom.NodeList uris = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new File(dir, "catalog-v001.xml")).getElementsByTagName("uri");
        for (int i = 0; i < uris.getLength(); i++) {
            org.w3c.dom.Element u = (org.w3c.dom.Element) uris.item(i);
            m.getIRIMappers().add(new org.semanticweb.owlapi.util.SimpleIRIMapper(IRI.create(u.getAttribute("name")),
                IRI.create(new File(dir, u.getAttribute("uri")).getCanonicalFile())));
        }
    }

    private static Map<String, Integer> countByType(OWLOntology o) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (OWLAxiom ax : o.getAxioms()) {
            if (!ax.isOfType(AxiomType.DECLARATION)) {
                counts.merge(ax.getAxiomType().getName(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Type the query into the DL Query view, ask for instances, and return what the result list holds. */
    private String query(String q, String shotName) throws Exception {
        Container tab = (Container) kit.getOWLWorkspace().getSelectedTab();
        JTextComponent editor = find(tab, JTextComponent.class, "ExpressionEditor");
        AbstractButton execute = findButton(tab, "Execute");
        AbstractButton instances = findButton(tab, "Instances");
        if (editor == null || execute == null) {
            return "no DL query view (editor " + editor + ", execute " + execute + ")";
        }
        onEdt(() -> {
            if (instances != null && !instances.isSelected()) {
                instances.doClick();
            }
            editor.setText(q);
        });
        pause(1200);
        boolean enabled = execute.isEnabled();
        SwingUtilities.invokeLater(execute::doClick);
        pause(2000);
        String dialogs = dialogs(shotName == null ? "05-dialog" : shotName + "-dialog");
        if (shotName != null) {
            shot(shotName);
        } else {
            shot("05-query-without-prefixes");
        }
        if (!dialogs.isEmpty()) {
            return "dialog: " + dialogs;
        }
        if (!enabled) {
            return "Execute disabled: the expression does not parse";
        }
        TreeSet<String> names = new TreeSet<>();
        for (JList<?> list : findAll(tab, JList.class, "ResultsList")) {
            ListModel<?> m = list.getModel();
            for (int i = 0; i < m.getSize(); i++) {
                Object o = owlObject(m.getElementAt(i));
                if (o instanceof OWLNamedIndividual) {
                    names.add(short_(((OWLNamedIndividual) o).getIRI()));
                }
            }
        }
        return String.join(" ", names);
    }

    private static final Pattern QNAME = Pattern.compile("(?<![\\w<])([a-z]*:)([A-Za-z0-9_][A-Za-z0-9_.-]*)");

    /** The query with each prefixed name replaced by the rendering Protégé gives its entity. */
    private String asRendered(String q) {
        Matcher m = QNAME.matcher(q);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            OWLEntity e = entity(m.group(1) + m.group(2));
            m.appendReplacement(b, Matcher.quoteReplacement(e == null ? m.group() : mm.getRendering(e)));
        }
        m.appendTail(b);
        return b.toString();
    }

    private OWLEntity entity(String qname) {
        int colon = qname.indexOf(':');
        String ns = PREFIXES.get(qname.substring(0, colon + 1));
        if (ns == null) {
            return null;
        }
        IRI iri = IRI.create(ns + qname.substring(colon + 1));
        for (OWLEntity e : mm.getActiveOntology().getEntitiesInSignature(iri, Imports.INCLUDED)) {
            return e;
        }
        return null;
    }

    /** Record, paint and close the dialogs Protégé has opened; returns their messages. */
    private String dialogs(String name) throws Exception {
        StringBuilder text = new StringBuilder();
        for (Window w : Window.getWindows()) {
            if (w instanceof javax.swing.JDialog && w.isShowing()) {
                for (javax.swing.JOptionPane p : findAll(((javax.swing.JDialog) w).getContentPane(),
                    javax.swing.JOptionPane.class, null)) {
                    text.append(String.valueOf(p.getMessage()).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
                }
                if (text.length() == 0) {
                    text.append(((javax.swing.JDialog) w).getTitle());
                }
                paint(((javax.swing.JDialog) w).getRootPane(), new File(out, name + ".png"));
                SwingUtilities.invokeLater(w::dispose);
                pause(500);
            }
        }
        return text.toString();
    }

    private static Object owlObject(Object item) {
        try {
            Method m = item.getClass().getMethod("getOWLObject");
            return m.invoke(item);
        } catch (Exception e) {
            return null;
        }
    }

    static String short_(IRI iri) {
        String s = iri.toString();
        for (Map.Entry<String, String> e : PREFIXES.entrySet()) {
            if (s.startsWith(e.getValue())) {
                return e.getKey() + s.substring(e.getValue().length());
            }
        }
        return "<" + s + ">";
    }

    private void setRenderer(String kind) throws Exception {
        onEdt(() -> {
            OWLRendererPreferences p = OWLRendererPreferences.getInstance();
            for (RendererPlugin r : p.getRendererPlugins()) {
                if (r.getName().toLowerCase().contains(kind) && !p.getRendererPlugin().getName().toLowerCase().contains(kind)) {
                    p.setRendererPlugin(r);
                }
            }
            log.println("  renderer: " + p.getRendererPlugin().getName());
            mm.refreshRenderer();
        });
        pause(500);
    }

    private void selectTab(String id) throws Exception {
        OWLWorkspace ws = kit.getOWLWorkspace();
        onEdt(() -> {
            WorkspaceTab tab = ws.getWorkspaceTab(id);
            if (tab != null) {
                ws.setSelectedTab(tab);
            } else {
                log.println("  (no tab " + id + ")");
            }
        });
        pause(1500);
    }

    private OWLOntology waitForActive() throws InterruptedException {
        String target = System.getProperty("rdfarray.walkthrough.ontology", "subjects.ttl");
        for (int i = 0; i < 600; i++) {
            OWLOntology o = mm.getActiveOntology();
            if (o != null && iriOf(o).endsWith(target)) {
                return o;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("the active ontology never became " + target);
    }

    private Window waitForWindow() throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            Window w = SwingUtilities.getWindowAncestor(kit.getWorkspace());
            if (w != null && w.isShowing()) {
                return w;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("no workspace window");
    }

    private void shot(String name) throws Exception {
        Window w = SwingUtilities.getWindowAncestor(kit.getWorkspace());
        paint(w instanceof JFrame ? ((JFrame) w).getRootPane() : w, new File(out, name + ".png"));
    }

    private static void paint(Component c, File file) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        onEdt(() -> {
            img[0] = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            c.paintAll(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", file);
    }

    private void section(String title) {
        log.println();
        log.println("== " + title);
    }

    static String iriOf(OWLOntology o) {
        return o.getOntologyID().getOntologyIRI().isPresent() ? o.getOntologyID().getOntologyIRI().get().toString()
            : o.getOntologyID().toString();
    }

    private List<String[]> queries() throws Exception {
        List<String[]> qs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
            Walkthrough.class.getResourceAsStream("/queries.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty() && !line.startsWith("#")) {
                    String[] p = line.split("\t", -1);
                    qs.add(new String[] {p[0], p.length > 1 ? p[1] : ""});
                }
            }
        }
        return qs;
    }

    static <T> T find(Container root, Class<T> type, String simpleName) {
        List<T> all = findAll(root, type, simpleName);
        return all.isEmpty() ? null : all.get(0);
    }

    static <T> List<T> findAll(Container root, Class<T> type, String simpleName) {
        List<T> found = new ArrayList<>();
        collect(root, type, simpleName, found);
        return found;
    }

    private static <T> void collect(Component c, Class<T> type, String simpleName, List<T> found) {
        if (type.isInstance(c) && (simpleName == null || c.getClass().getSimpleName().equals(simpleName))) {
            found.add(type.cast(c));
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collect(child, type, simpleName, found);
            }
        }
    }

    static AbstractButton findButton(Container root, String text) {
        for (AbstractButton b : findAll(root, AbstractButton.class, null)) {
            if (text.equals(b.getText())) {
                return b;
            }
        }
        return null;
    }

    static void onEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeAndWait(r);
        }
    }

    static void pause(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
