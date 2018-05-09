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
package es.bsc.compss.util;

import es.bsc.compss.types.resources.DynamicMethodWorker;
import es.bsc.compss.types.resources.Worker;
import es.bsc.compss.types.resources.WorkerResourceDescription;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.TreeSet;


public class WorkerPool {

    // Resource Sets:
    // Static Resources (read from xml)
    private final Map<String, Worker<? extends WorkerResourceDescription>> staticSet;
    // Critical Resources (can't be destroyed by periodical resource policy)
    private final Map<String, DynamicMethodWorker> criticalSet;
    // Non Critical Resources (can be destroyed by periodical resource policy)
    private final Map<String, DynamicMethodWorker> nonCriticalSet;

    // TreeSet : Priority on criticalSet based on cost
    private final Set<DynamicMethodWorker> criticalOrder;


    public WorkerPool() {
        staticSet = new HashMap<>();
        criticalSet = new HashMap<>();
        nonCriticalSet = new HashMap<>();
        criticalOrder = new TreeSet<>();
    }

    // Adds a new Resource on the Physical list
    public void addStaticResource(Worker<? extends WorkerResourceDescription> newResource) {
        staticSet.put(newResource.getName(), newResource);
    }

    // Adds a new Resource on the Critical list
    public void addDynamicResource(DynamicMethodWorker newResource) {
        criticalSet.put(newResource.getName(), newResource);
        criticalOrder.add(newResource);
    }

    public void coreElementUpdates(List<Integer> newCores) {
        for (Worker<? extends WorkerResourceDescription> r : staticSet.values()) {
            r.updatedCoreElements(newCores);
        }
        for (DynamicMethodWorker r : criticalSet.values()) {
            r.updatedCoreElements(newCores);
        }
        for (DynamicMethodWorker r : nonCriticalSet.values()) {
            r.updatedCoreElements(newCores);
        }
    }

    public Collection<Worker<? extends WorkerResourceDescription>> getStaticResources() {
        return staticSet.values();
    }

    public Worker<? extends WorkerResourceDescription> getStaticResource(String resourceName) {
        return staticSet.get(resourceName);
    }

    public DynamicMethodWorker getDynamicResource(String resourceName) {
        DynamicMethodWorker resource = null;
        resource = criticalSet.get(resourceName);
        if (resource == null) {
            resource = nonCriticalSet.get(resourceName);
        }
        return resource;
    }

    public List<DynamicMethodWorker> getDynamicResources() {
        List<DynamicMethodWorker> resources = new LinkedList<>();
        resources.addAll(criticalSet.values());
        resources.addAll(nonCriticalSet.values());

        return resources;
    }

    /**
     * Returns all the resource information
     *
     * @param resourceName
     * @return
     */
    public Worker<? extends WorkerResourceDescription> getResource(String resourceName) {
        Worker<? extends WorkerResourceDescription> resource = null;
        resource = staticSet.get(resourceName);
        if (resource == null) {
            resource = criticalSet.get(resourceName);
        }
        if (resource == null) {
            resource = nonCriticalSet.get(resourceName);
        }

        return resource;
    }

    /**
     * Deletes a resource from the pool
     *
     * @param resource
     */
    public void delete(Worker<? extends WorkerResourceDescription> resource) {
        String resourceName = resource.getName();
        // Remove resource from sets
        if (nonCriticalSet.remove(resourceName) == null) {
            if (criticalSet.remove(resourceName) == null) {
                staticSet.remove(resourceName);
            }
        }
    }

    /**
     *
     * @param res
     * @return list with all coreIds that can be executed by the resource res
     */
    public List<Integer> getExecutableCores(String res) {
        Worker<? extends WorkerResourceDescription> resource = getResource(res);
        if (resource == null) {
            return new LinkedList<>();
        }
        return resource.getExecutableCores();
    }

    /**
     * Selects a subset of the critical set able to execute all the cores
     */
    public void defineCriticalSet() {
        synchronized (this) {
            int coreCount = CoreManager.getCoreCount();
            boolean[] runnable = new boolean[coreCount];
            for (int coreId = 0; coreId < coreCount; coreId++) {
                runnable[coreId] = false;
            }

            String resourceName;
            for (Worker<? extends WorkerResourceDescription> res : staticSet.values()) {
                List<Integer> cores = res.getExecutableCores();
                for (int i = 0; i < cores.size(); i++) {
                    runnable[cores.get(i)] = true;
                }
            }

            LinkedList<DynamicMethodWorker> criticalOrderRemovals = new LinkedList<>();
            for (DynamicMethodWorker resource : criticalOrder) {
                resourceName = resource.getName();
                List<Integer> executableCores = resource.getExecutableCores();
                boolean needed = false;
                for (int i = 0; i < executableCores.size() && !needed; i++) {
                    needed = needed || !runnable[executableCores.get(i)];
                }
                if (needed) {
                    for (int i = 0; i < executableCores.size(); i++) {
                        runnable[executableCores.get(i)] = true;
                    }
                } else {
                    criticalSet.remove(resourceName);
                    criticalOrderRemovals.add(resource);
                    nonCriticalSet.put(resourceName, resource);
                }
            }
            for (DynamicMethodWorker resource : criticalOrderRemovals) {
                criticalOrder.remove(resource);
            }
        }
    }

    public Collection<DynamicMethodWorker> getNonCriticalResources() {
        return nonCriticalSet.values();
    }

    public Collection<DynamicMethodWorker> getCriticalResources() {
        return criticalSet.values();
    }

    /**
     *
     * @return a list with all the resources available
     */
    @SuppressWarnings("unchecked")
    public List<Worker<? extends WorkerResourceDescription>> findAllResources() {
        List<Worker<? extends WorkerResourceDescription>> workers = new LinkedList<>();

        if (staticSet != null && !staticSet.isEmpty()) {
            Object[] arrayStaticSet = staticSet.values().toArray();
            for (int i = 0; i < arrayStaticSet.length; i++) {
                workers.add((Worker<? extends WorkerResourceDescription>) arrayStaticSet[i]);
            }
        }

        if (criticalSet != null && !criticalSet.isEmpty()) {
            Object[] arrayCriticalSet = criticalSet.values().toArray();
            for (int i = 0; i < arrayCriticalSet.length; i++) {
                workers.add((Worker<? extends WorkerResourceDescription>) arrayCriticalSet[i]);
            }
        }

        if (nonCriticalSet != null && !nonCriticalSet.isEmpty()) {
            Object[] arrayNonCriticalSet = nonCriticalSet.values().toArray();
            for (int i = 0; i < arrayNonCriticalSet.length; i++) {
                workers.add((Worker<? extends WorkerResourceDescription>) arrayNonCriticalSet[i]);
            }
        }

        return workers;
    }

    public boolean isCriticalRemovalSafe(int[][] slotReductionImpls) {
        int coreCount = CoreManager.getCoreCount();
        // Compute cores from impl
        int[] slotReductionCores = new int[coreCount];
        for (int coreId = 0; coreId < coreCount; ++coreId) {
            for (int implId = 0; implId < CoreManager.getNumberCoreImplementations(coreId); ++implId) {
                if (slotReductionImpls[coreId][implId] > slotReductionCores[coreId]) {
                    slotReductionCores[coreId] = slotReductionImpls[coreId][implId];
                }
            }
        }

        int[] slots = new int[coreCount];
        for (DynamicMethodWorker r : criticalSet.values()) {
            int[] resSlots = r.getSimultaneousTasks();
            for (int coreId = 0; coreId < coreCount; coreId++) {
                slots[coreId] += resSlots[coreId];
            }
        }

        for (int coreId = 0; coreId < coreCount; coreId++) {
            if (slotReductionCores[coreId] > 0 && slotReductionCores[coreId] >= slots[coreId]) {
                return false;
            }
        }
        return true;
    }

    public String getCurrentState(String prefix) {
        StringBuilder sb = new StringBuilder();
        // Resources
        sb.append(prefix).append("RESOURCES = [").append("\n");
        for (Worker<? extends WorkerResourceDescription> r : staticSet.values()) {
            sb.append(prefix).append("\t").append("RESOURCE = [").append("\n");
            sb.append(r.getResourceLinks(prefix + "\t\t")); // Adds resource information
            sb.append(prefix).append("\t").append("\t").append("SET = Static").append("\n");
            sb.append(prefix).append("\t").append("]").append("\n");
        }
        for (DynamicMethodWorker r : criticalSet.values()) {
            sb.append(prefix).append("\t").append("RESOURCE = [").append("\n");
            sb.append(r.getResourceLinks(prefix + "\t\t")); // Adds resource information
            sb.append(prefix).append("\t").append("\t").append("SET = Critical").append("\n");
            sb.append(prefix).append("\t").append("]").append("\n");
        }
        for (DynamicMethodWorker r : nonCriticalSet.values()) {
            sb.append(prefix).append("\t").append("RESOURCE = [").append("\n");
            sb.append(r.getResourceLinks(prefix + "\t\t")); // Adds resource information
            sb.append(prefix).append("\t").append("\t").append("SET = Non-Critical").append("\n");
            sb.append(prefix).append("\t").append("]").append("\n");
        }
        sb.append(prefix).append("]").append("\n");

        /*
         * //Cores sb.append(prefix).append("CORES = [").append("\n"); for (int i = 0; i < CoreManager.getCoreCount();
         * i++) { sb.append(prefix).append("\t").append("CORE = [").append("\n");
         * sb.append(prefix).append("\t").append("\t").append("ID = ").append(i).append("\n");
         * sb.append(prefix).append("\t").append("\t").append("MAXTASKCOUNT = ").append(coreMaxTaskCount[i]).append("\n"
         * ); sb.append(prefix).append("\t").append("\t").append("TORESOURCE = [").append("\n"); for (Worker<?,?> r :
         * coreToResource[i]) {
         * sb.append(prefix).append("\t").append("\t").append("\t").append("RESOURCE = [").append("\n");
         * sb.append(prefix).append("\t").append("\t").append("\t").append("\t").append("NAME = ").append(r.getName()).
         * append("\n");
         * sb.append(prefix).append("\t").append("\t").append("\t").append("\t").append("SIMTASKS = ").append(r.
         * getSimultaneousTasks()[i]).append("\n");
         * sb.append(prefix).append("\t").append("\t").append("\t").append("]").append("\n"); }
         * sb.append(prefix).append("\t").append("\t").append("]").append("\n");
         * sb.append(prefix).append("\t").append("]").append("\n"); } sb.append(prefix).append("]").append("\n");
         */
        return sb.toString();
    }

}
