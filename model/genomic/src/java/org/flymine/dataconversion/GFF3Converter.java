package org.flymine.dataconversion;

/*
 * Copyright (C) 2002-2005 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.BufferedReader;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

import org.intermine.xml.full.Attribute;
import org.intermine.xml.full.Item;
import org.intermine.xml.full.Reference;
import org.intermine.xml.full.ReferenceList;
import org.intermine.xml.full.ItemHelper;
import org.intermine.xml.full.ItemFactory;
import org.intermine.util.TypeUtil;
import org.intermine.dataconversion.ItemWriter;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.metadata.Model;

import org.flymine.io.gff3.GFF3Parser;
import org.flymine.io.gff3.GFF3Record;

import org.apache.log4j.Logger;

/**
 * Class to read a GFF3 source data and produce a data representation
 *
 * @author Wenyan Ji
 * @author Richard Smith
 */

public class GFF3Converter
{
    private static final Logger LOG = Logger.getLogger(GFF3Converter.class);


    private Item organism;
    private Reference orgRef;
    protected ItemWriter writer;
    private String seqClsName;
    private String orgAbbrev;
    private Item infoSource;
    private Model tgtModel;
    private int itemid = 0;
    private Map analyses = new HashMap();
    private Map seqs = new HashMap();
    private Map identifierMap = new HashMap();
    private GFF3RecordHandler handler;
    private ItemFactory itemFactory;

    /**
     * Constructor
     * @param writer ItemWriter
     * @param seqClsName sequenceClassName
     * @param orgAbbrev organismAbbreviation. HS this case
     * @param infoSourceTitle title for infoSource
     * @param tgtModel the model to create items in
     * @param handler object to perform optional additional operations per GFF3 line
     */

    public GFF3Converter(ItemWriter writer, String seqClsName, String orgAbbrev,
                         String infoSourceTitle, Model tgtModel, GFF3RecordHandler handler) {

        this.writer = writer;
        this.seqClsName = seqClsName;
        this.orgAbbrev = orgAbbrev;
        this.tgtModel = tgtModel;
        this.handler = handler;
        this.itemFactory = new ItemFactory(tgtModel, "1_");

        this.organism = getOrganism();
        this.infoSource = createItem("InfoSource");
        infoSource.addAttribute(new Attribute("title", infoSourceTitle));

        handler.setItemFactory(itemFactory);
        handler.setIdentifierMap(identifierMap);
        handler.setInfoSource(infoSource);
        handler.setOrganism(organism);

    }

    /**
     * parse a bufferedReader and process GFF3 record
     * @param bReader BufferedReader
     * @throws java.io.IOException if an error occurs reading GFF
     * @throws ObjectStoreException if an error occurs storing items
     */
    public void parse(BufferedReader bReader) throws java.io.IOException, ObjectStoreException {

        GFF3Record record;
        long start, now, opCount;


        // TODO should probably not store if an empty file
        writer.store(ItemHelper.convert(organism));
        writer.store(ItemHelper.convert(infoSource));

        opCount = 0;
        start = System.currentTimeMillis();
        for (Iterator i = GFF3Parser.parse(bReader); i.hasNext();) {
            record = (GFF3Record) i.next();
            process(record);
            opCount++;
            if (opCount % 10000 == 0) {
                now = System.currentTimeMillis();
                LOG.info("processed " + opCount + " lines --took " + (now - start) + " ms");
                start = System.currentTimeMillis();
            }
        }

        // write ComputationalAnalysis items
        Iterator iter = analyses.values().iterator();
        while (iter.hasNext()) {
            writer.store(ItemHelper.convert((Item) iter.next()));
        }

        // write seq items
        iter = seqs.values().iterator();
        while (iter.hasNext()) {
            writer.store(ItemHelper.convert((Item) iter.next()));
        }
    }

    /**
     * process GFF3 record and give a xml presentation
     * @param record GFF3Record
     * @throws ObjectStoreException if an error occurs storing items
     */
    public void process(GFF3Record record) throws ObjectStoreException {
        // get rid of previous record Items from handler
        handler.clear();
        List names = record.getNames();
        List parents = record.getParents();

        Item seq = getSeq(record.getSequenceID());

        String term = record.getType();
        String className = TypeUtil.javaiseClassName(term);

        Item feature;

        // need to look up item id for this feature as may have already been a parent reference
        if (record.getId() != null) {
            feature = createItem(className, getIdentifier(record.getId()));
            feature.addAttribute(new Attribute("identifier", record.getId()));
        } else {
            feature = createItem(className);
        }

        if (names != null) {
            feature.addAttribute(new Attribute("name", (String) names.get(0)));
            for (Iterator i = names.iterator(); i.hasNext(); ) {
                String recordName = (String) i.next();
                Item synonym = createItem("Synonym");
                synonym.addReference(new Reference("subject", feature.getIdentifier()));
                synonym.addAttribute(new Attribute("value", recordName));
                synonym.addAttribute(new Attribute("type", "name"));
                synonym.addReference(new Reference("source", infoSource.getIdentifier()));
                handler.addItem(synonym);
            }
        }
        feature.addReference(getOrgRef());

        // if parents -> create a SimpleRelation
        if (record.getParents() != null) {
            for (Iterator i = parents.iterator(); i.hasNext();) {
                String parentName = (String) i.next();
                Item simpleRelation = createItem("SimpleRelation");
                simpleRelation.setReference("object", getIdentifier(parentName));
                simpleRelation.setReference("subject", feature.getIdentifier());
                handler.addParentRelation(simpleRelation);
            }
        }


        Item location = createItem("Location");
        location.addAttribute(new Attribute("start", String.valueOf(record.getStart())));
        location.addAttribute(new Attribute("end", String.valueOf(record.getEnd())));
        if (record.getStrand() != null && record.getStrand().equals("+")) {
            location.addAttribute(new Attribute("strand", "1"));
        } else if (record.getStrand() != null && record.getStrand().equals("-")) {
            location.addAttribute(new Attribute("strand", "-1"));
        } else {
            location.addAttribute(new Attribute("strand", "0"));
        }

        if (record.getPhase() != null) {
            location.addAttribute(new Attribute("phase", record.getPhase()));
        }
        location.addReference(new Reference("object", seq.getIdentifier()));
        location.addReference(new Reference("subject", feature.getIdentifier()));
        location.addCollection(new ReferenceList("evidence",
                            Arrays.asList(new Object[] {infoSource.getIdentifier()})));
        handler.setLocation(location);

        ReferenceList evidence = new ReferenceList("evidence");
        evidence.addRefId(infoSource.getIdentifier());

        if (record.getScore() != null) {
            Item computationalResult = createItem("ComputationalResult");
            if (String.valueOf(record.getScore()) != null) {
                computationalResult.addAttribute(new Attribute("type", "score"));
                computationalResult.addAttribute(new Attribute("score",
                                                               String.valueOf(record.getScore())));
            }

            if (record.getSource() != null) {
                Item computationalAnalysis = getComputationalAnalysis(record.getSource());
                computationalResult.addReference(new Reference("analysis",
                                                          computationalAnalysis.getIdentifier()));
                handler.setAnalysis(computationalAnalysis);
            }

            handler.setResult(computationalResult);
            evidence.addRefId(computationalResult.getIdentifier());
        }

        feature.addCollection(evidence);
        handler.setFeature(feature);

        handler.process(record);

        if (feature.hasAttribute("identifier")) {
            Item synonym = createItem("Synonym");
            synonym.addReference(new Reference("subject", feature.getIdentifier()));
            String value = feature.getAttribute("identifier").getValue();
            synonym.addAttribute(new Attribute("value", value));
            synonym.addAttribute(new Attribute("type", "identifier"));
            synonym.addReference(new Reference("source", infoSource.getIdentifier()));
            handler.addItem(synonym);
        }

        try {
            Iterator iter = handler.getItems().iterator();
            while (iter.hasNext()) {
                Item item = (Item) iter.next();
                writer.store(ItemHelper.convert(item));
            }
        } catch (ObjectStoreException e) {
            LOG.error("Problem writing item to the itemwriter");
            throw e;
        }
    }

    private String getIdentifier(String id) {
        String identifier = (String) identifierMap.get(id);
        if (identifier == null) {
            identifier = createIdentifier();
            identifierMap.put(id, identifier);
        }
        return identifier;
    }


    /**
     * Perform any necessary clean-up after post-conversion
     * @throws Exception if an error occurs
     */
    public void close() throws Exception {
    }

    /**
     * @return organism item, for homo_sapiens, abbreviation is HS
     */
    private Item getOrganism() {
        if (organism == null) {
            organism = createItem("Organism");
            organism.addAttribute(new Attribute("abbreviation", orgAbbrev));
        }
        return organism;
    }

    /**
     * @return organism reference
     */
    private Reference getOrgRef() {
        if (orgRef == null) {
            orgRef = new Reference("organism", getOrganism().getIdentifier());
        }
        return orgRef;
    }

    /**
     * @return ComputationalAnalysis item created/from map
     */
    private Item getComputationalAnalysis(String algorithm) {
        Item analysis = (Item) analyses.get(algorithm);
        if (analysis == null) {
            analysis = createItem("ComputationalAnalysis");
            analysis.addAttribute(new Attribute("algorithm", algorithm));
            analyses.put(algorithm, analysis);
        }
        return analysis;
    }

    /**
     * @return return/create item of class seqClsName for given identifier
     */
    private Item getSeq(String identifier) {
        Item seq = (Item) seqs.get(identifier);
        if (seq == null) {
            seq = createItem(seqClsName);
            seq.setAttribute("identifier", identifier);
            seq.addReference(getOrgRef());
            seqs.put(identifier, seq);
            handler.setSequence(seq);
        }
        return seq;
    }

    /**
     * Create an item with given className
     * @param className
     * @return the created item
     */
    private Item createItem(String className) {
        return createItem(className, createIdentifier());
    }

    /**
     * Create an item with given className and item identifier
     * @param className
     * @param implementations
     * @return the created item
     */
    private Item createItem(String className, String identifier) {
        return itemFactory.makeItem(identifier, tgtModel.getNameSpace() + className, "");
    }

    private String createIdentifier() {
        return "0_" + itemid++;
    }
}
