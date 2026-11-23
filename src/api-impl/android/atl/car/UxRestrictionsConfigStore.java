/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.atl.car;

import android.car.drivingstate.CarUxRestrictionsConfiguration;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.util.Slog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The persisted UX restrictions configurations of the car service: the file half of AOSP's
 * CarUxRestrictionsManagerService (packages/services/Car, android14-release), loadConfig,
 * promoteStagedConfig, saveUxRestrictionsConfigurationForNextBoot, getStagedConfigs,
 * persistConfig/readPersistedConfig with the V1 and V2 JSON schemas and validateConfigs.
 *
 * AOSP keeps the files in /data/system/car (SystemInterface.getSystemCarDir); this runtime's
 * equivalent of /data/system is the app data base directory (packages.list lives there too), so
 * the files are in {@code <app data base>/_system_/car}.
 */
public final class UxRestrictionsConfigStore {
	private static final String TAG = "CarUxR";

	private static final int UNKNOWN_JSON_SCHEMA_VERSION = -1;
	private static final int JSON_SCHEMA_VERSION_V1 = 1;
	private static final int JSON_SCHEMA_VERSION_V2 = 2;
	private static final String JSON_NAME_SCHEMA_VERSION = "schema_version";
	private static final String JSON_NAME_RESTRICTIONS = "restrictions";

	/** AOSP: the configuration in use, promoted from the staged one */
	public static final String CONFIG_FILENAME_PRODUCTION = "ux_restrictions_prod_config.json";
	/** AOSP: the configuration saved for the next boot */
	public static final String CONFIG_FILENAME_STAGED = "ux_restrictions_staged_config.json";
	/**
	 * Not AOSP: the restriction mode the car app host's car keeps across app starts (AOSP's car
	 * service keeps its mode in memory and starts each boot in the baseline; the host's car is
	 * the driver's, who chooses its mode on the car page, and the choice holds until changed).
	 */
	public static final String MODE_FILENAME_HOST = "ux_restrictions_host_mode";
	/** the longest restriction mode name the mode file keeps */
	private static final int MODE_MAX_LENGTH = 64;

	private final File mDir;

	public UxRestrictionsConfigStore(File dir) {
		mDir = dir;
	}

	/** The store of this runtime's car service. */
	public static UxRestrictionsConfigStore forSystem() {
		return new UxRestrictionsConfigStore(new File(android.atl.ATLPaths.app_data_dir_base, "_system_/car"));
	}

	public File getFile(String filename) {
		return new File(mDir, filename);
	}

	/**
	 * AOSP promoteStagedConfig: replaces the production file with the staged one. The car service
	 * only does this while the car is parked, so that the restrictions never change during a
	 * drive; the caller decides that.
	 */
	public boolean promoteStagedConfig() {
		Path staged = getFile(CONFIG_FILENAME_STAGED).toPath();
		if (!Files.exists(staged))
			return false;
		try {
			Files.move(staged, getFile(CONFIG_FILENAME_PRODUCTION).toPath(), StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException e) {
			Slog.e(TAG, "Could not promote state config", e);
			return false;
		}
	}

	/** AOSP loadConfig without the promotion: the production config, else null (the caller's XML/default map). */
	public List<CarUxRestrictionsConfiguration> readProductionConfig() {
		File prod = getFile(CONFIG_FILENAME_PRODUCTION);
		if (!prod.exists())
			return null;
		return readPersistedConfig(prod);
	}

	/** AOSP saveUxRestrictionsConfigurationForNextBoot (after the permission check). */
	public boolean saveForNextBoot(List<CarUxRestrictionsConfiguration> configs) {
		validateConfigs(configs);
		return persistConfig(configs, CONFIG_FILENAME_STAGED);
	}

	/**
	 * Whether a restriction mode name can be kept: the names configurations use (AOSP's are
	 * "baseline" and "passenger"; a configuration may define others), letters, digits, '_', '-'
	 * and '.', at most 64 of them.
	 */
	public static boolean isValidModeName(String mode) {
		if (mode == null || mode.isEmpty() || mode.length() > MODE_MAX_LENGTH)
			return false;
		for (int i = 0; i < mode.length(); i++) {
			char c = mode.charAt(i);
			if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.')
				return false;
		}
		return true;
	}

	/** The car app host's kept restriction mode ({@link #MODE_FILENAME_HOST}), or null when none is kept. */
	public String readHostMode() {
		File file = getFile(MODE_FILENAME_HOST);
		if (!file.exists())
			return null;
		try {
			byte[] data = new AtomicFile(file).readFully();
			String mode = new String(data, StandardCharsets.UTF_8).trim();
			if (isValidModeName(mode))
				return mode;
			Slog.w(TAG, "Ignoring an invalid restriction mode in " + file.getName());
		} catch (IOException e) {
			Slog.e(TAG, "Could not read " + file.getName(), e);
		}
		return null;
	}

	/** Keeps the car app host's restriction mode for its next starts; false when it could not be written. */
	public boolean saveHostMode(String mode) {
		if (!isValidModeName(mode))
			throw new IllegalArgumentException("invalid restriction mode " + mode);
		if (!mDir.isDirectory() && !mDir.mkdirs()) {
			Slog.e(TAG, "Could not create " + mDir);
			return false;
		}
		AtomicFile file = new AtomicFile(getFile(MODE_FILENAME_HOST));
		FileOutputStream fos = null;
		try {
			fos = file.startWrite();
			fos.write((mode + "\n").getBytes(StandardCharsets.UTF_8));
			file.finishWrite(fos);
			return true;
		} catch (IOException e) {
			Slog.e(TAG, "Could not keep the restriction mode", e);
			if (fos != null)
				file.failWrite(fos);
			return false;
		}
	}

	/** AOSP getStagedConfigs: the staged configurations, or null when nothing is staged. */
	public List<CarUxRestrictionsConfiguration> getStagedConfigs() {
		File staged = getFile(CONFIG_FILENAME_STAGED);
		if (staged.exists())
			return readPersistedConfig(staged);
		return null;
	}

	/**
	 * AOSP validateConfigs, for this runtime's CarUxRestrictionsConfiguration (the version whose
	 * configurations are told apart by physical port only, without occupant zones).
	 */
	public static void validateConfigs(List<CarUxRestrictionsConfiguration> configs) {
		if (configs == null)
			throw new NullPointerException("configs must not be null");
		if (configs.size() == 0)
			throw new IllegalArgumentException("Empty configuration.");
		if (configs.size() == 1)
			return;
		Set<Integer> existingPorts = new HashSet<>(configs.size());
		for (int i = 0; i < configs.size(); i++) {
			Integer port = configs.get(i).getPhysicalPort();
			if (port == null) {
				// Size was checked above; safe to assume there are multiple configs.
				throw new IllegalArgumentException("Input contains multiple configurations; each must set physical port.");
			}
			if (!existingPorts.add(port))
				throw new IllegalStateException("Multiple configurations for port " + port);
		}
	}

	/* AOSP persistConfig */
	private boolean persistConfig(List<CarUxRestrictionsConfiguration> configs, String filename) {
		File file = getFile(filename);
		if (!mDir.isDirectory() && !mDir.mkdirs()) {
			Slog.e(TAG, "Could not create " + mDir);
			return false;
		}
		AtomicFile stagedFile = new AtomicFile(file);
		FileOutputStream fos;
		try {
			fos = stagedFile.startWrite();
		} catch (IOException e) {
			Slog.e(TAG, "Could not open file to persist config", e);
			return false;
		}
		try {
			JsonWriter jsonWriter = new JsonWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
			writeJson(jsonWriter, configs);
			/* AOSP closes the writer (and with it the stream) before finishWrite; the stream must stay
			 * open for finishWrite's fsync, so only flush here */
			jsonWriter.flush();
		} catch (IOException e) {
			Slog.e(TAG, "Could not persist config", e);
			stagedFile.failWrite(fos);
			return false;
		}
		stagedFile.finishWrite(fos);
		return true;
	}

	/* AOSP writeJson */
	static void writeJson(JsonWriter jsonWriter, List<CarUxRestrictionsConfiguration> configs) throws IOException {
		jsonWriter.beginObject();
		jsonWriter.name(JSON_NAME_SCHEMA_VERSION).value(JSON_SCHEMA_VERSION_V2);
		jsonWriter.name(JSON_NAME_RESTRICTIONS);
		jsonWriter.beginArray();
		for (int i = 0; i < configs.size(); i++) {
			CarUxRestrictionsConfiguration config = configs.get(i);
			config.writeJson(jsonWriter);
		}
		jsonWriter.endArray();
		jsonWriter.endObject();
	}

	/* AOSP readPersistedConfig */
	private List<CarUxRestrictionsConfiguration> readPersistedConfig(File file) {
		if (!file.exists()) {
			Slog.e(TAG, "Could not find config file: " + file.getName());
			return null;
		}
		// Take one pass at the file to check the version and then a second pass to read the
		// contents. We could assess the version and read in one pass, but we're preferring
		// clarity over complexity here.
		int schemaVersion = readFileSchemaVersion(file);
		AtomicFile configFile = new AtomicFile(file);
		try (JsonReader reader = new JsonReader(new InputStreamReader(configFile.openRead(), StandardCharsets.UTF_8))) {
			List<CarUxRestrictionsConfiguration> configs = new ArrayList<>();
			switch (schemaVersion) {
				case JSON_SCHEMA_VERSION_V1:
					readV1Json(reader, configs);
					break;
				case JSON_SCHEMA_VERSION_V2:
					readV2Json(reader, configs);
					break;
				default:
					Slog.e(TAG, "Unable to parse schema for version " + schemaVersion);
			}
			return configs;
		} catch (IOException | IllegalStateException | IllegalArgumentException e) {
			Slog.e(TAG, "Could not read persisted config file " + file.getName(), e);
		}
		return null;
	}

	private static void readV1Json(JsonReader reader, List<CarUxRestrictionsConfiguration> configs) throws IOException {
		readRestrictionsArray(reader, configs, JSON_SCHEMA_VERSION_V1);
	}

	private static void readV2Json(JsonReader reader, List<CarUxRestrictionsConfiguration> configs) throws IOException {
		reader.beginObject();
		while (reader.hasNext()) {
			String name = reader.nextName();
			switch (name) {
				case JSON_NAME_RESTRICTIONS:
					readRestrictionsArray(reader, configs, JSON_SCHEMA_VERSION_V2);
					break;
				default:
					reader.skipValue();
			}
		}
		reader.endObject();
	}

	private static int readFileSchemaVersion(File file) {
		AtomicFile configFile = new AtomicFile(file);
		try (JsonReader reader = new JsonReader(new InputStreamReader(configFile.openRead(), StandardCharsets.UTF_8))) {
			if (reader.peek() == JsonToken.BEGIN_ARRAY) {
				// only schema V1 beings with an array - no need to keep reading
				return JSON_SCHEMA_VERSION_V1;
			} else {
				reader.beginObject();
				while (reader.hasNext()) {
					String name = reader.nextName();
					switch (name) {
						case JSON_NAME_SCHEMA_VERSION:
							// got the version, no need to continue reading
							return reader.nextInt();
						default:
							reader.skipValue();
					}
				}
				reader.endObject();
			}
		} catch (IOException | IllegalStateException | NumberFormatException e) {
			Slog.e(TAG, "Could not read persisted config file " + file.getName(), e);
		}
		return UNKNOWN_JSON_SCHEMA_VERSION;
	}

	private static void readRestrictionsArray(JsonReader reader, List<CarUxRestrictionsConfiguration> configs, int schemaVersion) throws IOException {
		reader.beginArray();
		while (reader.hasNext()) {
			configs.add(CarUxRestrictionsConfiguration.readJson(reader, schemaVersion));
		}
		reader.endArray();
	}
}
