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
package es.bsc.compss.nio.commands;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import es.bsc.comm.Connection;
import es.bsc.comm.nio.NIONode;


public class CommandCheckWorker extends Command implements Externalizable {

    private String uuid;
    private String nodeName;


    public CommandCheckWorker() {
        super();
    }

    public CommandCheckWorker(String uuid, String nodeName) {
        super();
        this.uuid = uuid;
        this.nodeName = nodeName;
    }

    @Override
    public CommandType getType() {
        return CommandType.CHECK_WORKER;
    }

    @Override
    public void handle(Connection c) {
        if (agent.isMyUuid(this.uuid, this.nodeName)) {
        	if (agent.getMaster() == null) {
        		agent.setMaster((NIONode) c.getNode());
        	}
            CommandCheckWorkerACK cmd = new CommandCheckWorkerACK(uuid, nodeName);
            c.sendCommand(cmd);
        }

        c.finishConnection();
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        uuid = (String) in.readUTF();
        nodeName = (String) in.readUTF();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(uuid);
        out.writeUTF(nodeName);
    }

    @Override
    public String toString() {
        return "CommandCheckWorker for deployment ID " + uuid + " on nodeName " + nodeName;
    }

}
