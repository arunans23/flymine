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
import java.util.HashMap;
import java.util.Map;

import org.intermine.objectstore.query.*;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.util.DynamicUtil;
import org.intermine.util.TypeUtil;
import org.intermine.objectstore.intermine.ObjectStoreInterMineImpl;

import org.intermine.model.InterMineObject;
import org.intermine.metadata.FieldDescriptor;

import org.flymine.model.genomic.Annotation;
import org.flymine.model.genomic.Location;

/**
 * Common operations for post processing.
 *
 * @author Richard Smith
 */
public class PostProcessUtil
{

    /**
     * Create a clone of given InterMineObject including the id
     * @param obj object to clone
     * @return the cloned object
     * @throws IllegalAccessException if problems with reflection
     */
    public static InterMineObject cloneInterMineObject(InterMineObject obj)
        throws IllegalAccessException {
        InterMineObject newObj = copyInterMineObject(obj);
        newObj.setId(obj.getId());
        return newObj;
    }


    /**
     * Create a copy of given InterMineObject with *no* id set
     * @param obj object to copy
     * @return the copied object
     * @throws IllegalAccessException if problems with reflection
     */
    public static InterMineObject copyInterMineObject(InterMineObject obj)
        throws IllegalAccessException {
        InterMineObject newObj = (InterMineObject)
            DynamicUtil.createObject(DynamicUtil.decomposeClass(obj.getClass()));
        Map fieldInfos = new HashMap();
        Iterator clsIter = DynamicUtil.decomposeClass(obj.getClass()).iterator();
        while (clsIter.hasNext()) {
            fieldInfos.putAll(TypeUtil.getFieldInfos((Class) clsIter.next()));
        }

        Iterator fieldIter = fieldInfos.keySet().iterator();
        while (fieldIter.hasNext()) {
            String fieldName = (String) fieldIter.next();
            if (!fieldName.equals("id")) {
                TypeUtil.setFieldValue(newObj, fieldName,
                                       TypeUtil.getFieldProxy(obj, fieldName));
            }
        }
        return newObj;
    }


    /**
     * Query ObjectStore for all Relation objects (or specified subclass)
     * between given object and a given subject classes.  Return an iterator ordered
     * by objectCls.
     * e.g.  Transcript -> Location -> Exon
     * @param os an ObjectStore to query
     * @param objectCls object type of the Relation
     * @param subjectCls subject type of the Relation
     * @param relationCls type of relation
     * @return an iterator over the results
     * @throws ObjectStoreException if problem reading ObjectStore
     */
    public static Iterator findRelations(ObjectStore os, Class objectCls, Class subjectCls,
                                         Class relationCls) throws ObjectStoreException {
        // TODO check objectCls and subjectCls assignable to BioEntity
        Query q = new Query();
        q.setDistinct(false);
        QueryClass qcObj = new QueryClass(objectCls);
        q.addFrom(qcObj);
        q.addToSelect(qcObj);
        QueryClass qcSub = new QueryClass(subjectCls);
        q.addFrom(qcSub);
        q.addToSelect(qcSub);
        QueryClass qcRel = new QueryClass(relationCls);
        q.addFrom(qcRel);
        q.addToSelect(qcRel);
        q.addToOrderBy(qcObj);
        ConstraintSet cs = new ConstraintSet(ConstraintOp.AND);
        QueryObjectReference ref1 = new QueryObjectReference(qcRel, "object");
        ContainsConstraint cc1 = new ContainsConstraint(ref1, ConstraintOp.CONTAINS, qcObj);
        cs.addConstraint(cc1);
        QueryObjectReference ref2 = new QueryObjectReference(qcRel, "subject");
        ContainsConstraint cc2 = new ContainsConstraint(ref2, ConstraintOp.CONTAINS, qcSub);
        cs.addConstraint(cc2);
        q.setConstraint(cs);

        ((ObjectStoreInterMineImpl) os).precompute(q);
        Results res = new Results(q, os, os.getSequence());
        res.setBatchSize(500);
        return res.iterator();
    }


    /**
     * Query ObjectStore for all Relation objects (or specified subclass)
     * between given object and any subject classes.  Return an iterator ordered
     * by objectCls.
     * e.g. Transcript -> Location
     * @param os an ObjectStore to query
     * @param objectCls object type of the Relation
     * @param relationCls type of Relation
     * @param colName name of collection in objectCls
     * @return an iterator over the results
     * @throws ObjectStoreException if problem reading ObjectStore
     */
    public static Iterator findRelations(ObjectStore os, Class objectCls, Class relationCls,
                                         String colName) throws ObjectStoreException {
        Query q = new Query();
        q.setDistinct(false);
        QueryClass qcObj = new QueryClass(objectCls);
        q.addFrom(qcObj);
        q.addToSelect(qcObj);
        QueryClass qcRel = new QueryClass(relationCls);
        q.addFrom(qcRel);
        q.addToSelect(qcRel);
        q.addToOrderBy(qcObj);
        ConstraintSet cs = new ConstraintSet(ConstraintOp.AND);
        QueryCollectionReference col1 = new QueryCollectionReference(qcObj, colName);
        ContainsConstraint cc1 = new ContainsConstraint(col1, ConstraintOp.CONTAINS, qcRel);
        cs.addConstraint(cc1);
        q.setConstraint(cs);

        ((ObjectStoreInterMineImpl) os).precompute(q);
        Results res = new Results(q, os, os.getSequence());
        res.setBatchSize(500);
        return res.iterator();
    }


    /**
     * Return an iterator over the results of a query that connects two classes by a third using
     * arbitrary fields.
     * @param os an ObjectStore to query
     * @param sourceClass the first class in the query
     * @param sourceClassFieldName the field in the sourceClass which should contain the
     * connectingClass
     * @param connectingClass the class referred to by sourceClass.sourceFieldName
     * @param connectingClassFieldName the field in connectingClass which should contain
     * destinationClass
     * @param destinationClass the class referred to by
     * connectingClass.connectingClassFieldName
     * @param orderBySource if true query will be ordered by sourceClass
     * @return an iterator over the results - (Gene, Exon) pairs
     * @throws ObjectStoreException if problem reading ObjectStore
     * @throws IllegalAccessException if one of the field names doesn't exist in the corresponding
     * class.
     */
    public static Iterator findRelations(ObjectStore os,
                                         Class sourceClass, String sourceClassFieldName,
                                         Class connectingClass, String connectingClassFieldName,
                                         Class destinationClass, boolean orderBySource)
        throws ObjectStoreException, IllegalAccessException {

        Query q = new Query();


        q.setDistinct(true);
        QueryClass qcSource = new QueryClass(sourceClass);
        q.addFrom(qcSource);
        q.addToSelect(qcSource);
        if (orderBySource) {
            q.addToOrderBy(qcSource);
        }
        QueryClass qcConnecting = new QueryClass(connectingClass);
        q.addFrom(qcConnecting);
        QueryClass qcDest = new QueryClass(destinationClass);
        q.addFrom(qcDest);
        q.addToSelect(qcDest);
        if (!orderBySource) {
            q.addToOrderBy(qcDest);
        }
        ConstraintSet cs = new ConstraintSet(ConstraintOp.AND);
        QueryCollectionReference ref1 =
            new QueryCollectionReference(qcSource, sourceClassFieldName);
        ContainsConstraint cc1 = new ContainsConstraint(ref1, ConstraintOp.CONTAINS, qcConnecting);
        cs.addConstraint(cc1);
        QueryReference ref2;

        Map descriptorMap = os.getModel().getFieldDescriptorsForClass(connectingClass);
        FieldDescriptor fd = (FieldDescriptor) descriptorMap.get(connectingClassFieldName);

        if (fd == null) {
            throw new IllegalAccessException("cannot find field \"" + connectingClassFieldName
                                             + "\" in class " + connectingClass.getName());
        }

        if (fd.isReference()) {
            ref2 = new QueryObjectReference(qcConnecting, connectingClassFieldName);
        } else {
            ref2 = new QueryCollectionReference(qcConnecting, connectingClassFieldName);
        }
        ContainsConstraint cc2 = new ContainsConstraint(ref2, ConstraintOp.CONTAINS, qcDest);
        cs.addConstraint(cc2);
        q.setConstraint(cs);

        ((ObjectStoreInterMineImpl) os).precompute(q);
        Results res = new Results(q, os, os.getSequence());
        res.setBatchSize(500);

        return res.iterator();
    }


    /**
     * Query ObjectStore for BioProperty subclasses related to BioEntities by an
     * Annotation object.  Select BioEntity and BioProperty.  Return an iterator
     * ordered by BioEntity
     * @param os an ObjectStore to query
     * @param entityCls type of BioEntity
     * @param propertyCls class of BioProperty to select
     * @return an iterator over the results
     * @throws ObjectStoreException if problem reading ObjectStore
     */
    public static Iterator findProperties(ObjectStore os, Class entityCls, Class propertyCls)
        throws ObjectStoreException {
        Query q = new Query();
        q.setDistinct(false);
        QueryClass qcEntity = new QueryClass(entityCls);
        q.addFrom(qcEntity);
        q.addToSelect(qcEntity);
        QueryClass qcAnn = new QueryClass(Annotation.class);
        q.addFrom(qcAnn);
        QueryClass qcProperty = new QueryClass(propertyCls);
        q.addFrom(qcProperty);
        q.addToSelect(qcProperty);
        q.addToOrderBy(qcEntity);

        ConstraintSet cs = new ConstraintSet(ConstraintOp.AND);
        QueryCollectionReference col1 = new QueryCollectionReference(qcEntity, "annotations");
        ContainsConstraint cc1 = new ContainsConstraint(col1, ConstraintOp.CONTAINS, qcAnn);
        cs.addConstraint(cc1);
        QueryObjectReference ref1 = new QueryObjectReference(qcAnn, "property");
        ContainsConstraint cc2 = new ContainsConstraint(ref1, ConstraintOp.CONTAINS, qcProperty);
        cs.addConstraint(cc2);
        q.setConstraint(cs);

        ((ObjectStoreInterMineImpl) os).precompute(q);
        Results res = new Results(q, os, os.getSequence());
        res.setBatchSize(500);
        return res.iterator();
    }


    /**
     * Return an iterator over all objects of the given class in the ObjectStore provided.
     * @param os an ObjectStore to query
     * @param cls the class to select instances of
     * @return an iterator over the results
     * @throws ObjectStoreException if problem running query
     */
    public static Iterator selectObjectsOfClass(ObjectStore os, Class cls)
        throws ObjectStoreException {
        Query q = new Query();
        q.setDistinct(false);
        QueryClass qc = new QueryClass(cls);
        q.addToSelect(qc);
        q.addFrom(qc);
        SingletonResults res = new SingletonResults(q, os, os.getSequence());
        res.setBatchSize(500);
        return res.iterator();
    }

    /**
     * Query ObjectStore for all Location object between given object and
     * subject classes.  Return an iterator over the results ordered by subject.
     * @param os the ObjectStore to find the Locations in
     * @param objectCls object type of the Location
     * @param subjectCls subject type of the Location
     * @param orderBySubject if true order the results using the subjectCls, otherwise order by
     * objectCls
     * @return an iterator over the results: object.id, location, subject
     * @throws ObjectStoreException if problem reading ObjectStore
     */
    public static Results findLocations(ObjectStore os, Class objectCls, Class subjectCls,
                                        boolean orderBySubject)
        throws ObjectStoreException {
        // TODO check objectCls and subjectCls assignable to BioEntity

        Query q = new Query();
        q.setDistinct(false);
        QueryClass qcObj = new QueryClass(objectCls);
        QueryField qfObj = new QueryField(qcObj, "id");
        q.addFrom(qcObj);
        q.addToSelect(qfObj);
        if (!orderBySubject) {
            q.addToOrderBy(qfObj);
        }
        QueryClass qcSub = new QueryClass(subjectCls);
        q.addFrom(qcSub);
        q.addToSelect(qcSub);
        if (orderBySubject) {
            q.addToOrderBy(qcSub);
        }
        QueryClass qcLoc = new QueryClass(Location.class);
        q.addFrom(qcLoc);
        q.addToSelect(qcLoc);
        ConstraintSet cs = new ConstraintSet(ConstraintOp.AND);
        QueryObjectReference ref1 = new QueryObjectReference(qcLoc, "object");
        ContainsConstraint cc1 = new ContainsConstraint(ref1, ConstraintOp.CONTAINS, qcObj);
        cs.addConstraint(cc1);
        QueryObjectReference ref2 = new QueryObjectReference(qcLoc, "subject");
        ContainsConstraint cc2 = new ContainsConstraint(ref2, ConstraintOp.CONTAINS, qcSub);
        cs.addConstraint(cc2);

        q.setConstraint(cs);
        ((ObjectStoreInterMineImpl) os).precompute(q);
        Results res = new Results(q, os, os.getSequence());

        return res;
    }
}
