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
import es.bsc.compss.exceptions.CannotLoadException;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.data.DataAccessId;
import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.implementations.AbstractMethodImplementation;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.implementations.MethodImplementation;
import es.bsc.compss.types.job.JobListener;
import es.bsc.compss.types.parameter.BasicTypeParameter;
import es.bsc.compss.types.parameter.DependencyParameter;
import es.bsc.compss.types.parameter.Parameter;
import es.bsc.compss.types.resources.Resource;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class LocalAgentJob extends AgentJob<LocalAgent> {

    protected static final Logger LOGGER = LogManager.getLogger(Loggers.COMM);
    protected static final boolean DEBUG = LOGGER.isDebugEnabled();

    private static final String PREPARING_JOB_ERROR = "Error loading values to run the action.";
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
        Parameter[] params = taskParams.getParameters();
        int numParams = params.length;
        boolean hasReturn = taskParams.hasReturnValue();
        Object retValue = null;
        if (hasReturn) {
            numParams--;
        }

        boolean hasTarget = taskParams.hasTargetObject();
        Object target = null;
        if (hasTarget) {
            numParams--;
            Parameter param = params[numParams];
            DependencyParameter dPar = (DependencyParameter) param;
            DataAccessId faId = dPar.getDataAccessId();
            String renaming;
            if (faId instanceof DataAccessId.WAccessId) {
                // Write mode
                DataAccessId.WAccessId waId = (DataAccessId.WAccessId) faId;
                renaming = waId.getWrittenDataInstance().getRenaming();
            } else if (faId instanceof DataAccessId.RWAccessId) {
                // Read write mode
                DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) faId;
                renaming = rwaId.getWrittenDataInstance().getRenaming();
            } else {
                // Read only mode
                DataAccessId.RAccessId raId = (DataAccessId.RAccessId) faId;
                renaming = raId.getReadDataInstance().getRenaming();
            }
            target = Comm.getData(renaming).getValue();
        }

        Class<?>[] types = new Class<?>[numParams];
        Object[] values = new Object[numParams];
        
        for (int parIdx = 0; parIdx < numParams; parIdx++) {
            Parameter param = params[parIdx];
            DataType type = param.getType();
            switch (type) {
                case FILE_T:
                case OBJECT_T:
                case PSCO_T:
                case EXTERNAL_OBJECT_T:
                    DependencyParameter dPar = (DependencyParameter) param;
                    DataAccessId faId = dPar.getDataAccessId();
                    String renaming;
                    if (faId instanceof DataAccessId.WAccessId) {
                        // Write mode
                        DataAccessId.WAccessId waId = (DataAccessId.WAccessId) faId;
                        renaming = waId.getWrittenDataInstance().getRenaming();
                    } else if (faId instanceof DataAccessId.RWAccessId) {
                        // Read write mode
                        DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) faId;
                        renaming = rwaId.getWrittenDataInstance().getRenaming();
                    } else {
                        // Read only mode
                        DataAccessId.RAccessId raId = (DataAccessId.RAccessId) faId;
                        renaming = raId.getReadDataInstance().getRenaming();
                    }
                    values[parIdx] = Comm.getData(renaming).getValue();
                    types[parIdx] = values[parIdx].getClass();
                    break;
                default:
                    BasicTypeParameter btParB = (BasicTypeParameter) param;
                    values[parIdx] = btParB.getValue();
                    types[parIdx] = values[parIdx].getClass();
            }
        }

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
            throw new JobExecutionException(ERROR_TASK_EXECUTION, e);
        } finally {

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
