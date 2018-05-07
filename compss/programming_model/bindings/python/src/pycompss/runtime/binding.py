#!/usr/bin/python
#
#  Copyright 2002-2018 Barcelona Supercomputing Center (www.bsc.es)
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
# 

# -*- coding: utf-8 -*-

"""
PyCOMPSs Binding - Binding
==========================
    This file contains the Python binding auxiliary classes and methods.
"""

from pycompss.api.parameter import Parameter
from pycompss.api.parameter import TYPE
from pycompss.api.parameter import DIRECTION
from pycompss.api.parameter import JAVA_MIN_INT, JAVA_MAX_INT
from pycompss.api.parameter import JAVA_MIN_LONG, JAVA_MAX_LONG
from pycompss.util.serializer import *
from pycompss.util.sizer import total_sizeof
from pycompss.util.persistent_storage import is_PSCO, get_ID, get_by_ID

import types
import os
import sys
import re
import uuid
import inspect
import logging
import traceback
import base64

from collections import *
from shutil import rmtree

# Import main C module extension for the communication with the runtime
# See ext/compssmodule.c
import compss

# Types conversion dictionary from python to COMPSs
if sys.version_info >= (3, 0):
    str_escape = 'unicode_escape'
    python_to_compss = {int: TYPE.INT,       # int
                        int: TYPE.LONG,      # long
                        float: TYPE.DOUBLE,  # float
                        bool: TYPE.BOOLEAN,  # bool
                        str: TYPE.STRING,    # str
                        # The type of instances of user-defined classes
                        # types.InstanceType: TYPE.OBJECT,
                        # The type of methods of user-defined class instances
                        # types.MethodType: TYPE.OBJECT,
                        # The type of user-defined old-style classes
                        # types.ClassType: TYPE.OBJECT,
                        # The type of modules
                        # types.ModuleType: TYPE.OBJECT,
                        # The type of tuples (e.g. (1, 2, 3, 'Spam'))
                        tuple: TYPE.OBJECT,
                        # The type of lists (e.g. [0, 1, 2, 3])
                        list: TYPE.OBJECT,
                        # The type of dictionaries (e.g. {'Bacon': 1, 'Ham': 0})
                        dict: TYPE.OBJECT,
                        # The type of generic objects
                        object: TYPE.OBJECT
                        }
else:
    str_escape = 'string_escape'
    python_to_compss = {types.IntType: TYPE.INT,          # int
                        types.LongType: TYPE.LONG,        # long
                        types.FloatType: TYPE.DOUBLE,     # float
                        types.BooleanType: TYPE.BOOLEAN,  # bool
                        types.StringType: TYPE.STRING,    # str
                        # The type of instances of user-defined classes
                        # types.InstanceType: TYPE.OBJECT,
                        # The type of methods of user-defined class instances
                        # types.MethodType: TYPE.OBJECT,
                        # The type of user-defined old-style classes
                        # types.ClassType: TYPE.OBJECT,
                        # The type of modules
                        # types.ModuleType: TYPE.OBJECT,
                        # The type of tuples (e.g. (1, 2, 3, 'Spam'))
                        types.TupleType: TYPE.OBJECT,
                        # The type of lists (e.g. [0, 1, 2, 3])
                        types.ListType: TYPE.OBJECT,
                        # The type of dictionaries (e.g. {'Bacon': 1, 'Ham': 0})
                        types.DictType: TYPE.OBJECT,
                        # The type of generic objects
                        types.ObjectType: TYPE.OBJECT
                        }

# Set temporary dir
temp_dir = '.'
temp_obj_prefix = '/compss-serialized-obj_'

# Dictionary to contain the conversion from object id to the
# filename where it is stored (mapping).
# The filename will be used for requesting an object to
# the runtime (its corresponding version).
objid_to_filename = {}

# Dictionary that contains the objects used within tasks.
pending_to_synchronize = {}

# Objects that have been accessed by the main program
objs_written_by_mp = {}  # obj_id -> compss_file_name

# Enable or disable small objects conversion to strings
# cross-module variable (set/modified from launch.py)
object_conversion = False

# Identifier handling
current_id = 1
# Object identifiers will be of the form runtime_id-current_id
# This way we avoid to have two objects from different applications having
# the same identifier
runtime_id = str(uuid.uuid1())
id2obj = {}


def get_object_id(obj, assign_new_key=False, force_insertion=False):
    '''Gets the identifier of an object. If not found or we are forced to,
    we create a new identifier for this object, deleting the old one if
    necessary. We can also query for some object without adding it in case of
    failure.
    '''
    global current_id
    global runtime_id
    # force_insertion implies assign_new_key
    assert not force_insertion or assign_new_key
    for identifier in id2obj:
        if id2obj[identifier] is obj:
            if force_insertion:
                id2obj.pop(identifier)
                break
            else:
                return identifier
    if assign_new_key:
        # This object was not in our object database or we were forced to
        # remove it, lets assign it an identifier and store it.
        # As mentioned before, identifiers are of the form runtime_id-current_id
        # in order to avoid having two objects from different applications with
        # the same identifier (and thus file name)
        new_id = '%s-%d' % (runtime_id, current_id)
        id2obj[new_id] = obj
        current_id += 1
        return new_id
    return None


# Enable or disable the management of *args parameters as a whole tuple built
# (and serialized) on the master and sent to the workers.
# When disabled, the parameters passed to a task with *args are serialized
# independently and the tuple is built on the worker.
aargs_as_tuple = False


# Setup logger
logger = logging.getLogger(__name__)
# logger = logging.getLogger()    # for jupyter logging
# logger.setLevel(logging.DEBUG)  # for jupyter logging


# ##############################################################################
# ############################ CLASSES #########################################
# ##############################################################################
class Function_Type:
    FUNCTION = 1
    INSTANCE_METHOD = 2
    CLASS_METHOD = 3


class Future(object):
    ''' Future object class definition.
    '''
    def __init__(self):
        self.__hidden_id = str(uuid.uuid1)


class EmptyReturn(object):
    pass


# ##############################################################################
# ############ FUNCTIONS THAT COMMUNICATE WITH THE RUNTIME #####################
# ##############################################################################

def start_runtime():
    '''
    Starts the runtime by calling the external python library that calls
    the bindings-common.
    '''
    if __debug__:
        logger.info('Starting COMPSs...')
    compss.start_runtime()
    if __debug__:
        logger.info('COMPSs started')


def stop_runtime():
    '''
    Stops the runtime by calling the external python library that calls
    the bindings-common.
    Also cleans objects and temporary files created during runtime.
    '''
    if __debug__:
        logger.info('Cleaning objects...')
    clean_objects()
    if __debug__:
        logger.info('Stopping COMPSs...')
    compss.stop_runtime()
    if __debug__:
        logger.info('Cleaning temps...')
    clean_temps()
    if __debug__:
        logger.info('COMPSs stopped')


def get_file(file_name, mode):
    '''
    Calls the external python library (that calls the bindings-common)
    in order to request a file.
    :return: The current name of the file requested (that may have been
             renamed during runtime).
    '''
    if __debug__:
        logger.debug('Getting file %s with mode %s' % (file_name, mode))
    compss_name = compss.get_file(file_name, mode)
    if __debug__:
        logger.debug('COMPSs file name is %s' % compss_name)
    return compss_name


def delete_file(file_name):
    '''
    Calls the external python library (that calls the bindings-common)
    in order to request a file removal.
    :param file_name: File name to remove
    :return: True if success. False on the contrary
    '''
    if __debug__:
        logger.debug('Deleting file %s' % (file_name))
    result = compss.delete_file(file_name) == 'true'
    if __debug__:
        if result:
            logger.debug('File %s successfully deleted.' % (file_name))
        else:
            logger.error('Failed to remove file %s.' % (file_name))
    return result


def delete_object(obj):
    '''
    Removes a used object from the internal structures and calls the
    external python library (that calls the bindings-common)
    in order to request a its corresponding file removal.
    :param obj: Object to remove
    :return: True if success. False on the contrary
    '''
    obj_id = get_object_id(obj, False, False)
    if obj_id is None:
        return False
    try:
        id2obj.pop(obj_id)
    except:
        pass
    try:
        file_name = objid_to_filename[obj_id]
        compss.delete_file(file_name)
    except:
        pass
    try:
        objid_to_filename.pop(obj_id)
    except:
        pass
    try:
        pending_to_synchronize.pop(obj_id)
    except:
        pass
    return True


def barrier(no_more_tasks=False):
    '''
    Calls the external python library (that calls the bindings-common)
    in order to request a barrier.
    Wait for all tasks.
    '''
    if __debug__:
        logger.debug('Barrier. No more tasks? %s' % str(no_more_tasks))
    compss.barrier(0, no_more_tasks)  # Always 0 (not needed for the signature)


def get_log_path():
    '''
    Requests the logging path to the external python library (that calls
    the bindings-common).
    :return: The path where to store the logs.
    '''
    if __debug__:
        logger.debug('Requesting log path')
    log_path = compss.get_logging_path()
    if __debug__:
        logger.debug('Log path received: %s' % log_path)
    return log_path


def register_ce(coreElement):
    '''
    Calls the external python library (that calls the bindings-common)
    in order to notify the runtime about a core element that needs to be
    registered.
    Java Examples:

        // METHOD
        System.out.println('Registering METHOD implementation');
        String coreElementSignature = 'methodClass.methodName';
        String implSignature = 'methodClass.methodName';
        String implConstraints = 'ComputingUnits:2';
        String implType = 'METHOD';
        String[] implTypeArgs = new String[] { 'methodClass', 'methodName' };
        rt.registerCoreElement(coreElementSignature, implSignature, implConstraints, implType, implTypeArgs);

        // MPI
        System.out.println('Registering MPI implementation');
        coreElementSignature = 'methodClass1.methodName1';
        implSignature = 'mpi.MPI';
        implConstraints = 'StorageType:SSD';
        implType = 'MPI';
        implTypeArgs = new String[] { 'mpiBinary', 'mpiWorkingDir', 'mpiRunner' };
        rt.registerCoreElement(coreElementSignature, implSignature, implConstraints, implType, implTypeArgs);

        // BINARY
        System.out.println('Registering BINARY implementation');
        coreElementSignature = 'methodClass2.methodName2';
        implSignature = 'binary.BINARY';
        implConstraints = 'MemoryType:RAM';
        implType = 'BINARY';
        implTypeArgs = new String[] { 'binary', 'binaryWorkingDir' };
        rt.registerCoreElement(coreElementSignature, implSignature, implConstraints, implType, implTypeArgs);

        // OMPSS
        System.out.println('Registering OMPSS implementation');
        coreElementSignature = 'methodClass3.methodName3';
        implSignature = 'ompss.OMPSS';
        implConstraints = 'ComputingUnits:3';
        implType = 'OMPSS';
        implTypeArgs = new String[] { 'ompssBinary', 'ompssWorkingDir' };
        rt.registerCoreElement(coreElementSignature, implSignature, implConstraints, implType, implTypeArgs);

        // OPENCL
        System.out.println('Registering OPENCL implementation');
        coreElementSignature = 'methodClass4.methodName4';
        implSignature = 'opencl.OPENCL';
        implConstraints = 'ComputingUnits:4';
        implType = 'OPENCL';
        implTypeArgs = new String[] { 'openclKernel', 'openclWorkingDir' };
        rt.registerCoreElement(coreElementSignature, implSignature, implConstraints, implType, implTypeArgs);

        // VERSIONING
        System.out.println('Registering METHOD implementation');
        coreElementSignature = 'methodClass.methodName';
        implSignature = 'anotherClass.anotherMethodName';
        implConstraints = 'ComputingUnits:1';
        implType = 'METHOD';
        implTypeArgs = new String[] { 'anotherClass', 'anotherMethodName' };
        rt.registerCoreElement(coreElementSignature, implSignature, implConstraints, implType, implTypeArgs);

    ---------------------

    Core Element fields:

    ce_signature: <String> Core Element signature  (e.g.- 'methodClass.methodName')
    implSignature: <String> Implementation signature (e.g.- 'methodClass.methodName')
    implConstraints: <Dict> Implementation constraints (e.g.- '{ComputingUnits:2}')
    implType: <String> Implementation type ('METHOD' | 'MPI' | 'BINARY' | 'OMPSS' | 'OPENCL')
    implTypeArgs: <List(Strings)> Implementation arguments (e.g.- ['methodClass', 'methodName'])

    :param coreElement: <CE> Core Element to register
    '''
    # Retrieve Core element fields
    ce_signature = coreElement.get_ce_signature()
    implSignature = coreElement.get_implSignature()
    implConstraints = coreElement.get_implConstraints()
    implType = coreElement.get_implType()
    implTypeArgs = coreElement.get_implTypeArgs()

    if __debug__:
        logger.debug('Registering CE with signature: %s' % (ce_signature))
        logger.debug('\t - Implementation signature: %s' % (implSignature))

    # Build constraints string from constraints dictionary
    implConstraints_string = ''
    for key, value in implConstraints.items():
        if isinstance(value, list):
            val = str(value).replace('\'', '')
            implConstraints_string += key + ':' + str(val) + ';'
        else:
            implConstraints_string += key + ':' + str(value) + ';'

    if __debug__:
        logger.debug('\t - Implementation constraints: %s' % (implConstraints_string))
        logger.debug('\t - Implementation type: %s' % (implType))
    implTypeArgs_string = ' '.join(implTypeArgs)
    if __debug__:
        logger.debug('\t - Implementation type arguments: %s' % (implTypeArgs_string))

    # Call runtime with the appropiate parameters
    compss.register_core_element(ce_signature,
                                 implSignature,
                                 implConstraints_string,
                                 implType,
                                 implTypeArgs)
    if __debug__:
        logger.debug('CE with signature %s registered.' % (ce_signature))


# QUESTION: What is the purpose of this getter? pending_to_synchronize is
# global and from this precise module
def get_pending_to_synchronize():
    '''
    This method retrieves the dictionary that contains the objects used as
    parameters for the tasks.
    Used within the API in order to check if waiting for an INOUT object.
    :return: Dictionary containing the ids of the objects used as task
             parameter.
    '''
    return pending_to_synchronize


def synchronize(obj, mode):
    '''
    Synchronization function.
    This method retrieves the value of a future object.
    Calls the runtime in order to wait for the value and returns it
    when received.
    :param obj: Object to synchronize.
    :param mode: Direction of the object to synchronize.
    :return: The value of the object requested.
    '''
    # TODO - CUANDO SE LLAME A compss.get_file, anadir un booleano diferenciando si es fichero u objeto
    # Objetivo: mejorar el detalle de las trazas. Esto se tiene que implementar
    # primero en el runtime, despues adaptar el api de C, y finalmente anadir
    # el booleano aqui.
    global current_id

    if is_PSCO(obj):
        obj_id = get_ID(obj)
        if obj_id not in pending_to_synchronize:
            return obj
        else:
            # file_path is of the form storage://pscoID or file://sys_path_to_file
            file_path = compss.get_file('storage://' + str(obj_id), mode)
            # TODO: Add switch on protocol
            protocol, file_name = file_path.split('://')
            new_obj = get_by_ID(file_name)
            return new_obj

    obj_id = get_object_id(obj)
    if obj_id not in pending_to_synchronize:
        return obj

    if __debug__:
        logger.debug('Synchronizing object %s with mode %s' % (obj_id, mode))

    file_name = objid_to_filename[obj_id]
    compss_file = compss.get_file(file_name, mode)

    # Runtime can return a path or a PSCOId
    if compss_file.startswith('/'):
        new_obj = deserialize_from_file(compss_file)
        compss.close_file(file_name, mode)
    else:
        new_obj = get_by_ID(compss_file)
    new_obj_id = get_object_id(new_obj, True, True)

    # The main program won't work with the old object anymore, update mapping
    objid_to_filename[new_obj_id] = objid_to_filename[obj_id].replace(obj_id, new_obj_id)
    objs_written_by_mp[new_obj_id] = objid_to_filename[new_obj_id]

    if __debug__:
        logger.debug('Deleting obj %s (new one is %s)' % (str(obj_id), str(new_obj_id)))
    compss.delete_file(objid_to_filename[obj_id])
    objid_to_filename.pop(obj_id)
    pending_to_synchronize.pop(obj_id)

    if __debug__:
        logger.debug('Now object with id %s and %s has mapping %s' % (new_obj_id, type(new_obj), file_name))

    return new_obj


def process_task(f, module_name, class_name, ftype, has_return, spec_args,
                 args, kwargs, self_kwargs, num_nodes, replicated,
                 distributed):
    '''
    Function that submits a task to the runtime.
    :param f: Function or method
    :param module_name: Name of the module containing the function/method
                        (including packages, if any)
    :param class_name: Name of the class (if method)
    :param ftype: Function type
    :param spec_args: Names of the task arguments
    :param args: Unnamed arguments
    :param kwargs: Named arguments
    :param self_kwargs: Decorator arguments
    :return: The future object related to the task return
    '''
    if __debug__:
        logger.debug('TASK: %s of type %s, in module %s, in class %s' % (f.__name__, ftype, module_name, class_name))

    # Check if the function is an instance method or a class method.
    first_par = 0
    if ftype == Function_Type.INSTANCE_METHOD:
        has_target = True
    else:
        has_target = False
        if ftype == Function_Type.CLASS_METHOD:
            first_par = 1  # skip class parameter

    fu = None
    fileNames = {}
    if has_return:
        fu, fileNames, self_kwargs, spec_args = build_return_objects(self_kwargs, spec_args)

    app_id = 0

    # Get path
    if class_name == '':
        path = module_name
    else:
        path = module_name + '.' + class_name

    num_pars = len(spec_args)

    # Infer COMPSs types from real types, except for files
    new_self_kwargs, is_future = infer_types_and_serialize_objects(spec_args, first_par, num_pars, fileNames, self_kwargs, args)

    # Build values and COMPSs types and directions
    values, compss_types, compss_directions, compss_streams, compss_prefixes = build_values_types_directions(ftype, first_par, num_pars, spec_args, new_self_kwargs, is_future, f.__code_strings__)

    # Get priority
    has_priority = self_kwargs['priority']

    # Signature and other parameters:
    signature = '.'.join([path, f.__name__])
    # num_nodes = 1        # default due to not MPI decorator yet
    # replicated = False   # default due to not replicated tag yet
    # distributed = False  # default due to not distributed tag yet

    if __debug__:
        # Log the task submission values for debugging purposes.
        if logger.isEnabledFor(logging.DEBUG):
            values_str = ''
            types_str = ''
            direct_str = ''
            streams_str = ''
            prefixes_str = ''
            for v in values:
                values_str += str(v) + ' '
            for t in compss_types:
                types_str += str(t) + ' '
            for d in compss_directions:
                direct_str += str(d) + ' '
            for s in compss_streams:
                streams_str += str(s) + ' '
            for p in compss_prefixes:
                prefixes_str += str(p) + ' '
            logger.debug('Processing task:')
            logger.debug('\t- App id: ' + str(app_id))
            logger.debug('\t- Path: ' + path)
            logger.debug('\t- Function name: ' + f.__name__)
            logger.debug('\t- Signature: ' + signature)
            logger.debug('\t- Priority: ' + str(has_priority))
            logger.debug('\t- Has target: ' + str(has_target))
            logger.debug('\t- Num nodes: ' + str(num_nodes))
            logger.debug('\t- Replicated: ' + str(replicated))
            logger.debug('\t- Distributed: ' + str(distributed))
            logger.debug('\t- Values: ' + values_str)
            logger.debug('\t- COMPSs types: ' + types_str)
            logger.debug('\t- COMPSs directions: ' + direct_str)
            logger.debug('\t- COMPSs streams: ' + streams_str)
            logger.debug('\t- COMPSs prefixes: ' + prefixes_str)

    # Check that there is the same amount of values as their types, as well
    # as their directions, streams and prefixes.
    assert(len(values) == len(compss_types) and len(values) == len(compss_directions) == len(compss_streams) == len(compss_prefixes))

    '''
    Submit task to the runtime (call to the C extension):
    Parameters:
        0 - <Integer>   - application id (by default always 0 due to it is not currently needed for the signature)
        1 - <String>    - path of the module where the task is
        2 - <String>    - function name of the task (to be called from the worker)
        3 - <String>    - priority flag (true|false)
        4 - <String>    - has target (true|false). If the task is within an object or not.
        5 - [<String>]  - task parameters (basic types or file paths for objects)
        6 - [<Integer>] - parameters types (number corresponding to the type of each parameter)
        7 - [<Integer>] - parameters directions (number corresponding to the direction of each parameter)
        8 - [<Integer>] - parameters streams (number corresponding to the stream of each parameter)
        9 - [<String>]  - parameters prefixes (sting corresponding to the prefix of each parameter)
    '''

    compss.process_task(app_id,
                        signature,
                        has_priority,
                        num_nodes,
                        replicated,
                        distributed,
                        has_target,
                        values,
                        compss_types,
                        compss_directions,
                        compss_streams,
                        compss_prefixes)

    # Return the future object/s corresponding to the task
    # This object will substitute the user expected return from the task and
    # will be used later for synchronization or as a task parameter (then
    # the runtime will take care of the dependency.
    return fu


# ##############################################################################
# ####################### AUXILIARY FUNCTIONS ##################################
# ##############################################################################
def build_return_objects(self_kwargs, spec_args):
    '''
    Build the return object and updates the self_kwargs and spec_args
    structures (as tuple in the multireturn case).
    :param self_kwargs:
    :param spec_args:
    :return:
    '''
    # Check if the returns statement contains an integer value.
    # In such case, build a list of objects of value length and set it in ret_type.
    if isinstance(self_kwargs['returns'], int):
        num_rets = self_kwargs['returns']
        # Assume all as objects (generic type).
        # It will not work properly when using user defined classes, since
        # the future object built will not be of the same type as expected
        # and may cause "AttributeError" since the 'object' does not have
        # the attributes of the class
        if num_rets > 1:
            ret_type = [object for _ in range(num_rets)]
        else:
            ret_type = object
    else:
        ret_type = self_kwargs['returns']
    fileNames = {}  # return files locations
    if ret_type:
        fu = []
        # Create future for return value
        if isinstance(ret_type, list) or isinstance(ret_type, tuple):  # MULTIRETURN
            if __debug__:
                logger.debug('Multiple objects return found.')
            # This condition fixes the multiple calls to a function with multireturn bug.
            if 'compss_retvalue' in spec_args:
                spec_args.remove('compss_retvalue')  # remove single return... it contains more than one
                if 'compss_retvalue' in self_kwargs:
                    self_kwargs.pop('compss_retvalue')
                else:
                    assert 'compss_retvalue0' in self_kwargs, 'Inconsistent state: multireturn detected, but there is no compss_retvalue0'
            pos = 0
            for i in ret_type:
                # Build the appropriate future object
                if i in python_to_compss:  # primitives, string, dic, list, tuple
                    fue = Future()
                elif inspect.isclass(i):
                    # For objects:
                    # type of future has to be specified to allow o = func; o.func
                    try:
                        fue = i()
                    except TypeError:
                        if __debug__:
                            logger.warning('Type {0} does not have an empty constructor, building generic future object'.format(ret_type))
                        fue = Future()
                else:
                    fue = Future()  # modules, functions, methods
                fu.append(fue)
                obj_id = get_object_id(fue, True)
                if __debug__:
                    logger.debug('Setting object %s of %s as a future' % (obj_id, type(fue)))
                ret_filename = temp_dir + temp_obj_prefix + str(obj_id)
                objid_to_filename[obj_id] = ret_filename
                pending_to_synchronize[obj_id] = fue
                fileNames['compss_retvalue' + str(pos)] = ret_filename
                # Once determined the filename where the returns are going to
                # be stored, create a new Parameter object for each return
                # object
                self_kwargs['compss_retvalue' + str(pos)] = Parameter(p_type=TYPE.FILE, p_direction=DIRECTION.OUT, p_prefix="#")
                spec_args.append('compss_retvalue' + str(pos))
                pos += 1
            self_kwargs['num_returns'] = pos  # Update the amount of objects to be returned
        else:  # SIMPLE RETURN
            # Build the appropriate future object
            if ret_type in python_to_compss:  # primitives, string, dic, list, tuple
                fu = Future()
            elif inspect.isclass(ret_type):
                # For objects:
                # type of future has to be specified to allow o = func; o.func
                try:
                    fu = ret_type()
                except TypeError:
                    if __debug__:
                        logger.warning('Type {0} does not have an empty constructor, building generic future object'.format(ret_type))
                    fu = Future()
            else:
                fu = Future()  # modules, functions, methods
            obj_id = get_object_id(fu, True)
            if __debug__:
                logger.debug('Setting object %s of %s as a future' % (obj_id, type(fu)))
            ret_filename = temp_dir + temp_obj_prefix + str(obj_id)
            objid_to_filename[obj_id] = ret_filename
            pending_to_synchronize[obj_id] = fu
            fileNames['compss_retvalue'] = ret_filename
            self_kwargs['compss_retvalue'] = Parameter(p_type=TYPE.FILE, p_direction=DIRECTION.OUT, p_prefix="#")
    else:
        fu = None

    return fu, fileNames, self_kwargs, spec_args


def fromDictToParameter(d):
    '''
    Convert a Dict defined by a user for a parameter into a real Parameter
    object.
    :param d: Dictionary (mandatory to have 'Type' key).
    :return: Parameter object.
    '''
    from pycompss.api.parameter import Parameter
    from pycompss.api.parameter import Type
    from pycompss.api.parameter import Direction
    from pycompss.api.parameter import Stream
    from pycompss.api.parameter import Prefix
    if Type not in d:  # If no Type specified => IN
        d[Type] = Parameter()
    p = d[Type]
    if Direction in d:
        p.setDirection(d[Direction])
    if Stream in d:
        p.setStream(d[Stream])
    if Prefix in d:
        p.setPrefix(d[Prefix])
    return p


def infer_types_and_serialize_objects(spec_args, first_par, num_pars,
                                      fileNames, self_kwargs, args):
    '''
    Infer COMPSs types for the task parameters and serialize them.
    :param spec_args: <List of strings> - Names of the task arguments
    :param first_par: <Integer> - First parameter
    :param num_pars: <Integer> - Number of parameters
    :param fileNames: <Dictionary> - Return objects filenames
    :param self_kwargs: <Dictionary> - Decorator arguments
    :param args: <List> - Unnamed arguments
    :return: Tuple of self_kwargs updated and a dictionary containing
             if the objects are future elements.
    '''
    is_future = {}
    max_obj_arg_size = 320000
    for i in range(first_par, num_pars):
        spec_arg = spec_args[i]
        p = self_kwargs.get(spec_arg)
        if p is None:
            if __debug__:
                logger.debug('Adding default decoration for param %s' % spec_arg)
            p = Parameter()
            self_kwargs[spec_arg] = p
        elif type(p) is dict:
            # The user has provided some information about a parameter within
            # the @task parameter
            if __debug__:
                logger.debug('Checking decoration for param %s' % spec_arg)
            p = fromDictToParameter(p)

        if spec_args[0] != 'self':
            # It is a function
            if i < len(args):
                p.value = args[i]
            else:
                p.value = fileNames[spec_arg]
        else:
            # It is a class function
            if spec_arg == 'self':
                p.value = args[0]
                # Check if self is a persistent object and set its type if it really is.
                if is_PSCO(p.value):
                    p.type = TYPE.EXTERNAL_PSCO
            elif spec_arg.startswith('compss_retvalue'):
                p.value = fileNames[spec_arg]
            else:
                p.value = args[i]

        # Update the parameter type if changed between task calls
        # Respect p.type if none to be inferred later.
        # Respect p.type == TYPE.FILE since may be updated wrongly to str.
        new_type = python_to_compss.get(type(p.value))
        if p.type is not None and p.type != TYPE.FILE and p.type != new_type:
            p.type = new_type

        val_type = type(p.value)
        is_future[i] = (val_type == Future)
        if __debug__:
            logger.debug('Parameter ' + spec_arg)
            logger.debug('\t- Value type: ' + str(val_type))
            logger.debug('\t- User-defined type: ' + str(p.type))

        # Infer type if necessary
        if p.type is None:
            if __debug__:
                logger.debug('Inferring type due to None pType.')
            p.type = python_to_compss.get(val_type)
            if p.type is None:
                if is_PSCO(p.value):
                    p.type = TYPE.EXTERNAL_PSCO
                else:
                    p.type = TYPE.OBJECT
            if __debug__:
                logger.debug('\t- Inferred type: %d' % p.type)

        # Convert small objects to string if object_conversion enabled
        # Check if the object is small in order not to serialize it.
        if object_conversion:
            p, bytes = convert_object_to_string(p, is_future.get(i), max_obj_arg_size, policy='objectSize')
            max_obj_arg_size -= bytes

        # Serialize objects into files
        p = serialize_object_into_file(p, is_future, i, val_type)

        if __debug__:
            logger.debug('Final type for parameter %s: %d' % (spec_arg, p.type))

    return self_kwargs, is_future


def serialize_object_into_file(p, is_future, i, val_type):
    '''
    Serialize an object into a file if necessary.
    :param p: object wrapper
    :param is_future: if is a future object <boolean>
    :param i: parameter position
    :param val_type: value type
    :return: p (whose type can have been modified)
    '''
    if p.type == TYPE.OBJECT or is_future.get(i):
        # 2nd condition: real type can be primitive,
        # but now it's acting as a future (object)
        try:
            # Check if the Object is a list composed by future objects
            # This could be delegated to the runtime.
            # Will have to be discussed.
            if (isinstance(val_type, list)):
                # Is there a future object within the list?
                if any(isinstance(v, (Future)) for v in p.value):
                    if __debug__:
                        logger.debug('Found a list that contains future objects - synchronizing...')
                    mode = get_compss_mode('in')
                    p.value = list(map(synchronize, p.value, [mode] * len(p.value)))
            turn_into_file(p)
        except SerializerException:
            import sys
            exc_type, exc_value, exc_traceback = sys.exc_info()
            lines = traceback.format_exception(exc_type, exc_value, exc_traceback)
            logger.exception('Pickling error exception: non-serializable object found as a parameter.')
            logger.exception(''.join(line for line in lines))
            print('[ ERROR ]: Non serializable objects can not be used as parameters (e.g. methods).')
            print('[ ERROR ]: Value: %s' % p.value)
            # Raise the exception up tu launch.py in order to point where the
            # error is in the user code.
            raise
    elif p.type == TYPE.EXTERNAL_PSCO:
        manage_persistent_object(p)
    elif p.type == TYPE.INT:
        if p.value > JAVA_MAX_INT or p.value < JAVA_MIN_INT:
            # This must go through Java as a long to prevent overflow
            # with Java int
            p.type = TYPE.LONG
    elif p.type == TYPE.LONG:
        if p.value > JAVA_MAX_LONG or p.value < JAVA_MIN_LONG:
            # This must be serialized to prevent overflow with Java long
            p.type = TYPE.OBJECT
            turn_into_file(p)
    return p


def convert_object_to_string(p, is_future, max_obj_arg_size,
                             policy='objectSize'):
    '''
    Convert small objects into string that can fit into the task
    parameters call
    :param p: object wrapper
    :param is_future: if is a future object <boolean>
    :param max_obj_arg_size: max size of the object to be converted
    :param policy: policy to use: 'objectSize' for considering the size of
                   the object or 'serializedSize' for considering the size
                   of the object serialized.
    :return: the object possibly converted to string
    '''
    bytes = 0
    if policy == 'objectSize':
        # Check if the object is small in order to serialize it.
        # This alternative evaluates the size of the object before
        # serializing the object.
        # Warning: calculate the size of a python object can be difficult
        # in terms of time and precision
        if (p.type == TYPE.OBJECT or p.type == TYPE.STRING) and not is_future and p.direction == DIRECTION.IN:
            if not isinstance(p.value, basestring) and isinstance(p.value, (list, dict, tuple, deque, set, frozenset)):
                # check object size
                # bytes = sys.getsizeof(p.value)  # does not work properly with recursive object
                bytes = total_sizeof(p.value)
                megabytes = bytes / 1000000  # truncate
                if __debug__:
                    logger.debug('Object size %d bytes (%d Mb).' % (bytes, megabytes))

                if bytes < max_obj_arg_size:  # be careful... more than this value produces:
                    # Cannot run program '/bin/bash'...: error=7, La lista de argumentos es demasiado larga
                    if __debug__:
                        logger.debug('The object size is less than 320 kb.')
                    real_value = p.value
                    try:
                        v = serialize_to_string(p.value)
                        p.value = v.encode(str_escape)
                        p.type = TYPE.STRING
                        if __debug__:
                            logger.debug('Inferred type modified (Object converted to String).')
                    except SerializerException:
                        p.value = real_value
                        p.type = TYPE.OBJECT
                        if __debug__:
                            logger.debug('The object cannot be converted due to: not serializable.')
                else:
                    p.type = TYPE.OBJECT
                    if __debug__:
                        logger.debug('Inferred type reestablished to Object.')
                    # if the parameter converts to an object, release the size to be used for converted objects?
                    # No more objects can be converted
                    # max_obj_arg_size += bytes
                    # if max_obj_arg_size > 320000:
                    #     max_obj_arg_size = 320000
    elif policy == 'serializedSize':
        from cPickle import PicklingError
        # Check if the object is small in order to serialize it.
        # This alternative evaluates the size after serializing the parameter
        if (p.type == TYPE.OBJECT or p.type == TYPE.STRING) and not is_future and p.direction == DIRECTION.IN:
            if not isinstance(p.value, basestring):
                real_value = p.value
                try:
                    v = serialize_to_string(p.value)
                    v = v.encode(str_escape)
                    # check object size
                    bytes = sys.getsizeof(v)
                    megabytes = bytes / 1000000  # truncate
                    if __debug__:
                        logger.debug('Object size %d bytes (%d Mb).' % (bytes, megabytes))
                    if bytes < max_obj_arg_size:
                        # be careful... more than this value produces:
                        # Cannot run program '/bin/bash'...: error=7,
                        # arguments list too long error.
                        if __debug__:
                            logger.debug('The object size is less than 320 kb.')
                        p.value = v
                        p.type = TYPE.STRING
                        if __debug__:
                            logger.debug('Inferred type modified (Object converted to String).')
                    else:
                        p.value = real_value
                        p.type = TYPE.OBJECT
                        if __debug__:
                            logger.debug('Inferred type reestablished to Object.')
                        # if the parameter converts to an object, release the
                        # size to be used for converted objects?
                        # No more objects can be converted
                        # max_obj_arg_size += bytes
                        # if max_obj_arg_size > 320000:
                        #     max_obj_arg_size = 320000
                except PicklingError:
                    p.value = real_value
                    p.type = TYPE.OBJECT
                    if __debug__:
                        logger.debug('The object cannot be converted due to: not serializable.')
    else:
        if __debug__:
            logger.debug('[ERROR] Wrong convert_objects_to_strings policy.')
        raise Exception('Wrong convert_objects_to_strings policy.')
    return p, bytes


def build_values_types_directions(ftype, first_par, num_pars, spec_args,
                                  deco_kwargs, is_future, code_strings):
    '''
    Build the values list, the values types list and the values directions list.
    :param ftype: task function type. If it is an instance method, the
                  first parameter will be put at the end.
    :param first_par: first parameter <Integer>
    :param num_pars:  number of parameters <Integer>
    :param spec_args: function spec_args
    :param deco_kwargs: function deco_kwargs
    :param is_future: is future dictionary
    :param code_strings: code strings or not <Boolean>
    :return: three lists: values, their types and their directions
    '''
    values = []
    compss_types = []
    compss_directions = []
    compss_streams = []
    compss_prefixes = []

    # Build the range of elements
    if ftype == Function_Type.INSTANCE_METHOD:
        ra = list(range(1, num_pars))
        ra.append(0)  # callee is the last
    else:
        ra = range(first_par, num_pars)
    # Fill the values, compss_types and compss_directions lists
    for i in ra:
        spec_arg = spec_args[i]
        p = deco_kwargs[spec_arg]
        if type(p) is dict:
            # The user has provided some information about a parameter within
            # the @task parameter
            p = fromDictToParameter(p)
        if p.type == TYPE.STRING and not is_future[i] and code_strings:
            # Encode the string in order to preserve the source
            # Checks that it is not a future (which is indicated with a path)
            # Considers multiple spaces between words
            p.value = base64.b64encode(p.value.encode()).decode()
        values.append(p.value)
        if p.type == TYPE.OBJECT or is_future.get(i):
            compss_types.append(TYPE.FILE)
        else:
            compss_types.append(p.type)
        compss_directions.append(p.direction)
        compss_streams.append(p.stream)
        compss_prefixes.append(p.prefix)
    return values, compss_types, compss_directions, compss_streams, compss_prefixes


def manage_persistent_object(p):
    '''
    Does the necessary actions over a persistent object used as task parameter.
    Check if the object has already been used (indexed in the objid_to_filename
    dictionary).
    In particular, saves the object id provided by the persistent storage
    (getID()) into the pending_to_synchronize dictionary.
    :param p: wrapper of the object to manage
    '''
    p.type = TYPE.EXTERNAL_PSCO
    obj_id = get_ID(p.value)
    pending_to_synchronize[obj_id] = p.value  # obj_id
    p.value = obj_id
    if __debug__:
        logger.debug('Managed persistent object: %s' % (obj_id))


def turn_into_file(p):
    '''
    Write a object into a file if the object has not been already written (p.value).
    Consults the objid_to_filename to check if it has already been written
    (reuses it if exists). If not, the object is serialized to file and
    registered in the objid_to_filename dictionary.
    This functions stores the object into pending_to_synchronize
    :param p: wrapper of the object to turn into file
    '''
    '''
    print('XXXXXXXXXXXXXXXXX')
    print('p           : ', p)
    print('p.value     : ', p.value)
    print('p.type      : ', p.type)
    print('p.direction : ', p.direction)
    print('XXXXXXXXXXXXXXXXX')
    if p.direction == DIRECTION.OUT:
        # If the parameter is out, infer the type and create an empty instance
        # of the same type as the original parameter:
        t = type(p.value)
        p.value = t()
    '''
    obj_id = get_object_id(p.value, True)
    file_name = objid_to_filename.get(obj_id)
    if file_name is None:
        # This is the first time a task accesses this object
        pending_to_synchronize[obj_id] = p.value
        file_name = temp_dir + temp_obj_prefix + str(obj_id)
        objid_to_filename[obj_id] = file_name
        if __debug__:
            logger.debug('Mapping object %s to file %s' % (obj_id, file_name))
        serialize_to_file(p.value, file_name)
    elif obj_id in objs_written_by_mp:
        if p.direction == DIRECTION.INOUT:
            pending_to_synchronize[obj_id] = p.value
        # Main program generated the last version
        compss_file = objs_written_by_mp.pop(obj_id)
        if __debug__:
            logger.debug('Serializing object %s to file %s' % (obj_id, compss_file))
        serialize_to_file(p.value, compss_file)
    p.value = file_name


def get_compss_mode(pymode):
    '''
    Get the direction of pymode string.
    :param pymode: String to parse and return the direction
    :return: Direction object (IN/INOUT/OUT)
    '''
    if pymode.startswith('w'):
        return DIRECTION.OUT
    elif pymode.startswith('r+') or pymode.startswith('a'):
        return DIRECTION.INOUT
    else:
        return DIRECTION.IN


def clean_objects():
    '''
    Clean the objects stored in the global dictionaries:
        * pending_to_synchronize dict
        * id2obj dict
        * objid_to_filename dict
        * objs_written_by_mp dict
    '''
    for filename in objid_to_filename.values():
        compss.delete_file(filename)
    pending_to_synchronize.clear()
    id2obj.clear()
    objid_to_filename.clear()
    objs_written_by_mp.clear()


def clean_temps():
    '''
    Clean temporary files.
    The temporary files end with the IT extension
    '''
    rmtree(temp_dir, True)
    cwd = os.getcwd()
    for f in os.listdir(cwd):
        if re.search('d\d+v\d+_\d+\.IT', f):
            os.remove(os.path.join(cwd, f))
