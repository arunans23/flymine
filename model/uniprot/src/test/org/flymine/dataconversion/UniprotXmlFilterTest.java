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

import java.util.Set;
import java.util.HashSet;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.File;
import java.io.InputStreamReader;

import org.custommonkey.xmlunit.XMLTestCase;
import org.custommonkey.xmlunit.XMLUnit;

public class UniprotXmlFilterTest extends XMLTestCase
{


    public void testFilter() throws Exception {
        Set organisms = new HashSet();
        organisms.add("7227");
        UniprotXmlFilter filter = new UniprotXmlFilter(organisms);

        BufferedReader srcReader = new BufferedReader(new InputStreamReader(getClass().getClassLoader()
                                      .getResourceAsStream("test/UniprotXmlFilterTest_src.xml")));

        //File tmpFile = File.createTempFile("uniprot_xml_filter_tmp", ".xml");
        File tmpFile = new File("uniprot_tmp.xml");
        BufferedWriter out = new BufferedWriter(new FileWriter(tmpFile));
        filter.filter(srcReader, out);
        out.flush();
        out.close();

        InputStreamReader expectedReader = new InputStreamReader(getClass().getClassLoader()
                                      .getResourceAsStream("test/UniprotXmlFilterTest_tgt.xml"));
//         BufferedReader t = new BufferedReader(expectedReader);
//         String line = null;
//         while ((line = t.readLine()) != null) {
//             System.out.println(line);
//         }
        XMLUnit.setIgnoreWhitespace(true);
        assertXMLEqual(expectedReader, new FileReader(tmpFile));
    }
}
