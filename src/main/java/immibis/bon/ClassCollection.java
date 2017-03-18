package immibis.bon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Manifest;

import org.objectweb.asm.tree.ClassNode;

public class ClassCollection implements Cloneable {

	public ClassCollection(NameSet nameSet, Collection<ClassNode> classes, Manifest manifest) {
		this.nameSet = nameSet;
		this.classes.addAll(classes);
		this.manifest = manifest;
	}

	private NameSet nameSet;
	private final Manifest manifest;
	private Collection<ClassNode> classes = new ArrayList<ClassNode>();
	private Map<String, byte[]> extraFiles = new HashMap<String, byte[]>();

	public Collection<ClassNode> getAllClasses() {
		return classes;
	}

	public NameSet getNameSet() {
		return nameSet;
	}

	public Manifest getManifest() {
		return manifest;
	}

	@Override
	public ClassCollection clone() {
		try {
			ClassCollection clone = (ClassCollection)super.clone();
			clone.classes = new ArrayList<ClassNode>();

			for(ClassNode ocn : classes) {
				// clone the ClassNode
				ClassNode ncn = new ClassNode();
				ocn.accept(ncn);
				clone.classes.add(ncn);
			}

			// copy map, but don't copy data
			clone.extraFiles = new HashMap<String, byte[]>(extraFiles);

			return clone;

		} catch (CloneNotSupportedException e) {
			throw new RuntimeException("This can't happen", e);
		}
	}

	public ClassCollection cloneWithNameSet(NameSet newNS) {
		ClassCollection rv = clone();
		rv.nameSet = newNS;
		return rv;
	}

	public Map<String, ClassNode> getClassMap() {
		Map<String, ClassNode> rv = new HashMap<String, ClassNode>();
		for(ClassNode cn : classes) {
			rv.put(cn.name, cn);
		}
		return rv;
	}

	public Map<String, byte[]> getExtraFiles() {
		return extraFiles;
	}

}
