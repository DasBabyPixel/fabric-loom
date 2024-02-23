/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018-2023 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.mods;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Usage;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.RemapConfigurationSettings;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.mods.dependency.ModDependency;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.extension.RemapperExtensionHolder;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.kotlin.KotlinClasspathService;
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader;
import net.fabricmc.loom.util.service.SharedServiceManager;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

public class ModProcessor {
	private static final String fromM = MappingsNamespace.INTERMEDIARY.toString();
	private static final String toM = MappingsNamespace.NAMED.toString();

	private static final Pattern COPY_CONFIGURATION_PATTERN = Pattern.compile("^(.+)Copy[0-9]*$");

	private final Project project;
	private final Configuration sourceConfiguration;
	private final SharedServiceManager serviceManager;

	public ModProcessor(Project project, Configuration sourceConfiguration, SharedServiceManager serviceManager) {
		this.project = project;
		this.sourceConfiguration = sourceConfiguration;
		this.serviceManager = serviceManager;
	}

	public void processMods(List<ModDependency> remapList) throws IOException {
		try {
			project.getLogger().lifecycle(":remapping {} mods from {}", remapList.size(), describeConfiguration(sourceConfiguration));
			remapJars(remapList);
		} catch (Exception e) {
			throw new RuntimeException(String.format(Locale.ENGLISH, "Failed to remap %d mods", remapList.size()), e);
		}
	}

	// Creates a human-readable descriptive string for the configuration.
	// This consists primarily of the name with any copy suffixes stripped
	// (they're not informative), and the usage attribute if present.
	private String describeConfiguration(Configuration configuration) {
		String description = configuration.getName();
		final Matcher copyMatcher = COPY_CONFIGURATION_PATTERN.matcher(description);

		// If we find a copy suffix, remove it.
		if (copyMatcher.matches()) {
			final String realName = copyMatcher.group(1);

			// It's only a copy if we find a non-copy version.
			if (project.getConfigurations().findByName(realName) != null) {
				description = realName;
			}
		}

		// Add the usage if present, e.g. "modImplementation (java-api)"
		final Usage usage = configuration.getAttributes().getAttribute(Usage.USAGE_ATTRIBUTE);

		if (usage != null) {
			description += " (" + usage.getName() + ")";
		}

		return description;
	}

	private CreateRemapper createRemapper(LoomGradleExtension extension, List<ModDependency> remapList) throws IOException {
		final MappingConfiguration mappingConfiguration = extension.getMappingConfiguration();

		Set<String> knownIndyBsms = new HashSet<>(extension.getKnownIndyBsms().get());

		for (ModDependency modDependency : remapList) {
			knownIndyBsms.addAll(modDependency.getMetadata().knownIdyBsms());
		}

		TinyRemapper.Builder builder = TinyRemapper.newRemapper().withKnownIndyBsm(knownIndyBsms).withMappings(TinyRemapperHelper.create(mappingConfiguration.getMappingsService(serviceManager).getMappingTree(), fromM, toM, false)).renameInvalidLocals(false).extraAnalyzeVisitor(AccessWidenerAnalyzeVisitorProvider.createFromMods(fromM, remapList));

		final KotlinClasspathService kotlinClasspathService = KotlinClasspathService.getOrCreateIfRequired(serviceManager, project);
		KotlinRemapperClassloader kotlinRemapperClassloader = null;

		if (kotlinClasspathService != null) {
			kotlinRemapperClassloader = KotlinRemapperClassloader.create(kotlinClasspathService);
			builder.extension(kotlinRemapperClassloader.getTinyRemapperExtension());
		}

		final Set<InputTag> remapMixins = new HashSet<>();
		final boolean requiresStaticMixinRemap = remapList.stream().anyMatch(modDependency -> modDependency.getMetadata().mixinRemapType() == ArtifactMetadata.MixinRemapType.STATIC);

		if (requiresStaticMixinRemap) {
			// Configure the mixin extension to remap mixins from mod jars that were remapped with the mixin extension.
			builder.extension(new MixinExtension(remapMixins::contains));
		}

		for (RemapperExtensionHolder holder : extension.getRemapperExtensions().get()) {
			holder.apply(builder, fromM, toM, project.getObjects());
		}

		final TinyRemapper remapper = builder.build();

		return new CreateRemapper(remapper, remapMixins, kotlinRemapperClassloader, requiresStaticMixinRemap);
	}

	private ConfigureRemapper configureRemapper(LoomGradleExtension extension, List<ModDependency> remapList, CreateRemapper createRemapper) throws IOException {
		final TinyRemapper remapper = createRemapper.remapper();
		final Set<InputTag> remapMixins = createRemapper.remapMixins();

		remapper.readClassPath(extension.getMinecraftJars(MappingsNamespace.INTERMEDIARY).toArray(Path[]::new));

		final Map<ModDependency, InputTag> tagMap = new HashMap<>();
		final Map<InputTag, List<InputTag>> nestedMap = new HashMap<>();
		final Map<InputTag, OutputConsumerPath> outputConsumerMap = new HashMap<>();
		final Map<InputTag, Path> tempFiles = new HashMap<>();
		final Map<InputTag, String> tempFileNames = new HashMap<>();
		final Map<InputTag, String> nestedJarPaths = new HashMap<>();
		final Map<InputTag, Pair<byte[], String>> accessWidenerMap = new HashMap<>();

		for (RemapConfigurationSettings entry : extension.getRemapConfigurations()) {
			for (File inputFile : entry.getSourceConfiguration().get().getFiles()) {
				if (remapList.stream().noneMatch(info -> info.getInputFile().toFile().equals(inputFile))) {
					project.getLogger().debug("Adding " + inputFile + " onto the remap classpath");
					remapper.readClassPathAsync(inputFile.toPath());
				}
			}
		}

		for (ModDependency info : remapList) {
			InputTag tag = remapper.createInputTag();

			project.getLogger().debug("Adding " + info.getInputFile() + " as a remap input");

			// Note: this is done at a jar level, not at the level of an individual mixin config.
			// If a mod has multiple mixin configs, it's assumed that either all or none of them have refmaps.
			if (info.getMetadata().mixinRemapType() == ArtifactMetadata.MixinRemapType.STATIC) {
				if (!createRemapper.requiresStaticMixinRemap) {
					// Should be impossible but stranger things have happened.
					throw new IllegalStateException("Was not configured for static remap, but a mod required it?!");
				}

				project.getLogger().info("Remapping mixins in {} statically", info.getInputFile());
				remapMixins.add(tag);
			}

			remapper.readInputsAsync(tag, info.getInputFile());
			tagMap.put(info, tag);

			// Add transitive dependencies
			LinkedList<Pair<Path, InputTag>> pathsToCheck = new LinkedList<>();
			pathsToCheck.add(new Pair<>(info.getInputFile(), tag));

			while (!pathsToCheck.isEmpty()) {
				Pair<Path, InputTag> pair = pathsToCheck.removeFirst();
				Path path = pair.left();
				InputTag pathTag = pair.right();
				JsonObject json = ZipUtils.unpackGson(path, "fabric.mod.json", JsonObject.class);

				if (json.has("jars")) {
					JsonArray jars = json.getAsJsonArray("jars");

					for (JsonElement jarElement : jars) {
						JsonObject jar = jarElement.getAsJsonObject();
						String filePath = jar.getAsJsonPrimitive("file").getAsString();
						byte[] bytes = ZipUtils.unpack(path, filePath);
						Path tempFile = Files.createTempFile("fabric-loom-nested-jar", ".jar");
						Files.write(tempFile, bytes);

						InputTag nestedTag = remapper.createInputTag();
						pathsToCheck.add(new Pair<>(tempFile, nestedTag));
						tempFileNames.put(nestedTag, Paths.get(filePath).getFileName().toString());
						nestedJarPaths.put(nestedTag, filePath);
						tempFiles.put(nestedTag, tempFile);
						remapper.readInputsAsync(nestedTag, tempFile);

						nestedMap.computeIfAbsent(pathTag, t -> new ArrayList<>()).add(nestedTag);
					}
				}
			}

			Files.deleteIfExists(getRemappedOutput(info));
		}

		return new ConfigureRemapper(tagMap, nestedMap, outputConsumerMap, accessWidenerMap, tempFiles, tempFileNames, nestedJarPaths);
	}

	private void applyRemapperJar(LoomGradleExtension extension, TinyRemapper remapper, Path inputFile, Path destination, Path nestedPrefix, InputTag tag, ConfigureRemapper configureRemapper, Map<InputTag, Path> destinations) throws IOException {
		project.getLogger().info("Remapping {} to {}", inputFile, destination);
		destinations.put(tag, destination);
		OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(destination).build();

		outputConsumer.addNonClassFiles(inputFile, NonClassCopyMode.FIX_META_INF, remapper);
		configureRemapper.outputConsumerMap.put(tag, outputConsumer);

		final AccessWidenerUtils.AccessWidenerData accessWidenerData = AccessWidenerUtils.readAccessWidenerData(inputFile);

		if (accessWidenerData != null) {
			project.getLogger().debug("Remapping access widener in {}", inputFile);
			byte[] remappedAw = AccessWidenerUtils.remapAccessWidener(accessWidenerData.content(), remapper.getEnvironment().getRemapper());
			configureRemapper.accessWidenerMap.put(tag, new Pair<>(remappedAw, accessWidenerData.path()));
		}

		remapper.apply(outputConsumer, tag);

		if (configureRemapper.nestedMap.containsKey(tag)) {
			for (InputTag nestedTag : configureRemapper.nestedMap.get(tag)) {
				String fileName = configureRemapper.tempFileNames.get(nestedTag);
				Path nestedInputFile = configureRemapper.tempFiles.get(nestedTag);
				Path thisPrefix = nestedPrefix.resolve(fileName + "-jars");

				Path nestedDestination = extension.getFiles().getProjectBuildCache().toPath().resolve("remapped_working").resolve(nestedPrefix.resolve(fileName));
				applyRemapperJar(extension, remapper, nestedInputFile, nestedDestination, thisPrefix, nestedTag, configureRemapper, destinations);
			}
		}
	}

	private Map<InputTag, Path> applyRemapper(LoomGradleExtension extension, List<ModDependency> remapList, CreateRemapper createRemapper, ConfigureRemapper configureRemapper) throws IOException {
		final TinyRemapper remapper = createRemapper.remapper;
		final Map<ModDependency, InputTag> tagMap = configureRemapper.tagMap;
		final KotlinRemapperClassloader kotlinRemapperClassloader = createRemapper.kotlinRemapperClassloader;

		final Map<InputTag, Path> destinations = new HashMap<>();

		try {
			// Apply this in a second loop as we need to ensure all the inputs are on the classpath before remapping.
			for (ModDependency dependency : remapList) {
				try {
					applyRemapperJar(extension, remapper, dependency.getInputFile(), getRemappedOutput(dependency), dependency.getInputFile().getFileName(), tagMap.get(dependency), configureRemapper, destinations);
				} catch (Exception e) {
					throw new RuntimeException("Failed to remap: " + dependency, e);
				}
			}
		} finally {
			remapper.finish();

			if (kotlinRemapperClassloader != null) {
				kotlinRemapperClassloader.close();
			}

			for (Path path : configureRemapper.tempFiles.values()) {
				Files.delete(path);
			}
		}

		return destinations;
	}

	private void completeRemapping(List<ModDependency> remapList, ConfigureRemapper configureRemapper, Map<InputTag, Path> destinations) throws IOException {
		for (ModDependency dependency : remapList) {
			InputTag tag = configureRemapper.tagMap.get(dependency);
			configureRemapper.outputConsumerMap.get(tag).close();

			final Path output = destinations.get(tag);
			final Pair<byte[], String> accessWidener = configureRemapper.accessWidenerMap.get(tag);

			if (accessWidener != null) {
				ZipUtils.replace(output, accessWidener.right(), accessWidener.left());
			}

			completeNested(configureRemapper, tag, destinations);

			remapJarManifestEntries(output);
			dependency.copyToCache(project, output, null);
		}
	}

	private void completeNested(ConfigureRemapper configureRemapper, InputTag tag, Map<InputTag, Path> destinations) throws IOException {
		configureRemapper.outputConsumerMap.get(tag).close();

		if (!configureRemapper.nestedMap.containsKey(tag)) {
			return;
		}

		for (InputTag inputTag : configureRemapper.nestedMap.get(tag)) {
			completeNested(configureRemapper, inputTag, destinations);
		}

		Path path = destinations.get(tag);

		try {
			for (InputTag inputTag : configureRemapper.nestedMap.get(tag)) {
				ZipUtils.replace(path, configureRemapper.nestedJarPaths.get(inputTag), Files.readAllBytes(destinations.get(inputTag)));
			}
		} catch (RuntimeException | IOException | Error t) {
			project.getLogger().error("File: ");
			project.getLogger().error("Failed while completing nesting for {}", destinations.get(tag));
			throw t;
		}
	}

	private void remapJars(List<ModDependency> remapList) throws IOException {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		final CreateRemapper createRemapper = createRemapper(extension, remapList);
		final ConfigureRemapper configureRemapper = configureRemapper(extension, remapList, createRemapper);
		final Map<InputTag, Path> destinations = applyRemapper(extension, remapList, createRemapper, configureRemapper);
		completeRemapping(remapList, configureRemapper, destinations);
	}

	private static Path getRemappedOutput(ModDependency dependency) {
		return dependency.getWorkingFile(null);
	}

	private void remapJarManifestEntries(Path jar) throws IOException {
		ZipUtils.transform(jar, Map.of(Constants.Manifest.PATH, bytes -> {
			var manifest = new Manifest(new ByteArrayInputStream(bytes));

			manifest.getMainAttributes().putValue(Constants.Manifest.MAPPING_NAMESPACE, toM);

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			manifest.write(out);
			return out.toByteArray();
		}));
	}

	private record CreateRemapper(TinyRemapper remapper, Set<InputTag> remapMixins, KotlinRemapperClassloader kotlinRemapperClassloader, boolean requiresStaticMixinRemap) {
	}

	private record ConfigureRemapper(Map<ModDependency, InputTag> tagMap, Map<InputTag, List<InputTag>> nestedMap, Map<InputTag, OutputConsumerPath> outputConsumerMap, Map<InputTag, Pair<byte[], String>> accessWidenerMap, Map<InputTag, Path> tempFiles, Map<InputTag, String> tempFileNames, Map<InputTag, String> nestedJarPaths) {
	}
}
