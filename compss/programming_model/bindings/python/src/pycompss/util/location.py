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
PyCOMPSs Utils - Location
===================
    This file contains the methods to detect the origin of the call stack.
    Useful to detect if we are in the master or in the worker.
"""

import inspect


def i_am_at_master():
    """
    Determine if the execution is being performed in the master node

    # if 'pycompss/runtime/launch.py' in inspect.stack()[-1][1]: --> I am at master
    # if inspect.stack()[-2][3] == 'compss_main' --> I am at master
    :return: <Boolean> - True if we are in the master node.
    """
    return 'pycompss/runtime/launch.py' in inspect.stack()[-1][1]


def i_am_at_worker():
    """
    Determine if the execution is being performed in a worker node.

    # if (inspect.stack()[-2][3] == 'compss_worker' or
    #     inspect.stack()[-2][3] == 'compss_persistent_worker'): --> I am at worker
    :return: <Boolean> - True if we are in a worker node.
    """
    return inspect.stack()[-2][3] in ['compss_worker', 'compss_persistent_worker']


def i_am_within_scope():
    """
    Determine if the execution is being performed within the PyCOMPSs scope.
    :return:  <Boolean> - True if under scope. False on the contrary.
    """
    return i_am_at_master() or i_am_at_worker()
    # Old way: - Conflicts with dataClay
    # import sys
    # return sys.path[0].endswith('Bindings/python/2/pycompss/runtime') or \
    #        sys.path[0].endswith('Bindings/python/2/pycompss/worker') or \
    #        sys.path[0].endswith('Bindings/python/3/pycompss/runtime') or \
    #        sys.path[0].endswith('Bindings/python/3/pycompss/worker') or \
    #        sys.path[0] == ''
