package immibis.bon;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Mapping {

	// TODO TheAndrey start: Добавленные поля
	private static final Pattern PATTERN_CLASS_PART = Pattern.compile("^[a-z0-9_\\/]+\\$([0-9]+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern PATTERN_SUBCLASS = Pattern.compile("^[a-z0-9_\\/]+(\\$[a-z0-9]+)+$", Pattern.CASE_INSENSITIVE);
	// TODO TheAndrey end

	private final Map<String, String> classes = new HashMap<>();
	private final Map<String, String> methods = new HashMap<>();
	private final Map<String, String> fields = new HashMap<>();
	private final Map<String, List<String>> exceptions = new HashMap<>();
	private final Map<String, String> classPrefixes = new HashMap<>();
	private String defaultPackage = "";

	public final NameSet fromNS, toNS;

	public Mapping(NameSet fromNS, NameSet toNS) {
		this.fromNS = fromNS;
		this.toNS = toNS;
	}

	public void setClass(String in, String out) {
		classes.put(in, out);
	}

	public void setMethod(String clazz, String name, String desc, String out) {
		methods.put(clazz + "/" + name + desc, out);
	}

	public void setField(String clazz, String name, String out) {
		fields.put(clazz + "/" + name, out);
	}

	public void setExceptions(String clazz, String method, String desc, List<String> exc) {
		exceptions.put(clazz + "/" + method + desc, exc);
	}

	public String getClass(String in) {
		if(in == null) {
			return null;
		}
		if(in.startsWith("[")) {
			return "[" + getClass(in.substring(1));
		}
		if(in.startsWith("L") && in.endsWith(";")) {
			return "L" + getClass(in.substring(1, in.length() - 1)) + ";";
		}

		if(in.length() == 1) {
			switch(in.charAt(0)) {
				case 'D':
				case 'Z':
				case 'B':
				case 'C':
				case 'S':
				case 'I':
				case 'J':
				case 'F':
					return in;
				default:
					break;
			}
		}

		String ret = classes.get(in);
		if(ret != null) {
			return ret;
		}
		for(Map.Entry<String, String> e : classPrefixes.entrySet()) {
			if(in.startsWith(e.getKey())) {
				return e.getValue() + in.substring(e.getKey().length());
			}
		}
		if(!in.contains("/")) {
			return defaultPackage + in;
		}

		// TODO TheAndrey start: Ремаппинг подклассов
		Matcher partMatcher = PATTERN_CLASS_PART.matcher(in);
		if(partMatcher.matches()) {
			String parent = getParentClassName(in);
			int part = Integer.parseInt(partMatcher.group(1));

			String mapped = classes.get(parent);
			if(mapped != null) {
				mapped += "$" + part;
				if(!mapped.equals(in)) {
					setClass(in, mapped); // сохраняем в маппинги для следующих обращений
//					System.out.println("Remapped part: " + in + " -> " + mapped);
					return mapped;
				}
			}
		}

		Matcher subClassMatcher = PATTERN_SUBCLASS.matcher(in);
		if(subClassMatcher.matches()) {
			String parent = in;
			while(parent != null) {
				parent = getParentClassName(parent);
				String mapped = classes.get(parent);
				if(mapped != null) {
					mapped += in.substring(parent.length());
					if(!mapped.equals(in)) {
						setClass(in, mapped); // сохраняем в маппинги для следующих обращений
//						System.out.println("Remapped part: " + in + " -> " + mapped);
						return mapped;
					}
				}
			}
		}
		// TODO TheAndrey end

		return in;
	}

	public String getMethod(String clazz, String name, String desc) {
		String ret = methods.get(clazz + "/" + name + desc);
		return ret == null ? name : ret;
	}

	public String getField(String clazz, String name, String desc) {
		String ret = fields.get(clazz + "/" + name);
		return ret == null ? name : ret;
	}

	public List<String> getExceptions(String clazz, String method, String desc) {
		List<String> ret = exceptions.get(clazz + "/" + method + desc);
		return ret == null ? Collections.<String>emptyList() : ret;
	}

	public void addPrefix(String old, String new_) {
		classPrefixes.put(old, new_);
	}

	// p must include trailing slash
	public void setDefaultPackage(String p) {
		defaultPackage = p;
	}

	public String parseTypes(String type, boolean generic, boolean method) {
		if(type == null) {
			return null;
		}
		int pos = 0, len = type.length(), l = type.indexOf('<');
		char c;
		StringBuilder out = new StringBuilder(len);
		do {
			switch((c = type.charAt(pos))) {
				case '(':
				case ')': if(!method) {
						break;
					}
				case 'V':
				case 'Z':
				case 'B':
				case 'C':
				case 'S':
				case 'I':
				case 'J':
				case 'F':
				case 'D':
				case '[':
				case '<':
				case '>':
					out.append(c);
					pos++;
					continue;
				case 'L': {
					out.append('L');
					char o = ';';
					int end = type.indexOf(';', pos);
					if((l > 0) & end > l) {
						end = l;
						o = '<';
						l = type.indexOf('<', l + 1);
					}
					final String obf = type.substring(pos + 1, end);
					out.append(getClass(obf)).append(o);
					pos = end + 1;
				}
				continue;
				default:
					if(!generic) {
						break;
					}
					out.append(c);
					pos++;
					continue;
			}
			throw new RuntimeException("Unknown character in descriptor: " + type.charAt(pos) + " (in " + type + ")");
		} while(pos < len);
		return out.toString();
	}

	public String mapMethodDescriptor(String desc) {
		// some basic sanity checks, doesn't ensure it's completely valid though
		if(desc.length() == 0 || desc.charAt(0) != '(' || desc.indexOf(")") < 1) {
			throw new IllegalArgumentException("Not a valid method descriptor: " + desc);
		}

		return parseTypes(desc, false, true);
	}

	public String mapTypeDescriptor(String in) {
		if(in.length() == 0) {
			throw new IllegalArgumentException("Not a valid type descriptor: " + in);
		}
		return parseTypes(in, false, false);
	}

	// TODO TheAndrey start: Добавленные методы
	/**
	 * Получить имя родительского класса, если это подкласс или часть. Каждый вызов позволяет подняться на ступень выше.
	 * @param name Имя класса, включая пакет
	 * @return Имя родительского класса. null если это класс верхнего уровня.
	 */
	public static String getParentClassName(String name) {
		// Отделяем пакет от имени класса
		int pointPos = name.lastIndexOf("/");
		String packageName = null;
		String className = name;
		if(pointPos >= 0) {
			packageName = name.substring(0, pointPos);
			className = name.substring(pointPos + 1);
		}

		int sepPos = className.lastIndexOf("$");
		if(sepPos < 0) return null; // это класс верхнего уровня
		String parentName = className.substring(0, sepPos);
		if(packageName != null) parentName = packageName + "/" + parentName;
		return parentName;
	}
	// TODO TheAndrey end

}
