package testPSCOInternal;

import model.Person;
import integratedtoolkit.types.annotations.Method;
import integratedtoolkit.types.annotations.Parameter;
import integratedtoolkit.types.annotations.Parameter.Direction;
import integratedtoolkit.types.annotations.Parameter.Type;


public interface InternalItf {
	
	@Method(declaringClass = "testPSCOInternal.InternalImpl")
	public void taskPSCOIn(
		@Parameter (type = Type.OBJECT, direction = Direction.IN) Person p
	);

	@Method(declaringClass = "testPSCOInternal.InternalImpl")
	public void taskPSCOInOut(
		@Parameter (type = Type.OBJECT, direction = Direction.INOUT) Person p
	);

	@Method(declaringClass = "testPSCOInternal.InternalImpl")
	public String taskPSCOInOutTaskPersisted(
		@Parameter (type = Type.OBJECT, direction = Direction.INOUT) Person p
	);

	@Method(declaringClass = "testPSCOInternal.InternalImpl")
	public Person taskPSCOReturn(
		@Parameter (type = Type.STRING, direction = Direction.IN) String name, 
		@Parameter () int age, 
		@Parameter () int numC,
		@Parameter (type = Type.STRING, direction = Direction.IN) String id
	);

	@Method(declaringClass = "testPSCOInternal.InternalImpl")
	public Person taskPSCOReturnNoTaskPersisted(
		@Parameter (type = Type.STRING, direction = Direction.IN) String name, 
		@Parameter () int age, 
		@Parameter () int numC
	);
	
	@Method(declaringClass = "model.Person")
	public void taskPSCOTarget(
	);
	
	@Method(declaringClass = "model.Person")
	public void taskPSCOTargetTaskPersisted(
		@Parameter (type = Type.STRING, direction = Direction.IN) String id
	);

}