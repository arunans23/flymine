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

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;

import org.intermine.InterMineException;
import org.intermine.xml.full.Attribute;
import org.intermine.xml.full.Item;
import org.intermine.xml.full.Reference;
import org.intermine.xml.full.ReferenceList;
import org.intermine.xml.full.ItemHelper;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.dataconversion.ItemReader;
import org.intermine.dataconversion.ItemWriter;
import org.intermine.dataconversion.DataTranslator;
import org.intermine.metadata.Model;
import org.intermine.util.XmlUtil;

/**
 * DataTranslator specific to protein structure data
 * @author Mark Woodbridge
 */
public class ProteinStructureDataTranslator extends DataTranslator
{
    protected static final String ENDL = System.getProperty("line.separator");
    protected String dataLocation;
    protected Item db;
    protected Map proteinSequenceFamilies = new HashMap();

    /**
     * @see DataTranslator#DataTranslator
     */
    public ProteinStructureDataTranslator(ItemReader srcItemReader, Properties mapping, Model srcModel,
                                          Model tgtModel, String dataLocation) {
        super(srcItemReader, mapping, srcModel, tgtModel);
        this.dataLocation = dataLocation;
    }

    /**
     * @see DataTranslator#translate
     */
    public void translate(ItemWriter tgtItemWriter)
        throws ObjectStoreException, InterMineException {

        db = createItem(tgtNs + "Database", "");
        db.addAttribute(new Attribute("title", "Pfam"));
        tgtItemWriter.store(ItemHelper.convert(db));

        super.translate(tgtItemWriter);
    }

    /**
     * @see DataTranslator#translateItem
     */
    protected Collection translateItem(Item srcItem)
        throws ObjectStoreException, InterMineException {
        Collection result = new HashSet();
        String className = XmlUtil.getFragmentFromURI(srcItem.getClassName());
        if ("Fragment_Protein_structure".equals(className)) {
            String id = srcItem.getAttribute("id").getValue();

            // modelledRegion -> proteinRegion
            // link proteinRegion to protein using relation
            Item modelledRegion = getReference(srcItem, "modelled_region");
            Item proteinRegion = createItem(tgtNs + "ProteinRegion", "");
            Item protein = createItem(tgtNs + "Protein", "");
            protein.addAttribute(new Attribute("primaryAccession", modelledRegion
                                               .getAttribute("uniprot_id").getValue()));
            proteinRegion.addReference(new Reference("protein", protein.getIdentifier()));
            Item location = createItem(tgtNs + "Location", "");
            location.addAttribute(new Attribute("start", modelledRegion
                                                .getAttribute("begin").getValue()));
            location.addAttribute(new Attribute("end", modelledRegion
                                                .getAttribute("end").getValue()));
            location.addReference(new Reference("object", protein.getIdentifier()));
            location.addReference(new Reference("subject", proteinRegion.getIdentifier()));

            location.addCollection(new ReferenceList("evidence", Arrays.asList(new Object[]
                {db.getIdentifier()})));

            // model -> modelledProteinStructure
            Item model = getReference(srcItem, "model");

            Item modelledProteinStructure = createItem(tgtNs + "ModelledProteinStructure", "");
            modelledProteinStructure.addAttribute(new Attribute("QScore", model
                                                                .getAttribute("prosa_q_score")
                                                                .getValue()));
            modelledProteinStructure.addAttribute(new Attribute("ZScore", model
                                                                .getAttribute("prosa_z_score")
                                                                .getValue()));
            String str;
            StringBuffer atm = new StringBuffer();
            try {
                String filename = dataLocation + id + "/" + id + ".atm";
                if (new File(filename).exists()) {
                    BufferedReader in = new BufferedReader(new FileReader(filename));
                    while ((str = in.readLine()) != null) {
                        atm.append(str + ENDL);
                    }
                    in.close();
                }
            } catch (IOException e) {
                throw new InterMineException(e);
            }
            modelledProteinStructure.addAttribute(new Attribute("atm", atm.toString()));

            // link proteinRegion and modelledProteinStructure using annotation, and add shortcut
            Item annotation = createItem(tgtNs + "Annotation", "");
            annotation.addReference(new Reference("subject", proteinRegion.getIdentifier()));
            annotation.addReference(new Reference("property", modelledProteinStructure
                                                  .getIdentifier()));
            modelledProteinStructure.addReference(new Reference("region",
                                                                proteinRegion.getIdentifier()));

            // sequenceFamily -> proteinSequenceFamily
            Item sequenceFamily = getReference(srcItem, "sequence_family");
            String name = sequenceFamily.getAttribute("pfam_id").getValue();
            Item proteinSequenceFamily = (Item) proteinSequenceFamilies.get(name);
            if (proteinSequenceFamily == null) {
                proteinSequenceFamily = createItem(tgtNs + "ProteinSequenceFamily", "");
                proteinSequenceFamily.addAttribute(new Attribute("name", name));
                proteinSequenceFamilies.put(name, proteinSequenceFamily);
            }

            // link proteinRegion and proteinSequenceFamily using annotation, and add shortcut
            Item annotation2 = createItem(tgtNs + "Annotation", "");
            annotation2.addReference(new Reference("subject", proteinRegion.getIdentifier()));
            annotation2.addReference(new Reference("property", proteinSequenceFamily
                                                  .getIdentifier()));
            annotation2.addCollection(new ReferenceList("evidence", Arrays.asList(new Object[]
                {db.getIdentifier()})));
            proteinRegion.addReference(new Reference("sequenceFamily",
                                                     proteinSequenceFamily.getIdentifier()));

            result.add(proteinRegion);
            result.add(protein);
            result.add(location);
            result.add(modelledProteinStructure);
            result.add(annotation);
            result.add(annotation2);
            result.add(proteinSequenceFamily);
        }

        return result;
    }
}
