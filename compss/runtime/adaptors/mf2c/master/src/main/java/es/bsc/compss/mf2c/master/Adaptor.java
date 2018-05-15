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

import es.bsc.compss.comm.CommAdaptor;
import es.bsc.compss.exceptions.ConstructConfigurationException;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.COMPSsWorker;
import es.bsc.compss.types.data.operation.DataOperation;
import es.bsc.compss.types.resources.configuration.Configuration;
import es.bsc.compss.types.uri.MultiURI;
import es.bsc.compss.mf2c.master.configuration.AgentConfiguration;
import es.bsc.compss.types.resources.MethodResourceDescription;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

import java.util.LinkedList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class Adaptor implements CommAdaptor {

    private final String localHostName;

    // Logging
    public static final Logger logger = LogManager.getLogger(Loggers.COMM);
    public static final boolean debug = logger.isDebugEnabled();

    public Adaptor() throws Exception {
        String localHostName = System.getProperty("MF2C_HOST");
        if (localHostName == null) {
            localHostName = InetAddress.getLocalHost().getHostName();
        }
        this.localHostName = localHostName;
    }

    @Override
    public void init() {

    }

    @Override
    public Configuration constructConfiguration(Object project_properties, Object resources_properties)
            throws ConstructConfigurationException {

        HashMap<String, Object> props = (HashMap<String, Object>) project_properties;
        AgentConfiguration ac = new AgentConfiguration(this.getClass().getName());
        ac.setHost((String) props.get("host"));
        MethodResourceDescription mrd = (MethodResourceDescription) props.get("description");
        ac.setDescription(mrd);
        ac.setLimitOfTasks(mrd.getTotalCPUComputingUnits());
        return ac;
    }

    @Override
    public COMPSsWorker initWorker(String workerName, Configuration config) {
        AgentConfiguration ac = (AgentConfiguration) config;
        String hostName;
        try {
            hostName = ac.getHost();
            if (!hostName.contains("://")) {
                hostName = "http://" + hostName;
            }
            URI u = new URI(hostName);
            hostName = u.getHost();
        } catch (URISyntaxException ex) {
            hostName = ac.getHost();
        }

        if (hostName.equals(localHostName) || hostName.equals("localhost")) {
            return new LocalAgent(hostName, ac);
        } else {
            return new RemoteAgent(hostName, ac);
        }
    }

    @Override
    public void stop() {

    }

    @Override
    public LinkedList<DataOperation> getPending() {
        return null;
    }

    @Override
    public void stopSubmittedJobs() {

    }

    @Override
    public void completeMasterURI(MultiURI u) {
        // No need to do nothing
    }

}
