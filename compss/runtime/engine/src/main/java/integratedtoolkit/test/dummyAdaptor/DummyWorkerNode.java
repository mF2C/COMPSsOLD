package integratedtoolkit.test.dummyAdaptor;

import integratedtoolkit.api.COMPSsRuntime.DataType;
import integratedtoolkit.types.COMPSsWorker;
import integratedtoolkit.types.Implementation;
import integratedtoolkit.types.TaskParams;
import integratedtoolkit.types.data.LogicalData;
import integratedtoolkit.types.data.Transferable;
import integratedtoolkit.types.data.location.DataLocation;
import integratedtoolkit.types.data.location.URI;
import integratedtoolkit.types.data.operation.DataOperation.EventListener;
import integratedtoolkit.types.job.Job;
import integratedtoolkit.types.job.Job.JobListener;
import integratedtoolkit.types.resources.Resource;
import integratedtoolkit.types.resources.ShutdownListener;
import integratedtoolkit.types.resources.configuration.MethodConfiguration;


public class DummyWorkerNode extends COMPSsWorker {
	
	private final String name;

    public DummyWorkerNode(String name, MethodConfiguration config) {
        super(name, config);
        this.name = name;
    }
    
    @Override
    public String getName() {
    	return name;
    }

    @Override
    public void start() throws Exception {
    }
    
    @Override
    public String getUser() {
        return this.name;
    }
    
	@Override
	public String getClasspath() {
		return this.name;
	}

	@Override
	public String getPythonpath() {
		return this.name;
	}

    @Override
    public Job<?> newJob(int taskId, TaskParams taskParams, Implementation<?> impl, Resource res, JobListener listener) {
        return null;
    }

    @Override
    public void setInternalURI(URI uri) {
    }

    @Override
    public void stop(ShutdownListener sl) {
    }

    @Override
    public void sendData(LogicalData srcData, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason, EventListener listener) {
    }

    @Override
    public void obtainData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason, EventListener listener) {
    }

    @Override
    public void updateTaskCount(int processorCoreCount) {
    }

    @Override
    public void announceCreation() throws Exception {
    }

    @Override
    public void announceDestruction() throws Exception {
    }

    @Override
    public String getCompletePath(DataType type, String name) {
    	return null;
    }

    @Override
    public void deleteTemporary() {
    }

    @Override
    public void generatePackage() {
    }

    @Override
    public void generateWorkersDebugInfo() {
    }
    
}