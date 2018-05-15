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
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.annotations.parameter.DataType;
import static es.bsc.compss.types.annotations.parameter.DataType.OBJECT_T;
import static es.bsc.compss.types.annotations.parameter.DataType.PSCO_T;
import es.bsc.compss.types.data.DataAccessId;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.implementations.MethodImplementation;
import es.bsc.compss.types.job.JobListener;
import es.bsc.compss.types.parameter.BasicTypeParameter;
import es.bsc.compss.types.parameter.DependencyParameter;
import es.bsc.compss.types.parameter.Parameter;
import es.bsc.compss.types.resources.Resource;
import java.lang.reflect.Method;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import storage.StorageException;
import storage.StorageItf;
import storage.StubItf;


public class LocalAgentJob extends AgentJob<LocalAgent> {

    protected static final Logger LOGGER = LogManager.getLogger(Loggers.COMM);
    protected static final boolean DEBUG = LOGGER.isDebugEnabled();

    private static final String PREPARING_JOB_ERROR = "Error loading values to run the action.";
    private static final String ERROR_OBTAINING_STORAGE = "Error loading values to run the action from StorageItf.";
    protected static final String ERROR_METHOD_DEFINITION = "Incompatible implementation for the resource ";
    private static final String ERROR_CLASS_REFLECTION = "Cannot get class by reflection";
    private static final String ERROR_METHOD_REFLECTION = "Cannot get method by reflection";
    private static final String ERROR_TASK_EXECUTION = "Error running the task";

    public LocalAgentJob(LocalAgent executor, int taskId, TaskDescription task, Implementation impl, Resource res, JobListener listener) {
        super(executor, taskId, task, impl, res, listener);
    }

    @Override
    public void submit() throws Exception {
        getExecutor().runJob(this);
    }

    @Override
    public void stop() throws Exception {
    }

    public void execute() throws JobExecutionException {
        System.out.println("[EXECUTION] Preparing to execution of a new Task");
        // If it is a native method, check that methodname is defined (otherwise define it from job parameters)
        // This is a workarround for Python
        MethodImplementation mImpl = (MethodImplementation) this.impl;
        // Get method definition properties
        String className = mImpl.getDeclaringClass();

        String methodName = mImpl.getAlternativeMethodName();
        if (methodName == null || methodName.isEmpty()) {
            methodName = taskParams.getName();
            mImpl.setAlternativeMethodName(taskParams.getName());
        }
        System.out.println("[EXECUTION]     Task Code: " + methodName + "@" + className);

        Parameter[] params = taskParams.getParameters();
        DependencyParameter targetParameter = null;
        DependencyParameter returnParameter = null;

        int numParams = params.length;

        boolean hasReturn = taskParams.hasReturnValue();
        Object retValue = null;
        if (hasReturn) {
            numParams--;
            returnParameter = (DependencyParameter) params[numParams];
        }

        boolean hasTarget = taskParams.hasTargetObject();
        Object target = null;
        if (hasTarget) {
            System.out.println("[EXECUTION]     Target:");
            numParams--;
            Parameter param = params[numParams];
            targetParameter = (DependencyParameter) param;
            DataType type = targetParameter.getType();
            System.out.println("[EXECUTION]             Type " + type);
            DataAccessId dAccId = targetParameter.getDataAccessId();
            System.out.println("[EXECUTION]             Access " + dAccId);

            String inRenaming;
            String renaming;
            if (dAccId instanceof DataAccessId.WAccessId) {
                throw new JobExecutionException("Target parameter is a Write access", null);
            } else if (dAccId instanceof DataAccessId.RWAccessId) {
                // Read write mode
                DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) dAccId;
                inRenaming = rwaId.getReadDataInstance().getRenaming();
                renaming = rwaId.getWrittenDataInstance().getRenaming();
            } else {
                // Read only mode
                DataAccessId.RAccessId raId = (DataAccessId.RAccessId) dAccId;
                inRenaming = raId.getReadDataInstance().getRenaming();
                renaming = inRenaming;
            }

            if (inRenaming != null) {
                String pscoId = Comm.getData(inRenaming).getId();
                if (pscoId != null) {
                    if (type.equals(DataType.OBJECT_T)) {
                        param.setType(DataType.PSCO_T);
                    }
                    // Change external object type
                    if (type.equals(DataType.FILE_T)) {
                        param.setType(DataType.EXTERNAL_OBJECT_T);
                    }
                    type = param.getType();
                }
            }
            System.out.println("[EXECUTION]             Actual Type " + type);
            String sourcePath = targetParameter.getDataTarget();
            System.out.println("[EXECUTION]             Data Target :" + targetParameter.getDataTarget());
            if (type == DataType.PSCO_T || type == DataType.EXTERNAL_OBJECT_T) {
                String pscoId = sourcePath;
                try {
                    target = StorageItf.getByID(pscoId);
                } catch (StorageException ex) {
                    throw new JobExecutionException(ERROR_OBTAINING_STORAGE, ex);
                }
            } else {
                target = Comm.getData(renaming).getValue();
            }
            System.out.println("[EXECUTION]             VALUE " + target);
        }

        Class<?>[] types = new Class<?>[numParams];
        Object[] values = new Object[numParams];

        System.out.println("[EXECUTION]     Parameters:");
        for (int parIdx = 0; parIdx < numParams; parIdx++) {
            System.out.println("[EXECUTION]         * Parameter " + parIdx + ": ");
            Parameter param = params[parIdx];
            DataType type = param.getType();
            System.out.println("[EXECUTION]             Definition Type " + type);
            switch (type) {
                case FILE_T:
                case OBJECT_T:
                case EXTERNAL_OBJECT_T:
                case PSCO_T:
                    DependencyParameter dPar = (DependencyParameter) param;

                    DataAccessId dAccId = dPar.getDataAccessId();
                    System.out.println("[EXECUTION]             Access " + dAccId);
                    String renaming = null;
                    String inRenaming;
                    if (dAccId instanceof DataAccessId.WAccessId) {
                        // Write mode
                        DataAccessId.WAccessId waId = (DataAccessId.WAccessId) dAccId;
                        inRenaming = null;
                        renaming = waId.getWrittenDataInstance().getRenaming();
                    } else if (dAccId instanceof DataAccessId.RWAccessId) {
                        // Read write mode
                        DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) dAccId;
                        inRenaming = rwaId.getReadDataInstance().getRenaming();
                        renaming = rwaId.getWrittenDataInstance().getRenaming();
                    } else {
                        // Read only mode
                        DataAccessId.RAccessId raId = (DataAccessId.RAccessId) dAccId;
                        inRenaming = raId.getReadDataInstance().getRenaming();;
                        renaming = inRenaming;
                    }

                    if (inRenaming != null) {
                        String pscoId = Comm.getData(inRenaming).getId();
                        if (pscoId != null) {
                            if (type.equals(DataType.OBJECT_T)) {
                                param.setType(DataType.PSCO_T);
                            }
                            // Change external object type
                            if (type.equals(DataType.FILE_T)) {
                                param.setType(DataType.EXTERNAL_OBJECT_T);
                            }
                            type = param.getType();
                        }
                    }
                    System.out.println("[EXECUTION]             Actual Type " + type);
                    String sourcePath = dPar.getDataTarget();
                    System.out.println("[EXECUTION]             Data Target :" + dPar.getDataTarget());
                    if (type == DataType.PSCO_T || type == DataType.EXTERNAL_OBJECT_T) {
                        String pscoId = sourcePath;
                        try {
                            values[parIdx] = StorageItf.getByID(pscoId);
                        } catch (StorageException ex) {
                            throw new JobExecutionException(ERROR_OBTAINING_STORAGE, ex);
                        }
                    } else {
                        values[parIdx] = Comm.getData(renaming).getValue();
                    }
                    System.out.println("[EXECUTION]             VALUE " + values[parIdx]);

                    types[parIdx] = values[parIdx].getClass();
                    break;
                default:
                    BasicTypeParameter btParB = (BasicTypeParameter) param;
                    values[parIdx] = btParB.getValue();
                    System.out.println("[EXECUTION]             Type " + type);
                    System.out.println("[EXECUTION]             Value " + btParB.getValue());
                    types[parIdx] = values[parIdx].getClass();
            }
        }

        if (hasReturn) {
            System.out.println("[EXECUTION]     Return:" + returnParameter.getDataAccessId());
        }

        System.out.println("[EXECUTION] Execution starts");

        Class<?> methodClass = null;
        Method method = null;
        try {
            methodClass = Class.forName(className);
        } catch (Exception e) {
            throw new JobExecutionException(ERROR_CLASS_REFLECTION, e);
        }
        method = findMethod(methodClass, methodName, numParams, types, values);
        try {
            LOGGER.info("Invoked " + method.getName() + " of " + target);
            retValue = method.invoke(target, values);
        } catch (Exception e) {
            System.out.println("[EXECUTION] Execution failed");
            throw new JobExecutionException(ERROR_TASK_EXECUTION, e);
        }
        System.out.println("[EXECUTION] Execution ends");

        System.out.println("[STAGE OUT]     Parameters:");
        for (int parIdx = 0; parIdx < numParams; parIdx++) {
            Parameter param = params[parIdx];
            DataType type = param.getType();

            switch (type) {
                case FILE_T:
                case EXTERNAL_OBJECT_T:
                case OBJECT_T:
                case PSCO_T:
                    Object par = values[parIdx];
                    DependencyParameter dp = (DependencyParameter) params[parIdx];
                    if (par != null && par instanceof StubItf && ((StubItf) par).getID() != null) {
                        String pscoId = ((StubItf) par).getID();
                        switch (type) {
                            case FILE_T:
                                type = DataType.EXTERNAL_OBJECT_T;
                                break;
                            case OBJECT_T:
                                type = DataType.PSCO_T;
                                break;
                        }
                        dp.setType(type);
                        dp.setDataTarget(pscoId);
                        System.out.println("[STAGE OUT]         * Parameter " + parIdx + ": ");
                        System.out.println("[STAGE OUT]             Type: " + type);
                        System.out.println("[STAGE OUT]             ID: " + pscoId);
                    } else {
                        switch (type) {
                            case EXTERNAL_OBJECT_T:
                                type = DataType.FILE_T;
                                break;
                            case PSCO_T:
                                type = DataType.OBJECT_T;
                                break;
                            default:
                        }
                        dp.setType(type);
                        System.out.println("[STAGE OUT]         * Parameter " + parIdx + ": ");
                        System.out.println("[STAGE OUT]             Type: " + type);
                        System.out.println("[STAGE OUT]             Value: " + values[parIdx]);
                    }
                    break;
                default:
            }
        }
        System.out.println("Stage OUT " + (hasTarget ? " has target" : "has not target"));
        if (hasTarget) {
            DataType type = targetParameter.getType();
            if (target != null && target instanceof StubItf && ((StubItf) target).getID() != null) {
                String pscoId = ((StubItf) target).getID();
                type = targetParameter.getType();
                if (type == OBJECT_T) {
                    type = DataType.PSCO_T;
                }
                targetParameter.setType(type);
                targetParameter.setDataTarget(pscoId);
                System.out.println("[STAGE OUT]         * Target : ");
                System.out.println("[STAGE OUT]             Type: " + type);
                System.out.println("[STAGE OUT]             ID: " + pscoId);
            } else {
                switch (type) {
                    case EXTERNAL_OBJECT_T:
                        type = DataType.FILE_T;
                        break;
                    case PSCO_T:
                        type = DataType.OBJECT_T;
                        break;
                }
                targetParameter.setType(type);
                System.out.println("[STAGE OUT]         * Target : ");
                System.out.println("[STAGE OUT]             Type: " + type);
                System.out.println("[STAGE OUT]             Value: " + target);
            }
        }
        System.out.println("Stage OUT " + (hasReturn ? " has return" : "has not return"));
        if (hasReturn) {
            DataType type = returnParameter.getType();
            if (retValue != null && retValue instanceof StubItf && ((StubItf) retValue).getID() != null) {
                String pscoId = ((StubItf) retValue).getID();
                type = returnParameter.getType();
                if (type == OBJECT_T) {
                    type = DataType.PSCO_T;
                }
                returnParameter.setType(type);
                returnParameter.setDataTarget(pscoId);
                System.out.println("[STAGE OUT]         * Return : ");
                System.out.println("[STAGE OUT]             Type: " + type);
                System.out.println("[STAGE OUT]             ID: " + pscoId);
            } else {
                targetParameter.setType(type);
                System.out.println("[STAGE OUT]         * Target : ");
                System.out.println("[STAGE OUT]             Type: " + type);
                System.out.println("[STAGE OUT]             Value: " + target);
            }
        }
    }

    private Method findMethod(Class<?> methodClass, String methodName, int numParams, Class<?>[] types, Object[] values) throws JobExecutionException {
        Method method = null;
        try {
            method = methodClass.getMethod(methodName, types);
        } catch (NoSuchMethodException | SecurityException e) {
            for (Method m : methodClass.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && numParams == m.getParameterCount()) {
                    int paramId = 0;
                    boolean isMatch = true;
                    for (java.lang.reflect.Parameter p : m.getParameters()) {
                        if (p.getType().isPrimitive()) {
                            if (p.getType() != values[paramId].getClass()) {
                                switch (p.getType().getCanonicalName()) {
                                    case "byte":
                                        isMatch = values[paramId].getClass().getCanonicalName().equals("java.lang.Byte");
                                        break;
                                    case "char":
                                        isMatch = values[paramId].getClass().getCanonicalName().equals("java.lang.Char");
                                        break;
                                    case "short":
                                        isMatch = values[paramId].getClass().getCanonicalName().equals("java.lang.Short");
                                        break;
                                    case "int":
                                        isMatch = values[paramId].getClass().getCanonicalName().equals("java.lang.Integer");
                                        break;
                                    case "long":
                                        isMatch = values[paramId].getClass().getCanonicalName().equals("java.lang.Long");
                                        break;
                                    case "float":
                                        isMatch = values[paramId].getClass().getCanonicalName().equals("java.lang.Float");
                                        break;
                                    case "double":
                                        isMatch = values[paramId].getClass().getCanonicalName().equals("java.lang.Double");
                                        break;
                                    case "boolean":
                                        isMatch = values[paramId].getClass().getCanonicalName().equals("java.lang.Boolean");
                                        break;
                                }
                            }
                        } else {
                            try {
                                p.getType().cast(values[paramId]);
                            } catch (ClassCastException cce) {
                                isMatch = false;
                                break;
                            }
                        }
                        paramId++;
                    }
                    if (isMatch) {
                        method = m;
                    }
                }
            }
            if (method == null) {
                throw new JobExecutionException(ERROR_METHOD_REFLECTION, e);
            }
        }
        return method;
    }
}
