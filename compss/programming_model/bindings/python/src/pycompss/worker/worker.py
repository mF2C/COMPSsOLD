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
PyCOMPSs Worker
===============
    This file contains the worker code.
    Args: debug full_path (method_class)
    method_name has_target num_params par_type_1 par_1 ... par_type_n par_n
"""

import logging
import os
import sys
import traceback
import base64

from pycompss.api.parameter import TYPE, JAVA_MAX_INT, JAVA_MIN_INT
from pycompss.runtime.commons import EMPTY_STRING_KEY
from pycompss.util.serializer import serialize_to_file
from pycompss.util.serializer import deserialize_from_file
from pycompss.util.serializer import deserialize_from_string
from pycompss.util.serializer import SerializerException
from pycompss.util.logs import init_logging_worker
from pycompss.util.persistent_storage import get_by_ID


if sys.version_info >= (3, 0):
    long = int
else:
    # Exception moved to built-in
    from exceptions import ValueError


SYNC_EVENTS = 8000666

# Should be equal to Tracer.java definitions
TASK_EVENTS = 8000010

# Rank 110-119 reserved to events launched from task.py
PROCESS_CREATION = 100
WORKER_INITIALIZATION = 102
PARAMETER_PROCESSING = 103
LOGGING = 104
MODULES_IMPORT = 105
WORKER_END = 106
PROCESS_DESTRUCTION = 107


if sys.version_info >= (2, 7):
    import importlib


# Uncomment the next line if you do not want to reuse pyc files.
# sys.dont_write_bytecode = True


def compss_worker(persistent_storage):
    """
    Worker main method (invocated from __main__).
    """
    logger = logging.getLogger('pycompss.worker.worker')

    logger.debug("Starting Worker")

    args = sys.argv[6:]
    path = args[0]
    method_name = args[1]

    numSlaves = int(args[2])
    slaves = []
    for i in range(2, 2 + numSlaves):
        slaves.append(args[i])
    argPosition = 3 + numSlaves

    args = args[argPosition:]
    cus = args[0]

    args = args[1:]
    has_target = args[0]
    return_type = args[1]
    num_params = int(args[2])

    args = args[3:]
    pos = 0

    values = []
    types = []
    streams = []
    prefixes = []

    if tracing:
        pyextrae.event(TASK_EVENTS, 0)
        pyextrae.event(TASK_EVENTS, PARAMETER_PROCESSING)

    if persistent_storage:
        from pycompss.util.persistent_storage import storage_task_context

    # Get all parameter values
    logger.debug("Processing parameters:")
    for i in range(0, num_params):
        pType = int(args[pos])
        pStream = int(args[pos + 1])
        pPrefix = args[pos + 2]
        pValue = args[pos + 3]

        logger.debug("Parameter : " + str(i))
        logger.debug("\t * Type : " + str(pType))
        logger.debug("\t * Stream : " + str(pStream))
        logger.debug("\t * Prefix : " + str(pPrefix))
        logger.debug("\t * Value: " + str(pValue))

        types.append(pType)
        streams.append(pStream)
        prefixes.append(pPrefix)

        if pType == TYPE.FILE:
            values.append(pValue)
        elif pType == TYPE.EXTERNAL_PSCO:
            po = get_by_ID(pValue)
            values.append(po)
            pos += 1  # Skip info about direction (R, W)
        elif pType == TYPE.STRING:
            num_substrings = int(pValue)
            aux = ''
            first_substring = True
            for j in range(4, num_substrings + 4):
                if not first_substring:
                    aux += ' '
                first_substring = False
                aux += args[pos + j]
            # Decode the string received
            aux = base64.b64decode(aux.encode())
            if aux.decode() == EMPTY_STRING_KEY:
                # Then it is an empty string
                aux = ""
            else:
                #######
                # Check if the string is really an object
                # Required in order to recover objects passed as parameters.
                # - Option object_conversion
                real_value = aux
                try:
                    # try to recover the real object
                    aux = deserialize_from_string(aux)
                except (SerializerException, ValueError, EOFError):
                    # was not an object
                    aux = str(real_value.decode())
                #######
            values.append(aux)
            logger.debug("\t * Final Value: " + str(aux))
            pos += num_substrings
        elif pType == TYPE.INT:
            values.append(int(pValue))
        elif pType == TYPE.LONG:
            my_l = long(pValue)
            if my_l > JAVA_MAX_INT or my_l < JAVA_MIN_INT:
                # A Python int was converted to a Java long to prevent overflow
                # We are sure we will not overflow Python int, otherwise this
                # would have been passed as a serialized object.
                my_l = int(my_l)
            values.append(my_l)
        elif pType == TYPE.DOUBLE:
            values.append(float(pValue))
        elif pType == TYPE.BOOLEAN:
            if pValue == 'true':
                values.append(True)
            else:
                values.append(False)
        # elif (pType == TYPE.OBJECT):
        #    pass
        else:
            logger.fatal("Invalid type (%d) for parameter %d" % (pType, i))
            exit(1)
        pos += 4

    if tracing:
        pyextrae.event(TASK_EVENTS, 0)
        pyextrae.event(TASK_EVENTS, LOGGING)

    if logger.isEnabledFor(logging.DEBUG):
        values_str = ''
        types_str = ''
        for v in values:
            values_str += "\t\t" + str(v) + "\n"
        for t in types:
            types_str += str(t) + " "
        logger.debug("RUN TASK with arguments\n" +
                     "\t- Path: " + path + "\n" +
                     "\t- Method/function name: " + method_name + "\n" +
                     "\t- Has target: " + has_target + "\n" +
                     "\t- # parameters: " + str(num_params) + "\n" +
                     "\t- Values:\n" + values_str +
                     "\t- COMPSs types: " + types_str)

    if tracing:
        pyextrae.event(TASK_EVENTS, 0)
        pyextrae.event(TASK_EVENTS, MODULES_IMPORT)

    try:
        # Try to import the module (for functions)
        logger.debug("Trying to import the user module.")
        if sys.version_info >= (2, 7):
            module = importlib.import_module(path)  # Python 2.7
            logger.debug("Module successfully loaded (Python version >= 2.7)")
        else:
            module = __import__(path, globals(), locals(), [path], -1)
            logger.debug("Module successfully loaded (Python version < 2.7")

        if persistent_storage:
            with storage_task_context(logger, values, config_file_path=storage_conf):
                getattr(module, method_name)(*values, compss_types=types, compss_tracing=tracing)
                if tracing:
                    pyextrae.eventandcounters(TASK_EVENTS, 0)
                    pyextrae.eventandcounters(TASK_EVENTS, WORKER_END)
        else:
            getattr(module, method_name)(*values, compss_types=types, compss_tracing=tracing)
            if tracing:
                pyextrae.eventandcounters(TASK_EVENTS, 0)
                pyextrae.eventandcounters(TASK_EVENTS, WORKER_END)
    # ==========================================================================
    except AttributeError:
        # Appears with functions that have not been well defined.
        exc_type, exc_value, exc_traceback = sys.exc_info()
        lines = traceback.format_exception(exc_type, exc_value, exc_traceback)
        logger.exception("WORKER EXCEPTION - Attribute Error Exception")
        logger.exception(''.join(line for line in lines))
        logger.exception("Check that all parameters have been defined with " +
                         "an absolute import path (even if in the same file)")
        exit(1)
    # ==========================================================================
    except ImportError:
        logger.debug("Could not import the module. Reason: Method in class.")
        # Not the path of a module, it ends with a class name
        class_name = path.split('.')[-1]
        module_name = '.'.join(path.split('.')[0:-1])

        if '.' in path:
            module_name = '.'.join(path.split('.')[0:-1])
        else:
            module_name = path
        module = __import__(module_name, fromlist=[class_name])
        klass = getattr(module, class_name)
        logger.debug("Method in class %s of module %s" % (class_name, module_name))

        if has_target == 'true':
            # Instance method
            file_name = values.pop()
            logger.debug("Deserialize self from file.")
            obj = deserialize_from_file(file_name)

            logger.debug("Processing callee, a hidden object of %s in file %s" % (file_name, type(obj)))
            values.insert(0, obj)
            types.pop()
            types.insert(0, TYPE.OBJECT)

            if persistent_storage:
                with storage_task_context(logger, values, config_file_path=storage_conf):
                    getattr(klass, method_name)(*values, compss_types=types, compss_tracing=tracing)
                    if tracing:
                        pyextrae.eventandcounters(TASK_EVENTS, 0)
                        pyextrae.eventandcounters(TASK_EVENTS, WORKER_END)
            else:
                getattr(klass, method_name)(*values, compss_types=types, compss_tracing=tracing)
                if tracing:
                    pyextrae.eventandcounters(TASK_EVENTS, 0)
                    pyextrae.eventandcounters(TASK_EVENTS, WORKER_END)

            logger.debug("Serializing self to file")
            logger.debug("Obj: " + str(obj))
            serialize_to_file(obj, file_name)
        else:
            # Class method - class is not included in values (e.g. values = [7])
            types.insert(0, None)    # class must be first type

            if persistent_storage:
                with storage_task_context(logger, values, config_file_path=storage_conf):
                    getattr(klass, method_name)(*values, compss_types=types, compss_tracing=tracing)
                    if tracing:
                        pyextrae.eventandcounters(TASK_EVENTS, 0)
                        pyextrae.eventandcounters(TASK_EVENTS, WORKER_END)
            else:
                getattr(klass, method_name)(*values, compss_types=types, compss_tracing=tracing)
                if tracing:
                    pyextrae.eventandcounters(TASK_EVENTS, 0)
                    pyextrae.eventandcounters(TASK_EVENTS, WORKER_END)
    # ==========================================================================
    except Exception:
        exc_type, exc_value, exc_traceback = sys.exc_info()
        lines = traceback.format_exception(exc_type, exc_value, exc_traceback)
        logger.exception("WORKER EXCEPTION")
        logger.exception(''.join(line for line in lines))
        exit(1)


if __name__ == "__main__":
    # Emit sync event if tracing is enabled
    tracing = sys.argv[1] == 'true'
    taskId = int(sys.argv[2])
    log_level = sys.argv[3]
    storage_conf = sys.argv[4]
    method_type = sys.argv[5]
    # class_name = sys.argv[6]
    # method_name = sys.argv[7]
    # numSlaves = sys.argv[8]
    # i = 8 + numSlaves
    # slaves = sys.argv[9-i]
    # numCus = sys.argv[i+1]
    # has_target = sys.argv[i+2] == 'true'
    # num_params = int(sys.argv[i+3])
    # params = sys.argv[i+4..]

    persistent_storage = False
    if storage_conf != 'null':
        persistent_storage = True
        from storage.api import initWorker as initStorageAtWorker
        from storage.api import finishWorker as finishStorageAtWorker

    if tracing:
        import pyextrae.multiprocessing as pyextrae
        pyextrae.eventandcounters(SYNC_EVENTS, taskId)
        # pyextrae.eventandcounters(TASK_EVENTS, 0)
        pyextrae.eventandcounters(TASK_EVENTS, WORKER_INITIALIZATION)

    # Load log level configuration file
    worker_path = os.path.dirname(os.path.realpath(__file__))
    if log_level == 'true' or log_level == "debug":
        # Debug
        init_logging_worker(worker_path + '/../../log/logging.json.debug')
    elif log_level == "info" or log_level == "off":
        # Info or no debug
        init_logging_worker(worker_path + '/../../log/logging.json.off')
    else:
        # Default
        init_logging_worker(worker_path + '/../../log/logging.json')

    if persistent_storage:
        # Initialize storage
        initStorageAtWorker(config_file_path=storage_conf)

    # Init worker
    compss_worker(persistent_storage)

    if tracing:
        pyextrae.eventandcounters(TASK_EVENTS, 0)
        # pyextrae.eventandcounters(TASK_EVENTS, PROCESS_DESTRUCTION)
        pyextrae.eventandcounters(SYNC_EVENTS, taskId)

    if persistent_storage:
        # Finish storage
        finishStorageAtWorker()
