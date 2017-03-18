package immibis.bon;

import static org.objectweb.asm.Opcodes.*;

import immibis.bon.io.MappingFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Collection;
import java.util.HashMap;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

public class AccessTransformer {

	public static ClassCollection remap(ClassCollection cc, Collection<ClassCollection> refs, IProgressListener progress) {

		if(classAccess.isEmpty() && fieldAccess.isEmpty() && methodAccess.isEmpty()) {
			return cc;
		}

		HashMap<String, ClassNode> refClasses = new HashMap<>();

		for(ClassCollection refcc : refs) {
			for(ClassNode cn : refcc.getAllClasses()) {
				refClasses.put(cn.name, cn);
			}
		}
		for(ClassNode cn : cc.getAllClasses()) {
			refClasses.put(cn.name, cn);
		}

		cc = cc.clone();

		int classesProcessed = 0;

		if(progress != null) {
			progress.setMax(cc.getAllClasses().size());
		}

		for(ClassNode cn : cc.getAllClasses()) {

			if(progress != null) {
				progress.set(classesProcessed++);
			}

			Modifier m = classAccess.get(cn.name);
			if(m != null) {
				cn.access = m.getFixedAccess(cn.access);
			}

			if(cn.innerClasses != null) {
				for(InnerClassNode in : cn.innerClasses) {
					m = classAccess.get(in.name);
					if(m != null) {
						in.access = m.getFixedAccess(in.access);
					}
				}
			}

			for(FieldNode fn : cn.fields) {
				m = fieldAccess.get(cn.name + '/' + fn.name);
				if(m != null) {
					fn.access = m.getFixedAccess(fn.access);
				}
				m = fieldAccess.get(cn.name + "/*");
				if(m != null) {
					fn.access = m.getFixedAccess(fn.access);
				}
			}

			for(MethodNode mn : cn.methods) {

				int access = mn.access;
				for(String owner = cn.name; owner != null;) {
					m = methodAccess.get(owner + '/' + mn.name + mn.desc);
					if(m != null) {
						access = m.getFixedAccess(access);
					}
					m = methodAccess.get(owner + "/*()V");
					if(m != null) {
						access = m.getFixedAccess(access);
					}
					ClassNode clazz = refClasses.get(owner);
					owner = clazz.superName;
				}
				mn.access = access;
			}
		}

		return cc;

	}

	private static HashMap<String, Modifier> classAccess = new HashMap<String, Modifier>();
	private static HashMap<String, Modifier> fieldAccess = new HashMap<String, Modifier>();
	private static HashMap<String, Modifier> methodAccess = new HashMap<String, Modifier>();

	public static void processTransformer(File atFile) {

		FileReader reader = null;
		try {
			reader = new FileReader(atFile);
			processATFile(reader);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if(reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
				}
			}
		}
	}

	protected static void processATFile(Reader rules) throws IOException {

		for(LineReader reader = new LineReader(rules);;) {

			String input = reader.readLine(), line;
			if(input == null) {
				return;
			} else if(input.length() == 0) {
				continue;
			}
			{
				int i = input.indexOf('#');
				line = i < 0 ? input : input.substring(0, i);
			}
			String[] parts = line.split(" ");
			if(parts.length > 3 || parts.length < 2) {
				throw new RuntimeException("Invalid config file line " + input);
			}

			String desc = "";
			String lookupName = parts[1].replace('.', '/');

			HashMap<String, Modifier> map;

			if(parts.length == 2) {
				map = classAccess;
			} else {
				String nameReference = parts[2];
				lookupName += '/' + nameReference;

				int parenIdx = nameReference.indexOf('(');
				if(parenIdx > 0) {
					map = methodAccess;
					desc = nameReference.substring(parenIdx);
					nameReference = nameReference.substring(0, parenIdx);
				} else {
					map = fieldAccess;
				}
				parts[1] = nameReference;
			}
			Modifier mod = map.get(lookupName);

			if(mod == null) {
				if(!MappingFactory.quiet) {
					System.out.printf("Loaded rule %s %s%s for %s\n", parts[0], parts[1], desc, lookupName);
				}
				map.put(lookupName, new Modifier(parts[0], parts[1], desc));
			} else {
				mod.setTargetAccess(parts[0]);
			}
		}
	}

	static class Modifier {

		public String name;
		public String desc;
		public int targetAccess;
		public boolean changeFinal;
		public boolean markFinal;

		public Modifier(String access, String name, String desc) {

			targetAccess = 8;
			setTargetAccess(access);
			this.name = name;
			this.desc = desc;
		}

		public void setTargetAccess(String name) {

			switch(targetAccess) {
				case 8:
					if(name.startsWith("private")) {
						targetAccess = ACC_PRIVATE;
					}
				// continue
				case ACC_PRIVATE:
					if(name.startsWith("default")) {
						targetAccess = 0;
					}
				// continue
				case 0:
					if(name.startsWith("protected")) {
						targetAccess = ACC_PROTECTED;
					}
				// continue
				case ACC_PROTECTED:
					if(name.startsWith("public")) {
						targetAccess = ACC_PUBLIC;
					}
				// continue
				case ACC_PUBLIC:
					break;
			}

			if(targetAccess == 8) {
				throw new RuntimeException("Invalid access modifier: " + name);
			}

			if(name.endsWith("-f")) {
				changeFinal = true;
				markFinal = false;
			} else if(!changeFinal && name.endsWith("+f")) {
				changeFinal = true;
				markFinal = true;
			}
		}

		public int getFixedAccess(int access) {

			int t = targetAccess & 7;
			int ret = (access & ~7);

			switch(access & 7) {
				case ACC_PRIVATE:
					ret |= t;
					break;
				case 0: // default
					ret |= (t != ACC_PRIVATE ? t : 0 /* default */);
					break;
				case ACC_PROTECTED:
					ret |= (t != ACC_PRIVATE && t != 0 /* default */ ? t : ACC_PROTECTED);
					break;
				case ACC_PUBLIC:
					ret |= ACC_PUBLIC;
					break;
				default:
					throw new RuntimeException("The fuck?");
			}

			if(changeFinal) {
				if(markFinal) {
					ret |= ACC_FINAL;
				} else {
					ret &= ~ACC_FINAL;
				}
			}
			return ret;
		}

	}

	static class LineReader { // from Properties#LineReader

		public LineReader(InputStream inStream) {

			this.inStream = inStream;
			inByteBuf = new byte[8192];
		}

		public LineReader(Reader reader) {

			this.reader = reader;
			inCharBuf = new char[8192];
		}

		byte[] inByteBuf;
		char[] inCharBuf;
		char[] lineBuf = new char[1024];
		int inLimit = 0;
		int inOff = 0;
		InputStream inStream;
		Reader reader;

		String readLine() throws IOException {

			int len = 0;
			char c = 0;

			boolean skipWhiteSpace = true;
			boolean isCommentLine = false;
			boolean isNewLine = true;
			boolean appendedLineBegin = false;
			boolean precedingBackslash = false;
			boolean skipLF = false;

			while(true) {
				if(inOff >= inLimit) {
					inLimit = (inStream == null) ? reader.read(inCharBuf)
							: inStream.read(inByteBuf);
					inOff = 0;
					if(inLimit <= 0) {
						if(len == 0 || isCommentLine) {
							return null;
						}
						return new String(lineBuf, 0, len);
					}
				}
				if(inStream != null) {
					//The line below is equivalent to calling a
					//ISO8859-1 decoder.
					c = (char)(0xff & inByteBuf[inOff++]);
				} else {
					c = inCharBuf[inOff++];
				}
				if(skipLF) {
					skipLF = false;
					if(c == '\n') {
						continue;
					}
				}
				if(skipWhiteSpace) {
					if(c == ' ' || c == '\t' || c == '\f') {
						continue;
					}
					if(!appendedLineBegin && (c == '\r' || c == '\n')) {
						continue;
					}
					skipWhiteSpace = false;
					appendedLineBegin = false;
				}
				if(isNewLine) {
					isNewLine = false;
					if(c == '#' || c == '!') {
						isCommentLine = true;
						continue;
					}
				}

				if(c != '\n' && c != '\r') {
					lineBuf[len++] = c;
					if(len == lineBuf.length) {
						int newLength = lineBuf.length * 2;
						if(newLength < 0) {
							newLength = Integer.MAX_VALUE;
						}
						char[] buf = new char[newLength];
						System.arraycopy(lineBuf, 0, buf, 0, lineBuf.length);
						lineBuf = buf;
					}
					//flip the preceding backslash flag
					if(c == '\\') {
						precedingBackslash = !precedingBackslash;
					} else {
						precedingBackslash = false;
					}
				} else {
					// reached EOL
					if(isCommentLine || len == 0) {
						isCommentLine = false;
						isNewLine = true;
						skipWhiteSpace = true;
						len = 0;
						continue;
					}
					if(inOff >= inLimit) {
						inLimit = (inStream == null)
								? reader.read(inCharBuf)
								: inStream.read(inByteBuf);
						inOff = 0;
						if(inLimit <= 0) {
							return new String(lineBuf, 0, len);
						}
					}
					if(precedingBackslash) {
						len -= 1;
						//skip the leading whitespace characters in following line
						skipWhiteSpace = true;
						appendedLineBegin = true;
						precedingBackslash = false;
						if(c == '\r') {
							skipLF = true;
						}
					} else {
						return new String(lineBuf, 0, len);
					}
				}
			}
		}

	}

}
