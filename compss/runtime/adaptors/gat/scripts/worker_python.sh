#!/bin/bash

  ####################################
  # WORKER SPECIFIC HELPER FUNCTIONS #
  ####################################

  activate_virtual_environment () {
    source ${pythonVirtualEnvironment}/bin/activate
  }

  deactivate_virtual_environment () {
    # 'deactivate' function included in virtual environment activate script
    deactivate
  }

  ####################################
  #               MAIN               #
  ####################################

  # Get worker common functions
  scriptDir=$(dirname $0)
  source ${scriptDir}/worker_commons.sh

  # Pre-execution
  get_parameters $@
  if [ "$pythonVirtualEnvironment" != "null" ] && [ "$pythonPropagateVirtualEnvironment" == "true" ]; then
    activate_virtual_environment
  fi
  set_env

  # Execution
  taskTracing=false # Only available with NIO
  taskId=0 # Not used with GAT

  # Include version subfolder in pycompss home and set pythonpath related env
  export PYCOMPSS_HOME=${PYCOMPSS_HOME}/${pythonVersion}
  export PYTHONPATH=${PYCOMPSS_HOME}:${pythonpath}:${app_dir}:${PYTHONPATH}

  echo "[WORKER_PYTHON.SH] PYTHONPATH: ${PYTHONPATH}"
  echo "[WORKER_PYTHON.SH] EXEC CMD: $pythonInterpreter ${PYCOMPSS_HOME}/pycompss/worker/worker.py $taskTracing $taskId $params"
  $pythonInterpreter ${PYCOMPSS_HOME}/pycompss/worker/worker.py $taskTracing $taskId $params

  exitCode=0
  # Exit
  if [ $? -eq 0 ]; then
    exitCode=0
  else
    echo 1>&2 "Task execution failed"
    exitCode=7
  fi

  if [ "$pythonVirtualEnvironment" != "null" ] && [ "$pythonPropagateVirtualEnvironment" == "true" ]; then
    deactivate_virtual_environment
  fi

  exit $exitCode
