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

import es.bsc.compss.comm.Comm;
import es.bsc.compss.mf2c.types.Resource;
import es.bsc.compss.mf2c.types.requests.Orchestrator;
import es.bsc.compss.mf2c.types.requests.StartApplicationRequest;
import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.data.DataAccessId;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.implementations.MethodImplementation;
import es.bsc.compss.types.job.JobListener;
import es.bsc.compss.types.job.JobListener.JobEndStatus;
import es.bsc.compss.types.parameter.BasicTypeParameter;
import es.bsc.compss.types.parameter.DependencyParameter;
import es.bsc.compss.types.parameter.Parameter;
import es.bsc.compss.types.resources.MethodResourceDescription;
import java.util.HashMap;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;


public class RemoteAgentJob extends AgentJob<RemoteAgent> {

    private static final String MF2C_LOCALHOST_RESOURCE = "http://" + System.getProperty("MF2C_HOST") + ":" + System.getProperty("MF2C_PORT") + "/";

    private static final HashMap<String, RemoteAgentJob> SUBMITTED_JOBS = new HashMap<>();

    public static void finishedRemoteJob(String jobId, JobListener.JobEndStatus endStatus) {
        RemoteAgentJob job = SUBMITTED_JOBS.remove(jobId);
        if (job == null) {
            return;
        }
        if (endStatus == JobEndStatus.OK) {
            job.getListener().jobCompleted(job);
        } else {
            job.getListener().jobFailed(job, endStatus);
        }
    }

    public RemoteAgentJob(RemoteAgent executor, int taskId, TaskDescription task, Implementation impl, es.bsc.compss.types.resources.Resource res, JobListener listener) {
        super(executor, taskId, task, impl, res, listener);
    }

    @Override
    public void submit() throws Exception {
        StartApplicationRequest sar = new StartApplicationRequest();

        WebTarget wt = getExecutor().getTarget();
        wt = wt.path("/COMPSs/startApplication/");

        MethodImplementation mImpl = (MethodImplementation) this.impl;
        // Get method definition properties
        String className = mImpl.getDeclaringClass();

        String methodName = mImpl.getAlternativeMethodName();
        if (methodName == null || methodName.isEmpty()) {
            methodName = taskParams.getName();
            mImpl.setAlternativeMethodName(taskParams.getName());
        }

        sar.setClassName(className);
        sar.setMethodName(methodName);
        sar.setCeiClass(null); //It is a task and we are not supporting Nested parallelism yet

        Parameter[] params = taskParams.getParameters();
        int numParams = params.length;
        boolean hasReturn = taskParams.hasReturnValue();
        Object retValue = null;
        if (hasReturn) {
            numParams--;
        }

        boolean hasTarget = taskParams.hasTargetObject();
        Object target = null;
        if (hasTarget) {
            numParams--;
            Parameter param = params[numParams];
            DependencyParameter dPar = (DependencyParameter) param;
            DataAccessId faId = dPar.getDataAccessId();
            String renaming;
            if (faId instanceof DataAccessId.WAccessId) {
                // Write mode
                DataAccessId.WAccessId waId = (DataAccessId.WAccessId) faId;
                renaming = waId.getWrittenDataInstance().getRenaming();
            } else if (faId instanceof DataAccessId.RWAccessId) {
                // Read write mode
                DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) faId;
                renaming = rwaId.getWrittenDataInstance().getRenaming();
            } else {
                // Read only mode
                DataAccessId.RAccessId raId = (DataAccessId.RAccessId) faId;
                renaming = raId.getReadDataInstance().getRenaming();
            }
            target = Comm.getData(renaming).getValue();
            throw new UnsupportedOperationException("Instance methods not supported yet.");
        }

        for (int parIdx = 0; parIdx < numParams; parIdx++) {
            Parameter param = params[parIdx];
            DataType type = param.getType();
            Class<?> valueType = null;
            Object value = null;
            switch (type) {
                case FILE_T:
                case OBJECT_T:
                case PSCO_T:
                case EXTERNAL_OBJECT_T:
                    throw new UnsupportedOperationException("DependencyParameters not supported yet");
                default:
                    BasicTypeParameter btParB = (BasicTypeParameter) param;
                    value = btParB.getValue();
                    valueType = value.getClass();
            }
            sar.addParameter(value);
        }

        Resource r = new Resource();
        r.setName(getExecutor().getName());
        r.setDescription((MethodResourceDescription) impl.getRequirements());
        sar.setResources(new Resource[]{r});
        sar.setOrchestrator(MF2C_LOCALHOST_RESOURCE, Orchestrator.HttpMethod.PUT, "COMPSs/endApplication/");
        
        Response response = wt
                .request(MediaType.APPLICATION_JSON)
                .put(Entity.xml(sar), Response.class);

        if (response.getStatusInfo().getStatusCode() != 200) {
            this.getListener().jobFailed(this, JobListener.JobEndStatus.SUBMISSION_FAILED);
        } else {
            String jobId = response.readEntity(String.class);
            SUBMITTED_JOBS.put(jobId, this);
        }
    }

    @Override
    public void stop() throws Exception {
    }

}
