#include <stdio.h>
#include <stdlib.h>
#include <limits.h>
#include <string.h>
#include <ctype.h>
#include "backend.h"
#include "semantic.h"
#include "backendlib.h"

static FILE *stubsFile = NULL;
static FILE *workerFile = NULL;
static FILE *includeFile = NULL;

static FILE *const_file = NULL;
static char includeName[PATH_MAX];

static char *c_types[] = {
  "int",		// boolean_dt
  "char",		// char_dt
  "unsigned char",	// byte_dt
  "short",		// short_dt
  "int",		// int_dt
  "long",		// long_dt
  "float",		// float_dt
  "double",		// double_dt
  "char *",		// string_dt
  "file",		// file_dt
  "void *",		// object_dt
  "void *",		// psco_dt
  "void *",		// external_psco_dt
  
  "char", 		// wchar_dt
  "char *", 		// wstring_dt
  "long long", 		// longlong_dt
  "void", 		// void_dt
  "void",		// any_dt
  "error"		// null_dt
};

static char *c_out_types[] = {
  "int",                  // boolean_dt
  "char",                 // char_dt
  "unsigned char",        // byte_dt
  "short",                // short_dt
  "int",                  // int_dt
  "long",                 // long_dt
  "float",                // float_dt
  "double",               // double_dt
  "char *",               // string_dt
  "file",                 // file_dt
  "void *",               // object_dt
  "void *",               // psco_dt
  "void *",               // external_psco_dt
  
  "char",                 // wchar_dt
  "char *",               // wstring_dt
  "long long",            // longlong_dt
  "void",                 // void_dt
  "void",                 // any_dt
  "error"                 // null_dt
};


void generate_prolog()
{
  char name[PATH_MAX];
  char *c;
  
  strncpy(name, get_filename_base(), PATH_MAX);
  strncat(name, "-stubs.cc", PATH_MAX);
  rename_if_clash(name);
  stubsFile = fopen(name, "w");
  if (stubsFile == NULL) {
    fprintf(stderr, "Error: Could not open %s for writing.\n", name);
    exit(1);
  }
  
  strncpy(name, get_filename_base(), PATH_MAX);
  strncat(name, "-executor.cc", PATH_MAX);
  rename_if_clash(name);
  workerFile = fopen(name, "w");
  if (workerFile == NULL) {
    fprintf(stderr, "Error: Could not open %s for writing.\n", name);
    exit(1);
  }
  
  strncpy(includeName, get_filename_base(), PATH_MAX);
  strncat(includeName, ".h", PATH_MAX);
  rename_if_clash(includeName);
  includeFile = fopen(includeName, "w");
  if (includeFile == NULL) {
    fprintf(stderr, "Error: Could not open %s for writing.\n", includeName);
    exit(1);
  }
  
  
  fprintf(stubsFile, "/* This file has been autogenerated from '%s'. */\n", get_filename());
  fprintf(stubsFile, "/* CHANGES TO THIS FILE WILL BE LOST */\n");
  fprintf(stubsFile, "\n");
  fprintf(stubsFile, "#include <stdio.h>\n");
  fprintf(stubsFile, "#include <stdlib.h>\n");
  fprintf(stubsFile, "#include <limits.h>\n");
  fprintf(stubsFile, "#include <string.h>\n");
  fprintf(stubsFile, "#include <fstream>\n");
  fprintf(stubsFile, "#include <jni.h>\n");
  fprintf(stubsFile, "#include <boost/archive/text_iarchive.hpp>\n");
  fprintf(stubsFile, "#include <boost/archive/text_oarchive.hpp>\n");
  fprintf(stubsFile, "#include <GS_compss.h>\n");
  fprintf(stubsFile, "#include <param_metadata.h>\n");
  fprintf(stubsFile, "#include \"%s\"\n", includeName);
  
  fprintf(stubsFile, "\n");
  fprintf(stubsFile, "using namespace std;\n");
  fprintf(stubsFile, "using namespace boost;\n");
  
  fprintf(stubsFile, "\n");
  
  fprintf(workerFile, "/* This file has been autogenerated from '%s'. */\n", get_filename());
  fprintf(workerFile, "/* CHANGES TO THIS FILE WILL BE LOST */\n");
  fprintf(workerFile, "\n");
  fprintf(workerFile, "#include <stdio.h>\n");
  fprintf(workerFile, "#include <stdlib.h>\n");
  fprintf(workerFile, "#include <limits.h>\n");
  fprintf(workerFile, "#include <string.h>\n");
  fprintf(workerFile, "#include <fstream>\n");
  fprintf(workerFile, "#include <boost/archive/text_iarchive.hpp>\n");
  fprintf(workerFile, "#include <boost/archive/text_oarchive.hpp>\n");
  
  fprintf(workerFile, "#include \"%s\"\n", includeName);
  fprintf(workerFile, "\n");
  fprintf(workerFile, "using namespace std;\n");
  fprintf(workerFile, "\n");
  fprintf(workerFile, "int execute(int argc, char **argv) {\n");
  fprintf(workerFile, "\n");
  // Args consistent with Runtime [0, NUM_INTERNAL_ARGS]: executable, tracing, taskId, workerDebug, storageConf, method_type, className, methodName, 
  //                                                      numSlaves, [slaves], numCus, hasTarget, returnType, numAppParams
  fprintf(workerFile, "\tprintf(\"\\n\");\n");
  fprintf(workerFile, "\tprintf(\"----------------- C WORKER -----------------\\n\");\n");
  fprintf(workerFile, "\tprintf(\"Total number of parameters: %%d\\n\", argc);\n");
  fprintf(workerFile, "\tif (argc < MIN_NUM_INTERNAL_ARGS) {\n");
  fprintf(workerFile, "\t\tprintf(\"ERROR: Incorrect number of COMPSs internal parameters\\n\");\n");
  fprintf(workerFile, "\t\tprintf(\"Aborting...\\n\");\n");
  fprintf(workerFile, "\t\treturn -1;\n");	
  fprintf(workerFile, "\t}\n");
  fprintf(workerFile, "\n");
  // Log args
  fprintf(workerFile, "\tprintf(\"Executable: %%s\\n\", argv[0]);\n");
  fprintf(workerFile, "\tprintf(\"Tracing: %%s\\n\", argv[1]);\n");
  fprintf(workerFile, "\tprintf(\"Task Id: %%s\\n\", argv[2]);\n");
  fprintf(workerFile, "\tprintf(\"Worker Debug: %%s\\n\", argv[3]);\n");
  fprintf(workerFile, "\tprintf(\"StorageConf: %%s\\n\", argv[4]);\n");
  fprintf(workerFile, "\tprintf(\"MethodType: %%s\\n\", argv[5]);\n");
  fprintf(workerFile, "\tprintf(\"ClassName: %%s\\n\", argv[6]);\n");
  fprintf(workerFile, "\tprintf(\"MethodName: %%s\\n\", argv[7]);\n");
  fprintf(workerFile, "\tprintf(\"NumSlaves: %%s\\n\", argv[8]);\n");
  fprintf(workerFile, "\tint numSlaves=atoi(argv[8]);\n");
  fprintf(workerFile, "\tfor(int i = 0; i < numSlaves; ++i) {\n");
  fprintf(workerFile, "\t\tprintf(\"Slave %%d has name %%s\\n\", i, argv[NUM_BASE_ARGS + i]);\n");
  fprintf(workerFile, "\t}\n");
  fprintf(workerFile, "\tint NUM_INTERNAL_ARGS=NUM_BASE_ARGS + numSlaves;\n");
  fprintf(workerFile, "\tprintf(\"NumComputingUnits: %%s\\n\", argv[NUM_INTERNAL_ARGS++]);\n");

  fprintf(workerFile, "\tprintf(\"HasTarget: %%s\\n\", argv[NUM_INTERNAL_ARGS++]);\n");
  fprintf(workerFile, "\tprintf(\"ReturnType: %%s\\n\", argv[NUM_INTERNAL_ARGS++]);\n");
  fprintf(workerFile, "\tprintf(\"Num App Params: %%s\\n\", argv[NUM_INTERNAL_ARGS++]);\n");

  fprintf(workerFile, "\tprintf(\"Application Arguments:\\n\");\n");
  fprintf(workerFile, "\tfor(int i = NUM_INTERNAL_ARGS; i < argc; i++)\n");
  fprintf(workerFile, "\t\tprintf(\"\\t%%s\\n\",argv[i]);\n");
  fprintf(workerFile, "\n");

  // Get OpName and OpCode
  fprintf(workerFile, "\tenum operationCode opCod;\n");
  fprintf(workerFile, "\tchar *opName;\n");
  fprintf(workerFile, "\topName = strdup(argv[METHOD_NAME_POS]);\n");
  fprintf(workerFile, "\tprintf(\"OpName: %%s\\n\", opName);\n");
  fprintf(workerFile, "\n");
  fprintf(workerFile, "\tfor(int i=0; i < N_OPS; i++) {\n");
  fprintf(workerFile, "\t\tif(strcmp(operationName[i], opName) == 0) {\n");
  fprintf(workerFile, "\t\t\topCod=(enum operationCode)i;\n");
  fprintf(workerFile, "\t\t\tbreak;\n");
  fprintf(workerFile, "\t\t}\n");
  fprintf(workerFile, "\t}\n");
  fprintf(workerFile, "\tprintf(\"OpCode: %%d\\n\", (int)opCod);\n");
  fprintf(workerFile, "\n");
 
  // Add end header logger
  fprintf(workerFile, "\tprintf(\"--------------------------------------------\\n\");\n");
  fprintf(workerFile, "\tprintf(\"\\n\");\n");

  // OpCode switch
  fprintf(workerFile, "\tint arg_offset = NUM_INTERNAL_ARGS;\n");
  fprintf(workerFile, "\tswitch(opCod)\n");
  fprintf(workerFile, "\t {\n");
 
  // Include file headers 
  fprintf(includeFile, "/* This file must be #included in the actual implementation file. */\n");
  fprintf(includeFile, "/* This file has been autogenerated from '%s'. */\n", get_filename());
  fprintf(includeFile, "/* CHANGES TO THIS FILE WILL BE LOST */\n");
  fprintf(includeFile, "\n");
  fprintf(includeFile, "#ifndef _GSS_");
  for (c = includeName; *c; c++) {
    if (isalnum(*c)) {
      fprintf(includeFile, "%c", toupper(*c));
    } else {
      fprintf(includeFile, "_");
    }
  }
  fprintf(includeFile, "\n");
  
  fprintf(includeFile, "#define _GSS_");
  for (c = includeName; *c; c++) {
    if (isalnum(*c)) {
      fprintf(includeFile, "%c", toupper(*c));
    } else {
      fprintf(includeFile, "_");
    }
  }
  fprintf(includeFile, "\n");
  fprintf(includeFile, "#include <GS_compss.h>\n");
  fprintf(includeFile, "#include <GS_templates.h>\n");
  fprintf(includeFile, "#include <param_metadata.h>\n");
  fprintf(includeFile, "\n");
  fprintf(includeFile, "typedef char* file;\n");
  fprintf(includeFile, "\n");
}


void generate_epilogue(void)
{
  char *c;
  // Close switch clause
  fprintf(workerFile, "\t}\n");
  fprintf(workerFile, "\n");
  // If this point is reached, no operation has been selected
  // Raise error for incorrect method execution
  fprintf(workerFile, "\tprintf(\"Incorrect Operation Code. Aborting...\\n\");\n");
  fprintf(workerFile, "\treturn -1;\n");
  fprintf(workerFile, "}\n");
  
  fprintf(includeFile, "\n");
  fprintf(includeFile, "#endif /* _GSS_");
  for (c = includeName; *c; c++) {
    if (isalnum(*c)) {
      fprintf(includeFile, "%c", toupper(*c));
    } else {
      fprintf(includeFile, "_");
    }
  }
  
  fprintf(includeFile, " */\n");
  
  fclose(stubsFile);
  fclose(workerFile);
  fclose(includeFile);
}

static void generate_enum(FILE *outFile, function *first_function)
{
  function *func;
  int is_first = 1;
  int n = 0;
  
  fprintf(outFile, "enum operationCode {");
  
  func = first_function;
  while (func != NULL) {
    if (is_first) {
      is_first = 0;
    } else {
      fprintf(outFile, ", ");
    }
    char *func_name = strdup(func->name);
    replace_char(func_name, ':', '_');
    fprintf(outFile, "%sOp", func_name);
    n++;
    func = func->next_function;
  }
  
  fprintf(outFile, "};\n");
  
  is_first = 1;
  
  fprintf(outFile, "static const char *operationName[] = {");
  
  func = first_function;
  while (func != NULL) {
    if (is_first) {
      is_first = 0;
    } else {
      fprintf(outFile, ", ");
    }
    fprintf(outFile, "\"%s\"", func->name);
    func = func->next_function;
  }
  
  fprintf(outFile, "};\n");

  // Add constants (according to COMPSs Runtime)
  fprintf(outFile, "static const int N_OPS=%d;\n", n);
  fprintf(outFile, "static const int NUM_BASE_ARGS = 9;\n");
  fprintf(outFile, "static const int MIN_NUM_INTERNAL_ARGS = 13;\n");
  fprintf(outFile, "static const int METHOD_NAME_POS = 7;\n");
  fprintf(outFile ,"\n");
}


static void generate_prototype(FILE *outFile, function *current_function)
{
  argument *current_argument;
  
  if (current_function->return_type != void_dt ) {
    fprintf(outFile, "%s %s(", current_function->return_typename, current_function->name);
  } else {
    fprintf(outFile, "%s %s(", c_types[current_function->return_type], current_function->name);
  }
  current_argument = current_function->first_argument;
  while (current_argument != NULL) {
    if (current_argument->dir == in_dir) {
      switch (current_argument->type) {
	case char_dt:
	case wchar_dt:
	case boolean_dt:
	case short_dt:
	case long_dt:
	case longlong_dt:
	case int_dt:
	case float_dt:
	case double_dt:
	  fprintf(outFile, "%s %s", c_out_types[current_argument->type], current_argument->name);
	  break;
	case object_dt:
	  fprintf(outFile, "%s *%s", current_argument->classname, current_argument->name);
	  break;
	case string_dt:
	case wstring_dt:
	  fprintf(outFile, "%s %s", c_out_types[current_argument->type], current_argument->name);
	  break;
	case file_dt:
	  fprintf(outFile, "%s %s", c_out_types[current_argument->type], current_argument->name);
	  break;
	case void_dt:
	case any_dt:
	case null_dt:
	default:;
      }
    } else {
      switch (current_argument->type) {
	case char_dt:
	case wchar_dt:
	case boolean_dt:
	case short_dt:
	case long_dt:
	case longlong_dt:
	case int_dt:
	case float_dt:
	case double_dt:
	  fprintf(outFile, "%s *%s", c_out_types[current_argument->type], current_argument->name);
	  break;
	case object_dt:
	  fprintf(outFile, "%s *%s", current_argument->classname, current_argument->name);
	  break;
	case string_dt:
	case wstring_dt:
	  fprintf(outFile, "%s *%s", c_out_types[current_argument->type], current_argument->name);
	  break;
	case file_dt:
	  fprintf(outFile, "%s %s", c_out_types[current_argument->type], current_argument->name);
	  break;
	case void_dt:
	case any_dt:
	case null_dt:
	default:;
      }
    }
    current_argument = current_argument->next_argument;
    if (current_argument != NULL) {
      fprintf(outFile, ", ");
    }
  }
  fprintf(outFile, ")");
}


static void generate_class_includes(FILE *outFile, function *current_function)
{
  argument *current_argument;
  
  current_argument = current_function->first_argument;
  while (current_argument != NULL) {
    if (current_argument->type == object_dt) {
      fprintf(outFile, "#include \"%s.h\";\n", current_argument->classname);
    }
    current_argument = current_argument->next_argument;
  }
}

static void generate_parameter_buffers(FILE *outFile, function *func)
{
  int k = 0;
  if (( func->classname != NULL ) && (func->access_static == 0)) k = k + 3;
  if ( func->return_type != void_dt ) k = k + 3;
  
  fprintf(outFile, "\t void *arrayObjs[%d];\n", k + func->argument_count * 3);
  fprintf(outFile, "\t int found;\n");
  fprintf(outFile, "\n");
}

static void generate_parameter_marshalling(FILE *outFile, function *func)
{
  argument *arg;
  int j = 0;
  int i = 0;
  
  if (( func->classname != NULL ) && (func->access_static == 0)){
    i = j*3;
    fprintf(outFile, "\t char *this_filename;\n");
    fprintf(outFile, "\t found = GS_register(this, (datatype)%d, inout_dir, \"%s\", this_filename);\n", object_dt, func->classname);
    fprintf(outFile, "\t if (!found) {\n");
    fprintf(outFile, "\t\t ofstream this_ofs(this_filename, std::ofstream::trunc);\n");
    fprintf(outFile, "\t\t archive::text_oarchive this_oa(this_ofs);\n");
    fprintf(outFile, "\t\t this_oa << *this;\n");
    fprintf(outFile, "\t\t this_ofs.flush();\n");
    fprintf(outFile, "\t\t this_ofs.close();\n");
    fprintf(outFile, "\t }\n");
    
    fprintf(outFile, "\t arrayObjs[%d] = &this_filename;\n", i);
    
    fprintf(outFile, "\t int param%d = %d;\n", i+1, file_dt);
    fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+1, i+1);
    
    fprintf(outFile, "\t int param%d = %d;\n", i+2, inout_dir);
    fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+2, i+2);
    
    fprintf(outFile, "\n");
    j++;
  }
  
  if ( func->return_type != void_dt ){
    i = j*3;
    fprintf(outFile, "\t %s return_object;\n", func->return_typename);
    fprintf(outFile, "\t char *return_filename;\n");
    fprintf(outFile, "\t found = GS_register(&return_object, (datatype)%d, null_dir, \"%s\", return_filename);\n", object_dt, func->return_typename);
    fprintf(outFile, "\t if (!found) {\n");
    fprintf(outFile, "\t\t ofstream return_ofs(return_filename, std::ofstream::trunc);\n");
    fprintf(outFile, "\t\t archive::text_oarchive return_oa(return_ofs);\n");
    fprintf(outFile, "\t\t return_oa << return_object;\n");
    fprintf(outFile, "\t\t return_ofs.flush();\n");
    fprintf(outFile, "\t\t return_ofs.close();\n");
    fprintf(outFile, "\t }\n");
    
    fprintf(outFile, "\t arrayObjs[%d] = &return_filename;\n", i);
    
    fprintf(outFile, "\t int param%d = %d;\n", i+1, file_dt);
    fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+1, i+1);
    
    fprintf(outFile, "\t int param%d = %d;\n", i+2, inout_dir);
    fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+2, i+2);
    
    fprintf(outFile, "\n");
    j++;
  }
  
  
  arg = func->first_argument;
  while (arg != NULL) {
    i = j*3;
    
    if (arg->dir == out_dir || arg->dir == inout_dir) {
      switch (arg->type) {
	case char_dt:
	case wchar_dt:
	case boolean_dt:
	case short_dt:
	case long_dt:
	case longlong_dt:
	case int_dt:
	case float_dt:
	case double_dt:
	case object_dt:
	  fprintf(outFile, "\t char *%s_filename;\n", arg->name);
	  fprintf(outFile, "\t found = GS_register(%s, (datatype)%d, (direction)%d, \"%s\", %s_filename);\n", arg->name, arg->type, arg->dir, arg->classname, arg->name);
	  fprintf(outFile, "\t if (!found) {\n");
	  fprintf(outFile, "\t\t ofstream %s_ofs(%s_filename, std::ofstream::trunc);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t archive::text_oarchive %s_oa(%s_ofs);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t %s_oa << *%s;\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t %s_ofs.flush();\n", arg->name);
	  fprintf(outFile, "\t\t %s_ofs.close();\n", arg->name);
	  fprintf(outFile, "\t }\n");
	  fprintf(outFile, "\t arrayObjs[%d] = &%s_filename;\n", i, arg->name);
	  fprintf(outFile, "\t int param%d = %d;\n", i+1, file_dt);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+1, i+1);
	  fprintf(outFile, "\t int param%d = %d;\n", i+2, inout_dir);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+2, i+2);
	  break;
	case string_dt:
	case wstring_dt:
	  fprintf(outFile, "\t char *%s_filename;\n", arg->name);
	  fprintf(outFile, "\t found = GS_register(%s, (datatype)%d, (direction)%d, \"%s\", %s_filename);\n", arg->name, arg->dir, arg->type, arg->classname, arg->name);
	  fprintf(outFile, "\t if (!found) {\n");
	  fprintf(outFile, "\t\t ofstream %s_ofs(%s_filename, std::ofstream::trunc);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t archive::text_oarchive %s_oa(%s_ofs);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t string %s_out_string (*%s);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t %s_oa << %s_out_string;\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t %s_ofs.flush();\n", arg->name);
	  fprintf(outFile, "\t\t %s_ofs.close();\n", arg->name);
	  fprintf(outFile, "\t }\n");
	  fprintf(outFile, "\t arrayObjs[%d] = &%s_filename;\n", i, arg->name);
	  fprintf(outFile, "\t int param%d = %d;\n", i+1, file_dt);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+1, i+1);
	  fprintf(outFile, "\t int param%d = %d;\n", i+2, inout_dir);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+2, i+2);
	  break;
	case file_dt:
	  //fprintf(outFile, "\t GS_register(%s, (datatype)%d, (direction)%d, \"%s\", *%s);\n", arg->name, arg->type, arg->dir, arg->classname, arg->name);
	  fprintf(outFile, "\t arrayObjs[%d] = &%s;\n", i, arg->name);
	  fprintf(outFile, "\t int param%d = %d;\n", i+1, arg->type);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+1, i+1);
	  fprintf(outFile, "\t int param%d = %d;\n", i+2, arg->dir);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+2, i+2);
	  break;
	case void_dt:
	case any_dt:
	case null_dt:
	default:;
      }
    } else {
      switch (arg->type) {
	case object_dt:
	  fprintf(outFile, "\t char *%s_filename;\n", arg->name);
	  fprintf(outFile, "\t found = GS_register(%s, (datatype)%d, (direction)%d, \"%s\", %s_filename);\n", arg->name, arg->type, arg->dir, arg->classname, arg->name);
	  fprintf(outFile, "\t if (!found) {\n");
	  fprintf(outFile, "\t\t ofstream %s_ofs(%s_filename, std::ofstream::trunc);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t archive::text_oarchive %s_oa(%s_ofs);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t %s_oa << %s;\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t %s_ofs.flush();\n", arg->name);
	  fprintf(outFile, "\t\t %s_ofs.close();\n", arg->name);
	  fprintf(outFile, "\t }\n");
	  fprintf(outFile, "\t arrayObjs[%d] = &%s_filename;\n", i, arg->name);
	  fprintf(outFile, "\t int param%d = %d;\n", i+1, file_dt);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+1, i+1);
	  fprintf(outFile, "\t int param%d = %d;\n", i+2, arg->dir);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+2, i+2);
	  break;
	default:
	  fprintf(outFile, "\t arrayObjs[%d] = &%s;\n", i, arg->name);
	  fprintf(outFile, "\t int param%d = %d;\n", i+1, arg->type);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+1, i+1);
	  fprintf(outFile, "\t int param%d = %d;\n", i+2, arg->dir);
	  fprintf(outFile, "\t arrayObjs[%d] = &param%d;\n", i+2, i+2);
	  break;
      }
    }
    
    fprintf(outFile, "\n");
    
    arg = arg->next_argument;
    j++;
  }
  
  fprintf(outFile, "\n");
}

static void generate_execute_call(FILE *outFile, function *func)
{
  char *appId = "0L";
  char *class_name = strdup("NULL");
  char *hasTarget = strdup("false");
  
  int arg_count = func->argument_count;
  
  if (( func->classname != NULL ) && (func->access_static == 0)) arg_count++;
  if ( func->return_type != void_dt ) arg_count++;
  
  fprintf(outFile, "\t char *method_name = strdup(\"%s\");\n", func->name);
  
  fprintf(outFile, "\t GS_ExecuteTask(0L, \"%s\", method_name, 0, %s, %d, (void**)arrayObjs);\n", class_name, hasTarget, arg_count);
  
  fprintf(outFile, "\n");
  
  if ( func->return_type != void_dt ){
    fprintf(outFile, "\n\t //Implicit synchronization\n");
    fprintf(outFile, "\t debug_printf(\"[   BINDING]  -  @%%s  -  Implicit synchronization of return value\\n\", method_name);\n");
    fprintf(outFile, "\t compss_wait_on(return_object);\n");
    fprintf(outFile, "\n\t return return_object;\n");
    fprintf(outFile, "\n");
  }
  
  fprintf(outFile, "\t free(method_name);\n");
  
}

static void generate_worker_case(FILE *outFile, function *func)
{
  argument *arg;
  int j = 0;
  int is_first_arg = 1;
  
  char *func_name = strdup(func->name);
  replace_char(func_name, ':', '_');
  fprintf(outFile, "\t case %sOp:\n", func_name);
  fprintf(outFile, "\t\t {\n");
  
  if (( func->classname != NULL ) && (func->access_static == 0)){
    fprintf(outFile, "\t\t\t %s this_%s;\n", func->classname, func->classname);
    fprintf(outFile, "\t\t\t \n");
    fprintf(outFile, "\t\t\t arg_offset += 1;\n");
    fprintf(outFile, "\t\t\t char *this_filename = strdup(argv[arg_offset]);\n");
    fprintf(outFile, "\t\t\t arg_offset += 1;\n");
    fprintf(outFile, "\t\t\t ifstream this_ifs(this_filename);\n");
    fprintf(outFile, "\t\t\t archive::text_iarchive this_ia(this_ifs);\n");
    fprintf(outFile, "\t\t\t this_ia >> this_%s;\n", func->classname);
    fprintf(outFile, "\t\t\t this_ifs.close();\n\n");
  }
  
  if ( func->return_type != void_dt ){
    fprintf(outFile, "\t\t\t %s return_object;\n", func->return_typename);
    fprintf(outFile, "\t\t\t \n");
    fprintf(outFile, "\t\t\t arg_offset += 1;\n");
    fprintf(outFile, "\t\t\t char *return_filename = strdup(argv[arg_offset]);\n");
    fprintf(outFile, "\t\t\t arg_offset += 1;\n");
    fprintf(outFile, "\t\t\t ifstream return_ifs(return_filename);\n");
    fprintf(outFile, "\t\t\t archive::text_iarchive return_ia(return_ifs);\n");
    fprintf(outFile, "\t\t\t return_ia >> return_object;\n");
    fprintf(outFile, "\t\t\t return_ifs.close();\n\n");
  }
  
  arg = func->first_argument;
  while (arg != NULL) {
    switch (arg->type) {
      case char_dt:
      case wchar_dt:
	fprintf(outFile, "\t\t\t char %s;\n", arg->name);
	break;
      case boolean_dt:
	fprintf(outFile, "\t\t\t int %s;\n", arg->name);
	break;
      case short_dt:
	fprintf(outFile, "\t\t\t short %s;\n", arg->name);
	break;
      case long_dt:
	fprintf(outFile, "\t\t\t long %s;\n", arg->name);
	break;
      case longlong_dt:
	fprintf(outFile, "\t\t\t long long %s;\n", arg->name);
	break;
      case int_dt:
	fprintf(outFile, "\t\t\t int %s;\n", arg->name);
	break;
      case float_dt:
	fprintf(outFile, "\t\t\t float %s;\n", arg->name);
	break;
      case double_dt:
	fprintf(outFile, "\t\t\t double %s;\n", arg->name);
	break;
      case file_dt:
	fprintf(outFile, "\t\t\t char *%s;\n", arg->name);
	break;
      case string_dt:
      case wstring_dt:
	fprintf(outFile, "\t\t\t char *%s;\n", arg->name);
	break;
      case object_dt:
	fprintf(outFile, "\t\t\t %s %s;\n", arg->classname, arg->name);
	break;
      case void_dt:
      case any_dt:
      case null_dt:
      default:
	;
    }
    arg = arg->next_argument;
  }
  fprintf(outFile, "\t\t\t \n");
  
  arg = func->first_argument;
  while (arg != NULL) { 
    if (arg->dir == in_dir) {
      // arg_offset -> type
      // arg_offset+1 -> stream
      // arg_offset+2 -> prefix
      // arg_offset+3 -> value
      
      switch (arg->type) {
	case char_dt:
	case wchar_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = argv[arg_offset][0];\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case boolean_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = argv[arg_offset]? 1 : 0;\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case short_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = atoi(argv[arg_offset]);\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case long_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = atol(argv[arg_offset]);\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case longlong_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = atoll(argv[arg_offset]);\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case int_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = atoi(argv[arg_offset]);\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case float_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = strtof(argv[arg_offset], NULL);\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case double_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = strtod(argv[arg_offset], NULL);\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case file_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = strdup(argv[arg_offset]);\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case string_dt:
	case wstring_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t int %s_nwords = atoi(argv[arg_offset]);\n", arg->name);
          //fprintf(outFile, "\t\t\t printf(\"String Num Words: %%d\\n\", %s_nwords);\n", arg->name);
	  fprintf(outFile, "\t\t\t \n");
	  fprintf(outFile, "\t\t\t int word_i;\n");
	  fprintf(outFile, "\t\t\t int %s_size = 0;\n", arg->name);
	  fprintf(outFile, "\t\t\t for (word_i=1; word_i<=%s_nwords; word_i++) {\n", arg->name);
	  fprintf(outFile, "\t\t\t\t %s_size += strlen(argv[arg_offset + word_i]);\n", arg->name);
	  fprintf(outFile, "\t\t\t }\n");
	  fprintf(outFile, "\t\t\t %s = (char *) malloc(%s_size + %s_nwords);\n", arg->name,arg->name,arg->name);
	  fprintf(outFile, "\t\t\t \n");
	  fprintf(outFile, "\t\t\t for (word_i=1; word_i<=%s_nwords; word_i++) {\n", arg->name);
	  fprintf(outFile, "\t\t\t\t arg_offset += 1;\n");
	  fprintf(outFile, "\t\t\t\t if (word_i == 1)\n");
	  fprintf(outFile, "\t\t\t\t\t strcat(%s, argv[arg_offset]);\n", arg->name);
	  fprintf(outFile, "\t\t\t\t else {\n");
	  fprintf(outFile, "\t\t\t\t\t strcat(%s, \" \");\n", arg->name);
	  fprintf(outFile, "\t\t\t\t\t strcat(%s, argv[arg_offset]);\n", arg->name);
	  fprintf(outFile, "\t\t\t\t }\n");
	  fprintf(outFile, "\t\t\t }\n\n");
	  break;
	case object_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t char *%s_filename = strdup(argv[arg_offset]);\n", arg->name);
	  fprintf(outFile, "\t\t\t ifstream %s_ifs(%s_filename);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t archive::text_iarchive %s_ia(%s_ifs);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t %s_ia >> %s;\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t %s_ifs.close();\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case void_dt:
	case any_dt:
	case null_dt:
	default:;
      }
    }
    
    if (arg->dir == inout_dir || arg->dir == out_dir) {
      switch (arg->type) {
	case char_dt:
	case wchar_dt:
	case boolean_dt:
	case short_dt:
	case long_dt:
	case longlong_dt:
	case int_dt:
	case float_dt:
	case double_dt:
	case object_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t char *%s_filename = strdup(argv[arg_offset]);\n", arg->name);
	  fprintf(outFile, "\t\t\t ifstream %s_ifs(%s_filename);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t archive::text_iarchive %s_ia(%s_ifs);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t %s_ia >> %s;\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t %s_ifs.close();\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case string_dt:
	case wstring_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t char *%s_filename = strdup(argv[arg_offset]);\n", arg->name);
	  fprintf(outFile, "\t\t\t ifstream %s_ifs(%s_filename);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t archive::text_iarchive %s_ia(%s_ifs);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t string %s_in_string;\n", arg->name);
	  fprintf(outFile, "\t\t\t %s_ia >> %s_in_string;\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t %s_ifs.close();\n", arg->name);
	  fprintf(outFile, "\t\t\t %s = strdup(%s_in_string.c_str());\n", arg->name, arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case file_dt:
          fprintf(outFile, "\t\t\t arg_offset += 3;\n");
	  fprintf(outFile, "\t\t\t %s = strdup(argv[arg_offset]);\n", arg->name);
          fprintf(outFile, "\t\t\t arg_offset += 1;\n\n");
	  break;
	case void_dt:
	case any_dt:
	case null_dt:
	default:;
      }
    }
    
    arg = arg->next_argument;
  }
  
  if (( func->classname != NULL ) && (func->access_static == 0)){
    fprintf(outFile, "\t\t\t this_%s.%s(", func->classname, func->methodname);
  } else if ( func->return_type != void_dt ){
    fprintf(outFile, "\t\t\t return_object = %s(", func->name);
  } else {
    fprintf(outFile, "\t\t\t %s(", func->name);
  }
  
  
  is_first_arg = 1;
  arg = func->first_argument;
  while (arg != NULL) {
    if (is_first_arg) {
      is_first_arg = 0;
    } else {
      fprintf(outFile, ", ");
    }
    if (arg->dir == in_dir) {
      switch (arg->type) {
	case char_dt:
	case wchar_dt:
	case boolean_dt:
	case short_dt:
	case long_dt:
	case longlong_dt:
	case int_dt:
	case float_dt:
	case double_dt:
	  fprintf(outFile, "%s", arg->name);
	  break;
	case object_dt:
	  fprintf(outFile, "&%s", arg->name);
	  break;
	case string_dt:
	case wstring_dt:
	  fprintf(outFile, "%s", arg->name);
	  break;
	case file_dt:
	  fprintf(outFile, "%s", arg->name);
	  break;
	case void_dt:
	case any_dt:
	case null_dt:
	default:
	  ;
      }
    } else { /* out_dir || inout_dir */
      switch (arg->type) {
	case char_dt:
	case wchar_dt:
	case boolean_dt:
	case short_dt:
	case long_dt:
	case longlong_dt:
	case int_dt:
	case float_dt:
	case double_dt:
	  fprintf(outFile, "&%s", arg->name);
	  break;
	case object_dt:
	  fprintf(outFile, "&%s", arg->name);
	  break;
	case string_dt:
	case wstring_dt:
	  fprintf(outFile, "&%s", arg->name);
	  break;
	case file_dt:
	  fprintf(outFile, "%s", arg->name);
	  break;
	case void_dt:
	case any_dt:
	case null_dt:
	default:
	  ;
      }
    }
    arg = arg->next_argument;
  }
  
  fprintf(outFile, ");\n");
  
  fprintf(outFile, "\n");
  
  if (( func->classname != NULL ) && (func->access_static == 0)){
    fprintf(outFile, "\t\t\t ofstream this_ofs(this_filename, std::ofstream::trunc);\n");
    fprintf(outFile, "\t\t\t archive::text_oarchive this_oa(this_ofs);\n");
    fprintf(outFile, "\t\t\t this_oa << this_%s;\n", func->classname);
    fprintf(outFile, "\t\t\t this_ofs.flush();\n");
    fprintf(outFile, "\t\t\t this_ofs.close();\n");
    j++;
  }
  
  if ( func->return_type != void_dt ){
    fprintf(outFile, "\t\t\t ofstream return_ofs(return_filename, std::ofstream::trunc);\n");
    fprintf(outFile, "\t\t\t archive::text_oarchive return_oa(return_ofs);\n");
    fprintf(outFile, "\t\t\t return_oa << return_object;\n");
    fprintf(outFile, "\t\t\t return_ofs.flush();\n");
    fprintf(outFile, "\t\t\t return_ofs.close();\n");
    j++;
  }
  
  is_first_arg = 1;
  arg = func->first_argument;
  while (arg != NULL) {
    if (arg->dir == out_dir || arg->dir == inout_dir) {
      switch (arg->type) {
	case char_dt:
	case wchar_dt:
	case boolean_dt:
	case short_dt:
	case long_dt:
	case longlong_dt:
	case int_dt:
	case float_dt:
	case double_dt:
	case object_dt:
	  fprintf(outFile, "\t\t\t ofstream %s_ofs(%s_filename, std::ofstream::trunc);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t\t archive::text_oarchive %s_oa(%s_ofs);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t\t %s_oa << %s;\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t\t %s_ofs.flush();\n", arg->name);
	  fprintf(outFile, "\t\t\t\t %s_ofs.close();\n", arg->name);
	  break;
	case string_dt:
	case wstring_dt:
	  fprintf(outFile, "\t\t\t\t ofstream %s_ofs(%s_filename, std::ofstream::trunc);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t\t archive::text_oarchive %s_oa(%s_ofs);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t\t string %s_out_string (%s);\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t\t %s_oa << %s_out_string;\n", arg->name, arg->name);
	  fprintf(outFile, "\t\t\t\t %s_ofs.flush();\n", arg->name);
	  fprintf(outFile, "\t\t\t\t %s_ofs.close();\n", arg->name);
	  break;
	case file_dt:
	  break;
	case void_dt:
	case any_dt:
	case null_dt:
	default:;
      }
    }
    
    fprintf(outFile, "\n");
    
    arg = arg->next_argument;
    j++;
  }
  
  arg = func->first_argument;
  while (arg != NULL) {
    switch (arg->type) {
      case char_dt:
      case wchar_dt:
      case boolean_dt:
      case short_dt:
      case long_dt:
      case longlong_dt:
      case int_dt:
      case float_dt:
      case double_dt:
	break;
      case file_dt:
	fprintf(outFile, "\t\t\t free(%s);\n", arg->name);
	break;
      case string_dt:
      case wstring_dt:
	fprintf(outFile, "\t\t\t free(%s);\n", arg->name);
	break;
      case void_dt:
      case any_dt:
      case null_dt:
      default:
	;
    }
    arg = arg->next_argument;
  }
 
  // Operation correctly executed
  fprintf(outFile, "\t\t\t return 0;\n");
  
  // Close enum case
  fprintf(outFile, "\t\t }\n");
  fprintf(outFile, "\t\t break;\n");  
}	


void generate_body(void)
{
  function *current_function;
  
  generate_enum(includeFile, get_first_function());
  
  fprintf(stubsFile, "\n");
  
  fprintf(includeFile, "/* Functions to be implemented. We sugest that you create a file */\n");
  fprintf(includeFile, "/* with name '%s-functions.cc' and implement them there. */\n", get_filename_base());
  
  current_function = get_first_function();
  while (current_function != NULL) {
    generate_prototype(stubsFile, current_function);
    fprintf(stubsFile, "\n");
    fprintf(stubsFile, "{\n");
    generate_parameter_buffers(stubsFile, current_function);
    generate_parameter_marshalling(stubsFile, current_function);
    generate_execute_call(stubsFile, current_function);
    fprintf(stubsFile, "}\n");
    fprintf(stubsFile, "\n");
    
    generate_worker_case(workerFile, current_function);
    
    // If the current function is not an object method
    if ( strstr(current_function->name, "::") == NULL) {
      generate_class_includes(includeFile, current_function);
      generate_prototype(includeFile, current_function);
      fprintf(includeFile, ";\n");
    }
    
    current_function = current_function->next_function;
  }
}

