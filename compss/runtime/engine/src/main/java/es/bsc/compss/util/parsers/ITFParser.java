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
package es.bsc.compss.util.parsers;

import es.bsc.compss.loader.LoaderUtils;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.annotations.parameter.Type;
import es.bsc.compss.types.annotations.parameter.Direction;
import es.bsc.compss.types.annotations.parameter.Stream;
import es.bsc.compss.types.annotations.Constants;
import es.bsc.compss.types.annotations.Constraints;
import es.bsc.compss.types.annotations.Parameter;
import es.bsc.compss.types.annotations.SchedulerHints;
import es.bsc.compss.types.annotations.task.Binary;
import es.bsc.compss.types.annotations.task.Decaf;
import es.bsc.compss.types.annotations.task.MPI;
import es.bsc.compss.types.annotations.task.Method;
import es.bsc.compss.types.annotations.task.OmpSs;
import es.bsc.compss.types.annotations.task.OpenCL;
import es.bsc.compss.types.annotations.task.Service;
import es.bsc.compss.types.annotations.task.repeatables.Binaries;
import es.bsc.compss.types.annotations.task.repeatables.Decafs;
import es.bsc.compss.types.annotations.task.repeatables.MPIs;
import es.bsc.compss.types.annotations.task.repeatables.Methods;
import es.bsc.compss.types.annotations.task.repeatables.MultiOmpSs;
import es.bsc.compss.types.annotations.task.repeatables.OpenCLs;
import es.bsc.compss.types.annotations.task.repeatables.Services;
import es.bsc.compss.types.implementations.BinaryImplementation;
import es.bsc.compss.types.implementations.DecafImplementation;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.implementations.MPIImplementation;
import es.bsc.compss.types.implementations.MethodImplementation;
import es.bsc.compss.types.implementations.OmpSsImplementation;
import es.bsc.compss.types.implementations.OpenCLImplementation;
import es.bsc.compss.types.implementations.ServiceImplementation;
import es.bsc.compss.types.resources.MethodResourceDescription;
import es.bsc.compss.util.CoreManager;
import es.bsc.compss.util.EnvironmentLoader;
import es.bsc.compss.util.ErrorManager;

import java.lang.annotation.Annotation;
import java.util.LinkedList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class ITFParser {

    private static final Logger LOGGER = LogManager.getLogger(Loggers.TS_COMP);
    private static final boolean debug = LOGGER.isDebugEnabled();

    /**
     *
     * Loads the annotated class and initializes the data structures that contain the constraints. For each method found
     * in the annotated interface creates its signature and adds the constraints to the structures.
     *
     * @param annotItfClass package and name of the Annotated Interface class
     * @return
     */
    public static List<Integer> parseITFMethods(Class<?> annotItfClass) {
        List<Integer> updatedMethods = new LinkedList<Integer>();

        int coreCount = annotItfClass.getDeclaredMethods().length;
        if (debug) {
            LOGGER.debug("Detected methods " + coreCount);
        }

        for (java.lang.reflect.Method m : annotItfClass.getDeclaredMethods()) {
            Integer methodId = parseITFMethod(m);
            updatedMethods.add(methodId);
        }

        return updatedMethods;
    }

    /**
     * Parses a single ITF Method (can have multiple annotations)
     *
     * @param m
     * @return
     */
    private static Integer parseITFMethod(java.lang.reflect.Method m) {
        /*
         * Computes the callee method signature and checks parameter annotations
         */
        LOGGER.info("Evaluating method " + m.getName());

        StringBuilder calleeMethodSignature = new StringBuilder();
        String methodName = m.getName();
        calleeMethodSignature.append(methodName).append("(");

        /*
         * Check all annotations are valid
         */
        checkMethodAnnotation(m);

        /*
         * Load if there is any non-native annotation or not
         */
        boolean hasNonNative = checkNonNativeAnnotation(m);

        /*
         * Construct signature and check parameters
         */
        boolean[] hasAnnotations = constructSignatureAndCheckParameters(m, hasNonNative, calleeMethodSignature);
        boolean hasStreams = hasAnnotations[0];
        boolean hasPrefixes = hasAnnotations[1];

        /*
         * Check all annotations present at the method for versioning
         */
        Integer methodId = CoreManager.registerNewCoreElement(calleeMethodSignature.toString());
        if (debug) {
            LOGGER.debug("   * Method methodId = " + methodId + " has " + m.getAnnotations().length + " annotations");
        }
        if (methodId != null) {
            List<Implementation> implementations = new LinkedList<>();
            List<String> signatures = new LinkedList<>();
            checkDefinedImplementations(m, methodId, calleeMethodSignature, hasStreams, hasPrefixes, implementations, signatures);

            /*
         * Register all implementations
             */
            CoreManager.registerNewImplementations(methodId, implementations, signatures);
        } else {
            methodId = CoreManager.getCoreId(calleeMethodSignature.toString());
        }
        /*
         * Returns the assigned methodId
         */
        return methodId;
    }

    /**
     * Checks if all the annotations present in method @m are valid or not
     *
     * @param m
     */
    private static void checkMethodAnnotation(java.lang.reflect.Method m) {
        /*
         * Check that all method annotations are valid
         */
        for (Annotation annot : m.getAnnotations()) {
            if (!annot.annotationType().getName().equals(Constraints.class.getName())
                    // Simple annotations
                    && !annot.annotationType().getName().equals(Method.class.getName())
                    && !annot.annotationType().getName().equals(Service.class.getName())
                    && !annot.annotationType().getName().equals(MPI.class.getName())
                    && !annot.annotationType().getName().equals(Decaf.class.getName())
                    && !annot.annotationType().getName().equals(OmpSs.class.getName())
                    && !annot.annotationType().getName().equals(OpenCL.class.getName())
                    && !annot.annotationType().getName().equals(Binary.class.getName())
                    // Repeatable annotations
                    && !annot.annotationType().getName().equals(Methods.class.getName())
                    && !annot.annotationType().getName().equals(Services.class.getName())
                    && !annot.annotationType().getName().equals(Decafs.class.getName())
                    && !annot.annotationType().getName().equals(MultiOmpSs.class.getName())
                    && !annot.annotationType().getName().equals(OpenCLs.class.getName())
                    && !annot.annotationType().getName().equals(Binaries.class.getName())
                    // Scheduler hints
                    && !annot.annotationType().getName().equals(SchedulerHints.class.getName())) {

                ErrorManager.warn("Unrecognised annotation " + annot.annotationType().getName() + " . SKIPPING");
            }
        }
    }

    /**
     * Returns if the method @m has non native annotations or not
     *
     * @param m
     * @return
     */
    private static boolean checkNonNativeAnnotation(java.lang.reflect.Method m) {
        /*
         * Checks if there is a non-native annotation or not
         */
        for (Annotation annot : m.getAnnotations()) {
            if (annot.annotationType().getName().equals(MPI.class.getName())
                    || annot.annotationType().getName().equals(Decaf.class.getName())
                    || annot.annotationType().getName().equals(OmpSs.class.getName())
                    || annot.annotationType().getName().equals(OpenCL.class.getName())
                    || annot.annotationType().getName().equals(Binary.class.getName())
                    // Repeatable annotations
                    || annot.annotationType().getName().equals(MPIs.class.getName())
                    || annot.annotationType().getName().equals(Decafs.class.getName())
                    || annot.annotationType().getName().equals(MultiOmpSs.class.getName())
                    || annot.annotationType().getName().equals(OpenCLs.class.getName())
                    || annot.annotationType().getName().equals(Binaries.class.getName())) {

                return true;
            }
        }

        return false;
    }

    /**
     * Constructs the signature of method @m and leaves the result in calleeMethodSignature. It also returns if the
     * method has stream parameters or not
     *
     * @param m
     * @param hasNonNative
     * @param calleeMethodSignature
     * @return
     */
    private static boolean[] constructSignatureAndCheckParameters(java.lang.reflect.Method m, boolean hasNonNative,
            StringBuilder calleeMethodSignature) {

        boolean hasStreams = false;
        boolean hasPrefixes = false;

        String methodName = m.getName();
        boolean hasSTDIN = false;
        boolean hasSTDOUT = false;
        boolean hasSTDERR = false;
        int numPars = m.getParameterAnnotations().length;
        if (numPars > 0) {
            for (int i = 0; i < numPars; i++) {
                Parameter par = (Parameter) m.getParameterAnnotations()[i][0];

                Class<?> parType = m.getParameterTypes()[i];
                Type annotType = par.type();
                String type = inferType(parType, annotType);

                // Add to callee
                if (i >= 1) {
                    calleeMethodSignature.append(",");
                }
                calleeMethodSignature.append(type);

                // Check parameter stream annotation
                switch (par.stream()) {
                    case STDIN:
                        if (hasSTDIN) {
                            ErrorManager.error("Method " + methodName + " has more than one parameter annotated has Stream.STDIN");
                        }
                        hasSTDIN = true;
                        break;
                    case STDOUT:
                        if (hasSTDOUT) {
                            ErrorManager.error("Method " + methodName + " has more than one parameter annotated has Stream.STDOUT");
                        }
                        hasSTDOUT = true;
                        break;
                    case STDERR:
                        if (hasSTDERR) {
                            ErrorManager.error("Method " + methodName + " has more than one parameter annotated has Stream.STDERR");
                        }
                        hasSTDERR = true;
                        break;
                    case UNSPECIFIED:
                        break;
                }
                hasStreams = hasStreams || !par.stream().equals(Stream.UNSPECIFIED);
                hasPrefixes = hasPrefixes || !par.prefix().equals(Constants.PREFIX_EMTPY);

                // Check parameter annotation (warnings and errors)
                checkParameterAnnotation(m, par, i, hasNonNative);
            }
        }
        calleeMethodSignature.append(")");

        boolean[] hasAnnotation = {hasStreams, hasPrefixes};
        return hasAnnotation;
    }

    /**
     * Infers the type of a parameter. If the parameter is annotated as a FILE or a STRING, the type is taken from the
     * annotation. If the annotation is UNSPECIFIED, the type is taken from the formal type.
     *
     * @param formalType Formal type of the parameter
     * @param annotType Annotation type of the parameter
     * @return A String representing the type of the parameter
     */
    private static String inferType(Class<?> formalType, Type annotType) {
        if (annotType.equals(Type.UNSPECIFIED)) {
            if (formalType.isPrimitive()) {
                if (formalType.equals(boolean.class)) {
                    return "BOOLEAN_T";
                } else if (formalType.equals(char.class)) {
                    return "CHAR_T";
                } else if (formalType.equals(byte.class)) {
                    return "BYTE_T";
                } else if (formalType.equals(short.class)) {
                    return "SHORT_T";
                } else if (formalType.equals(int.class)) {
                    return "INT_T";
                } else if (formalType.equals(long.class)) {
                    return "LONG_T";
                } else if (formalType.equals(float.class)) {
                    return "FLOAT_T";
                } else {
                    // Type is double --> formalType.equals(double.class)
                    return "DOUBLE_T";
                }
            } else {
                // Object
                return "OBJECT_T";
            }
        } else {
            return annotType + "_T";
        }
    }

    /**
     * Treats and display errors and warning related to the annotation of 1 parameter of a method/service
     *
     * @param m The method or service to be checked for warnings
     * @param par The parameter to analyse
     * @param i The position of the parameter (0 for the first parameter, 1 for the second, etc.)
     * @param hasNonNative Indicates if the method has non-native annotations or not
     */
    private static void checkParameterAnnotation(java.lang.reflect.Method m, Parameter par, int i, boolean hasNonNative) {
        final String WARNING_LOCATION = "In parameter number " + (i + 1) + " of method '" + m.getName() + "' in interface '"
                + m.getDeclaringClass().toString().replace("interface ", "") + "'.";

        Type annotType = par.type();
        Direction annotDirection = par.direction();
        Stream stream = par.stream();

        boolean isOut = annotDirection.equals(Direction.OUT);
        boolean isInOut = annotDirection.equals(Direction.INOUT);

        /*
         * Type checks
         */
        if (annotType.equals(Type.STRING)) {
            // Strings are immutable
            if (isOut || isInOut) {
                ErrorManager.warn("Can't specify a String with direction OUT/INOUT since they are immutable." + ErrorManager.NEWLINE
                        + WARNING_LOCATION + ErrorManager.NEWLINE + "Using direction=IN instead.");
            }
        } else if (m.getParameterTypes()[i].isPrimitive()) {
            // Primitive types are immutable (int, boolean, long, float, char, byte, short, double)
            if (isOut || isInOut) {
                String primType = m.getParameterTypes()[i].getName();
                ErrorManager.warn("Can't specify a primitive type ('" + primType + "') with direction OUT/INOUT, "
                        + "since they are always passed by value. " + ErrorManager.NEWLINE + WARNING_LOCATION + ErrorManager.NEWLINE
                        + "Using direction=IN instead.");
            }
        } else if (annotType.equals(Type.OBJECT)) {
            // Objects are not supported as OUT parameters
            if (isOut) {
                ErrorManager.warn("Can't specify an Object with direction OUT." + ErrorManager.NEWLINE + WARNING_LOCATION
                        + ErrorManager.NEWLINE + "Using direction=INOUT instead.");
            }
        }

        /*
         * Non native tasks only support FILES as INOUT
         */
        if (hasNonNative && !annotType.equals(Type.FILE) && (isOut || isInOut)) {
            ErrorManager.error(
                    "Non-Native tasks only supports " + annotType.name() + " types in mode IN" + ErrorManager.NEWLINE + WARNING_LOCATION);
        }

        /*
         * Stream checks
         */
        if (!stream.equals(Stream.UNSPECIFIED)) {
            // Stream parameters can only be files
            if (!annotType.equals(Type.FILE)) {
                ErrorManager.error("Can't specify an Stream with type different than File." + ErrorManager.NEWLINE + WARNING_LOCATION);
            }

            switch (stream) {
                case STDIN:
                    if (isOut || isInOut) {
                        ErrorManager.error("Stream STDIN must have direction IN" + ErrorManager.NEWLINE + WARNING_LOCATION);
                    }
                    break;
                case STDOUT:
                    if (!isOut && !isInOut) {
                        ErrorManager.error("Stream STDOUT must have direction OUT or INOUT" + ErrorManager.NEWLINE + WARNING_LOCATION);
                    }
                    break;
                case STDERR:
                    if (!isOut && !isInOut) {
                        ErrorManager.error("Stream STDERR must have direction OUT or INOUT" + ErrorManager.NEWLINE + WARNING_LOCATION);
                    }
                    break;
                case UNSPECIFIED:
                    // We never reach this point since the previous if protects this case
                    break;

            }
        }
    }

    /**
     * Check all the defined implementations of the same method
     *
     * @param m
     * @param methodId
     * @param calleeMethodSignature
     * @param hasStreams
     * @param implementations
     * @param signatures
     */
    private static void checkDefinedImplementations(java.lang.reflect.Method m, Integer methodId, StringBuilder calleeMethodSignature,
            boolean hasStreams, boolean hasPrefixes, List<Implementation> implementations, List<String> signatures) {

        /*
         * Global constraints of the method
         */
        MethodResourceDescription defaultConstraints = MethodResourceDescription.EMPTY_FOR_CONSTRAINTS.copy();
        if (m.isAnnotationPresent(Constraints.class)) {
            defaultConstraints = new MethodResourceDescription(m.getAnnotation(Constraints.class));
        }

        /*
         * Check all annotations present at the method for versioning
         */
        String methodName = m.getName();
        int implId = 0;

        /*
         * METHOD
         */
        for (Method methodAnnot : m.getAnnotationsByType(Method.class)) {
            LOGGER.debug("   * Processing @Method annotation");

            // Warning for ignoring streams
            if (hasStreams) {
                ErrorManager.warn("Java method " + methodName + " does not support stream annotations. SKIPPING stream annotation");
            }

            // Warning for ignoring prefixes
            if (hasPrefixes) {
                ErrorManager.warn("Java method " + methodName + " does not support prefix annotations. SKIPPING prefix annotation");
            }

            String declaringClass = methodAnnot.declaringClass();

            String methodSignature = calleeMethodSignature.toString() + declaringClass;
            signatures.add(methodSignature);

            // Load specific method constraints if present
            MethodResourceDescription implConstraints = defaultConstraints;
            if (methodAnnot.constraints() != null) {
                implConstraints = new MethodResourceDescription(methodAnnot.constraints());
                implConstraints.mergeMultiConstraints(defaultConstraints);
            }

            // Register method implementation
            Implementation impl = new MethodImplementation(declaringClass, methodName, methodId, implId, implConstraints);
            ++implId;
            implementations.add(impl);
        }

        /*
         * SERVICE
         */
        for (Service serviceAnnot : m.getAnnotationsByType(Service.class)) {
            // Services don't have constraints
            LOGGER.debug("   * Processing @Service annotation");

            // Warning for ignoring streams
            if (hasStreams) {
                ErrorManager.warn("Java service " + methodName + " does not support stream annotations. SKIPPING stream annotation");
            }

            calleeMethodSignature.append(serviceAnnot.namespace()).append(',');
            calleeMethodSignature.append(serviceAnnot.name()).append(',');
            calleeMethodSignature.append(serviceAnnot.port());

            String serviceSignature = calleeMethodSignature.toString();
            signatures.add(serviceSignature);

            // Register service implementation
            Implementation impl = new ServiceImplementation(methodId, serviceAnnot.namespace(), serviceAnnot.name(), serviceAnnot.port(),
                    serviceAnnot.operation());
            ++implId;
            implementations.add(impl);
        }

        /*
         * MPI
         */
        for (MPI mpiAnnot : m.getAnnotationsByType(MPI.class)) {
            LOGGER.debug("   * Processing @MPI annotation");

            String binary = EnvironmentLoader.loadFromEnvironment(mpiAnnot.binary());
            String workingDir = EnvironmentLoader.loadFromEnvironment(mpiAnnot.workingDir());
            String mpiRunner = EnvironmentLoader.loadFromEnvironment(mpiAnnot.mpiRunner());

            if (mpiRunner == null || mpiRunner.isEmpty()) {
                ErrorManager.error("Empty mpiRunner annotation for method " + m.getName());
            }
            if (binary == null || binary.isEmpty()) {
                ErrorManager.error("Empty binary annotation for method " + m.getName());
            }

            LOGGER.debug("Binary: " + binary);
            LOGGER.debug("mpiRunner: " + mpiRunner);

            String mpiSignature = calleeMethodSignature.toString() + LoaderUtils.MPI_SIGNATURE;
            signatures.add(mpiSignature);

            // Load specific method constraints if present
            MethodResourceDescription implConstraints = defaultConstraints;
            if (mpiAnnot.constraints() != null) {
                implConstraints = new MethodResourceDescription(mpiAnnot.constraints());
                implConstraints.mergeMultiConstraints(defaultConstraints);
            }

            // Register method implementation
            Implementation impl = new MPIImplementation(binary, workingDir, mpiRunner, methodId, implId, implConstraints);
            ++implId;
            implementations.add(impl);
        }

        /*
         * Decaf
         */
        for (Decaf decafAnnot : m.getAnnotationsByType(Decaf.class)) {
            LOGGER.debug("   * Processing @DECAF annotation");

            String dfScript = EnvironmentLoader.loadFromEnvironment(decafAnnot.dfScript());
            String dfExecutor = EnvironmentLoader.loadFromEnvironment(decafAnnot.dfExecutor());
            String dfLib = EnvironmentLoader.loadFromEnvironment(decafAnnot.dfLib());
            String workingDir = EnvironmentLoader.loadFromEnvironment(decafAnnot.workingDir());
            String mpiRunner = EnvironmentLoader.loadFromEnvironment(decafAnnot.mpiRunner());

            if (mpiRunner == null || mpiRunner.isEmpty()) {
                ErrorManager.error("Empty mpiRunner annotation for method " + m.getName());
            }
            if (dfScript == null || dfScript.isEmpty()) {
                ErrorManager.error("Empty binary annotation for method " + m.getName());
            }

            LOGGER.debug("DF Script: " + dfScript);
            LOGGER.debug("DF Executor: " + dfExecutor);
            LOGGER.debug("DF Lib: " + dfLib);
            LOGGER.debug("mpiRunner: " + mpiRunner);

            String mpiSignature = calleeMethodSignature.toString() + LoaderUtils.DECAF_SIGNATURE;
            signatures.add(mpiSignature);

            // Load specific method constraints if present
            MethodResourceDescription implConstraints = defaultConstraints;
            if (decafAnnot.constraints() != null) {
                implConstraints = new MethodResourceDescription(decafAnnot.constraints());
                implConstraints.mergeMultiConstraints(defaultConstraints);
            }

            // Register method implementation
            Implementation impl = new DecafImplementation(dfScript, dfExecutor, dfLib, workingDir, mpiRunner, methodId, implId,
                    implConstraints);
            ++implId;
            implementations.add(impl);
        }

        /*
         * OMPSS
         */
        for (OmpSs ompssAnnot : m.getAnnotationsByType(OmpSs.class)) {
            LOGGER.debug("   * Processing @OmpSs annotation");
            String binary = EnvironmentLoader.loadFromEnvironment(ompssAnnot.binary());
            String workingDir = EnvironmentLoader.loadFromEnvironment(ompssAnnot.workingDir());

            if (binary == null || binary.isEmpty()) {
                ErrorManager.error("Empty binary annotation for method " + m.getName());
            }

            String ompssSignature = calleeMethodSignature.toString() + LoaderUtils.OMPSS_SIGNATURE;
            signatures.add(ompssSignature);

            // Load specific method constraints if present
            MethodResourceDescription implConstraints = defaultConstraints;
            if (ompssAnnot.constraints() != null) {
                implConstraints = new MethodResourceDescription(ompssAnnot.constraints());
                implConstraints.mergeMultiConstraints(defaultConstraints);
            }

            // Register method implementation
            Implementation impl = new OmpSsImplementation(binary, workingDir, methodId, implId, implConstraints);
            ++implId;
            implementations.add(impl);
        }

        /*
         * OPENCL
         */
        for (OpenCL openclAnnot : m.getAnnotationsByType(OpenCL.class)) {
            LOGGER.debug("   * Processing @OpenCL annotation");
            String kernel = EnvironmentLoader.loadFromEnvironment(openclAnnot.kernel());
            String workingDir = EnvironmentLoader.loadFromEnvironment(openclAnnot.workingDir());

            if (kernel == null || kernel.isEmpty()) {
                ErrorManager.error("Empty kernel annotation for method " + m.getName());
            }

            String openclSignature = calleeMethodSignature.toString() + LoaderUtils.OPENCL_SIGNATURE;
            signatures.add(openclSignature);

            // Load specific method constraints if present
            MethodResourceDescription implConstraints = defaultConstraints;
            if (openclAnnot.constraints() != null) {
                implConstraints = new MethodResourceDescription(openclAnnot.constraints());
                implConstraints.mergeMultiConstraints(defaultConstraints);
            }

            // Register method implementation
            Implementation impl = new OpenCLImplementation(kernel, workingDir, methodId, implId, implConstraints);
            ++implId;
            implementations.add(impl);
        }

        /*
         * BINARY
         */
        for (Binary binaryAnnot : m.getAnnotationsByType(Binary.class)) {
            LOGGER.debug("   * Processing @Binary annotation");
            String binary = EnvironmentLoader.loadFromEnvironment(binaryAnnot.binary());
            String workingDir = EnvironmentLoader.loadFromEnvironment(binaryAnnot.workingDir());

            if (binary == null || binary.isEmpty()) {
                ErrorManager.error("Empty binary annotation for method " + m.getName());
            }

            String binarySignature = calleeMethodSignature.toString() + LoaderUtils.BINARY_SIGNATURE;
            signatures.add(binarySignature);

            // Load specific method constraints if present
            MethodResourceDescription implConstraints = defaultConstraints;
            if (binaryAnnot.constraints() != null) {
                implConstraints = new MethodResourceDescription(binaryAnnot.constraints());
                implConstraints.mergeMultiConstraints(defaultConstraints);
            }

            // Register method implementation
            Implementation impl = new BinaryImplementation(binary, workingDir, methodId, implId, implConstraints);
            ++implId;
            implementations.add(impl);
        }
    }

}
