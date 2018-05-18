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
import es.bsc.compss.types.data.operation.copy.DeferredCopy;
import es.bsc.compss.types.data.operation.copy.StorageCopy;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.job.Job;
import es.bsc.compss.types.job.JobListener;
import es.bsc.compss.types.resources.Resource;
import es.bsc.compss.types.resources.ShutdownListener;
import es.bsc.compss.util.Debugger;
import java.util.List;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import org.glassfish.jersey.client.ClientConfig;


public class RemoteAgent extends Agent {

    private static final ClientConfig config = new ClientConfig();
    private static final Client client = ClientBuilder.newClient(config);
    private final WebTarget target;

    public RemoteAgent(String name, AgentConfiguration config) {
        super(name, config);
        String host = config.getHost();
        if (!host.startsWith("http://")) {
            host = "http://" + host;
        }
        target = client.target(host);
    }

    @Override
    public void start() throws InitNodeException {
        //Should already have been started on the devices   
    }

    @Override
    public Job<?> newJob(int taskId, TaskDescription taskparams, Implementation impl, Resource res, List<String> slaveWorkersNodeNames, JobListener listener) {
        return new RemoteAgentJob(this, taskId, taskparams, impl, res, listener);
    }

    @Override
    public void stop(ShutdownListener sl) {
        sl.notifyEnd();
    }

    public WebTarget getTarget() {
        return target;
    }

    @Override
    public void obtainData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason,
            EventListener listener) {
        if (ld == null) {
            return;
        }
        Debugger.debug("stage in", "Placing data " + ld.getName() + " as " + target);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Obtain Data " + ld.getName() + " as " + target);
        }

        // If it has a PSCO location, it is a PSCO -> Order new StorageCopy
        if (ld.getId() != null) {
            orderStorageCopy(new StorageCopy(ld, source, target, tgtData, reason, listener));
        } else {
            listener.notifyFailure(new DeferredCopy(ld, source, target, tgtData, reason, listener), new Exception("Regular objects are not supported yet"));
        }
    }

}
