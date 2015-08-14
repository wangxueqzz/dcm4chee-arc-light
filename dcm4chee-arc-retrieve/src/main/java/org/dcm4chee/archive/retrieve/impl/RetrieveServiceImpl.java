/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.archive.retrieve.impl;

import com.mysema.commons.lang.CloseableIterator;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.jpa.hibernate.HibernateQuery;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.IDWithIssuer;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.imageio.codec.Transcoder;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.QueryRetrieveLevel2;
import org.dcm4chee.archive.code.CodeCache;
import org.dcm4chee.archive.conf.QueryRetrieveView;
import org.dcm4chee.archive.entity.*;
import org.dcm4chee.archive.query.util.QueryBuilder;
import org.dcm4chee.archive.retrieve.InstanceLocations;
import org.dcm4chee.archive.retrieve.RetrieveContext;
import org.dcm4chee.archive.retrieve.RetrieveService;
import org.dcm4chee.archive.storage.ReadContext;
import org.dcm4chee.archive.storage.Storage;
import org.dcm4chee.archive.storage.StorageFactory;
import org.hibernate.Session;
import org.hibernate.StatelessSession;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Aug 2015
 */
@ApplicationScoped
public class RetrieveServiceImpl implements RetrieveService {

    private static final Expression<?>[] SELECT = {
            QLocation.location,
            QSeries.series.pk,
            QInstance.instance.pk,
            QInstance.instance.sopClassUID,
            QInstance.instance.sopInstanceUID,
            QueryBuilder.instanceAttributesBlob
    };

    static final Expression<?>[] PATIENT_STUDY_SERIES_ATTRS = {
            QueryBuilder.seriesAttributesBlob.encodedAttributes,
            QueryBuilder.studyAttributesBlob.encodedAttributes,
            QueryBuilder.patientAttributesBlob.encodedAttributes
    };

    @PersistenceContext(unitName = "dcm4chee-arc")
    private EntityManager em;

    @Inject
    private StorageFactory storageFactory;

    @Inject
    private CodeCache codeCache;

    StatelessSession openStatelessSession() {
        return em.unwrap(Session.class).getSessionFactory().openStatelessSession();
    }

    @Override
    public RetrieveContext newRetrieveContextGET(
            Association as, Attributes rqCmd, QueryRetrieveLevel2 qrLevel, Attributes keys) {
        RetrieveContext ctx = newRetrieveContext(as, qrLevel, keys);
        ctx.setPriority(rqCmd.getInt(Tag.Priority, 0));
        ctx.setDestinationAETitle(as.getRemoteAET());
        return ctx;
    }

    @Override
    public RetrieveContext newRetrieveContextMOVE(
            Association as, Attributes rqCmd, QueryRetrieveLevel2 qrLevel, Attributes keys) {
        RetrieveContext ctx = newRetrieveContext(as, qrLevel, keys);
        ctx.setPriority(rqCmd.getInt(Tag.Priority, 0));
        ctx.setDestinationAETitle(rqCmd.getString(Tag.MoveDestination));
        ctx.setMoveOriginatorMessageID(rqCmd.getInt(Tag.MessageID, 0));
        ctx.setMoveOriginatorAETitle(as.getRemoteAET());
        return ctx;
    }

    private RetrieveContext newRetrieveContext(Association as, QueryRetrieveLevel2 qrLevel, Attributes keys) {
        RetrieveContext ctx = new RetrieveContextImpl(this, as.getApplicationEntity());
        IDWithIssuer pid = IDWithIssuer.pidOf(keys);
        if (pid != null)
            ctx.setPatientIDs(pid);
        switch (qrLevel) {
            case IMAGE:
                ctx.setSopInstanceUIDs(keys.getStrings(Tag.SOPInstanceUID));
            case SERIES:
                ctx.setSeriesInstanceUIDs(keys.getStrings(Tag.SeriesInstanceUID));
            case STUDY:
                ctx.setStudyInstanceUIDs(keys.getStrings(Tag.StudyInstanceUID));
        }
        QueryRetrieveView qrView = ctx.getQueryRetrieveView();
        ctx.setHideRejectionNotesWithCode(
                codeCache.findOrCreateEntities(qrView.getHideRejectionNotesWithCodes()));
        ctx.setShowInstancesRejectedByCode(
                codeCache.findOrCreateEntities(qrView.getShowInstancesRejectedByCodes()));
        return ctx;
    }

    @Override
    public boolean calculateMatches(RetrieveContext ctx)  {
        Collection<InstanceLocations> matches = calculateMatchesA(ctx);
        ctx.setMatches(matches);
        return !matches.isEmpty();
    }

    private Collection<InstanceLocations> calculateMatchesA(RetrieveContext ctx) {
        StatelessSession session = openStatelessSession();
        ArrayList<InstanceLocations> matches = new ArrayList<>();
        try (CloseableIterator<Tuple> iter = createQuery(ctx, session).iterate()) {
            HashMap<Long,InstanceLocations> instMap = new HashMap<>();
            HashMap<Long,Attributes> seriesAttrsMap = new HashMap<>();
            while (iter.hasNext()) {
                Tuple next = iter.next();
                Long instPk = next.get(QInstance.instance.pk);
                InstanceLocations match = instMap.get(instPk);
                if (match == null) {
                    Long seriesPk = next.get(QSeries.series.pk);
                    Attributes seriesAttrs = seriesAttrsMap.get(seriesPk);
                    if (seriesAttrs == null) {
                        seriesAttrs = getSeriesAttributes(session, seriesPk);
                        seriesAttrsMap.put(seriesPk, seriesAttrs);
                    }
                    Attributes instAttrs = AttributesBlob.decodeAttributes(
                            next.get(QInstance.instance.attributesBlob.encodedAttributes), null);
                    Attributes.unifyCharacterSets(seriesAttrs, instAttrs);
                    instAttrs.addAll(seriesAttrs);
                    match = new InstanceLocationsImpl(
                            next.get(QInstance.instance.sopClassUID),
                            next.get(QInstance.instance.sopInstanceUID),
                            instAttrs);
                    instMap.put(instPk, match);
                }
                match.getLocations().add(next.get(QLocation.location));
            }
            return matches;
        } finally {
            session.close();
        }
    }

    private Attributes getSeriesAttributes(StatelessSession session, Long seriesPk) {
        Tuple tuple = new HibernateQuery<Void>(session).select(PATIENT_STUDY_SERIES_ATTRS)
                .from(QSeries.series)
                .join(QSeries.series.attributesBlob, QueryBuilder.seriesAttributesBlob)
                .join(QSeries.series.study, QStudy.study)
                .join(QStudy.study.attributesBlob, QueryBuilder.studyAttributesBlob)
                .join(QStudy.study.patient, QPatient.patient)
                .join(QPatient.patient.attributesBlob, QueryBuilder.patientAttributesBlob)
                .where(QSeries.series.pk.eq(seriesPk))
                .fetchOne();
        Attributes patAttrs = AttributesBlob.decodeAttributes(
                tuple.get(QueryBuilder.patientAttributesBlob.encodedAttributes), null);
        Attributes studyAttrs = AttributesBlob.decodeAttributes(
                tuple.get(QueryBuilder.studyAttributesBlob.encodedAttributes), null);
        Attributes seriesAttrs = AttributesBlob.decodeAttributes(
                tuple.get(QueryBuilder.seriesAttributesBlob.encodedAttributes), null);
        Attributes.unifyCharacterSets(patAttrs, studyAttrs, seriesAttrs);
        Attributes attrs = new Attributes(patAttrs.size() + studyAttrs.size() + seriesAttrs.size() + 5);
        attrs.addAll(patAttrs);
        attrs.addAll(studyAttrs);
        attrs.addAll(seriesAttrs);
        return attrs;
    }

    private HibernateQuery<Tuple> createQuery(RetrieveContext ctx, StatelessSession session) {
        HibernateQuery<Tuple> query = new HibernateQuery<Void>(session).select(SELECT)
                .from(QLocation.location)
                .join(QLocation.location.instance, QInstance.instance)
                .join(QInstance.instance.attributesBlob, QueryBuilder.instanceAttributesBlob)
                .join(QInstance.instance.series, QSeries.series)
                .join(QSeries.series.study, QStudy.study);

        IDWithIssuer[] pids = ctx.getPatientIDs();
        if (pids.length > 0) {
            query = query.join(QStudy.study.patient, QPatient.patient);
            query = QueryBuilder.applyPatientIDJoins(query, pids, false);
        }

        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(QueryBuilder.patientIDPredicate(pids, false));
        predicate.and(QueryBuilder.uidsPredicate(QStudy.study.studyInstanceUID, ctx.getStudyInstanceUIDs(), false));
        predicate.and(QueryBuilder.uidsPredicate(QSeries.series.seriesInstanceUID, ctx.getSeriesInstanceUIDs(), false));
        predicate.and(QueryBuilder.uidsPredicate(QInstance.instance.sopInstanceUID, ctx.getSopInstanceUIDs(), false));
        predicate.and(QueryBuilder.hideRejectedInstance(ctx.getHideRejectionNotesWithCode(),
                ctx.isHideNotRejectedInstances()));
        predicate.and(QueryBuilder.hideRejectionNode(ctx.getHideRejectionNotesWithCode()));
        return query.where(predicate);
    }

    @Override
    public Transcoder newTranscoder(RetrieveContext ctx, InstanceLocations inst, Collection<String> tsuids)
            throws IOException {
        LocationInputStream locationInputStream = openLocationInputStream(ctx, inst);
        String tsuid = locationInputStream.getLocation().getTransferSyntaxUID();
        if (!tsuids.contains(tsuid)) {
            tsuid = tsuid.contains(UID.ExplicitVRLittleEndian)
                    ? UID.ExplicitVRLittleEndian
                    : UID.ImplicitVRLittleEndian;
        }
        Transcoder transcoder = new Transcoder(locationInputStream.getInputStream());
        transcoder.setIncludeBulkData(DicomInputStream.IncludeBulkData.URI);
        transcoder.setConcatenateBulkDataFiles(true);
        transcoder.setBulkDataDirectory(ctx.getArchiveAEExtension().getBulkDataSpoolDirectoryFile());
        transcoder.setDestinationTransferSyntax(tsuid);
        return transcoder;
    }

    private LocationInputStream openLocationInputStream(RetrieveContext ctx, InstanceLocations inst)
            throws IOException {
        IOException ex = null;
        for (Location location : inst.getLocations()) {
            try {
                return openLocationInputStream(ctx, location);
            } catch (IOException e) {
                ex = e;
            }
        }
        throw ex;
    }

    private LocationInputStream openLocationInputStream(RetrieveContext ctx, Location location)
            throws IOException {
        Storage storage = getStorage(ctx, location.getStorageID());
        ReadContext readContext = storage.createReadContext();
        readContext.setStoragePath(location.getStoragePath());
        InputStream stream = storage.openInputStream(readContext);
        return new LocationInputStream(stream, readContext, location);
    }

    private Storage getStorage(RetrieveContext ctx, String storageID) {
        Storage storage = ctx.getStorage(storageID);
        if (storage == null) {
            storage = storageFactory.getStorage(ctx.getArchiveAEExtension().getStorageDescriptor());
            ctx.putStorage(storageID, storage);
        }
        return storage;
    }
}