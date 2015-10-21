package immibis.bon.cui;

import immibis.bon.AccessTransformer;
import immibis.bon.ClassCollection;
import immibis.bon.NameSet;
import immibis.bon.Remapper;
import immibis.bon.io.ClassCollectionFactory;
import immibis.bon.io.JarWriter;
import immibis.bon.io.MappingFactory;
import immibis.bon.mcp.MappingLoader_MCP;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MCPRemap extends CUIBase {

	private static class Timer {

		private long start;

		public Timer() {
			start = System.currentTimeMillis();
		}

		public int flip() {
			int rv = (int)(System.currentTimeMillis() - start);
			start = System.currentTimeMillis();
			return rv;
		}

	}

	@Override
	protected void run() throws Exception {
		Timer timer = new Timer();
		int readTime = 0, remapTime = 0, transformTime = 0, writeTime = 0;

		System.out.println("Loading MCP configuration");

		String mcVer = MappingLoader_MCP.getMCVer(mcpDir);
		MappingFactory.registerMCPInstance(mcVer, side, mcpDir, null);
		readTime += timer.flip();

		NameSet inputNS = new NameSet(fromType, side, mcVer);
		NameSet outputNS = new NameSet(toType, side, mcVer);

		List<ClassCollection> refs = new ArrayList<ClassCollection>();
		for(RefOption ro : refOptsParsed) {
			NameSet refNS = new NameSet(ro.type, side, mcVer);

			if(!quiet) {
				System.out.println("Loading " + ro.file.getName());
			}
			ClassCollection refCC = ClassCollectionFactory.loadClassCollection(refNS, ro.file, null);
			readTime += timer.flip();

			if(!refNS.equals(inputNS)) {
				if(!quiet) {
					System.out.println("Remapping " + ro.file.getName() + " (" + refNS + " -> " + inputNS + ")");
				}
				refCC = Remapper.remap(refCC, inputNS, Collections.<ClassCollection>emptyList(), null);
				remapTime += timer.flip();
			}

			refs.add(refCC);
		}

		if(!quiet) {
			System.out.println("Loading " + inFile.getName());
		}
		ClassCollection inputCC = ClassCollectionFactory.loadClassCollection(inputNS, inFile, null);
		readTime += timer.flip();

		if(!quiet) {
			System.out.println("Applying AccessTransformers");
		}
		inputCC = AccessTransformer.remap(inputCC, refs, null);
		transformTime += timer.flip();

		System.out.println("Remapping " + inFile.getName() + " (" + inputNS + " -> " + outputNS + ")");
		ClassCollection outputCC = Remapper.remap(inputCC, outputNS, refs, null);
		remapTime += timer.flip();

		System.out.println("Writing " + outFile.getName());
		JarWriter.write(outFile, outputCC, keepManifest, null);
		writeTime += timer.flip();

		if(!quiet) {
			System.out.printf("Completed in %d ms (%dms read, %dms remap, %dms AT, %dms write)\n", readTime + remapTime + transformTime + writeTime,
					readTime, remapTime, transformTime, writeTime);
		} else {
			System.out.printf("Completed in %d ms\n", readTime + remapTime + transformTime + writeTime);
		}
	}

	@Required
	@Option("-mcp")
	public File mcpDir;
	@Required
	@Option("-in")
	public File inFile;
	@Required
	@Option("-out")
	public File outFile;
	@Required
	@Option("-from")
	public NameSet.Type fromType;
	@Required
	@Option("-to")
	public NameSet.Type toType;
	@Option("-side")
	public NameSet.Side side = NameSet.Side.UNIVERSAL;
	@Option("-ref")
	public List<String> refOpts = new ArrayList<String>();
	@Option("-refn")
	public List<String> refnOpts = new ArrayList<String>();
	@Option("-jref")
	public List<String> jrefOpts = new ArrayList<String>();
	@Option("-at")
	public List<String> atOpts = new ArrayList<String>();
	@Option("-q")
	public boolean quiet = false;
	@Option("-m")
	public boolean keepManifest = false;

	private static class RefOption {

		public NameSet.Type type;
		public File file;

		public RefOption(NameSet.Type t, File f) {
			type = t;
			file = f;
		}

	}

	private List<RefOption> refOptsParsed = new ArrayList<RefOption>();

	private List<File> atOptsParsed = new ArrayList<File>();

	@Override
	protected boolean checkOptions() throws Exception {
		if(!super.checkOptions()) {
			return false;
		}

		MappingFactory.quiet = quiet;

		boolean ok = true;

		if(!inFile.exists()) {
			System.err.println("Input file doesn't exist: " + inFile.getAbsolutePath());
			ok = false;
		}

		if(outFile.isDirectory()) {
			System.err.println("Output file is a directory: " + outFile.getAbsolutePath());
			ok = false;
		}

		if(!mcpDir.exists()) {
			System.err.println("MCP directory doesn't exist: " + mcpDir.getAbsolutePath());
			ok = false;
		}

		for(String s : refOpts) {
			refOptsParsed.add(new RefOption(fromType, new File(s)));
		}

		for(String s : refnOpts) {
			String[] p = s.split(":", 2);
			if(p.length != 2) {
				System.err.println("Missing : in -refn option: " + s);
				ok = false;
			} else {
				try {
					refOptsParsed.add(new RefOption(NameSet.Type.valueOf(p[0]), new File(p[1])));
				} catch (EnumConstantNotPresentException e) {
					System.err.println("Invalid name type: " + p[0]);
					ok = false;
				}
			}
		}

		for(String s1 : jrefOpts) {
			String[] sl;
			if(s1.indexOf(File.pathSeparatorChar) > 0) {
				sl = s1.split(File.pathSeparator);
			} else {
				sl = new String[]{s1};
			}

			for(String s : sl) {
				File d = new File(s);
				if(!d.isDirectory()) {
					System.err.println("Invalid input for -jref. Not a directory: " + s);
					continue;
				}
				File[] l = d.listFiles(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return (name.endsWith(".jar") || name.endsWith(".zip")) && new File(dir, name).isFile();
					}
				});
				if(l == null || l.length == 0) {
					continue;
				}
				for(File f : l) {
					refOptsParsed.add(new RefOption(fromType, f));
				}
			}
		}

		for(String s1 : atOpts) {
			String[] sl;
			if(s1.indexOf(File.pathSeparatorChar) > 0) {
				sl = s1.split(File.pathSeparator);
			} else {
				sl = new String[]{s1};
			}

			for(String s : sl) {
				File d = new File(s);
				if(!d.isDirectory()) {
					if(d.isFile()) {
						atOptsParsed.add(d);
					} else {
						System.err.println("Invalid input for -at. Not a directory: " + s);
					}
					continue;
				}
				File[] l = d.listFiles(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return name.endsWith("_at.cfg") && new File(dir, name).isFile();
					}
				});
				if(l == null || l.length == 0) {
					continue;
				}
				for(File f : l) {
					atOptsParsed.add(f);
				}
			}
		}

		for(RefOption ro : refOptsParsed) {
			if(!ro.file.exists()) {
				System.err.println("Reference file doesn't exist: " + ro.file.getAbsolutePath());
				ok = false;
			}
		}

		for(File ro : atOptsParsed) {
			if(!ro.exists()) {
				System.err.println("AccessTransformer file doesn't exist: " + ro.getAbsolutePath());
			} else {
				try {
					AccessTransformer.processTransformer(ro);
				} catch (RuntimeException ex) {
					System.err.println("AccessTransformer " + ro.getAbsolutePath() + " caused error: " + ex.getMessage());
					ok = false;
				}
			}
		}

		return ok;
	}

	@Override
	protected void showUsage() {
		System.out.println("Usage:");
		System.out.println("  java -jar BON.jar <option>...");
		System.out.println("");
		System.out.println("Required options:");
		System.out.println("  -mcp <mcp dir>");
		System.out.println("       Specifies the path to the MCP directory.");
		System.out.println("  -from <source names>");
		System.out.println("       Specifies the type of names the input file will uses.");
		System.out.println("       Can be OBF or SRG or MCP.");
		System.out.println("  -to <target names>");
		System.out.println("       Specifies the type of names the output file will use.");
		System.out.println("       Can be OBF or SRG or MCP.");
		System.out.println("  -side <side>");
		System.out.println("       Can be UNIVERSAL, CLIENT or SERVER.");
		System.out.println("  -in <input file>");
		System.out.println("       Specifies the path to the input file");
		System.out.println("  -out <output file>");
		System.out.println("       Specifies the path to the output file");
		System.out.println("");
		System.out.println("Optional options:");
		System.out.println("  -ref <reference file>");
		System.out.println("       Specifies the path to a jar file or directory which the input code depends on.");
		System.out.println("       This is hard to describe exactly, but you will want to specify MCP/bin/minecraft");
		System.out.println("       as well as any mods your mod depends on (e.g. RedPowerCore when processing");
		System.out.println("       RedPowerDigital). You can use this option several times with different files.");
		System.out.println("       The file must be using the same names specified in <source names>. See -refn.");
		System.out.println("");
		System.out.println("  -refn <names>:<reference file>");
		System.out.println("       Same as -ref, but the reference file can be using obfuscated, SRG or MCP names.");
		System.out.println("       If <names> is different from <source names>, the file will be remapped automatically,");
		System.out.println("       which will take slightly longer than if the file was already remapped.");
		System.out.println("");
		System.out.println("  -jref <reference dir(s)>");
		System.out.println("       Specifies the path to a directory which contains jars the input code depends on.");
		System.out.println("       It is assumed that all jars or zips in the directory are using the same mapping");
		System.out.println("       as the input jar. You can use this option instead of having one -ref per jar.");
		System.out.println("       This option *is* required to be a directory, and you may specify multiple using the");
		System.out.println("       path separator character (; on windows, : on *nix) on your OS, or multiple options.");
		System.out.println("");
		System.out.println("  -at <AT dir(s)>");
		System.out.println("       Specifies the path to a directory which contains _at.cfg files the input code depends on.");
		System.out.println("       It is assumed that all ATs in the directory are using the same mapping as the input jar.");
		System.out.println("       You can also pass individual files to this option. You may specify multiple using the");
		System.out.println("       path separator character (; on windows, : on *nix) on your OS, or multiple options.");
		System.out.println("");
		System.out.println("  -m");
		System.out.println("       Will ensure the manifest file (when remapping a jar) will be kept in the generated output jar.");
		System.out.println("");
		System.out.println("  -q");
		System.out.println("       Will minimize the output of BON to the console.");
		System.out.println("");
		System.out.println("Example command line:");
		System.out.println("  -mcp . -from OBF -to MCP -side UNIVERSAL -in RedPowerDigital.zip -out RedPowerDigital-deobf.zip -ref RedPowerCore.zip -refn MCP:bin/minecraft");
		System.out.println("       Deobfuscates RedPowerDigital.zip, saving the result in RedPowerDigital-deobf.zip.");
		System.out.println("       The current directory contains an MCP installation. RedPowerCore.zip (which is obfuscated)");
		System.out.println("       and bin/minecraft (which is not) will also be loaded.");
		System.out.println("");
		System.out.println("  -mcp . -from MCP -to OBF -side UNIVERSAL -in AwesomeMod.jar -out AwesomeMod-obf.jar -ref bin/minecraft");
		System.out.println("       Obfuscates AwesomeMod.jar, saving the result in AwesomeMod-obf.jar.");
		System.out.println("       The current directory contains an MCP installation.");
		System.out.println("");
		System.out.println("Note: If deobfuscating, you need to know if the input file is using SRG or OBF names.");
		System.out.println("The GUI gets around this by remapping twice, once with '-from OBF -to SRG', and then with '-from SRG -to MCP',");
		System.out.println("which is slower.");
		System.out.println("");
		System.out.println("Note: Automatic remapping of reference files may not work correctly if the reference file itself needs");
		System.out.println("reference files to remap correctly. (E.g. if RPDigital.zip requires RPCore.zip which requires bin/minecraft)");
		System.out.println("In this case you will need to ensure the reference files do not need remapping.");
		System.out.println("A reference file could be the output of a previous command.");
		System.out.println("");
	}

	public static void main(String[] args) throws Exception {
		new MCPRemap().run(args);
	}

}
