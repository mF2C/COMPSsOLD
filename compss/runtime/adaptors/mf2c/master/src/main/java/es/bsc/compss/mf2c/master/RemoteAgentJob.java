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
import static es.bsc.compss.types.annotations.parameter.DataType.OBJECT_T;
import es.bsc.compss.types.data.DataAccessId;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.implementations.MethodImplementation;
import es.bsc.compss.types.job.JobListener;
import es.bsc.compss.types.job.JobListener.JobEndStatus;
import es.bsc.compss.types.parameter.BasicTypeParameter;
import es.bsc.compss.types.parameter.DependencyParameter;
import es.bsc.compss.types.parameter.Parameter;
import es.bsc.compss.types.resources.MethodResourceDescription;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.util.Debugger;
import java.io.IOException;
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

    public static void finishedRemoteJob(String jobId, JobListener.JobEndStatus endStatus, String[] paramResults) {
        RemoteAgentJob job = SUBMITTED_JOBS.remove(jobId);
        if (job == null) {
            return;
        }
        job.stageout(paramResults);
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

        Debugger.debug("SUBMISSION", "Execution Agent: " + wt.getUri().toString());

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
        Debugger.debug("SUBMISSION", "Task Code: " + methodName + "@" + className);

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

        Debugger.debug("SUBMISSION", "Parameters:");
        for (int parIdx = 0; parIdx < numParams; parIdx++) {
            Debugger.debug("SUBMISSION", "     * Parameter " + parIdx + ": ");
            Parameter param = params[parIdx];
            DataType type = param.getType();
            Debugger.debug("SUBMISSION", "         Type " + type);
            switch (type) {
                case FILE_T:
                case OBJECT_T:
                case EXTERNAL_OBJECT_T:
                case PSCO_T:
                    DependencyParameter dPar = (DependencyParameter) param;
                    DataAccessId dAccId = dPar.getDataAccessId();
                    String inRenaming;
                    String renaming;
                    if (dAccId instanceof DataAccessId.WAccessId) {
                        throw new JobExecutionException("Target parameter is a Write access", null);
                    } else if (dAccId instanceof DataAccessId.RWAccessId) {
                        // Read write mode
                        DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) dAccId;
                        inRenaming = rwaId.getReadDataInstance().getRenaming();
                        renaming = rwaId.getWrittenDataInstance().getRenaming();
                    } else {
                        // Read only mode
                        DataAccessId.RAccessId raId = (DataAccessId.RAccessId) dAccId;
                        inRenaming = raId.getReadDataInstance().getRenaming();
                        renaming = inRenaming;
                    }

                    if (inRenaming != null) {
                        String pscoId = Comm.getData(inRenaming).getId();
                        if (pscoId != null) {
                            if (type.equals(DataType.OBJECT_T)) {
                                param.setType(DataType.PSCO_T);
                            }
                            // Change external object type
                            if (type.equals(DataType.FILE_T)) {
                                param.setType(DataType.EXTERNAL_OBJECT_T);
                            }
                            type = param.getType();
                        }
                    }
                    if (type == DataType.PSCO_T || type == DataType.EXTERNAL_OBJECT_T) {
                        Object value;
                        Debugger.debug("SUBMISSION", "         Access " + dAccId);
                        value = dPar.getDataTarget();
                        Debugger.debug("SUBMISSION", "         ID " + value);
                        sar.addPersistedParameter((String) value, param.getDirection());
                    } else {
                        throw new UnsupportedOperationException("Non-persisted DependencyParameters are not supported yet");
                    }
                    break;
                default:
                    BasicTypeParameter btParB = (BasicTypeParameter) param;
                    Object value = btParB.getValue();
                    Debugger.debug("SUBMISSION", "         Value " + value);
                    sar.addParameter(btParB, value);
            }
        }

        Resource r = new Resource();
        r.setName(getExecutor().getName());
        r.setDescription((MethodResourceDescription) impl.getRequirements());
        sar.setResources(new Resource[]{r});
        sar.setOrchestrator(MF2C_LOCALHOST_RESOURCE, Orchestrator.HttpMethod.PUT, "COMPSs/endApplication/");

        Debugger.debugAsXML(sar);

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

    private void stageout(String[] paramResults) {
        Parameter[] params = taskParams.getParameters();
        int numParams = params.length;

        boolean hasReturn = taskParams.hasReturnValue();
        boolean hasTarget = taskParams.hasTargetObject();

        if (hasReturn) {
            numParams--;
            DependencyParameter returnParameter = (DependencyParameter) taskParams.getParameters()[numParams];
            DataType type = returnParameter.getType();
            String locString = paramResults[numParams];
            if (locString != null) {
                SimpleURI uri = new SimpleURI(locString);
                try {
                    DataLocation loc = DataLocation.createLocation(worker, uri);
                    if (loc.getProtocol() == DataLocation.Protocol.PERSISTENT_URI) {
                        String pscoId = loc.getLocationKey();
                        type = returnParameter.getType();
                        if (type == OBJECT_T) {
                            type = DataType.PSCO_T;
                        }
                        returnParameter.setType(type);
                        returnParameter.setDataTarget(pscoId);
                        Debugger.debug("STAGE OUT", "         * Return : ");
                        Debugger.debug("STAGE OUT", "             Type: " + type);
                        Debugger.debug("STAGE OUT", "             ID: " + pscoId);
                    } else {
                        returnParameter.setType(type);
                        Debugger.debug("STAGE OUT", "         * Return : ");
                        Debugger.debug("STAGE OUT", "             Type: " + type);
                        Debugger.debug("STAGE OUT", "             Value location: " + loc);

                    }
                } catch (IOException ioe) {
                    Debugger.err("STAGE OUT", " ERROR PROCESSING TASK RESULT");
                }
            }
        }

        if (hasTarget) {
            numParams--;
            DependencyParameter targetParameter = (DependencyParameter) taskParams.getParameters()[numParams];
            DataType type = targetParameter.getType();
            String locString = paramResults[numParams];
            if (locString != null) {
                SimpleURI uri = new SimpleURI(locString);
                try {
                    DataLocation loc = DataLocation.createLocation(worker, uri);
                    if (loc.getProtocol() == DataLocation.Protocol.PERSISTENT_URI) {
                        String pscoId = loc.getLocationKey();
                        type = targetParameter.getType();
                        if (type == OBJECT_T) {
                            type = DataType.PSCO_T;
                        }
                        targetParameter.setType(type);
                        targetParameter.setDataTarget(pscoId);
                        Debugger.debug("STAGE OUT", "         * Return : ");
                        Debugger.debug("STAGE OUT", "             Type: " + type);
                        Debugger.debug("STAGE OUT", "             ID: " + pscoId);
                    } else {
                        targetParameter.setType(type);
                        Debugger.debug("STAGE OUT", "         * Return : ");
                        Debugger.debug("STAGE OUT", "             Type: " + type);
                        Debugger.debug("STAGE OUT", "             Value location: " + loc);

                    }
                } catch (IOException ioe) {
                    Debugger.err("STAGE OUT", " ERROR PROCESSING TASK TARGET");
                }
            }
        }

        Debugger.debug("STAGE OUT", "     Parameters:");
        for (int parIdx = 0; parIdx < numParams; parIdx++) {
            Parameter param = params[parIdx];
            DataType type = param.getType();

            switch (type) {
                case FILE_T:
                case EXTERNAL_OBJECT_T:
                case OBJECT_T:
                case PSCO_T:

                    DependencyParameter dp = (DependencyParameter) params[parIdx];
                    String locString = paramResults[parIdx];
                    if (locString != null) {
                        SimpleURI uri = new SimpleURI(locString);
                        try {
                            DataLocation loc = DataLocation.createLocation(worker, uri);
                            if (loc.getProtocol() == DataLocation.Protocol.PERSISTENT_URI) {
                                String pscoId = loc.getLocationKey();
                                switch (type) {
                                    case FILE_T:
                                        type = DataType.EXTERNAL_OBJECT_T;
                                        break;
                                    case OBJECT_T:
                                        type = DataType.PSCO_T;
                                        break;
                                }
                                dp.setType(type);
                                dp.setDataTarget(pscoId);
                                Debugger.debug("STAGE OUT", "         * Parameter " + parIdx + ": ");
                                Debugger.debug("STAGE OUT", "             Type: " + type);
                                Debugger.debug("STAGE OUT", "             ID: " + pscoId);
                            } else {
                                switch (type) {
                                    case EXTERNAL_OBJECT_T:
                                        type = DataType.FILE_T;
                                        break;
                                    case PSCO_T:
                                        type = DataType.OBJECT_T;
                                        break;
                                    default:
                                }
                                dp.setType(type);
                                Debugger.debug("STAGE OUT", "         * Parameter " + parIdx + ": ");
                                Debugger.debug("STAGE OUT", "             Type: " + type);
                                Debugger.debug("STAGE OUT", "             Value location: " + loc);
                            }
                        } catch (IOException ioe) {
                            Debugger.err("STAGE OUT", " ERROR PROCESSING TASK PARAMETER " + parIdx);
                        }
                        break;
                    }
                default:
            }
        }

    }

}
