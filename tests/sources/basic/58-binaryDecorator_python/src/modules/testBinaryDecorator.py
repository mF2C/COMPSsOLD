#!/usr/bin/python

# -*- coding: utf-8 -*-

"""
PyCOMPSs Testbench Tasks
========================
"""

# Imports
import unittest
import os

from pycompss.api.task import task
from pycompss.api.parameter import *
from pycompss.api.api import compss_barrier, compss_open, compss_wait_on
from pycompss.api.binary import binary
from pycompss.api.constraint import constraint


@binary(binary="date", workingDir="/tmp")
@task()
def myDate(dprefix, param):
    pass


@constraint(computingUnits="2")
@binary(binary="date", workingDir="/tmp")
@task()
def myDateConstrained(dprefix, param):
    pass


@constraint(computingUnits="$CUS")
@binary(binary="date", workingDir="/tmp")
@task()
def myDateConstrainedWithEnvVar(dprefix, param):
    pass


@binary(binary="sed", workingDir=".")
@task(file=FILE_IN)
def mySedIN(expression, file):
    pass


@binary(binary="sed", workingDir=".")
@task(returns=int, file=FILE_IN)
def mySedReturn(expression, file):
    pass


@binary(binary="./private.sh", workingDir=os.getcwd() + '/src/scripts/')
@task(returns=int)
def failedBinary(code):
    pass


@binary(binary="sed", workingDir=".")
@task(file=FILE_INOUT)
def mySedINOUT(flag, expression, file):
    pass


@binary(binary="grep", workingDir=".")
# @task(infile=Parameter(TYPE.FILE, DIRECTION.IN, STREAM.STDIN), result=Parameter(TYPE.FILE, DIRECTION.OUT, STREAM.STDOUT))
# @task(infile={Type:FILE_IN, Stream:STDIN}, result={Type:FILE_OUT, Stream:STDOUT})
@task(infile={Type: FILE_IN_STDIN}, result={Type: FILE_OUT_STDOUT})
def myGrepper(keyword, infile, result):
    pass


@binary(binary="ls")
@task(hide={Type: FILE_IN, Prefix: "--hide="}, sort={Type: IN, Prefix: "--sort="})
def myLs(flag, hide, sort):
    pass


@binary(binary="ls")
@task(hide={Type: FILE_IN, Prefix: "--hide="}, sort={Prefix: "--sort="})
def myLsWithoutType(flag, hide, sort):
    pass


class testBinaryDecorator(unittest.TestCase):

    def testFunctionalUsage(self):
        myDate("-d", "next friday")
        compss_barrier()

    def testFunctionalUsageWithConstraint(self):
        myDateConstrained("-d", "next monday")
        compss_barrier()

    def testFunctionalUsageWithEnvVarConstraint(self):
        myDateConstrainedWithEnvVar("-d", "next tuesday")
        compss_barrier()

    def testFileManagementIN(self):
        infile = "src/infile"
        mySedIN('s/Hi/HELLO/g', infile)
        compss_barrier()

    def testReturn(self):
        infile = "src/infile"
        ev = mySedReturn('s/Hi/HELLO/g', infile)
        ev = compss_wait_on(ev)
        self.assertEqual(ev, 0)

    def testFailedBinaryExitValue(self):
        ev = failedBinary(123)
        ev = compss_wait_on(ev)
        self.assertEqual(ev, 123)

    @unittest.skip("UNSUPPORTED WITH GAT")
    def testFileManagementINOUT(self):
        inoutfile = "src/inoutfile"
        mySedINOUT('-i', 's/Hi/HELLO/g', inoutfile)
        with compss_open(inoutfile, "r") as finout_r:
            content_r = finout_r.read()
        # Check if there are no Hi words, and instead there is HELLO
        if 'Hi' in content_r:
            self.fail("INOUT File failed.")

    def testFileManagement(self):
        infile = "src/infile"
        outfile = "src/grepoutfile"
        myGrepper("Hi", infile, outfile)
        compss_barrier()

    def testFilesAndPrefix(self):
        flag = '-l'
        infile = "src/infile"
        sort = "size"
        myLs(flag, infile, sort)
        compss_barrier()

    def testFilesAndPrefixWithoutType(self):
        flag = '-l'
        infile = "src/inoutfile"
        sort = "time"
        myLsWithoutType(flag, infile, sort)
        compss_barrier()
