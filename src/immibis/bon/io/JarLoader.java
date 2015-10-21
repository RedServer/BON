package immibis.bon.io;

import immibis.bon.ClassCollection;
import immibis.bon.ClassFormatException;
import immibis.bon.IProgressListener;
import immibis.bon.NameSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.objectweb.asm.tree.ClassNode;

public class JarLoader {

	private static final boolean VERIFY_SIGNATURES = false;

	public static ClassCollection loadClassesFromJar(NameSet nameSet, File jarFile, IProgressListener progress) throws IOException, ClassFormatException {
		Collection<ClassNode> classes = new ArrayList<ClassNode>();
		Map<String, byte[]> extraFiles = new HashMap<String, byte[]>();
		Manifest manifest = null;

		JarInputStream j_in = null;
		try {
			j_in = new JarInputStream(new FileInputStream(jarFile), VERIFY_SIGNATURES);
			JarEntry entry;

			while((entry = j_in.getNextJarEntry()) != null) {

				if(entry.isDirectory()) {
					continue;
				}

				String name = entry.getName();

				if(name.endsWith(".class")) {
					try {
						ClassNode cn = IOUtils.readClass(IOUtils.readStreamFully(j_in));

						if(!name.equals(cn.name + ".class")) {
							throw new ClassFormatException("Class '" + cn.name + "' has wrong path in jar file: '" + name + "'");
						}

						classes.add(cn);

					} catch (ClassFormatException e) {
						throw new RuntimeException("Unable to parse class file: " + name + " in " + jarFile.getName(), e);
					}
				} else {

					extraFiles.put(name, IOUtils.readStreamFully(j_in));
				}
			}
			manifest = j_in.getManifest();
		} finally {
			if(j_in != null) {
				j_in.close();
			}
		}

		ClassCollection cc = new ClassCollection(nameSet, classes, manifest);
		cc.getExtraFiles().putAll(extraFiles);
		return cc;
	}

}
