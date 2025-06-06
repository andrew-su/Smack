/**
 *
 * Copyright 2003-2007 Jive Software, 2016-2024 Florian Schmaus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.smack.roster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jivesoftware.smack.AsyncButOrdered;
import org.jivesoftware.smack.ConnectionCreationListener;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.Manager;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.FeatureNotSupportedException;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.SmackException.NotLoggedInException;
import org.jivesoftware.smack.SmackFuture;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPConnectionRegistry;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.PresenceTypeFilter;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.filter.StanzaTypeFilter;
import org.jivesoftware.smack.filter.ToMatchesFilter;
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.PresenceBuilder;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.packet.StanzaBuilder;
import org.jivesoftware.smack.packet.StanzaError.Condition;
import org.jivesoftware.smack.roster.SubscribeListener.SubscribeAnswer;
import org.jivesoftware.smack.roster.packet.RosterPacket;
import org.jivesoftware.smack.roster.packet.RosterPacket.Item;
import org.jivesoftware.smack.roster.packet.RosterVer;
import org.jivesoftware.smack.roster.packet.SubscriptionPreApproval;
import org.jivesoftware.smack.roster.rosterstore.RosterStore;
import org.jivesoftware.smack.util.ExceptionCallback;
import org.jivesoftware.smack.util.Objects;
import org.jivesoftware.smack.util.SuccessCallback;

import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.FullJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.util.cache.LruCache;

/**
 * <p>
 * The roster lets you keep track of the availability ("presence") of other
 * users. A roster also allows you to organize users into groups such as
 * "Friends" and "Co-workers". Other IM systems refer to the roster as the buddy
 * list, contact list, etc.
 * </p>
 * <p>
 * You can obtain a Roster instance for your connection via
 * {@link #getInstanceFor(XMPPConnection)}. A detailed description of the
 * protocol behind the Roster and Presence semantics can be found in
 * <a href="https://tools.ietf.org/html/rfc6121">RFC 6120</a>.
 * </p>
 *
 * <h2>Roster Entries</h2>
 * Every user in a roster is represented by a RosterEntry, which consists
 * of:
 * <ul>
 * <li>An XMPP address, aka. JID (e.g. jsmith@example.com).</li>
 * <li>A name you've assigned to the user (e.g. "Joe").</li>
 * <li>The list of groups in the roster that the entry belongs to. If the roster
 * entry belongs to no groups, it's called an "unfiled entry".</li>
 * </ul>
 * The following code snippet prints all entries in the roster:
 *
 * <pre>{@code
 * Roster roster = Roster.getInstanceFor(connection);
 * Collection<RosterEntry> entries = roster.getEntries();
 * for (RosterEntry entry : entries) {
 *     System.out.println(entry);
 * }
 * }</pre>
 *
 * Methods also exist to get individual entries, the list of unfiled entries, or
 * to get one or all roster groups.
 *
 * <h2>Presence</h2>
 * <p>
 * Every entry in the roster has presence associated with it. The
 * {@link #getPresence(BareJid)} method will return a Presence object with the
 * user's presence or `null` if the user is not online or you are not subscribed
 * to the user's presence. _Note:_ Presence subscription is not tied to the
 * user being on the roster, and vice versa: You could be subscribed to a remote
 * users presence without the user in your roster, and a remote user can be in
 * your roster without any presence subscription relation.
 * </p>
 * <p>
 * A user either has a presence of online or offline. When a user is online,
 * their presence may contain extended information such as what they are
 * currently doing, whether they wish to be disturbed, etc. See the Presence
 * class for further details.
 * </p>
 *
 * <h2>Listening for Roster and Presence Changes</h2>
 * <p>
 * The typical use of the roster class is to display a tree view of groups and
 * entries along with the current presence value of each entry. As an example,
 * see the image showing a Roster in the Exodus XMPP client to the right.
 * </p>
 * <p>
 * The presence information will likely change often, and it's also possible for
 * the roster entries to change or be deleted. To listen for changing roster and
 * presence data, a RosterListener should be used. To be informed about all
 * changes to the roster the RosterListener should be registered before logging
 * into the XMPP server. The following code snippet registers a RosterListener
 * with the Roster that prints any presence changes in the roster to standard
 * out. A normal client would use similar code to update the roster UI with the
 * changing information.
 * </p>
 *
 * <pre>{@code
 * Roster roster = Roster.getInstanceFor(con);
 * roster.addRosterListener(new RosterListener() {
 *     // Ignored events public void entriesAdded(Collection<String> addresses) {}
 *     public void entriesDeleted(Collection<String> addresses) {
 *     }
 *
 *     public void entriesUpdated(Collection<String> addresses) {
 *     }
 *
 *     public void presenceChanged(Presence presence) {
 *         System.out.println("Presence changed: " + presence.getFrom() + " " + presence);
 *      }
 * });
 * }</pre>
 *
 * Note that in order to receive presence changed events you need to be
 * subscribed to the users presence. See the following section.
 *
 * <h2>Adding Entries to the Roster</h2>
 *
 * <p>
 * Rosters and presence use a permissions-based model where users must give
 * permission before someone else can see their presence. This protects a user's
 * privacy by making sure that only approved users are able to view their
 * presence information. Therefore, when you add a new roster entry, you will
 * not see the presence information until the other user accepts your request.
 * </p>
 * <p>
 * If another user requests a presence subscription, you must accept or reject
 * that request. Smack handles presence subscription requests in one of three
* ways:
 * </p>
 * <ul>
 * <li>Automatically accept all presence subscription requests
 * ({@link SubscriptionMode#accept_all accept_all})</li>
 * <li>Automatically reject all presence subscription requests
 * ({@link SubscriptionMode#reject_all reject_all})</li>
 * <li>Process presence subscription requests manually.
 * ({@link SubscriptionMode#manual manual})</li>
 * </ul>
 * <p>
 * The mode can be set using {@link #setSubscriptionMode(SubscriptionMode)}.
 * Simple clients normally use one of the automated subscription modes, while
 * full-featured clients should manually process subscription requests and let
 * the end-user accept or reject each request.
 * </p>
 *
 * @author Matt Tucker
 * @see #getInstanceFor(XMPPConnection)
 */
public final class Roster extends Manager {

    private static final Logger LOGGER = Logger.getLogger(Roster.class.getName());

    static {
        XMPPConnectionRegistry.addConnectionCreationListener(new ConnectionCreationListener() {
            @Override
            public void connectionCreated(XMPPConnection connection) {
                getInstanceFor(connection);
            }
        });
    }

    private static final Map<XMPPConnection, Roster> INSTANCES = new WeakHashMap<>();

    /**
     * Returns the roster for the user.
     * <p>
     * This method will never return <code>null</code>, instead if the user has not yet logged into
     * the server all modifying methods of the returned roster object
     * like {@link Roster#createItemAndRequestSubscription(BareJid, String, String[])},
     * {@link Roster#removeEntry(RosterEntry)} , etc. except adding or removing
     * {@link RosterListener}s will throw an IllegalStateException.
     * </p>
     *
     * @param connection the connection the roster should be retrieved for.
     * @return the user's roster.
     */
    public static synchronized Roster getInstanceFor(XMPPConnection connection) {
        Roster roster = INSTANCES.get(connection);
        if (roster == null) {
            roster = new Roster(connection);
            INSTANCES.put(connection, roster);
        }
        return roster;
    }

    private static final StanzaFilter PRESENCE_PACKET_FILTER = StanzaTypeFilter.PRESENCE;

    private static final StanzaFilter OUTGOING_USER_UNAVAILABLE_PRESENCE = new AndFilter(PresenceTypeFilter.UNAVAILABLE, ToMatchesFilter.MATCH_NO_TO_SET);

    private static boolean rosterLoadedAtLoginDefault = true;

    /**
     * The default subscription processing mode to use when a Roster is created. By default,
     * all subscription requests are automatically rejected.
     */
    private static SubscriptionMode defaultSubscriptionMode = SubscriptionMode.reject_all;

    /**
     * The initial maximum size of the map holding presence information of entities without a Roster entry. Currently
     * {@value #INITIAL_DEFAULT_NON_ROSTER_PRESENCE_MAP_SIZE}.
     */
    public static final int INITIAL_DEFAULT_NON_ROSTER_PRESENCE_MAP_SIZE = 1024;

    private static int defaultNonRosterPresenceMapMaxSize = INITIAL_DEFAULT_NON_ROSTER_PRESENCE_MAP_SIZE;

    private RosterStore rosterStore;

    /**
     * The groups of this roster.
     * <p>
     * Note that we use {@link ConcurrentHashMap} also as static type of this field, since we use the fact that the same
     * thread can modify this collection, e.g. remove items, while iterating over it. This is done, for example in
     * {@link #deleteEntry(Collection, RosterEntry)}. If we do not denote the static type to ConcurrentHashMap, but
     * {@link Map} instead, then error prone would report a ModifyCollectionInEnhancedForLoop but.
     * </p>
     */
    private final ConcurrentHashMap<String, RosterGroup> groups = new ConcurrentHashMap<>();

    /**
     * Concurrent hash map from JID to its roster entry.
     */
    private final Map<BareJid, RosterEntry> entries = new ConcurrentHashMap<>();

    private final Set<RosterEntry> unfiledEntries = new CopyOnWriteArraySet<>();
    private final Set<RosterListener> rosterListeners = new LinkedHashSet<>();

    private final Set<PresenceEventListener> presenceEventListeners = new CopyOnWriteArraySet<>();

    /**
     * A map of JIDs to another Map of Resourceparts to Presences. The 'inner' map may contain
     * {@link Resourcepart#EMPTY} if there are no other Presences available.
     */
    private final Map<BareJid, Map<Resourcepart, Presence>> presenceMap = new ConcurrentHashMap<>();

    /**
     * Like {@link #presenceMap} but for presences of entities not in our Roster.
     */
    // TODO Ideally we want here to use a LRU cache like Map which will evict all superfluous items
    // if their maximum size is lowered below the current item count. LruCache does not provide
    // this.
    private final LruCache<BareJid, Map<Resourcepart, Presence>> nonRosterPresenceMap = new LruCache<>(
                    defaultNonRosterPresenceMapMaxSize);

    /**
     * Listeners called when the Roster was loaded.
     */
    private final Set<RosterLoadedListener> rosterLoadedListeners = new LinkedHashSet<>();

    /**
     * Mutually exclude roster listener invocation and changing the {@link #entries} map. Also used
     * to synchronize access to either the roster listeners or the entries map.
     */
    private final Object rosterListenersAndEntriesLock = new Object();

    private enum RosterState {
        uninitialized,
        loading,
        loaded,
    }

    /**
     * The current state of the roster.
     */
    private RosterState rosterState = RosterState.uninitialized;

    private final PresencePacketListener presencePacketListener = new PresencePacketListener();

    /**
     *
     */
    private boolean rosterLoadedAtLogin = rosterLoadedAtLoginDefault;

    private SubscriptionMode subscriptionMode = getDefaultSubscriptionMode();

    private final Set<SubscribeListener> subscribeListeners = new CopyOnWriteArraySet<>();

    private SubscriptionMode previousSubscriptionMode;

    /**
     * Returns the default subscription processing mode to use when a new Roster is created. The
     * subscription processing mode dictates what action Smack will take when subscription
     * requests from other users are made. The default subscription mode
     * is {@link SubscriptionMode#reject_all}.
     *
     * @return the default subscription mode to use for new Rosters
     */
    public static SubscriptionMode getDefaultSubscriptionMode() {
        return defaultSubscriptionMode;
    }

    /**
     * Sets the default subscription processing mode to use when a new Roster is created. The
     * subscription processing mode dictates what action Smack will take when subscription
     * requests from other users are made. The default subscription mode
     * is {@link SubscriptionMode#reject_all}.
     *
     * @param subscriptionMode the default subscription mode to use for new Rosters.
     */
    public static void setDefaultSubscriptionMode(SubscriptionMode subscriptionMode) {
        defaultSubscriptionMode = subscriptionMode;
    }

    private final AsyncButOrdered<BareJid> asyncButOrdered = new AsyncButOrdered<>();

    /**
     * Creates a new roster.
     *
     * @param connection an XMPP connection.
     */
    private Roster(final XMPPConnection connection) {
        super(connection);

        // Note that we use sync packet listeners because RosterListeners should be invoked in the same order as the
        // roster stanzas arrive.
        // Listen for any roster packets.
        connection.registerIQRequestHandler(new RosterPushListener());
        // Listen for any presence packets.
        connection.addSyncStanzaListener(presencePacketListener, PRESENCE_PACKET_FILTER);

        connection.addAsyncStanzaListener(new StanzaListener() {
            @SuppressWarnings("fallthrough")
            @Override
            public void processStanza(Stanza stanza) throws NotConnectedException,
                            InterruptedException, NotLoggedInException {
                Presence presence = (Presence) stanza;
                Jid from = presence.getFrom();
                SubscribeAnswer subscribeAnswer = null;
                switch (subscriptionMode) {
                case manual:
                    for (SubscribeListener subscribeListener : subscribeListeners) {
                        subscribeAnswer = subscribeListener.processSubscribe(from, presence);
                        if (subscribeAnswer != null) {
                            break;
                        }
                    }
                    if (subscribeAnswer == null) {
                        return;
                    }
                    break;
                case accept_all:
                    // Accept all subscription requests.
                    subscribeAnswer = SubscribeAnswer.Approve;
                    break;
                case reject_all:
                    // Reject all subscription requests.
                    subscribeAnswer = SubscribeAnswer.Deny;
                    break;
                }

                if (subscribeAnswer == null) {
                    return;
                }

                Presence.Type type;
                switch (subscribeAnswer) {
                case ApproveAndAlsoRequestIfRequired:
                    BareJid bareFrom = from.asBareJid();
                    RosterUtil.askForSubscriptionIfRequired(Roster.this, bareFrom);
                    // The fall through is intended.
                case Approve:
                    type = Presence.Type.subscribed;
                    break;
                case Deny:
                    type = Presence.Type.unsubscribed;
                    break;
                default:
                    throw new AssertionError();
                }

                Presence response = connection.getStanzaFactory().buildPresenceStanza()
                        .ofType(type)
                        .to(presence.getFrom())
                        .build();
                connection.sendStanza(response);
            }
        }, PresenceTypeFilter.SUBSCRIBE);

        // Listen for connection events
        connection.addConnectionListener(new ConnectionListener() {

            @Override
            public void authenticated(XMPPConnection connection, boolean resumed) {
                if (!isRosterLoadedAtLogin())
                    return;
                // We are done here if the connection was resumed
                if (resumed) {
                    return;
                }

                // Ensure that all available presences received so far in an eventually existing previous session are
                // marked 'offline'.
                setOfflinePresencesAndResetLoaded();

                try {
                    Roster.this.reload();
                }
                catch (InterruptedException | SmackException e) {
                    LOGGER.log(Level.SEVERE, "Could not reload Roster", e);
                    return;
                }
            }

            @Override
            public void connectionClosed() {
                // Changes the presence available contacts to unavailable
                setOfflinePresencesAndResetLoaded();
            }

        });

        connection.addStanzaSendingListener(new StanzaListener() {
            @Override
            public void processStanza(Stanza stanzav) throws NotConnectedException, InterruptedException {
                // Once we send an unavailable presence, the server is allowed to suppress sending presence status
                // information to us as optimization (RFC 6121 § 4.4.2). Thus XMPP clients which are unavailable, should
                // consider the presence information of their contacts as not up-to-date. We make the user obvious of
                // this situation by setting the presences of all contacts to unavailable (while keeping the roster
                // state).
                setOfflinePresences();
            }
        }, OUTGOING_USER_UNAVAILABLE_PRESENCE);

        // If the connection is already established, call reload
        if (connection.isAuthenticated()) {
            try {
                reloadAndWait();
            }
            catch (InterruptedException | SmackException e) {
                LOGGER.log(Level.SEVERE, "Could not reload Roster", e);
            }
        }

    }

    /**
     * Retrieve the user presences (a map from resource to {@link Presence}) for a given XMPP entity represented by their bare JID.
     *
     * @param entity the entity
     * @return the user presences
     */
    private Map<Resourcepart, Presence> getPresencesInternal(BareJid entity) {
        Map<Resourcepart, Presence> entityPresences = presenceMap.get(entity);
        if (entityPresences == null) {
            entityPresences = nonRosterPresenceMap.lookup(entity);
        }
        return entityPresences;
    }

    /**
     * Retrieve the user presences (a map from resource to {@link Presence}) for a given XMPP entity represented by their bare JID.
     *
     * @param entity the entity
     * @return the user presences
     */
    private synchronized Map<Resourcepart, Presence> getOrCreatePresencesInternal(BareJid entity) {
        Map<Resourcepart, Presence> entityPresences = getPresencesInternal(entity);
        if (entityPresences == null) {
            if (contains(entity)) {
                entityPresences = new ConcurrentHashMap<>();
                presenceMap.put(entity, entityPresences);
            }
            else {
                LruCache<Resourcepart, Presence> nonRosterEntityPresences = new LruCache<>(32);
                nonRosterPresenceMap.put(entity, nonRosterEntityPresences);
                entityPresences = nonRosterEntityPresences;
            }
        }
        return entityPresences;
    }

    /**
     * Returns the subscription processing mode, which dictates what action
     * Smack will take when subscription requests from other users are made.
     * The default subscription mode is {@link SubscriptionMode#reject_all}.
     * <p>
     * If using the manual mode, a PacketListener should be registered that
     * listens for Presence packets that have a type of
     * {@link org.jivesoftware.smack.packet.Presence.Type#subscribe}.
     * </p>
     *
     * @return the subscription mode.
     */
    public SubscriptionMode getSubscriptionMode() {
        return subscriptionMode;
    }

    /**
     * Sets the subscription processing mode, which dictates what action
     * Smack will take when subscription requests from other users are made.
     * The default subscription mode is {@link SubscriptionMode#reject_all}.
     * <p>
     * If using the manual mode, a PacketListener should be registered that
     * listens for Presence packets that have a type of
     * {@link org.jivesoftware.smack.packet.Presence.Type#subscribe}.
     * </p>
     *
     * @param subscriptionMode the subscription mode.
     */
    public void setSubscriptionMode(SubscriptionMode subscriptionMode) {
        this.subscriptionMode = subscriptionMode;
    }

    /**
     * Reloads the entire roster from the server. This is an asynchronous operation,
     * which means the method will return immediately, and the roster will be
     * reloaded at a later point when the server responds to the reload request.
     * @throws NotLoggedInException If not logged in.
     * @throws NotConnectedException if the XMPP connection is not connected.
     * @throws InterruptedException if the calling thread was interrupted.
     */
    public void reload() throws NotLoggedInException, NotConnectedException, InterruptedException {
        final XMPPConnection connection = getAuthenticatedConnectionOrThrow();

        RosterPacket packet = new RosterPacket();
        if (rosterStore != null && isRosterVersioningSupported()) {
            packet.setVersion(rosterStore.getRosterVersion());
        }
        rosterState = RosterState.loading;

        SmackFuture<IQ, Exception> future = connection.sendIqRequestAsync(packet);

        future.onSuccess(new RosterResultListener()).onError(new ExceptionCallback<Exception>() {

            @Override
            public void processException(Exception exception) {
                rosterState = RosterState.uninitialized;
                Level logLevel = Level.SEVERE;
                if (exception instanceof NotConnectedException) {
                    logLevel = Level.FINE;
                } else if (exception instanceof XMPPErrorException) {
                    Condition condition = ((XMPPErrorException) exception).getStanzaError().getCondition();
                    if (condition == Condition.feature_not_implemented || condition == Condition.service_unavailable) {
                        logLevel = Level.FINE;
                    }
                }
                LOGGER.log(logLevel, "Exception reloading roster", exception);
                for (RosterLoadedListener listener : rosterLoadedListeners) {
                    listener.onRosterLoadingFailed(exception);
                }
            }

        });
    }

    /**
     * Reload the roster and block until it is reloaded.
     *
     * @throws NotLoggedInException if the XMPP connection is not authenticated.
     * @throws NotConnectedException if the XMPP connection is not connected.
     * @throws InterruptedException if the calling thread was interrupted.
     * @since 4.1
     */
    public void reloadAndWait() throws NotLoggedInException, NotConnectedException, InterruptedException {
        reload();
        waitUntilLoaded();
    }

    /**
     * Set the roster store, may cause a roster reload.
     *
     * @param rosterStore TODO javadoc me please
     * @return true if the roster reload was initiated, false otherwise.
     * @since 4.1
     */
    public boolean setRosterStore(RosterStore rosterStore) {
        this.rosterStore = rosterStore;
        try {
            reload();
        }
        catch (InterruptedException | NotLoggedInException | NotConnectedException e) {
            LOGGER.log(Level.FINER, "Could not reload roster", e);
            return false;
        }
        return true;
    }

    boolean waitUntilLoaded() throws InterruptedException {
        long waitTime = connection().getReplyTimeout();
        long start = System.currentTimeMillis();
        while (!isLoaded()) {
            if (waitTime <= 0) {
                break;
            }
            synchronized (this) {
                if (!isLoaded()) {
                    wait(waitTime);
                }
            }
            long now = System.currentTimeMillis();
            waitTime -= now - start;
            start = now;
        }
        return isLoaded();
    }

    /**
     * Check if the roster is loaded.
     *
     * @return true if the roster is loaded.
     * @since 4.1
     */
    public boolean isLoaded() {
        return rosterState == RosterState.loaded;
    }

    /**
     * Adds a listener to this roster. The listener will be fired anytime one or more
     * changes to the roster are pushed from the server.
     *
     * @param rosterListener a roster listener.
     * @return true if the listener was not already added.
     * @see #getEntriesAndAddListener(RosterListener, RosterEntries)
     */
    public boolean addRosterListener(RosterListener rosterListener) {
        synchronized (rosterListenersAndEntriesLock) {
            return rosterListeners.add(rosterListener);
        }
    }

    /**
     * Removes a listener from this roster. The listener will be fired anytime one or more
     * changes to the roster are pushed from the server.
     *
     * @param rosterListener a roster listener.
     * @return true if the listener was active and got removed.
     */
    public boolean removeRosterListener(RosterListener rosterListener) {
        synchronized (rosterListenersAndEntriesLock) {
            return rosterListeners.remove(rosterListener);
        }
    }

    /**
     * Add a roster loaded listener. Roster loaded listeners are invoked once the {@link Roster}
     * was successfully loaded.
     *
     * @param rosterLoadedListener the listener to add.
     * @return true if the listener was not already added.
     * @see RosterLoadedListener
     * @since 4.1
     */
    public boolean addRosterLoadedListener(RosterLoadedListener rosterLoadedListener) {
        synchronized (rosterLoadedListener) {
            return rosterLoadedListeners.add(rosterLoadedListener);
        }
    }

    /**
     * Remove a roster loaded listener.
     *
     * @param rosterLoadedListener the listener to remove.
     * @return true if the listener was active and got removed.
     * @see RosterLoadedListener
     * @since 4.1
     */
    public boolean removeRosterLoadedListener(RosterLoadedListener rosterLoadedListener) {
        synchronized (rosterLoadedListener) {
            return rosterLoadedListeners.remove(rosterLoadedListener);
        }
    }

    /**
     * Add a {@link PresenceEventListener}. Such a listener will be fired whenever certain
     * presence events happen.<p>
     * Among those events are:
     * <ul>
     * <li> 'available' presence received
     * <li> 'unavailable' presence received
     * <li> 'error' presence received
     * <li> 'subscribed' presence received
     * <li> 'unsubscribed' presence received
     * </ul>
     * @param presenceEventListener listener to add.
     * @return true if the listener was not already added.
     */
    public boolean addPresenceEventListener(PresenceEventListener presenceEventListener) {
        return presenceEventListeners.add(presenceEventListener);
    }

    public boolean removePresenceEventListener(PresenceEventListener presenceEventListener) {
        return presenceEventListeners.remove(presenceEventListener);
    }

    /**
     * Creates a new group.
     * <p>
     * Note: you must add at least one entry to the group for the group to be kept
     * after a logout/login. This is due to the way that XMPP stores group information.
     * </p>
     *
     * @param name the name of the group.
     * @return a new group, or null if the group already exists
     */
    public RosterGroup createGroup(String name) {
        final XMPPConnection connection = connection();
        if (groups.containsKey(name)) {
            return groups.get(name);
        }

        RosterGroup group = new RosterGroup(name, connection);
        groups.put(name, group);
        return group;
    }

    /**
     * Creates a new roster item. The server will asynchronously update the roster with the subscription status.
     * <p>
     * There will be no presence subscription request. Consider using
     * {@link #createItemAndRequestSubscription(BareJid, String, String[])} if you also want to request a presence
     * subscription from the contact.
     * </p>
     *
     * @param jid the XMPP address of the contact (e.g. johndoe@jabber.org)
     * @param name the nickname of the user.
     * @param groups the list of group names the entry will belong to, or <code>null</code> if the roster entry won't
     *        belong to a group.
     * @throws NoResponseException if there was no response from the server.
     * @throws XMPPErrorException if an XMPP exception occurs.
     * @throws NotLoggedInException If not logged in.
     * @throws NotConnectedException if the XMPP connection is not connected.
     * @throws InterruptedException if the calling thread was interrupted.
     * @since 4.4.0
     */
    public void createItem(BareJid jid, String name, String[] groups) throws NotLoggedInException, NoResponseException, XMPPErrorException, NotConnectedException, InterruptedException {
        final XMPPConnection connection = getAuthenticatedConnectionOrThrow();

        // Create and send roster entry creation packet.
        RosterPacket rosterPacket = new RosterPacket();
        rosterPacket.setType(IQ.Type.set);
        RosterPacket.Item item = new RosterPacket.Item(jid, name);
        if (groups != null) {
            for (String group : groups) {
                if (group != null && group.trim().length() > 0) {
                    item.addGroupName(group);
                }
            }
        }
        rosterPacket.addRosterItem(item);
        connection.sendIqRequestAndWaitForResponse(rosterPacket);
    }

    /**
     * Creates a new roster entry and presence subscription. The server will asynchronously
     * update the roster with the subscription status.
     *
     * @param jid the XMPP address of the contact (e.g. johndoe@jabber.org)
     * @param name   the nickname of the user.
     * @param groups the list of group names the entry will belong to, or <code>null</code> if
     *               the roster entry won't belong to a group.
     * @throws NoResponseException if there was no response from the server.
     * @throws XMPPErrorException if an XMPP exception occurs.
     * @throws NotLoggedInException If not logged in.
     * @throws NotConnectedException if the XMPP connection is not connected.
     * @throws InterruptedException if the calling thread was interrupted.
     * @since 4.4.0
     */
    public void createItemAndRequestSubscription(BareJid jid, String name, String[] groups) throws NotLoggedInException, NoResponseException, XMPPErrorException, NotConnectedException, InterruptedException {
        createItem(jid, name, groups);

        sendSubscriptionRequest(jid);
    }

    /**
     * Creates a new pre-approved roster entry and presence subscription. The server will
     * asynchronously update the roster with the subscription status.
     *
     * @param user   the user. (e.g. johndoe@jabber.org)
     * @param name   the nickname of the user.
     * @param groups the list of group names the entry will belong to, or <code>null</code> if
     *               the roster entry won't belong to a group.
     * @throws NoResponseException if there was no response from the server.
     * @throws XMPPErrorException if an XMPP exception occurs.
     * @throws NotLoggedInException if not logged in.
     * @throws NotConnectedException if the XMPP connection is not connected.
     * @throws InterruptedException if the calling thread was interrupted.
     * @throws FeatureNotSupportedException if pre-approving is not supported.
     * @since 4.2
     */
    public void preApproveAndCreateEntry(BareJid user, String name, String[] groups) throws NotLoggedInException, NoResponseException, XMPPErrorException, NotConnectedException, InterruptedException, FeatureNotSupportedException {
        preApprove(user);
        createItemAndRequestSubscription(user, name, groups);
    }

    /**
     * Pre-approve user presence subscription.
     *
     * @param user the user. (e.g. johndoe@jabber.org)
     * @throws NotLoggedInException if not logged in.
     * @throws NotConnectedException if the XMPP connection is not connected.
     * @throws InterruptedException if the calling thread was interrupted.
     * @throws FeatureNotSupportedException if pre-approving is not supported.
     * @since 4.2
     */
    public void preApprove(BareJid user) throws NotLoggedInException, NotConnectedException, InterruptedException, FeatureNotSupportedException {
        final XMPPConnection connection = connection();
        if (!isSubscriptionPreApprovalSupported()) {
            throw new FeatureNotSupportedException("Pre-approving");
        }

        Presence presencePacket = connection.getStanzaFactory().buildPresenceStanza()
            .ofType(Presence.Type.subscribed)
            .to(user)
            .build();
        connection.sendStanza(presencePacket);
    }

    /**
     * Check for subscription pre-approval support.
     *
     * @return true if subscription pre-approval is supported by the server.
     * @throws NotLoggedInException if not logged in.
     * @since 4.2
     */
    public boolean isSubscriptionPreApprovalSupported() throws NotLoggedInException {
        final XMPPConnection connection = getAuthenticatedConnectionOrThrow();
        return connection.hasFeature(SubscriptionPreApproval.ELEMENT, SubscriptionPreApproval.NAMESPACE);
    }

    public void sendSubscriptionRequest(BareJid jid) throws NotLoggedInException, NotConnectedException, InterruptedException {
        final XMPPConnection connection = getAuthenticatedConnectionOrThrow();

        // Create a presence subscription packet and send.
        Presence presencePacket = connection.getStanzaFactory().buildPresenceStanza()
                .ofType(Presence.Type.subscribe)
                .to(jid)
                .build();
        connection.sendStanza(presencePacket);
    }

    /**
     * Add a subscribe listener, which is invoked on incoming subscription requests and if
     * {@link SubscriptionMode} is set to {@link SubscriptionMode#manual}. This also sets subscription
     * mode to {@link SubscriptionMode#manual}.
     *
     * @param subscribeListener the subscribe listener to add.
     * @return <code>true</code> if the listener was not already added.
     * @since 4.2
     */
    public boolean addSubscribeListener(SubscribeListener subscribeListener) {
        Objects.requireNonNull(subscribeListener, "SubscribeListener argument must not be null");
        if (subscriptionMode != SubscriptionMode.manual) {
            previousSubscriptionMode = subscriptionMode;
            subscriptionMode = SubscriptionMode.manual;
        }
        return subscribeListeners.add(subscribeListener);
    }

    /**
     * Remove a subscribe listener. Also restores the previous subscription mode
     * state, if the last listener got removed.
     *
     * @param subscribeListener TODO javadoc me please
     *            the subscribe listener to remove.
     * @return <code>true</code> if the listener registered and got removed.
     * @since 4.2
     */
    public boolean removeSubscribeListener(SubscribeListener subscribeListener) {
        boolean removed = subscribeListeners.remove(subscribeListener);
        if (removed && subscribeListeners.isEmpty()) {
            setSubscriptionMode(previousSubscriptionMode);
        }
        return removed;
    }

    /**
     * Removes a roster entry from the roster. The roster entry will also be removed from the
     * unfiled entries or from any roster group where it could belong and will no longer be part
     * of the roster. Note that this is a synchronous call -- Smack must wait for the server
     * to send an updated subscription status.
     *
     * @param entry a roster entry.
     * @throws XMPPErrorException if an XMPP error occurs.
     * @throws NotLoggedInException if not logged in.
     * @throws NoResponseException SmackException if there was no response from the server.
     * @throws NotConnectedException if the XMPP connection is not connected.
     * @throws InterruptedException if the calling thread was interrupted.
     */
    public void removeEntry(RosterEntry entry) throws NotLoggedInException, NoResponseException, XMPPErrorException, NotConnectedException, InterruptedException {
        final XMPPConnection connection = getAuthenticatedConnectionOrThrow();

        // Only remove the entry if it's in the entry list.
        // The actual removal logic takes place in RosterPacketListenerProcess>>Packet(Packet)
        if (!entries.containsKey(entry.getJid())) {
            return;
        }
        RosterPacket packet = new RosterPacket();
        packet.setType(IQ.Type.set);
        RosterPacket.Item item = RosterEntry.toRosterItem(entry);
        // Set the item type as REMOVE so that the server will delete the entry
        item.setItemType(RosterPacket.ItemType.remove);
        packet.addRosterItem(item);
        connection.sendIqRequestAndWaitForResponse(packet);
    }

    /**
     * Returns a count of the entries in the roster.
     *
     * @return the number of entries in the roster.
     */
    public int getEntryCount() {
        return getEntries().size();
    }

    /**
     * Add a roster listener and invoke the roster entries with all entries of the roster.
     * <p>
     * The method guarantees that the listener is only invoked after
     * {@link RosterEntries#rosterEntries(Collection)} has been invoked, and that all roster events
     * that happen while <code>rosterEntries(Collection) </code> is called are queued until the
     * method returns.
     * </p>
     * <p>
     * This guarantee makes this the ideal method to e.g. populate a UI element with the roster while
     * installing a {@link RosterListener} to listen for subsequent roster events.
     * </p>
     *
     * @param rosterListener the listener to install
     * @param rosterEntries the roster entries callback interface
     * @since 4.1
     */
    public void getEntriesAndAddListener(RosterListener rosterListener, RosterEntries rosterEntries) {
        Objects.requireNonNull(rosterListener, "listener must not be null");
        Objects.requireNonNull(rosterEntries, "rosterEntries must not be null");

        synchronized (rosterListenersAndEntriesLock) {
            rosterEntries.rosterEntries(entries.values());
            addRosterListener(rosterListener);
        }
    }

    /**
     * Returns a set of all entries in the roster, including entries
     * that don't belong to any groups.
     *
     * @return all entries in the roster.
     */
    public Set<RosterEntry> getEntries() {
        Set<RosterEntry> allEntries;
        synchronized (rosterListenersAndEntriesLock) {
            allEntries = new HashSet<>(entries.size());
            for (RosterEntry entry : entries.values()) {
                allEntries.add(entry);
            }
        }
        return allEntries;
    }

    /**
     * Returns a count of the unfiled entries in the roster. An unfiled entry is
     * an entry that doesn't belong to any groups.
     *
     * @return the number of unfiled entries in the roster.
     */
    public int getUnfiledEntryCount() {
        return unfiledEntries.size();
    }

    /**
     * Returns an unmodifiable set for the unfiled roster entries. An unfiled entry is
     * an entry that doesn't belong to any groups.
     *
     * @return the unfiled roster entries.
     */
    public Set<RosterEntry> getUnfiledEntries() {
        return Collections.unmodifiableSet(unfiledEntries);
    }

    /**
     * Returns the roster entry associated with the given XMPP address or
     * <code>null</code> if the user is not an entry in the roster.
     *
     * @param jid the XMPP address of the user (e.g."jsmith@example.com"). The address could be
     *             in any valid format (e.g. "domain/resource", "user@domain" or "user@domain/resource").
     * @return the roster entry or <code>null</code> if it does not exist.
     */
    public RosterEntry getEntry(BareJid jid) {
        if (jid == null) {
            return null;
        }
        return entries.get(jid);
    }

    /**
     * Returns true if the specified XMPP address is an entry in the roster.
     *
     * @param jid the XMPP address of the user (e.g."jsmith@example.com"). The
     *             address must be a bare JID e.g. "domain/resource" or
     *             "user@domain".
     * @return true if the XMPP address is an entry in the roster.
     */
    public boolean contains(BareJid jid) {
        return getEntry(jid) != null;
    }

    /**
     * Returns the roster group with the specified name, or <code>null</code> if the
     * group doesn't exist.
     *
     * @param name the name of the group.
     * @return the roster group with the specified name.
     */
    public RosterGroup getGroup(String name) {
        return groups.get(name);
    }

    /**
     * Returns the number of the groups in the roster.
     *
     * @return the number of groups in the roster.
     */
    public int getGroupCount() {
        return groups.size();
    }

    /**
     * Returns an unmodifiable collections of all the roster groups.
     *
     * @return an iterator for all roster groups.
     */
    public Collection<RosterGroup> getGroups() {
        return Collections.unmodifiableCollection(groups.values());
    }

    /**
     * Returns the presence info for a particular user. If the user is offline, or
     * if no presence data is available (such as when you are not subscribed to the
     * user's presence updates), unavailable presence will be returned.
     *
     * If the user has several presences (one for each resource), then the presence with
     * highest priority will be returned. If multiple presences have the same priority,
     * the one with the "most available" presence mode will be returned. In order,
     * that's {@link org.jivesoftware.smack.packet.Presence.Mode#chat free to chat},
     * {@link org.jivesoftware.smack.packet.Presence.Mode#available available},
     * {@link org.jivesoftware.smack.packet.Presence.Mode#away away},
     * {@link org.jivesoftware.smack.packet.Presence.Mode#xa extended away}, and
     * {@link org.jivesoftware.smack.packet.Presence.Mode#dnd do not disturb}.
     *
     * <p>
     * Note that presence information is received asynchronously. So, just after logging
     * in to the server, presence values for users in the roster may be unavailable
     * even if they are actually online. In other words, the value returned by this
     * method should only be treated as a snapshot in time, and may not accurately reflect
     * other user's presence instant by instant. If you need to track presence over time,
     * such as when showing a visual representation of the roster, consider using a
     * {@link RosterListener}.
     * </p>
     *
     * @param jid the XMPP address of the user (e.g."jsmith@example.com"). The
     *             address must be a bare JID e.g. "domain/resource" or
     *             "user@domain".
     * @return the user's current presence, or unavailable presence if the user is offline
     *         or if no presence information is available.
     */
    public Presence getPresence(BareJid jid) {
        Map<Resourcepart, Presence> userPresences = getPresencesInternal(jid);
        if (userPresences == null) {
            Presence presence = synthesizeUnvailablePresence(jid);
            return presence;
        }
        else {
            // Find the resource with the highest priority
            // Might be changed to use the resource with the highest availability instead.
            Presence presence = null;
            // This is used in case no available presence is found
            Presence unavailable = null;

            for (Presence p : userPresences.values()) {
                if (!p.isAvailable()) {
                    unavailable = p;
                    continue;
                }
                // Chose presence with highest priority first.
                if (presence == null || p.getPriority() > presence.getPriority()) {
                    presence = p;
                }
                // If equal priority, choose "most available" by the mode value.
                else if (p.getPriority() == presence.getPriority()) {
                    Presence.Mode pMode = p.getMode();
                    // Default to presence mode of available.
                    if (pMode == null) {
                        pMode = Presence.Mode.available;
                    }
                    Presence.Mode presenceMode = presence.getMode();
                    // Default to presence mode of available.
                    if (presenceMode == null) {
                        presenceMode = Presence.Mode.available;
                    }
                    if (pMode.compareTo(presenceMode) < 0) {
                        presence = p;
                    }
                }
            }
            if (presence == null) {
                if (unavailable != null) {
                    return unavailable;
                }
                else {
                    presence = synthesizeUnvailablePresence(jid);
                    return presence;
                }
            }
            else {
                return presence;
            }
        }
    }

    /**
     * Returns the presence info for a particular user's resource, or unavailable presence
     * if the user is offline or if no presence information is available, such as
     * when you are not subscribed to the user's presence updates.
     *
     * @param userWithResource a fully qualified XMPP ID including a resource (user@domain/resource).
     * @return the user's current presence, or unavailable presence if the user is offline
     *         or if no presence information is available.
     */
    public Presence getPresenceResource(FullJid userWithResource) {
        BareJid key = userWithResource.asBareJid();
        Resourcepart resource = userWithResource.getResourcepart();
        Map<Resourcepart, Presence> userPresences = getPresencesInternal(key);
        if (userPresences == null) {
            Presence presence = synthesizeUnvailablePresence(userWithResource);
            return presence;
        }
        else {
            Presence presence = userPresences.get(resource);
            if (presence == null) {
                presence = synthesizeUnvailablePresence(userWithResource);
                return presence;
            }
            else {
                return presence;
            }
        }
    }

    /**
     * Returns a List of Presence objects for all of a user's current presences if no presence information is available,
     * such as when you are not subscribed to the user's presence updates.
     *
     * @param bareJid an XMPP ID, e.g. jdoe@example.com.
     * @return a List of Presence objects for all the user's current presences, or an unavailable presence if no
     *         presence information is available.
     */
    public List<Presence> getAllPresences(BareJid bareJid) {
        Map<Resourcepart, Presence> userPresences = getPresencesInternal(bareJid);
        List<Presence> res;
        if (userPresences == null) {
            // Create an unavailable presence if none was found
            Presence unavailable = synthesizeUnvailablePresence(bareJid);
            res = new ArrayList<>(Arrays.asList(unavailable));
        } else {
            res = new ArrayList<>(userPresences.values().size());
            for (Presence presence : userPresences.values()) {
                res.add(presence);
            }
        }
        return res;
    }

    /**
     * Returns a List of all <b>available</b> Presence Objects for the given bare JID. If there are no available
     * presences, then the empty list will be returned.
     *
     * @param bareJid the bare JID from which the presences should be retrieved.
     * @return available presences for the bare JID.
     */
    public List<Presence> getAvailablePresences(BareJid bareJid) {
        List<Presence> allPresences = getAllPresences(bareJid);
        List<Presence> res = new ArrayList<>(allPresences.size());
        for (Presence presence : allPresences) {
            if (presence.isAvailable()) {
                // No need to clone presence here, getAllPresences already returns clones
                res.add(presence);
            }
        }
        return res;
    }

    /**
     * Returns a List of Presence objects for all of a user's current presences
     * or an unavailable presence if the user is unavailable (offline) or if no presence
     * information is available, such as when you are not subscribed to the user's presence
     * updates.
     *
     * @param jid an XMPP ID, e.g. jdoe@example.com.
     * @return a List of Presence objects for all the user's current presences,
     *         or an unavailable presence if the user is offline or if no presence information
     *         is available.
     */
    public List<Presence> getPresences(BareJid jid) {
        List<Presence> res;
        Map<Resourcepart, Presence> userPresences = getPresencesInternal(jid);
        if (userPresences == null) {
            Presence presence = synthesizeUnvailablePresence(jid);
            res = Arrays.asList(presence);
        }
        else {
            List<Presence> answer = new ArrayList<>();
            // Used in case no available presence is found
            Presence unavailable = null;
            for (Presence presence : userPresences.values()) {
                if (presence.isAvailable()) {
                    answer.add(presence);
                }
                else {
                    unavailable = presence;
                }
            }
            if (!answer.isEmpty()) {
                res = answer;
            }
            else if (unavailable != null) {
                res = Arrays.asList(unavailable);
            }
            else {
                Presence presence = synthesizeUnvailablePresence(jid);
                res = Arrays.asList(presence);
            }
        }
        return res;
    }

    /**
     * Check if the given JID is subscribed to the user's presence.
     * <p>
     * If the JID is subscribed to the user's presence then it is allowed to see the presence and
     * will get notified about presence changes. Also returns true, if the JID is the service
     * name of the XMPP connection (the "XMPP domain"), i.e. the XMPP service is treated like
     * having an implicit subscription to the users presence.
     * </p>
     * Note that if the roster is not loaded, then this method will always return false.
     *
     * @param jid TODO javadoc me please
     * @return true if the given JID is allowed to see the users presence.
     * @since 4.1
     */
    public boolean isSubscribedToMyPresence(Jid jid) {
        if (jid == null) {
            return false;
        }
        BareJid bareJid = jid.asBareJid();
        if (connection().getXMPPServiceDomain().equals(bareJid)) {
            return true;
        }
        RosterEntry entry = getEntry(bareJid);
        if (entry == null) {
            return false;
        }
        return entry.canSeeMyPresence();
    }

    /**
     * Check if the XMPP entity this roster belongs to is subscribed to the presence of the given JID.
     *
     * @param jid the jid to check.
     * @return <code>true</code> if we are subscribed to the presence of the given jid.
     * @since 4.2
     */
    public boolean iAmSubscribedTo(Jid jid) {
        if (jid == null) {
            return false;
        }
        BareJid bareJid = jid.asBareJid();
        RosterEntry entry = getEntry(bareJid);
        if (entry == null) {
            return false;
        }
        return entry.canSeeHisPresence();
    }

    /**
     * Sets if the roster will be loaded from the server when logging in for newly created instances
     * of {@link Roster}.
     *
     * @param rosterLoadedAtLoginDefault if the roster will be loaded from the server when logging in.
     * @see #setRosterLoadedAtLogin(boolean)
     * @since 4.1.7
     */
    public static void setRosterLoadedAtLoginDefault(boolean rosterLoadedAtLoginDefault) {
        Roster.rosterLoadedAtLoginDefault = rosterLoadedAtLoginDefault;
    }

    /**
     * Sets if the roster will be loaded from the server when logging in. This
     * is the common behaviour for clients but sometimes clients may want to differ this
     * or just never do it if not interested in rosters.
     *
     * @param rosterLoadedAtLogin if the roster will be loaded from the server when logging in.
     */
    public void setRosterLoadedAtLogin(boolean rosterLoadedAtLogin) {
        this.rosterLoadedAtLogin = rosterLoadedAtLogin;
    }

    /**
     * Returns true if the roster will be loaded from the server when logging in. This
     * is the common behavior for clients but sometimes clients may want to differ this
     * or just never do it if not interested in rosters.
     *
     * @return true if the roster will be loaded from the server when logging in.
     * @see <a href="http://xmpp.org/rfcs/rfc6121.html#roster-login">RFC 6121 2.2 - Retrieving the Roster on Login</a>
     */
    public boolean isRosterLoadedAtLogin() {
        return rosterLoadedAtLogin;
    }

    RosterStore getRosterStore() {
        return rosterStore;
    }

    /**
     * Changes the presence of available contacts offline by simulating an unavailable
     * presence sent from the server.
     */
    private void setOfflinePresences() {
        outerloop: for (Jid user : presenceMap.keySet()) {
            Map<Resourcepart, Presence> resources = presenceMap.get(user);
            if (resources != null) {
                for (Resourcepart resource : resources.keySet()) {
                    PresenceBuilder presenceBuilder = StanzaBuilder.buildPresence()
                            .ofType(Presence.Type.unavailable);
                    EntityBareJid bareUserJid = user.asEntityBareJidIfPossible();
                    if (bareUserJid == null) {
                        LOGGER.warning("Can not transform user JID to bare JID: '" + user + "'");
                        continue;
                    }
                    presenceBuilder.from(JidCreate.fullFrom(bareUserJid, resource));
                    try {
                        presencePacketListener.processStanza(presenceBuilder.build());
                    }
                    catch (NotConnectedException e) {
                        throw new IllegalStateException(
                                        "presencePacketListener should never throw a NotConnectedException when processStanza is called with a presence of type unavailable",
                                        e);
                    }
                    catch (InterruptedException e) {
                        break outerloop;
                    }
                }
            }
        }
    }

    /**
     * Changes the presence of available contacts offline by simulating an unavailable
     * presence sent from the server. After a disconnection, every Presence is set
     * to offline.
     */
    private void setOfflinePresencesAndResetLoaded() {
        setOfflinePresences();
        rosterState = RosterState.uninitialized;
    }

    /**
     * Fires roster changed event to roster listeners indicating that the
     * specified collections of contacts have been added, updated or deleted
     * from the roster.
     *
     * @param addedEntries   the collection of address of the added contacts.
     * @param updatedEntries the collection of address of the updated contacts.
     * @param deletedEntries the collection of address of the deleted contacts.
     */
    private void fireRosterChangedEvent(final Collection<Jid> addedEntries, final Collection<Jid> updatedEntries,
                    final Collection<Jid> deletedEntries) {
        synchronized (rosterListenersAndEntriesLock) {
            for (RosterListener listener : rosterListeners) {
                if (!addedEntries.isEmpty()) {
                    listener.entriesAdded(addedEntries);
                }
                if (!updatedEntries.isEmpty()) {
                    listener.entriesUpdated(updatedEntries);
                }
                if (!deletedEntries.isEmpty()) {
                    listener.entriesDeleted(deletedEntries);
                }
            }
        }
    }

    /**
     * Fires roster presence changed event to roster listeners.
     *
     * @param bareFrom the bare JID that send the presence.
     * @param ownPresence true if this is a presence from one of our available resources.
     * @param presence the presence change.
     */
    private void fireRosterPresenceEvent(BareJid bareFrom, boolean ownPresence, Presence presence) {
        if (!ownPresence && !contains(bareFrom)) {
            return;
        }

        synchronized (rosterListenersAndEntriesLock) {
            for (RosterListener listener : rosterListeners) {
                if (ownPresence) {
                    listener.ownPresenceChanged(presence);
                } else {
                    listener.presenceChanged(presence);
                }
            }
        }
    }

    private void addUpdateEntry(Collection<Jid> addedEntries, Collection<Jid> updatedEntries,
                    Collection<Jid> unchangedEntries, RosterPacket.Item item, RosterEntry entry) {
        RosterEntry oldEntry;
        synchronized (rosterListenersAndEntriesLock) {
            oldEntry = entries.put(item.getJid(), entry);
        }
        if (oldEntry == null) {
            BareJid jid = item.getJid();
            addedEntries.add(jid);
            // Move the eventually existing presences from nonRosterPresenceMap to presenceMap.
            move(jid, nonRosterPresenceMap, presenceMap);
        }
        else {
            RosterPacket.Item oldItem = RosterEntry.toRosterItem(oldEntry);
            if (!oldEntry.equalsDeep(entry) || !item.getGroupNames().equals(oldItem.getGroupNames())) {
                updatedEntries.add(item.getJid());
                oldEntry.updateItem(item);
            } else {
                // Record the entry as unchanged, so that it doesn't end up as deleted entry
                unchangedEntries.add(item.getJid());
            }
        }

        // Mark the entry as unfiled if it does not belong to any groups.
        if (item.getGroupNames().isEmpty()) {
            unfiledEntries.add(entry);
        }
        else {
            unfiledEntries.remove(entry);
        }

        // Add the entry/user to the groups
        List<String> newGroupNames = new ArrayList<>();
        for (String groupName : item.getGroupNames()) {
            // Add the group name to the list.
            newGroupNames.add(groupName);

            // Add the entry to the group.
            RosterGroup group = getGroup(groupName);
            if (group == null) {
                group = createGroup(groupName);
                groups.put(groupName, group);
            }
            // Add the entry.
            group.addEntryLocal(entry);
        }

        // Remove user from the remaining groups.
        List<String> oldGroupNames = new ArrayList<>();
        for (RosterGroup group : getGroups()) {
            oldGroupNames.add(group.getName());
        }
        oldGroupNames.removeAll(newGroupNames);

        for (String groupName : oldGroupNames) {
            RosterGroup group = getGroup(groupName);
            group.removeEntryLocal(entry);
            if (group.getEntryCount() == 0) {
                groups.remove(groupName);
            }
        }
    }

    private void deleteEntry(Collection<Jid> deletedEntries, RosterEntry entry) {
        BareJid user = entry.getJid();
        entries.remove(user);
        unfiledEntries.remove(entry);
        // Move the presences from the presenceMap to the nonRosterPresenceMap.
        move(user, presenceMap, nonRosterPresenceMap);
        deletedEntries.add(user);

        for (Map.Entry<String, RosterGroup> e : groups.entrySet()) {
            RosterGroup group = e.getValue();
            group.removeEntryLocal(entry);
            if (group.getEntryCount() == 0) {
                groups.remove(e.getKey());
            }
        }
    }

    /**
     * Removes all the groups with no entries.
     *
     * This is used by {@link RosterPushListener} and {@link RosterResultListener} to
     * cleanup groups after removing contacts.
     */
    private void removeEmptyGroups() {
        // We have to do this because RosterGroup.removeEntry removes the entry immediately
        // (locally) and the group could remain empty.
        // TODO Check the performance/logic for rosters with large number of groups
        for (RosterGroup group : getGroups()) {
            if (group.getEntryCount() == 0) {
                groups.remove(group.getName());
            }
        }
    }

    /**
     * Move presences from 'entity' from one presence map to another.
     *
     * @param entity the entity
     * @param from the map to move presences from
     * @param to the map to move presences to
     */
    private static void move(BareJid entity, Map<BareJid, Map<Resourcepart, Presence>> from, Map<BareJid, Map<Resourcepart, Presence>> to) {
        Map<Resourcepart, Presence> presences = from.remove(entity);
        if (presences != null && !presences.isEmpty()) {
            to.put(entity, presences);
        }
    }

    /**
     * Ignore ItemTypes as of RFC 6121, 2.1.2.5.
     *
     * This is used by {@link RosterPushListener} and {@link RosterResultListener}.
     *
     * @param item the roster item to check
     * @return <code>true</code> if the item type should be ignored
     * */
    private static boolean hasValidSubscriptionType(RosterPacket.Item item) {
        switch (item.getItemType()) {
            case none:
            case from:
            case to:
            case both:
                return true;
            default:
                return false;
        }
    }

    private static Presence synthesizeUnvailablePresence(Jid from) {
        return StanzaBuilder.buildPresence()
                .ofType(Presence.Type.unavailable)
                .from(from)
                .build();
    }

    /**
     * Check if the server supports roster versioning.
     *
     * @return true if the server supports roster versioning, false otherwise.
     */
    public boolean isRosterVersioningSupported() {
        return connection().hasFeature(RosterVer.ELEMENT, RosterVer.NAMESPACE);
    }

    /**
     * An enumeration for the subscription mode options.
     */
    public enum SubscriptionMode {

        /**
         * Automatically accept all subscription and unsubscription requests.
         * This is suitable for simple clients. More complex clients will
         * likely wish to handle subscription requests manually.
         */
        accept_all,

        /**
         * Automatically reject all subscription requests. This is the default mode.
         */
        reject_all,

        /**
         * Subscription requests are ignored, which means they must be manually
         * processed by registering a listener for presence packets and then looking
         * for any presence requests that have the type Presence.Type.SUBSCRIBE or
         * Presence.Type.UNSUBSCRIBE.
         */
        manual
    }

    /**
     * Listens for all presence packets and processes them.
     */
    private final class PresencePacketListener implements StanzaListener {

        @Override
        public void processStanza(Stanza packet) throws NotConnectedException, InterruptedException {
            // Try to ensure that the roster is loaded when processing presence stanzas. While the
            // presence listener is synchronous, the roster result listener is not, which means that
            // the presence listener may be invoked with a not yet loaded roster.
            if (rosterState == RosterState.loading) {
                try {
                    waitUntilLoaded();
                }
                catch (InterruptedException e) {
                    LOGGER.log(Level.INFO, "Presence listener was interrupted", e);

                }
            }

            final Jid from = packet.getFrom();
            final Presence presence = (Presence) packet;
            final XMPPConnection connection = connection();
            if (connection == null) {
                LOGGER.finest("Connection was null while trying to handle exotic presence stanza: " + presence);
                return;
            }

            if (!isLoaded() && rosterLoadedAtLogin) {
                // Only log the warning, if this is not the reflected self-presence. Otherwise,
                // the reflected self-presence may cause a spurious warning in case the
                // connection got quickly shut down. See SMACK-941.
                if (from != null && !from.equals(connection.getUser())) {
                    LOGGER.warning("Roster not loaded while processing " + packet);
                }
            }

            final BareJid key;
            final boolean ownPresence;
            if (from != null) {
                EntityFullJid myJid = connection.getUser();
                ownPresence = from.isParentOf(myJid);

                key = from.asBareJid();
            } else {
                // Assume the presence come "from the users account on the server" since no from was set (RFC 6120 §
                // 8.1.2.1 4.). Note that getUser() may return null, but should never return null in this case as where
                // connected.
                EntityFullJid myJid = connection.getUser();
                if (myJid == null) {
                    LOGGER.info(
                            "Connection had no local address in Roster's presence listener."
                            + " Possibly we received a presence without from before being authenticated."
                            + " Presence: " + presence);
                    return;
                }
                LOGGER.info("Exotic presence stanza without from received: " + presence);
                key = myJid.asBareJid();
                ownPresence = true;
            }

            asyncButOrdered.performAsyncButOrdered(key, new Runnable() {
                @Override
                public void run() {
                    Resourcepart fromResource = Resourcepart.EMPTY;
                    BareJid bareFrom = null;
                    FullJid fullFrom = null;
                    if (from != null) {
                        fromResource = from.getResourceOrNull();
                        if (fromResource == null) {
                            fromResource = Resourcepart.EMPTY;
                            bareFrom = from.asBareJid();
                        }
                        else {
                            fullFrom = from.asFullJidIfPossible();
                            // We know that this must be a full JID in this case.
                            assert fullFrom != null;
                        }
                    }
                    Map<Resourcepart, Presence> userPresences;
                    // If an "available" presence, add it to the presence map. Each presence
                    // map will hold for a particular user a map with the presence
                    // packets saved for each resource.
                    switch (presence.getType()) {
                    case available:
                        // Get the user presence map
                        userPresences = getOrCreatePresencesInternal(key);
                        // See if an offline presence was being stored in the map. If so, remove
                        // it since we now have an online presence.
                        userPresences.remove(Resourcepart.EMPTY);
                        // Add the new presence, using the resources as a key.
                        userPresences.put(fromResource, presence);

                        // If the user is in the roster or if its our own presence, fire an event.
                        fireRosterPresenceEvent(key, ownPresence, presence);

                        for (PresenceEventListener presenceEventListener : presenceEventListeners) {
                            presenceEventListener.presenceAvailable(fullFrom, presence);
                        }
                        break;
                    // If an "unavailable" packet.
                    case unavailable:
                        // If no resource, this is likely an offline presence as part of
                        // a roster presence flood. In that case, we store it.
                        userPresences = getOrCreatePresencesInternal(key);
                        if (from.hasNoResource()) {
                            // Get the user presence map
                            userPresences.put(Resourcepart.EMPTY, presence);
                        }
                        // Otherwise, this is a normal offline presence.
                        else {
                            // Store the offline presence, as it may include extra information
                            // such as the user being on vacation.
                            userPresences.put(fromResource, presence);
                        }

                        // If the user is in the roster or if its our own presence, fire an event.
                        fireRosterPresenceEvent(key, ownPresence, presence);

                        // Ensure that 'from' is a full JID before invoking the presence unavailable
                        // listeners. Usually unavailable presences always have a resourcepart, i.e. are
                        // full JIDs, but RFC 6121 § 4.5.4 has an implementation note that unavailable
                        // presences from a bare JID SHOULD be treated as applying to all resources. I don't
                        // think any client or server ever implemented that, I do think that this
                        // implementation note is a terrible idea since it adds another corner case in
                        // client code, instead of just having the invariant
                        // "unavailable presences are always from the full JID".
                        if (fullFrom != null) {
                            for (PresenceEventListener presenceEventListener : presenceEventListeners) {
                                presenceEventListener.presenceUnavailable(fullFrom, presence);
                            }
                        } else {
                            LOGGER.fine("Unavailable presence from bare JID: " + presence);
                        }

                        break;
                    // Error presence packets from a bare JID mean we invalidate all existing
                    // presence info for the user.
                    case error:
                        // No need to act on error presences send without from, i.e.
                        // directly send from the users XMPP service, or where the from
                        // address is not a bare JID
                        if (from == null || !from.isEntityBareJid()) {
                            break;
                        }
                        userPresences = getOrCreatePresencesInternal(key);
                        // Any other presence data is invalidated by the error packet.
                        userPresences.clear();

                        // Set the new presence using the empty resource as a key.
                        userPresences.put(Resourcepart.EMPTY, presence);

                        // If the user is in the roster or if its our own presence, fire an event.
                        fireRosterPresenceEvent(key, ownPresence, presence);

                        for (PresenceEventListener presenceEventListener : presenceEventListeners) {
                            presenceEventListener.presenceError(from, presence);
                        }
                        break;
                    case subscribed:
                        for (PresenceEventListener presenceEventListener : presenceEventListeners) {
                            presenceEventListener.presenceSubscribed(bareFrom, presence);
                        }
                        break;
                    case unsubscribed:
                        for (PresenceEventListener presenceEventListener : presenceEventListeners) {
                            presenceEventListener.presenceUnsubscribed(bareFrom, presence);
                        }
                        break;
                    default:
                        break;
                    }
                }
            });
        }
    }

    /**
     * Handles Roster results as described in <a href="https://tools.ietf.org/html/rfc6121#section-2.1.4">RFC 6121 2.1.4</a>.
     */
    private final class RosterResultListener implements SuccessCallback<IQ> {

        @Override
        public void onSuccess(IQ packet) {
            final XMPPConnection connection = connection();
            LOGGER.log(Level.FINE, "RosterResultListener received {0}", packet);
            Collection<Jid> addedEntries = new ArrayList<>();
            Collection<Jid> updatedEntries = new ArrayList<>();
            Collection<Jid> deletedEntries = new ArrayList<>();
            Collection<Jid> unchangedEntries = new ArrayList<>();

            if (packet instanceof RosterPacket) {
                // Non-empty roster result. This stanza contains all the roster elements.
                RosterPacket rosterPacket = (RosterPacket) packet;

                // Ignore items without valid subscription type
                ArrayList<Item> validItems = new ArrayList<>();
                for (RosterPacket.Item item : rosterPacket.getRosterItems()) {
                    if (hasValidSubscriptionType(item)) {
                        validItems.add(item);
                    }
                }

                for (RosterPacket.Item item : validItems) {
                    RosterEntry entry = new RosterEntry(item, Roster.this, connection);
                    addUpdateEntry(addedEntries, updatedEntries, unchangedEntries, item, entry);
                }

                // Delete all entries which where not added or updated
                Set<Jid> toDelete = new HashSet<>();
                for (RosterEntry entry : entries.values()) {
                    toDelete.add(entry.getJid());
                }
                toDelete.removeAll(addedEntries);
                toDelete.removeAll(updatedEntries);
                toDelete.removeAll(unchangedEntries);
                for (Jid user : toDelete) {
                    deleteEntry(deletedEntries, entries.get(user));
                }

                if (rosterStore != null) {
                    String version = rosterPacket.getVersion();
                    rosterStore.resetEntries(validItems, version);
                }

                removeEmptyGroups();
            }
            else {
                // Empty roster result as defined in RFC6121 2.6.3. An empty roster result basically
                // means that rosterver was used and the roster hasn't changed (much) since the
                // version we presented the server. So we simply load the roster from the store and
                // await possible further roster pushes.
                List<RosterPacket.Item> storedItems = rosterStore.getEntries();
                if (storedItems == null) {
                    // The roster store was corrupted. Reset the store and reload the roster without using a roster version.
                    rosterStore.resetStore();
                    try {
                        reload();
                    } catch (NotLoggedInException | NotConnectedException
                            | InterruptedException e) {
                        LOGGER.log(Level.FINE,
                                "Exception while trying to load the roster after the roster store was corrupted",
                                e);
                    }
                    return;
                }
                for (RosterPacket.Item item : storedItems) {
                    RosterEntry entry = new RosterEntry(item, Roster.this, connection);
                    addUpdateEntry(addedEntries, updatedEntries, unchangedEntries, item, entry);
                }
            }

            rosterState = RosterState.loaded;
            synchronized (Roster.this) {
                Roster.this.notifyAll();
            }
            // Fire event for roster listeners.
            fireRosterChangedEvent(addedEntries, updatedEntries, deletedEntries);

            // Call the roster loaded listeners after the roster events have been fired. This is
            // important because the user may call getEntriesAndAddListener() in onRosterLoaded(),
            // and if the order would be the other way around, the roster listener added by
            // getEntriesAndAddListener() would be invoked with information that was already
            // available at the time getEntriesAndAddListener() was called.
            try {
                synchronized (rosterLoadedListeners) {
                    for (RosterLoadedListener rosterLoadedListener : rosterLoadedListeners) {
                        rosterLoadedListener.onRosterLoaded(Roster.this);
                    }
                }
            }
            catch (Exception e) {
                LOGGER.log(Level.WARNING, "RosterLoadedListener threw exception", e);
            }
        }
    }

    /**
     * Listens for all roster pushes and processes them.
     */
    private final class RosterPushListener extends AbstractIqRequestHandler {

        private RosterPushListener() {
            super(RosterPacket.ELEMENT, RosterPacket.NAMESPACE, IQ.Type.set, Mode.sync);
        }

        @Override
        public IQ handleIQRequest(IQ iqRequest) {
            final XMPPConnection connection = connection();
            RosterPacket rosterPacket = (RosterPacket) iqRequest;

            EntityFullJid ourFullJid = connection.getUser();
            if (ourFullJid == null) {
                LOGGER.warning("Ignoring roster push " + iqRequest + " while " + connection
                                + " has no bound resource. This may be a server bug.");
                return null;
            }

            // Roster push (RFC 6121, 2.1.6)
            // A roster push with a non-empty from not matching our address MUST be ignored
            EntityBareJid ourBareJid = ourFullJid.asEntityBareJid();
            Jid from = rosterPacket.getFrom();
            if (from != null) {
                if (from.equals(ourFullJid)) {
                    // Since RFC 6121 roster pushes are no longer allowed to
                    // origin from the full JID as it was the case with RFC
                    // 3921. Log a warning an continue processing the push.
                    // See also SMACK-773.
                    LOGGER.warning(
                            "Received roster push from full JID. This behavior is since RFC 6121 not longer standard compliant. "
                                    + "Please ask your server vendor to fix this and comply to RFC 6121 § 2.1.6. IQ roster push stanza: "
                                    + iqRequest);
                } else if (!from.equals(ourBareJid)) {
                    LOGGER.warning("Ignoring roster push with a non matching 'from' ourJid='" + ourBareJid + "' from='"
                            + from + "'");
                    return IQ.createErrorResponse(iqRequest, Condition.service_unavailable);
                }
            }

            // A roster push must contain exactly one entry
            Collection<Item> items = rosterPacket.getRosterItems();
            if (items.size() != 1) {
                LOGGER.warning("Ignoring roster push with not exactly one entry. size=" + items.size());
                return IQ.createErrorResponse(iqRequest, Condition.bad_request);
            }

            Collection<Jid> addedEntries = new ArrayList<>();
            Collection<Jid> updatedEntries = new ArrayList<>();
            Collection<Jid> deletedEntries = new ArrayList<>();
            Collection<Jid> unchangedEntries = new ArrayList<>();

            // We assured above that the size of items is exactly 1, therefore we are able to
            // safely retrieve this single item here.
            Item item = items.iterator().next();
            RosterEntry entry = new RosterEntry(item, Roster.this, connection);
            String version = rosterPacket.getVersion();

            if (item.getItemType().equals(RosterPacket.ItemType.remove)) {
                deleteEntry(deletedEntries, entry);
                if (rosterStore != null) {
                    rosterStore.removeEntry(entry.getJid(), version);
                }
            }
            else if (hasValidSubscriptionType(item)) {
                addUpdateEntry(addedEntries, updatedEntries, unchangedEntries, item, entry);
                if (rosterStore != null) {
                    rosterStore.addEntry(item, version);
                }
            }

            removeEmptyGroups();

            // Fire event for roster listeners.
            fireRosterChangedEvent(addedEntries, updatedEntries, deletedEntries);

            return IQ.createResultIQ(rosterPacket);
        }
    }

    /**
     * Set the default maximum size of the non-Roster presence map.
     * <p>
     * The roster will only store this many presence entries for entities non in the Roster. The
     * default is {@value #INITIAL_DEFAULT_NON_ROSTER_PRESENCE_MAP_SIZE}.
     * </p>
     *
     * @param maximumSize the maximum size
     * @since 4.2
     */
    public static void setDefaultNonRosterPresenceMapMaxSize(int maximumSize) {
        defaultNonRosterPresenceMapMaxSize = maximumSize;
    }

    /**
     * Set the maximum size of the non-Roster presence map.
     *
     * @param maximumSize TODO javadoc me please
     * @since 4.2
     * @see #setDefaultNonRosterPresenceMapMaxSize(int)
     */
    public void setNonRosterPresenceMapMaxSize(int maximumSize) {
        nonRosterPresenceMap.setMaxCacheSize(maximumSize);
    }

}
