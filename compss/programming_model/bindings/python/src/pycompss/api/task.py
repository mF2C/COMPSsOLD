#
#  Copyright 2012-2017 Barcelona Supercomputing Center (www.bsc.es)
#
#  Licensed under the Apache License, Version 2.1 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.1
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
"""
@author: etejedor
@author: fconejer
@author: srodrig1

PyCOMPSs API - Task
===================
    This file contains the class task, needed for the task definition and the
    reveal_objects function.
"""
import inspect
import os
import logging
import pycompss.runtime.binding as binding
from pycompss.runtime.core_element import CE
from pycompss.util.serializer import serialize_objects, deserialize_from_file, deserialize_from_string
from pycompss.util.interactiveHelpers import updateTasksCodeFile
from pycompss.util.location import i_am_at_master
from functools import wraps


# Tracing Events and Codes -> Should be equal to Tracer.java definitions
SYNC_EVENTS = 8000666
TASK_EVENTS = 8000010
TASK_EXECUTION = 120
SERIALIZATION = 121


logger = logging.getLogger('pycompss.api.task')
#logger = logging.getLogger()   # for jupyter logging
#logger.setLevel(logging.DEBUG)


class task(object):

    def __init__(self, *args, **kwargs):
        """
        If there are decorator arguments, the function to be decorated is
        not passed to the constructor!
        """
        logger.debug("Init @task decorator...")

        # Defaults
        self.args = args          # Not used
        self.kwargs = kwargs      # The only ones actually used: (decorators)
        self.is_instance = False

        # Reserved PyCOMPSs keywords and default values
        if 'isModifier' not in self.kwargs:
            self.kwargs['isModifier'] = True
        if 'returns' not in self.kwargs:
            self.kwargs['returns'] = False
        if 'priority' not in self.kwargs:
            self.kwargs['priority'] = False
        if 'isReplicated' not in self.kwargs:
            self.kwargs['isReplicated'] = False
        if 'isDistributed' not in self.kwargs:
            self.kwargs['isDistributed'] = False

        # Pre-process decorator arguments
        from pycompss.api.parameter import Parameter
        from pycompss.api.parameter import TYPE
        from pycompss.api.parameter import DIRECTION
        import copy

        if i_am_at_master():
            for arg_name in self.kwargs.keys():
                if arg_name not in ['isModifier', 'returns', 'priority', 'isReplicated', 'isDistributed']:
                    # Prevent p.value from being overwritten later by ensuring
                    # each Parameter is a separate object
                    p = self.kwargs[arg_name]
                    pcopy = copy.copy(p)  # shallow copy
                    self.kwargs[arg_name] = pcopy

        if self.kwargs['isModifier']:
            direction = DIRECTION.INOUT
        else:
            direction = DIRECTION.IN

        # Add callee object parameter
        self.kwargs['self'] = Parameter(p_type=TYPE.OBJECT, p_direction=direction)

        # Check the return type:
        if self.kwargs['returns']:
            # This condition is interesting, because a user can write returns=list
            # However, a list has the attribute __len__ but raises an exception.
            # Since the user does not indicate the length, it will be managed as a single return.
            # When the user specifies the length, it is possible to manage the elements independently.
            if not hasattr(self.kwargs['returns'], '__len__') or type(self.kwargs['returns']) is type:
                # Simple return
                retType = getCOMPSsType(self.kwargs['returns'])
                self.kwargs['compss_retvalue'] = Parameter(p_type=retType, p_direction=DIRECTION.OUT)
            else:
                # Multi return
                i = 0
                returns = []
                for r in self.kwargs['returns']:  # This adds only one? - yep
                    retType = getCOMPSsType(r)
                    returns.append(Parameter(p_type=retType, p_direction=DIRECTION.OUT))
                self.kwargs['compss_retvalue'] = tuple(returns)

        logger.debug("Init @task decorator finished.")

    def __call__(self, f):
        """
        If there are decorator arguments, __call__() is only called
        once, as part of the decoration process! You can only give
        it a single argument, which is the function object.
        """
        # Assume it is an instance method if the first parameter of the
        # function is called 'self'
        # "I would rely on the convention that functions that will become
        # methods have a first argument named self, and other functions don't.
        # Fragile, but then, there's no really solid way."
        self.spec_args = inspect.getargspec(f)

        # Set default booleans
        self.is_instance = False
        self.is_classmethod = False   # not used currently in this scope, only when registering the task
        self.has_varargs = False
        self.has_keywords = False
        self.has_defaults = False
        self.has_return = False

        # Question: Will the first condition evaluate to false? spec_args will always be a named tuple, so
        # it will always return true if evaluated as a bool
        # Answer: The first condition evaluates if args exists (a list) and is not empty in the spec_args.
        # The second checks if the first argument in that list is 'self'. In case that the args list exists and its
        # first element is self, then the function is considered as an instance function (task defined within a class).
        if self.spec_args.args and self.spec_args.args[0] == 'self':
            self.is_instance = True

        # Check if contains *args
        if self.spec_args.varargs is not None:
            self.has_varargs = True

        # Check if contains **kwargs
        if self.spec_args.keywords is not None:
            self.has_keywords = True

        # Check if has default values
        if self.spec_args.defaults is not None:
            self.has_defaults = True

        # Check if the keyword returns has been specified by the user.
        if self.kwargs['returns']:
            self.has_return = True
            self.spec_args.args.append('compss_retvalue')

        # Get module (for invocation purposes in the worker)
        mod = inspect.getmodule(f)
        self.module = mod.__name__

        if(self.module == '__main__' or self.module == 'pycompss.runtime.launch'):
            # the module where the function is defined was run as __main__,
            # we need to find out the real module name

            # Get the real module name from our launch.py app_path global variable
            try:
                path = getattr(mod, "app_path")
            except AttributeError:
                # This exception is raised when the runtime is not running and the @task decorator is used.
                print("ERROR!!! The runtime has not been started yet. The function will be ignored.")
                print("Please, start the runtime before using task decorated functions in order to avoid this error.")
                print("Suggestion: Use the 'runcompss' command or the 'start' function from pycompss.interactive module depending on your needs.")
                return

            # Get the file name
            file_name = os.path.splitext(os.path.basename(path))[0]

            # Do any necessary preprocessing action before executing any code
            if file_name.startswith('InteractiveMode'):
                # If the file_name starts with 'InteractiveMode' means that the user is using PyCOMPSs
                # from jupyter-notebook. Convention between this file and interactive.py
                # In this case it is necessary to do a pre-processing step that consists
                # of putting all user code that may be executed in the worker on a file.
                # This file has to be visible for all workers.
                updateTasksCodeFile(f, path)
            else:
                # work as always
                pass

            # Get the module
            self.module = getModuleName(path, file_name)

        # The registration needs to be done only in the master node
        if i_am_at_master():
            registerTask(f, self.module, self.is_instance)

        # Modified variables until now that will be used later:
        #   - self.spec_args    : Function argspect (Named tuple)
        #                         e.g. ArgSpec(args=['a', 'b', 'compss_retvalue'], varargs=None, keywords=None, defaults=None)
        #   - self.is_instance  : Boolean - if the function is an instance (contains self in the spec_args)
        #   - self.has_varargs  : Boolean - if the function has *args
        #   - self.has_keywords : Boolean - if the function has **kwargs
        #   - self.has_defaults : Boolean - if the function has default values
        #   - self.has_return   : Boolean - if the function has return
        #   - self.module       : module as string (e.g. test.kmeans)
        #   - is_replicated     : Boolean - if the task is replicated
        #   - is_distributed    : Boolean - if the task is distributed
        # Other variables that will be used:
        #   - f                 : Decorated function
        #   - self.args         : Decorator args tuple (usually empty)
        #   - self.kwargs       : Decorator keywords dictionary.
        #                         e.g. {'priority': True, 'isModifier': True, 'returns': <type 'dict'>,
        #                               'self': <pycompss.api.parameter.Parameter instance at 0xXXXXXXXXX>,
        #                               'compss_retvalue': <pycompss.api.parameter.Parameter instance at 0xXXXXXXXX>}

        @wraps(f)
        def wrapped_f(*args, **kwargs):
            # args   - <Tuple>      - Contains the objects that the function has been called with (positional).
            # kwargs - <Dictionary> - Contains the named objects that the function has been called with.

            is_replicated = self.kwargs['isReplicated']
            is_distributed = self.kwargs['isDistributed']
            computingNodes = 1
            if 'computingNodes' in kwargs:
                # There is a @mpi decorator over task that overrides the default value of computing nodes
                computingNodes = kwargs['computingNodes']
                del kwargs['computingNodes']

            # Check if this call is nested using the launch_pycompss_module function from launch.py.
            is_nested = False
            istack = inspect.stack()
            for i_s in istack:
                if i_s[3] == 'launch_pycompss_module':
                    is_nested = True
                if i_s[3] == 'launch_pycompss_application':
                    is_nested = True

            if not i_am_at_master() and (not is_nested):
                # Task decorator worker body code.
                newTypes, newValues = workerCode(f,
                                                 self.is_instance,
                                                 self.has_varargs,
                                                 self.has_keywords,
                                                 self.has_defaults,
                                                 self.has_return,
                                                 args,
                                                 kwargs,
                                                 self.kwargs,
                                                 self.spec_args)
                return newTypes, newValues
            else:
                # Task decorator master body code.
                # Returns the future object that will be used instead of the
                # actual function return.
                fo = masterCode(f,
                                self.module,
                                self.is_instance,
                                self.has_varargs,
                                self.has_keywords,
                                self.has_defaults,
                                self.has_return,
                                is_replicated,
                                is_distributed,
                                computingNodes,
                                args,
                                self.args,
                                kwargs,
                                self.kwargs,
                                self.spec_args)
                return fo
        return wrapped_f


###############################################################################
##################### TASK DECORATOR FUNCTIONS ################################
###############################################################################


def getModuleName(path, file_name):
    """
    Get the module name considering its path and filename.
    Example: runcompss -d src/kmeans.py
             path = "test/kmeans.py"
             file_name = "kmeans" (without py extension)
             return mod_name = "test.kmeans"
    :param path: relative path until the file.py from where the runcompss has been executed
    :param file_name: python file to be executed name (without the py extension)
    :return: the module name
    """
    dirs = path.split(os.path.sep)
    mod_name = file_name
    i = len(dirs) - 1
    while i > 0:
        new_l = len(path) - (len(dirs[i]) + 1)
        path = path[0:new_l]
        if "__init__.py" in os.listdir(path):
            # directory is a package
            i -= 1
            mod_name = dirs[i] + '.' + mod_name
        else:
            break
    return mod_name


def registerTask(f, module, is_instance):
    """
    This function is used to register the task in the runtime.
    This registration must be done only once on the task decorator initialization.
    :param f: Function to be registered
    :param module: Module that the function belongs to.
    """
    # Look for the decorator that has to do the registration
    # Since the __init__ of the decorators is independent, there is no way to pass information through them.
    # However, the __call__ method of the decorators can be used. The way that they are called is from bottom
    # to top. So, the first one to call its __call__ method will always be @task.
    # Consequently, the @task decorator __call__ method can detect the top decorator and pass a hint to order
    # that decorator that has to do the registration (not the others).
    gotFuncCode = False
    func = f
    while not gotFuncCode:
        try:
            funcCode = inspect.getsourcelines(func)
            gotFuncCode = True
        except IOError:
            # There is one or more decorators below the @task --> undecorate until possible to get the func code.
            # Example of this case: test 19: @timeit decorator below the @task decorator.
            func = func.__wrapped__
    topDecorator = getTopDecorator(funcCode)
    logger.debug("[@TASK] Top decorator of function %s in module %s: %s" % (f.__name__, module, str(topDecorator)))
    f.__who_registers__ = topDecorator
    # Include the registering info related to @task
    ins = inspect.getouterframes(inspect.currentframe())
    class_name = ins[2][3]  # I know that this is ugly, but I see no other way to get the class name
    is_classmethod = class_name != '<module>' # I know that this is ugly, but I see no other way to check if it is a classs method.
    if is_instance or is_classmethod:
        ce_signature = module + "." + class_name + '.' + f.__name__
        implTypeArgs = [module + "." + class_name, f.__name__]
    else:
        ce_signature = module + "." + f.__name__
        implTypeArgs = [module, f.__name__]
    implSignature = ce_signature
    implConstraints = {}
    implType = "METHOD"
    coreElement = CE(ce_signature,
                     implSignature,
                     implConstraints,
                     implType,
                     implTypeArgs)
    f.__to_register__ = coreElement
    # Do the task register if I am the top decorator
    if f.__who_registers__ == __name__:
        logger.debug("[@TASK] I have to do the register of function %s in module %s" % (f.__name__, module))
        logger.debug("[@TASK] %s" % str(f.__to_register__))
        binding.register_ce(coreElement)


def workerCode(f, is_instance, has_varargs, has_keywords, has_defaults, has_return,
               args, kwargs, self_kwargs, self_spec_args):
    """ Task decorator body executed in the workers.
    Its main function is to execute to execute the function decorated as task.
    Prior to the execution, the worker needs to retrieve the necessary parameters.
    These parameters will be used for the invocation of the function.
    When the function has been executed, stores in a file the result and finishes the worker execution.
    Then, the runtime grabs this files and does all management among tasks that involve them.
    :param f: <Function> - Function to execute
    :param is_instance: <Boolean> - If the function is an instance function.
    :param has_varargs: <Boolean> - If the function has *args
    :param has_keywords: <Boolean> - If the function has **kwargs
    :param has_defaults: <Boolean> - If the function has default parameter values
    :param has_return: <Boolean> - If the function has return
    :param args: <Tuple> - Contains the objects that the function has been called with (positional).
    :param kwargs: <Dictionary> - Contains the named objects that the function has been called with.
    :param self_kwargs: <Dictionary> - Decorator keywords dictionary.
    :param self_spec_args: <Named Tuple> - Function argspect
    :return: Two lists: newTypes and newValues.
    """
    # Retrieve internal parameters from worker.py.
    tracing = kwargs.get('compss_tracing')
    process_name = kwargs.get('compss_process_name')

    # types = kwargs['compss_types']
    # values = args
    newTypes = []
    newValues = []

    if tracing:
        import pyextrae
        pyextrae.eventandcounters(TASK_EVENTS, 0)
        pyextrae.eventandcounters(TASK_EVENTS, SERIALIZATION)

    spec_args = self_spec_args.args
    toadd = []

    # Check if there is *arg parameter in the task
    if has_varargs:
        if binding.aargs_as_tuple:
            # If the *args are expected to be managed as a tuple:
            toadd.append(self_spec_args.varargs)
        else:
            # If the *args are expected to be managed as individual elements:
            num_aargs = len(args) - len(spec_args)
            if has_keywords:
                num_aargs -= 1
            for i in range(num_aargs):
                toadd.append('*' + self_spec_args.varargs + str(i))

    # Check if there is **kwarg parameters in the task
    if has_keywords:
        toadd.append(self_spec_args.keywords)

    returns = self_kwargs['returns']
    if has_return:
        spec_args = spec_args[:-1] + toadd + [spec_args[-1]]
    else:
        spec_args = spec_args + toadd

    # Discover hidden objects passed as files
    real_values, to_serialize = reveal_objects(args,
                                               spec_args,
                                               self_kwargs,
                                               kwargs['compss_types'],
                                               process_name,
                                               returns)

    if binding.aargs_as_tuple:
        # Check if there is *arg parameter in the task, so the last element (*arg tuple) has to be flattened
        if has_varargs:
            if has_keywords:
                real_values = real_values[:-2] + list(real_values[-2]) + [real_values[-1]]
            else:
                real_values = real_values[:-1] + list(real_values[-1])
    else:
        pass
    kargs = {}
    # Check if there is **kwarg parameter in the task, so the last element (kwarg dict) has to be flattened
    if has_keywords:
        kargs = real_values[-1]         # kwargs dict
        real_values = real_values[:-1]  # remove kwargs from real_values

    if tracing:
        pyextrae.eventandcounters(TASK_EVENTS, 0)
        pyextrae.eventandcounters(TASK_EVENTS, TASK_EXECUTION)

    ret = f(*real_values, **kargs)  # Real call to f function

    if tracing:
        pyextrae.eventandcounters(TASK_EVENTS, 0)
        pyextrae.eventandcounters(TASK_EVENTS, SERIALIZATION)

    # this will contain the same as to_serialize but we will store the whole
    # file identifier string instead of simply the file_name
    _output_objects = []

    if returns:
        # If there is multireturn then serialize each one on a different file
        # Multireturn example: a,b,c = fun() , where fun() has a return x,y,z
        if isinstance(returns, list) or isinstance(returns, tuple):
            num_ret = len(returns)
            total_rets = len(args) - num_ret
            rets = args[total_rets:]
            i = 0
            for ret_filename in rets:
                _output_objects.append((ret[i], ret_filename))
                ret_filename = ret_filename.split(':')[-1]
                to_serialize.append((ret[i], ret_filename))
                i += 1
        else:  # simple return
            ret_filename = args[-1]
            _output_objects.append((ret, ret_filename))
            ret_filename = ret_filename.split(':')[-1]
            to_serialize.append((ret, ret_filename))

    # Check if the values and types have changed after the task execution:
    # I.e.- an object that has been made persistent within the task may be detected here,
    # and the type change done within the outputTypes list.
    newTypes, newValues, to_serialize = checkValueChanges(kwargs['compss_types'],
                                                          list(args),
                                                          to_serialize)
    if len(to_serialize) > 0:
        serialize_objects(to_serialize)

    return newTypes, newValues


def masterCode(f, self_module, is_instance, has_varargs, has_keywords, has_defaults, has_return,
               is_replicated, is_distributed, num_nodes,
               args, self_args, kwargs, self_kwargs, self_spec_args):
    """
    Task decorator body executed in the master
    :param f: <Function> - Function to execute
    :param self_module: <String> - Function module
    :param is_instance: <Boolean> - If the function belongs to an instance
    :param has_varargs: <Boolean> - If the function has *args
    :param has_keywords: <Boolean> - If the function has **kwargs
    :param has_defaults: <Boolean> - If the function has default values
    :param has_return: <Boolean> - If the function has return
    :param is_replicated: <Boolean> - If the function is replicated
    :param is_distributed: <Boolean> - If the function is distributed
    :param num_nodes: <Integer> - Number of computing nodes
    :param args: <Tuple> - Contains the objects that the function has been called with (positional).
    :param self_args: <Tuple> - Decorator args (usually empty)
    :param kwargs: <Dictionary> - Contains the named objects that the function has been called with.
    :param self_kwargs: <Dictionary> - Decorator keywords dictionary.
    :param self_spec_args: <Named Tuple> - Function argspect
    :return: Future object that fakes the real return of the task (for its delegated execution)
    """
    from pycompss.runtime.binding import process_task
    from pycompss.runtime.binding import Function_Type

    # Check the type of the function called.
    # inspect.ismethod(f) does not work here,
    # for methods python hasn't wrapped the function as a method yet
    # Everything is still a function here, can't distinguish yet
    # with inspect.ismethod or isfunction
    ftype = Function_Type.FUNCTION
    class_name = ''
    if is_instance:
        ftype = Function_Type.INSTANCE_METHOD
        class_name = type(args[0]).__name__
    elif args and inspect.isclass(args[0]):
        for n, _ in inspect.getmembers(args[0], inspect.ismethod):
            if n == f.__name__:
                ftype = Function_Type.CLASS_METHOD
                class_name = args[0].__name__

    # Build the arguments list
    # Be very careful with parameter position.
    # The included are sorted by position. The rest may not.

    # Check how many parameters are defined in the function
    num_params = len(self_spec_args.args)
    if has_return:
        num_params -= 1

    # Check if the user has defined default values and include them
    argsList = []
    if has_defaults:
        # There are default parameters
        # Get the variable names and values that have been defined by default (get_default_args(f)).
        # default_params will have a list of pairs of the form (argument, default_value)
        # Default values have to be always defined after undefined value parameters.
        default_params = get_default_args(f)
        argsList = list(args)  # Given values
        # Default parameter addition
        for p in self_spec_args.args[len(args):num_params]:
            if p in kwargs:
                argsList.append(kwargs[p])
                kwargs.pop(p)
            else:
                for dp in default_params:
                    if p in dp[0]:
                        argsList.append(dp[1])
        args = tuple(argsList)

    # List of parameter names
    vals_names = list(self_spec_args.args[:num_params])
    # List of values of each parameter
    vals = list(args[:num_params])  # first values of args are the parameters

    # Check if there are *args or **kwargs
    args_names = []
    args_vals = []
    if has_varargs:   # *args
        aargs = '*' + self_spec_args.varargs
        if binding.aargs_as_tuple:
            # If the *args are expected to be managed as a tuple:
            args_names.append(aargs)  # Name used for the *args
            args_vals.append(args[num_params:])  # last values will compose the *args parameter
        else:
            # If the *args are expected to be managed as individual elements:
            pos = 0
            for i in range(len(args[num_params:])):
                args_names.append(aargs + str(pos))  # Name used for the *args
                pos += 1
            args_vals = args_vals + list(args[num_params:])
    if has_keywords:  # **kwargs
        aakwargs = '**' + self_spec_args.keywords  # Name used for the **kwargs
        args_names.append(aakwargs)
        # Check if some of the **kwargs are used as vals
        if len(vals_names) > len(vals):
            for i in range(len(vals), len(vals_names)):
                vals.append(kwargs[vals_names[i]])
                kwargs.pop(vals_names[i])
        # The **kwargs dictionary is considered as a single dictionary object.
        args_vals.append(kwargs)

    # Build the final list of parameter names
    spec_args = vals_names + args_names
    if has_return: # 'compss_retvalue' in self_spec_args.args:
        spec_args += ['compss_retvalue']
    # Build the final list of values for each parameter
    values = tuple(vals + args_vals)

    fo = process_task(f, self_module, class_name, ftype, has_return, spec_args, values, kwargs, self_kwargs, num_nodes,
                      is_replicated, is_distributed)
    # Starts the asynchronous creation of the task.
    # First calling the PyCOMPSs library and then C library (bindings-commons).
    return fo


###############################################################################
######################## AUXILIARY FUNCTIONS ##################################
###############################################################################


def getTopDecorator(code):
    """
    Retrieves the decorator which is on top of the current task decorators stack.
    :param code: Tuple which contains the task code to analyse and the number of lines of the code.
    :return: the decorator name in the form "pycompss.api.__name__"
    """
    # Code has two fields:
    # code[0] = the entire function code.
    # code[1] = the number of lines of the function code.
    funcCode = code[0]
    decoratorKeys = ("implement", "constraint", "decaf", "mpi", "ompss", "binary", "opencl", "task")
    decorators = [l for l in funcCode if l.strip().startswith('@')]  # Could be improved if it stops when the first line without @ is found.
                                                                     # but we have to be care if a decorator is commented (# before @)
                                                                     # The strip is due to the spaces that appear before functions definitions,
                                                                     # such as class methods.
    for dk in decoratorKeys:
        for d in decorators:
            if dk in d:
                return "pycompss.api." + dk.lower()  # each decorator __name__


def checkValueChanges(types, values, to_serialize):
    """
    Check if the input values have changed and adapt its types accordingly.
    Considers also changes that may affect to the to_serialize list.
    Note: This function can also return the real_to_serialize list, which contains the objects that should be
          serialized after checking the changes. For example, if a return is a simple type (int), it can be considered
          within the newTypes and newValues, poped from the to_serialize list, and returned on the task return pipe.
          However, the runtime does not support getting values from the return pipe. For this reason, we continue using
          the to_serialize list to serialize the return object into the return file. Consequently, the
          real_to_serialize variable is not currently used, but should be considered when the runtime provides support
          for returning simple objects through the pipe.
    Warning: Due to the runtime does not support gathering values from the output pipe at worker, all values will be
             set to null but the PSCOs that may have changed.
    :param types: List of types of the values list
    :param values: List of values used as task input
    :param to_serialize: List of objects to be serialized
    :return: Three lists, the new types, new values and new to_serialize list.
    """
    assert len(types) == len(values), 'Inconsistent state: type-value length mismatch.'
    from pycompss.api.parameter import TYPE
    '''
    real_to_serialize=[]
    # Analise all to_serialize objects
    for ts in to_serialize:
        # ts[0] == real object to serialize
        # ts[1] == file path where to serialize
        pos = 0
        changed = False
        for i in values:
            if isinstance(i, str) and ts[1] in i:
                values[pos] = ts[0]  # Include the real value within the values list
                # Update the new type
                changed = True
            pos += 1
        if not changed:
            real_to_serialize.append(ts)
    '''
    # Update the existing PSCOS with their id.
    for i in range(len(types)):
        if types[i] == TYPE.EXTERNAL_PSCO:
            values[i] = values[i].getID()
    real_to_serialize=[]
    # Analise only PSCOS from to_serialize objects list
    for ts in to_serialize:
        # ts[0] == real object to serialize
        # ts[1] == file path where to serialize
        pos = 0
        changed = False
        for i in values:
            if isinstance(i, str) and getCOMPSsType(ts[0]) == TYPE.EXTERNAL_PSCO and ts[1] in i:
                values[pos] = ts[0].getID()  # Include the PSCO id in the values list
                types[pos] = TYPE.EXTERNAL_PSCO
                changed = True
            pos += 1
        if not changed:
            real_to_serialize.append(ts)
    # Put all values that do not match the EXTERNAL_PSCO type to null
    for i in range(len(types)):
        if not types[i] == TYPE.EXTERNAL_PSCO:
            values[i] = 'null'
    return types, values, real_to_serialize


def getCOMPSsType(value):
    """
    Retrieve the value type mapped to COMPSs types.
    :param value: Value to analyse
    :return: The Type of the value
    """
    from pycompss.api.parameter import TYPE
    if type(value) is bool:
        return TYPE.BOOLEAN
    elif type(value) is str and len(value) == 1:
        return TYPE.CHAR           # Char does not exist as char. Only for strings of length 1.
    elif type(value) is str and len(value) > 1:
        return TYPE.STRING
    #elif type(value) is byte:     # byte does not exist in python (instead bytes is an str alias)
    #    return TYPE.BYTE
    # elif type(value) is short:   # short does not exist in python... they are integers.
    #    return TYPE.SHORT
    elif type(value) is int:
        return TYPE.INT
    elif type(value) is long:
        return TYPE.LONG
    elif type(value) is float:
        return TYPE.DOUBLE
    # elif type(value) is double:  # In python, floats are doubles.
    #     return TYPE.DOUBLE
    elif type(value) is str:
        return TYPE.STRING
    # elif type(value) is :       # Unavailable  # The runtime does not support python objects
    #     return TYPE.OBJECT
    # elif type(value) is :       # Unavailable  # PSCOs not persisted will be handled as objects (as files)
    #     return TYPE.PSCO

    #TODO: I find that it makes no sense to identify PSCOs this way
    # Why do not we simply check if the object of a subclass of the
    # storage_object? 
    elif 'getID' in dir(value):
        try:
            if value.getID() is not None:  # the 'getID' + id == criteria for persistent object
                return TYPE.EXTERNAL_PSCO
        except TypeError:
            # A PSCO class has been used to check its type (when checking the return).
            # Since we still don't know if it is going to be persistend inside, we assume that it is not.
            # It will be checked later on the worker side when the task finishes.
            return TYPE.FILE
    else:
        # Default type
        return TYPE.FILE


def get_default_args(f):
    """
    Returns a dictionary of arg_name:default_values for the input function
    @param f: Function to inspect for default parameters.
    """
    a = inspect.getargspec(f)
    num_params = len(a.args) - len(a.defaults)
    return zip(a.args[num_params:], a.defaults)


def reveal_objects(values,
                   spec_args, deco_kwargs, compss_types,
                   process_name, returns):
    """
    Function that goes through all parameters in order to
    find and open the files.
    :param values: <List> - The value of each parameter.
    :param spec_args: <List> - Specific arguments.
    :param deco_kwargs: <List> - The decoratos.
    :param compss_types: <List> - The types of the values.
    :param process_name: <String> - Process name (id).
    :param returns: If the function returns a value. Type = Boolean.
    :return: a list with the real values
    """
    from pycompss.api.parameter import Parameter
    from pycompss.api.parameter import TYPE
    from pycompss.api.parameter import DIRECTION

    num_pars = len(spec_args)
    real_values = []
    to_serialize = []

    if returns:
        num_pars -= 1    # return value must not be passed to the function call

    for i in range(num_pars):
        spec_arg = spec_args[i]
        compss_type = compss_types[i]
        value = values[i]
        if i == 0:
            if spec_arg == 'self':  # callee object
                if deco_kwargs['isModifier']:
                    d = DIRECTION.INOUT
                else:
                    d = DIRECTION.IN
                deco_kwargs[spec_arg] = Parameter(p_type=TYPE.OBJECT, p_direction=d)
            elif inspect.isclass(value):  # class (it's a class method)
                real_values.append(value)
                continue

        p = deco_kwargs.get(spec_arg)
        if p is None:  # decoration not present, using default
            p = Parameter()

        import time
        def elapsed(start):
            return time.time() - start

        if compss_type == TYPE.FILE and p.type != TYPE.FILE:
            # Getting ids and file names from passed files and objects patern is "originalDataID:destinationDataID;flagToPreserveOriginalData:flagToWrite:PathToFile"
            # forig, fdest, preserve, write_final, fname = value.split(':')
            complete_fname = value.split(':')
            if len(complete_fname) > 1:
                # In NIO we get more information
                forig = complete_fname[0]
                fdest = complete_fname[1]
                preserve = complete_fname[2]
                write_final = complete_fname[3]
                fname = complete_fname[4]
                preserve, write_final = list(map(lambda x: x == "true", [preserve, write_final]))
                suffix_name = forig
            else:
                fname = complete_fname[0]

            value = fname
            # For COMPSs it is a file, but it is actually a Python object
            logger.debug("Processing a hidden object in parameter %d", i)
            obj = deserialize_from_file(value)
            real_values.append(obj)
            if p.direction != DIRECTION.IN:
                to_serialize.append((obj, value))
        else:
            print('compss_type' + str(compss_type)+ ' type'+ str(p.type))
            if compss_type == TYPE.FILE:
                # forig, fdest, preserve, write_final, fname = value.split(':')
                complete_fname = value.split(':')
                if len(complete_fname) > 1:
                    # In NIO we get more information
                    forig = complete_fname[0]
                    fdest = complete_fname[1]
                    preserve = complete_fname[2]
                    write_final = complete_fname[3]
                    fname = complete_fname[4]
                else:
                    # In GAT we only get the name
                    fname = complete_fname[0]
                value = fname
            real_values.append(value)
    return real_values, to_serialize
