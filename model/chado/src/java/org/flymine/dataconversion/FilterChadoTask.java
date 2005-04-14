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

import org.apache.tools.ant.Task;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;

import java.util.Set;
import java.util.HashSet;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Iterator;

import org.flymine.io.gff3.GFF3Parser;
import org.flymine.io.gff3.GFF3Record;

/**
 * Read Chado GFF3 files are write out only those lines whose types are supported by the FlyMine
 * genomic model.
 *
 * @author Richard Smith
 */
public class FilterChadoTask extends Task
{
    protected FileSet fileSet;
    protected File tgtDir;
    protected Set organisms = new HashSet();

    /**
     * Set the source fileset.
     * @param fileSet the fileset
     */
    public void addFileSet(FileSet fileSet) {
        this.fileSet = fileSet;
    }

    /**
     * Set the target directory
     * @param tgtDir the target directory
     */
    public void setTgtDir(File tgtDir) {
        this.tgtDir = tgtDir;
    }

    /**
     * @see Task#execute
     */
    public void execute() throws BuildException {
        if (fileSet == null) {
            throw new BuildException("fileSet must be specified");
        }
        if (tgtDir == null) {
            throw new BuildException("tgtDir must be specified");
        }

        try {
            DirectoryScanner ds = fileSet.getDirectoryScanner(getProject());
            String[] files = ds.getIncludedFiles();
            for (int i = 0; i < files.length; i++) {
                File toRead = new File(ds.getBasedir(), files[i]);
                System.err .println("Processing file " + toRead.toString());

                String outName = toRead.getName();
                File out = new File(tgtDir, outName);
                BufferedWriter writer = new BufferedWriter(new FileWriter(out));
                FilterChadoTask.filterGFF3(new BufferedReader(new FileReader(toRead)), writer);
                writer.flush();
                writer.close();
            }
        } catch (Exception e) {
            throw new BuildException (e);
        }
    }

    /**
     * Filter specific feature types out of the FlyBase GFF3.
     * @param in input GFF3
     * @param out GFF3 file to write
     * @throws IOException if problems reading/writing
     */
    public static void filterGFF3(BufferedReader in, BufferedWriter out) throws IOException {
        Iterator iter = GFF3Parser.parse(in);
        while (iter.hasNext()) {
            GFF3Record record = (GFF3Record) iter.next();
            if (typeToKeep(record.getType())) {
                out.write(record.toGFF3());
            }
        }
        out.flush();
    }

    private static boolean typeToKeep(String type) {
        if (type.startsWith("match") || type.equals("aberration_junction")
            || type.equals("DNA_motif") || type.equals("rescue_fragment")
            || type.equals("scaffold") || type.equals("chromosome_arm")
            || type.equals("chromosome") || type.equals("mature_peptide")
//            || type.equals("orthologous_region") || type.equals("syntenic_region")
            ) {
            return false;
        }
        return true;
    }


}
