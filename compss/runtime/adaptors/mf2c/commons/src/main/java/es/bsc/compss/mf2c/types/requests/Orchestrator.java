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

import javax.xml.bind.annotation.XmlRootElement;


@XmlRootElement(name = "Orchestrator")
public class Orchestrator {

    public static enum HttpMethod {
        GET,
        POST,
        PUT,
        DELETE
    }

    private String host;
    private HttpMethod method;
    private String operation;

    public Orchestrator() {
    }

    public Orchestrator(String host, HttpMethod method, String operation) {
        this.host = host;
        this.method = method;
        this.operation = operation;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getHost() {
        return host;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }

}
