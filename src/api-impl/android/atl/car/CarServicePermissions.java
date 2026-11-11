/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.atl.ATLPackageSettings;
import android.content.pm.PermissionInfo;

/**
 * The permissions the car service (AOSP packages/services/Car, package com.android.car, signed with
 * the platform key) defines, with their protection levels: the {@code <permission>} declarations
 * of its AndroidManifest.xml (android12-release). Here the car service is part of the platform, so
 * its permissions are platform permissions.
 */
public final class CarServicePermissions {
	private static final String[][] PERMISSIONS = {
		{"android.car.permission.CAR_ENERGY", "dangerous"},
		{"android.car.permission.ADJUST_RANGE_REMAINING", "signature|privileged"},
		{"android.car.permission.CAR_IDENTIFICATION", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_CLIMATE", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_DOORS", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_WINDOWS", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_MIRRORS", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_SEATS", "signature|privileged"},
		{"android.car.permission.CAR_MILEAGE", "signature|privileged"},
		{"android.car.permission.CAR_TIRES", "signature|privileged"},
		{"android.car.permission.READ_CAR_STEERING", "signature|privileged"},
		{"android.car.permission.READ_CAR_DISPLAY_UNITS", "normal"},
		{"android.car.permission.CONTROL_CAR_DISPLAY_UNITS", "normal"},
		{"android.car.permission.CAR_SPEED", "dangerous"},
		{"android.car.permission.CAR_ENERGY_PORTS", "normal"},
		{"android.car.permission.CONTROL_CAR_ENERGY_PORTS", "signature|privileged"},
		{"android.car.permission.CAR_ENGINE_DETAILED", "signature|privileged"},
		{"android.car.permission.CAR_DYNAMICS_STATE", "signature|privileged"},
		{"android.car.permission.CAR_VENDOR_EXTENSION", "signature|privileged"},
		{"android.car.permission.CAR_PROJECTION", "signature|privileged"},
		{"android.car.permission.ACCESS_CAR_PROJECTION_STATUS", "signature|privileged"},
		{"android.car.permission.BIND_PROJECTION_SERVICE", "signature"},
		{"android.car.permission.CAR_MOCK_VEHICLE_HAL", "signature|privileged"},
		{"android.car.permission.CAR_INFO", "normal"},
		{"android.car.permission.READ_CAR_VENDOR_PERMISSION_INFO", "signature|privileged"},
		{"android.car.permission.CAR_EXTERIOR_ENVIRONMENT", "normal"},
		{"android.car.permission.CAR_EPOCH_TIME", "signature|privileged"},
		{"android.car.permission.STORAGE_ENCRYPTION_BINDING_SEED", "signature|privileged"},
		{"android.car.permission.CAR_EXTERIOR_LIGHTS", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS", "signature|privileged"},
		{"android.car.permission.READ_CAR_INTERIOR_LIGHTS", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_INTERIOR_LIGHTS", "signature|privileged"},
		{"android.car.permission.CAR_POWER", "signature|privileged"},
		{"android.car.permission.CAR_POWERTRAIN", "normal"},
		{"android.car.permission.CAR_NAVIGATION_MANAGER", "signature|privileged"},
		{"android.car.permission.CAR_DIAGNOSTICS", "signature|privileged"},
		{"android.car.permission.CLEAR_CAR_DIAGNOSTICS", "signature|privileged"},
		{"android.car.permission.BIND_VMS_CLIENT", "signature"},
		{"android.car.permission.VMS_PUBLISHER", "signature|privileged"},
		{"android.car.permission.VMS_SUBSCRIBER", "signature|privileged"},
		{"android.car.permission.CAR_DRIVING_STATE", "signature|privileged"},
		{"android.car.permission.USE_CAR_TELEMETRY_SERVICE", "signature|privileged"},
		{"android.car.permission.REQUEST_CAR_EVS_ACTIVITY", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_EVS_ACTIVITY", "signature|privileged"},
		{"android.car.permission.USE_CAR_EVS_CAMERA", "signature|privileged"},
		{"android.car.permission.MONITOR_CAR_EVS_STATUS", "signature|privileged"},
		{"android.car.permission.CONTROL_APP_BLOCKING", "signature|privileged"},
		{"android.car.permission.CAR_CONTROL_AUDIO_VOLUME", "signature|privileged"},
		{"android.car.permission.CAR_CONTROL_AUDIO_SETTINGS", "signature|privileged"},
		{"android.car.permission.RECEIVE_CAR_AUDIO_DUCKING_EVENTS", "system|signature"},
		{"android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE", "signature"},
		{"android.car.permission.BIND_CAR_INPUT_SERVICE", "signature"},
		{"android.car.permission.CAR_DISPLAY_IN_CLUSTER", "signature|privileged"},
		{"android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL", "signature|privileged"},
		{"android.car.permission.CAR_HANDLE_USB_AOAP_DEVICE", "signature|privileged"},
		{"android.car.permission.CAR_UX_RESTRICTIONS_CONFIGURATION", "signature|privileged"},
		{"android.car.permission.READ_CAR_OCCUPANT_AWARENESS_STATE", "signature|privileged"},
		{"android.car.permission.ACCESS_PRIVATE_DISPLAY_ID", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_OCCUPANT_AWARENESS_SYSTEM", "signature|privileged"},
		{"android.car.permission.STORAGE_MONITORING", "signature|privileged"},
		{"android.car.permission.CAR_ENROLL_TRUST", "signature|privileged"},
		{"android.car.permission.CAR_TEST_SERVICE", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_FEATURES", "signature|privileged"},
		{"android.car.permission.USE_CAR_WATCHDOG", "signature|privileged"},
		{"android.car.permission.CONTROL_CAR_WATCHDOG_CONFIG", "signature|privileged"},
		{"android.car.permission.COLLECT_CAR_WATCHDOG_METRICS", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_WINDOW", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_WINDOW", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_DOOR", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_DOOR", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_SEAT", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_SEAT", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_MIRROR", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_MIRROR", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_INFO", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_INFO", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_ENGINE", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_ENGINE", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_HVAC", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_HVAC", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_LIGHT", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_LIGHT", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_1", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_1", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_2", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_2", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_3", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_3", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_4", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_4", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_5", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_5", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_6", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_6", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_7", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_7", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_8", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_8", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_9", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_9", "signature|privileged"},
		{"android.car.permission.GET_CAR_VENDOR_CATEGORY_10", "signature|privileged"},
		{"android.car.permission.SET_CAR_VENDOR_CATEGORY_10", "signature|privileged"},
		{"android.car.permission.CAR_MONITOR_INPUT", "signature|privileged"},
		{"android.car.permission.READ_CAR_POWER_POLICY", "normal"},
		{"android.car.permission.CONTROL_CAR_POWER_POLICY", "signature|privileged"},
		{"android.car.permission.TEMPLATE_RENDERER", "signature|privileged"},
	};

	private CarServicePermissions() {}

	/** PermissionInfo.parseProtectionLevel for the manifest's protectionLevel strings */
	private static int parse(String level) {
		int base = PermissionInfo.PROTECTION_NORMAL;
		int flags = 0;
		for (String part : level.split("\\|")) {
			switch (part) {
				case "normal":
					base = PermissionInfo.PROTECTION_NORMAL;
					break;
				case "dangerous":
					base = PermissionInfo.PROTECTION_DANGEROUS;
					break;
				case "signature":
					base = PermissionInfo.PROTECTION_SIGNATURE;
					break;
				case "system":
				case "privileged":
					/* "system" is the old name of the privileged flag */
					flags |= PermissionInfo.PROTECTION_FLAG_SYSTEM;
					break;
				default:
					throw new IllegalArgumentException("unknown protection level " + part);
			}
		}
		return base | flags;
	}

	/** Registers the car service's permissions with the package manager. */
	public static void register() {
		for (String[] p : PERMISSIONS)
			ATLPackageSettings.definePlatformPermission(p[0], parse(p[1]));
	}
}
