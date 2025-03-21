/*
 * Copyright (c) 2009--2014 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.rhn.domain.token;

import com.redhat.rhn.common.db.datasource.ModeFactory;
import com.redhat.rhn.common.db.datasource.WriteMode;
import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.common.validator.ValidatorException;
import com.redhat.rhn.domain.channel.Channel;
import com.redhat.rhn.domain.kickstart.KickstartData;
import com.redhat.rhn.domain.kickstart.KickstartSession;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.org.OrgFactory;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerConstants;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.domain.server.ServerGroupType;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.frontend.struts.Scrubber;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.type.StandardBasicTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ActivationKeyFactory
 */
public class ActivationKeyFactory extends HibernateFactory {

    public static final String DEFAULT_DESCRIPTION = "None";
    private static ActivationKeyFactory singleton = new ActivationKeyFactory();
    private static Logger log = LogManager.getLogger(ActivationKeyFactory.class);

    /**
     * Lookup an ActivationKey by it's key string.
     * @param key The key for the ActivationKey
     * @return Returns the corresponding ActivationKey or null if not found.
     */
    public static ActivationKey lookupByKey(String key) {
        if (key == null) {
            return null;
        }

        return (ActivationKey) HibernateFactory.getSession()
            .getNamedQuery("ActivationKey.findByKey")
                                      .setParameter("key", key, StandardBasicTypes.STRING)
                                      .uniqueResult();
    }

    /**
     * Lookup the root ActivationKey based on the token.  Looks up by the
     * token and where the KickstartSession is null.
     * @param tokenIn token coming in
     * @return activation key for this token
     */
    public static ActivationKey lookupByToken(Token tokenIn) {
        if (tokenIn == null) {
            return null;
        }
        return (ActivationKey) HibernateFactory.getSession()
            .getNamedQuery("ActivationKey.findByToken")
                                      .setParameter("token", tokenIn)
                                      .uniqueResult();
    }


    /**
     * Creates and fills out a new Activation Key (Including generating a key/token).
     * Sets deployConfigs to false, disabled to 0, and usage limit to null.
     * @param user The user for the key
     * @param note The note to attach to the key
     * @return Returns the newly created ActivationKey.
     */
    public static ActivationKey createNewKey(User user, String note) {
        return createNewKey(user, null, "", note, 0L, null, false);
    }



    /**
     * Creates and fills out a new Activation Key (Including generating a key/token).
     * Sets deployConfigs to false, disabled to 0, and usage limit to null.
     * Sets the 'server' to the server param, and the groups to the
     * system groups the server is subscribed to.
     * @param user The user for the key
     * @param server The server for the key
     * @param key Key to use, blank to have one auto-generated
     * @param note The note to attach to the key
     * @param usageLimit Usage limit for the activation key
     * @param baseChannel Base channel for the activation key
     * @param universalDefault Whether or not this key should be set as the universal
     *        default.
     * @return Returns the newly created ActivationKey.
     */
    public static ActivationKey createNewKey(User user, Server server, String key,
            String note, Long usageLimit, Channel baseChannel, boolean universalDefault) {

        ActivationKey newKey = new ActivationKey();

        String keyToUse = key;
        if (keyToUse == null || keyToUse.equals("")) {
            keyToUse = generateKey();
        }
        else {
            keyToUse = key.trim().replace(" ", "");
        }

        keyToUse = ActivationKey.sanitize(user.getOrg(), keyToUse);
        validateKeyName(keyToUse);

        if (server != null) {
            keyToUse = "re-" + keyToUse;
        }

        newKey.setKey(keyToUse);
        newKey.setCreator(user);
        newKey.setOrg(user.getOrg());
        newKey.setServer(server);

        if (StringUtils.isBlank(note)) {
            note = DEFAULT_DESCRIPTION;
        }
        newKey.setNote((String)Scrubber.scrub(note));
        newKey.getToken().setDeployConfigs(false); // Don't deploy configs by default
        newKey.setDisabled(Boolean.FALSE); // Enable by default
        newKey.setUsageLimit(usageLimit);

        if (baseChannel != null) {
            newKey.getToken().addChannel(baseChannel);
        }

        // Set the entitlements equal to what the server has by default
        // If the server has the bootstrap entitlement use enterprise entitlement
        if (server != null && !server.isBootstrap()) {
            List<ServerGroupType> serverEntitlements = server.getEntitledGroupTypes();
            serverEntitlements.stream().forEach(newKey::addEntitlement);
        }
        else {
            newKey.addEntitlement(
                    ServerConstants.getServerGroupTypeEnterpriseEntitled());
        }

        // Set the default server contact method
        newKey.setContactMethod(ServerFactory.findContactMethodById(0L));

        save(newKey);

        if (universalDefault) {
            Token token = newKey.getToken();
            user.getOrg().setToken(token);
            OrgFactory.save(user.getOrg());
        }

        return newKey;
    }

    /**
     * Basically validates the name of key, makes sure it doesnot have invalid chars etc...
     * Also asserts that the key passed in has not been
     * previously accounted for. This is mainly useful for validating
     * activation key creation. Basically raises an assertion exception
     * on validation errors.
     * @param key the name of the key.
     */
    public static void validateKeyName(String key) {
        String [] badChars = {",", "\""};
        boolean nameOk = true;
        for (String c : badChars) {
            if (key.contains(c)) {
                nameOk = false;
                break;
            }
        }
        if (!nameOk) {
            ValidatorException.raiseException("activation-key.java.invalid_chars", key,
                                        "[" + StringUtils.join(badChars, " ") + "]");
        }
        if (lookupByKey(key) != null) {
            ValidatorException.raiseException("activation-key.java.exists", key);
        }
    }


    /**
     * Generate a random activation key string.
     * @return random string
     */
    public static String generateKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Saves an ActivationKey to the database
     * @param keyIn The ActivationKey to save.
     */
    public static void save(ActivationKey keyIn) {
        singleton.saveObject(keyIn);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    protected Logger getLogger() {
        return log;
    }

    /**
     * Lookup an ActivationKey by its associated KickstartSession.
     *
     * @param sess that is associated with ActivationKey
     * @return ActivationKey associated with session
     */
    public static ActivationKey lookupByKickstartSession(KickstartSession sess) {
        return (ActivationKey) HibernateFactory.getSession()
                                      .getNamedQuery("ActivationKey.findBySession")
                                      .setParameter("session", sess)
                                      //Retrieve from cache if there
                                      .setCacheable(true)
                                      .uniqueResult();
    }

    /**
     * Lookup an ActivationKey by its associated Server.
     *
     * @param server that is associated with ActivationKey
     * @return ActivationKey assocaited with session
     */
    public static List<ActivationKey> lookupByServer(Server server) {
        if (server == null) {
            return null;
        }
        return getSession().getNamedQuery("ActivationKey.findByServer").
            setParameter("server", server).list();
    }

    /**
     * Lookup activation keys for a given activated server.
     *
     * @param server the activated server
     * @return list of keys that were used for activation
     */
    public static List<ActivationKey> lookupByActivatedServer(Server server) {
        return getSession().getNamedQuery("ActivationKey.findByActivatedServer").
                setParameter("server", server).list();
    }

    /**
     * Remove all activation-keys associated with a given server
     *
     * @param sid server-id of the server of interest
     * @return number of rows deleted
     */
    public static int removeKeysForServer(Long sid) {
        WriteMode m = ModeFactory.getWriteMode("System_queries", "remove_activation_keys");
        Map<String, Object> params = new HashMap<>();
        params.put("sid", sid);
        return m.executeUpdate(params);
    }

    /**
     * Remove an ActivationKey
     * @param key to remove
     */
    public static void removeKey(ActivationKey key) {
        if (key != null) {
            WriteMode m = ModeFactory.getWriteMode("System_queries",
                    "remove_activation_key");
            Map<String, Object> params = new HashMap<>();
            params.put("token", key.getKey());
            m.executeUpdate(params);
        }
    }

    /**
     * List all kickstarts associated with an activation key
     * @param key the key to look for associations with
     * @return list of kickstartData objects
     */
    public static List<KickstartData> listAssociatedKickstarts(ActivationKey key) {
        return singleton.listObjectsByNamedQuery("ActivationKey.listAssociatedKickstarts",
                Map.of("token", key.getToken()));
    }

    /**
     * Get the ActivationKey object by its Id
     *
     * @param id the activation key id
     * @param org the org of the activation key
     * @return the ActivationKey object
     */
    public static ActivationKey lookupById(Long id, Org org) {
        return ActivationKeyFactory.lookupByToken(TokenFactory.lookup(id, org));
    }
}
