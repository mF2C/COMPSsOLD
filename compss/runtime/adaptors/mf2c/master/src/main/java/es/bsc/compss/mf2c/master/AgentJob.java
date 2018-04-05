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

import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.job.Job;
import es.bsc.compss.types.job.JobListener;
import es.bsc.compss.types.resources.Resource;


public abstract class AgentJob<T extends Agent> extends Job<T> {

    private final T executor;

    public AgentJob(T executor, int taskId, TaskDescription task, Implementation impl, Resource res, JobListener listener) {
        super(taskId, task, impl, res, listener);
        this.executor = executor;
    }

    @Override
    public String getHostName() {
        return getResourceNode().getName();
    }

    @Override
    public Implementation.TaskType getType() {
        return Implementation.TaskType.METHOD;
    }

    public T getExecutor() {
        return this.executor;
    }

    @Override
    public String toString() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}
