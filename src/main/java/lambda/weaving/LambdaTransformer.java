package lambda.weaving;

import static java.lang.System.*;
import static org.objectweb.asm.Type.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lambda.LambdaParameter;
import lambda.NewLambda;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.EmptyVisitor;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.util.AbstractVisitor;

class LambdaTransformer implements Opcodes {
	static boolean DEBUG = true;

	static Map<String, byte[]> lambdasByName = new HashMap<String, byte[]>();

	static Method findMethod(String owner, String name, String desc) throws NoSuchMethodException, ClassNotFoundException {
		Class<?>[] argumentClasses = new Class[getArgumentTypes(desc).length];
		Arrays.fill(argumentClasses, Object.class);
		return Class.forName(getObjectType(owner).getClassName()).getMethod(name, argumentClasses);
	}

	static Field findField(String owner, String name) throws NoSuchFieldException, ClassNotFoundException {
		String className = getObjectType(owner).getClassName();
		return Class.forName(className).getDeclaredField(name);
	}


	static class MethodInfo {
		String name;
		String desc;

		public MethodInfo(String name, String desc) {
			this.name = name;
			this.desc = desc;
		}

		String getFullName() {
			return name + desc;
		}

		Map<Integer, LocalInfo> accessedLocalsByIndex = new HashMap<Integer, LocalInfo>();
		List<LambdaInfo> lambdas = new ArrayList<LambdaInfo>();

		void accessLocalFromLambda(int operand) {
			LocalInfo local = accessedLocalsByIndex.get(operand);
			if (local == null) {
				local = new LocalInfo();
				accessedLocalsByIndex.put(operand, local);
			}
			currentLambda().accessedLocals.add(local);
		}

		void newLambda() {
			lambdas.add(new LambdaInfo());
		}

		void setLambdaArity(int arity) {
			currentLambda().arity = arity;
		}

		LambdaInfo currentLambda() {
			return lambdas.get(lambdas.size() - 1);
		}

		void setTypeOfLocal(int index, Type type) {
			LocalInfo localInfo = accessedLocalsByIndex.get(index);
			if (localInfo != null)
				localInfo.type = type;
		}

		static class LocalInfo {
			int index;
			Type type;
		}

		static class LambdaInfo {
			int arity;
			Set<LocalInfo> accessedLocals = new HashSet<LocalInfo>();
		}
	}

	static class FirstPassClassVisitor extends ClassAdapter {
		List<Integer> arities = new ArrayList<Integer>();
		Map<String, Type> accessedLocals = new HashMap<String, Type>();
		List<Set<Integer>> lambdaLocals = new ArrayList<Set<Integer>>();

		Map<String, MethodInfo> methodsByName = new HashMap<String, MethodInfo>();

		boolean inLambda;
		MethodInfo currentMethod;

		FirstPassClassVisitor() {
			super(new EmptyVisitor());
		}

		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			currentMethod = new MethodInfo(name, desc);
			methodsByName.put(currentMethod.getFullName(), currentMethod);
			
			MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
			return new MethodAdapter(mv) {
				public void visitFieldInsn(int opcode, String owner, String name, String desc) {
					try {
						Field field = findField(owner, name);
						if (!inLambda && field.isAnnotationPresent(LambdaParameter.class)) {
							inLambda = true;
							currentMethod.newLambda();

							lambdaLocals.add(new HashSet<Integer>());
						}
					} catch (NoSuchFieldException ignore) {
						// debug(ignore);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}

				public void visitVarInsn(int opcode, int operand) {
					if (inLambda) {
						String key = currentMethod.getFullName() + "." + operand;

						currentMethod.accessLocalFromLambda(operand);

						lambdaLocals.get(lambdaLocals.size() - 1).add(operand);
						accessedLocals.put(key, getType(Object.class));
					}
					super.visitIntInsn(opcode, operand);
				}

				public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
					String key = currentMethod.getFullName() + "." + index;
					if (accessedLocals.containsKey(key))
						accessedLocals.put(key, getType(desc));

					currentMethod.setTypeOfLocal(index, getType(desc));

					super.visitLocalVariable(name, desc, signature, start, end, index);
				}

				public void visitMethodInsn(int opcode, String owner, String name, String desc) {
					try {
						Method method = findMethod(owner, name, desc);
						if (method.isAnnotationPresent(NewLambda.class)) {
							arities.add(method.getParameterTypes().length - 1);

							currentMethod.setLambdaArity(method.getParameterTypes().length - 1);
							inLambda = false;
						}
					} catch (NoSuchMethodException ignore) {
						// debug(ignore);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			};
		}

		boolean hasNoLambdas() {
			for (MethodInfo method : methodsByName.values()) {
				if (!method.lambdas.isEmpty())
					return false;
			}
			return true;
		}
	}

	static class SecondPassClassVisitor extends ClassAdapter {
		String source;
		String className;

		Iterator<Integer> arities;
		Iterator<Set<Integer>> lambdaLocals;
		Map<String, Type> accessedLocals;
		Map<String, MethodInfo> methodsByName;

		int currentLambdaId;

		class LambdaMethodVisitor extends GeneratorAdapter {
			static final String LAMBDA_CLASS_PREFIX = "Fn";

			MethodVisitor originalMethodWriter;
			ClassWriter lambdaWriter;

			Map<String, Integer> paremeterNamesToIndex = new LinkedHashMap<String, Integer>();

			int currentLine;

			int currentArity;
			Set<Integer> currentLambdaLocals;

			Set<String> initializedLocals = new HashSet<String>();

			MethodInfo method;

			private LambdaMethodVisitor(MethodVisitor mv, int access, String methodName, String desc) {
				super(mv, access, methodName, desc);
				method = methodsByName.get(methodName + desc);

				this.originalMethodWriter = mv;
			}

			public void visitFieldInsn(int opcode, String owner, String name, String desc) {
				try {
					String className = getObjectType(owner).getClassName();
					Field field = Class.forName(className).getDeclaredField(name);
					boolean isLambdaParameter = field.isAnnotationPresent(LambdaParameter.class);
					if (!inLambda() && isLambdaParameter) {
						currentArity = arities.next();
						currentLambdaLocals = lambdaLocals.next();
						debug("starting new lambda with arity " + currentArity + " locals " + currentLambdaLocals);

						createLambdaClass();
						createLambdaConstructor();

						createCallMethodAndRedirectMethodVisitorToIt();
					}
					if (isLambdaParameter) {
						if (!paremeterNamesToIndex.containsKey(name)) {
							initLambdaParameter(name);
						} else {
							int index = paremeterNamesToIndex.get(name);
							debug("accessing lambda parameter " + field + " with index " + index);
							accessLambdaParameter(field, index);
						}
					} else {
						super.visitFieldInsn(opcode, owner, name, desc);
					}
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}

			public void visitMethodInsn(int opcode, String owner, String name, String desc) {
				try {
					if (inLambda() && notAConstructor(name)) {
						Method method = findMethod(owner, name, desc);
						if (method.isAnnotationPresent(NewLambda.class)) {
							debug("new lambda created by " + method + " in " + sourceAndLine());

							returnFromCall();
							endLambdaClass();

							restoreOriginalMethodWriterAndInstantiateNewLambda();
							return;
						}
					}
				} catch (NoSuchMethodException ignore) {
					// debug(ignore);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				super.visitMethodInsn(opcode, owner, name, desc);
			}

			public void visitVarInsn(int opcode, int operand) {
				String key = method.getFullName() + "." + operand;
				if (accessedLocals.containsKey(key)) {
					Type type = accessedLocals.get(key);

					if (!initializedLocals.contains(key)) {
						initializedLocals.add(key);
						mv.visitInsn(ICONST_1);
						newArray(type);
						mv.visitVarInsn(ASTORE, operand);
					}

					if (inLambda()) {
						mv.visitVarInsn(ALOAD, 0);
						mv.visitFieldInsn(GETFIELD, currentLambdaClass(), lambdaFieldNameForLocal(operand), getDescriptor(Object.class));
						mv.visitTypeInsn(CHECKCAST, "[" + type.getDescriptor());
					} else {
						mv.visitVarInsn(ALOAD, operand);
					}

					int arrayOpcode;
					if (opcode >= ISTORE && opcode <= ASTORE) {
						mv.visitInsn(SWAP);
						mv.visitInsn(ICONST_0);
						mv.visitInsn(SWAP);
						arrayOpcode = type.getOpcode(IASTORE);
						mv.visitInsn(arrayOpcode);
					} else {
						mv.visitInsn(ICONST_0);
						arrayOpcode = type.getOpcode(IALOAD);
						mv.visitInsn(arrayOpcode);
					}
					debug("variable " + key + "(" + type + ") accessed using wrapped array " + AbstractVisitor.OPCODES[opcode] + " -> "
							+ AbstractVisitor.OPCODES[arrayOpcode]
							+ (inLambda() ? " field in " + currentLambdaClass() + "." + lambdaFieldNameForLocal(operand) : " local"));
				} else {
					super.visitIntInsn(opcode, operand);
				}
			}

			public void visitLineNumber(int line, Label start) {
				super.visitLineNumber(line, start);
				currentLine = line;
			}

			public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
				String key = method.getFullName() + "." + index;
				if (accessedLocals.containsKey(key)) {
					desc = getDescriptor(Object.class);
				}
				super.visitLocalVariable(name, desc, signature, start, end, index);
			}

			boolean notAConstructor(String name) {
				return !name.startsWith("<");
			}

			void createLambdaClass() {
				nextLambdaId();

				lambdaWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				lambdaWriter.visit(V1_5, ACC_PUBLIC, currentLambdaClass(), null, getInternalName(Object.class), new String[] { "lambda/"
						+ LAMBDA_CLASS_PREFIX + +currentArity });
				lambdaWriter.visitOuterClass(className, method.name, method.desc);
			}

			void createCallMethodAndRedirectMethodVisitorToIt() {
				String arguments = "";
				for (int i = 0; i < currentArity; i++)
					arguments += getDescriptor(Object.class);
				mv = lambdaWriter.visitMethod(ACC_PUBLIC, "call", "(" + arguments + ")" + getDescriptor(Object.class), null, null);
				mv.visitCode();
			}

			void createLambdaConstructor() {
				String parameters = "";
				for (Integer local : currentLambdaLocals) {
					parameters += getDescriptor(Object.class);
					lambdaWriter.visitField(ACC_PRIVATE + ACC_FINAL + ACC_SYNTHETIC, "val$" + local, getDescriptor(Object.class), null,
							null).visitEnd();
				}

				mv = lambdaWriter.visitMethod(ACC_PUBLIC, "<init>", "(" + parameters + ")V", null, null);
				mv.visitCode();
				int i = 1;
				for (int local : currentLambdaLocals) {
					mv.visitVarInsn(ALOAD, 0);
					mv.visitVarInsn(ALOAD, i++);
					mv.visitFieldInsn(PUTFIELD, currentLambdaClass(), lambdaFieldNameForLocal(local), getDescriptor(Object.class));
				}

				mv.visitVarInsn(ALOAD, 0);
				mv.visitMethodInsn(INVOKESPECIAL, getInternalName(Object.class), "<init>", "()V");
				mv.visitInsn(RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}

			String lambdaFieldNameForLocal(int local) {
				return "val$" + local;
			}

			void returnFromCall() {
				mv.visitInsn(ARETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}

			void restoreOriginalMethodWriterAndInstantiateNewLambda() {
				mv = originalMethodWriter;
				mv.visitTypeInsn(NEW, currentLambdaClass());
				mv.visitInsn(DUP);

				String parameters = "";
				for (int local : currentLambdaLocals) {
					parameters += getDescriptor(Object.class);
					mv.visitVarInsn(ALOAD, local);
				}
				mv.visitMethodInsn(INVOKESPECIAL, currentLambdaClass(), "<init>", "(" + parameters + ")V");
			}

			void endLambdaClass() {
				lambdaWriter.visitEnd();
				byte[] bs = lambdaWriter.toByteArray();
				String resource = currentLambdaClass() + ".class";
				lambdasByName.put(resource, bs);
				cv.visitInnerClass(currentLambdaClass(), className,
						currentLambdaClass().substring(currentLambdaClass().indexOf('$') + 1), ACC_PUBLIC);

				ClassInjector injector = new ClassInjector();
				if (DEBUG)
					injector.dump(resource, bs);
				injector.inject(ClassLoader.getSystemClassLoader(), currentLambdaClass().replace('/', '.'), bs);

				lambdaWriter = null;
				paremeterNamesToIndex.clear();
			}

			private void initLambdaParameter(String name) {
				if (paremeterNamesToIndex.size() == currentArity) {
					throw new IllegalArgumentException("Tried to access a unbound parameter [" + name + "] valid ones are "
							+ paremeterNamesToIndex.keySet() + " " + sourceAndLine());
				}
				paremeterNamesToIndex.put(name, paremeterNamesToIndex.size() + 1);
			}

			void accessLambdaParameter(Field field, int parameter) {
				if (paremeterNamesToIndex.size() != currentArity) {
					throw new IllegalArgumentException("Parameter already bound [" + field.getName() + "] " + sourceAndLine());
				}
				mv.visitVarInsn(ALOAD, parameter);
				mv.visitTypeInsn(CHECKCAST, getInternalName(field.getType()));
			}

			String sourceAndLine() {
				return ("(" + source + ":" + currentLine + ")");
			}

			void nextLambdaId() {
				currentLambdaId++;
			}

			String currentLambdaClass() {
				return (className + "$" + LAMBDA_CLASS_PREFIX + currentArity + "_" + currentLambdaId);
			}

			boolean inLambda() {
				return lambdaWriter != null;
			}
		}

		SecondPassClassVisitor(ClassVisitor cv, FirstPassClassVisitor firstPass) {
			super(cv);
			this.methodsByName = firstPass.methodsByName;
			this.accessedLocals = firstPass.accessedLocals;
			this.lambdaLocals = firstPass.lambdaLocals.iterator();
			this.arities = firstPass.arities.iterator();
		}

		public void visitSource(String source, String debug) {
			super.visitSource(source, debug);
			this.source = source;
		}

		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			super.visit(version, access, name, signature, superName, interfaces);
			this.className = name;
		}

		public MethodVisitor visitMethod(int access, final String name, String desc, String signature, String[] exceptions) {
			MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
			debug("Second pass: processing method " + name + desc);
			return new LambdaMethodVisitor(mv, access, name, desc);
		}
	}

	byte[] transform(String resource, InputStream in) throws IOException {
		if (lambdasByName.containsKey(resource)) {
			debug("generated lambda was requested by the class loader " + resource);
			return lambdasByName.get(resource);
		}

		ClassReader cr = new ClassReader(in);

		debug("First pass: alanyzing use of lambdas in " + resource);
		FirstPassClassVisitor firstPass = new FirstPassClassVisitor();
		cr.accept(firstPass, 0);

		if (firstPass.hasNoLambdas()) {
			debug("First pass: no lambdas in " + resource);
			return null;
		}

		debug("Second pass: transforming lambdas and accessed locals in " + resource);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		SecondPassClassVisitor visitor = new SecondPassClassVisitor(cw, firstPass);
		cr.accept(visitor, 0);

		return cw.toByteArray();
	}

	static void debug(Object msg) {
		if (DEBUG)
			err.println(msg);
	}

}
