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
package es.bsc.compss.nio.worker.executors.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import es.bsc.compss.log.Loggers;

import es.bsc.compss.nio.NIOParam;
import es.bsc.compss.nio.NIOTask;
import es.bsc.compss.nio.NIOTracer;
import es.bsc.compss.nio.exceptions.JobExecutionException;
import es.bsc.compss.nio.exceptions.SerializedObjectException;
import es.bsc.compss.nio.worker.NIOWorker;

import es.bsc.compss.types.implementations.AbstractMethodImplementation;
import es.bsc.compss.types.implementations.AbstractMethodImplementation.MethodType;
import es.bsc.compss.types.annotations.Constants;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.annotations.parameter.Stream;

import storage.StorageException;
import storage.StubItf;


public abstract class Invoker {

    protected static final Logger LOGGER = LogManager.getLogger(Loggers.WORKER_INVOKER);

    private static final String ERROR_SERIALIZED_OBJ = "ERROR: Cannot obtain object";
    private static final String ERROR_PERSISTENT_OBJ = "ERROR: Cannot getById persistent object";

    protected static final String ERROR_METHOD_DEFINITION = "Incorrect method definition for task of type ";
    protected static final String ERROR_TASK_EXECUTION = "ERROR: Exception executing task (user code)";

    protected final NIOWorker nw;
    protected final NIOTask nt;
    protected final File taskSandboxWorkingDir;
    protected final int[] assignedCoreUnits;
    private final boolean debug;

    protected final MethodType methodType;
    protected final AbstractMethodImplementation impl;
    protected final int numParams;
    protected final int totalNumberOfParams;
    protected final boolean hasTarget;
    protected final boolean hasReturn;
    protected final Class<?>[] types;
    protected final Stream[] streams;
    protected final String[] prefixes;
    protected final Object[] values;
    private final String[] renamings;
    private final boolean[] isFile;
    private final boolean[] canBePSCO;
    private final boolean[] writeFinalValue;
    protected final TargetParam target;
    private Object retValue;


    public Invoker(NIOWorker nw, NIOTask nt, File taskSandboxWorkingDir, int[] assignedCoreUnits) throws JobExecutionException {
        this.nw = nw;
        this.nt = nt;
        this.taskSandboxWorkingDir = taskSandboxWorkingDir;
        this.assignedCoreUnits = assignedCoreUnits;

        this.debug = NIOWorker.isWorkerDebugEnabled();

        /* Task information **************************************** */
        this.methodType = nt.getMethodType();
        this.impl = nt.getMethodImplementation();
        this.hasTarget = nt.hasTarget();
        this.hasReturn = nt.hasReturn();
        this.numParams = nt.getNumParams();

        /* Parameters information ********************************** */
        this.totalNumberOfParams = this.hasTarget ? this.numParams - 1 : this.numParams; // Don't count target if needed
                                                                                         // (i.e. obj.func())
        this.types = new Class[this.totalNumberOfParams];
        this.values = new Object[this.totalNumberOfParams];
        this.streams = new Stream[this.numParams];
        this.prefixes = new String[this.numParams];
        this.renamings = new String[this.numParams];
        this.isFile = new boolean[this.numParams];
        this.canBePSCO = new boolean[this.numParams];
        this.writeFinalValue = new boolean[this.numParams]; // By default the boolean initializer is in false
        // False because basic types aren't nor written nor preserved
        this.target = new TargetParam();

        /* Parse the parameters ************************************ */
        Iterator<NIOParam> params = nt.getParams().iterator();
        for (int i = 0; i < this.numParams; i++) {
            NIOParam np = params.next();
            processParameter(np, i);
        }

        /* DEBUG information *************************************** */
        if (this.debug) {
            // Print request information
            System.out.println("WORKER - Parameters of execution:");
            System.out.println("  * Method type: " + this.methodType);
            System.out.println("  * Method definition: " + this.impl.getMethodDefinition());
            System.out.print("  * Parameter types:");
            for (int i = 0; i < this.types.length; i++) {
                System.out.print(" " + this.types[i].getName());
            }
            System.out.println("");

            System.out.print("  * Parameter values:");
            for (Object v : this.values) {
                System.out.print(" " + v);
            }
            System.out.println("");

            System.out.print("  * Parameter streams:");
            for (Stream s : this.streams) {
                System.out.print(" " + s.name());
            }
            System.out.println("");

            System.out.print("  * Parameter prefixes:");
            for (String s : this.prefixes) {
                System.out.print(" " + s);
            }
            System.out.println("");

            System.out.println("  * Has Target: " + this.hasTarget);
            System.out.println("  * Has Return: " + this.hasReturn);
        }

        this.retValue = null;
    }

    public void processTask() throws JobExecutionException {
        /* Invoke the requested method ****************************** */
        this.retValue = invoke();

        /* Check SCO persistence for return and target ************** */
        checkSCOPersistence();

        /* Write to disk the updated values ************************* */
        writeUpdatedParameters();
    }

    public void serializeBinaryExitValue() throws JobExecutionException {
        LOGGER.debug("Checking binary exit value serialization");

        NIOParam lastParam = nt.getParams().getLast();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("- Param Type: " + lastParam.getType().name());
            LOGGER.debug("- Preserve source data: " + lastParam.isPreserveSourceData());
            LOGGER.debug("- Write final value: " + lastParam.isWriteFinalValue());
            LOGGER.debug("- Prefix: " + lastParam.getPrefix());
        }
        
        // Last parameter is a FILE, direction OUT, with skip prefix => return in Python
        if (lastParam.getType().equals(DataType.FILE_T) && !lastParam.isPreserveSourceData() && lastParam.isWriteFinalValue()
                && lastParam.getPrefix().equals(Constants.PREFIX_SKIP)) {

            // Write exit value to the file
            String renaming = lastParam.getOriginalName();
            LOGGER.info("Writing Binary Exit Value (" + this.retValue.toString() + ") to " + renaming);
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(renaming))) {
                String value = "I" + this.retValue.toString() + "\n.\n";
                writer.write(value);
                writer.flush();
            } catch (IOException ioe) {
                throw new JobExecutionException("ERROR: Cannot serialize binary exit value for bindings", ioe);
            }
        }
    }

    private void processParameter(NIOParam np, int i) throws JobExecutionException {
        // We need to use wrapper classes for basic types, reflection will unwrap automatically
        this.streams[i] = np.getStream();
        this.prefixes[i] = np.getPrefix();

        switch (np.getType()) {
            case BOOLEAN_T:
                this.types[i] = boolean.class;
                this.values[i] = np.getValue();
                break;
            case CHAR_T:
                this.types[i] = char.class;
                this.values[i] = np.getValue();
                break;
            case BYTE_T:
                this.types[i] = byte.class;
                this.values[i] = np.getValue();
                break;
            case SHORT_T:
                this.types[i] = short.class;
                this.values[i] = np.getValue();
                break;
            case INT_T:
                this.types[i] = int.class;
                this.values[i] = np.getValue();
                break;
            case LONG_T:
                this.types[i] = long.class;
                this.values[i] = np.getValue();
                break;
            case FLOAT_T:
                this.types[i] = float.class;
                this.values[i] = np.getValue();
                break;
            case DOUBLE_T:
                this.types[i] = double.class;
                this.values[i] = np.getValue();
                break;
            case STRING_T:
                this.types[i] = String.class;
                this.values[i] = np.getValue();
                break;
            case FILE_T:
                this.types[i] = String.class;
                this.values[i] = np.getOriginalName();
                this.writeFinalValue[i] = np.isWriteFinalValue();
                break;
            case OBJECT_T:
                this.renamings[i] = np.getValue().toString();
                this.writeFinalValue[i] = np.isWriteFinalValue();

                // Get object
                Object obj;
                try {
                    obj = this.nw.getObject(this.renamings[i]);
                } catch (SerializedObjectException soe) {
                    throw new JobExecutionException(ERROR_SERIALIZED_OBJ, soe);
                }

                // Check if object is null
                if (obj == null) {
                    // Try if renaming refers to a PSCOId that is not catched
                    // This happens when 2 tasks have an INOUT PSCO that is persisted within the 1st task
                    try {
                        obj = this.nw.getPersistentObject(renamings[i]);
                    } catch (StorageException se) {
                        throw new JobExecutionException(ERROR_SERIALIZED_OBJ, se);
                    }
                }

                // Check if object is still null
                if (obj == null) {
                    StringBuilder sb = new StringBuilder();
                    if (this.hasTarget && i == this.numParams - 1) {
                        sb.append("Target object");
                    } else {
                        sb.append("Object parameter ").append(i);
                    }
                    sb.append(" with renaming ").append(this.renamings[i]);
                    sb.append(" in MethodDefinition ").append(this.impl.getMethodDefinition());
                    sb.append(" is null!").append("\n");

                    throw new JobExecutionException(sb.toString());
                }

                // Store information as target or as normal parameter
                if (this.hasTarget && i == this.numParams - 1) {
                    // Last parameter is the target object
                    this.target.setValue(obj);
                } else {
                    // Any other parameter
                    this.types[i] = obj.getClass();
                    this.values[i] = obj;
                }
                break;
            case PSCO_T:
                this.renamings[i] = np.getValue().toString();
                this.writeFinalValue[i] = np.isWriteFinalValue();

                // Get ID
                String id = this.renamings[i];

                // Get Object
                try {
                    obj = this.nw.getPersistentObject(id);
                } catch (StorageException e) {
                    throw new JobExecutionException(ERROR_PERSISTENT_OBJ + " with id " + id, e);
                }

                // Check if object is null
                if (obj == null) {
                    StringBuilder sb = new StringBuilder();
                    if (this.hasTarget && i == this.numParams - 1) {
                        sb.append("Target PSCO");
                    } else {
                        sb.append("PSCO parameter ").append(i);
                    }
                    sb.append(" with renaming ").append(this.renamings[i]);
                    sb.append(" in MethodDefinition ").append(this.impl.getMethodDefinition());
                    sb.append(" is null!").append("\n");

                    throw new JobExecutionException(sb.toString());
                }

                // Store information as target or as normal parameter
                if (this.hasTarget && i == this.numParams - 1) {
                    // Last parameter is the target object
                    this.target.setValue(obj);
                } else {
                    // Any other parameter
                    this.types[i] = obj.getClass();
                    this.values[i] = obj;
                }
                break;
            case EXTERNAL_OBJECT_T:
                this.types[i] = String.class;
                this.values[i] = np.getValue();
                this.writeFinalValue[i] = np.isWriteFinalValue();
                break;
        }

        this.isFile[i] = (np.getType().equals(DataType.FILE_T));
        this.canBePSCO[i] = (np.getType().equals(DataType.OBJECT_T)) || (np.getType().equals(DataType.PSCO_T));
    }

    private void checkSCOPersistence() {
        // Check all parameters and target
        for (int i = 0; i < this.numParams; i++) {
            if (this.canBePSCO[i] && this.writeFinalValue[i]) {
                // Get information as target or as normal parameter
                Object obj = null;
                if (this.hasTarget && i == this.numParams - 1) {
                    obj = this.target.getValue();
                } else {
                    obj = this.values[i];
                }

                // Check if it is a PSCO and has been persisted in task
                String id = null;
                try {
                    StubItf psco = (StubItf) obj;
                    id = psco.getID();
                } catch (Exception e) {
                    // No need to raise an exception because normal objects are not PSCOs
                    id = null;
                }

                // Update to PSCO if needed
                if (id != null) {
                    // Object has been persisted, we store the PSCO and change the value to its ID
                    this.nw.storePersistentObject(id, obj);

                    if (this.hasTarget && i == this.numParams - 1) {
                        this.target.setValue(id);
                    } else {
                        this.values[i] = id;
                    }
                    this.nt.getParams().get(i).setType(DataType.PSCO_T);
                    this.nt.getParams().get(i).setValue(id);

                    // We set it as non writable because we have already stored it
                    this.writeFinalValue[i] = false;
                }
            }
        }

        // Check return
        if (this.hasReturn && this.retValue != null) {
            // Check if it is a PSCO and has been persisted in task
            String id = null;
            try {
                StubItf psco = (StubItf) this.retValue;
                id = psco.getID();
            } catch (Exception e) {
                // No need to raise an exception because normal objects are not PSCOs
                id = null;
            }

            // Update to PSCO if needed
            if (id != null) {
                // Object has been persisted
                this.nt.getParams().getLast().setType(DataType.PSCO_T);
                this.nt.getParams().getLast().setValue(id);
            }
        }
    }

    private void writeUpdatedParameters() {
        // Write to disk the updated object parameters, if any (including the target)
        for (int i = 0; i < this.numParams; i++) {
            if (this.writeFinalValue[i]) {
                switch (this.nt.getParams().get(i).getType()) {
                    case FILE_T:
                        this.nw.storeObject(renamings[i], this.nt.getParams().get(i).getValue());
                        break;
                    default:
                        // Update task parameters for TaskResult command
                        Object res = (this.hasTarget && i == this.numParams - 1) ? this.target.getValue() : this.values[i];
                        this.nt.getParams().get(i).setValue(res);
                        this.nw.storeObject(renamings[i], res);
                }

            }
        }

        // Serialize the return value if existing
        // PSCOs are already stored, skip them
        if (this.hasReturn && this.retValue != null) {
            String renaming = (String) this.nt.getParams().getLast().getValue();
            if (debug) {
                LOGGER.debug("Store return value " + this.retValue + " as " + renaming);
            }
            // Always stored because it can only be a OUT object
            this.nw.storeObject(renaming.substring(renaming.lastIndexOf('/') + 1), this.retValue);
        }
    }

    private Object invoke() throws JobExecutionException {
        emitStartTask();
        try {
            return invokeMethod();
        } catch (JobExecutionException jee) {
            throw jee;
        } finally {
            emitEndTask();
        }
    }

    private void emitStartTask() {
        int taskType = this.nt.getTaskType() + 1; // +1 Because Task ID can't be 0 (0 signals end task)
        int taskId = this.nt.getTaskId();

        // TRACING: Emit start task
        if (NIOTracer.isActivated()) {
            NIOTracer.emitEventAndCounters(taskType, NIOTracer.getTaskEventsType());
            NIOTracer.emitEvent(taskId, NIOTracer.getTaskSchedulingType());
        }
    }

    private void emitEndTask() {
        // TRACING: Emit end task
        if (NIOTracer.isActivated()) {
            NIOTracer.emitEventAndCounters(NIOTracer.EVENT_END, NIOTracer.getTaskEventsType());
            NIOTracer.emitEvent(NIOTracer.EVENT_END, NIOTracer.getTaskSchedulingType());
        }
    }

    public abstract Object invokeMethod() throws JobExecutionException;

}
