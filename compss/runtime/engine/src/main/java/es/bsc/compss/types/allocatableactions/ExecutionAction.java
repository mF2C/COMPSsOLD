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
package es.bsc.compss.types.allocatableactions;

import es.bsc.compss.api.COMPSsRuntime.TaskMonitor;
import es.bsc.compss.comm.Comm;
import es.bsc.compss.components.impl.ResourceScheduler;
import es.bsc.compss.components.impl.TaskProducer;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.Task;
import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.Task.TaskState;
import es.bsc.compss.types.annotations.parameter.Direction;
import es.bsc.compss.scheduler.exceptions.BlockedActionException;
import es.bsc.compss.scheduler.exceptions.FailedActionException;
import es.bsc.compss.scheduler.exceptions.UnassignedActionException;
import es.bsc.compss.scheduler.types.ActionOrchestrator;
import es.bsc.compss.scheduler.types.AllocatableAction;
import es.bsc.compss.scheduler.types.SchedulingInformation;
import es.bsc.compss.scheduler.types.Score;
import es.bsc.compss.types.data.DataAccessId;
import es.bsc.compss.types.data.DataInstanceId;
import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.data.operation.JobTransfersListener;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.implementations.Implementation.TaskType;
import es.bsc.compss.types.job.Job;
import es.bsc.compss.types.job.JobListener.JobEndStatus;
import es.bsc.compss.types.parameter.DependencyParameter;
import es.bsc.compss.types.parameter.Parameter;
import es.bsc.compss.types.job.JobStatusListener;
import es.bsc.compss.types.resources.Worker;
import es.bsc.compss.types.resources.WorkerResourceDescription;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.util.CoreManager;
import es.bsc.compss.util.ErrorManager;
import es.bsc.compss.util.JobDispatcher;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class ExecutionAction extends AllocatableAction {

    // Fault tolerance parameters
    private static final int TRANSFER_CHANCES = 2;
    private static final int SUBMISSION_CHANCES = 2;
    private static final int SCHEDULING_CHANCES = 2;

    // LOGGER
    private static final Logger JOB_LOGGER = LogManager.getLogger(Loggers.JM_COMP);

    // Execution Info
    protected final TaskProducer producer;
    protected final Task task;
    private final LinkedList<Integer> jobs;
    private int transferErrors = 0;
    protected int executionErrors = 0;

    /**
     * Creates a new execution action
     *
     * @param schedulingInformation
     * @param orchestrator
     * @param producer
     * @param task
     */
    public ExecutionAction(SchedulingInformation schedulingInformation, ActionOrchestrator orchestrator, TaskProducer producer, Task task) {

        super(schedulingInformation, orchestrator);

        this.producer = producer;
        this.task = task;
        this.jobs = new LinkedList<>();
        this.transferErrors = 0;
        this.executionErrors = 0;

        // Add execution to task
        this.task.addExecution(this);

        // Register data dependencies events
        for (Task predecessor : this.task.getPredecessors()) {
            for (ExecutionAction e : predecessor.getExecutions()) {
                if (e != null && e.isPending()) {
                    addDataPredecessor(e);
                }
            }
        }

        // Scheduling constraints
        // Restricted resource
        Task resourceConstraintTask = this.task.getEnforcingTask();
        if (resourceConstraintTask != null) {
            for (ExecutionAction e : resourceConstraintTask.getExecutions()) {
                addResourceConstraint(e);
            }
        }
    }

    /**
     * Returns the associated task
     *
     * @return
     */
    public final Task getTask() {
        return this.task;
    }

    /*
     * ***************************************************************************************************************
     * EXECUTION AND LIFECYCLE MANAGEMENT
     * ***************************************************************************************************************
     */
    @Override
    public boolean isToReserveResources() {
        return true;
    }

    @Override
    public boolean isToReleaseResources() {
        return true;
    }

    @Override
    protected void doAction() {
        JOB_LOGGER.info("Ordering transfers to " + getAssignedResource() + " to run task: " + task.getId());
        transferErrors = 0;
        executionErrors = 0;
        TaskMonitor monitor = task.getTaskMonitor();
        if (monitor != null) {
            monitor.onSubmission();
        }
        doInputTransfers();
    }

    private final void doInputTransfers() {
        JobTransfersListener listener = new JobTransfersListener(this);
        transferInputData(listener);
        listener.enable();
    }

    private final void transferInputData(JobTransfersListener listener) {
        TaskDescription taskDescription = task.getTaskDescription();
        for (Parameter p : taskDescription.getParameters()) {
            JOB_LOGGER.debug("    * " + p);
            if (p instanceof DependencyParameter) {
                DependencyParameter dp = (DependencyParameter) p;
                switch (taskDescription.getType()) {
                    case METHOD:
                        transferJobData(dp, listener);
                        break;
                    case SERVICE:
                        if (dp.getDirection() != Direction.INOUT) {
                            // For services we only transfer IN parameters because the only
                            // parameter that can be INOUT is the target
                            transferJobData(dp, listener);
                        }
                        break;
                }
            }
        }
    }

    // Private method that performs data transfers
    private final void transferJobData(DependencyParameter param, JobTransfersListener listener) {
        Worker<? extends WorkerResourceDescription> w = getAssignedResource().getResource();
        DataAccessId access = param.getDataAccessId();
        if (access instanceof DataAccessId.WAccessId) {
            String tgtName = ((DataAccessId.WAccessId) access).getWrittenDataInstance().getRenaming();
            if (DEBUG) {
                JOB_LOGGER.debug("Setting data target job transfer: " + w.getCompleteRemotePath(param.getType(), tgtName));
            }
            param.setDataTarget(w.getCompleteRemotePath(param.getType(), tgtName).getPath());

            return;
        }

        listener.addOperation();
        if (access instanceof DataAccessId.RAccessId) {
            String srcName = ((DataAccessId.RAccessId) access).getReadDataInstance().getRenaming();
            w.getData(srcName, srcName, param, listener);
        } else {
            // Is RWAccess
            String srcName = ((DataAccessId.RWAccessId) access).getReadDataInstance().getRenaming();
            String tgtName = ((DataAccessId.RWAccessId) access).getWrittenDataInstance().getRenaming();
            w.getData(srcName, tgtName, (LogicalData) null, param, listener);
        }
    }

    /*
     * ***************************************************************************************************************
     * EXECUTED SUPPORTING THREAD ON JOB_TRANSFERS_LISTENER
     * ***************************************************************************************************************
     */
    /**
     * Code executed after some input transfers have failed
     *
     * @param failedtransfers
     */
    public final void failedTransfers(int failedtransfers) {
        JOB_LOGGER.debug("Received a notification for the transfers for task " + task.getId() + " with state FAILED");
        ++transferErrors;
        if (transferErrors < TRANSFER_CHANCES) {
            JOB_LOGGER.debug("Resubmitting input files for task " + task.getId() + " to host " + getAssignedResource().getName() + " since "
                    + failedtransfers + " transfers failed.");

            doInputTransfers();
        } else {
            ErrorManager
                    .warn("Transfers for running task " + task.getId() + " on worker " + getAssignedResource().getName() + " have failed.");
            this.notifyError();
        }
    }

    /**
     * Code executed when all transfers have successed
     *
     * @param transferGroupId
     */
    public final void doSubmit(int transferGroupId) {
        JOB_LOGGER.debug("Received a notification for the transfers of task " + task.getId() + " with state DONE");
        JobStatusListener listener = new JobStatusListener(this);
        Job<?> job = submitJob(transferGroupId, listener);

        // Register job
        jobs.add(job.getJobId());
        JOB_LOGGER.info((this.getExecutingResources().size() > 1 ? "Rescheduled" : "New") + " Job " + job.getJobId() + " (Task: "
                + task.getId() + ")");
        JOB_LOGGER.info("  * Method name: " + task.getTaskDescription().getName());
        JOB_LOGGER.info("  * Target host: " + this.getAssignedResource().getName());

        profile.start();
        JobDispatcher.dispatch(job);
    }

    protected Job<?> submitJob(int transferGroupId, JobStatusListener listener) {
        // Create job
        if (DEBUG) {
            LOGGER.debug(this.toString() + " starts job creation");
        }
        Worker<? extends WorkerResourceDescription> w = getAssignedResource().getResource();
        List<String> slaveNames = new ArrayList<>(); // No salves
        Job<?> job = w.newJob(this.task.getId(), this.task.getTaskDescription(), this.getAssignedImplementation(), slaveNames, listener);
        job.setTransferGroupId(transferGroupId);
        job.setHistory(Job.JobHistory.NEW);

        return job;
    }

    /**
     * Code executed when the job execution has failed
     *
     * @param job
     * @param endStatus
     */
    public final void failedJob(Job<?> job, JobEndStatus endStatus) {
        profile.end();

        int jobId = job.getJobId();
        JOB_LOGGER.error("Received a notification for job " + jobId + " with state FAILED");
        ++executionErrors;
        if (transferErrors + executionErrors < SUBMISSION_CHANCES) {
            JOB_LOGGER.error("Job " + job.getJobId() + " for running task " + task.getId() + " on worker "
                    + this.getAssignedResource().getName() + " has failed; resubmitting task to the same worker.");
            ErrorManager.warn("Job " + job.getJobId() + " for running task " + task.getId() + " on worker "
                    + this.getAssignedResource().getName() + " has failed; resubmitting task to the same worker.");
            job.setHistory(Job.JobHistory.RESUBMITTED);
            profile.start();
            JobDispatcher.dispatch(job);
        } else {
            notifyError();
        }
    }

    /**
     * Code executed when the job execution has been completed
     *
     * @param job
     */
    public final void completedJob(Job<?> job) {
        // End profile
        profile.end();
        // Notify end
        int jobId = job.getJobId();
        JOB_LOGGER.info(
                "Received a notification for job " + jobId + " with state OK (avg. duration: " + profile.getAverageExecutionTime() + ")");
        // Job finished, update info about the generated/updated data
        doOutputTransfers(job);
        // Notify completion
        notifyCompleted();
    }

    private final void doOutputTransfers(Job<?> job) {
        // Job finished, update info about the generated/updated data
        Worker<? extends WorkerResourceDescription> w = this.getAssignedResource().getResource();
        int paramId = -1;
        for (Parameter p : job.getTaskParams().getParameters()) {
            paramId++;
            if (p instanceof DependencyParameter) {
                // OUT or INOUT: we must tell the FTM about the
                // generated/updated datum
                DataInstanceId dId = null;
                DependencyParameter dp = (DependencyParameter) p;
                switch (p.getDirection()) {
                    case IN:
                        // FTM already knows about this datum
                        continue;
                    case OUT:
                        dId = ((DataAccessId.WAccessId) dp.getDataAccessId()).getWrittenDataInstance();
                        break;
                    case INOUT:
                        dId = ((DataAccessId.RWAccessId) dp.getDataAccessId()).getWrittenDataInstance();
                        if (job.getType() == TaskType.SERVICE) {
                            continue;
                        }
                        break;
                }
                String name = dId.getRenaming();
                if (job.getType() == TaskType.METHOD) {
                    String targetProtocol = null;
                    switch (dp.getType()) {
                        case FILE_T:
                            targetProtocol = DataLocation.Protocol.FILE_URI.getSchema();
                            break;
                        case OBJECT_T:
                            targetProtocol = DataLocation.Protocol.OBJECT_URI.getSchema();
                            break;
                        case PSCO_T:
                            targetProtocol = DataLocation.Protocol.PERSISTENT_URI.getSchema();
                            break;
                        case EXTERNAL_OBJECT_T:
                            // Its value is the PSCO Id
                            targetProtocol = DataLocation.Protocol.PERSISTENT_URI.getSchema();
                            break;
                        default:
                            // Should never reach this point because only
                            // DependencyParameter types are treated
                            // Ask for any_uri just in case
                            targetProtocol = DataLocation.Protocol.ANY_URI.getSchema();
                            break;
                    }
                    DataLocation outLoc = null;
                    try {
                        SimpleURI targetURI = new SimpleURI(targetProtocol + dp.getDataTarget());
                        outLoc = DataLocation.createLocation(w, targetURI);
                    } catch (Exception e) {
                        ErrorManager.error(DataLocation.ERROR_INVALID_LOCATION + " " + dp.getDataTarget(), e);
                    }
                    Comm.registerLocation(name, outLoc);
                    TaskMonitor monitor = task.getTaskMonitor();
                    if (monitor != null) {
                        monitor.valueGenerated(paramId, dp.getType(), outLoc);
                    }
                } else {
                    // Service
                    Object value = job.getReturnValue();
                    Comm.registerValue(name, value);
                    TaskMonitor monitor = task.getTaskMonitor();
                    if (monitor != null) {
                        monitor.valueGenerated(paramId, dp.getType(), value);
                    }
                }
            }
        }
    }

    /*
     * ***************************************************************************************************************
     * EXECUTION TRIGGERS
     * ***************************************************************************************************************
     */
    @Override
    protected void doCompleted() {
        // Profile the resource
        this.getAssignedResource().profiledExecution(this.getAssignedImplementation(), profile);

        TaskMonitor monitor = task.getTaskMonitor();
        if (monitor != null) {
            monitor.onSuccesfulExecution();
        }

        // Decrease the execution counter and set the task as finished and notify the producer
        task.decreaseExecutionCount();
        task.setStatus(TaskState.FINISHED);
        producer.notifyTaskEnd(task);
    }

    @Override
    protected void doError() throws FailedActionException {
        if (this.getExecutingResources().size() >= SCHEDULING_CHANCES) {
            LOGGER.warn("Task " + task.getId() + " has already been rescheduled; notifying task failure.");
            ErrorManager.warn("Task " + task.getId() + " has already been rescheduled; notifying task failure.");
            throw new FailedActionException();
        } else {
            ErrorManager.warn("Task " + task.getId() + " execution on worker " + this.getAssignedResource().getName()
                    + " has failed; rescheduling task execution. (changing worker)");
            LOGGER.warn("Task " + task.getId() + " execution on worker " + this.getAssignedResource().getName()
                    + " has failed; rescheduling task execution. (changing worker)");
        }
        TaskMonitor monitor = task.getTaskMonitor();
        if (monitor != null) {
            monitor.onErrorExecution();
        }
    }

    @Override
    protected void doFailed() {
        // Failed message
        String taskName = task.getTaskDescription().getName();
        StringBuilder sb = new StringBuilder();
        sb.append("Task '").append(taskName).append("' TOTALLY FAILED.\n");
        sb.append("Possible causes:\n");
        sb.append("     -Exception thrown by task '").append(taskName).append("'.\n");
        sb.append("     -Expected output files not generated by task '").append(taskName).append("'.\n");
        sb.append("     -Could not provide nor retrieve needed data between master and worker.\n");
        sb.append("\n");
        sb.append("Check files '").append(Comm.getAppHost().getJobsDirPath()).append("job[");
        Iterator<Integer> j = jobs.iterator();
        while (j.hasNext()) {
            sb.append(j.next());
            if (!j.hasNext()) {
                break;
            }
            sb.append("|");
        }
        sb.append("'] to find out the error.\n");
        sb.append(" \n");

        ErrorManager.warn(sb.toString());
        TaskMonitor monitor = task.getTaskMonitor();
        if (monitor != null) {
            monitor.onFailedExecution();
        }
        // Notify task failure
        task.decreaseExecutionCount();
        task.setStatus(TaskState.FAILED);
        producer.notifyTaskEnd(task);
    }

    /*
     * ***************************************************************************************************************
     * SCHEDULING MANAGEMENT
     * ***************************************************************************************************************
     */
    @Override
    public final List<ResourceScheduler<? extends WorkerResourceDescription>> getCompatibleWorkers() {
        LinkedList compatible = new LinkedList();
        for (ResourceScheduler r : getCoreElementExecutors(task.getTaskDescription().getId())) {
            if (r.getAssignedAppId() == null || r.getAssignedAppId() == this.task.getAppId()) {
                compatible.add(r);
            }
        }
        return compatible;
    }

    @Override
    public final Implementation[] getImplementations() {
        List<Implementation> coreImpls = CoreManager.getCoreImplementations(task.getTaskDescription().getId());

        int coreImplsSize = coreImpls.size();
        Implementation[] impls = (Implementation[]) new Implementation[coreImplsSize];
        for (int i = 0; i < coreImplsSize; ++i) {
            impls[i] = (Implementation) coreImpls.get(i);
        }
        return impls;
    }

    @Override
    public <W extends WorkerResourceDescription> boolean isCompatible(Worker<W> r) {
        return r.canRun(task.getTaskDescription().getId());
    }

    @Override
    public final <T extends WorkerResourceDescription> List<Implementation> getCompatibleImplementations(ResourceScheduler<T> r) {
        return r.getExecutableImpls(task.getTaskDescription().getId());
    }

    @Override
    public final Integer getCoreId() {
        return task.getTaskDescription().getId();
    }

    @Override
    public final int getPriority() {
        return task.getTaskDescription().hasPriority() ? 1 : 0;
    }

    @Override
    public final <T extends WorkerResourceDescription> Score schedulingScore(ResourceScheduler<T> targetWorker, Score actionScore) {
        Score computedScore = targetWorker.generateResourceScore(this, task.getTaskDescription(), actionScore);
        // LOGGER.debug("Scheduling Score " + computedScore);
        return computedScore;
    }

    @SuppressWarnings("unchecked")
    @Override
    public final void schedule(Score actionScore) throws BlockedActionException, UnassignedActionException {
        // COMPUTE RESOURCE CANDIDATES
        List<ResourceScheduler<? extends WorkerResourceDescription>> candidates = new LinkedList<>();
        if (this.isTargetResourceEnforced()) {
            // The scheduling is forced to a given resource
            candidates.add((ResourceScheduler<WorkerResourceDescription>) this.getEnforcedTargetResource());
        } else if (isSchedulingConstrained()) {
            // The scheduling is constrained by dependencies
            for (AllocatableAction a : this.getConstrainingPredecessors()) {
                candidates.add((ResourceScheduler<WorkerResourceDescription>) a.getAssignedResource());
            }
        } else {
            // Free scheduling
            candidates = getCompatibleWorkers();
        }
        this.schedule(actionScore, candidates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final void tryToSchedule(Score actionScore) throws BlockedActionException, UnassignedActionException {
        // COMPUTE RESOURCE CANDIDATES
        List<ResourceScheduler<? extends WorkerResourceDescription>> candidates = new LinkedList<>();
        if (this.isTargetResourceEnforced()) {
            // The scheduling is forced to a given resource
            candidates.add((ResourceScheduler<WorkerResourceDescription>) this.getEnforcedTargetResource());
        } else if (isSchedulingConstrained()) {
            // The scheduling is constrained by dependencies
            for (AllocatableAction a : this.getConstrainingPredecessors()) {
                candidates.add((ResourceScheduler<WorkerResourceDescription>) a.getAssignedResource());
            }
        } else {
            // Free scheduling
            List<ResourceScheduler<? extends WorkerResourceDescription>> compatibleCandidates = getCompatibleWorkers();
            if (compatibleCandidates.size() == 0) {
                throw new BlockedActionException();
            }
            for (ResourceScheduler<? extends WorkerResourceDescription> currentWorker : compatibleCandidates) {
                if (currentWorker.getResource().hasAvailableSlots()) {
                    candidates.add(currentWorker);
                }
            }
            if (candidates.size() == 0) {
                throw new UnassignedActionException();
            }
        }
        this.schedule(actionScore, candidates);
    }

    private final <T extends WorkerResourceDescription> void schedule(Score actionScore,
            List<ResourceScheduler<? extends WorkerResourceDescription>> candidates)
            throws BlockedActionException, UnassignedActionException {
        // COMPUTE BEST WORKER AND IMPLEMENTATION
        StringBuilder debugString = new StringBuilder("Scheduling " + this + " execution:\n");
        ResourceScheduler<? extends WorkerResourceDescription> bestWorker = null;
        Implementation bestImpl = null;
        Score bestScore = null;
        int usefulResources = 0;
        for (ResourceScheduler<? extends WorkerResourceDescription> worker : candidates) {
            if (this.getExecutingResources().contains(worker)) {
                if (DEBUG) {
                    LOGGER.debug("Task already ran on worker " + worker.getName());
                }
                continue;
            }
            Score resourceScore = worker.generateResourceScore(this, task.getTaskDescription(), actionScore);
            ++usefulResources;
            for (Implementation impl : getCompatibleImplementations(worker)) {
                Score implScore = worker.generateImplementationScore(this, task.getTaskDescription(), impl, resourceScore);
                if (DEBUG) {
                    debugString.append(" Resource ").append(worker.getName()).append(" ").append(" Implementation ")
                            .append(impl.getImplementationId()).append(" ").append(" Score ").append(implScore).append("\n");
                }
                if (Score.isBetter(implScore, bestScore)) {
                    bestWorker = worker;
                    bestImpl = impl;
                    bestScore = implScore;
                }
            }
        }

        // CHECK SCHEDULING RESULT
        if (DEBUG) {
            LOGGER.debug(debugString.toString());
        }

        if (bestWorker == null && usefulResources == 0) {
            LOGGER.warn("No worker can run " + this);
            throw new BlockedActionException();
        }

        schedule(bestWorker, bestImpl);
    }

    @Override
    public final <T extends WorkerResourceDescription> void schedule(ResourceScheduler<T> targetWorker, Score actionScore)
            throws BlockedActionException, UnassignedActionException {

        if (targetWorker == null
                // Resource is not compatible with the Core
                || !targetWorker.getResource().canRun(task.getTaskDescription().getId())
                // already ran on the resource
                || this.getExecutingResources().contains(targetWorker)) {

            String message = "Worker " + (targetWorker == null ? "null" : targetWorker.getName()) + " has not available resources to run "
                    + this;
            LOGGER.warn(message);
            throw new UnassignedActionException();
        }

        Implementation bestImpl = null;
        Score bestScore = null;
        Score resourceScore = targetWorker.generateResourceScore(this, task.getTaskDescription(), actionScore);
        for (Implementation impl : getCompatibleImplementations(targetWorker)) {
            Score implScore = targetWorker.generateImplementationScore(this, task.getTaskDescription(), impl, resourceScore);
            if (Score.isBetter(implScore, bestScore)) {
                bestImpl = impl;
                bestScore = implScore;
            }
        }

        schedule(targetWorker, bestImpl);
    }

    @Override
    public final <T extends WorkerResourceDescription> void schedule(ResourceScheduler<T> targetWorker, Implementation impl)
            throws BlockedActionException, UnassignedActionException {
        if (targetWorker == null || impl == null) {
            throw new UnassignedActionException();
        }

        if (DEBUG) {
            LOGGER.debug("Scheduling " + this + " on worker " + (targetWorker == null ? "null" : targetWorker.getName())
                    + " with implementation " + (impl == null ? "null" : impl.getImplementationId()));
        }

        if (// Resource is not compatible with the implementation
                !targetWorker.getResource().canRun(impl)
                // already ran on the resource
                || this.getExecutingResources().contains(targetWorker)) {

            LOGGER.debug("Worker " + targetWorker.getName() + " has not available resources to run " + this);
            throw new UnassignedActionException();
        }

        LOGGER.info(
                "Assigning action " + this + " to worker " + targetWorker.getName() + " with implementation " + impl.getImplementationId());

        this.assignImplementation(impl);
        assignResource(targetWorker);
        targetWorker.scheduleAction(this);
        TaskMonitor monitor = task.getTaskMonitor();
        if (monitor != null) {
            monitor.onSchedule();
        }
    }

    /*
     * ***************************************************************************************************************
     * OTHER
     * ***************************************************************************************************************
     */
    @Override
    public String toString() {
        return "ExecutionAction ( Task " + task.getId() + ", CE name " + task.getTaskDescription().getName() + ")";
    }

}
