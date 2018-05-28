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
package es.bsc.compss.agent.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.bsc.compss.mf2c.types.exceptions.ServiceException;
import es.bsc.compss.util.Debugger;
import java.util.UUID;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientConfig;


public class ServiceOperationReport {

    private String targetAddress;
    private ServiceInstance serviceInstance;
    private String operation;
    private float execution_time;
    private AccessControlList acl = new AccessControlList();

    public ServiceOperationReport() {

    }

    public ServiceOperationReport(String targetAddress, String serviceInstanceId, String operation, long time) {
        this.targetAddress = targetAddress;
        this.serviceInstance = new ServiceInstance("service-instance/" + serviceInstanceId);
        this.operation = operation;
        this.execution_time = time;
        acl.setOwner(new Owner("ADMIN", "ROLE"));
        acl.addRule(new Rule("USER", "MODIFY", "ROLE"));
        acl.addRule(new Rule("ADMIN", "ALL", "ROLE"));
    }

    public void setServiceInstance(ServiceInstance serviceInstance) {
        this.serviceInstance = serviceInstance;
    }

    public ServiceInstance getServiceInstance() {
        return serviceInstance;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }

    public void setExecution_time(float execution_time) {
        this.execution_time = execution_time;
    }

    public float getExecution_time() {
        return execution_time;
    }

    public AccessControlList getAcl() {
        return acl;
    }

    public void setAcl(AccessControlList acl) {
        this.acl = acl;
    }

    public void report() {
        if (targetAddress != null) {
            ClientConfig config = new ClientConfig();
            Client client = ClientBuilder.newClient(config);
            WebTarget target = client.target(targetAddress);
            target = target.path("service-operation-report");
            Debugger.debug("Reporting execution time to :" + target.getUri().toString());
            Response r;

            // DEBUGGER OF SERVICE_OPERATION_REPORT IN JSON
            try {
                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(this);
                System.out.println("Publishing result of an operation execution:\n" + json);
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                r = target
                        .request(MediaType.APPLICATION_JSON)
                        .header("slipstream-authn-info", "super ADMIN")
                        .post(Entity.json(this), Response.class);

                Debugger.debug("Report status code: " + r.getStatusInfo().getStatusCode());
                if (r.getStatusInfo().getStatusCode() != 200) {
                    Debugger.err(r.getStatusInfo().getReasonPhrase());
                    Debugger.err(r.readEntity(String.class));
                } else {
                    Debugger.debug("Report published correctly");
                }
            } catch (Exception e) {
                Debugger.err(e);
            }
        }
    }

    public static void main(String[] args) {
        ServiceOperationReport report = new ServiceOperationReport("https://dashboard.mf2c-project.eu/api", UUID.randomUUID().toString(), "test", 720l);
        report.report();
    }

}
