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
package es.bsc.compss.mf2c.master;

import es.bsc.compss.comm.Comm;
import es.bsc.compss.types.data.listener.EventListener;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.data.location.DataLocation.Protocol;
import es.bsc.compss.types.COMPSsWorker;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.data.Transferable;
import es.bsc.compss.types.resources.ExecutorShutdownListener;
import es.bsc.compss.types.uri.MultiURI;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.mf2c.master.configuration.AgentConfiguration;
import static es.bsc.compss.types.COMPSsNode.DEBUG;
import es.bsc.compss.types.data.operation.DataOperation;
import es.bsc.compss.types.data.operation.copy.StorageCopy;
import es.bsc.compss.util.Debugger;
import java.util.LinkedList;
import java.util.List;
import storage.StorageException;
import storage.StorageItf;


public abstract class Agent extends COMPSsWorker {

    private final String name;
    private AgentConfiguration config;

    public Agent(String name, AgentConfiguration config) {
        super(name, config);
        this.name = name;
        this.config = config;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setInternalURI(MultiURI uri) {

    }

    @Override
    public void sendData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason, EventListener listener) {
        // Never sends Data
    }

    @Override
    public void updateTaskCount(int processorCoreCount) {
        // No need to do anything
    }

    @Override
    public void announceDestruction() {
        // No need to do anything
    }

    @Override
    public void announceCreation() {
        // No need to do anything
    }

    @Override
    public String getUser() {
        return "";
    }

    @Override
    public SimpleURI getCompletePath(DataType type, String name) {
        // The path of the data is the same than in the master
        String path = null;
        switch (type) {
            case FILE_T:
                path = Protocol.FILE_URI.getSchema() + Comm.getAppHost().getTempDirPath() + name;
                break;
            case OBJECT_T:
                path = Protocol.OBJECT_URI.getSchema() + name;
                break;
            case PSCO_T:
                path = Protocol.PERSISTENT_URI.getSchema() + name;
                break;
            case EXTERNAL_OBJECT_T:
                path = Protocol.PERSISTENT_URI.getSchema() + name;
                break;
            default:
                return null;
        }

        // Switch path to URI
        return new SimpleURI(path);
    }

    @Override
    public void deleteTemporary() {
    }

    @Override
    public boolean generatePackage() {
        return false;
    }

    @Override
    public void shutdownExecutionManager(ExecutorShutdownListener sl) {
        sl.notifyEnd();
    }

    @Override
    public boolean generateWorkersDebugInfo() {
        return false;
    }

    @Override
    public String getClasspath() {
        // No classpath for services
        return "";
    }

    @Override
    public String getPythonpath() {
        // No pythonpath for services
        return "";
    }

    protected void orderStorageCopy(StorageCopy sc) {
        LOGGER.info("Order PSCO Copy for " + sc.getSourceData().getName());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("LD Target " + sc.getTargetData());
            LOGGER.debug("FROM: " + sc.getPreferredSource());
            LOGGER.debug("TO: " + sc.getTargetLoc());
            LOGGER.debug("MUST PRESERVE: " + sc.mustPreserveSourceData());
        }

        LogicalData source = sc.getSourceData();
        LogicalData target = sc.getTargetData();
        if (target != null) {
            if (target.getName().equals(source.getName())) {
                // The source and target are the same --> IN
                newReplica(sc);
            } else {
                // The source and target are different --> OUT
                newVersion(sc);
            }
        } else {
            // Target doesn't exist yet --> INOUT
            newVersion(sc);
        }
    }

    private void newReplica(StorageCopy sc) {
        String targetHostname = this.getName();
        LogicalData srcLD = sc.getSourceData();
        LogicalData targetLD = sc.getTargetData();

        Debugger.debug("STAGE IN", "Requesting Storage to place a new replica of " + srcLD.getId() + " on " + targetHostname + ")");
        LOGGER.debug("Ask for new Replica of " + srcLD.getName() + " to " + targetHostname);

        // Get the PSCO to replicate
        String pscoId = srcLD.getId();

        // Get the current locations
        List<String> currentLocations = new LinkedList<>();
        try {
            currentLocations = StorageItf.getLocations(pscoId);
        } catch (StorageException se) {
            // Cannot obtain current locations from back-end
            sc.end(DataOperation.OpEndState.OP_FAILED, se);
            return;
        }

        if (!currentLocations.contains(targetHostname)) {
            // Perform replica
            LOGGER.debug("Performing new replica for PSCO " + pscoId);
            try {
                // TODO: WARN New replica is NOT necessary because we can't prefetch data
                // StorageItf.newReplica(pscoId, targetHostname);
            } finally {
            }
        } else {
            LOGGER.debug("PSCO " + pscoId + " already present. Skip replica.");
        }

        // Update information
        sc.setFinalTarget(pscoId);
        if (targetLD != null) {
            targetLD.setId(pscoId);
        }

        // Notify successful end
        sc.end(DataOperation.OpEndState.OP_OK);
    }

    private void newVersion(StorageCopy sc) {

        String targetHostname = this.getName();
        LogicalData srcLD = sc.getSourceData();
        LogicalData targetLD = sc.getTargetData();
        boolean preserveSource = sc.mustPreserveSourceData();

        Debugger.debug("STAGE IN", "Requesting Storage to create a new Version of " + srcLD.getId() + "(" + srcLD.getName() + ")");
        if (DEBUG) {
            LOGGER.debug("Ask for new Version of " + srcLD.getName() + " with id " + srcLD.getId() + " to " + targetHostname
                    + " with must preserve " + preserveSource);
        }

        // Get the PSCOId to replicate
        String pscoId = srcLD.getId();

        // Perform version
        LOGGER.debug("Performing new version for PSCO " + pscoId);
        try {
            String newId = StorageItf.newVersion(pscoId, preserveSource, Comm.getAppHost().getName());
            LOGGER.debug("Register new new version of " + pscoId + " as " + newId);
            sc.setFinalTarget(newId);
            if (targetLD != null) {
                targetLD.setId(newId);
            }
        } catch (Exception e) {
            sc.end(DataOperation.OpEndState.OP_FAILED, e);
            return;
        }

        // Notify successful end
        sc.end(DataOperation.OpEndState.OP_OK);
    }
}
