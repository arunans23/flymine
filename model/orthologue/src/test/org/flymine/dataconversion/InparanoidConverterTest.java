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

import junit.framework.TestCase;

import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.rdf.model.ModelFactory;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import org.intermine.xml.full.FullParser;
import org.intermine.dataconversion.TargetItemsTestCase;
import org.intermine.dataconversion.MockItemWriter;
import org.intermine.dataconversion.FileConverter;

public class InparanoidConverterTest extends TargetItemsTestCase
{
    private String ENDL = System.getProperty("line.separator");

    public void setUp() throws Exception {
        super.setUp();
    }

    public void testProcess() throws Exception {
        // the input file format is 5 tab-delimited columns (eg. '14 1217 CE 1.000 O01438')
        // the first two are cluster IDs and are ignored
        // the third is the species (orthologues are cross-species, paralogues are within-species)
        // the fourth is a confidence relative to the closest match (defined as the orthologue and given a confidence of 1.000)
        // note that the confidence for the first member of a group appears to be meaningless (row 1).
        // the fifth is some form of swissprot id to identify the protein
        // so...this input should produce one orthologue (rows 1 & 3) and two paralogues (1 & 2, 3 & 4)
        String input = "14\t1217\tCE\t1.000\tO01438" + ENDL
            + "14\t1217\tCE\t0.997\tQ95Q95" + ENDL
            + "14\t1217\tSC\t1.000\tTOR2_YEAST" + ENDL
            + "14\t1217\tSC\t0.566\tTOR1_YEAST" + ENDL;

        MockItemWriter itemWriter = new MockItemWriter(new HashMap());
        FileConverter converter = new InparanoidConverter(itemWriter);
        converter.process(new StringReader(input));
        converter.close();

        assertEquals(new HashSet(getExpectedItems()), itemWriter.getItems());
    }

    protected Collection getExpectedItems() throws Exception {
        return FullParser.parse(getClass().getClassLoader().getResourceAsStream("test/InparanoidConverterTest.xml"));
    }

    protected String getModelName() {
        return "genomic";
    }
}
