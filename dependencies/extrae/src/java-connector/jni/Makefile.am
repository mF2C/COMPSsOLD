include $(top_srcdir)/PATHS

EXTRA_DIST=es

JNI_INCLUDE_DIR=include

all: javatrace.jar

javatrace.jar: es/bsc/cepbatools/extrae/Wrapper.java
	$(JAVAC) $(BASE_DIR)/src/java-connector/jni/es/bsc/cepbatools/extrae/Wrapper.java -d .
	$(JAVAH) -d $(JNI_INCLUDE_DIR) -classpath . -jni es.bsc.cepbatools.extrae.Wrapper
	$(JAR) cvf javatrace.jar es/bsc/cepbatools/extrae/Wrapper.class 

clean-local:
	rm -fr javatrace.jar es/bsc/cepbatools/extrae/Wrapper.class $(JNI_INCLUDE_DIR)

install-data-local: javatrace.jar
	$(MKDIR_P) $(libdir)
	cp javatrace.jar $(libdir)

uninstall-local:
	rm $(libdir)/javatrace.jar
