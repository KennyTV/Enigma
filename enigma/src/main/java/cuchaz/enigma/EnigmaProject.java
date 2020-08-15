package cuchaz.enigma;

import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import cuchaz.enigma.analysis.ClassCache;
import cuchaz.enigma.analysis.EntryReference;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.api.service.NameProposalService;
import cuchaz.enigma.bytecode.translators.SourceFixVisitor;
import cuchaz.enigma.bytecode.translators.TranslationClassVisitor;
import cuchaz.enigma.source.Decompiler;
import cuchaz.enigma.source.DecompilerService;
import cuchaz.enigma.source.Decompilers;
import cuchaz.enigma.source.SourceSettings;
import cuchaz.enigma.translation.ProposingTranslator;
import cuchaz.enigma.translation.Translator;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.MappingsChecker;
import cuchaz.enigma.translation.mapping.tree.DeltaTrackingTree;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.utils.I18n;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

public class EnigmaProject {
	private final Enigma enigma;

	private final ClassCache classCache;
	private final JarIndex jarIndex;
	private final byte[] jarChecksum;

	private EntryRemapper mapper;

	public EnigmaProject(Enigma enigma, ClassCache classCache, JarIndex jarIndex, byte[] jarChecksum) {
		Preconditions.checkArgument(jarChecksum.length == 20);
		this.enigma = enigma;
		this.classCache = classCache;
		this.jarIndex = jarIndex;
		this.jarChecksum = jarChecksum;

		this.mapper = EntryRemapper.empty(jarIndex);
	}

	public void setMappings(EntryTree<EntryMapping> mappings) {
		if (mappings != null) {
			mapper = EntryRemapper.mapped(jarIndex, mappings);
		} else {
			mapper = EntryRemapper.empty(jarIndex);
		}
	}

	public Enigma getEnigma() {
		return enigma;
	}

	public ClassCache getClassCache() {
		return classCache;
	}

	public JarIndex getJarIndex() {
		return jarIndex;
	}

	public byte[] getJarChecksum() {
		return jarChecksum;
	}

	public EntryRemapper getMapper() {
		return mapper;
	}

	public void dropMappings(ProgressListener progress) {
		DeltaTrackingTree<EntryMapping> mappings = mapper.getObfToDeobf();

		Collection<Entry<?>> dropped = dropMappings(mappings, progress);
		for (Entry<?> entry : dropped) {
			mappings.trackChange(entry);
		}
	}

	private Collection<Entry<?>> dropMappings(EntryTree<EntryMapping> mappings, ProgressListener progress) {
		// drop mappings that don't match the jar
		MappingsChecker checker = new MappingsChecker(jarIndex, mappings);
		MappingsChecker.Dropped dropped = checker.dropBrokenMappings(progress);

		Map<Entry<?>, String> droppedMappings = dropped.getDroppedMappings();
		for (Map.Entry<Entry<?>, String> mapping : droppedMappings.entrySet()) {
			System.out.println("WARNING: Couldn't find " + mapping.getKey() + " (" + mapping.getValue() + ") in jar. Mapping was dropped.");
		}

		return droppedMappings.keySet();
	}

	public Decompiler createDecompiler(DecompilerService decompilerService) {
		return decompilerService.create(name -> {
			ClassNode node = this.getClassCache().getClassNode(name);

			if (node == null) {
				return null;
			}

			ClassNode fixedNode = new ClassNode();
			node.accept(new SourceFixVisitor(Enigma.ASM_VERSION, fixedNode, getJarIndex()));
			return fixedNode;
		}, new SourceSettings(true, true));
	}

	public boolean isRenamable(Entry<?> obfEntry) {
		if (obfEntry instanceof MethodEntry) {
			// HACKHACK: Object methods are not obfuscated identifiers
			MethodEntry obfMethodEntry = (MethodEntry) obfEntry;
			String name = obfMethodEntry.getName();
			String sig = obfMethodEntry.getDesc().toString();
			if (name.equals("clone") && sig.equals("()Ljava/lang/Object;")) {
				return false;
			} else if (name.equals("equals") && sig.equals("(Ljava/lang/Object;)Z")) {
				return false;
			} else if (name.equals("finalize") && sig.equals("()V")) {
				return false;
			} else if (name.equals("getClass") && sig.equals("()Ljava/lang/Class;")) {
				return false;
			} else if (name.equals("hashCode") && sig.equals("()I")) {
				return false;
			} else if (name.equals("notify") && sig.equals("()V")) {
				return false;
			} else if (name.equals("notifyAll") && sig.equals("()V")) {
				return false;
			} else if (name.equals("toString") && sig.equals("()Ljava/lang/String;")) {
				return false;
			} else if (name.equals("wait") && sig.equals("()V")) {
				return false;
			} else if (name.equals("wait") && sig.equals("(J)V")) {
				return false;
			} else if (name.equals("wait") && sig.equals("(JI)V")) {
				return false;
			}
		} else if (obfEntry instanceof LocalVariableEntry && !((LocalVariableEntry) obfEntry).isArgument()) {
			return false;
		}

		return this.jarIndex.getEntryIndex().hasEntry(obfEntry);
	}

	public boolean isRenamable(EntryReference<Entry<?>, Entry<?>> obfReference) {
		return obfReference.isNamed() && isRenamable(obfReference.getNameableEntry());
	}

	public JarExport exportRemappedJar(ProgressListener progress) {
		Collection<ClassEntry> classEntries = jarIndex.getEntryIndex().getClasses();

		NameProposalService[] nameProposalServices = getEnigma().getServices().get(NameProposalService.TYPE).toArray(new NameProposalService[0]);
		Translator deobfuscator = nameProposalServices.length == 0 ? mapper.getDeobfuscator() : new ProposingTranslator(mapper, nameProposalServices);

		AtomicInteger count = new AtomicInteger();
		progress.init(classEntries.size(), I18n.translate("progress.classes.deobfuscating"));

		Map<String, ClassNode> compiled = classEntries.parallelStream()
				.map(entry -> {
					ClassEntry translatedEntry = deobfuscator.translate(entry);
					progress.step(count.getAndIncrement(), translatedEntry.toString());

					ClassNode node = classCache.getClassNode(entry.getFullName());
					if (node != null) {
						ClassNode translatedNode = new ClassNode();
						node.accept(new TranslationClassVisitor(deobfuscator, Enigma.ASM_VERSION, new SourceFixVisitor(Enigma.ASM_VERSION, translatedNode, jarIndex)));
						return translatedNode;
					}

					return null;
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toMap(n -> n.name, Functions.identity()));

		return new JarExport(jarIndex, compiled);
	}

	public static final class JarExport {
		private final JarIndex jarIndex;
		private final Map<String, ClassNode> compiled;

		JarExport(JarIndex jarIndex, Map<String, ClassNode> compiled) {
			this.jarIndex = jarIndex;
			this.compiled = compiled;
		}

		public void write(Path path, ProgressListener progress) throws IOException {
			progress.init(this.compiled.size(), I18n.translate("progress.jar.writing"));

			try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
				AtomicInteger count = new AtomicInteger();

				for (ClassNode node : this.compiled.values()) {
					progress.step(count.getAndIncrement(), node.name);

					String entryName = node.name.replace('.', '/') + ".class";

					ClassWriter writer = new ClassWriter(0);
					node.accept(writer);

					out.putNextEntry(new JarEntry(entryName));
					out.write(writer.toByteArray());
					out.closeEntry();
				}
			}
		}

		public SourceExport decompile(ProgressListener progress, DecompilerService decompilerService) {
			Collection<ClassNode> classes = this.compiled.values().stream()
					.filter(classNode -> classNode.name.indexOf('$') == -1)
					.collect(Collectors.toList());

			progress.init(classes.size(), I18n.translate("progress.classes.decompiling"));

			//create a common instance outside the loop as mappings shouldn't be changing while this is happening
			Decompiler decompiler = decompilerService.create(compiled::get, new SourceSettings(false, false));
            Decompiler altDecompiler = Decompilers.CFR.create(compiled::get, new SourceSettings(false, false));

			AtomicInteger count = new AtomicInteger();

            Collection<ClassSource> decompiled = new ArrayList<>(classes.size());
            classes.parallelStream().forEach(translatedNode -> {
                try {
                    String source = decompileClass(translatedNode, decompiler);
                    decompiled.add(new ClassSource(translatedNode.name, source));
                } catch (Throwable e) {
                    try {
                        String source = decompileClass(translatedNode, altDecompiler);
                        decompiled.add(new ClassSource(translatedNode.name, source));
                    } catch (Throwable e2) {
                        System.err.println("Error decompiling " + translatedNode.name);
                    }
                }
                progress.step(count.getAndIncrement(), translatedNode.name);
            });

			return new SourceExport(decompiled);
		}

		private String decompileClass(ClassNode translatedNode, Decompiler decompiler) {
			return decompiler.getSource(translatedNode.name).asString();
		}
	}

	public static final class SourceExport {
		private final Collection<ClassSource> decompiled;

		SourceExport(Collection<ClassSource> decompiled) {
			this.decompiled = decompiled;
		}

		public void write(Path path, ProgressListener progress) throws IOException {
			progress.init(decompiled.size(), I18n.translate("progress.sources.writing"));

			int count = 0;
			for (ClassSource source : decompiled) {
				progress.step(count++, source.name);

				Path sourcePath = source.resolvePath(path);
				source.writeTo(sourcePath);
			}
		}
	}

	private static class ClassSource {
		private final String name;
		private final String source;

		ClassSource(String name, String source) {
			this.name = name;
			this.source = source;
		}

		void writeTo(Path path) throws IOException {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = Files.newBufferedWriter(path)) {
				writer.write(source);
			}
		}

		Path resolvePath(Path root) {
			return root.resolve(name.replace('.', '/') + ".java");
		}
	}
}
