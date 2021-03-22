package immibis.bon;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import immibis.bon.io.MappingFactory;
import immibis.bon.io.MappingFactory.MappingUnavailableException;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * TheAndrey: OOP style
 */
public class Remapper {

	private final HashMap<String, ClassNode> refClasses = new HashMap<>();
	private final HashMap<String, Set<String>> inheritanceMap = new HashMap<>(); // parent class => inheritor list
	private final Mapping mapping;

	public Remapper(Mapping mapping) {
		this.mapping = mapping;
	}

	/**
	 * Returns actual owner of field or null if the field could not be resolved
	 */
	private String resolveField(String owner, String name, String desc) {
		ClassNode cn = refClasses.get(owner);
		if(cn == null) return null;

		if(!mapping.getField(owner, name, desc).equals(name)) {
			return owner; // short-circuit: this is a remapped field
		}
		// http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.3.2

		for(FieldNode fn : cn.fields) {
			if(fn.name.equals(name) && fn.desc.equals(desc)) {
				return owner;
			}
		}

		for(String i : cn.interfaces) {
			String result = resolveField(i, name, desc);
			if(result != null) {
				return result;
			}
		}

		return resolveField(cn.superName, name, desc);

	}

	/**
	 * Returns actual owner of method
	 * @return [realOwner, realDesc] or null if the method could not be resolved
	 */
	private String[] resolveMethod(String owner, String name, String desc) {
		ClassNode cn = refClasses.get(owner);
		if(cn == null) return null;

		String newName = mapping.getMethod(owner, name, desc);
		if(!newName.equals(name)) {
			return new String[]{owner, desc, newName}; // short-circuit: this is a remapped method
		}
		String[] r = null;

		/* Process interface class */
		if((cn.access & Opcodes.ACC_INTERFACE) != 0) {

			// interface method resolution; http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.3.4
			for(MethodNode mn : cn.methods) {
				if(mn.name.equals(name) && mn.desc.equals(desc)) {
					r = new String[]{owner, desc, name};
					break;
				}
			}

			for(String i : cn.interfaces) {
				String[] result = resolveMethod(i, name, desc);
				if(r == null ? result != null : (result != null && !result[2].equals(r[2]))) {
					return result;
				}
			}

		} else {

			// normal method resolution; http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.3.3
			for(MethodNode mn : cn.methods) {
				if(mn.name.equals(name) && mn.desc.equals(desc)) {
					r = new String[]{owner, desc, name};
					break;
				}
			}

			String originalOwner = owner;

			while(true) {
				cn = refClasses.get(owner);
				if(cn == null) {
					break;
				}

				newName = mapping.getMethod(owner, name, desc);
				if(!newName.equals(name)) {
					return new String[]{owner, desc, newName}; // short-circuit: this is a remapped method
				}
				if(r == null) {
					for(MethodNode mn : cn.methods) {
						if(mn.name.equals(name) && mn.desc.equals(desc)) {
							r = new String[]{owner, desc, name};
							break;
						}
					}
				}

				owner = cn.superName;
			}

			owner = originalOwner;

			while(true) {
				cn = refClasses.get(owner);
				if(cn == null) {
					break;
				}

				for(String i : cn.interfaces) {
					String[] result = resolveMethod(i, name, desc);
					if(r == null ? result != null : (result != null && !result[2].equals(r[2]))) {
						return result;
					}
				}

				owner = cn.superName;
			}

			/* TheAndrey: Find method owner in interfaces of inherited classes */
			HashSet<ClassNode> possibleInterfaces = new LinkedHashSet<>();
			findInheritedInterfaces(originalOwner, possibleInterfaces);

			for(ClassNode inode : possibleInterfaces) {
				for(MethodNode method : inode.methods) {
					if(method.name.equals(name) && method.desc.equals(desc)) {
						r = new String[]{inode.name, desc, name};
						break;
					}
				}
			}
		}

		return r;
	}

	/**
	 * Recursive scan of inherited interfaces
	 */
	private void findInheritedInterfaces(String owner, Set<ClassNode> interfaces) {
		/* Get all inherits of class */
		Set<String> inherited = inheritanceMap.get(owner);
		if(inherited == null || inherited.isEmpty()) return; // stop

		for(String name : inherited) {
			/* Get interfaces */
			ClassNode node = refClasses.get(name);
			if(node != null) addInterfacesRecursive(node, interfaces);

			findInheritedInterfaces(name, interfaces);
		}
	}

	private void addInterfacesRecursive(ClassNode node, Set<ClassNode> interfaces) {
		if(node.interfaces == null || node.interfaces.isEmpty()) return;

		for(String name : node.interfaces) {
			ClassNode inode = refClasses.get(name);
			if(inode != null) {
				interfaces.add(inode);
				if(Modifier.isInterface(inode.access)) addInterfacesRecursive(inode, interfaces);
			}
		}
	}

	public ClassCollection remap(ClassCollection cc, Collection<ClassCollection> refs, IProgressListener progress) {
		if(!cc.getNameSet().equals(mapping.fromNS)) {
			throw new IllegalArgumentException("Input classes use nameset " + cc.getNameSet() + ", but mapping is from " + mapping.fromNS + "; cannot apply mapping");
		}

		for(ClassCollection ref : refs) {
			if(!ref.getNameSet().equals(mapping.fromNS)) {
				throw new IllegalArgumentException("Reference ClassCollection uses nameset " + ref.getNameSet() + " but input uses " + mapping.fromNS);
			}
		}

		refClasses.clear();
		inheritanceMap.clear();

		for(ClassCollection refcc : refs) {
			for(ClassNode cn : refcc.getAllClasses()) {
				refClasses.put(cn.name, cn);
			}
		}
		for(ClassNode cn : cc.getAllClasses()) {
			refClasses.put(cn.name, cn);
		}

		// TheAndrey: Generate inheritance map
		for(ClassNode node : refClasses.values()) {
			if(Modifier.isInterface(node.access)) { // Interface

				if(node.interfaces != null && !node.interfaces.isEmpty()) {
					for(String parent : node.interfaces) {
						if(parent.startsWith("java/")) continue; // Not needed for remap

						Set<String> list = inheritanceMap.computeIfAbsent(parent, k -> new LinkedHashSet<>());
						list.add(node.name);
					}
				}

			} else if(!node.superName.equals("java/lang/Object")) { // Normal class

				Set<String> list = inheritanceMap.computeIfAbsent(node.superName, k -> new LinkedHashSet<>());
				list.add(node.name);

			}
		}

		cc = cc.cloneWithNameSet(mapping.toNS);

		int classesProcessed = 0;

		if(progress != null) {
			progress.setMax(cc.getAllClasses().size());
		}

		for(ClassNode cn : cc.getAllClasses()) {

			if(progress != null) {
				progress.set(classesProcessed++);
			}

			for(MethodNode mn : cn.methods) {

				String[] resolvedMN = resolveMethod(cn.name, mn.name, mn.desc);

				if(resolvedMN != null) {
					mn.name = mapping.getMethod(resolvedMN[0], mn.name, resolvedMN[1]);
					mn.desc = mapping.mapMethodDescriptor(resolvedMN[1]);

				} else {
					mn.name = mapping.getMethod(cn.name, mn.name, mn.desc);
					mn.desc = mapping.mapMethodDescriptor(mn.desc);
				}

				if(mn.instructions != null) {
					for(AbstractInsnNode ain = mn.instructions.getFirst(); ain != null; ain = ain.getNext()) {

						switch(ain.getType()) {
							case AbstractInsnNode.FIELD_INSN: {
								FieldInsnNode fin = (FieldInsnNode)ain;

								String realOwner = resolveField(fin.owner, fin.name, fin.desc);
								if(realOwner == null) realOwner = fin.owner;

								fin.name = mapping.getField(realOwner, fin.name, fin.desc);
								fin.desc = mapping.mapTypeDescriptor(fin.desc);
								fin.owner = mapping.getClass(realOwner);
								break;
							}

							case AbstractInsnNode.FRAME: {
								FrameNode fn = (FrameNode)ain;

								if(fn.local != null) {
									for(int k = 0; k < fn.local.size(); k++) {
										if(fn.local.get(k) instanceof String) {
											fn.local.set(k, mapping.getClass((String)fn.local.get(k)));
										}
									}
								}

								if(fn.stack != null) {
									for(int k = 0; k < fn.stack.size(); k++) {
										if(fn.stack.get(k) instanceof String) {
											fn.stack.set(k, mapping.getClass((String)fn.stack.get(k)));
										}
									}
								}
								break;
							}

							case AbstractInsnNode.METHOD_INSN: {
								MethodInsnNode min = (MethodInsnNode)ain;

								String[] realOwnerAndDesc = resolveMethod(min.owner, min.name, min.desc);

								String realOwner = realOwnerAndDesc == null ? min.owner : realOwnerAndDesc[0];
								String realDesc = realOwnerAndDesc == null ? min.desc : realOwnerAndDesc[1];

								min.name = mapping.getMethod(realOwner, min.name, realDesc);
								min.owner = mapping.getClass(min.owner); // note: not realOwner which could be an interface
								min.desc = mapping.mapMethodDescriptor(realDesc);
								break;
							}

							case AbstractInsnNode.LDC_INSN: {
								LdcInsnNode lin = (LdcInsnNode)ain;
								if(lin.cst instanceof Type) {
									lin.cst = Type.getType(mapping.mapTypeDescriptor(((Type)lin.cst).getDescriptor()));
								}
								break;
							}

							case AbstractInsnNode.TYPE_INSN: {
								TypeInsnNode tin = (TypeInsnNode)ain;
								tin.desc = mapping.getClass(tin.desc);
								break;
							}

							// TheAndrey start
							case AbstractInsnNode.MULTIANEWARRAY_INSN: { // Многомерный массив
								MultiANewArrayInsnNode arrayinsn = (MultiANewArrayInsnNode)ain;
								arrayinsn.desc = mapping.getClass(arrayinsn.desc);
								break;
							}

							case AbstractInsnNode.INVOKE_DYNAMIC_INSN: { // Вызов лямбды
								InvokeDynamicInsnNode invokeinsn = (InvokeDynamicInsnNode)ain;
								Type returnType = Type.getReturnType(invokeinsn.desc);
								Type internalDesc = null;

								invokeinsn.desc = mapping.mapMethodDescriptor(invokeinsn.desc);

								// Правим типы аргументов
								for(int i = 0; i < invokeinsn.bsmArgs.length; i++) {
									Object arg = invokeinsn.bsmArgs[i];

									if(arg instanceof Type) {
										if(internalDesc == null) internalDesc = (Type)arg;
										arg = Type.getType(mapping.mapMethodDescriptor(((Type)arg).getDescriptor()));
									} else if(arg instanceof Handle) {
										Handle handle = (Handle)arg;
										boolean isField = isFieldHandle(handle);
										String handleOwner = mapping.getClass(handle.getOwner());
										String handleName = isField ? mapping.getField(handle.getOwner(), handle.getName(), handle.getDesc()) : mapping.getMethod(handle.getOwner(), handle.getName(), handle.getDesc());
										String handleDesc = isField ? mapping.mapTypeDescriptor(handle.getDesc()) : mapping.mapMethodDescriptor(handle.getDesc());
										if(!handle.getOwner().equals(handleOwner) || !handle.getName().equals(handleName) || !handle.getDesc().equals(handleDesc)) {
											arg = new Handle(handle.getTag(), handleOwner, handleName, handleDesc, handle.isInterface());
										}
									}

									invokeinsn.bsmArgs[i] = arg;
								}

								// Переименование BootstrapMethod, после того как определили его desc из параметров
								if(internalDesc != null) {
									invokeinsn.name = mapping.getMethod(returnType.getInternalName(), invokeinsn.name, internalDesc.toString());
								}
								break;
							}
							// TheAndrey end
						}

					}
				}

				for(TryCatchBlockNode tcb : mn.tryCatchBlocks) {
					if(tcb.type != null) {
						tcb.type = mapping.getClass(tcb.type);
					}
				}

				{
					Set<String> exceptions = new HashSet<>(mn.exceptions);
					exceptions.addAll(mapping.getExceptions(cn.name, mn.name, mn.desc));
					mn.exceptions.clear();
					for(String s : exceptions) {
						mn.exceptions.add(mapping.getClass(s));
					}
				}

				if(mn.localVariables != null) {
					for(LocalVariableNode lvn : mn.localVariables) {
						lvn.desc = mapping.mapTypeDescriptor(lvn.desc);
					}
				}

				mn.signature = mapping.parseTypes(mn.signature, true, true);

				if(mn.visibleAnnotations != null) {
					for(AnnotationNode n : mn.visibleAnnotations) {
						n.desc = mapping.parseTypes(n.desc, true, false);
					}
				}
				if(mn.invisibleAnnotations != null) {
					for(AnnotationNode n : mn.invisibleAnnotations) {
						n.desc = mapping.parseTypes(n.desc, true, false);
					}
				}
			}

			for(FieldNode fn : cn.fields) {
				fn.name = mapping.getField(cn.name, fn.name, fn.desc);
				fn.desc = mapping.mapTypeDescriptor(fn.desc);
				fn.signature = mapping.parseTypes(fn.signature, true, false);

				if(fn.visibleAnnotations != null) {
					for(AnnotationNode n : fn.visibleAnnotations) {
						n.desc = mapping.parseTypes(n.desc, true, false);
					}
				}
				if(fn.invisibleAnnotations != null) {
					for(AnnotationNode n : fn.invisibleAnnotations) {
						n.desc = mapping.parseTypes(n.desc, true, false);
					}
				}
			}

			cn.name = mapping.getClass(cn.name);
			cn.superName = mapping.getClass(cn.superName);

			cn.signature = mapping.parseTypes(cn.signature, true, false);

			for(int k = 0, e = cn.interfaces.size(); k < e; k++) {
				cn.interfaces.set(k, mapping.getClass(cn.interfaces.get(k)));
			}

			if(cn.visibleAnnotations != null) {
				for(AnnotationNode n : cn.visibleAnnotations) {
					n.desc = mapping.parseTypes(n.desc, true, false);
				}
			}
			if(cn.invisibleAnnotations != null) {
				for(AnnotationNode n : cn.invisibleAnnotations) {
					n.desc = mapping.parseTypes(n.desc, true, false);
				}
			}

			for(InnerClassNode icn : cn.innerClasses) {
				icn.name = mapping.getClass(icn.name);
				if(icn.outerName != null) {
					icn.outerName = mapping.getClass(icn.outerName);
				}
			}

			if(cn.outerMethod != null) {
				String[] resolved = resolveMethod(cn.outerClass, cn.outerMethod, cn.outerMethodDesc);
				if(resolved != null) {
					cn.outerMethod = mapping.getMethod(resolved[0], cn.outerMethod, resolved[1]);
					cn.outerMethodDesc = mapping.mapMethodDescriptor(resolved[1]);
				} else {
					cn.outerMethod = mapping.getMethod(cn.outerClass, cn.outerMethod, cn.outerMethodDesc);
					cn.outerMethodDesc = mapping.mapMethodDescriptor(cn.outerMethodDesc);
				}
			}
			if(cn.outerClass != null) {
				cn.outerClass = mapping.getClass(cn.outerClass);
			}
		}

		return cc;
	}

	public static ClassCollection remap(ClassCollection classes, NameSet toNS, Collection<ClassCollection> refs, IProgressListener progress) throws MappingUnavailableException {
		Remapper instance = new Remapper(MappingFactory.getMapping(classes.getNameSet(), toNS, null));
		return instance.remap(classes, refs, progress);
	}

	private static boolean isFieldHandle(Handle handle) {
		return handle.getTag() == Opcodes.H_GETFIELD || handle.getTag() == Opcodes.H_GETSTATIC || handle.getTag() == Opcodes.H_PUTFIELD || handle.getTag() == Opcodes.H_PUTSTATIC;
	}
}
