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

'''
PyCOMPSs Binding - Launch
=========================
This file contains the __main__ method.
It is called from pycompssext script with the user and environment parameters.
'''

import os
import sys
import logging
import traceback
import pycompss.runtime.binding as binding
from tempfile import mkdtemp
from pycompss.api.api import compss_start, compss_stop
from pycompss.runtime.binding import get_log_path
from pycompss.util.logs import init_logging
from pycompss.util.jvm_parser import convert_to_dict
from pycompss.util.serializer import SerializerException
from pycompss.util.optional_modules import show_optional_module_warnings

app_path = None

if sys.version_info >= (3, 0):
    py_version = 3
else:
    py_version = 2


def get_logging_cfg_file(log_level):
    logging_cfg_file = 'logging.json'
    cfg_files = {
        'debug': 'logging.json.debug',
        'info': 'logging.json.off',
        'off': 'logging.json.off'
    }
    if log_level in cfg_files:
        logging_cfg_file = cfg_files[log_level]
    return logging_cfg_file


def parse_arguments():
    import argparse
    parser = argparse.ArgumentParser(description='PyCOMPSs application launcher')
    parser.add_argument('log_level', help='Logging level [debug|info|off]')
    parser.add_argument('object_conversion', help='Object_conversion [true|false]')
    parser.add_argument('storage_configuration', help='Storage configuration [null|*]')
    parser.add_argument('app_path', help='Application path')
    return parser.parse_args()


def compss_main():
    '''
    General call:
    python $PYCOMPSS_HOME/pycompss/runtime/launch.py $log_level $PyObject_serialize $storageConf $fullAppPath $application_args
    '''
    global app_path

    # Start the runtime, see bindings commons
    compss_start()

    # See parse_arguments, defined above
    # In order to avoid parsing user arguments, we are going to remove user
    # args from sys.argv
    user_sys_argv = sys.argv[5:]
    sys.argv = sys.argv[:5]
    args = parse_arguments()
    # We are done, now sys.argv must contain user args only
    sys.argv = [args.app_path] + user_sys_argv

    # Get log_level
    log_level = args.log_level

    # Get object_conversion boolean
    binding.object_conversion = args.object_conversion == 'true'

    # Get storage configuration at master
    storage_conf = args.storage_configuration
    persistent_storage = False
    if storage_conf != 'null':
        persistent_storage = True
        from storage.api import init as init_storage
        from storage.api import finish as finish_storage

    # Get application execution path
    app_path = args.app_path

    binding_log_path = get_log_path()
    log_path = os.path.join(os.getenv('COMPSS_HOME'), 'Bindings', 'python', str(py_version), 'log')
    binding.temp_dir = mkdtemp(prefix='pycompss', dir=os.path.join(binding_log_path, 'tmpFiles/'))

    logging_cfg_file = get_logging_cfg_file(log_level)

    init_logging(os.path.join(log_path, logging_cfg_file), binding_log_path)
    if __debug__:
        logger = logging.getLogger('pycompss.runtime.launch')

    # Get JVM options
    jvm_opts = os.environ['JVM_OPTIONS_FILE']
    opts = convert_to_dict(jvm_opts)
    # storage_conf = opts.get('-Dcompss.storage.conf')

    try:
        if __debug__:
            logger.debug('--- START ---')
            logger.debug('PyCOMPSs Log path: %s' % binding_log_path)
        if persistent_storage:
            if __debug__:
                logger.debug('Storage configuration file: %s' % storage_conf)
            init_storage(config_file_path=storage_conf)
        show_optional_module_warnings()
        # MAIN EXECUTION
        if sys.version_info >= (3, 0):
            exec(compile(open(app_path).read(), app_path, 'exec'), globals())
        else:
            execfile(app_path, globals())    # MAIN EXECUTION
        if persistent_storage:
            finish_storage()
        if __debug__:
            logger.debug('--- END ---')
    except SystemExit as e:
        if e.code != 0:  # Seems this is not happening
            print('[ ERROR ]: User program ended with exitcode %s.' % e.code)
            print('\t\tShutting down runtime...')
    except SerializerException:
        # If an object that can not be serialized has been used as a parameter.
        exc_type, exc_value, exc_traceback = sys.exc_info()
        lines = traceback.format_exception(exc_type, exc_value, exc_traceback)
        for line in lines:
            if app_path in line:
                print('[ ERROR ]: In: %s', line)
    finally:
        compss_stop()
        sys.stdout.flush()
        sys.stderr.flush()

    # --- Execution finished ---


#################################################
# For external execution
#################################################

# Version 4.0
def launch_pycompss_application(app, func, args=[], kwargs={},
                                log_level='off',
                                o_c=False,
                                debug=False,
                                graph=False,
                                trace=False,
                                monitor=None,
                                project_xml=None,
                                resources_xml=None,
                                summary=False,
                                taskExecution='compss',
                                storageConf=None,
                                taskCount=50,
                                appName=None,
                                uuid=None,
                                baseLogDir=None,
                                specificLogDir=None,
                                extraeCfg=None,
                                comm='NIO',
                                conn='es.bsc.compss.connectors.DefaultSSHConnector',
                                masterName='',
                                masterPort='',
                                scheduler='es.bsc.compss.scheduler.loadBalancingScheduler.LoadBalancingScheduler',
                                jvmWorkers='-Xms1024m,-Xmx1024m,-Xmn400m',
                                obj_conv=False,
                                cpuAffinity='automatic',
                                gpuAffinity='automatic',
                                profileInput='',
                                profileOutput='',
                                scheduler_config='',
                                external_adaptation=False,
                                python_propagate_virtual_environment=True
                                ):
    global app_path
    launchPath = os.path.dirname(os.path.abspath(__file__))
    # compss_home = launchPath without the last 4 folders:
    # (Bindings/python/pycompss/runtime)
    compss_home = os.path.sep.join(launchPath.split(os.path.sep)[:-4])

    # Grab the existing PYTHONPATH and CLASSPATH values
    pythonpath = os.environ['PYTHONPATH']
    classpath = os.environ['CLASSPATH']

    # Enable/Disable object to string conversion
    binding.object_conversion = obj_conv

    # Get the filename and its path.
    file_name = os.path.splitext(os.path.basename(app))[0]
    cp = os.path.dirname(app)

    # Build a dictionary with all variables needed for initializing the runtime.
    config = {}
    config['compss_home'] = compss_home
    config['debug'] = debug
    if project_xml is None:
        projXml = 'Runtime/configuration/xml/projects/default_project.xml'
        config['project_xml'] = compss_home + os.path.sep + projXml
    else:
        config['project_xml'] = project_xml
    if resources_xml is None:
        resXml = 'Runtime/configuration/xml/resources/default_resources.xml'
        config['resources_xml'] = compss_home + os.path.sep + resXml
    else:
        config['resources_xml'] = resources_xml
    config['summary'] = summary
    config['taskExecution'] = taskExecution
    config['storageConf'] = storageConf
    config['taskCount'] = taskCount
    if appName is None:
        config['appName'] = file_name
    else:
        config['appName'] = appName
    config['uuid'] = uuid
    config['baseLogDir'] = baseLogDir
    config['specificLogDir'] = specificLogDir
    config['graph'] = graph
    config['monitor'] = monitor
    config['trace'] = trace
    config['extraeCfg'] = extraeCfg
    config['comm'] = comm
    config['conn'] = conn
    config['masterName'] = masterName
    config['masterPort'] = masterPort
    config['scheduler'] = scheduler
    config['cp'] = cp
    config['classpath'] = classpath
    config['jvmWorkers'] = jvmWorkers
    config['pythonPath'] = pythonpath
    config['cpuAffinity'] = cpuAffinity
    config['gpuAffinity'] = gpuAffinity
    config['profileInput'] = profileInput
    config['profileOutput'] = profileOutput
    config['scheduler_config'] = scheduler_config
    if external_adaptation:
        config['external_adaptation'] = 'true'
    else:
        config['external_adaptation'] = 'false'
    config['python_interpreter'] = 'python' + str(sys.version_info[0])
    config['python_version'] = str(sys.version_info[0])
    if 'VIRTUAL_ENV' in os.environ:
        # Running within a virtual environment
        python_virtual_environment = os.environ['VIRTUAL_ENV']
    else:
        python_virtual_environment = 'null'
    config['python_virtual_environment'] = python_virtual_environment
    config['python_propagate_virtual_environment'] = python_propagate_virtual_environment

    initialize_compss(config)

    # Runtime start
    compss_start()

    # Configure logging
    app_path = app
    log_path = get_log_path()
    if debug:
        # DEBUG
        init_logging(compss_home + '/Bindings/python/log/logging.json.debug', log_path)
    else:
        # NO DEBUG
        init_logging(compss_home + '/Bindings/python/log/logging.json', log_path)
    logger = logging.getLogger("pycompss.runtime.launch")

    logger.debug('--- START ---')
    logger.debug('PyCOMPSs Log path: %s' % log_path)
    saved_argv = sys.argv
    sys.argv = args
    # Execution:
    if func is None or func == '__main__':
        result = execfile(app)
    else:
        import imp
        imported_module = imp.load_source(file_name, app)
        methodToCall = getattr(imported_module, func)
        result = methodToCall(*args, **kwargs)
    # Recover the system arguments
    sys.argv = saved_argv
    logger.debug('--- END ---')

    compss_stop()

    return result


def initialize_compss(config):
    '''Creates the initialization files for the runtime start (java options file).
    Receives a dictionary (config) with the configuration parameters.
    WARNING!!! if new parameters are included in the runcompss launcher,
    they have to be considered in this configuration. Otherwise, the runtime will not start.
    * Current required parameters:
        - 'compss_home'    = <String>       = COMPSs installation path
        - 'debug'          = <Boolean>      = Enable/Disable debugging (True|False)
        - 'project_xml'    = <String>       = Specific project.xml path
        - 'resources_xml'  = <String>       = Specific resources.xml path
        - 'summary'        = <Boolean>      = Enable/Disable summary (True|False)
        - 'taskExecution'  = <String>       = Who performs the task execution (normally "compss")
        - 'storageConf'    = None|<String>  = Storage configuration file path
        - 'taskCount'      = <Integer>      = Number of tasks (for structure initialization purposes)
        - 'appName'        = <String>       = Application name
        - 'uuid'           = None|<String>  = Application UUID
        - 'baseLogDir'     = None|<String>  = Base log path
        - 'specificLogDir' = None|<String>  = Specific log path
        - 'graph'          = <Boolean>      = Enable/Disable graph generation
        - 'monitor'        = None|<Integer> = Disable/Frequency of the monitor
        - 'trace'          = <Boolean>      = Enable/Disable trace generation
        - 'extraeCfg'      = None|<String>  = Default extrae configuration/User specific extrae configuration
        - 'comm'           = <String>       = GAT/NIO
        - 'conn'           = <String>       = Connector (normally: es.bsc.compss.connectors.DefaultSSHConnector)
        - 'masterName'     = <String>       = Master node name
        - 'masterPort'     = <String>       = Master node port
        - 'scheduler'      = <String>       = Scheduler (normally: es.bsc.compss.scheduler.resourceEmptyScheduler.ResourceEmptyScheduler)
        - 'cp'             = <String>       = Application path
        - 'classpath'      = <String>       = CLASSPATH environment variable contents
        - 'pythonPath'     = <String>       = PYTHONPATH environment variable contents
        - 'jvmWorkers'     = <String>       = Worker's jvm configuration (example: "-Xms1024m,-Xmx1024m,-Xmn400m")
        - 'cpuAffinity'    = <String>       = (default: automatic)
        - 'gpuAffinity'    = <String>       = (default: automatic)
        - 'profileInput'   = <String>       = profiling input
        - 'profileOutput'  = <String>       = profiling output
        - 'scheduler_config'    = <String>  = Path to the file which contains the scheduler configuration.
        - 'external_adaptation' = <String>  = Enable external adaptation. This option will disable the Resource Optimizer
        - 'python_interpreter'  = <String>  = Python interpreter
        - 'python_version'      = <String>  = Python interpreter version
        - 'python_virtual_environment'            = <String>  = Python virtual environment path
        - 'python_propagate_virtual_environment'  = <Boolean> = Propagate python virtual environment to workers
    :param config: Configuration parameters dictionary
    '''
    from tempfile import mkstemp
    fd, temp_path = mkstemp()
    jvm_options_file = open(temp_path, 'w')

    # JVM GENERAL OPTIONS
    jvm_options_file.write('-XX:+PerfDisableSharedMem\n')
    jvm_options_file.write('-XX:-UsePerfData\n')
    jvm_options_file.write('-XX:+UseG1GC\n')
    jvm_options_file.write('-XX:+UseThreadPriorities\n')
    jvm_options_file.write('-XX:ThreadPriorityPolicy=42\n')
    if config['debug']:
        jvm_options_file.write('-Dlog4j.configurationFile=' + config['compss_home'] + '/Runtime/configuration/log/COMPSsMaster-log4j.debug\n')  # DEBUG
    else:
        jvm_options_file.write('-Dlog4j.configurationFile=' + config['compss_home'] + '/Runtime/configuration/log/COMPSsMaster-log4j\n')  # NO DEBUG
    jvm_options_file.write('-Dcompss.to.file=false\n')
    jvm_options_file.write('-Dcompss.project.file=' + config['project_xml'] + '\n')
    jvm_options_file.write('-Dcompss.resources.file=' + config['resources_xml'] + '\n')
    jvm_options_file.write(
        '-Dcompss.project.schema=' + config['compss_home'] + '/Runtime/configuration/xml/projects/project_schema.xsd\n')
    jvm_options_file.write(
        '-Dcompss.resources.schema=' + config['compss_home'] + '/Runtime/configuration/xml/resources/resources_schema.xsd\n')
    jvm_options_file.write('-Dcompss.lang=python\n')
    if config['summary']:
        jvm_options_file.write('-Dcompss.summary=true\n')
    else:
        jvm_options_file.write('-Dcompss.summary=false\n')
    jvm_options_file.write('-Dcompss.task.execution=' + config['taskExecution'] + '\n')
    if config['storageConf'] is None:
        jvm_options_file.write('-Dcompss.storage.conf=null\n')
    else:
        jvm_options_file.write('-Dcompss.storage.conf=' + config['storageConf'] + '\n')

    jvm_options_file.write('-Dcompss.core.count=' + str(config['taskCount']) + '\n')

    jvm_options_file.write('-Dcompss.appName=' + config['appName'] + '\n')

    if config['uuid'] is None:
        import uuid
        myUuid = str(uuid.uuid4())
    else:
        myUuid = config['uuid']

    jvm_options_file.write('-Dcompss.uuid=' + myUuid + '\n')

    if config['baseLogDir'] is None:
        # it will be within $HOME/.COMPSs
        jvm_options_file.write('-Dcompss.baseLogDir=\n')
    else:
        jvm_options_file.write('-Dcompss.baseLogDir=' + config['baseLogDir'] + '\n')

    if config['specificLogDir'] is None:
        jvm_options_file.write('-Dcompss.specificLogDir=\n')
    else:
        jvm_options_file.write('-Dcompss.specificLogDir=' + config['specificLogDir'] + '\n')

    jvm_options_file.write('-Dcompss.appLogDir=/tmp/' + myUuid + '/\n')

    if config['graph']:
        jvm_options_file.write('-Dcompss.graph=true\n')
    else:
        jvm_options_file.write('-Dcompss.graph=false\n')

    if config['monitor'] is None:
        jvm_options_file.write('-Dcompss.monitor=0\n')
    else:
        jvm_options_file.write('-Dcompss.monitor=' + str(config['monitor']) + '\n')

    if not config['trace'] or config['trace'] == 0:
        jvm_options_file.write('-Dcompss.tracing=0' + '\n')
    elif config['trace'] == 1:
        jvm_options_file.write('-Dcompss.tracing=1\n')
        os.environ['EXTRAE_CONFIG_FILE'] = config['compss_home'] + '/Runtime/configuration/xml/tracing/extrae_basic.xml'
    elif config['trace'] == 2:
        jvm_options_file.write('-Dcompss.tracing=2\n')
        os.environ['EXTRAE_CONFIG_FILE'] = config['compss_home'] + '/Runtime/configuration/xml/tracing/extrae_advanced.xml'
    else:
        jvm_options_file.write('-Dcompss.tracing=0' + '\n')

    if config['extraeCfg'] is None:
        jvm_options_file.write('-Dcompss.extrae.file=null\n')
    else:
        jvm_options_file.write('-Dcompss.extrae.file=' + config['extraeCfg'] + '\n')

    if config['comm'] == 'GAT':
        jvm_options_file.write('-Dcompss.comm=es.bsc.compss.gat.master.GATAdaptor\n')
    else:
        jvm_options_file.write('-Dcompss.comm=es.bsc.compss.nio.master.NIOAdaptor\n')

    jvm_options_file.write('-Dcompss.conn=' + config['conn'] + '\n')
    jvm_options_file.write('-Dcompss.masterName=' + config['masterName'] + '\n')
    jvm_options_file.write('-Dcompss.masterPort=' + config['masterPort'] + '\n')
    jvm_options_file.write('-Dcompss.scheduler=' + config['scheduler'] + '\n')
    jvm_options_file.write('-Dgat.adaptor.path=' + config['compss_home'] + '/Dependencies/JAVA_GAT/lib/adaptors\n')
    if config['debug']:
        jvm_options_file.write('-Dgat.debug=true\n')
    else:
        jvm_options_file.write('-Dgat.debug=false\n')
    jvm_options_file.write('-Dgat.broker.adaptor=sshtrilead\n')
    jvm_options_file.write('-Dgat.file.adaptor=sshtrilead\n')
    jvm_options_file.write('-Dcompss.worker.cp=' + config['cp'] + ':' + config['compss_home'] + '/Runtime/compss-engine.jar:' + config['classpath'] + '\n')
    jvm_options_file.write('-Dcompss.worker.jvm_opts=' + config['jvmWorkers'] + '\n')
    jvm_options_file.write('-Dcompss.worker.cpu_affinity=' + config['cpuAffinity'] + '\n')
    jvm_options_file.write('-Dcompss.worker.gpu_affinity=' + config['gpuAffinity'] + '\n')
    jvm_options_file.write('-Dcompss.profile.input=' + config['profileInput'] + '\n')
    jvm_options_file.write('-Dcompss.profile.output=' + config['profileOutput'] + '\n')
    jvm_options_file.write('-Dcompss.scheduler.config=' + config['scheduler_config'] + '\n')
    jvm_options_file.write('-Dcompss.external.adaptation=' + config['external_adaptation'] + '\n')

    # JVM OPTIONS - PYTHON
    jvm_options_file.write('-Djava.class.path=' + config['cp'] + ':' + config['compss_home'] + '/Runtime/compss-engine.jar:' + config['classpath'] + '\n')
    jvm_options_file.write('-Dcompss.worker.pythonpath=' + config['cp'] + ':' + config['pythonPath'] + '\n')
    jvm_options_file.write('-Dcompss.python.interpreter=' + config['python_interpreter'] + '\n')
    jvm_options_file.write('-Dcompss.python.version=' + config['python_version'] + '\n')
    jvm_options_file.write('-Dcompss.python.virtualenvironment=' + config['python_virtual_environment'] + '\n')
    if config['python_propagate_virtual_environment']:
        jvm_options_file.write('-Dcompss.python.propagate_virtualenvironment=true\n')
    else:
        jvm_options_file.write('-Dcompss.python.propagate_virtualenvironment=false\n')
    jvm_options_file.close()
    os.close(fd)
    os.environ['JVM_OPTIONS_FILE'] = temp_path

    # print("Uncomment if you want to check the configuration file path.")
    # print("JVM_OPTIONS_FILE: %s" % temp_path)


'''
This is the PyCOMPSs entry point
'''
if __name__ == '__main__':
    compss_main()
