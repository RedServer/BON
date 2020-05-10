package immibis.bon.gui;

public enum Operation {

	DEOBFUSCATE_MOD("Deobfuscate mod", "deobf"),
	REOBFUSCATE_MOD("Reobfuscate mod", "reobf"),
	REOBFUSCATE_MOD_SRG("Reobfuscate mod to SRG", "srg"),
	SRGIFY_MOD("Deobfuscate mod to SRG", "srg");

	Operation(String str, String suffix) {
		this.str = str;
		this.defaultNameSuffix = suffix;
	}

	private final String str;

	@Override
	public String toString() {
		return str;
	}

	public final String defaultNameSuffix;

}
