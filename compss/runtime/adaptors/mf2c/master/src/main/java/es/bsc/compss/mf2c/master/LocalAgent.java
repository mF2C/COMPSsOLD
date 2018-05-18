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

import es.bsc.compss.exceptions.InitNodeException;
import es.bsc.compss.mf2c.master.configuration.AgentConfiguration;
import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.data.Transferable;
import es.bsc.compss.types.data.listener.EventListener;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.data.location.DataLocation.Protocol;
import es.bsc.compss.types.data.operation.copy.StorageCopy;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.job.Job;
import es.bsc.compss.types.job.JobListener;
import es.bsc.compss.types.job.JobListener.JobEndStatus;
import es.bsc.compss.types.resources.Resource;
import es.bsc.compss.types.resources.ShutdownListener;
import es.bsc.compss.types.uri.MultiURI;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.util.Debugger;
import es.bsc.compss.util.ErrorManager;
import es.bsc.compss.util.Serializer;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;


public class LocalAgent extends Agent {

    private final ExecutorService executor;
    private boolean stop = false;
    private final LinkedBlockingQueue<LocalAgentJob> requests = new LinkedBlockingQueue<LocalAgentJob>();

    public LocalAgent(String name, AgentConfiguration config) {
        super(name, config);
        int cores = config.getLimitOfTasks();
        executor = Executors.newFixedThreadPool(cores);
    }

    @Override
    public void start() throws InitNodeException {
        executor.execute(new JobExecutor());
    }

    @Override
    public void stop(ShutdownListener sl) {
        sl.notifyEnd();
    }

    @Override
    public Job<?> newJob(int taskId, TaskDescription taskparams, Implementation impl, Resource res, List<String> slaveWorkersNodeNames, JobListener listener) {
        return new LocalAgentJob(this, taskId, taskparams, impl, res, listener);
    }

    protected void runJob(LocalAgentJob job) {
        requests.offer(job);
    }

    @Override
    public void obtainData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason,
            EventListener listener) {
        if (ld == null) {
            return;
        }

        Debugger.debug("STAGE IN", "Placing data " + ld.getName() + " as " + target);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Obtain Data " + ld.getName() + " as " + target);
        }

        // If it has a PSCO location, it is a PSCO -> Order new StorageCopy
        if (ld.getId() != null) {
            Debugger.debug("STAGE IN", "    Using persistent storage to copy " + reason.getType() + " parameter");
            orderStorageCopy(new StorageCopy(ld, source, target, tgtData, reason, listener));
        } else {
            Debugger.debug("STAGE IN", "    Ordering the copy of a plain object");
            /*
             * Otherwise the data is a file or an object that can be already in the master memory, in the master disk or
             * being transfered
             */
            String targetPath = "";
            for (MultiURI mu : target.getURIs()) {
                if (mu.getHost().getNode() == this) {
                    targetPath = mu.getPath();
                }
            }

            // Check if data is in memory (no need to check if it is PSCO since previous case avoids it)
            if (ld.isInMemory()) {
                if (tgtData != ld) {
                    // Serialize value to file
                    try {
                        Serializer.serialize(ld.getValue(), targetPath);
                    } catch (IOException ex) {
                        ErrorManager.warn("Error copying file from memory to " + targetPath, ex);
                    }

                    if (tgtData != null) {
                        tgtData.addLocation(target);
                    }
                }
                LOGGER.debug("Object in memory. Set dataTarget to " + targetPath);

                reason.setDataSource(source);
                reason.setDataTarget(targetPath);
                listener.notifyEnd(null);
                return;
            } else {
                Debugger.err("STAGE IN", "    Object not in memory. We **** up!!!!");
            }
        }
    }


    private class JobExecutor implements Runnable {

        @Override
        public void run() {
            while (true) {
                LocalAgent me = LocalAgent.this;
                LinkedBlockingQueue<LocalAgentJob> requests = me.requests;
                LocalAgentJob job;
                try {
                    job = requests.take();
                } catch (InterruptedException ex) {
                    job = null;
                }
                if (job == null) {
                    return;
                }
                try {
                    job.execute();
                } catch (JobExecutionException jee) {
                    job.getListener().jobFailed(job, JobEndStatus.EXECUTION_FAILED);
                    continue;
                }
                job.getListener().jobCompleted(job);
            }

        }
    }
}
