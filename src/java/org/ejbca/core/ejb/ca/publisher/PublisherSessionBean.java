/*************************************************************************
 *                                                                       *
 *  EJBCA: The OpenSource Certificate Authority                          *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/

package org.ejbca.core.ejb.ca.publisher;

import java.io.UnsupportedEncodingException;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import javax.ejb.CreateException;
import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.apache.log4j.Logger;
import org.cesecore.audit.enums.EventStatus;
import org.cesecore.audit.log.SecurityEventsLoggerSessionLocal;
import org.cesecore.authentication.tokens.AlwaysAllowLocalAuthenticationToken;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authentication.tokens.UsernamePrincipal;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.authorization.control.AccessControlSessionLocal;
import org.cesecore.certificates.endentity.ExtendedInformation;
import org.cesecore.jndi.JndiConstants;
import org.cesecore.util.Base64GetHashMap;
import org.cesecore.util.CertTools;
import org.ejbca.core.ejb.audit.enums.EjbcaEventTypes;
import org.ejbca.core.ejb.audit.enums.EjbcaModuleTypes;
import org.ejbca.core.ejb.audit.enums.EjbcaServiceTypes;
import org.ejbca.core.model.InternalEjbcaResources;
import org.ejbca.core.model.SecConst;
import org.ejbca.core.model.authorization.AccessRulesConstants;
import org.ejbca.core.model.ca.publisher.ActiveDirectoryPublisher;
import org.ejbca.core.model.ca.publisher.BasePublisher;
import org.ejbca.core.model.ca.publisher.CustomPublisherContainer;
import org.ejbca.core.model.ca.publisher.LdapPublisher;
import org.ejbca.core.model.ca.publisher.LdapSearchPublisher;
import org.ejbca.core.model.ca.publisher.PublisherConnectionException;
import org.ejbca.core.model.ca.publisher.PublisherConst;
import org.ejbca.core.model.ca.publisher.PublisherException;
import org.ejbca.core.model.ca.publisher.PublisherExistsException;
import org.ejbca.core.model.ca.publisher.PublisherQueueVolatileData;
import org.ejbca.core.model.ca.publisher.ValidationAuthorityPublisher;
import org.ejbca.core.model.log.LogConstants;

/**
 * Handles management of Publishers.
 * 
 * @version $Id$
 */
@Stateless(mappedName = JndiConstants.APP_JNDI_PREFIX + "PublisherSessionRemote")
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class PublisherSessionBean implements PublisherSessionLocal, PublisherSessionRemote {

    private static final Logger log = Logger.getLogger(PublisherSessionBean.class);

    /** Internal localization of logs and errors */
    private static final InternalEjbcaResources intres = InternalEjbcaResources.getInstance();

    @PersistenceContext(unitName="ejbca")
    private EntityManager entityManager;

    @EJB
    private AccessControlSessionLocal authorizationSession;
    @EJB
    private PublisherQueueSessionLocal publisherQueueSession;
    @EJB
    private SecurityEventsLoggerSessionLocal auditSession;

    @Override
    public boolean storeCertificate(AuthenticationToken admin, Collection<Integer> publisherids, Certificate incert, String username, String password, String userDN, String cafp,
            int status, int type, long revocationDate, int revocationReason, String tag, int certificateProfileId, long lastUpdate,
            ExtendedInformation extendedinformation) {
    	if (publisherids == null) {
    		return true;
    	}
        String certSerno = CertTools.getSerialNumberAsString(incert);
        Iterator<Integer> iter = publisherids.iterator();
        boolean returnval = true;
        while (iter.hasNext()) {
            int publishStatus = PublisherConst.STATUS_PENDING;
            Integer id = iter.next();
            PublisherData pdl = PublisherData.findById(entityManager, Integer.valueOf(id));
            if (pdl != null) {
                String fingerprint = CertTools.getFingerprintAsString(incert);
                // If it should be published directly
                if (!getPublisher(pdl).getOnlyUseQueue()) {
                    try {
                    	try {
                    		if (publisherQueueSession.storeCertificateNonTransactional(getPublisher(pdl), admin, incert, username, password, userDN, cafp, status, type, revocationDate, revocationReason,
                    				tag, certificateProfileId, lastUpdate, extendedinformation)) {
                    			publishStatus = PublisherConst.STATUS_SUCCESS;
                    		}
                        } catch (EJBException e) {
                        	final Throwable t = e.getCause();
                        	if (t instanceof PublisherException) {
                        		throw (PublisherException)t;
                        	} else {
                        		throw e;
                        	}
                        }
                        String msg = intres.getLocalizedMessage("publisher.store", CertTools.getSubjectDN(incert), pdl.getName());
                        final Map<String, Object> details = new LinkedHashMap<String, Object>();
                        details.put("msg", msg);
                        auditSession.log(EjbcaEventTypes.PUBLISHER_STORE_CERTIFICATE, EventStatus.SUCCESS, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), String.valueOf(LogConstants.INTERNALCAID), username, certSerno, details);
                    } catch (PublisherException pe) {
                        String msg = intres.getLocalizedMessage("publisher.errorstore", pdl.getName(), fingerprint);
                        final Map<String, Object> details = new LinkedHashMap<String, Object>();
                        details.put("msg", msg);
                        details.put("error", pe.getMessage());
                        auditSession.log(EjbcaEventTypes.PUBLISHER_STORE_CERTIFICATE, EventStatus.FAILURE, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), String.valueOf(LogConstants.INTERNALCAID), username, certSerno, details);
                    }
                }
                if (publishStatus != PublisherConst.STATUS_SUCCESS) {
                    returnval = false;
                }
                if (log.isDebugEnabled()) {
                    log.debug("KeepPublishedInQueue: " + getPublisher(pdl).getKeepPublishedInQueue());
                    log.debug("UseQueueForCertificates: " + getPublisher(pdl).getUseQueueForCertificates());
                }
                if ((publishStatus != PublisherConst.STATUS_SUCCESS || getPublisher(pdl).getKeepPublishedInQueue())
                        && getPublisher(pdl).getUseQueueForCertificates()) {
                    // Write to the publisher queue either for audit reasons or
                    // to be able try again
                    PublisherQueueVolatileData pqvd = new PublisherQueueVolatileData();
                    pqvd.setUsername(username);
                    pqvd.setPassword(password);
                    pqvd.setExtendedInformation(extendedinformation);
                    pqvd.setUserDN(userDN);
                    String fp = CertTools.getFingerprintAsString(incert);
                    try {
                        publisherQueueSession.addQueueData(id.intValue(), PublisherConst.PUBLISH_TYPE_CERT, fp, pqvd, publishStatus);
                        String msg = intres.getLocalizedMessage("publisher.storequeue", pdl.getName(), fp, status);
                        log.info(msg);
                    } catch (CreateException e) {
                        String msg = intres.getLocalizedMessage("publisher.errorstorequeue", pdl.getName(), fp, status);
                        log.info(msg, e);
                    }
                }
            } else {
                String msg = intres.getLocalizedMessage("publisher.nopublisher", id);
                log.info(msg);
                returnval = false;
            }
        }
        return returnval;
    }

    /**
     * The same basic method is be used for both store and revoke
     * @return true if publishing was successful for all publishers (or no publishers were given as publisherids), false if not or if was queued for any of the publishers
     */
    @Override
    public void revokeCertificate(AuthenticationToken admin, Collection<Integer> publisherids, Certificate cert, String username, String userDN, String cafp, int type, int reason,
            long revocationDate, String tag, int certificateProfileId, long lastUpdate) {
        storeCertificate(admin, publisherids, cert, username, null, userDN, cafp,
                SecConst.CERT_REVOKED, type, revocationDate, reason, tag, certificateProfileId, lastUpdate, null);
    }

    @Override
    public boolean storeCRL(AuthenticationToken admin, Collection<Integer> publisherids, byte[] incrl, String cafp, int number, String userDN) {
        log.trace(">storeCRL");
        Iterator<Integer> iter = publisherids.iterator();
        boolean returnval = true;
        while (iter.hasNext()) {
            int publishStatus = PublisherConst.STATUS_PENDING;
            Integer id = iter.next();
            PublisherData pdl = PublisherData.findById(entityManager, Integer.valueOf(id));
            if (pdl != null) {
                // If it should be published directly
                if (!getPublisher(pdl).getOnlyUseQueue()) {
                    try {
                    	if (publisherQueueSession.storeCRLNonTransactional(getPublisher(pdl), admin, incrl, cafp, number, userDN)) {
                            publishStatus = PublisherConst.STATUS_SUCCESS;
                        }
                        String msg = intres.getLocalizedMessage("publisher.store", "CRL", pdl.getName());
                        final Map<String, Object> details = new LinkedHashMap<String, Object>();
                        details.put("msg", msg);
                        auditSession.log(EjbcaEventTypes.PUBLISHER_STORE_CRL, EventStatus.SUCCESS, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), String.valueOf(LogConstants.INTERNALCAID), null, null, details);
                    } catch (PublisherException pe) {
                        String msg = intres.getLocalizedMessage("publisher.errorstore", pdl.getName(), "CRL");
                        final Map<String, Object> details = new LinkedHashMap<String, Object>();
                        details.put("msg", msg);
                        details.put("error", pe.getMessage());
                        auditSession.log(EjbcaEventTypes.PUBLISHER_STORE_CRL, EventStatus.FAILURE, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), String.valueOf(LogConstants.INTERNALCAID), null, null, details);
                    }
                }
                if (publishStatus != PublisherConst.STATUS_SUCCESS) {
                    returnval = false;
                }
                if (log.isDebugEnabled()) {
                    log.debug("KeepPublishedInQueue: " + getPublisher(pdl).getKeepPublishedInQueue());
                    log.debug("UseQueueForCRLs: " + getPublisher(pdl).getUseQueueForCRLs());
                }
                if ((publishStatus != PublisherConst.STATUS_SUCCESS || getPublisher(pdl).getKeepPublishedInQueue())
                        && getPublisher(pdl).getUseQueueForCRLs()) {
                    // Write to the publisher queue either for audit reasons or
                    // to be able try again
                    final PublisherQueueVolatileData pqvd = new PublisherQueueVolatileData();
                    pqvd.setUserDN(userDN);
                    String fp = CertTools.getFingerprintAsString(incrl);
                    try {
                        publisherQueueSession.addQueueData(id.intValue(), PublisherConst.PUBLISH_TYPE_CRL, fp, pqvd, PublisherConst.STATUS_PENDING);
                        String msg = intres.getLocalizedMessage("publisher.storequeue", pdl.getName(), fp, "CRL");
                        log.info(msg);
                    } catch (CreateException e) {
                        String msg = intres.getLocalizedMessage("publisher.errorstorequeue", pdl.getName(), fp, "CRL");
                        log.info(msg, e);
                    }
                }
            } else {
                String msg = intres.getLocalizedMessage("publisher.nopublisher", id);
                log.info(msg);
                returnval = false;
            }
        }
        log.trace("<storeCRL");
        return returnval;
    }

    @Override
    public void testConnection(AuthenticationToken admin, int publisherid) throws PublisherConnectionException {
        if (log.isTraceEnabled()) {
            log.trace(">testConnection(id: " + publisherid + ")");
        }
        PublisherData pdl = PublisherData.findById(entityManager, Integer.valueOf(publisherid));
        if (pdl != null) {
            String name = pdl.getName();
            try {
                getPublisher(pdl).testConnection(admin);
                String msg = intres.getLocalizedMessage("publisher.testedpublisher", name);
                log.info(msg);
            } catch (PublisherConnectionException pe) {
                String msg = intres.getLocalizedMessage("publisher.errortestpublisher", name);
                log.info(msg);
                throw new PublisherConnectionException(pe.getMessage());
            }
        } else {
            String msg = intres.getLocalizedMessage("publisher.nopublisher", Integer.valueOf(publisherid));
            log.info(msg);
        }
        if (log.isTraceEnabled()) {
            log.trace("<testConnection(id: " + publisherid + ")");
        }
    }

    @Override
    public void addPublisher(AuthenticationToken admin, String name, BasePublisher publisher) throws PublisherExistsException {
        if (log.isTraceEnabled()) {
            log.trace(">addPublisher(name: " + name + ")");
        }
        addPublisher(admin, findFreePublisherId().intValue(), name, publisher);
        log.trace("<addPublisher()");
    }

    @Override
    public void addPublisher(AuthenticationToken admin, int id, String name, BasePublisher publisher) throws PublisherExistsException {
        if (log.isTraceEnabled()) {
            log.trace(">addPublisher(name: " + name + ", id: " + id + ")");
        }
        boolean success = false;
        if (PublisherData.findByName(entityManager, name) == null) {
            if (PublisherData.findById(entityManager, Integer.valueOf(id)) == null) {
                try {
                	entityManager.persist(new PublisherData(Integer.valueOf(id), name, publisher));
                    success = true;
                } catch (Exception e) {
                    String msg = intres.getLocalizedMessage("publisher.erroraddpublisher", name);
                    log.error(msg, e);
                }
            }
        }
        if (success) {
            String msg = intres.getLocalizedMessage("publisher.addedpublisher", name);
            final Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("msg", msg);
            auditSession.log(EjbcaEventTypes.PUBLISHER_CREATION, EventStatus.SUCCESS, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
        } else {
            String msg = intres.getLocalizedMessage("publisher.erroraddpublisher", name);
            final Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("msg", msg);
            auditSession.log(EjbcaEventTypes.PUBLISHER_CREATION, EventStatus.FAILURE, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
            throw new PublisherExistsException();
        }
        log.trace("<addPublisher()");
    }

    @Override
    public void changePublisher(AuthenticationToken admin, String name, BasePublisher publisher) {
        if (log.isTraceEnabled()) {
            log.trace(">changePublisher(name: " + name + ")");
        }
        PublisherData htp = PublisherData.findByName(entityManager, name);
        if (htp != null) {
            htp.setPublisher(publisher);
            String msg = intres.getLocalizedMessage("publisher.changedpublisher", name);
            final Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("msg", msg);
            auditSession.log(EjbcaEventTypes.PUBLISHER_CHANGE, EventStatus.SUCCESS, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
        } else {
            String msg = intres.getLocalizedMessage("publisher.errorchangepublisher", name);
            final Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("msg", msg);
            auditSession.log(EjbcaEventTypes.PUBLISHER_CHANGE, EventStatus.FAILURE, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
        }
        log.trace("<changePublisher()");
    }

    @Override
    public void clonePublisher(AuthenticationToken admin, String oldname, String newname) {
        if (log.isTraceEnabled()) {
            log.trace(">clonePublisher(name: " + oldname + ")");
        }
        BasePublisher publisherdata = null;
        try {
        	PublisherData htp = PublisherData.findByName(entityManager, oldname);
        	if (htp == null) {
        		throw new Exception("Could not find publisher " + oldname);
        	}
            publisherdata = (BasePublisher) getPublisher(htp).clone();
            try {
                addPublisher(admin, newname, publisherdata);
                String msg = intres.getLocalizedMessage("publisher.clonedpublisher", newname, oldname);
                final Map<String, Object> details = new LinkedHashMap<String, Object>();
                details.put("msg", msg);
                auditSession.log(EjbcaEventTypes.PUBLISHER_CLONE, EventStatus.SUCCESS, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
            } catch (PublisherExistsException f) {
                String msg = intres.getLocalizedMessage("publisher.errorclonepublisher", newname, oldname);
                final Map<String, Object> details = new LinkedHashMap<String, Object>();
                details.put("msg", msg);
                auditSession.log(EjbcaEventTypes.PUBLISHER_CLONE, EventStatus.FAILURE, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
                throw f;
            }
        } catch (Exception e) {
            String msg = intres.getLocalizedMessage("publisher.errorclonepublisher", newname, oldname);
            log.error(msg, e);
            throw new EJBException(e);	// TODO: This might be a bit too much...
        }
        log.trace("<clonePublisher()");
    }

    @Override
    public void removePublisher(AuthenticationToken admin, String name) {
        if (log.isTraceEnabled()) {
            log.trace(">removePublisher(name: " + name + ")");
        }
        try {
            PublisherData htp = PublisherData.findByName(entityManager, name);
            if (htp == null) {
            	if (log.isDebugEnabled()) {
            		log.debug("Trying to remove a publisher that does not exist: "+name);                		
            	}
            } else {
            	entityManager.remove(htp);
                String msg = intres.getLocalizedMessage("publisher.removedpublisher", name);
                final Map<String, Object> details = new LinkedHashMap<String, Object>();
                details.put("msg", msg);
                auditSession.log(EjbcaEventTypes.PUBLISHER_REMOVAL, EventStatus.SUCCESS, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
            }
        } catch (Exception e) {
            String msg = intres.getLocalizedMessage("publisher.errorremovepublisher", name);
            final Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("msg", msg);
            details.put("error", e.getMessage());
            auditSession.log(EjbcaEventTypes.PUBLISHER_REMOVAL, EventStatus.FAILURE, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
        }
        log.trace("<removePublisher()");
    }

    @Override
    public void renamePublisher(AuthenticationToken admin, String oldname, String newname) throws PublisherExistsException {
        if (log.isTraceEnabled()) {
            log.trace(">renamePublisher(from " + oldname + " to " + newname + ")");
        }
        boolean success = false;
        if (PublisherData.findByName(entityManager, newname) == null) {
        	PublisherData htp = PublisherData.findByName(entityManager, oldname);
        	if (htp != null) {
                htp.setName(newname);
                success = true;
            }
        }
        if (success) {
            String msg = intres.getLocalizedMessage("publisher.renamedpublisher", oldname, newname);
            final Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("msg", msg);
            auditSession.log(EjbcaEventTypes.PUBLISHER_RENAME, EventStatus.SUCCESS, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
        } else {
            String msg = intres.getLocalizedMessage("publisher.errorrenamepublisher", oldname, newname);
            final Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("msg", msg);
            auditSession.log(EjbcaEventTypes.PUBLISHER_RENAME, EventStatus.FAILURE, EjbcaModuleTypes.PUBLISHER, EjbcaServiceTypes.EJBCA, admin.toString(), null, null, null, details);            
            throw new PublisherExistsException();
        }
        log.trace("<renamePublisher()");
    }

    @Override
    public Collection<Integer> getAllPublisherIds(AuthenticationToken admin) throws AuthorizationDeniedException {
        HashSet<Integer> returnval = new HashSet<Integer>();
        if(!authorizationSession.isAuthorizedNoLogging(admin, AccessRulesConstants.ROLE_SUPERADMINISTRATOR)) {
            final String msg = intres.getLocalizedMessage("authorization.notuathorizedtoresource", AccessRulesConstants.ROLE_SUPERADMINISTRATOR, null);
	        throw new AuthorizationDeniedException(msg);
        }
        Iterator<PublisherData> i = PublisherData.findAll(entityManager).iterator();
        while (i.hasNext()) {
        	returnval.add(i.next().getId());
        }
        return returnval;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public HashMap<Integer,String> getPublisherIdToNameMap(AuthenticationToken admin) {
        HashMap<Integer,String> returnval = new HashMap<Integer,String>();
        Iterator<PublisherData> i = PublisherData.findAll(entityManager).iterator();
        while (i.hasNext()) {
        	PublisherData next = i.next();
        	returnval.put(next.getId(), next.getName());
        }
        return returnval;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public BasePublisher getPublisher(AuthenticationToken admin, String name) {
        BasePublisher returnval = null;
        PublisherData pd = PublisherData.findByName(entityManager, name);
        if (pd != null) {
        	returnval = getPublisher(pd);
        }
        return returnval;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public BasePublisher getPublisher(AuthenticationToken admin, int id) {
        BasePublisher returnval = null;
        PublisherData pd = PublisherData.findById(entityManager, Integer.valueOf(id));
        if (pd != null) {
        	returnval = getPublisher(pd);
        }
        return returnval;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public int getPublisherUpdateCount(AuthenticationToken admin, int publisherid) {
        int returnval = 0;
        PublisherData pd = PublisherData.findById(entityManager, Integer.valueOf(publisherid));
        if (pd != null) {
        	returnval = pd.getUpdateCounter();
        }
        return returnval;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public int getPublisherId(AuthenticationToken admin, String name) {
        int returnval = 0;
        PublisherData pd = PublisherData.findByName(entityManager, name);
        if (pd != null) {
        	returnval = pd.getId();
        }
        return returnval;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public String getPublisherName(AuthenticationToken admin, int id) {
        if (log.isTraceEnabled()) {
            log.trace(">getPublisherName(id: " + id + ")");
        }
        String returnval = null;
        PublisherData pd = PublisherData.findById(entityManager, Integer.valueOf(id));
        if (pd != null) {
        	returnval = pd.getName();
        }
        log.trace("<getPublisherName()");
        return returnval;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public String testAllConnections() {
        log.trace(">testAllPublishers");
        String returnval = "";
        AuthenticationToken admin = new AlwaysAllowLocalAuthenticationToken(new UsernamePrincipal("PublisherSessionBean.testAllConnections"));
        Iterator<PublisherData> i = PublisherData.findAll(entityManager).iterator();
        while (i.hasNext()) {
        	PublisherData pdl = i.next();
        	String name = pdl.getName();
        	try {
        		getPublisher(pdl).testConnection(admin);
        	} catch (PublisherConnectionException pe) {
        		String msg = intres.getLocalizedMessage("publisher.errortestpublisher", name);
        		log.info(msg);
        		returnval += "\n" + msg;
        	}
        }
        log.trace("<testAllPublishers");
        return returnval;
    }

    private Integer findFreePublisherId() {
    	Random ran = (new Random((new Date()).getTime()));
    	int id = ran.nextInt();
    	boolean foundfree = false;
    	while (!foundfree) {
    		if (id > 1) {
    			PublisherData pd = PublisherData.findById(entityManager, Integer.valueOf(id));
    			if (pd == null) {
    				foundfree = true;
    			}
    		}
    		id = ran.nextInt();
    	}
    	return Integer.valueOf(id);
    }

    /** @return the publisher data and updates it if necessary. */
    private BasePublisher getPublisher(PublisherData pData) {
        BasePublisher publisher = pData.getCachedPublisher();
        if (publisher == null) {
            java.beans.XMLDecoder decoder;
            try {
                decoder = new java.beans.XMLDecoder(new java.io.ByteArrayInputStream(pData.getData().getBytes("UTF8")));
            } catch (UnsupportedEncodingException e) {
                throw new EJBException(e);
            }
            HashMap h = (HashMap) decoder.readObject();
            decoder.close();
            // Handle Base64 encoded string values
            HashMap data = new Base64GetHashMap(h);

            switch (((Integer) (data.get(BasePublisher.TYPE))).intValue()) {
            case PublisherConst.TYPE_LDAPPUBLISHER:
                publisher = new LdapPublisher();
                break;
            case PublisherConst.TYPE_LDAPSEARCHPUBLISHER:
                publisher = new LdapSearchPublisher();
                break;
            case PublisherConst.TYPE_ADPUBLISHER:
                publisher = new ActiveDirectoryPublisher();
                break;
            case PublisherConst.TYPE_CUSTOMPUBLISHERCONTAINER:
                publisher = new CustomPublisherContainer();
                break;
            case PublisherConst.TYPE_VAPUBLISHER:
                publisher = new ValidationAuthorityPublisher();
                break;
            }
            publisher.loadData(data);
        }
        return publisher;
    }
}
