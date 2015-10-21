package immibis.bon.cui;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.List;

public abstract class CUIBase {

	@Target(ElementType.FIELD)
	@Retention(RetentionPolicy.RUNTIME)
	public static @interface Option {

		String value();

	}

	@Target(ElementType.FIELD)
	@Retention(RetentionPolicy.RUNTIME)
	public static @interface Required {

	}

	protected abstract void showUsage() throws Exception;

	protected abstract void run() throws Exception;

	private Field getOptionField(String name) {
		for(Field f : getClass().getFields()) {
			Option o = f.getAnnotation(Option.class);
			if(o != null && o.value().equals(name)) {
				return f;
			}
		}
		return null;
	}

	private String ensureNext(String[] args, Int index, String o, String v) {
		if(v != null) {
			return v;
		}
		if(index.value >= args.length) {
			throw new IllegalArgumentException("Expected an argument for " + o);
		}
		return args[index.value++];
	}

	@SuppressWarnings("unchecked")
	private boolean parseOptions(String[] args) throws Exception {
		boolean ok = true;

		for(Int k = new Int(); k.value < args.length;) {
			String opt = args[k.value++];
			String val = null;
			if(opt.equalsIgnoreCase("--help") || opt.equalsIgnoreCase("-help") || opt.equalsIgnoreCase("-h")) {
				showUsage();
				return false;
			}

			Field f = getOptionField(opt);
			if(f == null) {
				String[] t = opt.split(" ", 2);
				f = getOptionField(t[0]);
				if(f == null) {
					System.err.println("Unknown option: " + opt);
					ok = false;
					continue;
				}
				opt = t[0];
				if(t.length == 2) {
					val = t[1];
				}
			}

			if(Enum.class.isAssignableFrom(f.getType())) {
				val = ensureNext(args, k, opt, val);
				try {
					f.set(this, Enum.valueOf(f.getType().asSubclass(Enum.class), val));
				} catch (EnumConstantNotPresentException e) {
					System.err.println("Invalid option for " + opt + ": " + val);
					System.err.println("Valid values: " + f.getType().getEnumConstants());
					ok = false;
					continue;
				}

			} else if(f.getType() == String.class) {
				val = ensureNext(args, k, opt, val);
				if(f.get(this) != null) {
					System.err.println("Option specified more than once: " + opt);
					ok = false;
				} else {
					f.set(this, val);
				}

			} else if(f.getType() == File.class) {
				val = ensureNext(args, k, opt, val);
				if(f.get(this) != null) {
					System.err.println("Option specified more than once: " + opt);
					ok = false;
				} else {
					f.set(this, new File(val));
				}

			} else if(f.getType() == List.class) {
				val = ensureNext(args, k, opt, val);
				((List<String>)f.get(this)).add(val);

			} else if(f.getType() == boolean.class) {
				f.setBoolean(this, !f.getBoolean(this));

			} else {
				System.err.println("BUG: Invalid option type " + f.getType().getName() + " for option field " + f.getName() + " for option " + opt);
				ok = false;
			}
		}

		return ok;
	}

	protected boolean checkOptions() throws Exception {
		boolean ok = true;
		for(Field f : getClass().getFields()) {
			if(f.isAnnotationPresent(Required.class) && f.get(this) == null) {
				System.err.println("Required option " + f.getAnnotation(Option.class).value() + " not present.");
				ok = false;
			}
		}
		if(!ok) {
			showUsage();
		}
		return ok;
	}

	protected void run(String[] args) throws Exception {
		if(args.length == 0) {
			showUsage();
		} else if(parseOptions(args) && checkOptions()) {
			run();
		}
	}

	private class Int {

		public int value;

		public Int() {
			this(0);
		}

		public Int(int v) {
			value = v;
		}

	}

}
