%define name	 	compss-cloud 
%define version		mf2c-it1
%define release		1

Requires: compss-engine
Summary: The BSC COMP Superscalar Runtime Cloud Resources
Name: %{name}
Version: %{version}
Release: %{release}
License: Apache 2.0
Group: Development/Libraries
Source: %{name}-%{version}.tar.gz
Distribution: Linux
Vendor: Barcelona Supercomputing Center - Centro Nacional de Supercomputación
URL: http://compss.bsc.es
Packager: COMPSs Support <support-compss@bsc.es>
Prefix: /opt/COMPSs/Runtime
ExclusiveArch: x86_64

%description
The BSC COMP Superscalar Runtime Cloud Resources.

%prep
%setup -q

#------------------------------------------------------------------------------------
%build
echo "* Building COMP Superscalar Runtime Cloud Resources..."
echo " "

echo "   - Compile sources"
cd resources
mvn -U clean install
cd ..

echo "   - Create deployment folders"
mkdir -p COMPSs/Runtime/connectors
mkdir -p COMPSs/Runtime/cloud-conn

echo "   - Copy deployment files"
#Connectors
connectors=$(find ./resources/ -name "*.jar" | grep -v "cloud-conn")
for conn in $connectors; do
  cp $conn COMPSs/Runtime/connectors/
done
connectors=$(find ./resources/ -name "*.jar" | grep "cloud-conn")
for conn in $connectors; do
  cp $conn COMPSs/Runtime/cloud-conn/
done 

echo "   - Erase sources"
ls . | grep -v COMPSs | xargs rm -r

echo "COMP Superscalar Runtime Cloud Resources built"
echo " "

#------------------------------------------------------------------------------------
%install
echo "* Installing COMPSs Runtime Cloud Resources..."

echo " - Creating COMPSs Runtime Cloud Resource structure..."
mkdir -p ${RPM_BUILD_ROOT}/opt/COMPSs/Runtime/
cp -r COMPSs/Runtime/connectors ${RPM_BUILD_ROOT}/opt/COMPSs/Runtime/
cp -r COMPSs/Runtime/cloud-conn ${RPM_BUILD_ROOT}/opt/COMPSs/Runtime/
echo " - COMPSs Runtime Cloud Resources structure created"
echo " "

echo " - Setting COMPSs Runtime Cloud Resources permissions..."
chmod 755 -R ${RPM_BUILD_ROOT}/opt/COMPSs/Runtime/connectors
chmod 755 -R ${RPM_BUILD_ROOT}/opt/COMPSs/Runtime/cloud-conn
echo " - COMPSs Runtime Cloud Resources permissions set"
echo " "

echo "Congratulations!"
echo "COMPSs Runtime Cloud Resources Successfully installed!"
echo " "

#------------------------------------------------------------------------------------
%post 
echo "* Installing COMPSs Runtime Cloud Resources..."
echo " "

echo "Congratulations!"
echo "COMPSs Runtime Cloud Resources Successfully installed!"
echo " "


#------------------------------------------------------------------------------------
%preun

#------------------------------------------------------------------------------------
%postun 
rm -rf /opt/COMPSs/Runtime/connectors
rm -rf /opt/COMPSs/Runtime/cloud-conn
echo "COMPSs Runtime Cloud Resources Successfully uninstalled!"
echo " "

#------------------------------------------------------------------------------------
%clean
rm -rf ${RPM_BUILD_ROOT}/opt/COMPSs/Runtime/connectors
rm -rf ${RPM_BUILD_ROOT}/opt/COMPSs/Runtime/cloud-conn

#------------------------------------------------------------------------------------
%files 
%defattr(-,root,root)
/opt/COMPSs/Runtime/connectors
/opt/COMPSs/Runtime/cloud-conn

