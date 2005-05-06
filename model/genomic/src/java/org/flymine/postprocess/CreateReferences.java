package org.flymine.postprocess;

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
import java.util.ArrayList;

import org.intermine.objectstore.query.*;
import org.intermine.objectstore.ObjectStoreWriter;
import org.intermine.objectstore.ObjectStore;

import org.intermine.sql.Database;

import org.intermine.metadata.Model;
import org.intermine.metadata.ClassDescriptor;
import org.intermine.metadata.MetaDataException;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.intermine.ObjectStoreWriterInterMineImpl;
import org.intermine.objectstore.intermine.ObjectStoreInterMineImpl;
import org.intermine.util.DatabaseUtil;
import org.intermine.util.TypeUtil;
import org.flymine.model.genomic.*;

import org.apache.log4j.Logger;

/**
 * Fill in additional references/collections in genomic model
 *
 * @author Richard Smith
 * @author Kim Rutherford
 */
public class CreateReferences
{
    private static final Logger LOG = Logger.getLogger(CreateReferences.class);

    protected ObjectStoreWriter osw;
    private Model model;

    /**
     * Construct with an ObjectStoreWriter, read and write from same ObjectStore
     * @param osw an ObjectStore to write to
     * @throws MetaDataException if problem getting genomic Model
     */
    public CreateReferences(ObjectStoreWriter osw) throws MetaDataException {
        this.osw = osw;
        this.model = Model.getInstanceByName("genomic");
    }

    /**
     * Fill in missing references/collections in model by querying relations
     * @throws Exception if anything goes wrong
     */
    public void insertReferences() throws Exception {
        LOG.info("insertReferences stage 1");
        // Transcript.exons / Exon.transcripts
        insertReferences(Transcript.class, Exon.class, RankedRelation.class, "exons");
        insertReferences(Transcript.class, Exon.class, SimpleRelation.class, "exons");
        // Intron.MRNAs / MRNA.introns
        insertReferences(MRNA.class, Intron.class, SimpleRelation.class, "introns");
        insertReferences(Transcript.class, Exon.class, SimpleRelation.class, "exons");
        LOG.info("insertReferences stage 2");
        // Gene.transcript / Transcript.gene
        insertReferenceField(Gene.class, "subjects", SimpleRelation.class, "subject",
                             Transcript.class, "gene");
        LOG.info("insertReferences stage 3");
        // Exon.chromosome / Chromosome.exons
        insertReferenceField(Chromosome.class, "subjects", Location.class, "subject",
                             Exon.class, "chromosome");
        LOG.info("insertReferences stage 4");
        // ChromosomeBand.chromosome / Chromosome.bands
        insertReferenceField(Chromosome.class, "subjects", Location.class, "subject",
                             ChromosomeBand.class, "chromosome");
        LOG.info("insertReferences stage 5");
        // Exon.gene / Gene.exons
        insertReferenceField(Gene.class, "transcripts", Transcript.class, "exons",
                             Exon.class, "gene");
        LOG.info("insertReferences stage 6");
        // UTR.gene / Gene.UTRs
        insertReferenceField(Gene.class, "transcripts", MRNA.class, "UTRs",
                             UTR.class, "gene");
        // UTR.chromosome
        insertReferenceField(Chromosome.class, "subjects", Location.class, "subject",
                             UTR.class, "chromosome");
        LOG.info("insertReferences stage 7");
        // Gene.chromosome / Chromosome.genes
        insertReferenceField(Chromosome.class, "exons", Exon.class, "gene",
                             Gene.class, "chromosome");
        LOG.info("insertReferences stage 8");
        // Transcript.chromosome / Chromosome.transcripts
        insertReferenceField(Chromosome.class, "exons", Exon.class, "transcripts",
                             Transcript.class, "chromosome");
        LOG.info("insertReferences stage 9");
        // Protein.genes / Gene.proteins
        insertCollectionField(Gene.class, "transcripts", Transcript.class, "protein",
                              Protein.class, "genes", false);

        // TODO CDS.chromosome - currently missing from model
        //insertReferenceField(Chromosome.class, "subjects", Location.class, "subject",
        //                     CDS.class, "chromosome");
        // CDS.gene / Gene.CDSs
        insertReferenceField(Gene.class, "transcripts", MRNA.class, "CDSs",
                             CDS.class, "gene");


        ObjectStore os = osw.getObjectStore();
        if (os instanceof ObjectStoreInterMineImpl) {
            Database db = ((ObjectStoreInterMineImpl) os).getDatabase();
            DatabaseUtil.analyse(db, false);
        }

        LOG.info("insertReferences stage 10");
        // Protein.interactions
        insertReferences(Protein.class, ProteinInteraction.class, "subjects", "interactions");
        LOG.info("insertReferences stage 11");
        // Protein.interactions
        insertReferences(Protein.class, ProteinInteraction.class, "objects", "interactions");

        if (os instanceof ObjectStoreInterMineImpl) {
            Database db = ((ObjectStoreInterMineImpl) os).getDatabase();
            DatabaseUtil.analyse(db, false);
        }

        LOG.info("insertReferences stage 12");
        insertGeneAnnotationReferences();

        if (os instanceof ObjectStoreInterMineImpl) {
            Database db = ((ObjectStoreInterMineImpl) os).getDatabase();
            DatabaseUtil.analyse(db, false);
        }
        LOG.info("insertReferences stage 13");
        // Gene.GOTerms
        insertReferences(Gene.class, GOTerm.class, "GOTerms");
        LOG.info("insertReferences stage 14");
        // Gene.phenotypes
        insertReferences(Gene.class, Phenotype.class, "phenotypes");

        if (os instanceof ObjectStoreInterMineImpl) {
            Database db = ((ObjectStoreInterMineImpl) os).getDatabase();
            DatabaseUtil.analyse(db, false);
        }
    }


    /**
     * Fill in the "orthologues" collection of Gene.  Needs to be run after
     * UpdateOrthologues which in turn relies on CreateReferences -> so has
     * become a separate method.
     * @throws Exception if anything goes wrong
     */
    public void populateOrthologuesCollection() throws Exception {
        insertReferences(Gene.class, Orthologue.class, "subjects", "orthologues");
    }

    /**
     * Fill in missing references/collectiosn in model by querying relations
     * BioEntity1 -> Relation -> BioEntity2   ==>   BioEntity1 -> BioEntity2
     * @param objectClass the class of the objects to which the collection will be added
     * @param subjectClass the class of the objects added to the collection
     * @param relationClass the class that relates subjectClass and objectClass
     * @param collectionFieldName the field in the object class to which the subjects will be added
     * @throws Exception if anything goes wrong
     */
    protected void insertReferences(Class objectClass, Class subjectClass,
                                    Class relationClass, String collectionFieldName)
        throws Exception {

        LOG.info("Beginning insertReferences(" + objectClass.getName() + ", "
                 + subjectClass.getName() + ", " + relationClass.getName() + ", "
                 + collectionFieldName + ")");

        InterMineObject lastObject = null;
        List newCollection = new ArrayList();
        Iterator resIter = PostProcessUtil.findRelations(osw.getObjectStore(), objectClass,
                                                         subjectClass, relationClass);
        // results will be:  object ; subject ; relation  (ordered by object_
        osw.beginTransaction();

        int count = 0;

        while (resIter.hasNext()) {
            ResultsRow rr = (ResultsRow) resIter.next();
            InterMineObject thisObject = (InterMineObject) rr.get(0);
            InterMineObject thisSubject = (InterMineObject) rr.get(1);

            if (lastObject == null || !thisObject.getId().equals(lastObject.getId())) {
                if (lastObject != null) {
                    // clone so we don't change the ObjectStore cache
                    InterMineObject tempObject = PostProcessUtil.cloneInterMineObject(lastObject);

                    TypeUtil.setFieldValue(tempObject, collectionFieldName, newCollection);
                    count += newCollection.size();
                    osw.store(tempObject);
                }

                newCollection = new ArrayList();
            }

            newCollection.add(thisSubject);

            lastObject = thisObject;
        }

        if (lastObject != null) {
            // clone so we don't change the ObjectStore cache
            InterMineObject tempObject = PostProcessUtil.cloneInterMineObject(lastObject);
            TypeUtil.setFieldValue(tempObject, collectionFieldName, newCollection);
            count += newCollection.size();
            osw.store(tempObject);
        }
        LOG.info("Created " + count + " references in " + objectClass.getName() + " to "
                 + subjectClass.getName() + " via the " + collectionFieldName + " field");
        osw.commitTransaction();

        // now ANALYSE tables relation to class that has been altered - may be rows added
        // to indirection tables
        if (osw instanceof ObjectStoreWriterInterMineImpl) {
            ClassDescriptor cld = model.getClassDescriptorByName(objectClass.getName());
            DatabaseUtil.analyse(((ObjectStoreWriterInterMineImpl) osw).getDatabase(), cld, false);
        }
    }

    /**
     * Add a reference to and object of type X in objects of type Y by using a connecting class.
     * Eg. Add a reference to Gene objects in Exon by examining the Transcript objects in the
     * transcripts collection of the Gene, which would use a query like:
     *   SELECT DISTINCT gene FROM Gene AS gene, Transcript AS transcript, Exon AS exon WHERE
     *   (gene.transcripts CONTAINS transcript AND transcript.exons CONTAINS exon) ORDER BY gene
     * and then set exon.gene
     *
     * in overview we are doing:
     * BioEntity1 -> BioEntity2 -> BioEntity3   ==>   BioEntitiy1 -> BioEntity3
     * @param sourceClass the first class in the query
     * @param sourceClassFieldName the field in the sourceClass which should contain the
     * connectingClass
     * @param connectingClass the class referred to by sourceClass.sourceFieldName
     * @param connectingClassFieldName the field in connectingClass which should contain
     * destinationClass
     * @param destinationClass the class referred to by
     * connectingClass.connectingClassFieldName
     * @param createFieldName the reference field in the destinationClass - the
     * collection to create/set
     * @throws Exception if anything goes wrong
     */
    protected void insertReferenceField(Class sourceClass, String sourceClassFieldName,
                                        Class connectingClass, String connectingClassFieldName,
                                        Class destinationClass, String createFieldName)
        throws Exception {
        InterMineObject lastObject = null;

        LOG.info("Beginning insertReferences("
                 + sourceClass.getName() + ", "
                 + sourceClassFieldName + ", "
                 + connectingClass.getName() + ", "
                 + connectingClassFieldName + ","
                 + destinationClass.getName() + ", "
                 + createFieldName + ")");

        Iterator resIter =
            PostProcessUtil.findRelations(osw.getObjectStore(),
                                          sourceClass, sourceClassFieldName,
                                          connectingClass, connectingClassFieldName,
                                          destinationClass, true);

        // results will be sourceClass ; destClass (ordered by sourceClass)
        osw.beginTransaction();

        int count = 0;

        while (resIter.hasNext()) {
            ResultsRow rr = (ResultsRow) resIter.next();
            InterMineObject thisSourceObject = (InterMineObject) rr.get(0);
            InterMineObject thisDestObject = (InterMineObject) rr.get(1);

            try {
                // clone so we don't change the ObjectStore cache
                InterMineObject tempObject = PostProcessUtil.cloneInterMineObject(thisDestObject);
                TypeUtil.setFieldValue(tempObject, createFieldName, thisSourceObject);
                count++;
                osw.store(tempObject);
            } catch (IllegalAccessException e) {
                LOG.error("Object with ID: " + thisDestObject.getId()
                          + " has no " + createFieldName + " field");
            }
        }

        LOG.info("Created " + count + " references in " + destinationClass.getName() + " to "
                 + sourceClass.getName() + " via " + connectingClass.getName());
        osw.commitTransaction();

        // now ANALYSE tables relation to class that has been altered - may be rows added
        // to indirection tables
        if (osw instanceof ObjectStoreWriterInterMineImpl) {
            ClassDescriptor cld = model.getClassDescriptorByName(destinationClass.getName());
            DatabaseUtil.analyse(((ObjectStoreWriterInterMineImpl) osw).getDatabase(), cld, false);
        }
    }


    /**
     * Add a collection of objects of type X to objects of type Y by using a connecting class.
     * Eg. Add a collection of Protein objects to Gene by examining the Transcript objects in the
     * transcripts collection of the Gene, which would use a query like:
     *   SELECT DISTINCT gene FROM Gene AS gene, Transcript AS transcript, Protein AS protein WHERE
     *   (gene.transcripts CONTAINS transcript AND transcript.protein CONTAINS protein)
     *   ORDER BY gene
     * and then set protected gene.protein (if created
     * BioEntity1 -> BioEntity2 -> BioEntity3   ==>   BioEntity1 -> BioEntity3
     * @param firstClass the first class in the query
     * @param firstClassFieldName the field in the firstClass which should contain the
     * connectingClass
     * @param connectingClass the class referred to by firstClass.sourceFieldName
     * @param connectingClassFieldName the field in connectingClass which should contain
     * secondClass
     * @param secondClass the class referred to by
     * connectingClass.connectingClassFieldName
     * @param createFieldName the collection field in the secondClass - the
     * collection to create/set
     * @param createInFirstClass if true create the new collection field in firstClass, otherwise
     * create in secondClass
     * @throws Exception if anything goes wrong
     */
    protected void insertCollectionField(Class firstClass, String firstClassFieldName,
                                         Class connectingClass, String connectingClassFieldName,
                                         Class secondClass, String createFieldName,
                                         boolean createInFirstClass)
        throws Exception {
        InterMineObject lastDestObject = null;
        List newCollection = new ArrayList();

        LOG.info("Beginning insertReferences("
                 + firstClass.getName() + ", "
                 + firstClassFieldName + ", "
                 + connectingClass.getName() + ", "
                 + connectingClassFieldName + ","
                 + secondClass.getName() + ", "
                 + createFieldName + ", "
                 + createInFirstClass + ")");

        Iterator resIter =
            PostProcessUtil.findRelations(osw.getObjectStore(),
                                          firstClass, firstClassFieldName,
                                          connectingClass, connectingClassFieldName,
                                          secondClass, createInFirstClass);

        // results will be firstClass ; destClass (ordered by firstClass)
        osw.beginTransaction();
        int count = 0;

        while (resIter.hasNext()) {
            ResultsRow rr = (ResultsRow) resIter.next();
            InterMineObject thisSourceObject;
            InterMineObject thisDestObject;

            if (createInFirstClass) {
                thisDestObject = (InterMineObject) rr.get(0);
                thisSourceObject = (InterMineObject) rr.get(1);
            } else {
                thisDestObject = (InterMineObject) rr.get(1);
                thisSourceObject = (InterMineObject) rr.get(0);;
            }

            if (lastDestObject == null
                || !thisDestObject.getId().equals(lastDestObject.getId())) {

                if (lastDestObject != null) {
                    try {
                        InterMineObject tempObject =
                            PostProcessUtil.cloneInterMineObject(lastDestObject);
                        List oldCollection =
                            (List) TypeUtil.getFieldValue(tempObject, createFieldName);
                        newCollection.addAll(oldCollection);
                        TypeUtil.setFieldValue(tempObject, createFieldName, newCollection);
                        count += newCollection.size();
                        osw.store(tempObject);
                   } catch (IllegalAccessException e) {
                        LOG.error("Object with ID: " + thisDestObject.getId()
                                  + " has no " + createFieldName + " field");
                    }
                }

                newCollection = new ArrayList();
            }

            newCollection.add(thisSourceObject);

            lastDestObject = thisDestObject;
        }

        if (lastDestObject != null) {
            // clone so we don't change the ObjectStore cache
            InterMineObject tempObject = PostProcessUtil.cloneInterMineObject(lastDestObject);
            TypeUtil.setFieldValue(tempObject, createFieldName, newCollection);
            count += newCollection.size();
            osw.store(tempObject);
        }

        LOG.info("Created " + count + " references in " + secondClass.getName() + " to "
                 + firstClass.getName() + " via " + connectingClass.getName());
        osw.commitTransaction();

        // now ANALYSE tables relation to class that has been altered - may be rows added
        // to indirection tables
        if (osw instanceof ObjectStoreWriterInterMineImpl) {
            ClassDescriptor cld = model.getClassDescriptorByName(secondClass.getName());
            DatabaseUtil.analyse(((ObjectStoreWriterInterMineImpl) osw).getDatabase(), cld, false);
        }
    }


    /**
     * Create specific named collection of a particular Relation type.
     * For example Gene.subjects contains Orthologues and other Relations, create collection
     * called Gene.orthologues with just Orthologues in (but remain duplicated in Gene.subjects.
     * BioEntity.collection1 -> Relation   ==>   BioEntity.collection2 -> subclass of Relation
     * @param thisClass the class of the objects to fill in a collection for
     * @param collectionClass the type of Relation in the collection
     * @param oldCollectionName the name of the collection to find objects in
     * @param newCollectionName the name of the collection to add objects to
     * @throws Exception if anything goes wrong
     */
    protected void insertReferences(Class thisClass, Class collectionClass,
                                    String oldCollectionName, String newCollectionName)
        throws Exception {

        LOG.info("Beginning insertReferences(" + thisClass.getName() + ", "
                 + collectionClass.getName() + ", " + oldCollectionName + ", "
                 + newCollectionName + ")");

        InterMineObject lastObject = null;
        List newCollection = new ArrayList();
        Iterator resIter = PostProcessUtil.findRelations(osw.getObjectStore(), thisClass,
                                                         collectionClass, oldCollectionName);
        // results will be: thisClass ; collectionClass  (ordered by thisClass)
        osw.beginTransaction();

        int count = 0;

        while (resIter.hasNext()) {
            ResultsRow rr = (ResultsRow) resIter.next();
            InterMineObject thisObject = (InterMineObject) rr.get(0);
            InterMineObject collectionObject = (InterMineObject) rr.get(1);

            if (lastObject == null || !thisObject.getId().equals(lastObject.getId())) {
                if (lastObject != null) {
                    // clone so we don't change the ObjectStore cache
                    InterMineObject tempObject = PostProcessUtil.cloneInterMineObject(lastObject);

                    TypeUtil.setFieldValue(tempObject, newCollectionName, newCollection);
                    count += newCollection.size();
                    osw.store(tempObject);
                }

                newCollection = new ArrayList();
            }

            newCollection.add(collectionObject);

            lastObject = thisObject;
        }

        if (lastObject != null) {
            // clone so we don't change the ObjectStore cache
            InterMineObject tempObject = PostProcessUtil.cloneInterMineObject(lastObject);
            TypeUtil.setFieldValue(tempObject, newCollectionName, newCollection);
            count += newCollection.size();
            osw.store(tempObject);
        }
        LOG.info("Created " + count + " " + newCollectionName + " collections in "
                 + thisClass.getName() + " of " + collectionClass.getName());
        osw.commitTransaction();

        // now ANALYSE tables relation to class that has been altered - may be rows added
        // to indirection tables
        if (osw instanceof ObjectStoreWriterInterMineImpl) {
            ClassDescriptor cld = model.getClassDescriptorByName(thisClass.getName());
            DatabaseUtil.analyse(((ObjectStoreWriterInterMineImpl) osw).getDatabase(), cld, false);
        }
    }


    /**
     * Fill in missing references/collections in model by querying relations
     * BioEntity -> Annotation -> BioProperty   ==>   BioEntity -> BioProperty
     * @param entityClass the class of the objects to which the collection will be added
     * @param propertyClass the class of the BioProperty to transkfer
     * @param newCollectionName the collection in entityClass to add objects to
     * @throws Exception if anything goes wrong
     */
    protected void insertReferences(Class entityClass, Class propertyClass,
                                    String newCollectionName)
        throws Exception {

        LOG.info("Beginning insertReferences(" + entityClass.getName() + ", "
                 + propertyClass.getName() + ", " + newCollectionName + ")");

        InterMineObject lastObject = null;
        List newCollection = new ArrayList();
        Iterator resIter = PostProcessUtil.findProperties(osw.getObjectStore(), entityClass,
                                                         propertyClass);
        // results will be:  entityClass ; properyClass (ordered by entityClass)
        osw.beginTransaction();

        int count = 0;

        while (resIter.hasNext()) {
            ResultsRow rr = (ResultsRow) resIter.next();
            InterMineObject thisObject = (InterMineObject) rr.get(0);
            InterMineObject thisProperty = (InterMineObject) rr.get(1);

            if (lastObject == null || !thisObject.getId().equals(lastObject.getId())) {
                if (lastObject != null) {
                    // clone so we don't change the ObjectStore cache
                    InterMineObject tempObject = PostProcessUtil.cloneInterMineObject(lastObject);

                    TypeUtil.setFieldValue(tempObject, newCollectionName, newCollection);
                    count += newCollection.size();
                    osw.store(tempObject);
                }

                newCollection = new ArrayList();
            }

            newCollection.add(thisProperty);

            lastObject = thisObject;
        }

        if (lastObject != null) {
            // clone so we don't change the ObjectStore cache
            InterMineObject tempObject = PostProcessUtil.cloneInterMineObject(lastObject);
            TypeUtil.setFieldValue(tempObject, newCollectionName, newCollection);
            count += newCollection.size();
            osw.store(tempObject);
        }
        LOG.info("Created " + count + " " + newCollectionName + " collections in "
                 + entityClass.getName() + " of " + propertyClass.getName());
        osw.commitTransaction();

        // now ANALYSE tables relation to class that has been altered - may be rows added
        // to indirection tables
        if (osw instanceof ObjectStoreWriterInterMineImpl) {
            ClassDescriptor cld = model.getClassDescriptorByName(entityClass.getName());
            DatabaseUtil.analyse(((ObjectStoreWriterInterMineImpl) osw).getDatabase(), cld, false);
        }
    }

    /**
     * Copy all GO annotations from the Protein objects to the corresponding Gene(s)
     * @throws Exception if anything goes wrong
     */
    protected void insertGeneAnnotationReferences()
        throws Exception {
        LOG.info("Beginning insertGeneAnnotationReferences()");

        osw.beginTransaction();

        Iterator resIter = findProteinProperties();

        int count = 0;

        while (resIter.hasNext()) {
            ResultsRow rr = (ResultsRow) resIter.next();
            Gene thisGene = (Gene) rr.get(0);
            Annotation thisAnnotation = (Annotation) rr.get(1);

            Annotation tempAnnotation =
                (Annotation) PostProcessUtil.cloneInterMineObject(thisAnnotation);
            // generate a new ID
            tempAnnotation.setId(null);
            tempAnnotation.setSubject(thisGene);
            osw.store(tempAnnotation);

            count++;
        }

        LOG.info("Created " + count + " new Annotations on Genes");
        osw.commitTransaction();

        // now ANALYSE tables relation to class that has been altered - may be rows added
        // to indirection tables
        if (osw instanceof ObjectStoreWriterInterMineImpl) {
            ClassDescriptor cld = model.getClassDescriptorByName(Annotation.class.getName());
            DatabaseUtil.analyse(((ObjectStoreWriterInterMineImpl) osw).getDatabase(), cld, false);
        }
    }

    /**
     * Query Gene->Protein->Annotation->GOTerm and return an iterator over the Gene, Protein and
     * GOTerm.
     */
    private Iterator findProteinProperties() throws Exception {
        Query q = new Query();

        q.setDistinct(false);

        QueryClass qcGene = new QueryClass(Gene.class);
        q.addFrom(qcGene);
        q.addToSelect(qcGene);
        q.addToOrderBy(qcGene);

        QueryClass qcProtein = new QueryClass(Protein.class);
        q.addFrom(qcProtein);

        QueryClass qcAnnotation = new QueryClass(Annotation.class);
        q.addFrom(qcAnnotation);
        q.addToSelect(qcAnnotation);

        QueryClass qcGOTerm = new QueryClass(GOTerm.class);
        q.addFrom(qcGOTerm);

        ConstraintSet cs = new ConstraintSet(ConstraintOp.AND);

        QueryCollectionReference geneProtRef = new QueryCollectionReference(qcProtein, "genes");
        ContainsConstraint geneProtConstraint =
            new ContainsConstraint(geneProtRef, ConstraintOp.CONTAINS, qcGene);
        cs.addConstraint(geneProtConstraint);

        QueryCollectionReference protAnnRef =
            new QueryCollectionReference(qcProtein, "annotations");
        ContainsConstraint protAnnConstraint =
            new ContainsConstraint(protAnnRef, ConstraintOp.CONTAINS, qcAnnotation);
        cs.addConstraint(protAnnConstraint);

        QueryObjectReference annPropertyRef =
            new QueryObjectReference(qcAnnotation, "property");
        ContainsConstraint annPropertyConstraint =
            new ContainsConstraint(annPropertyRef, ConstraintOp.CONTAINS, qcGOTerm);
        cs.addConstraint(annPropertyConstraint);

        q.setConstraint(cs);

        ObjectStore os = osw.getObjectStore();

        ((ObjectStoreInterMineImpl) os).precompute(q);
        Results res = new Results(q, os, os.getSequence());
        res.setBatchSize(500);

        return res.iterator();
    }
}
