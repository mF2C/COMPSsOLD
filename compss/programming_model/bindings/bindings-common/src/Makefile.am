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

include_HEADERS = GS_compss.h param_metadata.h
lib_LTLIBRARIES = libbindings_common.la

libbindings_common_la_SOURCES = GS_compss.cc
libbindings_common_la_CPPFLAGS = -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux/ -I.
libbindings_common_la_LDFLAGS = -shared -L$(JAVA_LIB_DIR) -ljvm

ACLOCAL_AMFLAGS =-I m4

clean:
	rm -f *.o *.lo *~
