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
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;


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
        System.out.println("Preparing to submit new Task");
        StartApplicationRequest sar = new StartApplicationRequest();

        WebTarget wt = getExecutor().getTarget();
        wt = wt.path("/COMPSs/startApplication/");

        System.out.println("    Execution Agent: " + wt.getUri().toString());

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
        System.out.println("    Task Code: " + methodName + "@" + className);

        Parameter[] params = taskParams.getParameters();
        int numParams = params.length;

        boolean hasReturn = taskParams.hasReturnValue();
        Object retValue = null;
        boolean hasTarget = taskParams.hasTargetObject();
        Object target = null;

        
        
        if (hasReturn) {
            sar.setHasResult(true);
            numParams--;
        }

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

        System.out.println("    Parameters:");
        for (int parIdx = 0; parIdx < numParams; parIdx++) {
            System.out.println("        * Parameter " + parIdx + ": ");
            Parameter param = params[parIdx];
            DataType type = param.getType();
            System.out.println("            Type " + type);
            switch (type) {
                case FILE_T:
                case OBJECT_T:
                case EXTERNAL_OBJECT_T:
                    throw new UnsupportedOperationException("Non-persisted DependencyParameters are not supported yet");
                case PSCO_T:
                    DependencyParameter dPar = (DependencyParameter) param;
                    Object value;
                    DataAccessId dAccId = dPar.getDataAccessId();
                    System.out.println("            Access " + dAccId);
                    value = dPar.getDataTarget();
                    System.out.println("            ID " + value);
                    sar.addPersistedParameter((String) value, param.getDirection());
                    break;
                default:
                    BasicTypeParameter btParB = (BasicTypeParameter) param;
                    value = btParB.getValue();
                    System.out.println("            Value " + value);
                    sar.addParameter(btParB, value);
            }
        }

        Resource r = new Resource();
        r.setName(getExecutor().getName());
        r.setDescription((MethodResourceDescription) impl.getRequirements());
        sar.setResources(new Resource[]{r});
        sar.setOrchestrator(MF2C_LOCALHOST_RESOURCE, Orchestrator.HttpMethod.PUT, "COMPSs/endApplication/");

        JAXBContext jaxbContext = JAXBContext.newInstance(StartApplicationRequest.class);
        Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
        jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        jaxbMarshaller.marshal(sar, System.out);

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
