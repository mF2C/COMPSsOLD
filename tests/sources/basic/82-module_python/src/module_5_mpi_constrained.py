#!/usr/bin/python

# -*- coding: utf-8 -*-

"""
PyCOMPSs Testbench
========================
"""

# Imports
from pycompss.api.task import task
from pycompss.api.mpi import mpi
from pycompss.api.constraint import constraint


@constraint(computingUnits="2")
@mpi(binary="date", runner="mpirun", computingNodes=2)
@task()
def myDateConstrained(dprefix, param):
    pass


def main():
    from pycompss.api.api import compss_barrier
    myDateConstrained("-d", "next monday")
    compss_barrier()
    print("Finished")


if __name__ == '__main__':
    main()
