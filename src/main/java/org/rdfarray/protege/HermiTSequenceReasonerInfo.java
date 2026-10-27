/*
 * protege-owl-array, https://github.com/rdf-array/protege-owl-array
 * Licensed under the Apache License, Version 2.0 and the LGPL 3.0, as the OWL API is.
 */
package org.rdfarray.protege;

import org.protege.editor.owl.model.inference.AbstractProtegeOWLReasonerInfo;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.rdfarray.SequenceAwareReasonerFactory;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * HermiT behind owlapi-array's {@link SequenceAwareReasonerFactory}: the reasoner sees the
 * ontology with every sequence replaced by its expansion (owl-array.md §6), closure included, and
 * the OWL-array vocabulary's axioms; the ontology Protégé edits is not changed.
 */
public class HermiTSequenceReasonerInfo extends AbstractProtegeOWLReasonerInfo {

    private final OWLReasonerFactory factory = new SequenceAwareReasonerFactory(new ReasonerFactory());

    @Override
    public OWLReasonerFactory getReasonerFactory() {
        return factory;
    }

    @Override
    public BufferingMode getRecommendedBuffering() {
        return BufferingMode.BUFFERING;
    }
}
