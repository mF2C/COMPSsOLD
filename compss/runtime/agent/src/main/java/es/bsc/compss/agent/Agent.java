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
package es.bsc.compss.agent;

import es.bsc.compss.api.COMPSsRuntime;
import es.bsc.compss.api.impl.COMPSsRuntimeImpl;
import es.bsc.compss.comm.Comm;
import es.bsc.compss.exceptions.ConstructConfigurationException;
import es.bsc.compss.agent.interaction.ServiceOperationReport;
import es.bsc.compss.mf2c.master.RemoteAgentJob;
import es.bsc.compss.mf2c.types.ApplicationParameter;
import es.bsc.compss.mf2c.types.Resource;
import es.bsc.compss.mf2c.types.requests.EndApplicationNotification;
import es.bsc.compss.mf2c.types.requests.Orchestrator;
import es.bsc.compss.mf2c.types.requests.StartApplicationRequest;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.annotations.parameter.Direction;
import es.bsc.compss.types.annotations.parameter.Stream;
import es.bsc.compss.types.job.JobListener.JobEndStatus;
import es.bsc.compss.types.resources.MethodResourceDescription;
import es.bsc.compss.types.resources.DynamicMethodWorker;
import es.bsc.compss.types.resources.components.Processor;
import es.bsc.compss.types.resources.configuration.MethodConfiguration;
import es.bsc.compss.util.Debugger;
import es.bsc.compss.util.ResourceManager;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.client.ClientConfig;
import storage.StorageException;
import storage.StorageItf;


@Path("/COMPSs")
public class Agent {

    private static final COMPSsRuntimeImpl RUNTIME;
    private static final Random APP_ID_GENERATOR = new Random();

    private static final String LOCAL_HOST_NAME;

    private static final ClientConfig config = new ClientConfig();
    private static final Client client = ClientBuilder.newClient(config);

    private static final String REPORT_ADDRESS;
    private static final String DEFAULT_REPORT_ADDRESS = "proxy:443";

    static {

        String reportAddress = System.getProperty("report.address");
        if (reportAddress == null) {
            reportAddress = DEFAULT_REPORT_ADDRESS;
        }
        REPORT_ADDRESS = reportAddress;
        String DC_CONF_PATH = System.getProperty("dataclay.configpath");
        Debugger.debug("AGENT", "DataClay configuration: " + DC_CONF_PATH);
        if (DC_CONF_PATH != null) {
            try {
                StorageItf.init(DC_CONF_PATH);
            } catch (StorageException se) {
                se.printStackTrace(System.err);
                System.err.println("Continuing...");
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    StorageItf.finish();
                } catch (StorageException se) {
                    se.printStackTrace(System.err);
                    System.err.println("Continuing...");
                }
            }
        });

        RUNTIME = new COMPSsRuntimeImpl();
        RUNTIME.startIT();
        RUNTIME.registerCoreElement(
                "load(OBJECT_T,OBJECT_T,STRING_T,LONG_T,STRING_T,STRING_T,OBJECT_T)",
                "load(OBJECT_T,OBJECT_T,STRING_T,LONG_T,STRING_T,STRING_T,OBJECT_T)es.bsc.compss.agent.loader.Loader",
                "",
                "METHOD",
                new String[]{"es.bsc.compss.agent.loader.Loader", "load"}
        );

        String hostName = System.getProperty("MF2C_HOST");
        if (hostName == null) {
            try {
                hostName = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                hostName = "localhost";
            }
        }
        LOCAL_HOST_NAME = hostName;
    }

    @GET
    @Path("test/")
    public Response test() {
        return Response.ok().build();
    }

    @PUT
    @Path("startApplication/")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response startApplication(StartApplicationRequest request) {
        Debugger.debug("AGENT", "Received new request:\n" + request.toString());
        if (request.getResources() == null || request.getResources().length == 0) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Empty list of resources to host the execution.").build();
        }

        Response response;
        String ceiClass = request.getCeiClass();
        if (ceiClass != null) {
            response = runMain(request);
        } else {
            response = runTask(request);
        }

        return response;
    }

    @PUT
    @Path("endApplication/")
    @Consumes(MediaType.APPLICATION_XML)
    public Response endApplication(EndApplicationNotification notification) {
        String jobId = notification.getJobId();
        JobEndStatus endStatus = notification.getEndStatus();
        String[] paramsResults = notification.getParamResults();
        RemoteAgentJob.finishedRemoteJob(jobId, endStatus, paramsResults);
        return Response.ok().build();
    }

    private static Response runMain(StartApplicationRequest request) {
        String serviceInstanceId = request.getServiceInstanceId();
        long appId = Math.abs(APP_ID_GENERATOR.nextLong());
        long mainAppId = Math.abs(APP_ID_GENERATOR.nextLong());

        String ceiClass = request.getCeiClass();
        try {
            Class<?> cei = Class.forName(ceiClass);
        } catch (ClassNotFoundException cnfe) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Could not find class " + ceiClass + " to detect internal methods.").build();
        }

        String className = request.getClassName();
        String methodName = request.getMethodName();
        Object[] params;
        try {
            params = request.getParamsValuesContent();
        } catch (Exception cnfe) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
                    "Could not recover an input parameter value. " + cnfe.getLocalizedMessage()
            ).build();
        }

        //Adding Resources
        DynamicMethodWorker masterNode = null;
        List<DynamicMethodWorker> workerNodes = null;
        try {
            masterNode = addMasterNode(mainAppId, request);
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Error creating master.").build();
        }

        if (masterNode == null) {
            Debugger.out("AGENT", " Must forward request");
        } else {
            try {
                workerNodes = addWorkerNodes(appId, request);
            } catch (Exception e) {
                e.printStackTrace();
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Error creating worker.").build();
            }

            Orchestrator orchestrator = request.getOrchestrator();
            RUNTIME.executeTask(mainAppId,
                    new MasterTaskMonitor(serviceInstanceId, methodName, mainAppId, masterNode, workerNodes, orchestrator),
                    "es.bsc.compss.agent.loader.Loader", "load", false, false, 7, new Object[]{
                        RUNTIME, DataType.OBJECT_T, Direction.IN, Stream.UNSPECIFIED, "",
                        RUNTIME, DataType.OBJECT_T, Direction.IN, Stream.UNSPECIFIED, "",
                        ceiClass, DataType.STRING_T, Direction.IN, Stream.UNSPECIFIED, "",
                        appId, DataType.LONG_T, Direction.IN, Stream.UNSPECIFIED, "",
                        className, DataType.STRING_T, Direction.IN, Stream.UNSPECIFIED, "",
                        methodName, DataType.STRING_T, Direction.IN, Stream.UNSPECIFIED, "",
                        params, DataType.OBJECT_T, Direction.IN, Stream.UNSPECIFIED, ""});
        }

        return Response.ok(appId, MediaType.TEXT_PLAIN).build();
    }

    private static List<DynamicMethodWorker> addWorkerNodes(long appId, StartApplicationRequest request) throws ConstructConfigurationException {
        List<DynamicMethodWorker> workers = new LinkedList<>();
        for (Resource r : request.getResources()) {
            HashMap<String, Object> props = new HashMap<>();
            props.put("host", r.getName());
            r.getDescription().updateProcessorCounters();
            props.put("description", r.getDescription());

            if (r.getDescription().getTotalCPUComputingUnits() > 0) {
                MethodConfiguration ac = (MethodConfiguration) Comm.constructConfiguration("es.bsc.compss.mf2c.master.Adaptor", props, "");
                String workerId = r.getName() + "_" + appId;
                DynamicMethodWorker mw = new DynamicMethodWorker(workerId, r.getDescription(), ac, new HashMap());
                ResourceManager.addDynamicWorker(mw, r.getDescription(), appId);
                ResourceManager.printResourcesState();
                workers.add(mw);
            }
        }
        return workers;
    }

    private static DynamicMethodWorker addMasterNode(long mainAppId, StartApplicationRequest request) throws ConstructConfigurationException {
        DynamicMethodWorker mw = null;
        for (Resource r : request.getResources()) {
            String hostName = r.getName();
            try {
                if (!hostName.contains("://")) {
                    hostName = "http://" + hostName;
                }
                URI u = new URI(hostName);
                hostName = u.getHost();
            } catch (URISyntaxException ex) {
                hostName = r.getName();
            }

            if (hostName.equals(LOCAL_HOST_NAME) || hostName.equals("localhost")) {
                for (Processor p : r.getDescription().getProcessors()) {
                    p.setComputingUnits(p.getComputingUnits() - 1);

                    Processor p2 = new Processor(p);
                    p2.setComputingUnits(1);
                    MethodResourceDescription mrd = new MethodResourceDescription();
                    mrd.updateProcessorCounters();
                    mrd.addProcessor(p2);

                    HashMap<String, Object> props = new HashMap<>();
                    props.put("host", r.getName());
                    props.put("description", mrd);
                    MethodConfiguration ac = (MethodConfiguration) Comm.constructConfiguration("es.bsc.compss.mf2c.master.Adaptor", props, "");
                    String workerId = r.getName() + "_" + mainAppId;
                    mw = new DynamicMethodWorker(workerId, mrd, ac, new HashMap());
                    ResourceManager.addDynamicWorker(mw, mrd, mainAppId);
                    ResourceManager.printResourcesState();
                    break;
                }
            }
        }
        return mw;
    }

    private static Response runTask(StartApplicationRequest request) {
        long appId = Math.abs(APP_ID_GENERATOR.nextLong());
        try {
            Debugger.debug("agent", "Running Task " + appId);
            List<DynamicMethodWorker> workerNodes = null;
            try {
                workerNodes = addWorkerNodes(appId, request);
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Error creating worker.").build();
            }

            String className = request.getClassName();
            String methodName = request.getMethodName();

            //PREPARING PARAMETERS
            StringBuilder typesSB = new StringBuilder();

            ApplicationParameter[] sarParams = request.getParams();

            int paramsCount = sarParams.length;
            if (request.getTarget() != null) {
                paramsCount++;
            }
            if (request.isHasResult()) {
                paramsCount++;
            }

            Object[] params = new Object[5 * paramsCount];
            int position = 0;
            for (ApplicationParameter param : sarParams) {
                if (typesSB.length() > 0) {
                    typesSB.append(",");
                }
                if (param.getType() != DataType.PSCO_T) {
                    typesSB.append(param.getType().toString());
                } else {
                    typesSB.append("OBJECT_T");
                }
                params[position] = param.getValue().getContent();
                params[position + 1] = param.getType();
                params[position + 2] = param.getDirection();
                params[position + 3] = Stream.UNSPECIFIED;
                params[position + 4] = "";
                position += 5;
            }

            if (request.getTarget() != null) {
                ApplicationParameter targetParam = request.getTarget();
                params[position] = targetParam.getValue().getContent();
                params[position + 1] = targetParam.getType();
                params[position + 2] = targetParam.getDirection();
                params[position + 3] = Stream.UNSPECIFIED;
                params[position + 4] = "";
                position += 5;
            }

            if (request.isHasResult()) {
                params[position] = null;
                params[position + 1] = DataType.OBJECT_T;
                params[position + 2] = Direction.OUT;
                params[position + 3] = Stream.UNSPECIFIED;
                params[position + 4] = "";
                position += 5;
            }

            String paramsTypes = typesSB.toString();

            RUNTIME.registerCoreElement(
                    methodName + "(" + paramsTypes + ")",
                    methodName + "(" + paramsTypes + ")" + className,
                    "",
                    "METHOD",
                    new String[]{className, methodName}
            );

            Orchestrator orchestrator = request.getOrchestrator();

            RUNTIME.executeTask(
                    appId,
                    new TaskMonitor(appId, paramsCount, null, workerNodes, orchestrator),
                    className,
                    methodName,
                    false,
                    request.getTarget() != null,
                    paramsCount, params
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
        return Response.ok(appId, MediaType.TEXT_PLAIN).build();
    }

    public static void main(String[] args) throws Exception {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        int port = Integer.parseInt(args[0]);
        System.setProperty("MF2C_PORT", args[0]);
        Server jettyServer = new Server(port);
        jettyServer.setHandler(context);

        ServletHolder jerseyServlet = context.addServlet(org.glassfish.jersey.servlet.ServletContainer.class, "/*");
        jerseyServlet.setInitOrder(0);

        // Tells the Jersey Servlet which REST service/class to load.
        jerseyServlet.setInitParameter(
                "jersey.config.server.provider.classnames",
                Agent.class
                .getCanonicalName());

        try {
            jettyServer.start();
            jettyServer.join();
        } finally {
            jettyServer.destroy();
        }
    }


    private static abstract class AppMonitor implements COMPSsRuntime.TaskMonitor {

        private final long appId;
        private final DynamicMethodWorker masterNode;
        private final List<DynamicMethodWorker> workerNodes;

        public AppMonitor(long appId, DynamicMethodWorker masterNode, List<DynamicMethodWorker> workerNodes) {
            this.appId = appId;
            this.masterNode = masterNode;
            this.workerNodes = workerNodes;
        }

        public long getAppId() {
            return this.appId;
        }

        public DynamicMethodWorker getMasterNode() {
            return masterNode;
        }

        public List<DynamicMethodWorker> getWorkerNodes() {
            return workerNodes;
        }

        @Override
        public void onCompletion() {
            for (DynamicMethodWorker workerNode : workerNodes) {
                ResourceManager.reduceWholeWorker(workerNode);
            }
            if (masterNode != null) {
                ResourceManager.reduceWholeWorker(masterNode);
            }
            completed();
        }

        public abstract void completed();

    }


    private static class TaskMonitor extends AppMonitor {

        private final Orchestrator orchestrator;
        private final String[] paramResults;
        private boolean successful;

        public TaskMonitor(
                long appId, int numParams,
                DynamicMethodWorker masterNode, List<DynamicMethodWorker> workerNodes, Orchestrator orchestrator) {
            super(appId, masterNode, workerNodes);
            this.orchestrator = orchestrator;
            this.successful = false;
            this.paramResults = new String[numParams];
        }

        @Override
        public void onCreation() {
        }

        @Override
        public void onAccessesProcess() {
        }

        @Override
        public void onSchedule() {
        }

        @Override
        public void onSubmission() {
        }

        @Override
        public void valueGenerated(int paramId, DataType type, Object value) {
            paramResults[paramId] = value.toString();
        }

        @Override
        public void onErrorExecution() {
        }

        @Override
        public void onFailedExecution() {
            successful = false;
        }

        @Override
        public void onSuccesfulExecution() {
            successful = true;
        }

        @Override
        public void completed() {
            try {
                if (orchestrator != null) {
                    String masterId = orchestrator.getHost();
                    String operation = orchestrator.getOperation();
                    WebTarget target = client.target(masterId);
                    WebTarget wt = target.path(operation);
                    EndApplicationNotification ean = new EndApplicationNotification(
                            "" + getAppId(),
                            successful ? JobEndStatus.OK : JobEndStatus.EXECUTION_FAILED,
                            paramResults);
                    Debugger.debug("AGENT", "Submitting Job End");
                    Debugger.debugAsXML(ean);

                    Response response = wt
                            .request(MediaType.APPLICATION_JSON)
                            .put(Entity.xml(ean), Response.class);
                    if (response.getStatusInfo().getStatusCode() != 200) {
                        Debugger.err("AGENT", "Could not notify Application " + getAppId() + " end to " + wt);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private static class MasterTaskMonitor extends AppMonitor {

        private final TaskProfile profile;
        private final String serviceInstanceId;
        private final String operation;

        public MasterTaskMonitor(
                String serviceInstanceId,
                String operation,
                long appId,
                DynamicMethodWorker masterNode,
                List<DynamicMethodWorker> workerNodes,
                Orchestrator orchestrator
        ) {
            super(appId, masterNode, workerNodes);
            this.serviceInstanceId = serviceInstanceId;
            this.operation = operation;
            this.profile = new TaskProfile();
        }

        @Override
        public void onCreation() {
            profile.created();
        }

        @Override
        public void onAccessesProcess() {
            profile.processedAccesses();
        }

        @Override
        public void onSchedule() {
            profile.scheduled();
        }

        @Override
        public void onSubmission() {
            profile.submitted();
        }

        @Override
        public void valueGenerated(int paramId, DataType type, Object value) {

        }

        @Override
        public void onErrorExecution() {
            profile.finished();
        }

        @Override
        public void onFailedExecution() {
            profile.finished();
        }

        @Override
        public void onSuccesfulExecution() {
            profile.finished();
        }

        @Override
        public void completed() {
            profile.end();
            Debugger.debug("AGENT", "Execution lasted " + profile.getTotalTime());
            ServiceOperationReport report = new ServiceOperationReport(REPORT_ADDRESS, this.serviceInstanceId, this.operation, this.profile.getTotalTime());
            report.report();
        }
    }


    private static class TaskProfile {

        private final long taskReception = System.currentTimeMillis();
        private Long taskCreation = null;
        private Long taskAnalyzed = null;
        private Long taskScheduled = null;
        private Long executionStart = null;
        private Long executionEnd = null;
        private Long taskEnd = null;

        public TaskProfile() {

        }

        public void created() {
            taskCreation = System.currentTimeMillis();
        }

        public void end() {
            taskEnd = System.currentTimeMillis();
        }

        public Long getTotalTime() {
            Long length = null;
            if (taskEnd != null) {
                length = taskEnd - taskReception;
            }
            return length;
        }

        private void finished() {
            executionEnd = System.currentTimeMillis();
        }

        private void submitted() {
            executionStart = System.currentTimeMillis();
        }

        private void scheduled() {
            taskScheduled = System.currentTimeMillis();
        }

        private void processedAccesses() {
            taskAnalyzed = System.currentTimeMillis();
        }
    }
}
