%define name	 	compss-framework 
%define version		mf2c-it1
%define release		1

Requires: compss-runtime, compss-bindings, compss-tools, compss-cloud
Summary: The BSC COMP Superscalar Framework
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
Prefix: /opt
BuildArch: noarch

%description
The BSC COMP Superscalar Framework.

%prep

#------------------------------------------------------------------------------------
%build

#------------------------------------------------------------------------------------
%install

#------------------------------------------------------------------------------------
%post 

#------------------------------------------------------------------------------------
%preun

#------------------------------------------------------------------------------------
%postun 

#------------------------------------------------------------------------------------
%clean

#------------------------------------------------------------------------------------
%files 
