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
package es.bsc.compss.mf2c.types.requests;

import es.bsc.compss.mf2c.types.ApplicationParameter;
import es.bsc.compss.mf2c.types.ApplicationParameterValue;
import es.bsc.compss.mf2c.types.ApplicationParameterValue.ArrayParameter;
import es.bsc.compss.mf2c.types.Resource;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.annotations.parameter.Direction;
import java.io.Serializable;
import java.util.Arrays;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;


@XmlRootElement(name = "startApplication")
public class StartApplicationRequest implements Serializable {

    private String serviceInstanceId;
    private String ceiClass;
    private String className;
    private String methodName;
    private ApplicationParameter[] params = new ApplicationParameter[0];
    private Resource[] resources;
    private Orchestrator orchestrator;

    public StartApplicationRequest() {

    }

    public void setServiceInstanceId(String serviceInstanceId) {
        this.serviceInstanceId = serviceInstanceId;
    }

    public String getServiceInstanceId() {
        return serviceInstanceId;
    }

    @XmlElementWrapper(name = "resources")
    @XmlElements({
        @XmlElement(name = "resource")})
    public Resource[] getResources() {
        return resources;
    }

    public void setResources(Resource[] resources) {
        this.resources = resources;
    }

    public String getCeiClass() {
        return ceiClass;
    }

    public void setCeiClass(String ceiClass) {
        this.ceiClass = ceiClass;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public void addParameter(Object value) {
        addParameter(value, Direction.IN);
    }

    public void addParameter(Object value, Direction direction) {
        addParameter(value, Direction.IN, DataType.OBJECT_T);
    }

    public void addParameter(Object value, Direction direction, DataType type) {
        ApplicationParameter p = new ApplicationParameter(value, direction, type);
        p.setParamId(params.length);

        ApplicationParameter[] oldParams = params;
        params = new ApplicationParameter[oldParams.length + 1];
        if (oldParams.length > 0) {
            System.arraycopy(oldParams, 0, params, 0, oldParams.length);
        }
        params[oldParams.length] = p;
    }

    @XmlElementWrapper(name = "parameters")
    public ApplicationParameter[] getParams() {
        return params;
    }

    public void setParams(ApplicationParameter[] params) {
        this.params = params;
    }

    public ApplicationParameterValue[] getParamsValues() throws ClassNotFoundException {
        int paramsCount = params.length;
        ApplicationParameterValue[] paramValues = new ApplicationParameterValue[paramsCount];
        for (ApplicationParameter param : params) {
            int paramIdx = param.getParamId();
            paramValues[paramIdx] = param.getValue();
        }
        return paramValues;
    }

    public Object[] getParamsValuesContent() throws ClassNotFoundException {
        int paramsCount = params.length;
        Object[] paramValues = new Object[paramsCount];
        for (ApplicationParameter param : params) {
            int paramIdx = param.getParamId();
            paramValues[paramIdx] = param.getValue().getValue();
        }
        return paramValues;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("StartApplication ")
                .append(className)
                .append(".")
                .append(methodName)
                .append("(");

        for (ApplicationParameter param : this.params) {
            if (param.getValue() instanceof ArrayParameter) {
                sb.append("(").append(param.getType()).append(") XXXXX");
            } else {
                sb.append("(").append(param.getType()).append(") ");
                try {
                    sb.append(param.getValue());
                } catch (Exception e) {
                    sb.append("XXXXXXXXX");
                }
            }
        }
        sb.append(") defined in CEI ").append(ceiClass).append(" using ").append(Arrays.toString(resources));
        return sb.toString();
    }

    public void setOrchestrator(String host, Orchestrator.HttpMethod method, String operation) {
        this.orchestrator = new Orchestrator(host, method, operation);
    }

    public void setOrchestrator(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public Orchestrator getOrchestrator() {
        return orchestrator;
    }

}
