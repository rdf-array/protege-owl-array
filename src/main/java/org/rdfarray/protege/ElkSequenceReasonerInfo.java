/*
 * protege-owl-array, https://github.com/rdf-array/protege-owl-array
 * Licensed under the Apache License, Version 2.0 and the LGPL 3.0, as the OWL API is.
 */
package org.rdfarray.protege;

import org.protege.editor.owl.model.inference.AbstractProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerPlugin;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerPluginLoader;
import org.semanticweb.owlapi.rdfarray.SequenceAwareReasonerFactory;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * ELK behind owlapi-array's {@link SequenceAwareReasonerFactory}, without the closure axioms,
 * which OWL 2 EL cannot express (owl-array.md §6). The ELK plugin exports no package, so its
 * factory is not imported: it is taken from ELK's own reasoner plugin, through Protégé's plugin
 * registry, when it is first needed.
 */
public class ElkSequenceReasonerInfo extends AbstractProtegeOWLReasonerInfo {

    /** The end of the id of ELK's reasoner extension, {@code org.semanticweb.elk.elk.reasoner.factory}. */
    static final String ELK_EXTENSION = "elk.reasoner.factory";

    private OWLReasonerFactory factory;

    @Override
    public synchronized OWLReasonerFactory getReasonerFactory() {
        if (factory == null) {
            factory = new SequenceAwareReasonerFactory(elk().getReasonerFactory(), false);
        }
        return factory;
    }

    private ProtegeOWLReasonerInfo elk() {
        for (ProtegeOWLReasonerPlugin plugin : new ProtegeOWLReasonerPluginLoader(getOWLModelManager()).getPlugins()) {
            if (plugin.getId().endsWith(ELK_EXTENSION)) {
                try {
                    ProtegeOWLReasonerInfo info = plugin.newInstance();
                    info.initialise();
                    return info;
                } catch (Exception e) {
                    throw new IllegalStateException("the ELK reasoner plugin could not be started", e);
                }
            }
        }
        throw new IllegalStateException("the ELK reasoner plugin (elk-protege) is not installed");
    }

    @Override
    public BufferingMode getRecommendedBuffering() {
        return BufferingMode.BUFFERING;
    }
}
