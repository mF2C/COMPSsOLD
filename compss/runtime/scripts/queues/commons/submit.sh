#!/bin/bash

#---------------------------------------------------
# SCRIPT CONSTANTS DECLARATION
#---------------------------------------------------
DEFAULT_SC_CFG="default"


#---------------------------------------------------
# ERROR CONSTANTS DECLARATION
#---------------------------------------------------
ERROR_NUM_NODES="Invalid number of nodes"
ERROR_SWITCHES="Too little switches for the specified number of nodes"
ERROR_NO_ASK_SWITCHES="Cannot ask switches for less than ${MIN_NODES_REQ_SWITCH} nodes"
ERROR_NODE_MEMORY="Incorrect node_memory parameter. Only disabled or <int> allowed. I.e. 33000, 66000"
ERROR_TMP_FILE="Cannot create TMP Submit file"
ERROR_SUBMIT="Error submiting script to queue system"
ERROR_STORAGE_PROPS="storage_props flag not defined"
ERROR_STORAGE_PROPS_FILE="storage_props file doesn't exist"


#---------------------------------------------------------------------------------------
# HELPER FUNCTIONS
#---------------------------------------------------------------------------------------

###############################################
# Displays usage
###############################################
usage() {
  local exitValue=$1

  /bin/cat <<EOT
Usage: $0 [options] application_name application_arguments

* Options:
  General:
    --help, -h                              Print this help message

    --opts                                  Show available options

    --version, -v                           Print COMPSs version
    
    --sc_cfg=<name>                         SuperComputer configuration file to use. Must exist inside queues/cfgs/
                                            Mandatory
                                            Default: ${DEFAULT_SC_CFG}
    
  Submission configuration:
EOT

  show_opts $exitValue
}

###############################################
# Show Options
###############################################
show_opts() {
  local exitValue=$1

  # Load default CFG for default values
  local defaultSC_cfg=${scriptDir}/../cfgs/${DEFAULT_SC_CFG}.cfg
  source ${defaultSC_cfg}
  local defaultQS_cfg=${scriptDir}/../${QUEUE_SYSTEM}/${QUEUE_SYSTEM}.cfg
  source ${defaultQS_cfg}
  
  # Show usage
  /bin/cat <<EOT
    --exec_time=<minutes>                   Expected execution time of the application (in minutes)
                                            Default: ${DEFAULT_EXEC_TIME}
    --num_nodes=<int>                       Number of nodes to use
                                            Default: ${DEFAULT_NUM_NODES}
    --num_switches=<int>                    Maximum number of different switches. Select 0 for no restrictions.
                                            Maximum nodes per switch: ${MAX_NODES_SWITCH}
                                            Only available for at least ${MIN_NODES_REQ_SWITCH} nodes. 
                                            Default: ${DEFAULT_NUM_SWITCHES} 
    --queue=<name>                          Queue name to submit the job. Depends on the queue system.
                                            For example (MN3): bsc_cs | bsc_debug | debug | interactive
                                            Default: ${DEFAULT_QUEUE}
    --reservation=<name>                    Reservation to use when submitting the job. 
                                            Default: ${DEFAULT_RESERVATION}
    --job_dependency=<jobID>                Postpone job execution until the job dependency has ended.
                                            Default: ${DEFAULT_DEPENDENCY_JOB}
    --storage_home=<string>                 Root installation dir of the storage implementation
                                            Default: ${DEFAULT_STORAGE_HOME}
    --storage_props=<string>                Absolute path of the storage properties file
                                            Mandatory if storage_home is defined

 Launch configuration:
EOT
  ${scriptDir}/../../user/launch_compss --opts 

  exit $exitValue
}
  
###############################################
# Displays version
###############################################
display_version() {
  local exitValue=$1

  ${scriptDir}/../../user/runcompss --version

  exit $exitValue
}

###############################################
# Displays errors when treating arguments
###############################################
display_error() {
  local error_msg=$1
  
  echo $error_msg
  echo " "
  
  usage 1
}

###############################################
# Function to log the arguments
###############################################
log_args() {
  # Display arguments
  echo "SC Configuration:          ${sc_cfg}"
  echo "Queue:                     ${queue}"
  echo "Reservation:               ${reservation}"
  echo "Num Nodes:                 ${num_nodes}"
  echo "Num Switches:              ${num_switches}"
  echo "Job dependency:            ${dependencyJob}"
  echo "Exec-Time:                 ${wc_limit}"
  echo "Other:                     ${args_pass}"
}

###############################################
# Function that converts a cost in minutes 
# to an expression of wall clock limit
###############################################
convert_to_wc() {
  local cost=$1
  wc_limit=${EMPTY_WC_LIMIT}

  local min=$(expr $cost % 60)
  if [ $min -lt 10 ]; then
    wc_limit=":0${min}${wc_limit}"
  else
    wc_limit=":${min}${wc_limit}"
  fi

  local hrs=$(expr $cost / 60)
  if [ $hrs -gt 0 ]; then
    if [ $hrs -lt 10 ]; then
      wc_limit="0${hrs}${wc_limit}"
    else
      wc_limit="${hrs}${wc_limit}"
    fi
  else
      wc_limit="00${wc_limit}"
  fi
}


#---------------------------------------------------
# MAIN FUNCTIONS DECLARATION
#---------------------------------------------------

###############################################
# Function to get the arguments
###############################################
get_args() {
  # Avoid enqueue if there is no application
  if [ $# -eq 0 ]; then
    usage 1
  fi

  #Parse COMPSs Options
  while getopts hdt-: flag; do 
    # Treat the argument
    case "$flag" in
      h)
        # Display help
        usage 0
        ;;
      -)
        # Check more complex arguments 
        case "$OPTARG" in
          help)
            # Display help
            usage 0
            ;;
          version)
            # Display compss version
            display_version 0
            ;;
          opts)
            # Display options
            show_opts 0
            ;;
          sc_cfg=*)
            sc_cfg=$(echo $OPTARG | sed -e 's/sc_cfg=//g')
            args_pass="$args_pass --$OPTARG"
            ;;
          master_working_dir=*)
            master_working_dir=$(echo $OPTARG | sed -e 's/master_working_dir=//g')
            args_pass="$args_pass --$OPTARG"
            ;;
          exec_time=*)
            exec_time=$(echo $OPTARG | sed -e 's/exec_time=//g')
            ;;
          num_nodes=*)
            num_nodes=$(echo $OPTARG | sed -e 's/num_nodes=//g')
            ;;
          num_switches=*)
            num_switches=$(echo $OPTARG | sed -e 's/num_switches=//g')
            ;;
          queue=*)
            queue=$(echo $OPTARG | sed -e 's/queue=//g')
            ;;
          reservation=*)
            reservation=$(echo $OPTARG | sed -e 's/reservation=//g')
            ;;
          job_dependency=*)
            dependencyJob=$(echo $OPTARG | sed -e 's/job_dependency=//g')
            ;;
          node_memory=*)
            node_memory=$(echo $OPTARG | sed -e 's/node_memory=//g')
            ;;
          network=*)
            network=$(echo $OPTARG | sed -e 's/network=//g')
            args_pass="$args_pass --$OPTARG"
            ;;
          storage_home=*)
            storage_home=$(echo $OPTARG | sed -e 's/storage_home=//g')
            ;;
          storage_props=*)
            storage_props=$(echo $OPTARG | sed -e 's/storage_props=//g')
            ;;
          storage_conf=*)
            # Storage conf is automatically generated. Remove it from COMPSs flags
            echo "WARNING: storage_conf is automatically generated. Omitting parameter"
            ;;
          *)
            # Flag didn't match any patern. Add to COMPSs 
            args_pass="$args_pass --$OPTARG"
            ;;
        esac
        ;;
      *)
        # Flag didn't match any patern. End of COMPSs flags
        args_pass="$args_pass -$flag"
        ;; 
    esac
  done
  # Shift COMPSs arguments
  shift $((OPTIND-1))

  # Pass application name and args
  args_pass="$args_pass $@" 
}

###############################################
# Function to check the arguments
###############################################
check_args() {
  ###############################################################
  # Queue system checks
  ###############################################################
  if [ -z "${queue}" ]; then
    queue=${DEFAULT_QUEUE}
  fi

  if [ -z "${exec_time}" ]; then
    exec_time=${DEFAULT_EXEC_TIME}
  fi
  convert_to_wc $exec_time
  
  if [ -z "${reservation}" ]; then
    reservation=${DEFAULT_RESERVATION}
  fi

  if [ -z "${dependencyJob}" ]; then
    dependencyJob=${DEFAULT_DEPENDENCY_JOB}
  fi
  
  ###############################################################
  # Infrastructure checks
  ###############################################################
  if [ -z "${num_nodes}" ]; then
    num_nodes=${DEFAULT_NUM_NODES}
  fi
  if [ ${num_nodes} -lt ${MINIMUM_NUM_NODES} ]; then
      display_error "${ERROR_NUM_NODES}" 1
  fi

  if [ -z "${num_switches}" ]; then
    num_switches=${DEFAULT_NUM_SWITCHES}
  fi
  maxnodes=$(expr ${num_switches} \* ${MAX_NODES_SWITCH})
  if [ "${num_switches}" != "0" ] && [ ${maxnodes} -lt ${num_nodes} ]; then
    display_error "${ERROR_SWITCHES}"
  fi
  if [ ${num_nodes} -lt ${MIN_NODES_REQ_SWITCH} ] && [ "${num_switches}" != "0" ]; then
    display_error "${ERROR_NO_ASK_SWITCHES}"
  fi

  # Network variable and modification
  if [ -z "${network}" ]; then
    network=${DEFAULT_NETWORK}
  elif [ "${network}" == "default" ]; then
    network=${DEFAULT_NETWORK}
  elif [ "${network}" != "ethernet" ] && [ "${network}" != "infiniband" ] && [ "${network}" != "data" ]; then
    display_error "${ERROR_NETWORK}"
  fi
  
  ###############################################################
  # Node checks
  ###############################################################
  if [ -z "${node_memory}" ]; then
    node_memory=${DEFAULT_NODE_MEMORY}
  elif [ "${node_memory}" != "disabled" ] && ! [[ "${node_memory}" =~ ^[0-9]+$ ]]; then
    display_error "${ERROR_NODE_MEMORY}"
  fi

  if [ -z "${master_working_dir}" ]; then
    master_working_dir=${DEFAULT_MASTER_WORKING_DIR}
  fi

  ###############################################################
  # Storage checks
  ###############################################################
  if [ -z "${storage_home}" ]; then
    storage_home=${DEFAULT_STORAGE_HOME}
  fi

  if [ "${storage_home}" != "${DISABLED_STORAGE_HOME}" ]; then
    # Check storage props is defined
    if [ -z "${storage_props}" ]; then
      display_error "${ERROR_STORAGE_PROPS}"
    fi

    # Check storage props file exists
    if [ ! -f ${storage_props} ]; then
      # PropsFile doesn't exist
      display_error "${ERROR_STORAGE_PROPS_FILE}"
    fi
  fi
}

###############################################
# Function to create a TMP submit script
###############################################
create_tmp_submit() {
  # Create TMP DIR for submit script
  TMP_SUBMIT_SCRIPT=$(mktemp)
  echo "Temp submit script is: $TMP_SUBMIT_SCRIPT"
  if [ $? -ne 0 ]; then
    display_error "${ERROR_TMP_FILE}" 1
  fi

  # Add queue selection
  if [ "${queue}" != "default" ]; then
    cat >> $TMP_SUBMIT_SCRIPT << EOT
#!/bin/bash
#
#${QUEUE_CMD} ${QARG_QUEUE_SELECTION} ${queue}
EOT
  else 
    cat >> $TMP_SUBMIT_SCRIPT << EOT
#!/bin/bash
#
EOT
  fi

  # Switches selection
  if [ "${num_switches}" != "0" ]; then
    cat >> $TMP_SUBMIT_SCRIPT << EOT
#${QUEUE_CMD} ${QARG_NUM_SWITCHES}${QUEUE_SEPARATOR}"cu[maxcus=${num_switches}]"
EOT
  fi

  # Add Job name and job dependency
  if [ "${dependencyJob}" != "None" ]; then
    if [ "${QARG_JOB_DEP_INLINE}" == "true" ]; then
      cat >> $TMP_SUBMIT_SCRIPT << EOT
#${QUEUE_CMD} ${QARG_JOB_NAME}${QUEUE_SEPARATOR}COMPSs ${QARG_JOB_DEPENDENCY_OPEN}${dependencyJob}${QARG_JOB_DEPENDENCY_CLOSE}
EOT
    else
      cat >> $TMP_SUBMIT_SCRIPT << EOT
#${QUEUE_CMD} ${QARG_JOB_NAME}${QUEUE_SEPARATOR}COMPSs 
#${QUEUE_CMD} ${QARG_JOB_DEPENDENCY_OPEN}${dependencyJob}${QARG_JOB_DEPENDENCY_CLOSE}
EOT
    fi
  else 
    cat >> $TMP_SUBMIT_SCRIPT << EOT
#${QUEUE_CMD} ${QARG_JOB_NAME}${QUEUE_SEPARATOR}COMPSs
EOT
  fi

  # Reservation
  if [ "${reservation}" != "disabled" ]; then
    cat >> $TMP_SUBMIT_SCRIPT << EOT
#${QUEUE_CMD} ${QARG_RESERVATION}${QUEUE_SEPARATOR}${reservation}
EOT
  fi

  # Node memory
  if [ "${node_memory}" != "disabled" ]; then
    cat >> $TMP_SUBMIT_SCRIPT << EOT
#${QUEUE_CMD} ${QARG_MEMORY}${QUEUE_SEPARATOR}${node_memory}
EOT
  fi

  # Generic arguments
  cat >> $TMP_SUBMIT_SCRIPT << EOT
#${QUEUE_CMD} ${QARG_WALLCLOCK} $wc_limit
#${QUEUE_CMD} ${QARG_WD}${QUEUE_SEPARATOR}${master_working_dir}
#${QUEUE_CMD} ${QARG_JOB_OUT} compss-%J.out
#${QUEUE_CMD} ${QARG_JOB_ERROR} compss-%J.err
#${QUEUE_CMD} ${QARG_NUM_NODES}${QUEUE_SEPARATOR}${num_nodes}
#${QUEUE_CMD} ${QARG_EXCLUSIVE_NODES}
EOT

  # Span argument if defined on queue system
  if [ -n "${QARG_SPAN}" ]; then
    cat >> $TMP_SUBMIT_SCRIPT << EOT
#${QUEUE_CMD} ${QARG_SPAN}
EOT
  fi

  # Host list parsing before launch
  cat >> $TMP_SUBMIT_SCRIPT << EOT
  
host_list=\$(${HOSTLIST_CMD} \$${ENV_VAR_NODE_LIST} | sed -e 's/\.[^\ ]*//g')
master_node=\$(hostname)
worker_nodes=\$(echo \${host_list} | sed -e "s/\${master_node}//g")
EOT

  # Storage init
  if [ "${storage_home}" != "${DISABLED_STORAGE_HOME}" ]; then
    # ADD STORAGE_INIT, STORAGE_FINISH AND NODES PARSING
    cat >> $TMP_SUBMIT_SCRIPT << EOT
storage_conf=$HOME/.COMPSs/\$${ENV_VAR_JOB_ID}/storage/cfgfiles/client.properties
storage_master_node=\$(echo \${worker_nodes} | tr " " "\t" | awk {' print \$1 '})
worker_nodes=\$(echo \${host_list} | sed -e "s/\${storage_master_node}//g")

${storage_home}/scripts/storage_init.sh \$${ENV_VAR_JOB_ID} "\${master_node}" "\${storage_master_node}" "\${worker_nodes}" ${network} ${storage_props}

${scriptDir}/../../user/launch_compss --master_node="\${master_node}" --worker_nodes="\${worker_nodes}" --node_memory=${node_memory} --storage_conf=\${storage_conf} ${args_pass}

${storage_home}/scripts/storage_stop.sh \$${ENV_VAR_JOB_ID} "\${master_node}" "\${storage_master_node}" "\${worker_nodes}" ${network}

EOT
  else
    # ONLY ADD EXECUTE COMMAND
    cat >> $TMP_SUBMIT_SCRIPT << EOT

${scriptDir}/../../user/launch_compss --master_node="\${master_node}" --worker_nodes="\${worker_nodes}" --node_memory=${node_memory} ${args_pass}
EOT
  fi
}

###############################################
# Function to clean TMP files
###############################################
cleanup() {
  rm -rf ${TMP_SUBMIT_SCRIPT}.*
}

###############################################
# Function to submit the script
###############################################
submit() {
  # Submit the job to the queue
  ${SUBMISSION_CMD} < ${TMP_SUBMIT_SCRIPT} 1>${TMP_SUBMIT_SCRIPT}.out 2>${TMP_SUBMIT_SCRIPT}.err
  result=$?

  # Check if submission failed
  if [ $result -ne 0 ]; then
    submit_err=$(cat ${TMP_SUBMIT_SCRIPT}.err)
    echo "${ERROR_SUBMIT}${submit_err}"
    exit 1
  fi
}


#---------------------------------------------------
# MAIN EXECUTION
#---------------------------------------------------
  scriptDir=$(dirname $0)

  # Get command args
  get_args "$@"
  
  # Load specific queue system variables
  source ${scriptDir}/../cfgs/${sc_cfg}
  source ${scriptDir}/../${QUEUE_SYSTEM}/${QUEUE_SYSTEM}.cfg

  # Check parameters
  check_args

  # Log received arguments
  log_args

  # Create TMP submit script
  create_tmp_submit
  
  # Trap cleanup
  trap cleanup EXIT

  # Submit
  submit