package integratedtoolkit.nio.master;

import es.bsc.comm.Connection;
import es.bsc.comm.nio.NIONode;
import integratedtoolkit.api.ITExecution;
import integratedtoolkit.comm.Comm;
import integratedtoolkit.exceptions.UnstartedNodeException;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.data.location.DataLocation;
import integratedtoolkit.types.job.Job;
import integratedtoolkit.types.data.LogicalData;
import integratedtoolkit.types.data.location.URI;
import integratedtoolkit.nio.NIOAgent;
import integratedtoolkit.nio.NIOAgent.DataRequest.MasterDataRequest;
import integratedtoolkit.nio.NIOTask;
import integratedtoolkit.nio.NIOTracer;
import integratedtoolkit.nio.NIOURI;
import integratedtoolkit.nio.commands.CommandNewTask;
import integratedtoolkit.nio.commands.CommandShutdown;
import integratedtoolkit.nio.commands.Data;
import integratedtoolkit.nio.commands.tracing.CommandGeneratePackage;
import integratedtoolkit.nio.commands.workerFiles.CommandGenerateWorkerDebugFiles;
import integratedtoolkit.nio.master.configuration.NIOConfiguration;
import integratedtoolkit.types.COMPSsWorker;
import integratedtoolkit.types.Implementation;
import integratedtoolkit.types.TaskParams;
import integratedtoolkit.types.data.Transferable;
import integratedtoolkit.types.data.operation.Copy;
import integratedtoolkit.types.data.operation.Copy.DeferredCopy;
import integratedtoolkit.types.data.operation.DataOperation;
import integratedtoolkit.types.data.operation.DataOperation.EventListener;
import integratedtoolkit.types.job.Job.JobListener;
import integratedtoolkit.types.resources.Resource;
import integratedtoolkit.types.resources.ShutdownListener;
import integratedtoolkit.util.ErrorManager;
import java.util.LinkedList;

import org.apache.log4j.Logger;

public class NIOWorkerNode extends COMPSsWorker {

    protected static final Logger logger = Logger.getLogger(Loggers.COMM);

    private static final int MAX_RETRIES = 5;

    private NIONode node;
    private final NIOConfiguration config;
    private final NIOAdaptor commManager;

    @Override
    public String getName() {
        return config.getHost();
    }

    public NIOWorkerNode(String name, NIOConfiguration config, NIOAdaptor adaptor) {
        super(name, config);
        this.config = config;
        this.commManager = adaptor;
    }

    @Override
    public void start() throws Exception {
        NIONode n = null;
        try {
            n = new WorkerStarter(this).startWorker();
        } catch (Exception e) {
            ErrorManager.warn("There was an error when initiating worker " + getName() + ".", e);
            throw e;
        }
        this.node = n;

        if (tracing) {
            logger.debug("Initializing NIO tracer " + this.getName());
            NIOTracer.startTracing(this.getName(), this.getUser(), this.getHost(), this.getLimitOfTasks());
        }
    }

    public String getUser() {
        return config.getUser();
    }

    public String getHost() {
        return config.getHost();
    }

    public String getInstallDir() {
        return config.getInstallDir();
    }

    public String getBaseWorkingDir() {
        return config.getWorkingDir();
    }

    public String getWorkingDir() {
        return config.getSandboxWorkingDir();
    }

    public String getAppDir() {
        return config.getAppDir();
    }

    public String getLibPath() {
        return config.getLibraryPath();
    }

    public int getLimitOfTasks() {
        return config.getLimitOfTasks();
    }

    public NIOConfiguration getConfiguration() {
        return this.config;
    }

    @Override
    public void setInternalURI(URI uri) throws UnstartedNodeException {
        if (node == null) {
            throw new UnstartedNodeException();
        }
        NIOURI nio = new NIOURI(node, uri.getPath());
        uri.setInternalURI(NIOAdaptor.ID, nio);
    }

    @Override
    public Job<?> newJob(int taskId, TaskParams taskParams, Implementation<?> impl, Resource res, JobListener listener) {
        return new NIOJob(taskId, taskParams, impl, res, listener);
    }

    @Override
    public void stop(ShutdownListener sl) {
        logger.info("Shutting down " + this.getName());
        if (node == null) {
            sl.notifyFailure(new Exception());
            logger.error("Shutdown has failed");
        }
        Connection c = NIOAgent.tm.startConnection(node);
        commManager.shuttingDown(this, c, sl);
        CommandShutdown cmd = new CommandShutdown(null, null);
        c.sendCommand(cmd);

        c.receive();
        c.finishConnection();
    }

    @Override
    public void sendData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason, EventListener listener) {
        if (target.getHosts().contains(Comm.appHost)) {//Si es pel master
            //Ordenar la petició directament
            if (tgtData != null) {
                URI u;
                if ((u = ld.alreadyAvailable(Comm.appHost)) != null) {//Already present at the master
                    reason.setDataTarget(u.getPath());
                    listener.notifyEnd(null);
                    return;
                }
            }

            Copy c = new DeferredCopy(ld, null, target, tgtData, reason, listener);
            Data d = new Data(ld);
            if (source != null) {
                for (URI uri : source.getURIs()) {
                    try {
                        NIOURI nURI = (NIOURI) uri.getInternalURI(NIOAdaptor.ID);
                        if (nURI != null) {
                            d.getSources().add(nURI);
                        }
                    } catch (UnstartedNodeException une) {
                        //Ignore internal URI.
                    }
                }
            }
            String path = target.getPath();
            ld.startCopy(c, c.getTargetLoc());
            NIOAgent.DataRequest dr = new MasterDataRequest(c, reason.getType(), d, path);
            commManager.addTransferRequest(dr);
            commManager.requestTransfers();
        } else {
            orderCopy(new DeferredCopy(ld, source, target, tgtData, reason, listener));
        }
    }

    @Override
    public void obtainData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason, EventListener listener) {
        orderCopy(new DeferredCopy(ld, source, target, tgtData, reason, listener));
    }

    private void orderCopy(DeferredCopy c) {
        Resource tgtRes = c.getTargetLoc().getHosts().getFirst();
        LogicalData ld = c.getSourceData();
        String path;
        synchronized (ld) {
            URI u;

            if ((c.getTargetData() != null) && (u = ld.alreadyAvailable(tgtRes)) != null) {
                path = u.getPath();
            } else {
                path = c.getTargetLoc().getPath();
            }
            //TODO: MISSING CHECK IF FILE IS ALREADY BEEN COPIED IN A SHARED LOCATION    
            ld.startCopy(c, c.getTargetLoc());
            commManager.registerCopy(c);
        }
        c.setProposedSource(new Data(ld));
        c.setFinalTarget(path);
        c.end(DataOperation.OpEndState.OP_OK);
    }

    @Override
    public void updateTaskCount(int processorCoreCount) {
    }

    @Override
    public void announceDestruction() {
        //No need to do nothing
    }

    @Override
    public void announceCreation() {
        //No need to do nothing
    }

    @Override
    public String getCompletePath(ITExecution.ParamType type, String name) {
        switch (type) {
            case FILE_T:
                return config.getSandboxWorkingDir() + name;
            case OBJECT_T:
            case SCO_T:
            case PSCO_T:
                return name;
            default:
                return null;
        }
    }

    @Override
    public void deleteTemporary() {
        //TODO NIOWorkerNode hauria d'eliminar " + workingDir + " a " + getName());
    }

    @Override
    public void generatePackage() {
        logger.debug("Sending command to generated tracing package for " + this.getHost());
        if (node == null) {
            logger.error("Package generation has failed.");
        }

        Connection c = NIOAgent.tm.startConnection(node);
        CommandGeneratePackage cmd = new CommandGeneratePackage(this.getHost(), this.getInstallDir(), this.getWorkingDir(), this.getName());
        c.sendCommand(cmd);
        c.receive();
        c.finishConnection();

        commManager.waitUntilTracingPackageGenerated();
        logger.debug("Tracing Package generated");
    }

    @Override
    public void generateWorkersDebugInfo() {
        logger.debug("Sending command to generate worker debug files for " + this.getHost());
        if (node == null) {
            logger.error("Worker debug files generation has failed.");
        }
        Connection c = NIOAgent.tm.startConnection(node);
        CommandGenerateWorkerDebugFiles cmd = new CommandGenerateWorkerDebugFiles(this.getHost(), this.getInstallDir(), this.getWorkingDir(), this.getName());
        c.sendCommand(cmd);

        c.receive();
        c.finishConnection();
        logger.debug("Worker debug files generated");
    }

    public void submitTask(NIOJob job, LinkedList<String> obsolete) throws UnstartedNodeException {
        if (node == null) {
            throw new UnstartedNodeException();
        }
        NIOTask t = job.prepareJob();
        CommandNewTask cmd = new CommandNewTask(t, obsolete);
        Connection c = NIOAgent.tm.startConnection(node);
        c.sendCommand(cmd);
        c.finishConnection();
    }

}
