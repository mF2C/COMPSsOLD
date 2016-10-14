package integratedtoolkit.nio.worker.executors.util;

import java.io.File;
import java.util.Iterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import integratedtoolkit.api.COMPSsRuntime.DataType;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.nio.NIOParam;
import integratedtoolkit.nio.NIOTask;
import integratedtoolkit.nio.NIOTracer;
import integratedtoolkit.nio.exceptions.JobExecutionException;
import integratedtoolkit.nio.exceptions.SerializedObjectException;
import integratedtoolkit.nio.worker.NIOWorker;
import integratedtoolkit.types.implementations.AbstractMethodImplementation;
import integratedtoolkit.types.implementations.AbstractMethodImplementation.MethodType;
import storage.StorageException;
import storage.StubItf;


public abstract class Invoker {

    protected static final Logger logger = LogManager.getLogger(Loggers.WORKER_INVOKER);

    private static final String ERROR_SERIALIZED_OBJ = "ERROR: Cannot obtain object";
    private static final String ERROR_PERSISTENT_OBJ = "ERROR: Cannot getById persistent object";
    private static final String ERROR_OUT_FILES = "ERROR: One or more OUT files have not been created by task with Method Definition [";
    
    protected static final String ERROR_METHOD_DEFINITION = "Incorrect method definition for task of type ";
    protected static final String ERROR_TASK_EXECUTION = "ERROR: Exception executing task (user code)";

    protected final NIOWorker nw;
    protected final NIOTask nt;
    protected final int[] assignedCoreUnits;
    private final boolean debug;
    
    protected final MethodType methodType;
    protected final AbstractMethodImplementation impl;
    protected final int numParams;
    protected final int totalNumberOfParams;
    protected final boolean hasTarget;
    protected final Class<?>[] types;
    protected final Object[] values;
    private final String[] renamings;
    private final boolean[] isFile;
    private final boolean[] canBePSCO; 
    private final boolean[] writeFinalValue;
    protected final TargetParam target;
    private Object retValue;
    

    public Invoker(NIOWorker nw, NIOTask nt, int[] assignedCoreUnits) throws JobExecutionException {
        this.nw = nw;
        this.nt = nt;
        this.assignedCoreUnits = assignedCoreUnits;
        
        this.debug = NIOWorker.isWorkerDebugEnabled;
        
        /* Task information **************************************** */
        this.methodType = nt.getMethodType();
        this.impl = nt.getMethodImplementation();
        this.hasTarget = nt.isHasTarget();
        this.numParams = nt.getNumParams();

        /* Parameters information ********************************** */
        this.totalNumberOfParams = this.hasTarget ? this.numParams - 1 : this.numParams; // Don't count target if needed (i.e. obj.func())
        this.types = new Class[this.totalNumberOfParams];
        this.values = new Object[this.totalNumberOfParams];
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
            System.out.println();

            System.out.print("  * Parameter values:");
            for (Object v : this.values) {
                System.out.print(" " + v);
            }
            System.out.println();
        }
        
        this.retValue = null;
    }
    
    public void processTask() throws JobExecutionException {
        /* Invoke the requested method ****************************** */
        this.retValue = invoke();
        
        /* Check SCO persistence for return and target ************** */
        checkSCOPersistence();

        /* Check files existence ************************************ */
        checkJobFiles();

        /* Write to disk the updated values ************************* */
        writeUpdatedParameters();
    }
    
    private void processParameter(NIOParam np, int i) throws JobExecutionException {
        // We need to use wrapper classes for basic types, reflection will unwrap automatically
        switch (np.getType()) {
            case BOOLEAN_T:
                types[i] = boolean.class;
                values[i] = np.getValue();
                break;
            case CHAR_T:
                types[i] = char.class;
                values[i] = np.getValue();
                break;
            case BYTE_T:
                types[i] = byte.class;
                values[i] = np.getValue();
                break;
            case SHORT_T:
                types[i] = short.class;
                values[i] = np.getValue();
                break;
            case INT_T:
                types[i] = int.class;
                values[i] = np.getValue();
                break;
            case LONG_T:
                types[i] = long.class;
                values[i] = np.getValue();
                break;
            case FLOAT_T:
                types[i] = float.class;
                values[i] = np.getValue();
                break;
            case DOUBLE_T:
                types[i] = double.class;
                values[i] = np.getValue();
                break;
            case STRING_T:
                types[i] = String.class;
                values[i] = np.getValue();
                break;
            case FILE_T:
                types[i] = String.class;
                values[i] = np.getValue();
                writeFinalValue[i] = np.isWriteFinalValue();
                break;
            case OBJECT_T:
                renamings[i] = np.getValue().toString();
                writeFinalValue[i] = np.isWriteFinalValue();

                // Get object
                Object obj;
                try {
                    obj = this.nw.getObject(renamings[i]);
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
        if (this.retValue != null) {
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

    private void checkJobFiles() throws JobExecutionException {
        // Check if all the output files have been actually created (in case user has forgotten)
        // No need to distinguish between IN or OUT files, because IN files will exist, and
        // if there's one or more missing, they will be necessarily out.
        boolean allOutFilesCreated = true;
        for (int i = 0; i < this.numParams; i++) {
            if (this.isFile[i]) {
                String filepath = (String) this.values[i];
                File f = new File(filepath);
                if (!f.exists()) {
                    StringBuilder errMsg = new StringBuilder();
                    errMsg.append("ERROR: File with path '").append(this.values[i]);
                    errMsg.append("' has not been generated for parameter number: ").append( String.valueOf(i + 1) );
                    errMsg.append(" by task with Method Definition ").append(this.impl.getMethodDefinition());

                    System.out.println(errMsg.toString());
                    System.err.println(errMsg.toString());
                    allOutFilesCreated = false;
                }
            }
        }

        if (!allOutFilesCreated) {
            throw new JobExecutionException(ERROR_OUT_FILES + this.impl.getMethodDefinition());
        }
    }

    private void writeUpdatedParameters() {

        // Write to disk the updated object parameters, if any (including the target)
        for (int i = 0; i < this.numParams; i++) {
            if (this.writeFinalValue[i]) {
                Object res = (this.hasTarget && i == this.numParams - 1) ? this.target.getValue() : this.values[i];
                // Update task params for TaskResult command
                this.nt.getParams().get(i).setValue(res);
                
                // The parameter is a file, an object that MUST be stored
                this.nw.storeObject(renamings[i], res);
            }
        }

        // Serialize the return value if existing
        // PSCOs are already stored, skip them
        if (this.retValue != null) {
            String renaming = (String) this.nt.getParams().getLast().getValue();
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