package org.sa.rainbow.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.sa.rainbow.RainbowConstants;
import org.sa.rainbow.core.error.RainbowAbortException;
import org.sa.rainbow.util.Util;

/**
 * A singleton class that provides utilities for reading properties, and getting access to important Rainbow Framework
 * services.
 * 
 * @author Bradley Schmerl: schmerl
 * 
 */
public class Rainbow implements RainbowConstants {
    static Logger LOGGER = Logger.getLogger (Rainbow.class);

    /**
     * States used to help the Rainbow daemon process determine what to do after this Rainbow component exits.
     * 
     * @author Shang-Wen Cheng (zensoul@cs.cmu.edu)
     */
    public static enum ExitState {
        /**
         * Completely clear out (daemon dies) after the Rainbow component exits (default).
         */
        DESTRUCT,
        /** Restart the Rainbow component after exits. */
        RESTART,
        /**
         * After the Rainbow component exits, sleep and await awake command to restart Rainbow.
         */
        SLEEP,
        /** Abort of operation. */
        ABORT;

        public static ExitState parseState (int val) {
            ExitState st = ExitState.DESTRUCT;
            switch (val) {
            case EXIT_VALUE_DESTRUCT:
                st = ExitState.DESTRUCT;
                break;
            case EXIT_VALUE_RESTART:
                st = ExitState.RESTART;
                break;
            case EXIT_VALUE_SLEEP:
                st = ExitState.SLEEP;
                break;
            case EXIT_VALUE_ABORT:
                st = ExitState.ABORT;
                break;
            }
            return st;
        }

        public int exitValue () {
            int ev = 0;
            switch (this) {
            case DESTRUCT:
                ev = EXIT_VALUE_DESTRUCT;
                break;
            case RESTART:
                ev = EXIT_VALUE_RESTART;
                break;
            case SLEEP:
                ev = EXIT_VALUE_SLEEP;
                break;
            case ABORT:
                ev = EXIT_VALUE_ABORT;
                break;
            }
            return ev;
        }
    }

    /** The thread name */
    public static final String NAME        = "Rainbow Runtime Infrastructure";

    /**
     * Exit status that Rainbow would report when it exits, default to sleeping.
     */
    private static ExitState   m_exitState = ExitState.SLEEP;

    /**
     * Singleton instance of Rainbow
     */
    private static Rainbow m_instance = null;

    private boolean            m_shouldTerminate = false;

    public static Rainbow instance () {
        if (m_instance == null) {
            m_instance = new Rainbow ();
        }
        return m_instance;
    }

    /**
     * Returns whether the Rainbow runtime infrastructure should terminate.
     * 
     * @return <code>true</code> if Rainbow should terminate, <code>false</code> otherwise.
     */
    public static boolean shouldTerminate () {
        return instance ().m_shouldTerminate;
    }

    /**
     * Sets the shouldTerminate flag so that the Rainbow Runtime Infrastructure parts know to terminate. This method is
     * intended primarily for {@link org.sa.rainbow.core.Oracle <code>Oracle</code>}, but may be used by the
     * UpdateService to signal termination.
     */
    public static void signalTerminate () {
        if (!instance ().m_shouldTerminate) { // log once the signalling to
                                              // terminate
            LOGGER.info ("*** Signalling Terminate ***");
        }
        instance ().m_shouldTerminate = true;
    }

    public static void signalTerminate (ExitState exitState) {
        setExitState (exitState);
        signalTerminate ();
    }

    /**
     * Returns the exit value given the Rainbow exit state; this is stored statically since it would only be used once.
     * 
     * @return int the exit value to return on System exit.
     */
    public static int exitValue () {
        return m_exitState.exitValue ();
    }

    public static void setExitState (ExitState state) {
        m_exitState = state;
    }

    private Properties m_props;
    private File       m_basePath;
    private File       m_targetPath;

    private ThreadGroup m_threadGroup;

    private Rainbow () {
        m_props = new Properties ();
        m_threadGroup = new ThreadGroup (NAME);
        establishPaths ();
        loadConfigFiles ();
        canonicalizeHost2IPs ();
        evalPropertySubstitution ();
    }



    public static Properties properties () {
        return instance ().m_props;
    }

    /**
     * Determines and configures the paths to the Rainbow base installation and target configuration files
     */
    private void establishPaths () {
        String binPath = System.getProperty (PROPKEY_BIN_PATH, RAINBOW_BIN_DIR); // The location of rainbow binaries
        String cfgPath = System.getProperty (PROPKEY_CONFIG_PATH, RAINBOW_CONFIG_PATH); // The location of targets
        String target = System.getProperty (PROPKEY_TARGET_NAME, DEFAULT_TARGET_NAME); // The target to use 
        m_props.setProperty (PROPKEY_BIN_PATH, binPath);
        m_props.setProperty (PROPKEY_CONFIG_PATH, cfgPath);
        m_props.setProperty (PROPKEY_TARGET_NAME, target);
        m_basePath = Util.computeBasePath (cfgPath);
        if (m_basePath == null) {
            String errorMsg = MessageFormat.format ("Configuration path {0} NOT found,  bailing.", cfgPath);
            LOGGER.error (errorMsg);
            throw new RainbowAbortException (errorMsg);
        }

        m_targetPath = Util.getRelativeToPath (m_basePath, target);
        try {
            m_props.setProperty (PROPKEY_TARGET_PATH, Util.unifyPath (m_targetPath.getCanonicalPath ()));
        }
        catch (IOException e) {
            LOGGER.error (e);
            if (m_targetPath == null) {
                String errMsg = MessageFormat.format ("Target configuration ''{0}'' NOT found, bailing!", target);
                LOGGER.error (errMsg);
                throw new RainbowAbortException (errMsg);
            }
        }

    }

    /**
     * Determine and load the appropriate sequence of Rainbow's config files
     */
    private void loadConfigFiles () {
        LOGGER.debug (MessageFormat.format ("Rainbow config path: {0}", m_targetPath.getAbsolutePath ()));

        computeHostSpecificConfig ();
        String cfgFile = m_props.getProperty (PROPKEY_CONFIG_FILE, DEFAULT_CONFIG_FILE);
        List<String> cfgFiles = new ArrayList<> ();
        if (!cfgFile.equals (DEFAULT_CONFIG_FILE)) {
            // load commong config file first
            cfgFiles.add (DEFAULT_CONFIG_FILE);
        }
        cfgFiles.add (cfgFile);
        LOGGER.debug (
                MessageFormat.format ("Loading Rainbow config file(s): {0}", Arrays.toString (cfgFiles.toArray ())));

        // Load the properties in each cfgFile into the m_props of this method. This constitutes the properties of Rainbow
        // for this host.
        for (String cfg : cfgFiles) {
            try (FileInputStream pfIn = new FileInputStream (Util.getRelativeToPath (m_targetPath, cfg))) {
                m_props.load (pfIn);
            }
            catch (FileNotFoundException e) {
                LOGGER.error (e);
            }
            catch (IOException e) {
                LOGGER.error (e);
            }
        }
    }

    /**
     * Sanitizes the master, deployment, and all target location hostnames to their IP addresses. This method does value
     * substitution for the master and deployment hosts.
     */
    private void canonicalizeHost2IPs () {
        String masterLoc = m_props.getProperty (PROPKEY_MASTER_LOCATION);
        canonicalizeHost2IP ("Master Location", masterLoc);

        String deployLoc = m_props.getProperty (PROPKEY_DEPLOYMENT_LOCATION);
        canonicalizeHost2IP ("Deployment Location", deployLoc);

        // Resolve all of the mentioned target locations
        int cnt = Integer.parseInt (m_props.getProperty (PROPKEY_TARGET_LOCATION + Util.SIZE_SFX, "0"));
        for (int i = 0; i < cnt; i++) {
            String propName = Rainbow.PROPKEY_TARGET_LOCATION + Util.DOT + i;
            String hostLoc = m_props.getProperty (propName);
            canonicalizeHost2IP ("Target host location", hostLoc);
        }

    }

    /**
     * Substitutes property values containing the pattern ${x} with the value that is mapped to the key "x".
     */
    private void evalPropertySubstitution () {
        for (Object kObj : m_props.keySet ()) {
            String key = (String )kObj;
            String val = m_props.getProperty (key);
            while (val.contains (Util.TOKEN_BEGIN)) {
                m_props.setProperty (key, Util.evalTokens (val, m_props));
                val = m_props.getProperty (key);
            }
        }
    }

    private void canonicalizeHost2IP (String string, String masterLoc) {
        masterLoc = Util.evalTokens (masterLoc, m_props);
        try {
            masterLoc = InetAddress.getByName (masterLoc).getHostAddress ();
            m_props.setProperty (PROPKEY_MASTER_LOCATION, masterLoc);
        }
        catch (UnknownHostException e) {
            LOGGER.warn (
                    MessageFormat.format (
                            "{1} ''{0}'' could not be resolved to an IP using the given name.",
                            masterLoc, string),
                            e);
        }
    }

    /**
     * Uses hostname on which this Rainbow component resides to determine if a host-specific Rainbow config file exists.
     * Sets the config file name to point to that file if yes. The attempts, in order, are:
     * <ol>
     * <li>lowercased OS-known (remebered) localhost name
     * <li>first segment (before first dot) of the remebered hostname
     * <li>IP number
     * <li>lowercased canonical hostname
     * <li>first segment of the canonical
     * </ol>
     * 
     * @throws SocketException
     */
    private void computeHostSpecificConfig () {
        List<String> triedHosts = new ArrayList<String> ();

        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces ();
            while (networkInterfaces.hasMoreElements ()) {
                NetworkInterface ni = networkInterfaces.nextElement ();
                Enumeration<InetAddress> addresses = ni.getInetAddresses ();
                while (addresses.hasMoreElements ()) {
                    InetAddress ia = addresses.nextElement ();
                    if (checkInetAddressConfigFile (ia, triedHosts)) return;
                }
            }
        }
        catch (SocketException e) {
        }
        LOGGER.error (MessageFormat.format ("Unable to find host-specific property file! Tried: {0}",
                Arrays.toString (triedHosts.toArray ())));
    }

    /**
     * Checks through all the possible host specific combinations: IP address, host.name, host, name
     * 
     * @param ia
     *            the InetAdress to check
     * @param triedHosts
     *            The combinations that have been tried
     * @return true after the the first properties file that matches is found
     */
    private boolean checkInetAddressConfigFile (InetAddress ia, List<String> triedHosts) {
        if (ia instanceof Inet6Address) return false;
        // check with the remembered hostname (preferred)
        String hostname = ia.getHostName ().toLowerCase ();
        triedHosts.add (hostname);
        if (checkSetConfig (hostname)) return true;
        // try part before first dot
        int dotIdx = hostname.indexOf (Util.DOT);
        if (dotIdx > -1) {
            hostname = hostname.substring (0, dotIdx);
            if (!triedHosts.contains (hostname)) {
                triedHosts.add (hostname);
                if (checkSetConfig (hostname)) return true;
            }
        }
        // then try IP number
        hostname = ia.getHostAddress ();
        if (!triedHosts.contains (hostname)) {
            triedHosts.add (hostname);
            if (checkSetConfig (hostname)) return true;
        }
        // otherwise try canonical hostname
        hostname = ia.getCanonicalHostName ().toLowerCase ();
        if (!triedHosts.contains (hostname)) {
            triedHosts.add (hostname);
            if (checkSetConfig (hostname)) return true;
        }
        // finally, try first part of canonical
        dotIdx = hostname.indexOf (Util.DOT);
        if (dotIdx > -1) {
            hostname = hostname.substring (0, dotIdx);
            if (!triedHosts.contains (hostname)) {
                triedHosts.add (hostname);
                if (checkSetConfig (hostname)) return true;
            }
        }

        return false;
    }

    /**
     * Checks to see if there is a property file that for this hostname, and sets the config file property if there is.
     * It is checking whether rainbow-&lt;hostname&gt;.properties exists.
     * 
     * @param hostname
     *            the hostname to check
     * @return true if the file hostname specific properties file exists
     */
    private boolean checkSetConfig (String hostname) {
        boolean good = false;
        String cfgFileName = Rainbow.CONFIG_FILE_TEMPLATE.replace (CONFIG_FILE_STUB_NAME, hostname);
        if (Util.getRelativeToPath (m_targetPath, cfgFileName).exists ()) {
            m_props.setProperty (Rainbow.PROPKEY_CONFIG_FILE, cfgFileName);
            good = true;
        }
        return good;
    }

    public ThreadGroup getThreadGroup () {
        return m_threadGroup;
    }

}