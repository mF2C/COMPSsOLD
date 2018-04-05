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
package es.bsc.compss.agent.clients;

import es.bsc.compss.mf2c.types.Resource;
import es.bsc.compss.mf2c.types.exceptions.ServiceException;
import es.bsc.compss.mf2c.types.requests.StartApplicationRequest;
import es.bsc.compss.types.resources.MethodResourceDescription;
import es.bsc.compss.types.resources.components.Processor;
import es.bsc.compss.util.Serializer;
import java.util.UUID;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import org.glassfish.jersey.client.ClientConfig;


public class RESTClient {
    
    private static final ClientConfig config = new ClientConfig();
    private static final Client client = ClientBuilder.newClient(config);
    private static final WebTarget target = client.target("http://localhost:46100");
    
    public static void main(String[] args) throws Exception {
        //System.out.println("Resultat TEST:" + test());
        //System.out.println("Resultat TASK:" + startLocalTask());
        //System.out.println("Resultat TASK REMOTE:" + startRemoteTask());
        //System.out.println("Resultat APP:" + startLocalApplication());
        //System.out.println("Resultat APP REMOTE:" + startRemoteApplication());
        //System.out.println("Resultat APP MIXED:" + startMixedApplication());
        System.out.println("Resultat APP CONTAINERS:" + startContainersApplication());
    }
    
    public static String test() throws Exception {
        WebTarget wt = target.path("/COMPSs/test/");
        Response response = wt
                .request(MediaType.APPLICATION_JSON)
                .get(Response.class);
        if (response.getStatusInfo().getStatusCode() != 200) {
            throw new ServiceException(response.getStatusInfo().getReasonPhrase() + " - " + response.readEntity(String.class));
        }
        return response.readEntity(String.class);
    }
    
    public static String startLocalApplication() throws Exception {
        StartApplicationRequest sar = new StartApplicationRequest();
        WebTarget wt = target.path("/COMPSs/startApplication/");
        
        sar.setClassName("es.bsc.compss.test.Test");
        sar.setMethodName("main");
        sar.setCeiClass("es.bsc.compss.test.TestItf");
        sar.addParameter(new String[]{"3"});
        
        Resource r = new Resource();
        r.setName("COMPSsWorker01:8080");
        r.setDescription(createResourceDescription(2, 4f));
        sar.setResources(new Resource[]{r});
        
        JAXBContext jaxbContext = JAXBContext.newInstance(StartApplicationRequest.class);
        Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
        jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        jaxbMarshaller.marshal(sar, System.out);
        
        Response response = wt
                .request(MediaType.APPLICATION_JSON)
                .put(Entity.xml(sar), Response.class);
        if (response.getStatusInfo().getStatusCode() != 200) {
            throw new ServiceException(response.getStatusInfo().getReasonPhrase() + " - " + response.readEntity(String.class));
        }
        return response.readEntity(String.class);
    }
    
    public static String startRemoteApplication() throws Exception {
        StartApplicationRequest sar = new StartApplicationRequest();
        WebTarget wt = target.path("/COMPSs/startApplication/");
        
        sar.setClassName("es.bsc.compss.test.Test");
        sar.setMethodName("main");
        sar.setCeiClass("es.bsc.compss.test.TestItf");
        sar.addParameter(new String[]{"3"});
        
        Resource r = new Resource();
        r.setName("COMPSsWorker02:1200");
        r.setDescription(createResourceDescription(2, 4f));
        sar.setResources(new Resource[]{r});
        
        Response response = wt
                .request(MediaType.APPLICATION_JSON)
                .put(Entity.text(Serializer.serialize(sar)), Response.class);
        if (response.getStatusInfo().getStatusCode() != 200) {
            throw new ServiceException(response.getStatusInfo().getReasonPhrase() + " - " + response.readEntity(String.class));
        }
        return response.readEntity(String.class);
    }
    
    public static String startMixedApplication() throws Exception {
        StartApplicationRequest sar = new StartApplicationRequest();
        WebTarget wt = target.path("/COMPSs/startApplication/");
        
        sar.setClassName("es.bsc.compss.test.Test");
        sar.setMethodName("main");
        sar.setCeiClass("es.bsc.compss.test.TestItf");
        sar.addParameter(new String[]{"3"});
        sar.setServiceInstanceId(UUID.randomUUID().toString());
        
        Resource master = new Resource();
        master.setName("COMPSsWorker01:8080");
        master.setDescription(createResourceDescription(1, 4f));
        
        Resource worker = new Resource();
        worker.setName("COMPSsWorker02:1200");
        worker.setDescription(createResourceDescription(1, 4f));
        
        sar.setResources(new Resource[]{master, worker});
        
        JAXBContext jaxbContext = JAXBContext.newInstance(StartApplicationRequest.class);
        Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
        jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        jaxbMarshaller.marshal(sar, System.out);
        
        Response response = wt
                .request(MediaType.APPLICATION_JSON)
                .put(Entity.xml(sar), Response.class);
        if (response.getStatusInfo().getStatusCode() != 200) {
            throw new ServiceException(response.getStatusInfo().getReasonPhrase() + " - " + response.readEntity(String.class));
        }
        return response.readEntity(String.class);
    }
    
    public static String startLocalTask() throws Exception {
        StartApplicationRequest sar = new StartApplicationRequest();
        WebTarget wt = target.path("/COMPSs/startApplication/");
        
        sar.setClassName("es.bsc.compss.test.Test");
        sar.setMethodName("test");
        
        Resource r = new Resource();
        r.setName("COMPSsWorker01:8080");
        r.setDescription(createResourceDescription(2, 4f));
        sar.setResources(new Resource[]{r});
        Response response = wt
                .request(MediaType.APPLICATION_JSON)
                .put(Entity.text(Serializer.serialize(sar)), Response.class);
        if (response.getStatusInfo().getStatusCode() != 200) {
            throw new ServiceException(response.getStatusInfo().getReasonPhrase() + " - " + response.readEntity(String.class));
        }
        return response.readEntity(String.class);
    }
    
    public static String startRemoteTask() throws Exception {
        StartApplicationRequest sar = new StartApplicationRequest();
        WebTarget wt = target.path("/COMPSs/startApplication/");
        
        sar.setClassName("es.bsc.compss.test.Test");
        sar.setMethodName("test");
        
        Resource r = new Resource();
        r.setName("COMPSsWorker02d:1200");
        r.setDescription(createResourceDescription(2, 4f));
        sar.setResources(new Resource[]{r});
        
        Response response = wt
                .request(MediaType.APPLICATION_JSON)
                .put(Entity.xml(sar), Response.class);
        if (response.getStatusInfo().getStatusCode() != 200) {
            throw new ServiceException(response.getStatusInfo().getReasonPhrase() + " - " + response.readEntity(String.class));
        }
        return response.readEntity(String.class);
    }
    
    
    
    
    public static String startContainersApplication() throws Exception {
        StartApplicationRequest sar = new StartApplicationRequest();
        WebTarget wt = target.path("/COMPSs/startApplication/");
        
        sar.setClassName("es.bsc.compss.test.Test");
        sar.setMethodName("main");
        sar.setCeiClass("es.bsc.compss.test.TestItf");
        sar.addParameter(new String[]{"3"});
        sar.setServiceInstanceId(UUID.randomUUID().toString());
        
        Resource master = new Resource();
        master.setName("172.17.0.3:46100");
        master.setDescription(createResourceDescription(1, 4f));
        
        Resource worker = new Resource();
        worker.setName("172.17.0.2:46100");
        worker.setDescription(createResourceDescription(1, 4f));
        
        sar.setResources(new Resource[]{master, worker});
        
        JAXBContext jaxbContext = JAXBContext.newInstance(StartApplicationRequest.class);
        Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
        jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        jaxbMarshaller.marshal(sar, System.out);
        
        Response response = wt
                .request(MediaType.APPLICATION_JSON)
                .put(Entity.xml(sar), Response.class);
        if (response.getStatusInfo().getStatusCode() != 200) {
            throw new ServiceException(response.getStatusInfo().getReasonPhrase() + " - " + response.readEntity(String.class));
        }
        return response.readEntity(String.class);
    }
    
    
    private static MethodResourceDescription createResourceDescription(int cpuCores, float memory) {
        MethodResourceDescription mrd = new MethodResourceDescription();
        
        Processor p = new Processor();
        p.setComputingUnits(cpuCores);
        mrd.addProcessor(p);
        
        mrd.setMemorySize(memory);
        
        return mrd;
    }
    
}
