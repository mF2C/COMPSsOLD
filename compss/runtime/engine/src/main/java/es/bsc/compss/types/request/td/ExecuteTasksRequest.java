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
package es.bsc.compss.types.request.td;

import java.util.Collection;

import es.bsc.compss.components.impl.ResourceScheduler;
import es.bsc.compss.components.impl.TaskProducer;
import es.bsc.compss.components.impl.TaskScheduler;
import es.bsc.compss.types.Task;
import es.bsc.compss.types.Task.TaskState;
import es.bsc.compss.types.allocatableactions.ExecutionAction;
import es.bsc.compss.types.allocatableactions.MultiNodeExecutionAction;
import es.bsc.compss.types.allocatableactions.MultiNodeGroup;
import es.bsc.compss.types.request.exceptions.ShutdownException;
import es.bsc.compss.types.resources.WorkerResourceDescription;

/**
 * The ExecuteTasksRequest class represents the request to execute a task
 */
public class ExecuteTasksRequest extends TDRequest {

    private final TaskProducer producer;
    private final Task task;

    /**
     * Constructs a new ScheduleTasks Request
     *
     * @param producer taskProducer to be notified when the task ends
     * @param t Task to run
     */
    public ExecuteTasksRequest(TaskProducer producer, Task t) {
        this.producer = producer;
        this.task = t;
    }

    /**
     * Returns the task to execute
     *
     * @return task to execute
     */
    public Task getTask() {
        return task;
    }

    @Override
    public void process(TaskScheduler ts) throws ShutdownException {
        int coreId = task.getTaskDescription().getId();
        if (DEBUG) {
            LOGGER.debug("Treating Scheduling request for task " + task.getId() + "(core " + coreId + ")");
        }

        task.setStatus(TaskState.TO_EXECUTE);
        int numNodes = task.getTaskDescription().getNumNodes();
        boolean isReplicated = task.getTaskDescription().isReplicated();
        boolean isDistributed = task.getTaskDescription().isDistributed();

        if (isReplicated) {
            // Method annotation forces to replicate task to all nodes
            if (DEBUG) {
                LOGGER.debug("Replicating task " + task.getId());
            }

            Collection<ResourceScheduler<? extends WorkerResourceDescription>> resources = ts.getWorkers();
            task.setExecutionCount(resources.size() * numNodes);
            for (ResourceScheduler<? extends WorkerResourceDescription> rs : resources) {
                submitTask(ts, numNodes, rs);
            }
        } else if (isDistributed) {
            // Method annotation forces RoundRobin among nodes
            // WARN: This code is proportional to the number of resources, can lead to some overhead
            if (DEBUG) {
                LOGGER.debug("Distributing task " + task.getId());
            }

            ResourceScheduler<? extends WorkerResourceDescription> selectedResource = null;
            int minNumTasksOfSameType = Integer.MAX_VALUE;
            Collection<ResourceScheduler<? extends WorkerResourceDescription>> resources = ts.getWorkers();
            for (ResourceScheduler<? extends WorkerResourceDescription> rs : resources) {
                // RS numTasks only considers MasterExecutionActions
                int numTasks = rs.getNumTasks(task.getTaskDescription().getId());
                if (numTasks < minNumTasksOfSameType) {
                    minNumTasksOfSameType = numTasks;
                    selectedResource = rs;
                }
            }

            task.setExecutionCount(numNodes);
            submitTask(ts, numNodes, selectedResource);
        } else {
            // Normal task
            if (DEBUG) {
                LOGGER.debug("Submitting task " + task.getId());
            }

            task.setExecutionCount(numNodes);
            submitTask(ts, numNodes, null);
        }

        if (DEBUG) {
            LOGGER.debug("Treated Scheduling request for task " + task.getId() + " (core " + coreId + ")");
        }
    }

    private <T extends WorkerResourceDescription> void submitTask(TaskScheduler ts, int numNodes, ResourceScheduler<T> specificResource) {
        // A task can use one or more resources
        if (numNodes == 1) {
            submitSingleTask(ts, specificResource);
        } else {
            submitMultiNodeTask(ts, numNodes, specificResource);
        }
    }

    private <T extends WorkerResourceDescription> void submitSingleTask(TaskScheduler ts, ResourceScheduler<T> specificResource) {
        LOGGER.debug("Scheduling request for task " + task.getId() + " treated as singleTask");
        ExecutionAction action = new ExecutionAction(ts.generateSchedulingInformation(specificResource), ts.getOrchestrator(), producer, task);
        ts.newAllocatableAction(action);
    }

    private <T extends WorkerResourceDescription> void submitMultiNodeTask(TaskScheduler ts, int numNodes, ResourceScheduler<T> specificResource) {
        LOGGER.debug("Scheduling request for task " + task.getId() + " treated as multiNodeTask with " + numNodes + " nodes");
        // Can use one or more resources depending on the computingNodes
        MultiNodeGroup group = new MultiNodeGroup(numNodes);
        for (int i = 0; i < numNodes; ++i) {
            MultiNodeExecutionAction action = new MultiNodeExecutionAction(ts.generateSchedulingInformation(specificResource), ts.getOrchestrator(), producer, task, group);
            ts.newAllocatableAction(action);
        }
    }

    @Override
    public TDRequestType getType() {
        return TDRequestType.EXECUTE_TASKS;
    }

}
