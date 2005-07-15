package org.flymine.web;

/*
 * Copyright (C) 2002-2005 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.biojava.bio.Annotation;

import org.biojava.bio.seq.io.FastaFormat;
import org.biojava.bio.seq.io.SeqIOTools;
import org.biojava.bio.symbol.IllegalSymbolException;
import org.biojava.utils.ChangeVetoException;
import org.flymine.biojava.FlyMineSequence;
import org.flymine.biojava.FlyMineSequenceFactory;
import org.flymine.model.genomic.LocatedSequenceFeature;
import org.flymine.model.genomic.Sequence;

import org.intermine.model.InterMineObject;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.query.ConstraintOp;
import org.intermine.objectstore.query.ConstraintSet;
import org.intermine.objectstore.query.ContainsConstraint;
import org.intermine.objectstore.query.Query;
import org.intermine.objectstore.query.QueryClass;
import org.intermine.objectstore.query.QueryField;
import org.intermine.objectstore.query.QueryObjectReference;
import org.intermine.objectstore.query.QueryReference;
import org.intermine.objectstore.query.QueryValue;
import org.intermine.objectstore.query.Results;
import org.intermine.objectstore.query.SimpleConstraint;
import org.intermine.web.ExportException;
import org.intermine.web.FieldExporter;

/**
 * ResidueFieldExporter class
 *
 * @author Kim Rutherford
 */

public class ResidueFieldExporter implements FieldExporter
{

    /**
     * Export a field containing residues in FASTA format.
     * @param object the object of interest
     * @param fieldName the field of the object to output - should be sequence
     * @param os the ObjectStore that contains the object
     * @param response The HTTP response we are creating - used to get the OutputStream to write to
     * @throws ExportException if the application business logic throws an exception
     */
    public void exportField(InterMineObject object, String fieldName, ObjectStore os,
                            HttpServletResponse response)
      throws ExportException {
        if (! (object instanceof Sequence)) {
            throw new IllegalArgumentException("ResidueFieldExporter can only export "
                                               + "Sequence.residues fields");
        }

        response.setContentType("text/plain");
        response.setHeader("Content-Disposition ", "inline; filename=" + fieldName + ".fasta");

        try {
            LocatedSequenceFeature lsf = null;

            if (object instanceof Sequence) {
                lsf = getLocatedSequenceFeatureForSequence(os, (Sequence) object);
            }

            FlyMineSequence flyMineSequence = null;

            if (lsf != null) {
                flyMineSequence = FlyMineSequenceFactory.make(lsf);
            }

            // avoid opening the OutputStream until we have all the data - this avoids some problems
            // that occur when getOutputStream() is called twice (once by this method and again to
            // write the error)
            OutputStream outputStream = response.getOutputStream();

            Annotation annotation = flyMineSequence.getAnnotation();

            if (flyMineSequence == null) {
                annotation.setProperty(FastaFormat.PROPERTY_DESCRIPTIONLINE, "unknown");
            } else {
                annotation.setProperty(FastaFormat.PROPERTY_DESCRIPTIONLINE, lsf.getIdentifier());
            }

            SeqIOTools.writeFasta(outputStream, flyMineSequence);

            outputStream.close();
        } catch (IllegalSymbolException e) {
            throw new ExportException("unexpected error while exporting", e);
        } catch (IllegalArgumentException e) {
            throw new ExportException("unexpected error while exporting", e);
        } catch (ChangeVetoException e) {
            throw new ExportException("unexpected error while exporting", e);
        } catch (IOException e) {
            throw new ExportException("unexpected IO error while exporting", e);
        }
    }

    private LocatedSequenceFeature getLocatedSequenceFeatureForSequence(ObjectStore os,
                                                                        Sequence sequence) {
        Query q = new Query();

        QueryClass lsfQc = new QueryClass(LocatedSequenceFeature.class);
        q.addFrom(lsfQc);
        q.addToSelect(lsfQc);

        QueryClass sequenceQc = new QueryClass(Sequence.class);
        q.addFrom(sequenceQc);
        QueryReference ref = new QueryObjectReference(lsfQc, "sequence");
        ConstraintSet cs = new ConstraintSet(ConstraintOp.AND);
        cs.addConstraint(new ContainsConstraint(ref, ConstraintOp.CONTAINS, sequenceQc));

        QueryField seqIdQf = new QueryField(sequenceQc, "id");
        QueryValue seqIdQv = new QueryValue(sequence.getId());
        cs.addConstraint(new SimpleConstraint(seqIdQf, ConstraintOp.EQUALS, seqIdQv));
        q.setConstraint(cs);

        org.intermine.web.LogMe.log("rfe", "query: " + q);


        Results results = new Results(q, os, os.getSequence());

        if (results.size() == 1) {
            return (LocatedSequenceFeature) ((List) results.get(0)).get(0);
        } else {
            return null;
        }
    }
}

