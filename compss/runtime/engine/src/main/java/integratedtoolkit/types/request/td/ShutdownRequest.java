package integratedtoolkit.types.request.td;

import integratedtoolkit.components.ResourceUser.WorkloadStatus;
import integratedtoolkit.components.impl.JobManager;
import integratedtoolkit.components.impl.TaskScheduler;
import integratedtoolkit.types.request.exceptions.ShutdownException;
import integratedtoolkit.util.CoreManager;
import integratedtoolkit.util.ResourceManager;

import java.util.concurrent.Semaphore;


/**
 * This class represents a notification to end the execution
 */
public class ShutdownRequest extends TDRequest {

    /**
     * Semaphore where to synchronize until the operation is done
     */
    private Semaphore semaphore;

    /**
     * Constructs a new ShutdownRequest
     *
     */
    public ShutdownRequest(Semaphore sem) {
        this.semaphore = sem;
    }

    /**
     * Returns the semaphore where to synchronize until the object can be read
     *
     * @return the semaphore where to synchronize until the object can be read
     */
    public Semaphore getSemaphore() {
        return semaphore;
    }

    /**
     * Sets the semaphore where to synchronize until the requested object can be
     * read
     *
     * @param sem the semaphore where to synchronize until the requested object
     * can be read
     */
    public void setSemaphore(Semaphore sem) {
        this.semaphore = sem;
    }

    @Override
    public TDRequestType getRequestType() {
        return TDRequestType.SHUTDOWN;
    }

    @Override
    public void process(TaskScheduler ts, JobManager jm) throws ShutdownException {
        //ts.shutdown();
    	logger.debug("Processing ShutdownRequest request...");
        jm.shutdown();
        
        // Print core state
        WorkloadStatus status = new WorkloadStatus(CoreManager.getCoreCount());
        ts.getWorkloadState(status);
        ResourceManager.stopNodes(status);
        semaphore.release();
        throw new ShutdownException();
    }
}
