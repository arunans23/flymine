package org.flymine.dataconversion;

/*
 * Copyright (C) 2002-2004 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.BufferedReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.intermine.objectstore.ObjectStoreException;
import org.intermine.xml.full.Item;
import org.intermine.xml.full.Attribute;
import org.intermine.xml.full.Reference;
import org.intermine.xml.full.ReferenceList;
import org.intermine.xml.full.ItemHelper;
import org.intermine.dataconversion.FileConverter;
import org.intermine.dataconversion.ItemWriter;

/**
 * DataConverter to parse an RNAi data file into Items
 * @author Andrew Varley
 */
public class RNAiConverter extends FileConverter
{
    protected static final String RNAI_NS = "http://www.flymine.org/model/rnai#";

    protected Map genes = new HashMap();
    protected Map synonyms = new HashMap();
    protected Map annotations = new HashMap();
    protected Map phenotypes = new HashMap();
    protected Item db;
    protected int id = 0;

    /**
     * Constructor
     * @param reader Reader of input data in tab delimited format
     * @param writer the ItemWriter used to handle the resultant items
     * @throws ObjectStoreException if an error occurs in storing
     */
    public RNAiConverter(BufferedReader reader, ItemWriter writer)
        throws ObjectStoreException {
        super(reader, writer);
        setupItems();
    }

    /**
     * @see DataConverter#process
     */
    public void process() throws Exception {
        try {
            //intentionally throw away first line
            String line = reader.readLine();

            while ((line = reader.readLine()) != null) {
                String[] array = line.split("\t", -1); //keep trailing empty Strings
                Item gene = newGene(array[3], array[6], array[2],
                        (array.length > 13 ? array[13] : null),
                        (array.length > 14 ? array[14] : null));
                String pubMedId;
                if (array[7].startsWith("pmid:")) {
                    pubMedId = array[7].substring(5);
                } else if (array[7].startsWith("pmid")) {
                    pubMedId = array[7].substring(4);
                } else {
                    throw new IllegalArgumentException("pubMedId does not start with \"pmid:\""
                        + " or \"pmid\": " + array[7]);
                }
                newAnnotation(gene,
                        getPhenotype(array[4]),
                        getExperimentalResult(pubMedId).getIdentifier());
            }

            for (Iterator i = genes.values().iterator(); i.hasNext();) {
                writer.store(ItemHelper.convert((Item) i.next()));
            }
            for (Iterator i = annotations.values().iterator(); i.hasNext();) {
                writer.store(ItemHelper.convert((Item) i.next()));
            }
            for (Iterator i = phenotypes.values().iterator(); i.hasNext();) {
                writer.store(ItemHelper.convert((Item) i.next()));
            }
        } finally {
            writer.close();
        }
    }

    private Map annotationEvidence = new HashMap();
    private Map geneAnnotation = new HashMap();
    private Map phenotypeAnnotation = new HashMap();

    /**
     * Creates an Annotation, and puts it into a map for future reference.
     * This map must be written out at the end.
     *
     * @param gene the gene to be attached
     * @param phenotype the phenotype to be attached
     * @param experimentalResultId the identifier of the ExperimentalResult to be put in the
     * collection
     */
    protected void newAnnotation(Item gene, Item phenotype, String experimentalResultId) {
        String annotationKey = gene.getIdentifier() + "-" + phenotype.getIdentifier();
        Item annotation = (Item) annotations.get(annotationKey);
        if (annotation == null) {
            annotation = newItem("Annotation");
            annotation.addReference(new Reference("subject", gene.getIdentifier()));
            annotation.addReference(new Reference("property", phenotype.getIdentifier()));
            ReferenceList experimentalResultCollection = new ReferenceList();
            experimentalResultCollection.setName("evidence");
            annotation.addCollection(experimentalResultCollection);
            annotations.put(annotationKey, annotation);
            annotationEvidence.put(annotationKey, new HashSet());
        }
        Set evidences = (Set) annotationEvidence.get(annotationKey);
        if (!evidences.contains(experimentalResultId)) {
            ReferenceList experimentalResultCollection = annotation.getCollection("evidence");
            experimentalResultCollection.addRefId(experimentalResultId);
            evidences.add(experimentalResultId);
        }
        Set geneAnnotations = (Set) geneAnnotation.get(gene.getIdentifier());
        if (!geneAnnotations.contains(annotationKey)) {
            ReferenceList annotationCollection = gene.getCollection("annotations");
            annotationCollection.addRefId(annotation.getIdentifier());
            geneAnnotations.add(annotationKey);
        }
        Set phenotypeAnnotations = (Set) phenotypeAnnotation.get(phenotype.getIdentifier());
        if (!phenotypeAnnotations.contains(annotationKey)) {
            ReferenceList annotationCollection = phenotype.getCollection("annotations");
            annotationCollection.addRefId(annotation.getIdentifier());
            phenotypeAnnotations.add(annotationKey);
        }
    }

    /**
     * Add a synonym to a gene
     * @param gene a gene Item
     * @param syn the actual synonym for the gene
     * @throws ObjectStoreException if an error occurs when storing the Item
     */
    protected void addSynonym(Item gene, String syn) throws ObjectStoreException {
        ReferenceList refs = gene.getCollection("synonyms");
        if (refs == null) {
            refs = new ReferenceList();
            refs.setName("synonyms");
            gene.addCollection(refs);
        }
        Item synonym = newSynonym(syn, gene);
        if (!refs.getRefIds().contains(synonym.getIdentifier())) {
            refs.addRefId(synonym.getIdentifier());
        }
    }

    /**
     * Convenience method to create a new gene Item
     * @param sequenceName the WormBase sequence name
     * @param commonName the CGC-Approved gene name
     * @param taxonId the Organism taxonId
     * @param synonym1 a synonym, or null
     * @param synonym2 another synonym, or null
     * @return a new gene Item
     * @throws ObjectStoreException if an error occurs when storing the Item
     */
    protected Item newGene(String sequenceName, String commonName, String taxonId,
            String synonym1, String synonym2)  throws ObjectStoreException {
        Item item = (Item) genes.get(sequenceName);
        if (item == null) {
            item = newItem("Gene");
            item.addAttribute(new Attribute("sequenceName", sequenceName));
            if ((commonName != null) && (!"".equals(commonName))) {
                item.addAttribute(new Attribute("commonName", commonName));
            }
            item.addReference(new Reference("organism", getOrganism(taxonId).getIdentifier()));
            if ((synonym1 != null) && (!"".equals(synonym1))) {
                addSynonym(item, synonym1);
            }
            if ((synonym2 != null) && (!"".equals(synonym2))) {
                addSynonym(item, synonym2);
            }
            ReferenceList annotationCollection = new ReferenceList();
            annotationCollection.setName("annotations");
            item.addCollection(annotationCollection);
            geneAnnotation.put(item.getIdentifier(), new HashSet());
            genes.put(sequenceName, item);
        }
        return item;
    }

    /**
     * Convenience method to create and store a new synonym Item
     * @param synonym the actual synonym
     * @param subject the synonym's subject item
     * @return a new synonym Item
     * @throws ObjectStoreException if an error occurs in storing the Utem
     */
    protected Item newSynonym(String synonym, Item subject) throws ObjectStoreException {
        if (synonyms.containsKey(synonym)) {
            return (Item) synonyms.get(synonym);
        }
        Item item = newItem("Synonym");
        item.addAttribute(new Attribute("synonym", synonym));
        item.addReference(new Reference("subject", subject.getIdentifier()));
        item.addReference(new Reference("source", db.getIdentifier()));
        writer.store(ItemHelper.convert(item));
        synonyms.put(synonym, item);
        return item;
    }

    /**
     * Convenience method to create common Items
     * @throws ObjectStoreException if an error occurs in storing
     */
    protected void setupItems() throws ObjectStoreException {
        db = newItem("Database");
        db.addAttribute(new Attribute("title", "WormBase"));
        writer.store(ItemHelper.convert(db));
    }

    private Map experimentalResults = new HashMap();

    /**
     * Sets up a ExperimentalResult, Experiment, and Publication for a pubMedId, and puts them into
     * a Map for future use, and stores them in the default writer.
     *
     * @param pubMedId the id of the publication
     * @return an Item that is the ExperimentalResult
     * @throws ObjectStoreException if an error occurs storing the Items
     */
    protected Item getExperimentalResult(String pubMedId) throws ObjectStoreException {
        Item experimentalResult = (Item) experimentalResults.get(pubMedId);
        if (experimentalResult == null) {
            Item pub = newItem("Publication");
            pub.addAttribute(new Attribute("pubMedId", pubMedId));
            Item experiment = newItem("RNAiExperiment");
            experiment.addReference(new Reference("publication", pub.getIdentifier()));
            experimentalResult = newItem("ExperimentalResult");
            experimentalResult.addReference(new Reference("experiment",
                                                          experiment.getIdentifier()));

            experimentalResults.put(pubMedId, experimentalResult);
            writer.store(ItemHelper.convert(pub));
            writer.store(ItemHelper.convert(experiment));
            writer.store(ItemHelper.convert(experimentalResult));
        }
        return experimentalResult;
    }

    private Map organisms = new HashMap();

    /**
     * Sets up a organism for a taxonId, and puts it into a Map of organisms for future use,
     * and stores it in the default writer.
     *
     * @param taxonId the id of the organism
     * @return an Item that is the organism
     * @throws ObjectStoreException if an error occurs storing the Item
     */
    protected Item getOrganism(String taxonId) throws ObjectStoreException {
        Item organism = (Item) organisms.get(taxonId);
        if (organism == null) {
            organism = newItem("Organism");
            organism.addAttribute(new Attribute("taxonId", taxonId));

            organisms.put(taxonId, organism);
            writer.store(ItemHelper.convert(organism));
        }
        return organism;
    }

    /**
     * Sets up a phenotype for a code, and puts it into a Map for future use.
     * They must be stored afterwards.
     *
     * @param code the code
     * @return an Item that is the phenotype
     */
    protected Item getPhenotype(String code) {
        if ("prz".equals(code) || "Prz".equals(code)) {
            code = "Prl";
        }
        Item phenotype = (Item) phenotypes.get(code);
        if (phenotype == null) {
            phenotype = newItem("Phenotype");
            phenotype.addAttribute(new Attribute("code", code));
            ReferenceList annotationCollection = new ReferenceList();
            annotationCollection.setName("annotations");
            phenotype.addCollection(annotationCollection);
            phenotypeAnnotation.put(phenotype.getIdentifier(), new HashSet());
            phenotypes.put(code, phenotype);
        }
        return phenotype;
    }

    /**
     * Convenience method for creating a new Item
     * @param className the name of the class
     * @return a new Item
     */
    protected Item newItem(String className) {
        Item item = new Item();
        item.setIdentifier(alias(className) + "_" + (id++));
        item.setClassName(RNAI_NS + className);
        item.setImplementations("");
        return item;
    }
}

