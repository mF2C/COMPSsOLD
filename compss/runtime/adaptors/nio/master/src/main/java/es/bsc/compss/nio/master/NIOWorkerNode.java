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
package es.bsc.compss.nio.master;

import es.bsc.comm.Connection;
import es.bsc.comm.nio.NIONode;
import es.bsc.compss.comm.Comm;
import es.bsc.compss.exceptions.InitNodeException;
import es.bsc.compss.exceptions.UnstartedNodeException;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.nio.NIOAgent;
import es.bsc.compss.nio.NIOTask;
import es.bsc.compss.nio.NIOTracer;
import es.bsc.compss.nio.NIOURI;
import es.bsc.compss.nio.commands.CommandExecutorShutdown;
import es.bsc.compss.nio.commands.CommandNewTask;
import es.bsc.compss.nio.commands.CommandShutdown;
import es.bsc.compss.nio.commands.Data;
import es.bsc.compss.nio.commands.tracing.CommandGeneratePackage;
import es.bsc.compss.nio.commands.workerFiles.CommandGenerateWorkerDebugFiles;
import es.bsc.compss.nio.dataRequest.DataRequest;
import es.bsc.compss.nio.dataRequest.MasterDataRequest;
import es.bsc.compss.nio.master.configuration.NIOConfiguration;
import es.bsc.compss.types.data.listener.EventListener;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.job.Job;
import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.data.location.DataLocation.Protocol;
import es.bsc.compss.types.COMPSsWorker;
import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.data.Transferable;
import es.bsc.compss.types.data.operation.DataOperation;
import es.bsc.compss.types.data.operation.DataOperation.OpEndState;
import es.bsc.compss.types.data.operation.copy.Copy;
import es.bsc.compss.types.data.operation.copy.DeferredCopy;
import es.bsc.compss.types.data.operation.copy.StorageCopy;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.job.JobListener;
import es.bsc.compss.types.resources.Resource;
import es.bsc.compss.types.resources.ShutdownListener;
import es.bsc.compss.types.resources.ExecutorShutdownListener;
import es.bsc.compss.types.uri.MultiURI;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.util.ErrorManager;

import java.util.LinkedList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import storage.StorageException;
import storage.StorageItf;


public class NIOWorkerNode extends COMPSsWorker {

    protected static final Logger LOGGER = LogManager.getLogger(Loggers.COMM);

    private NIONode node;
    private final NIOConfiguration config;
    private final NIOAdaptor commManager;
    private boolean started = false;
    private WorkerStarter workerStarter;

    @Override
    public String getName() {
        return this.config.getHost();
    }

    public NIOWorkerNode(String name, NIOConfiguration config, NIOAdaptor adaptor) {
        super(name, config);
        this.config = config;
        this.commManager = adaptor;
    }

    @Override
    public void start() throws InitNodeException {
        NIONode n = null;
        try {
        	this.workerStarter = new WorkerStarter(this);
        	synchronized (this.workerStarter) {
        		n = this.workerStarter.startWorker();
        		this.node = n;
        		this.started = true;
        	}
        } catch (InitNodeException e) {
            ErrorManager.warn("There was an exception when initiating worker " + getName() + ".", e);
            throw e;
        }
        
        if (NIOTracer.isActivated()) {
            LOGGER.debug("Initializing NIO tracer " + this.getName());
            NIOTracer.startTracing(this.getName(), this.getUser(), this.getHost(), this.getLimitOfTasks());
        }
    }

    @Override
    public String getUser() {
        return this.config.getUser();
    }

    public String getHost() {
        return this.config.getHost();
    }

    public String getInstallDir() {
        return this.config.getInstallDir();
    }

    public String getBaseWorkingDir() {
        return this.config.getWorkingDir();
    }

    public String getWorkingDir() {
        return this.config.getSandboxWorkingDir();
    }

    public String getAppDir() {
        return this.config.getAppDir();
    }

    public String getLibPath() {
        return this.config.getLibraryPath();
    }

    @Override
    public String getClasspath() {
        return this.config.getClasspath();
    }

    @Override
    public String getPythonpath() { return this.config.getPythonpath(); }

    public int getLimitOfTasks() {
        return this.config.getLimitOfTasks();
    }

    public int getTotalComputingUnits() {
        return this.config.getTotalComputingUnits();
    }

    public int getTotalGPUs() {
        return this.config.getTotalGPUComputingUnits();
    }

    public int getTotalFPGAs() {
        return this.config.getTotalFPGAComputingUnits();
    }

    public NIOConfiguration getConfiguration() {
        return this.config;
    }

    @Override
    public void setInternalURI(MultiURI uri) throws UnstartedNodeException {
        if (node == null) {
            throw new UnstartedNodeException();
        }
        NIOURI nio = new NIOURI(node, uri.getPath());
        uri.setInternalURI(NIOAdaptor.ID, nio);
    }

    @Override
    public Job<?> newJob(int taskId, TaskDescription taskParams, Implementation impl, Resource res, List<String> slaveWorkersNodeNames,
            JobListener listener) {

        return new NIOJob(taskId, taskParams, impl, res, slaveWorkersNodeNames, listener);
    }

    @Override
    public void stop(ShutdownListener sl) {
    	//synchronized (this.workerStarter) {

    		if (workerStarter != null) {
    			workerStarter.setToStop();
    			LOGGER.debug("Worker " + this.getName() + " set to be stopped.");
    			synchronized (this.workerStarter) {
    				if (started) {
    					LOGGER.debug("Shutting down " + this.getName());
    					if (node == null) {
    						sl.notifyFailure(new UnstartedNodeException());
    						LOGGER.error("Shutdown has failed");
    					}
    					Connection c = NIOAgent.getTransferManager().startConnection(node);
    					commManager.shuttingDown(this, c, sl);
    					CommandShutdown cmd = new CommandShutdown(null, null);
    					c.sendCommand(cmd);
    					c.receive();
    					c.finishConnection();
    				} else {
    					LOGGER.debug("Worker " + this.getName() + " has not started.");
    					sl.notifyEnd();
    				}
    			}
    		} else {
    			LOGGER.debug("Worker " + this.getName() + " has not been created.");
    			sl.notifyEnd();
    		}

    	//}
    }

    @Override
    public void shutdownExecutionManager(ExecutorShutdownListener esl) {
        if (started) {
            LOGGER.debug("Shutting down execution manager " + this.getName());
            if (node == null) {
                esl.notifyFailure(new UnstartedNodeException());
                LOGGER.error("Shutdown execution manager has failed");
            }
            Connection c = NIOAgent.getTransferManager().startConnection(node);
            commManager.shuttingDownEM(this, c, esl);

            CommandExecutorShutdown cmd = new CommandExecutorShutdown(null);
            c.sendCommand(cmd);

            c.receive();
            c.finishConnection();
        } else {
            LOGGER.debug("Worker " + this.getName() + " has not started. Considering execution manager stopped");
            esl.notifyEnd();
        }
    }

    @Override
    public void sendData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason,
            EventListener listener) {

        if (target.getHosts().contains(Comm.getAppHost())) {
            // Request to master

            // Order petition directly
            if (tgtData != null) {
                MultiURI u = ld.alreadyAvailable(Comm.getAppHost());
                if (u != null) { // Already present at the master
                    reason.setDataTarget(u.getPath());
                    listener.notifyEnd(null);
                    return;
                }
            }

            Copy c = new DeferredCopy(ld, null, target, tgtData, reason, listener);
            Data d = new Data(ld);
            if (source != null) {
                for (MultiURI uri : source.getURIs()) {
                    try {
                        NIOURI nURI = (NIOURI) uri.getInternalURI(NIOAdaptor.ID);
                        if (nURI != null) {
                            d.getSources().add(nURI);
                        }
                    } catch (UnstartedNodeException une) {
                        // Ignore internal URI
                    }
                }
            }

            String path = target.getURIInHost(Comm.getAppHost()).getPath();
            c.setFinalTarget(path);
            ld.startCopy(c, c.getTargetLoc());
            DataRequest dr = new MasterDataRequest(c, reason.getType(), d, path);
            commManager.addTransferRequest(dr);
            commManager.requestTransfers();
        } else {
            // Request to any other
            orderCopy(new DeferredCopy(ld, source, target, tgtData, reason, listener));
        }
    }

    @Override
    public void obtainData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason,
            EventListener listener) {

        if (ld == null) {
            return;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Obtain Data " + ld.getName() + " as " + target);
        }

        // If it is a PSCO -> Order new StorageCopy
        if (ld.getId() != null) {
            orderStorageCopy(new StorageCopy(ld, source, target, tgtData, reason, listener));
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Ordering deferred copy " + ld.getName());
            }
            orderCopy(new DeferredCopy(ld, source, target, tgtData, reason, listener));
        }
    }

    private void orderStorageCopy(StorageCopy sc) {
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

        LOGGER.debug("Ask for new Replica of " + srcLD.getName() + " to " + targetHostname);

        // Get the PSCO to replicate
        String pscoId = srcLD.getId();

        // Get the current locations
        List<String> currentLocations = new LinkedList<>();
        try {
            currentLocations = StorageItf.getLocations(pscoId);
        } catch (StorageException se) {
            // Cannot obtain current locations from back-end
            sc.end(OpEndState.OP_FAILED, se);
            return;
        }

        if (!currentLocations.contains(targetHostname)) {
            // Perform replica
            LOGGER.debug("Performing new replica for PSCO " + pscoId);
            if (NIOTracer.isActivated()) {
                NIOTracer.emitEvent(NIOTracer.Event.STORAGE_NEWREPLICA.getId(), NIOTracer.Event.STORAGE_NEWREPLICA.getType());
            }
            try {
                // TODO: WARN New replica is NOT necessary because we can't prefetch data
                // StorageItf.newReplica(pscoId, targetHostname);
            } finally {
                if (NIOTracer.isActivated()) {
                    NIOTracer.emitEvent(NIOTracer.EVENT_END, NIOTracer.Event.STORAGE_NEWREPLICA.getType());
                }
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
        sc.end(OpEndState.OP_OK);
    }

    private void newVersion(StorageCopy sc) {
        String targetHostname = this.getName();
        LogicalData srcLD = sc.getSourceData();
        LogicalData targetLD = sc.getTargetData();
        boolean preserveSource = sc.mustPreserveSourceData();

        if (DEBUG) {
            LOGGER.debug("Ask for new Version of " + srcLD.getName() + " with id " + srcLD.getId() + " to " + targetHostname
                    + " with must preserve " + preserveSource);
        }

        // Get the PSCOId to replicate
        String pscoId = srcLD.getId();

        // Perform version
        LOGGER.debug("Performing new version for PSCO " + pscoId);
        if (NIOTracer.isActivated()) {
            NIOTracer.emitEvent(NIOTracer.Event.STORAGE_NEWVERSION.getId(), NIOTracer.Event.STORAGE_NEWVERSION.getType());
        }
        try {
            String newId = StorageItf.newVersion(pscoId, preserveSource, targetHostname);
            LOGGER.debug("Register new new version of " + pscoId + " as " + newId);
            sc.setFinalTarget(newId);
            if (targetLD != null) {
                targetLD.setId(newId);
            }
        } catch (Exception e) {
            sc.end(OpEndState.OP_FAILED, e);
            return;
        } finally {
            if (NIOTracer.isActivated()) {
                NIOTracer.emitEvent(NIOTracer.EVENT_END, NIOTracer.Event.STORAGE_NEWVERSION.getType());
            }
        }

        // Notify successful end
        sc.end(OpEndState.OP_OK);
    }

    private void orderCopy(DeferredCopy c) {
        LOGGER.info("Order Copy for " + c.getSourceData());

        Resource tgtRes = ((LinkedList<Resource>) c.getTargetLoc().getHosts()).getFirst();
        LogicalData ld = c.getSourceData();
        String path;
        synchronized (ld) {
            if (c.getTargetData() != null) {
                MultiURI u = ld.alreadyAvailable(tgtRes);
                if (u != null) {
                    path = u.getPath();
                } else {
                    path = c.getTargetLoc().getURIInHost(tgtRes).getPath();
                }
            } else {
                path = c.getTargetLoc().getURIInHost(tgtRes).getPath();
            }
            c.setProposedSource(new Data(ld));
            LOGGER.debug("Setting final target in deferred copy " + path);
            c.setFinalTarget(path);
            // TODO: MISSING CHECK IF FILE IS ALREADY BEEN COPIED IN A SHARED LOCATION
            ld.startCopy(c, c.getTargetLoc());
            commManager.registerCopy(c);
        }
        c.end(DataOperation.OpEndState.OP_OK);
    }

    @Override
    public void updateTaskCount(int processorCoreCount) {
        // No need to do nothing
    }

    @Override
    public void announceDestruction() {
        // No need to do nothing
    }

    @Override
    public void announceCreation() {
        // No need to do nothing
    }

    @Override
    public SimpleURI getCompletePath(DataType type, String name) {
        String path = null;
        switch (type) {
            case FILE_T:
                path = Protocol.FILE_URI.getSchema() + config.getSandboxWorkingDir() + name;
                break;
            case OBJECT_T:
                path = Protocol.OBJECT_URI.getSchema() + name;
                break;
            case PSCO_T:
                // Search for the PSCO id
                String id = Comm.getData(name).getId();
                path = Protocol.PERSISTENT_URI.getSchema() + id;
                break;
            case EXTERNAL_OBJECT_T:
                // The value of the registered object in the runtime is the PSCO Id
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
        // This is only used to clean the master
        // Nothing to do
    }

    @Override
    public boolean generatePackage() {
        if (started) {
            LOGGER.debug("Sending command to generated tracing package for " + this.getHost());
            if (node == null) {
                LOGGER.error("ERROR: Package generation for " + this.getHost() + " has failed.");
                return false;
            } else {

                Connection c = NIOAgent.getTransferManager().startConnection(node);
                CommandGeneratePackage cmd = new CommandGeneratePackage();
                c.sendCommand(cmd);
                c.receive();
                c.finishConnection();
                commManager.waitUntilTracingPackageGenerated();
                LOGGER.debug("Tracing Package generated");
                return true;
            }
        } else {
            LOGGER.debug("Worker " + this.getHost() + " not started. No tracing package generated");
            return false;
        }

    }

    @Override
    public boolean generateWorkersDebugInfo() {
        if (started) {
            LOGGER.debug("Sending command to generate worker debug files for " + this.getHost());
            if (node == null) {
                LOGGER.error("Worker debug files generation has failed.");
            }

            Connection c = NIOAgent.getTransferManager().startConnection(node);
            CommandGenerateWorkerDebugFiles cmd = new CommandGenerateWorkerDebugFiles();
            c.sendCommand(cmd);
            c.receive();
            c.finishConnection();

            commManager.waitUntilWorkersDebugInfoGenerated();
            LOGGER.debug("Worker debug files generated");
            return true;
        } else {
            LOGGER.debug("Worker debug files not generated because worker was not started");
            return false;
        }
    }

    public void submitTask(NIOJob job, List<String> obsolete) throws UnstartedNodeException {
        if (node == null) {
            throw new UnstartedNodeException();
        }
        NIOTask t = job.prepareJob();
        CommandNewTask cmd = new CommandNewTask(t, obsolete);
        Connection c = NIOAgent.getTransferManager().startConnection(node);
        c.sendCommand(cmd);
        c.finishConnection();
    }

    public void setStarted(boolean b) {
        started = b;
    }

}
