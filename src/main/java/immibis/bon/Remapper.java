package immibis.bon;

import immibis.bon.io.MappingFactory;
import immibis.bon.io.MappingFactory.MappingUnavailableException;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Handle;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

public class Remapper {

	// returns actual owner of field
	// or null if the field could not be resolved
	private static String resolveField(Map<String, ClassNode> refClasses, String owner, String name, String desc, Mapping m) {

		ClassNode cn = refClasses.get(owner);
		if(cn == null) {
			return null;
		}

		if(!m.getField(owner, name, desc).equals(name)) {
			return owner; // short-circuit: this is a remapped field
		}
		// http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.3.2

		for(FieldNode fn : (List<FieldNode>)cn.fields) {
			if(fn.name.equals(name) && fn.desc.equals(desc)) {
				return owner;
			}
		}

		for(String i : (List<String>)cn.interfaces) {
			String result = resolveField(refClasses, i, name, desc, m);
			if(result != null) {
				return result;
			}
		}

		return resolveField(refClasses, cn.superName, name, desc, m);

	}

	// returns [realOwner, realDesc]
	// or null if the method could not be resolved
	private static String[] resolveMethod(Map<String, ClassNode> refClasses, String owner, String name, String desc, Mapping m) {

		ClassNode cn = refClasses.get(owner);
		if(cn == null) {
			return null;
		}

		String newName = m.getMethod(owner, name, desc);
		if(!newName.equals(name)) {
			return new String[]{owner, desc, newName}; // short-circuit: this is a remapped method
		}
		String[] r = null;

		if((cn.access & Opcodes.ACC_INTERFACE) != 0) {

			// interface method resolution; http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.3.4
			for(MethodNode mn : (List<MethodNode>)cn.methods) {
				if(mn.name.equals(name) && mn.desc.equals(desc)) {
					r = new String[]{owner, desc, name};
					break;
				}
			}

			for(String i : (List<String>)cn.interfaces) {
				String[] result = resolveMethod(refClasses, i, name, desc, m);
				if(r == null ? result != null : (result != null && !result[2].equals(r[2]))) {
					return result;
				}
			}

			return r;

		} else {

			// normal method resolution; http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.3.3
			for(MethodNode mn : (List<MethodNode>)cn.methods) {
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

				newName = m.getMethod(owner, name, desc);
				if(!newName.equals(name)) {
					return new String[]{owner, desc, newName}; // short-circuit: this is a remapped method
				}
				if(r == null) {
					for(MethodNode mn : (List<MethodNode>)cn.methods) {
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

				for(String i : (List<String>)cn.interfaces) {
					String[] result = resolveMethod(refClasses, i, name, desc, m);
					if(r == null ? result != null : (result != null && !result[2].equals(r[2]))) {
						return result;
					}
				}

				owner = cn.superName;
			}

			return r;
		}
	}

	public static ClassCollection remap(ClassCollection cc, Mapping m, Collection<ClassCollection> refs, IProgressListener progress) {

		if(!cc.getNameSet().equals(m.fromNS)) {
			throw new IllegalArgumentException("Input classes use nameset " + cc.getNameSet() + ", but mapping is from " + m.fromNS + "; cannot apply mapping");
		}

		for(ClassCollection ref : refs) {
			if(!ref.getNameSet().equals(m.fromNS)) {
				throw new IllegalArgumentException("Reference ClassCollection uses nameset " + ref.getNameSet() + " but input uses " + m.fromNS);
			}
		}

		HashMap<String, ClassNode> refClasses = new HashMap<String, ClassNode>();

		for(ClassCollection refcc : refs) {
			for(ClassNode cn : refcc.getAllClasses()) {
				refClasses.put(cn.name, cn);
			}
		}
		for(ClassNode cn : cc.getAllClasses()) {
			refClasses.put(cn.name, cn);
		}

		cc = cc.cloneWithNameSet(m.toNS);

		int classesProcessed = 0;

		if(progress != null) {
			progress.setMax(cc.getAllClasses().size());
		}

		for(ClassNode cn : cc.getAllClasses()) {

			if(progress != null) {
				progress.set(classesProcessed++);
			}

			for(MethodNode mn : (List<MethodNode>)cn.methods) {

				String[] resolvedMN = resolveMethod(refClasses, cn.name, mn.name, mn.desc, m);

				if(resolvedMN != null) {
					mn.name = m.getMethod(resolvedMN[0], mn.name, resolvedMN[1]);
					mn.desc = m.mapMethodDescriptor(resolvedMN[1]);

				} else {
					mn.name = m.getMethod(cn.name, mn.name, mn.desc);
					mn.desc = m.mapMethodDescriptor(mn.desc);
				}

				if(mn.instructions != null) {
					for(AbstractInsnNode ain = mn.instructions.getFirst(); ain != null; ain = ain.getNext()) {

						switch(ain.getType()) { // TODO TheAndrey: Замена на switch
							case AbstractInsnNode.FIELD_INSN: {
								FieldInsnNode fin = (FieldInsnNode)ain;

								String realOwner = resolveField(refClasses, fin.owner, fin.name, fin.desc, m);
								if(realOwner == null) realOwner = fin.owner;

								fin.name = m.getField(realOwner, fin.name, fin.desc);
								fin.desc = m.mapTypeDescriptor(fin.desc);
								fin.owner = m.getClass(realOwner);
								break;
							}

							case AbstractInsnNode.FRAME: {
								FrameNode fn = (FrameNode)ain;

								if(fn.local != null) {
									for(int k = 0; k < fn.local.size(); k++) {
										if(fn.local.get(k) instanceof String) {
											fn.local.set(k, m.getClass((String)fn.local.get(k)));
										}
									}
								}

								if(fn.stack != null) {
									for(int k = 0; k < fn.stack.size(); k++) {
										if(fn.stack.get(k) instanceof String) {
											fn.stack.set(k, m.getClass((String)fn.stack.get(k)));
										}
									}
								}
								break;
							}

							case AbstractInsnNode.METHOD_INSN: {
								MethodInsnNode min = (MethodInsnNode)ain;

								String[] realOwnerAndDesc = resolveMethod(refClasses, min.owner, min.name, min.desc, m);

								String realOwner = realOwnerAndDesc == null ? min.owner : realOwnerAndDesc[0];
								String realDesc = realOwnerAndDesc == null ? min.desc : realOwnerAndDesc[1];

								min.name = m.getMethod(realOwner, min.name, realDesc);
								min.owner = m.getClass(min.owner); // note: not realOwner which could be an interface
								min.desc = m.mapMethodDescriptor(realDesc);
								break;
							}

							case AbstractInsnNode.LDC_INSN: {
								LdcInsnNode lin = (LdcInsnNode)ain;
								if(lin.cst instanceof Type) {
									lin.cst = Type.getType(m.mapTypeDescriptor(((Type)lin.cst).getDescriptor()));
								}
								break;
							}

							case AbstractInsnNode.TYPE_INSN: {
								TypeInsnNode tin = (TypeInsnNode)ain;
								tin.desc = m.getClass(tin.desc);
								break;
							}

							// TODO TheAndrey start
							case AbstractInsnNode.MULTIANEWARRAY_INSN: { // Многомерный массив
								MultiANewArrayInsnNode arrayinsn = (MultiANewArrayInsnNode)ain;
								arrayinsn.desc = m.getClass(arrayinsn.desc);
								break;
							}

							case AbstractInsnNode.INVOKE_DYNAMIC_INSN: { // Вызов лямбды
								InvokeDynamicInsnNode invokeinsn = (InvokeDynamicInsnNode)ain;
								invokeinsn.desc = m.mapMethodDescriptor(invokeinsn.desc);

								// Правим типы аргументов
								for(int i = 0; i < invokeinsn.bsmArgs.length; i++) {
									Object arg = invokeinsn.bsmArgs[i];
									if(arg instanceof Type) {
										arg = Type.getType(m.mapMethodDescriptor(((Type)arg).getDescriptor()));
									} else if(arg instanceof Handle) {
										Handle handle = (Handle)arg;
										String handleOwner = m.getClass(handle.getOwner());
										String handleDesc = m.mapMethodDescriptor(handle.getDesc());
										if(!handle.getOwner().equals(handleOwner) || !handle.getDesc().equals(handleDesc)) {
											arg = new Handle(handle.getTag(), handleOwner, handle.getName(), handleDesc, handle.isInterface());
										}
									}
									invokeinsn.bsmArgs[i] = arg;
								}
								break;
							}
							// TODO TheAndrey end
						}

					}
				}

				for(TryCatchBlockNode tcb : (List<TryCatchBlockNode>)mn.tryCatchBlocks) {
					if(tcb.type != null) {
						tcb.type = m.getClass(tcb.type);
					}
				}

				{
					Set<String> exceptions = new HashSet<String>(mn.exceptions);
					exceptions.addAll(m.getExceptions(cn.name, mn.name, mn.desc));
					mn.exceptions.clear();
					for(String s : exceptions) {
						mn.exceptions.add(m.getClass(s));
					}
				}

				if(mn.localVariables != null) {
					for(LocalVariableNode lvn : (List<LocalVariableNode>)mn.localVariables) {
						lvn.desc = m.mapTypeDescriptor(lvn.desc);
					}
				}

				mn.signature = m.parseTypes(mn.signature, true, true);

				if(mn.visibleAnnotations != null) {
					for(AnnotationNode n : (List<AnnotationNode>)mn.visibleAnnotations) {
						n.desc = m.parseTypes(n.desc, true, false);
					}
				}
				if(mn.invisibleAnnotations != null) {
					for(AnnotationNode n : (List<AnnotationNode>)mn.invisibleAnnotations) {
						n.desc = m.parseTypes(n.desc, true, false);
					}
				}
			}

			for(FieldNode fn : (List<FieldNode>)cn.fields) {
				fn.name = m.getField(cn.name, fn.name, fn.desc);
				fn.desc = m.mapTypeDescriptor(fn.desc);
				fn.signature = m.parseTypes(fn.signature, true, false);

				if(fn.visibleAnnotations != null) {
					for(AnnotationNode n : (List<AnnotationNode>)fn.visibleAnnotations) {
						n.desc = m.parseTypes(n.desc, true, false);
					}
				}
				if(fn.invisibleAnnotations != null) {
					for(AnnotationNode n : (List<AnnotationNode>)fn.invisibleAnnotations) {
						n.desc = m.parseTypes(n.desc, true, false);
					}
				}
			}

			cn.name = m.getClass(cn.name);
			cn.superName = m.getClass(cn.superName);

			cn.signature = m.parseTypes(cn.signature, true, false);

			for(int k = 0, e = cn.interfaces.size(); k < e; k++) {
				cn.interfaces.set(k, m.getClass((String)cn.interfaces.get(k)));
			}

			if(cn.visibleAnnotations != null) {
				for(AnnotationNode n : (List<AnnotationNode>)cn.visibleAnnotations) {
					n.desc = m.parseTypes(n.desc, true, false);
				}
			}
			if(cn.invisibleAnnotations != null) {
				for(AnnotationNode n : (List<AnnotationNode>)cn.invisibleAnnotations) {
					n.desc = m.parseTypes(n.desc, true, false);
				}
			}

			for(InnerClassNode icn : (List<InnerClassNode>)cn.innerClasses) {
				icn.name = m.getClass(icn.name);
				if(icn.outerName != null) {
					icn.outerName = m.getClass(icn.outerName);
				}
			}

			if(cn.outerMethod != null) {
				String[] resolved = resolveMethod(refClasses, cn.outerClass, cn.outerMethod, cn.outerMethodDesc, m);
				if(resolved != null) {
					cn.outerMethod = m.getMethod(resolved[0], cn.outerMethod, resolved[1]);
					cn.outerMethodDesc = m.mapMethodDescriptor(resolved[1]);
				} else {
					cn.outerMethod = m.getMethod(cn.outerClass, cn.outerMethod, cn.outerMethodDesc);
					cn.outerMethodDesc = m.mapMethodDescriptor(cn.outerMethodDesc);
				}
			}
			if(cn.outerClass != null) {
				cn.outerClass = m.getClass(cn.outerClass);
			}
		}

		return cc;

	}

	public static ClassCollection remap(ClassCollection classes, NameSet toNS, Collection<ClassCollection> refs, IProgressListener progress) throws MappingUnavailableException, IOException {
		return remap(classes, MappingFactory.getMapping(classes.getNameSet(), toNS, null), refs, progress);
	}

}
