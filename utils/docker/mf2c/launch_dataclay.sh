#!/bin/bash

NODE_PORT=46100
DEBUG="off"
REPORT_ADDRESS=""

USERNAME="AppUser"
PASSWORD="AppPwd"
DATASET="AppDS"
NAMESPACE="AppNS"

usage() {
cat << EOF
Usage: $0 [OPTION]...

Mandatory options:
  -h, --hostname           	name of the mF2C hostname

COMPSs options:  
  -p, --port               	port on which the agent listens. Default value 46100
  -ra, --reportAddress		URL of the service where to report the execution profile
  -d, --debug			enables debug.

DataClay options:  
  -u, --username            	DataClay user
  -pwd, --password		DataClay password 
  -ds, --dataset		DataClay dataset name
  -ns, --namespace		DataClay namespace

Other options:
  --help			prints this message

EOF
}

while true; do
  case "$1" in
	-h	| --hostname )		NODE_HOSTNAME=$2; 				shift 2;;
	-p 	| --port ) 		NODE_PORT=$2; 					shift 2;;
	-ra 	| --reportAddress )	REPORT_ADDRESS=$2; 				shift 2;;
	-d	| --debug )		DEBUG=$2;					shift 2;;
	-u 	| --user ) 		USERNAME=$2; 					shift 2;;
	-pwd 	| --password )		PASSWORD=$2; 					shift 2;;
	-ds 	| --dataset )		DATASET=$2; 					shift 2;;
	-ns	| --namespace )		NAMESPACE=$2; 					shift 2;;
	--help)				usage;						exit;;					
    -- ) shift; break ;;
    * ) break ;;
  esac
done

if [[ -z "$NODE_HOSTNAME" ]]; then
	echo "ERROR! MF2C_HOSTNAME not set"
	exit
fi


CURRENT_DIR=`pwd`

echo "GENERATING DATACLAY CONFIGURATION FILES..."
echo "    * Creating client.properties"
mkdir -p ${CURRENT_DIR}/cfgfiles
cat << EOF >> ${CURRENT_DIR}/cfgfiles/client.properties
HOST=127.0.0.1
TCPPORT=11034
EOF

echo "    * Creating session.properties"
cat << EOF >> ${CURRENT_DIR}/cfgfiles/session.properties
Account=${USERNAME}
Password=${PASSWORD}
StubsClasspath=${CURRENT_DIR}/stubs
DataSets=${DATASET}
DataSetForStore=${DATASET}
DataClayClientConfig=${CURRENT_DIR}/cfgfiles/client.properties
EOF



echo "Preparing DataClay environment"
DC_TOOL="java -Dorg.apache.logging.log4j.simplelog.StatusLogger.level=OFF -cp /app/dataclay.jar"
# Registering User
${DC_TOOL} dataclay.tool.NewAccount ${USERNAME} ${PASSWORD}
#Registering DATASET
${DC_TOOL} dataclay.tool.NewDataContract ${USERNAME} ${PASSWORD} ${DATASET} ${USERNAME}

#Registering Application classes
${DC_TOOL} dataclay.tool.NewNamespace ${USERNAME} ${PASSWORD} ${NAMESPACE} java

mkdir classes
cd classes
jar xf /app/app.jar
ls | grep -v datamodel | xargs rm -rf
cd ..

if [ 0 -ge 0 ]; then
	${DC_TOOL} dataclay.tool.NewModel ${USERNAME} ${PASSWORD} ${NAMESPACE} classes
fi
rm -rf classes

#Obtaining stubs
${DC_TOOL} dataclay.tool.AccessNamespace ${USERNAME} ${PASSWORD} ${NAMESPACE}
${DC_TOOL} dataclay.tool.GetStubs ${USERNAME} ${PASSWORD} ${NAMESPACE} ${CURRENT_DIR}/stubs

# LAUNCH COMPSs AGENT
echo "Launching COMPSs agent on Worker ${NODE_HOSTNAME} and port ${NODE_PORT} with debug level ${DEBUG}"
echo "User authenticates to Dataclay with username ${USERNAME} and password ${PASSWORD}"
echo "DataClay will use the ${DATASET} dataset and the namespace ${NAMESPACE}"
export COMPSS_HOME=/opt/COMPSs
REPORT_ADDRESS="-Dreport.address=${REPORT_ADDRESS} ";
java \
	-DCOMPSS_HOME=/opt/COMPSs \
	-cp stubs:/app/app.jar:/app/dataclay.jar:/opt/COMPSs/Runtime/compss-agent.jar \
	-Dcompss.scheduler=es.bsc.compss.scheduler.loadBalancingScheduler.LoadBalancingScheduler \
	-Dlog4j.configurationFile=/opt/COMPSs/Runtime/configuration/COMPSsMaster-log4j.${DEBUG} \
	-DMF2C_HOST=${NODE_HOSTNAME} \
	${REPORT_ADDRESS}\
	-Ddataclay.configpath=${CURRENT_DIR}/cfgfiles/session.properties \
	es.bsc.compss.agent.Agent \
	${NODE_PORT}

