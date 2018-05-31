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
package es.bsc.compss.components.impl;

import es.bsc.compss.log.Loggers;
import es.bsc.compss.scheduler.exceptions.ActionNotFoundException;
import es.bsc.compss.scheduler.exceptions.ActionNotWaitingException;
import es.bsc.compss.scheduler.types.AllocatableAction;
import es.bsc.compss.scheduler.types.Profile;
import es.bsc.compss.scheduler.types.Score;
import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.resources.Worker;
import es.bsc.compss.types.resources.WorkerResourceDescription;
import es.bsc.compss.types.resources.updates.ResourceUpdate;
import es.bsc.compss.util.CoreManager;

import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;


/**
 *
 * Scheduler representation for a given worker
 *
 * @param <T>
 */
public class ResourceScheduler<T extends WorkerResourceDescription> {

    // Logger
    protected static final Logger LOGGER = LogManager.getLogger(Loggers.TS_COMP);
    protected static final boolean DEBUG = LOGGER.isDebugEnabled();

    private final Long assignedAppId;

    // Task running in the resource
    private final List<AllocatableAction> running;
    // Task without enough resources to be executed right now
    protected final PriorityQueue<AllocatableAction> blocked;

    // Worker assigned to the resource scheduler
    protected final Worker<T> myWorker;
    // Modifications pending to be applied
    private final List<ResourceUpdate<T>> pendingModifications;

    // Profile information of the task executions
    private Profile[][] profiles;

    /**
     * Constructs a new Resource Scheduler associated to the worker @w
     *
     * @param w
     * @param assignedAppId
     * @param defaultResource
     */
    public ResourceScheduler(Worker<T> w, Long assignedAppId, JSONObject defaultResource, JSONObject defaultImplementations) {
        this.running = new LinkedList<>();
        this.blocked = new PriorityQueue<>(20, new Comparator<AllocatableAction>() {

            @Override
            public int compare(AllocatableAction a1, AllocatableAction a2) {
                Score score1 = generateBlockedScore(a1);
                Score score2 = generateBlockedScore(a2);
                return score1.compareTo(score2);
            }
        });

        this.myWorker = w;
        this.assignedAppId = assignedAppId;
        this.pendingModifications = new LinkedList<>();
        JSONObject resMap;
        if (defaultResource != null) {
            try {
                resMap = defaultResource.getJSONObject("implementations");
            } catch (JSONException je) {
                resMap = null;
            }
        } else {
            resMap = null;
        }
        this.profiles = loadProfiles(resMap, defaultImplementations);

    }

    /*
     * ***************************************************************************************************************
     * RESOURCE MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Returns the worker name
     *
     * @return
     */
    public final String getName() {
        return this.myWorker.getName();
    }

    /**
     * Returns the id of the only application whose tasks can run on the resource
     *
     * @return
     */
    public Long getAssignedAppId() {
        return this.assignedAppId;
    }

    /**
     * Returns the worker resource
     *
     * @return
     */
    public final Worker<T> getResource() {
        return this.myWorker;
    }

    /**
     * Returns the coreElements that can be executed by the resource
     *
     * @return
     */
    public final List<Integer> getExecutableCores() {
        return this.myWorker.getExecutableCores();
    }

    /**
     * Returns the implementations that can be executed by the resource
     *
     * @return
     */
    public final List<Implementation>[] getExecutableImpls() {
        return this.myWorker.getExecutableImpls();
    }

    /**
     * Returns the implementations of the core @id that can be executed by the resource
     *
     * @param coreId
     * @return
     */
    public final List<Implementation> getExecutableImpls(int coreId) {
        return this.myWorker.getExecutableImpls(coreId);
    }

    /**
     * Adds a pending modification on the resource features
     *
     * @param modification
     */
    public final void pendingModification(ResourceUpdate<T> modification) {
        pendingModifications.add(modification);
    }

    /**
     * Returns if there are pending modification on the resources
     *
     * @return
     */
    public final boolean hasPendingModifications() {
        return !pendingModifications.isEmpty();
    }

    /**
     * Returns if the pending modifications on the resources
     *
     * @return
     */
    public final List<ResourceUpdate<T>> getPendingModifications() {
        return pendingModifications;
    }

    /**
     * Removes a pending modification on the resource features
     *
     * @param modification
     */
    public final void completedModification(ResourceUpdate<T> modification) {
        pendingModifications.remove(modification);
    }

    /*
     * ***************************************************************************************************************
     * ACTION PROFILE MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Prepares the default profiles for each implementation cores
     *
     * @param resMap default profile values for the resource
     * @param implMap default profile values for the implementation
     *
     * @return default profile structure
     */
    protected final Profile[][] loadProfiles(JSONObject resMap, JSONObject implMap) {
        Profile[][] profiles;
        int coreCount = CoreManager.getCoreCount();
        profiles = new Profile[coreCount][];
        for (int coreId = 0; coreId < coreCount; ++coreId) {
            List<Implementation> impls = CoreManager.getCoreImplementations(coreId);
            int implCount = impls.size();
            profiles[coreId] = new Profile[implCount];
            for (Implementation impl : impls) {
                String signature = CoreManager.getSignature(coreId, impl.getImplementationId());
                JSONObject jsonImpl = null;
                if (resMap != null) {
                    try {
                        jsonImpl = resMap.getJSONObject(signature);
                        profiles[coreId][impl.getImplementationId()] = generateProfileForImplementation(impl, jsonImpl);
                    } catch (JSONException je) {
                        // Do nothing
                    }
                }
                if (profiles[coreId][impl.getImplementationId()] == null) {
                    if (implMap != null) {
                        try {
                            jsonImpl = implMap.getJSONObject(signature);
                        } catch (JSONException je) {
                            // Do nothing
                        }
                    }
                    profiles[coreId][impl.getImplementationId()] = generateProfileForImplementation(impl, jsonImpl);
                    profiles[coreId][impl.getImplementationId()].clearExecutionCount();
                }
            }
        }
        return profiles;
    }

    /**
     * Updates the coreElement structures
     *
     * @param newCoreCount
     * @param resourceJSON
     */
    public void updatedCoreElements(int newCoreCount, JSONObject resourceJSON) {
        int oldCoreCount = this.profiles.length;
        Profile[][] profiles = new Profile[newCoreCount][];
        JSONObject implMap;
        if (resourceJSON != null) {
            try {
                implMap = resourceJSON.getJSONObject("implementations");
            } catch (JSONException je) {
                implMap = null;
            }
        } else {
            implMap = null;
        }

        for (int coreId = 0; coreId < newCoreCount; coreId++) {
            int oldImplCount = 0;
            if (coreId < oldCoreCount) {
                oldImplCount = this.profiles[coreId].length;
            }
            List<Implementation> impls = CoreManager.getCoreImplementations(coreId);
            int newImplCount = impls.size();

            // Create new array
            profiles[coreId] = new Profile[newImplCount];

            // Copy the previous profile implementations
            for (Implementation impl : impls) {
                int implId = impl.getImplementationId();
                if (implId < oldImplCount) {
                    profiles[coreId][implId] = this.profiles[coreId][implId];
                } else {
                    JSONObject jsonImpl;
                    if (implMap != null) {
                        try {
                            jsonImpl = implMap.getJSONObject(CoreManager.getSignature(coreId, implId));
                        } catch (JSONException je) {
                            jsonImpl = null;
                        }
                    } else {
                        jsonImpl = null;
                    }
                    profiles[coreId][implId] = generateProfileForImplementation(impl, jsonImpl);
                }
            }
        }
        this.profiles = profiles;
    }

    /**
     * Generates a Profile for an action.
     *
     * @param impl
     * @param jsonImpl
     * @return a profile object for an action.
     */
    public Profile generateProfileForImplementation(Implementation impl, JSONObject jsonImpl) {
        return new Profile(jsonImpl);
    }

    /**
     * Generates a Profile for an action.
     *
     * @param <T>
     * @param action
     * @return a profile object for an action.
     */
    public <R extends WorkerResourceDescription> Profile generateProfileForRun(AllocatableAction action) {
        return new Profile();
    }

    /**
     * Returns the profile for a given implementation @impl
     *
     * @param coreId
     * @param implId
     * @return
     */
    public final Profile getProfile(int coreId, int implId) {
        return profiles[coreId][implId];
    }

    /**
     * Returns the profile for a given implementation @impl
     *
     * @param impl
     * @return
     */
    public final Profile getProfile(Implementation impl) {
        if (impl != null) {
            if (impl.getCoreId() != null) {
                return getProfile(impl.getCoreId(), impl.getImplementationId());
            }
        }
        return null;
    }

    /**
     * Updates the execution profile of implementation @impl by accumulating the profile @profile
     *
     * @param impl
     * @param profile
     */
    public final void profiledExecution(Implementation impl, Profile profile) {
        if (impl != null) {
            int coreId = impl.getCoreId();
            int implId = impl.getImplementationId();
            this.profiles[coreId][implId].accumulate(profile);
        }

    }

    /*
     * ***************************************************************************************************************
     * RUNNING ACTIONS MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Returns the number of tasks of type @taskId that this resource is running
     *
     * @param coreId
     * @return
     */
    public final int getNumTasks(int coreId) {
        int taskCount = -1;
        if (coreId < profiles.length) {
            taskCount = 0;
            for (AllocatableAction aa : this.running) {
                if (aa.getCoreId() == coreId) {
                    taskCount++;
                }
            }
        }
        return taskCount;
    }

    /**
     * Returns all the hosted actions
     *
     * @return
     */
    public final AllocatableAction[] getHostedActions() {
        return this.running.toArray(new AllocatableAction[running.size()]);
    }

    /**
     * Adds a new running action on the resource
     *
     * @param action
     */
    public final void hostAction(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Host action " + action);
        this.running.add(action);

    }

    /**
     * Removes a running action
     *
     * @param action
     */
    public final void unhostAction(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Unhost action " + action + " on resource " + getName());
        this.running.remove(action);
    }

    /*
     * ***************************************************************************************************************
     * BLOCKED ACTIONS MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Adds a blocked action on this worker
     *
     * @param action
     */
    public final void waitOnResource(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Block action " + action + " on resource " + getName());
        this.blocked.add(action);
    }

    /**
     * Returns if there are blocked actions or not
     *
     * @return
     */
    public final boolean hasBlockedActions() {
        return this.blocked.size() > 0;
    }

    /**
     * Returns all the blocked actions
     *
     * @return
     */
    public PriorityQueue<AllocatableAction> getBlockedActions() {
        return this.blocked;
    }

    /**
     * Returns the first blocked action without removing it
     *
     * @return
     */
    public final AllocatableAction getFirstBlocked() {
        return this.blocked.peek();
    }

    /**
     * Removes the first blocked action
     *
     */
    public final void removeFirstBlocked() {
        this.blocked.poll();
    }

    /**
     * Tries to launch blocked actions on resource. When an action cannot be launched, its successors are not tried
     *
     */
    @SuppressWarnings("unchecked")
    public final void tryToLaunchBlockedActions() {
        LOGGER.debug("[ResourceScheduler] Try to launch blocked actions on resource " + getName());
        while (this.hasBlockedActions()) {
            AllocatableAction firstBlocked = this.getFirstBlocked();
            Implementation selectedImplementation = firstBlocked.getAssignedImplementation();
            if (!firstBlocked.isToReserveResources() || myWorker.canRunNow((T) selectedImplementation.getRequirements())) {
                try {
                    firstBlocked.resumeExecution();
                    this.removeFirstBlocked();
                } catch (ActionNotWaitingException anwe) {
                    // Not possible. If the task is in blocked list it is waiting
                }
            } else {
                break;
            }
        }
    }

    /*
     * ***************************************************************************************************************
     * SCHEDULE MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Assigns an initial Schedule for action @action
     *
     * @param action
     */
    public void scheduleAction(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Schedule action " + action + " on resource " + getName());
        // Assign no resource dependencies.
        // The worker will automatically block the tasks when there are not enough resources available.
    }

    /**
     * Unschedules the action assigned to this worker
     *
     * @param action
     * @return
     * @throws es.bsc.compss.scheduler.exceptions.ActionNotFoundException
     */
    public List<AllocatableAction> unscheduleAction(AllocatableAction action) throws ActionNotFoundException {
        LOGGER.debug("[ResourceScheduler] Unschedule action " + action + " on resource " + getName());
        return new LinkedList<>();
    }

    /**
     * Cancels an action execution
     *
     * @param action
     * @throws es.bsc.compss.scheduler.exceptions.ActionNotFoundException
     */
    public final void cancelAction(AllocatableAction action) throws ActionNotFoundException {
        LOGGER.debug("[ResourceScheduler] Cancel action " + action + " on resource " + getName());
        this.blocked.remove(action);
        unscheduleAction(action);
    }

    /*
     * ***************************************************************************************************************
     * SCORES
     * ***************************************************************************************************************
     */
    /**
     * Returns the score for the action when it is blocked
     *
     * @param action
     * @return
     */
    public Score generateBlockedScore(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Generate blocked score for action " + action);
        return new Score(action.getPriority(), 0, 0, 0);
    }

    /**
     * Returns the resource score of action @action
     *
     * @param action
     * @param params
     * @param actionScore
     * @return
     */
    public Score generateResourceScore(AllocatableAction action, TaskDescription params, Score actionScore) {
        // LOGGER.debug("[ResourceScheduler] Generate resource score for action " + action);
        // Gets the action priority
        long actionPriority = actionScore.getActionScore();
        // Computes the resource waiting score
        long waitingScore = -this.blocked.size();
        // Computes the priority of the resource
        long resourceScore = Score.calculateDataLocalityScore(params, this.myWorker);

        return new Score(actionPriority, resourceScore, waitingScore, 0);
    }

    /**
     * Returns the score of a given implementation @impl for action @action with a fixed resource score @resourceScore
     *
     * @param action
     * @param params
     * @param impl
     * @param resourceScore
     * @return
     */
    @SuppressWarnings("unchecked")
    public Score generateImplementationScore(AllocatableAction action, TaskDescription params, Implementation impl, Score resourceScore) {
        // LOGGER.debug("[ResourceScheduler] Generate implementation score for action " + action);
        long actionPriority = resourceScore.getActionScore();
        long resourcePriority = resourceScore.getResourceScore();
        if (!myWorker.canRunNow((T) impl.getRequirements())) {
            resourcePriority -= Integer.MAX_VALUE;
        }
        long waitingScore = resourceScore.getWaitingScore();
        long implScore = -this.getProfile(impl).getAverageExecutionTime();
        return new Score(actionPriority, resourcePriority, waitingScore, implScore);
    }

    /*
     * ***************************************************************************************************************
     * OTHER
     * ***************************************************************************************************************
     */
    /**
     * Clear internal structures
     *
     */
    public void clear() {
        LOGGER.debug("[ResourceScheduler] Clear resource scheduler " + getName());
        this.running.clear();
        this.blocked.clear();
        this.pendingModifications.clear();
        this.myWorker.releaseAllResources();
    }

    public JSONObject toJSONObject() {
        JSONObject jsonObject = new JSONObject();
        int coreCount = CoreManager.getCoreCount();
        Map<String, JSONObject> implsMap = new HashMap<>();

        for (int coreId = 0; coreId < coreCount; coreId++) {
            List<Implementation> impls = CoreManager.getCoreImplementations(coreId);
            for (Implementation impl : impls) {
                int implId = impl.getImplementationId();
                JSONObject implProfile = profiles[coreId][implId].toJSONObject();
                implsMap.put(CoreManager.getSignature(coreId, implId), implProfile);
            }
        }
        jsonObject.put("implementations", implsMap);
        return jsonObject;
    }

    public JSONObject updateJSON(JSONObject oldResource) {
        JSONObject difference = new JSONObject();
        JSONObject implsDiff = new JSONObject();
        difference.put("implementations", implsDiff);

        JSONObject implsMap = oldResource.getJSONObject("implementations");

        int coreCount = CoreManager.getCoreCount();
        for (int coreId = 0; coreId < coreCount; coreId++) {
            int implCount = CoreManager.getNumberCoreImplementations(coreId);
            for (int implId = 0; implId < implCount; implId++) {
                String signature = CoreManager.getSignature(coreId, implId);
                if (implsMap.has(signature)) {
                    JSONObject impl = implsMap.getJSONObject(signature);
                    JSONObject diff = profiles[coreId][implId].updateJSON(impl);
                    implsDiff.put(signature, diff);
                } else {
                    JSONObject implProfile = profiles[coreId][implId].toJSONObject();
                    implsMap.put(signature, implProfile);
                    implsDiff.put(signature, implProfile);
                }
            }
        }

        return difference;
    }

    @Override
    public String toString() {
        try {
            return "ResourceScheduler@" + getName();
        } catch (NullPointerException ne) {
            return super.toString();
        }
    }

}
