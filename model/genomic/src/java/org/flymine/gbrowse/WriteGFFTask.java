package org.flymine.gbrowse;

/*
 * Copyright (C) 2002-2005 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.io.File;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.tools.ant.Task;
import org.apache.tools.ant.BuildException;

import org.biojava.bio.program.gff.SimpleGFFRecord;
import org.biojava.bio.symbol.IllegalSymbolException;

import org.intermine.objectstore.query.*;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreFactory;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.util.TypeUtil;
import org.intermine.util.DynamicUtil;
import org.intermine.objectstore.proxy.ProxyCollection;

import org.flymine.postprocess.PostProcessUtil;
import org.flymine.model.genomic.*;

/**
 * A Task for creating GFF and FASTA files for use by GBrowse.  Only those features that are
 * located on a Chromosome are written.
 * @author Kim Rutherford
 */

public class WriteGFFTask extends Task
{
    private String alias;

    private File destinationDirectory;

    /**
     * Set the ObjectStore alias to read from
     * @param alias name of the ObjectStore
     */
    public void setAlias(String alias) {
        this.alias = alias;
    }

    /**
     * Set the name of the directory where the GFF and FASTA files should be created.
     * @param destinationDirectory the directory for creating new files in.
     */
    public void setDest(File destinationDirectory) {
        this.destinationDirectory = destinationDirectory;
    }

    /**
     * @see Task#execute
     */
    public void execute() throws BuildException {
        if (destinationDirectory == null) {
            throw new BuildException("dest attribute is not set");
        }
        if (alias == null) {
            throw new BuildException("alias attribute is not set");
        }

        ObjectStore os = null;
        try {
            os = ObjectStoreFactory.getObjectStore(alias);
            writeGFF(os, destinationDirectory);
        } catch (Exception e) {
            e.printStackTrace(System.out);
            throw new BuildException(e);
        }
    }

    /**
     * Create a GFF and FASTA files for the objects in the given ObjectStore, suitable for reading
     * by GBrowse.
     * @param os the ObjectStore to read from
     * @param destinationDirectory the directory to write the GFF and FASTA files into
     * @throws IllegalSymbolException if any of the residues in a LocatedSequenceFeature can't be
     * turned into DNA symbols.
     * @throws ObjectStoreException if the is a problem with the ObjectStore
     * @throws IOException if there is a problem while writing
     */
    void writeGFF(ObjectStore os, File destinationDirectory)
        throws ObjectStoreException, IOException, IllegalSymbolException {
        Results results =
            PostProcessUtil.findLocations(os, Chromosome.class, BioEntity.class, false);

        results.setBatchSize(2000);

        Iterator resIter = results.iterator();

        PrintWriter gffWriter = null;

        // a Map of object classes to counts
        Map objectCounts = null;

        // Map from Transcript to Location (on Chromosome)
        Map seenTranscripts = new HashMap();
        // Map from exon to Location (on Chromosome)
        Map seenExons = new HashMap();

        // the last Chromosome seen
        Integer currentChrId = null;
        Chromosome currentChr = null;

        while (resIter.hasNext()) {
            ResultsRow rr = (ResultsRow) resIter.next();
            Integer resultChrId = (Integer) rr.get(0);
            BioEntity feature = (BioEntity) rr.get(1);
            Location loc = (Location) rr.get(2);

            if (currentChrId == null || !currentChrId.equals(resultChrId)) {
                if (currentChrId != null) {
                    writeTranscriptsAndExons(os, gffWriter, currentChr, seenTranscripts, seenExons);
                    seenTranscripts = new HashMap();
                    seenExons = new HashMap();
                }

                currentChr = (Chromosome) os.getObjectById(resultChrId);
                writeChromosomeFasta(destinationDirectory, currentChr);

                File gffFile = chromosomeGFFFile(destinationDirectory, currentChr);
                if (gffWriter != null) {
                    gffWriter.close();
                }
                gffWriter = new PrintWriter(new FileWriter(gffFile));

                writeFeature(gffWriter, currentChr, currentChr, null, new Integer(0), null);

                objectCounts = new HashMap();
                currentChrId = resultChrId;

            }

            if (feature instanceof Transcript && !(feature instanceof TRNA)) {
                // process Transcripts but not tRNAs
                seenTranscripts.put(feature, loc);
            }

            if (feature instanceof Exon) {
                seenExons.put(feature, loc);
            }

            writeFeature(gffWriter, currentChr, feature, loc,
                         (Integer) objectCounts.get(feature.getClass()), null);


            incrementCount(objectCounts, feature);
        }

        writeTranscriptsAndExons(os, gffWriter, currentChr, seenTranscripts, seenExons);

        gffWriter.close();
    }

    private void writeTranscriptsAndExons(ObjectStore os, PrintWriter gffWriter, Chromosome chr,
                                          Map seenTranscripts, Map seenExons)
        throws IOException {
        Iterator transcriptIter = seenTranscripts.keySet().iterator();
        while (transcriptIter.hasNext()) {

            Transcript transcript = (Transcript) transcriptIter.next();
            Location transcriptLocation = (Location) seenTranscripts.get(transcript);
            Gene gene = transcript.getGene();

            writeFeature(gffWriter, chr, transcript, transcriptLocation, null, gene);

            List exons = transcript.getExons();

            ProxyCollection exonsResults = (ProxyCollection) exons;

            // exon collections are small enough that optimisation just slows things down
            exonsResults.setNoOptimise();
            exonsResults.setNoExplain();

            Iterator exonIter = exons.iterator();
            while (exonIter.hasNext()) {
                Exon exon = (Exon) exonIter.next();
                Location exonLocation = (Location) seenExons.get(exon);

                writeFeature(gffWriter, chr, exon, exonLocation, null, transcript);
            }
        }
    }

    private void incrementCount(Map objectCounts, Object object) {
        if (objectCounts.containsKey(object.getClass())) {
            int oldCount = ((Integer) objectCounts.get(object.getClass())).intValue();
            objectCounts.put(object.getClass(), new Integer(oldCount + 1));
        } else {
            objectCounts.put(object.getClass(), new Integer(1));
        }
    }

    private static final String FLYMINE_STRING = "flymine";

    private void writeFeature(PrintWriter gffWriter, Chromosome chr,
                              BioEntity bioEntity, Location location, Integer index,
                              BioEntity parent)
        throws IOException {

        if (index == null) {
            index = new Integer(0);
        }

        StringBuffer lineBuffer = new StringBuffer();

        lineBuffer.append(chromosomeFileNamePrefix(chr)).append("\t");
        lineBuffer.append(FLYMINE_STRING).append("\t");

        String featureName = null;

        if (bioEntity instanceof Transcript && !(bioEntity instanceof TRNA) && parent != null) {
            featureName = "mRNA";
        } else {
            if (bioEntity instanceof Exon && parent != null) {
                featureName = "CDS";
            } else {
                Class bioEntityClass = bioEntity.getClass();
                Set classes = DynamicUtil.decomposeClass(bioEntityClass);

                StringBuffer nameBuffer = new StringBuffer();

                Iterator iter = classes.iterator();

                while (iter.hasNext()) {
                    Class thisClass = (Class) iter.next();
                    if (nameBuffer.length() > 0) {
                        nameBuffer.append("_");
                    } else {
                        nameBuffer.append(TypeUtil.unqualifiedName(thisClass.getName()));
                    }
                }

                featureName = nameBuffer.toString();
            }
        }

        if ((bioEntity instanceof Transcript && !(bioEntity instanceof TRNA)
             || bioEntity instanceof Exon) && parent != null) {
            lineBuffer.append(featureName).append("\t");
        } else {
            String lcName = featureName.toLowerCase();
            lineBuffer.append(lcName).append("\t");
        }

        if (location == null && bioEntity == chr) {
            // special case for Chromosome location
            lineBuffer.append(1).append("\t").append(chr.getLength()).append("\t");
        } else {
            lineBuffer.append(location.getStart()).append("\t");
            lineBuffer.append(location.getEnd()).append("\t");
        }

        lineBuffer.append(0).append("\t");
        int strand;

        if (location == null) {
            lineBuffer.append(".");
        } else {
            if (location.getStrand().intValue() == 1) {
                lineBuffer.append("+");
            } else {
                if (location.getStrand().intValue() == -1) {
                    lineBuffer.append("-");
                } else {
                    lineBuffer.append(".");
                }
            }
        }

        lineBuffer.append("\t");

        if (location == null) {
            lineBuffer.append(".");
        } else {
            if (location.getPhase() == null) {
                lineBuffer.append(".");
            } else {
                lineBuffer.append(location.getPhase());
            }
        }

        lineBuffer.append("\t");

        Map attributes = new LinkedHashMap();

        List identifiers = new ArrayList();
        if (location == null && bioEntity == chr) {
            identifiers.add(chromosomeFileNamePrefix(chr));
        } else {
            if (bioEntity.getIdentifier() == null) {
                identifiers.add(featureName + "_" + index);
            } else {
                if (bioEntity instanceof Exon && parent != null) {
                    identifiers.add(parent.getIdentifier());
                } else {
                    identifiers.add(bioEntity.getIdentifier());
                }
            }
        }

        if ((bioEntity instanceof Transcript && !(bioEntity instanceof TRNA)
             || bioEntity instanceof Exon) && parent != null) {
            attributes.put("mRNA", identifiers);
        } else {
            attributes.put(featureName, identifiers);
        }

        ArrayList flyMineIDs = new ArrayList();
        flyMineIDs.add("FlyMineInternalID_" + bioEntity.getId());
        attributes.put("Alias", flyMineIDs);
        attributes.put("FlyMineInternalID", (List) flyMineIDs.clone());

        if (bioEntity instanceof Transcript && !(bioEntity instanceof TRNA) && parent != null) {
            ArrayList geneNameList = new ArrayList();
            geneNameList.add(parent.getIdentifier());
            attributes.put("Gene", geneNameList);
        }

        if (bioEntity instanceof ChromosomeBand) {
            ArrayList indexList = new ArrayList();
            indexList.add(index.toString());
            attributes.put("Index", indexList);
        }

        lineBuffer.append(SimpleGFFRecord.stringifyAttributes(attributes));

        gffWriter.println(lineBuffer.toString());
    }

    private void writeChromosomeFasta(File destinationDirectory, Chromosome chr)
        throws IOException, IllegalArgumentException, IllegalSymbolException {
        FileOutputStream fileStream =
            new FileOutputStream(chromosomeFastaFile(destinationDirectory, chr));

        PrintStream printStream = new PrintStream(fileStream);

        Sequence chromosomeSequence = chr.getSequence();

// Too slow!
//         FlyMineSequence sequence = FlyMineSequenceFactory.make(chr);

//         if (sequence != null) {
//             try {
//                 sequence.getAnnotation().setProperty(FastaFormat.PROPERTY_DESCRIPTIONLINE,
//                                                      chromosomeFileNamePrefix(chr));
//             } catch (ChangeVetoException e) {
//                 throw new RuntimeException("failed to set a property", e);
//             }
//            SeqIOTools.writeFasta(outputStream, sequence);
//         }

        printStream.println(">" + chromosomeFileNamePrefix(chr));

        String residues = chromosomeSequence.getResidues();

        // code from BioJava's FastaFormat class:

        int length = residues.length();

        for (int pos = 0; pos < length; pos += 60) {
            int end = Math.min(pos + 60, length);
            printStream.println(residues.substring(pos, end));
        }

        printStream.close();
        fileStream.close();
    }


    private File chromosomeFastaFile(File destinationDirectory, Chromosome chr) {
        return new File(destinationDirectory, chromosomeFileNamePrefix(chr) + ".fa");
    }

    private File chromosomeGFFFile(File destinationDirectory, Chromosome chr) {
        return new File(destinationDirectory, chromosomeFileNamePrefix(chr) + ".gff");
    }

    private String chromosomeFileNamePrefix(Chromosome chr) {
        return chr.getOrganism().getGenus() + "_" + chr.getOrganism().getSpecies()
            + "_chr_" + chr.getIdentifier();
    }
}
