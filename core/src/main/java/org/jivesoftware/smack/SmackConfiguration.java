/**
 *
 * Copyright 2003-2007 Jive Software.
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

package org.jivesoftware.smack;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jivesoftware.smack.compression.Java7ZlibInputOutputStream;
import org.jivesoftware.smack.compression.XMPPInputOutputStream;
import org.jivesoftware.smack.initializer.SmackInitializer;
import org.jivesoftware.smack.parsing.ExceptionThrowingCallback;
import org.jivesoftware.smack.parsing.ParsingExceptionCallback;
import org.jivesoftware.smack.util.FileUtils;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Represents the configuration of Smack. The configuration is used for:
 * <ul>
 *      <li> Initializing classes by loading them at start-up.
 *      <li> Getting the current Smack version.
 *      <li> Getting and setting global library behavior, such as the period of time
 *          to wait for replies to packets from the server. Note: setting these values
 *          via the API will override settings in the configuration file.
 * </ul>
 *
 * Configuration settings are stored in org.jivesoftware.smack/smack-config.xml (typically inside the
 * smack.jar file).
 * 
 * @author Gaston Dombiak
 */
public final class SmackConfiguration {
    private static final String SMACK_VERSION;
    private static final String DEFAULT_CONFIG_FILE = "classpath:org.jivesoftware.smack/smack-config.xml";
    
    private static final Logger LOGGER = Logger.getLogger(SmackConfiguration.class.getName());

    private static int defaultPacketReplyTimeout = 5000;
    private static int packetCollectorSize = 5000;

    private static List<String> defaultMechs = new ArrayList<String>();

    private static Set<String> disabledSmackClasses = new HashSet<String>();

    private final static List<XMPPInputOutputStream> compressionHandlers = new ArrayList<XMPPInputOutputStream>(2);

    /**
     * Loads the configuration from the smack-config.xml file.<p>
     *
     * So far this means that:
     * 1) a set of classes will be loaded in order to execute their static init block
     * 2) retrieve and set the current Smack release
     */
    static {
        String smackVersion;
        try {
            InputStream is = FileUtils.getStreamForUrl("classpath:org.jivesoftware.smack/version", null);
            byte[] buf = new byte[1024];
            is.read(buf);
            smackVersion = new String(buf, "UTF-8");
        } catch(Exception e) {
            LOGGER.log(Level.SEVERE, "Could not determine Smack version", e);
            smackVersion = "unkown";
        }
        SMACK_VERSION = smackVersion;

        String disabledClasses = System.getProperty("smack.disabledClasses");
        if (disabledClasses != null) {
            String[] splitDisabledClasses = disabledClasses.split(",");
            for (String s : splitDisabledClasses) disabledSmackClasses.add(s);
        }
        try {
            FileUtils.addLines("classpath:org.jivesoftware.smack/disabledClasses", disabledSmackClasses);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }

        try {
            Class<?> c = Class.forName("org.jivesoftware.smack.CustomSmackConfiguration");
            Field f = c.getField("DISABLED_SMACK_CLASSES");
            String[] sa = (String[]) f.get(null);
            if (sa != null)
                for (String s : sa)
                    disabledSmackClasses.add(s);
        }
        catch (ClassNotFoundException e1) {
        }
        catch (NoSuchFieldException e) {
        }
        catch (SecurityException e) {
        }
        catch (IllegalArgumentException e) {
        }
        catch (IllegalAccessException e) {
        }

        InputStream configFileStream;
        try {
            configFileStream = FileUtils.getStreamForUrl(DEFAULT_CONFIG_FILE, null);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }

        try {
            processConfigFile(configFileStream, null);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // Add the Java7 compression handler first, since it's preferred
        compressionHandlers.add(new Java7ZlibInputOutputStream());
    }

    /**
     * The default parsing exception callback is {@link ExceptionThrowingCallback} which will
     * throw an exception and therefore disconnect the active connection.
     */
    private static ParsingExceptionCallback defaultCallback = new ExceptionThrowingCallback();

    /**
     * Returns the Smack version information, eg "1.3.0".
     * 
     * @return the Smack version information.
     */
    public static String getVersion() {
        return SMACK_VERSION;
    }

    /**
     * Returns the number of milliseconds to wait for a response from
     * the server. The default value is 5000 ms.
     * 
     * @return the milliseconds to wait for a response from the server
     */
    public static int getDefaultPacketReplyTimeout() {
        // The timeout value must be greater than 0 otherwise we will answer the default value
        if (defaultPacketReplyTimeout <= 0) {
            defaultPacketReplyTimeout = 5000;
        }
        return defaultPacketReplyTimeout;
    }

    /**
     * Sets the number of milliseconds to wait for a response from
     * the server.
     * 
     * @param timeout the milliseconds to wait for a response from the server
     */
    public static void setDefaultPacketReplyTimeout(int timeout) {
        if (timeout <= 0) {
            throw new IllegalArgumentException();
        }
        defaultPacketReplyTimeout = timeout;
    }

    /**
     * Gets the default max size of a packet collector before it will delete 
     * the older packets.
     * 
     * @return The number of packets to queue before deleting older packets.
     */
    public static int getPacketCollectorSize() {
        return packetCollectorSize;
    }

    /**
     * Sets the default max size of a packet collector before it will delete 
     * the older packets.
     * 
     * @param collectorSize the number of packets to queue before deleting older packets.
     */
    public static void setPacketCollectorSize(int collectorSize) {
        packetCollectorSize = collectorSize;
    }
    
    /**
     * Add a SASL mechanism to the list to be used.
     *
     * @param mech the SASL mechanism to be added
     */
    public static void addSaslMech(String mech) {
        if(! defaultMechs.contains(mech) ) {
            defaultMechs.add(mech);
        }
    }

   /**
     * Add a Collection of SASL mechanisms to the list to be used.
     *
     * @param mechs the Collection of SASL mechanisms to be added
     */
    public static void addSaslMechs(Collection<String> mechs) {
        for(String mech : mechs) {
            addSaslMech(mech);
        }
    }

    /**
     * Remove a SASL mechanism from the list to be used.
     *
     * @param mech the SASL mechanism to be removed
     */
    public static void removeSaslMech(String mech) {
        defaultMechs.remove(mech);
    }

   /**
     * Remove a Collection of SASL mechanisms to the list to be used.
     *
     * @param mechs the Collection of SASL mechanisms to be removed
     */
    public static void removeSaslMechs(Collection<String> mechs) {
        defaultMechs.removeAll(mechs);
    }

    /**
     * Returns the list of SASL mechanisms to be used. If a SASL mechanism is
     * listed here it does not guarantee it will be used. The server may not
     * support it, or it may not be implemented.
     *
     * @return the list of SASL mechanisms to be used.
     */
    public static List<String> getSaslMechs() {
        return Collections.unmodifiableList(defaultMechs);
    }

    /**
     * Set the default parsing exception callback for all newly created connections
     *
     * @param callback
     * @see ParsingExceptionCallback
     */
    public static void setDefaultParsingExceptionCallback(ParsingExceptionCallback callback) {
        defaultCallback = callback;
    }

    /**
     * Returns the default parsing exception callback
     * 
     * @return the default parsing exception callback
     * @see ParsingExceptionCallback
     */
    public static ParsingExceptionCallback getDefaultParsingExceptionCallback() {
        return defaultCallback;
    }

    public static void addCompressionHandler(XMPPInputOutputStream xmppInputOutputStream) {
        compressionHandlers.add(xmppInputOutputStream);
    }

    public static List<XMPPInputOutputStream> getCompresionHandlers() {
        List<XMPPInputOutputStream> res = new ArrayList<XMPPInputOutputStream>(compressionHandlers.size());
        for (XMPPInputOutputStream ios : compressionHandlers) {
            if (ios.isSupported()) {
                res.add(ios);
            }
        }
        return res;
    }

    public static void processConfigFile(InputStream cfgFileStream, Collection<Exception> exceptions) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(cfgFileStream, "UTF-8");
        int eventType = parser.getEventType();
        do {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.getName().equals("startupClasses")) {
                    parseClassesToLoad(parser, false, exceptions);
                }
                else if (parser.getName().equals("optionalStartupClasses")) {
                    parseClassesToLoad(parser, true, exceptions);
                }
            }
            eventType = parser.next();
        }
        while (eventType != XmlPullParser.END_DOCUMENT);
        try {
            cfgFileStream.close();
        }
        catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error while closing config file input stream", e);
        }
    }

    private static void parseClassesToLoad(XmlPullParser parser, boolean optional, Collection<Exception> exceptions) throws XmlPullParserException, IOException, Exception {
        final String startName = parser.getName();
        int eventType;
        String name;
        do {
            eventType = parser.next();
            name = parser.getName();
            if (eventType == XmlPullParser.START_TAG && "className".equals(name)) {
                String classToLoad = parser.nextText();
                if (disabledSmackClasses.contains(classToLoad)) {
                    LOGGER.info("Not loading disabled Smack class " + classToLoad);
                }
                else {
                    try {
                        loadSmackClass(classToLoad, optional);
                    } catch (Exception e) {
                        // Don't throw the exception if an exceptions collection is given, instead
                        // record it there. This is used for unit testing purposes.
                        if (exceptions != null) {
                            exceptions.add(e);
                        } else {
                            throw e;
                        }
                    }
                }
            }
        } while (! (eventType == XmlPullParser.END_TAG && startName.equals(name)));
    }

    private static void loadSmackClass(String className, boolean optional) throws Exception {
        Class<?> initClass;
        try {
            // Attempt to load the class so that the class can get initialized
            initClass = Class.forName(className);
        }
        catch (ClassNotFoundException cnfe) {
            Level logLevel;
            if (optional) {
                logLevel = Level.FINE;
            }
            else {
                logLevel = Level.WARNING;
            }
            LOGGER.log(logLevel, "A startup class '" + className + "' could not be loaded.");
            if (!optional) {
                throw cnfe;
            } else {
                return;
            }
        }
        if (SmackInitializer.class.isAssignableFrom(initClass)) {
            SmackInitializer initializer = (SmackInitializer) initClass.newInstance();
            initializer.initialize();
            List<Exception> exceptions = initializer.getExceptions();
            if (exceptions.size() == 0) {
                LOGGER.log(Level.FINE, "Loaded SmackInitializer " + className);
            } else {
                for (Exception e : exceptions) {
                    LOGGER.log(Level.SEVERE, "Exception in loadSmackClass", e);
                }
            }
        } else {
            LOGGER.log(Level.FINE, "Loaded " + className);
        }
    }
}
