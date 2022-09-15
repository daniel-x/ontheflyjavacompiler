package de.a0h.ontheflyjavacompiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Suitable for compiling simple java source code and load it into the JVM, all
 * on the fly, i.e. without reading or writing files on the file system. This
 * class is a wrapper of the java compiler API.
 * 
 * <p>
 * Example:
 * 
 * <pre>
 * String code = "" + //
 * 		"public class MyClass implements Runnable {\n" + //
 * 		"\n" + //
 * 		"	String name;\n" + //
 * 		"\n" + //
 * 		"	public MyClass(String name) {\n" + //
 * 		"		this.name = name;\n" + //
 * 		"	}\n" + //
 * 		"\n" + //
 * 		"	public void run() {\n" + //
 * 		"		System.out.printf(\"Hello, %s!\\n\", name);\n" + //
 * 		"	}\n" + //
 * 		"}\n" + //
 * 		"";
 * 
 * System.out.println(code);
 * 
 * OnTheFlyJavaCompiler compiler = new OnTheFlyJavaCompiler();
 * Class<?> clazz = compiler.compile(code);
 * Runnable runnable = (Runnable) compiler.newInstance(clazz, "Jonny");
 * 
 * System.out.println("output: ");
 * runnable.run();
 * </pre>
 * </p>
 */
public class OnTheFlyJavaCompiler {

	public static void main(String[] args) {
		String code = "" + //
				"public class MyClass implements Runnable {\n" + //
				"\n" + //
				"	String name;\n" + //
				"\n" + //
				"	public MyClass(String name) {\n" + //
				"		this.name = name;\n" + //
				"	}\n" + //
				"\n" + //
				"	public void run() {\n" + //
				"		System.out.printf(\"Hello, %s!\\n\", name);\n" + //
				"	}\n" + //
				"}\n" + //
				"";

		System.out.println(code);

		OnTheFlyJavaCompiler compiler = new OnTheFlyJavaCompiler();
		Class<?> clazz = compiler.compile(code);
		Runnable runnable = (Runnable) compiler.newInstance(clazz, "Jonny");

		System.out.println("output: ");
		runnable.run();
	}

	public Class<?> compile(String source) {
		String className = extractFullyQualifiedClassName(source);

		return compile(className, source, true);
	}

	public Class<?> compile(String source, boolean ignoreWarnings) {
		String className = extractFullyQualifiedClassName(source);

		return compile(className, source, ignoreWarnings);
	}

	public Class<?> compile(String className, String source) {
		return compile(source, true);
	}

	public Class<?> compile(String className, String source, boolean ignoreWarnings) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

		DiagnosticCollector<JavaFileObject> diagnosticsCollector = new DiagnosticCollector<JavaFileObject>();

		JavaFileManager standardFileManager = compiler.getStandardFileManager(diagnosticsCollector, null, null);

		VirtualFileManager virtualFileManager = new VirtualFileManager(standardFileManager);

		VirtualSourceFile sourceFile = new VirtualSourceFile(className, source);

		CompilationTask task = compiler.getTask( //
				null, //
				virtualFileManager, //
				diagnosticsCollector, //
				null, //
				null, //
				Arrays.asList(sourceFile) //
		);

		boolean success = task.call();
		boolean hasRelevantDiagnostics = (!ignoreWarnings && !diagnosticsCollector.getDiagnostics().isEmpty());
		if (!success || hasRelevantDiagnostics) {
			String results = compilationResultsToString(diagnosticsCollector, success);
			throw new RuntimeException(results);
		}

		VirtualClassLoader classLoader = virtualFileManager.getClassLoader(null);
		Class<?> clazz;
		try {
			clazz = classLoader.findClass(sourceFile.getClassName());
		} catch (ClassNotFoundException e) {
			throw new RuntimeException("couldn't load class " + sourceFile.getClassName(), e);
		}

		return clazz;
	}

	protected String compilationResultsToString(DiagnosticCollector<JavaFileObject> diagnosticsCollector,
			boolean success) {
		StringBuilder result = new StringBuilder();

		result.append(success ? "build succeeded\n" : "build failed\n");

		for (Diagnostic<? extends JavaFileObject> dia : diagnosticsCollector.getDiagnostics()) {
			String msg = "" + // Unit
					dia.getKind() + ": " + dia.getMessage(null) + " " + //
					"at " + dia.getLineNumber() + ":" + dia.getColumnNumber() + " " + //
					"(position: " + dia.getStartPosition() + "): " + dia.getCode();

			result.append(msg).append("\n");
		}

		return result.toString();
	}

	public <T> T newInstance(Class<? extends T> clazz, Object... params) {
		T result;

		try {
			Class<?>[] types = new Class<?>[params.length];
			for (int i = 0; i < params.length; i++) {
				types[i] = params[i].getClass();
			}

			Constructor<? extends T> constructor = clazz.getConstructor(types);
			result = constructor.newInstance(params);

		} catch (InstantiationException e) {
			throw new RuntimeException(e);

		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);

		} catch (IllegalArgumentException e) {
			throw new RuntimeException(e);

		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);

		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);

		} catch (SecurityException e) {
			throw new RuntimeException(e);

		}

		return result;
	}

	public static String extractFullyQualifiedClassName(String source) {
		String simpleClassName = extractClassName(source);

		if (simpleClassName == null) {
			throw new IllegalArgumentException("no class declaration found in source");
		}

		String packageName = extractPackageName(source);

		if (packageName != null) {
			simpleClassName = packageName + '.' + simpleClassName;
		}

		return simpleClassName;
	}

	protected static String extractPackageName(String source) {
		return getGroup1(source, "package\\s+([^\\s;]+)");
	}

	protected static String extractClassName(String source) {
		return getGroup1(source, "\\sclass\\s+([^\\s\\{]+)");
	}

	protected static String getGroup1(String str, String pattern) {
		Matcher matcher = Pattern.compile(pattern).matcher(str);

		String result;
		if (matcher.find()) {
			result = matcher.group(1);
		} else {
			result = null;
		}

		return result;
	}
}

class VirtualSourceFile extends SimpleJavaFileObject {

	String content;

	String className;

	VirtualSourceFile(String className, String content) {
		super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
		this.className = className;
		this.content = content;
	}

	public String getClassName() {
		return className;
	}

	@Override
	public CharSequence getCharContent(boolean ignoreEncodingErrors) {
		return content;
	}
}

class VirtualClassFile extends SimpleJavaFileObject {

	byte[] content = null;

	String className;

	ByteArrayOutputStream bytecodeCollector = new ByteArrayOutputStream(8192) {

		@Override
		public void close() throws IOException {
			content = toByteArray();
			super.close();

			bytecodeCollector = null;
		}

	};

	VirtualClassFile(String className) {
		super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);

		this.className = className;
	}

	public OutputStream openOutputStream() {
		return bytecodeCollector;
	}
}

class VirtualFileManager extends ForwardingJavaFileManager<JavaFileManager> {

	VirtualClassLoader virtualClassLoader = new VirtualClassLoader();

	protected VirtualFileManager(JavaFileManager fileManager) {
		super(fileManager);
	}

	@Override
	public VirtualClassLoader getClassLoader(Location location) {
		return virtualClassLoader;
	}

	@Override
	public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling)
			throws IOException {
		VirtualClassFile classFile = new VirtualClassFile(className);

		virtualClassLoader.addVirtualClassFile(classFile);

		return classFile;
	}
}

class VirtualClassLoader extends ClassLoader {

	HashMap<String, VirtualClassFile> classNameToBytecodeMap = new HashMap<>();

	public void addVirtualClassFile(VirtualClassFile classFile) {
		classNameToBytecodeMap.put(classFile.className, classFile);
	}

	@Override
	public Class<?> findClass(String moduleName, String className) {
		try {
			return findClass(className);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Class<?> findClass(String className) throws ClassNotFoundException {
		byte[] data = loadClassData(className);

		Class<?> result = super.defineClass(className, data, 0, data.length);

		return result;
	}

	private byte[] loadClassData(String className) throws ClassNotFoundException {
		VirtualClassFile classFile = classNameToBytecodeMap.get(className);

		byte[] result;
		if (classFile != null) {
			result = classFile.content;

			if (result == null) {
				throw new IllegalStateException("bytecode not completed yet");
			}

		} else {
			throw new ClassNotFoundException(className);
		}

		return result;
	}

//	public static void printStackTrace(String message) {
//		printStackTraceImpl(message, System.out);
//	}
//
//	public static void printStackTrace(String message, PrintStream out) {
//		printStackTraceImpl(message, out);
//	}
//
//	public static void printStackTraceImpl(String message, PrintStream out) {
//		StackTraceElement[] trace = Thread.currentThread().getStackTrace();
//
//		System.out.println(trace[3] + ": " + message);
//		for (int i = 4; i < trace.length; i++) {
//			System.out.println("    " + trace[i]);
//		}
//	}

}