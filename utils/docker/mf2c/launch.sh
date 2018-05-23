#!/bin/bash
 
NODE_PORT=46100
DEBUG="off"
REPORT_ADDRESS="null"

usage() {
cat << EOF
Usage: $0 [OPTION]...

Mandatory options:
  -h, --hostname           	name of the mF2C hostname

COMPSs options:  
  -p, --port               	port on which the agent listens. Default value 46100
  -ra, --reportAddress		URL of the service where to report the execution profile
  -d, --debug			sets debug level

Other options:
  --help			prints this message

EOF
}


while true; do
  case "$1" in
	-h 		| --hostname )		NODE_HOSTNAME=$2; 				shift 2;;
	-p 		| --port ) 		NODE_PORT=$2; 					shift 2;;
	-ra 		| --reportAddress )	REPORT_ADDRESS=$2; 				shift 2;;
	-d		| --debug )		DEBUG=$2;					shift 2;;
	--help )				usage;						exit;;
    -- ) shift; break ;;
    * ) break ;;
  esac
done

if [[ -z "$NODE_HOSTNAME" ]]; then
	echo "ERROR! MF2C_HOSTNAME not set"
	exit
fi

echo "Launching COMPSs agent on Worker ${NODE_HOSTNAME} and port ${NODE_PORT} with debug level ${DEBUG} reporting to ${REPORT_ADDRESS}"
REPORT_ADDRESS="-Dreport.address=${REPORT_ADDRESS} ";
# LAUNCH COMPSs AGENT
export COMPSS_HOME=/opt/COMPSs
java \
	-DCOMPSS_HOME=/opt/COMPSs \
	-cp /app/app.jar:/opt/COMPSs/Runtime/compss-agent.jar \
	-Dlog4j.configurationFile=/opt/COMPSs/Runtime/configuration/COMPSsMaster-log4j.${DEBUG} \
	-Dcompss.scheduler=es.bsc.compss.scheduler.loadBalancingScheduler.LoadBalancingScheduler \
	-DMF2C_HOST=${NODE_HOSTNAME} \
	${REPORT_ADDRESS}\
	es.bsc.compss.agent.Agent \
	${NODE_PORT}

