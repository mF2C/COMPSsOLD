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
import es.bsc.compss.types.data.listener.EventListener;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.data.location.DataLocation.Protocol;
import es.bsc.compss.types.COMPSsWorker;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.data.Transferable;
import es.bsc.compss.types.resources.ExecutorShutdownListener;
import es.bsc.compss.types.uri.MultiURI;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.mf2c.master.configuration.AgentConfiguration;


public abstract class Agent extends COMPSsWorker {

    private final String name;
    private AgentConfiguration config;

    public Agent(String name, AgentConfiguration config) {
        super(name, config);
        this.name = name;
        this.config = config;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setInternalURI(MultiURI uri) {

    }

    @Override
    public void sendData(LogicalData ld, DataLocation source, DataLocation target, LogicalData tgtData, Transferable reason, EventListener listener) {
        // Never sends Data
        System.out.println("send Data ");
        System.out.println("\t Logical Data: " + ld);
        System.out.println("\t Data Source: " + source);
        System.out.println("\t Data Target: " + target);
        System.out.println("\t Target Data: " + tgtData);
    }

    @Override
    public void updateTaskCount(int processorCoreCount) {
        // No need to do anything
    }

    @Override
    public void announceDestruction() {
        // No need to do anything
    }

    @Override
    public void announceCreation() {
        // No need to do anything
    }

    @Override
    public String getUser() {
        return "";
    }

    @Override
    public SimpleURI getCompletePath(DataType type, String name) {
        // The path of the data is the same than in the master
        String path = null;
        switch (type) {
            case FILE_T:
                path = Protocol.FILE_URI.getSchema() + Comm.getAppHost().getTempDirPath() + name;
                break;
            case OBJECT_T:
                path = Protocol.OBJECT_URI.getSchema() + name;
                break;
            case PSCO_T:
                path = Protocol.PERSISTENT_URI.getSchema() + name;
                break;
            case EXTERNAL_OBJECT_T:
                path = Protocol.PERSISTENT_URI.getSchema() + name;
                break;
            default:
                return null;
        }

        // Switch path to URI
        return new SimpleURI(path);
    }

    @Override
    public void deleteTemporary() {
    }

    @Override
    public boolean generatePackage() {
        return false;
    }

    @Override
    public void shutdownExecutionManager(ExecutorShutdownListener sl) {
        sl.notifyEnd();
    }

    @Override
    public boolean generateWorkersDebugInfo() {
        return false;
    }

    @Override
    public String getClasspath() {
        // No classpath for services
        return "";
    }

    @Override
    public String getPythonpath() {
        // No pythonpath for services
        return "";
    }

}
