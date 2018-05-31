/*         
 *  Copyright 2002-2018 Barcelona Supercomputing Center (www.bsc.es)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package es.bsc.compss.comm;

import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.data.location.DataLocation.Protocol;
import es.bsc.compss.types.exceptions.NonInstantiableException;
import es.bsc.compss.COMPSsConstants;
import es.bsc.compss.exceptions.ConstructConfigurationException;
import es.bsc.compss.exceptions.UnstartedNodeException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import storage.StorageException;
import storage.StorageItf;
import storage.StubItf;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.COMPSsWorker;
import es.bsc.compss.types.resources.MasterResource;
import es.bsc.compss.types.resources.Resource;
import es.bsc.compss.types.resources.configuration.Configuration;
import es.bsc.compss.types.uri.MultiURI;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.util.Classpath;
import es.bsc.compss.util.ErrorManager;
import es.bsc.compss.util.Tracer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Representation of the Communication interface of the Runtime
 *
 */
public class Comm {

    private static final String STORAGE_CONF = System.getProperty(COMPSsConstants.STORAGE_CONF);
    private static final String ADAPTORS_REL_PATH = File.separator + "Runtime" + File.separator + "adaptors";

    private static final Map<String, CommAdaptor> adaptors = new ConcurrentHashMap<>();

    // Log and debug
    protected static final Logger LOGGER = LogManager.getLogger(Loggers.COMM);
    private static final boolean DEBUG = LOGGER.isDebugEnabled();

    // Logical data
    private static Map<String, LogicalData> data = Collections.synchronizedMap(new TreeMap<String, LogicalData>());

    // Master information
    private static MasterResource appHost;

    /**
     * Private constructor to avoid instantiation
     */
    private Comm() {
        throw new NonInstantiableException("Comm");
    }

    /**
     * Communications initializer
     */
    public static void init() {
        appHost = new MasterResource();
        try {
            if (STORAGE_CONF == null || STORAGE_CONF.equals("") || STORAGE_CONF.equals("null")) {
                LOGGER.warn("No storage configuration file passed");
            } else {
                LOGGER.debug("Initializing Storage with: " + STORAGE_CONF);
                StorageItf.init(STORAGE_CONF);
            }
        } catch (StorageException e) {
            LOGGER.fatal("Error loading storage configuration file: " + STORAGE_CONF, e);
            System.exit(1);
        }

        loadAdaptorsJars();
        /*
         * Initializes the Tracer activation value to enable querying Tracer.isActivated()
         */
        if (System.getProperty(COMPSsConstants.TRACING) != null && Integer.parseInt(System.getProperty(COMPSsConstants.TRACING)) > 0) {
            LOGGER.debug("Tracing is activated");
            int tracing_level = Integer.parseInt(System.getProperty(COMPSsConstants.TRACING));
            Tracer.init(tracing_level);
            Tracer.emitEvent(Tracer.Event.STATIC_IT.getId(), Tracer.Event.STATIC_IT.getType());
        }

    }

    /**
     * Initializes the internal adaptor and constructs a comm configuration
     *
     * @param adaptorName
     * @param project_properties
     * @param resources_properties
     * @return
     * @throws ConstructConfigurationException
     */
    public static Configuration constructConfiguration(String adaptorName, Object project_properties, Object resources_properties)
            throws ConstructConfigurationException {

        // Check if adaptor has already been used
        CommAdaptor adaptor = adaptors.get(adaptorName);
        if (adaptor == null) {
            // Create a new adaptor instance
            try {
                Constructor<?> constrAdaptor = Class.forName(adaptorName).getConstructor();
                adaptor = (CommAdaptor) constrAdaptor.newInstance();
            } catch (NoSuchMethodException | SecurityException | ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {

                throw new ConstructConfigurationException(e);
            }

            // Initialize adaptor
            adaptor.init();

            // Add adaptor to used adaptors
            adaptors.put(adaptorName, adaptor);
        }

        if (DEBUG) {
            LOGGER.debug("Adaptor Name: " + adaptorName);
        }

        // Construct properties
        return adaptor.constructConfiguration(project_properties, resources_properties);
    }

    /**
     * Returns the resource assigned as master node
     *
     * @return
     */
    public static MasterResource getAppHost() {
        return appHost;
    }

    /**
     * Initializes a worker with name @name and configuration @config
     *
     * @param name
     * @param config
     * @return
     */
    public static COMPSsWorker initWorker(String name, Configuration config) {
        String adaptorName = config.getAdaptorName();
        CommAdaptor adaptor = adaptors.get(adaptorName);
        return adaptor.initWorker(name, config);
    }

    /**
     * Stops the communication layer. Clean FTM, Job, {GATJob, NIOJob} and WSJob
     */
    public static void stop() {
        appHost.deleteIntermediate();
        for (CommAdaptor adaptor : adaptors.values()) {
            adaptor.stop();
        }

        // Stop Storage interface
        if (STORAGE_CONF != null && !STORAGE_CONF.equals("") && !STORAGE_CONF.equals("null")) {
            try {
                LOGGER.debug("Stopping Storage...");
                StorageItf.finish();
            } catch (StorageException e) {
                LOGGER.error("Error releasing storage library: " + e.getMessage());
            }
        }
        // Stop tracing system
        if (Tracer.isActivated()) {
            Tracer.emitEvent(Tracer.EVENT_END, Tracer.getRuntimeEventsType());
            Tracer.fini();
        }
    }

    /**
     * Registers a new data with id @dataId
     *
     * @param dataId
     * @return
     */
    public static synchronized LogicalData registerData(String dataId) {
        LOGGER.debug("Register new data " + dataId);

        System.out.println("[DATA] Regsitering " + dataId);
        LogicalData logicalData = new LogicalData(dataId);
        data.put(dataId, logicalData);

        return logicalData;
    }

    /**
     * Registers a new location @location for the data with id @dataId dataId must exist
     *
     * @param dataId
     * @param location
     * @return
     */
    public static synchronized LogicalData registerLocation(String dataId, DataLocation location) {
        LOGGER.debug("Registering new Location for data " + dataId + ":");
        LOGGER.debug("  * Location: " + location);

        LogicalData logicalData = data.get(dataId);
        logicalData.addLocation(location);

        return logicalData;
    }

    /**
     * Registers a new value @value for the data with id @dataId dataId must exist
     *
     * @param dataId
     * @param value
     * @return
     */
    public static synchronized LogicalData registerValue(String dataId, Object value) {
        LOGGER.debug("Register value " + value + " for data " + dataId);

        String targetPath = Protocol.OBJECT_URI.getSchema() + dataId;
        DataLocation location = null;
        try {
            SimpleURI uri = new SimpleURI(targetPath);
            location = DataLocation.createLocation(appHost, uri);
        } catch (IOException e) {
            ErrorManager.error(DataLocation.ERROR_INVALID_LOCATION + " " + targetPath, e);
        }

        LogicalData logicalData = data.get(dataId);
        logicalData.addLocation(location);
        logicalData.setValue(value);

        // Register PSCO Location if needed it's PSCO and it's persisted
        if (value instanceof StubItf) {
            String id = ((StubItf) value).getID();
            if (id != null) {
                Comm.registerPSCO(dataId, id);
            }
        }

        return logicalData;
    }

    /**
     * Registers a new External PSCO id @id for the data with id @dataId dataId must exist
     *
     * @param dataId
     * @param id
     * @return
     */
    public static synchronized LogicalData registerExternalPSCO(String dataId, String id) {
        LogicalData ld = registerPSCO(dataId, id);
        ld.setValue(id);

        return ld;
    }

    /**
     * Registers a new PSCO id @id for the data with id @dataId dataId must exist
     *
     * @param dataId
     * @param id
     * @return
     */
    public static synchronized LogicalData registerPSCO(String dataId, String id) {
        String targetPath = Protocol.PERSISTENT_URI.getSchema() + id;
        DataLocation location = null;
        try {
            SimpleURI uri = new SimpleURI(targetPath);
            location = DataLocation.createLocation(appHost, uri);
        } catch (IOException ioe) {
            ErrorManager.error(DataLocation.ERROR_INVALID_LOCATION + " " + targetPath, ioe);
        }

        LogicalData logicalData = data.get(dataId);
        logicalData.addLocation(location);

        return logicalData;
    }

    /**
     * Clears the value of the data id @dataId
     *
     * @param dataId
     * @return
     */
    public static synchronized Object clearValue(String dataId) {
        LOGGER.debug("Clear value of data " + dataId);
        LogicalData logicalData = data.get(dataId);

        return logicalData.removeValue();
    }

    /**
     * Checks if a given dataId @renaming exists
     *
     * @param renaming
     * @return
     */
    public static synchronized boolean existsData(String renaming) {
        return (data.get(renaming) != null);
    }

    /**
     * Returns the data with id @dataId
     *
     * @param dataId
     * @return
     */
    public static synchronized LogicalData getData(String dataId) {
        LogicalData retVal = data.get(dataId);
        if (retVal == null) {
            LOGGER.warn("Get data " + dataId + " is null.");
        }
        return retVal;
    }

    /**
     * Dumps the stored data (only for testing)
     *
     * @return
     */
    public static synchronized String dataDump() {
        StringBuilder sb = new StringBuilder("DATA DUMP\n");
        for (Entry<String, LogicalData> lde : data.entrySet()) {
            sb.append("\t *").append(lde.getKey()).append(":\n");
            LogicalData ld = lde.getValue();
            for (MultiURI u : ld.getURIs()) {
                sb.append("\t\t + ").append(u.toString()).append("\n");
                for (String adaptor : adaptors.keySet()) {

                    Object internal = null;
                    try {
                        internal = u.getInternalURI(adaptor);
                        if (internal != null) {
                            sb.append("\t\t\t - ").append(internal.toString()).append("\n");
                        }
                    } catch (UnstartedNodeException une) {
                        // Node was not started. Cannot print internal object.
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Returns all the data stored in a host @host
     *
     * @param host
     * @return
     */
    public static Set<LogicalData> getAllData(Resource host) {
        // logger.debug("Get all data from host: " + host.getName());
        return host.getAllDataFromHost();
    }

    /**
     * Removes the data with id @renaming
     *
     * @param renaming
     */
    public static synchronized void removeData(String renaming) {
        LOGGER.debug("Remove data " + renaming);

        LogicalData ld = data.remove(renaming);
        ld.isObsolete();
        for (DataLocation dl : ld.getLocations()) {
            MultiURI uri = dl.getURIInHost(appHost);
            if (uri != null) {
                File f = new File(uri.getPath());
                if (f.exists()) {
                    LOGGER.info("Deleting file " + f.getAbsolutePath());
                    if (!f.delete()) {
                        LOGGER.error("Cannot delete file " + f.getAbsolutePath());
                    }
                }
            }
        }

    }

    /**
     * Return the active adaptors
     *
     * @return
     */
    public static Map<String, CommAdaptor> getAdaptors() {
        return adaptors;
    }

    /**
     * Stops all the submitted jobs
     *
     */
    public static void stopSubmittedjobs() {
        for (CommAdaptor adaptor : adaptors.values()) {
            adaptor.stopSubmittedJobs();
        }
    }

    private static void loadAdaptorsJars() {
        LOGGER.info("Loading Adaptors...");
        String compssHome = System.getenv(COMPSsConstants.COMPSS_HOME);

        if (compssHome == null || compssHome.isEmpty()) {
            LOGGER.warn("WARN: COMPSS_HOME not defined, no adaptors loaded.");
            return;
        }

        try {
            Classpath.loadPath(compssHome + ADAPTORS_REL_PATH, LOGGER);
        } catch (FileNotFoundException ex) {
            LOGGER.warn("WARN_MSG = [Adaptors folder not defined, no adaptors loaded.]");
        }
    }

}
