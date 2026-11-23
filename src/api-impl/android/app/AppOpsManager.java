/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.util.Slog;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * AOSP AppOpsManager (android14-release) with the decisions of AppOpsService on this device.
 *
 * The op table (op codes, op strings, switch ops, the permission behind an op and its default
 * mode) is AOSP's sAppOpInfos. An op's mode for a package is, as on AOSP:
 * <ul>
 * <li>a mode the platform set (setMode/setUidMode, needs the system uid; kept in the app data
 *     base directory's _system_/appops.properties like AOSP's appops.xml);</li>
 * <li>for an op backed by a runtime permission (RUNTIME_PERMISSION_OPS), the permission state
 *     PermissionPolicyService mirrors into it: MODE_IGNORED while the permission is not granted;
 *     granted location, camera and microphone ops are MODE_FOREGROUND unless the background
 *     permission (ACCESS_BACKGROUND_LOCATION) is granted too, the rest MODE_ALLOWED;</li>
 * <li>otherwise the op's default mode (appop permissions such as SYSTEM_ALERT_WINDOW or
 *     PACKAGE_USAGE_STATS default to MODE_DEFAULT: the caller then checks the permission).</li>
 * </ul>
 * MODE_FOREGROUND is evaluated by the uid's state: the app this runtime runs is the device's
 * foreground app (on screen, or projected through the car host), so it evaluates to MODE_ALLOWED;
 * the raw variants return it as is. The permission state comes from PackageManager
 * (ATLPackageSettings: the grants of the package's manifest).
 */
public class AppOpsManager {
	private static final String TAG = "AppOpsManager";

	public static final int MODE_ALLOWED = 0;
	public static final int MODE_IGNORED = 1;
	public static final int MODE_ERRORED = 2;
	public static final int MODE_DEFAULT = 3;
	public static final int MODE_FOREGROUND = 4;

	/** AOSP: also report foreground changes (startWatchingMode flags). */
	public static final int WATCH_FOREGROUND_CHANGES = 1 << 0;

	/** AOSP (hidden): no operation. */
	public static final int OP_NONE = -1;
	public static final int OP_COARSE_LOCATION = 0;
	public static final int OP_FINE_LOCATION = 1;
	public static final int OP_GPS = 2;
	public static final int OP_VIBRATE = 3;
	public static final int OP_READ_CONTACTS = 4;
	public static final int OP_WRITE_CONTACTS = 5;
	public static final int OP_READ_CALL_LOG = 6;
	public static final int OP_WRITE_CALL_LOG = 7;
	public static final int OP_READ_CALENDAR = 8;
	public static final int OP_WRITE_CALENDAR = 9;
	public static final int OP_WIFI_SCAN = 10;
	public static final int OP_POST_NOTIFICATION = 11;
	public static final int OP_NEIGHBORING_CELLS = 12;
	public static final int OP_CALL_PHONE = 13;
	public static final int OP_READ_SMS = 14;
	public static final int OP_WRITE_SMS = 15;
	public static final int OP_RECEIVE_SMS = 16;
	public static final int OP_RECEIVE_EMERGECY_SMS = 17;
	public static final int OP_RECEIVE_MMS = 18;
	public static final int OP_RECEIVE_WAP_PUSH = 19;
	public static final int OP_SEND_SMS = 20;
	public static final int OP_READ_ICC_SMS = 21;
	public static final int OP_WRITE_ICC_SMS = 22;
	public static final int OP_WRITE_SETTINGS = 23;
	public static final int OP_SYSTEM_ALERT_WINDOW = 24;
	public static final int OP_ACCESS_NOTIFICATIONS = 25;
	public static final int OP_CAMERA = 26;
	public static final int OP_RECORD_AUDIO = 27;
	public static final int OP_PLAY_AUDIO = 28;
	public static final int OP_READ_CLIPBOARD = 29;
	public static final int OP_WRITE_CLIPBOARD = 30;
	public static final int OP_TAKE_MEDIA_BUTTONS = 31;
	public static final int OP_TAKE_AUDIO_FOCUS = 32;
	public static final int OP_AUDIO_MASTER_VOLUME = 33;
	public static final int OP_AUDIO_VOICE_VOLUME = 34;
	public static final int OP_AUDIO_RING_VOLUME = 35;
	public static final int OP_AUDIO_MEDIA_VOLUME = 36;
	public static final int OP_AUDIO_ALARM_VOLUME = 37;
	public static final int OP_AUDIO_NOTIFICATION_VOLUME = 38;
	public static final int OP_AUDIO_BLUETOOTH_VOLUME = 39;
	public static final int OP_WAKE_LOCK = 40;
	public static final int OP_MONITOR_LOCATION = 41;
	public static final int OP_MONITOR_HIGH_POWER_LOCATION = 42;
	public static final int OP_GET_USAGE_STATS = 43;
	public static final int OP_MUTE_MICROPHONE = 44;
	public static final int OP_TOAST_WINDOW = 45;
	public static final int OP_PROJECT_MEDIA = 46;
	public static final int OP_ACTIVATE_VPN = 47;
	public static final int OP_WRITE_WALLPAPER = 48;
	public static final int OP_ASSIST_STRUCTURE = 49;
	public static final int OP_ASSIST_SCREENSHOT = 50;
	public static final int OP_READ_PHONE_STATE = 51;
	public static final int OP_ADD_VOICEMAIL = 52;
	public static final int OP_USE_SIP = 53;
	public static final int OP_PROCESS_OUTGOING_CALLS = 54;
	public static final int OP_USE_FINGERPRINT = 55;
	public static final int OP_BODY_SENSORS = 56;
	public static final int OP_READ_CELL_BROADCASTS = 57;
	public static final int OP_MOCK_LOCATION = 58;
	public static final int OP_READ_EXTERNAL_STORAGE = 59;
	public static final int OP_WRITE_EXTERNAL_STORAGE = 60;
	public static final int OP_TURN_SCREEN_ON = 61;
	public static final int OP_GET_ACCOUNTS = 62;
	public static final int OP_RUN_IN_BACKGROUND = 63;
	public static final int OP_AUDIO_ACCESSIBILITY_VOLUME = 64;
	public static final int OP_READ_PHONE_NUMBERS = 65;
	public static final int OP_REQUEST_INSTALL_PACKAGES = 66;
	public static final int OP_PICTURE_IN_PICTURE = 67;
	public static final int OP_INSTANT_APP_START_FOREGROUND = 68;
	public static final int OP_ANSWER_PHONE_CALLS = 69;
	public static final int OP_RUN_ANY_IN_BACKGROUND = 70;
	public static final int OP_CHANGE_WIFI_STATE = 71;
	public static final int OP_REQUEST_DELETE_PACKAGES = 72;
	public static final int OP_BIND_ACCESSIBILITY_SERVICE = 73;
	public static final int OP_ACCEPT_HANDOVER = 74;
	public static final int OP_MANAGE_IPSEC_TUNNELS = 75;
	public static final int OP_START_FOREGROUND = 76;
	public static final int OP_BLUETOOTH_SCAN = 77;
	public static final int OP_USE_BIOMETRIC = 78;
	public static final int OP_ACTIVITY_RECOGNITION = 79;
	public static final int OP_SMS_FINANCIAL_TRANSACTIONS = 80;
	public static final int OP_READ_MEDIA_AUDIO = 81;
	public static final int OP_WRITE_MEDIA_AUDIO = 82;
	public static final int OP_READ_MEDIA_VIDEO = 83;
	public static final int OP_WRITE_MEDIA_VIDEO = 84;
	public static final int OP_READ_MEDIA_IMAGES = 85;
	public static final int OP_WRITE_MEDIA_IMAGES = 86;
	public static final int OP_LEGACY_STORAGE = 87;
	public static final int OP_ACCESS_ACCESSIBILITY = 88;
	public static final int OP_READ_DEVICE_IDENTIFIERS = 89;
	public static final int OP_ACCESS_MEDIA_LOCATION = 90;
	public static final int OP_QUERY_ALL_PACKAGES = 91;
	public static final int OP_MANAGE_EXTERNAL_STORAGE = 92;
	public static final int OP_INTERACT_ACROSS_PROFILES = 93;
	public static final int OP_ACTIVATE_PLATFORM_VPN = 94;
	public static final int OP_LOADER_USAGE_STATS = 95;
	public static final int OP_DEPRECATED_1 = 96;
	public static final int OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED = 97;
	public static final int OP_AUTO_REVOKE_MANAGED_BY_INSTALLER = 98;
	public static final int OP_NO_ISOLATED_STORAGE = 99;
	public static final int OP_PHONE_CALL_MICROPHONE = 100;
	public static final int OP_PHONE_CALL_CAMERA = 101;
	public static final int OP_RECORD_AUDIO_HOTWORD = 102;
	public static final int OP_MANAGE_ONGOING_CALLS = 103;
	public static final int OP_MANAGE_CREDENTIALS = 104;
	public static final int OP_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER = 105;
	public static final int OP_RECORD_AUDIO_OUTPUT = 106;
	public static final int OP_SCHEDULE_EXACT_ALARM = 107;
	public static final int OP_FINE_LOCATION_SOURCE = 108;
	public static final int OP_COARSE_LOCATION_SOURCE = 109;
	public static final int OP_MANAGE_MEDIA = 110;
	public static final int OP_BLUETOOTH_CONNECT = 111;
	public static final int OP_UWB_RANGING = 112;
	public static final int OP_ACTIVITY_RECOGNITION_SOURCE = 113;
	public static final int OP_BLUETOOTH_ADVERTISE = 114;
	public static final int OP_RECORD_INCOMING_PHONE_AUDIO = 115;
	public static final int OP_NEARBY_WIFI_DEVICES = 116;
	public static final int OP_ESTABLISH_VPN_SERVICE = 117;
	public static final int OP_ESTABLISH_VPN_MANAGER = 118;
	public static final int OP_ACCESS_RESTRICTED_SETTINGS = 119;
	public static final int OP_RECEIVE_AMBIENT_TRIGGER_AUDIO = 120;
	public static final int OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO = 121;
	public static final int OP_RUN_USER_INITIATED_JOBS = 122;
	public static final int OP_READ_MEDIA_VISUAL_USER_SELECTED = 123;
	public static final int OP_SYSTEM_EXEMPT_FROM_SUSPENSION = 124;
	public static final int OP_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS = 125;
	public static final int OP_READ_WRITE_HEALTH_DATA = 126;
	public static final int OP_FOREGROUND_SERVICE_SPECIAL_USE = 127;
	public static final int OP_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS = 128;
	public static final int OP_SYSTEM_EXEMPT_FROM_HIBERNATION = 129;
	public static final int OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION = 130;
	public static final int OP_CAPTURE_CONSENTLESS_BUGREPORT_ON_USERDEBUG_BUILD = 131;
	public static final int OP_DEPRECATED_2 = 132;
	public static final int OP_USE_FULL_SCREEN_INTENT = 133;
	public static final int OP_CAMERA_SANDBOXED = 134;
	public static final int OP_RECORD_AUDIO_SANDBOXED = 135;
	/** AOSP (hidden): the number of ops. */
	public static final int _NUM_OP = 136;

	public static final String OPSTR_COARSE_LOCATION = "android:coarse_location";
	public static final String OPSTR_FINE_LOCATION = "android:fine_location";
	public static final String OPSTR_GPS = "android:gps";
	public static final String OPSTR_VIBRATE = "android:vibrate";
	public static final String OPSTR_READ_CONTACTS = "android:read_contacts";
	public static final String OPSTR_WRITE_CONTACTS = "android:write_contacts";
	public static final String OPSTR_READ_CALL_LOG = "android:read_call_log";
	public static final String OPSTR_WRITE_CALL_LOG = "android:write_call_log";
	public static final String OPSTR_READ_CALENDAR = "android:read_calendar";
	public static final String OPSTR_WRITE_CALENDAR = "android:write_calendar";
	public static final String OPSTR_WIFI_SCAN = "android:wifi_scan";
	public static final String OPSTR_POST_NOTIFICATION = "android:post_notification";
	public static final String OPSTR_NEIGHBORING_CELLS = "android:neighboring_cells";
	public static final String OPSTR_CALL_PHONE = "android:call_phone";
	public static final String OPSTR_READ_SMS = "android:read_sms";
	public static final String OPSTR_WRITE_SMS = "android:write_sms";
	public static final String OPSTR_RECEIVE_SMS = "android:receive_sms";
	public static final String OPSTR_RECEIVE_EMERGENCY_BROADCAST = "android:receive_emergency_broadcast";
	public static final String OPSTR_RECEIVE_MMS = "android:receive_mms";
	public static final String OPSTR_RECEIVE_WAP_PUSH = "android:receive_wap_push";
	public static final String OPSTR_SEND_SMS = "android:send_sms";
	public static final String OPSTR_READ_ICC_SMS = "android:read_icc_sms";
	public static final String OPSTR_WRITE_ICC_SMS = "android:write_icc_sms";
	public static final String OPSTR_WRITE_SETTINGS = "android:write_settings";
	public static final String OPSTR_SYSTEM_ALERT_WINDOW = "android:system_alert_window";
	public static final String OPSTR_ACCESS_NOTIFICATIONS = "android:access_notifications";
	public static final String OPSTR_CAMERA = "android:camera";
	public static final String OPSTR_RECORD_AUDIO = "android:record_audio";
	public static final String OPSTR_PLAY_AUDIO = "android:play_audio";
	public static final String OPSTR_READ_CLIPBOARD = "android:read_clipboard";
	public static final String OPSTR_WRITE_CLIPBOARD = "android:write_clipboard";
	public static final String OPSTR_TAKE_MEDIA_BUTTONS = "android:take_media_buttons";
	public static final String OPSTR_TAKE_AUDIO_FOCUS = "android:take_audio_focus";
	public static final String OPSTR_AUDIO_MASTER_VOLUME = "android:audio_master_volume";
	public static final String OPSTR_AUDIO_VOICE_VOLUME = "android:audio_voice_volume";
	public static final String OPSTR_AUDIO_RING_VOLUME = "android:audio_ring_volume";
	public static final String OPSTR_AUDIO_MEDIA_VOLUME = "android:audio_media_volume";
	public static final String OPSTR_AUDIO_ALARM_VOLUME = "android:audio_alarm_volume";
	public static final String OPSTR_AUDIO_NOTIFICATION_VOLUME = "android:audio_notification_volume";
	public static final String OPSTR_AUDIO_BLUETOOTH_VOLUME = "android:audio_bluetooth_volume";
	public static final String OPSTR_WAKE_LOCK = "android:wake_lock";
	public static final String OPSTR_MONITOR_LOCATION = "android:monitor_location";
	public static final String OPSTR_MONITOR_HIGH_POWER_LOCATION = "android:monitor_location_high_power";
	public static final String OPSTR_GET_USAGE_STATS = "android:get_usage_stats";
	public static final String OPSTR_MUTE_MICROPHONE = "android:mute_microphone";
	public static final String OPSTR_TOAST_WINDOW = "android:toast_window";
	public static final String OPSTR_PROJECT_MEDIA = "android:project_media";
	public static final String OPSTR_ACTIVATE_VPN = "android:activate_vpn";
	public static final String OPSTR_WRITE_WALLPAPER = "android:write_wallpaper";
	public static final String OPSTR_ASSIST_STRUCTURE = "android:assist_structure";
	public static final String OPSTR_ASSIST_SCREENSHOT = "android:assist_screenshot";
	public static final String OPSTR_READ_PHONE_STATE = "android:read_phone_state";
	public static final String OPSTR_ADD_VOICEMAIL = "android:add_voicemail";
	public static final String OPSTR_USE_SIP = "android:use_sip";
	public static final String OPSTR_PROCESS_OUTGOING_CALLS = "android:process_outgoing_calls";
	public static final String OPSTR_USE_FINGERPRINT = "android:use_fingerprint";
	public static final String OPSTR_BODY_SENSORS = "android:body_sensors";
	public static final String OPSTR_READ_CELL_BROADCASTS = "android:read_cell_broadcasts";
	public static final String OPSTR_MOCK_LOCATION = "android:mock_location";
	public static final String OPSTR_READ_EXTERNAL_STORAGE = "android:read_external_storage";
	public static final String OPSTR_WRITE_EXTERNAL_STORAGE = "android:write_external_storage";
	public static final String OPSTR_TURN_SCREEN_ON = "android:turn_screen_on";
	public static final String OPSTR_GET_ACCOUNTS = "android:get_accounts";
	public static final String OPSTR_RUN_IN_BACKGROUND = "android:run_in_background";
	public static final String OPSTR_AUDIO_ACCESSIBILITY_VOLUME = "android:audio_accessibility_volume";
	public static final String OPSTR_READ_PHONE_NUMBERS = "android:read_phone_numbers";
	public static final String OPSTR_REQUEST_INSTALL_PACKAGES = "android:request_install_packages";
	public static final String OPSTR_PICTURE_IN_PICTURE = "android:picture_in_picture";
	public static final String OPSTR_INSTANT_APP_START_FOREGROUND = "android:instant_app_start_foreground";
	public static final String OPSTR_ANSWER_PHONE_CALLS = "android:answer_phone_calls";
	public static final String OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background";
	public static final String OPSTR_CHANGE_WIFI_STATE = "android:change_wifi_state";
	public static final String OPSTR_REQUEST_DELETE_PACKAGES = "android:request_delete_packages";
	public static final String OPSTR_BIND_ACCESSIBILITY_SERVICE = "android:bind_accessibility_service";
	public static final String OPSTR_ACCEPT_HANDOVER = "android:accept_handover";
	public static final String OPSTR_MANAGE_IPSEC_TUNNELS = "android:manage_ipsec_tunnels";
	public static final String OPSTR_START_FOREGROUND = "android:start_foreground";
	public static final String OPSTR_BLUETOOTH_SCAN = "android:bluetooth_scan";
	public static final String OPSTR_USE_BIOMETRIC = "android:use_biometric";
	public static final String OPSTR_ACTIVITY_RECOGNITION = "android:activity_recognition";
	public static final String OPSTR_SMS_FINANCIAL_TRANSACTIONS = "android:sms_financial_transactions";
	public static final String OPSTR_READ_MEDIA_AUDIO = "android:read_media_audio";
	public static final String OPSTR_WRITE_MEDIA_AUDIO = "android:write_media_audio";
	public static final String OPSTR_READ_MEDIA_VIDEO = "android:read_media_video";
	public static final String OPSTR_WRITE_MEDIA_VIDEO = "android:write_media_video";
	public static final String OPSTR_READ_MEDIA_IMAGES = "android:read_media_images";
	public static final String OPSTR_WRITE_MEDIA_IMAGES = "android:write_media_images";
	public static final String OPSTR_LEGACY_STORAGE = "android:legacy_storage";
	public static final String OPSTR_ACCESS_ACCESSIBILITY = "android:access_accessibility";
	public static final String OPSTR_READ_DEVICE_IDENTIFIERS = "android:read_device_identifiers";
	public static final String OPSTR_ACCESS_MEDIA_LOCATION = "android:access_media_location";
	public static final String OPSTR_QUERY_ALL_PACKAGES = "android:query_all_packages";
	public static final String OPSTR_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage";
	public static final String OPSTR_INTERACT_ACROSS_PROFILES = "android:interact_across_profiles";
	public static final String OPSTR_ACTIVATE_PLATFORM_VPN = "android:activate_platform_vpn";
	public static final String OPSTR_LOADER_USAGE_STATS = "android:loader_usage_stats";
	public static final String OPSTR_AUTO_REVOKE_PERMISSIONS_IF_UNUSED = "android:auto_revoke_permissions_if_unused";
	public static final String OPSTR_AUTO_REVOKE_MANAGED_BY_INSTALLER = "android:auto_revoke_managed_by_installer";
	public static final String OPSTR_NO_ISOLATED_STORAGE = "android:no_isolated_storage";
	public static final String OPSTR_PHONE_CALL_MICROPHONE = "android:phone_call_microphone";
	public static final String OPSTR_PHONE_CALL_CAMERA = "android:phone_call_camera";
	public static final String OPSTR_RECORD_AUDIO_HOTWORD = "android:record_audio_hotword";
	public static final String OPSTR_MANAGE_ONGOING_CALLS = "android:manage_ongoing_calls";
	public static final String OPSTR_MANAGE_CREDENTIALS = "android:manage_credentials";
	public static final String OPSTR_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER = "android:use_icc_auth_with_device_identifier";
	public static final String OPSTR_RECORD_AUDIO_OUTPUT = "android:record_audio_output";
	public static final String OPSTR_SCHEDULE_EXACT_ALARM = "android:schedule_exact_alarm";
	public static final String OPSTR_FINE_LOCATION_SOURCE = "android:fine_location_source";
	public static final String OPSTR_COARSE_LOCATION_SOURCE = "android:coarse_location_source";
	public static final String OPSTR_MANAGE_MEDIA = "android:manage_media";
	public static final String OPSTR_BLUETOOTH_CONNECT = "android:bluetooth_connect";
	public static final String OPSTR_UWB_RANGING = "android:uwb_ranging";
	public static final String OPSTR_ACTIVITY_RECOGNITION_SOURCE = "android:activity_recognition_source";
	public static final String OPSTR_BLUETOOTH_ADVERTISE = "android:bluetooth_advertise";
	public static final String OPSTR_RECORD_INCOMING_PHONE_AUDIO = "android:record_incoming_phone_audio";
	public static final String OPSTR_NEARBY_WIFI_DEVICES = "android:nearby_wifi_devices";
	public static final String OPSTR_ESTABLISH_VPN_SERVICE = "android:establish_vpn_service";
	public static final String OPSTR_ESTABLISH_VPN_MANAGER = "android:establish_vpn_manager";
	public static final String OPSTR_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings";
	public static final String OPSTR_RECEIVE_AMBIENT_TRIGGER_AUDIO = "android:receive_ambient_trigger_audio";
	public static final String OPSTR_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO = "android:receive_explicit_user_interaction_audio";
	public static final String OPSTR_RUN_USER_INITIATED_JOBS = "android:run_user_initiated_jobs";
	public static final String OPSTR_READ_MEDIA_VISUAL_USER_SELECTED = "android:read_media_visual_user_selected";
	public static final String OPSTR_SYSTEM_EXEMPT_FROM_SUSPENSION = "android:system_exempt_from_suspension";
	public static final String OPSTR_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS = "android:system_exempt_from_dismissible_notifications";
	public static final String OPSTR_READ_WRITE_HEALTH_DATA = "android:read_write_health_data";
	public static final String OPSTR_FOREGROUND_SERVICE_SPECIAL_USE = "android:foreground_service_special_use";
	public static final String OPSTR_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS = "android:system_exempt_from_power_restrictions";
	public static final String OPSTR_SYSTEM_EXEMPT_FROM_HIBERNATION = "android:system_exempt_from_hibernation";
	public static final String OPSTR_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION = "android:system_exempt_from_activity_bg_start_restriction";
	public static final String OPSTR_CAPTURE_CONSENTLESS_BUGREPORT_ON_USERDEBUG_BUILD = "android:capture_consentless_bugreport_on_userdebug_build";
	public static final String OPSTR_DEPRECATED_2 = "android:deprecated_2";
	public static final String OPSTR_USE_FULL_SCREEN_INTENT = "android:use_full_screen_intent";
	public static final String OPSTR_CAMERA_SANDBOXED = "android:camera_sandboxed";
	public static final String OPSTR_RECORD_AUDIO_SANDBOXED = "android:record_audio_sandboxed";

	/* AOSP sAppOpInfos, as parallel arrays: op string, switch op, permission, default mode */
	private static final String[] sOpToString = {
		OPSTR_COARSE_LOCATION, OPSTR_FINE_LOCATION, OPSTR_GPS, OPSTR_VIBRATE, OPSTR_READ_CONTACTS,
		OPSTR_WRITE_CONTACTS, OPSTR_READ_CALL_LOG, OPSTR_WRITE_CALL_LOG, OPSTR_READ_CALENDAR, OPSTR_WRITE_CALENDAR,
		OPSTR_WIFI_SCAN, OPSTR_POST_NOTIFICATION, OPSTR_NEIGHBORING_CELLS, OPSTR_CALL_PHONE, OPSTR_READ_SMS,
		OPSTR_WRITE_SMS, OPSTR_RECEIVE_SMS, OPSTR_RECEIVE_EMERGENCY_BROADCAST, OPSTR_RECEIVE_MMS,
		OPSTR_RECEIVE_WAP_PUSH, OPSTR_SEND_SMS, OPSTR_READ_ICC_SMS, OPSTR_WRITE_ICC_SMS, OPSTR_WRITE_SETTINGS,
		OPSTR_SYSTEM_ALERT_WINDOW, OPSTR_ACCESS_NOTIFICATIONS, OPSTR_CAMERA, OPSTR_RECORD_AUDIO, OPSTR_PLAY_AUDIO,
		OPSTR_READ_CLIPBOARD, OPSTR_WRITE_CLIPBOARD, OPSTR_TAKE_MEDIA_BUTTONS, OPSTR_TAKE_AUDIO_FOCUS,
		OPSTR_AUDIO_MASTER_VOLUME, OPSTR_AUDIO_VOICE_VOLUME, OPSTR_AUDIO_RING_VOLUME, OPSTR_AUDIO_MEDIA_VOLUME,
		OPSTR_AUDIO_ALARM_VOLUME, OPSTR_AUDIO_NOTIFICATION_VOLUME, OPSTR_AUDIO_BLUETOOTH_VOLUME, OPSTR_WAKE_LOCK,
		OPSTR_MONITOR_LOCATION, OPSTR_MONITOR_HIGH_POWER_LOCATION, OPSTR_GET_USAGE_STATS, OPSTR_MUTE_MICROPHONE,
		OPSTR_TOAST_WINDOW, OPSTR_PROJECT_MEDIA, OPSTR_ACTIVATE_VPN, OPSTR_WRITE_WALLPAPER, OPSTR_ASSIST_STRUCTURE,
		OPSTR_ASSIST_SCREENSHOT, OPSTR_READ_PHONE_STATE, OPSTR_ADD_VOICEMAIL, OPSTR_USE_SIP,
		OPSTR_PROCESS_OUTGOING_CALLS, OPSTR_USE_FINGERPRINT, OPSTR_BODY_SENSORS, OPSTR_READ_CELL_BROADCASTS,
		OPSTR_MOCK_LOCATION, OPSTR_READ_EXTERNAL_STORAGE, OPSTR_WRITE_EXTERNAL_STORAGE, OPSTR_TURN_SCREEN_ON,
		OPSTR_GET_ACCOUNTS, OPSTR_RUN_IN_BACKGROUND, OPSTR_AUDIO_ACCESSIBILITY_VOLUME, OPSTR_READ_PHONE_NUMBERS,
		OPSTR_REQUEST_INSTALL_PACKAGES, OPSTR_PICTURE_IN_PICTURE, OPSTR_INSTANT_APP_START_FOREGROUND,
		OPSTR_ANSWER_PHONE_CALLS, OPSTR_RUN_ANY_IN_BACKGROUND, OPSTR_CHANGE_WIFI_STATE, OPSTR_REQUEST_DELETE_PACKAGES,
		OPSTR_BIND_ACCESSIBILITY_SERVICE, OPSTR_ACCEPT_HANDOVER, OPSTR_MANAGE_IPSEC_TUNNELS, OPSTR_START_FOREGROUND,
		OPSTR_BLUETOOTH_SCAN, OPSTR_USE_BIOMETRIC, OPSTR_ACTIVITY_RECOGNITION, OPSTR_SMS_FINANCIAL_TRANSACTIONS,
		OPSTR_READ_MEDIA_AUDIO, OPSTR_WRITE_MEDIA_AUDIO, OPSTR_READ_MEDIA_VIDEO, OPSTR_WRITE_MEDIA_VIDEO,
		OPSTR_READ_MEDIA_IMAGES, OPSTR_WRITE_MEDIA_IMAGES, OPSTR_LEGACY_STORAGE, OPSTR_ACCESS_ACCESSIBILITY,
		OPSTR_READ_DEVICE_IDENTIFIERS, OPSTR_ACCESS_MEDIA_LOCATION, OPSTR_QUERY_ALL_PACKAGES,
		OPSTR_MANAGE_EXTERNAL_STORAGE, OPSTR_INTERACT_ACROSS_PROFILES, OPSTR_ACTIVATE_PLATFORM_VPN,
		OPSTR_LOADER_USAGE_STATS, null, OPSTR_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
		OPSTR_AUTO_REVOKE_MANAGED_BY_INSTALLER, OPSTR_NO_ISOLATED_STORAGE, OPSTR_PHONE_CALL_MICROPHONE,
		OPSTR_PHONE_CALL_CAMERA, OPSTR_RECORD_AUDIO_HOTWORD, OPSTR_MANAGE_ONGOING_CALLS, OPSTR_MANAGE_CREDENTIALS,
		OPSTR_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER, OPSTR_RECORD_AUDIO_OUTPUT, OPSTR_SCHEDULE_EXACT_ALARM,
		OPSTR_FINE_LOCATION_SOURCE, OPSTR_COARSE_LOCATION_SOURCE, OPSTR_MANAGE_MEDIA, OPSTR_BLUETOOTH_CONNECT,
		OPSTR_UWB_RANGING, OPSTR_ACTIVITY_RECOGNITION_SOURCE, OPSTR_BLUETOOTH_ADVERTISE,
		OPSTR_RECORD_INCOMING_PHONE_AUDIO, OPSTR_NEARBY_WIFI_DEVICES, OPSTR_ESTABLISH_VPN_SERVICE,
		OPSTR_ESTABLISH_VPN_MANAGER, OPSTR_ACCESS_RESTRICTED_SETTINGS, OPSTR_RECEIVE_AMBIENT_TRIGGER_AUDIO,
		OPSTR_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, OPSTR_RUN_USER_INITIATED_JOBS,
		OPSTR_READ_MEDIA_VISUAL_USER_SELECTED, OPSTR_SYSTEM_EXEMPT_FROM_SUSPENSION,
		OPSTR_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS, OPSTR_READ_WRITE_HEALTH_DATA,
		OPSTR_FOREGROUND_SERVICE_SPECIAL_USE, OPSTR_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS,
		OPSTR_SYSTEM_EXEMPT_FROM_HIBERNATION, OPSTR_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION,
		OPSTR_CAPTURE_CONSENTLESS_BUGREPORT_ON_USERDEBUG_BUILD, OPSTR_DEPRECATED_2, OPSTR_USE_FULL_SCREEN_INTENT,
		OPSTR_CAMERA_SANDBOXED, OPSTR_RECORD_AUDIO_SANDBOXED
	};

	private static final int[] sOpToSwitch = {
		OP_COARSE_LOCATION, OP_FINE_LOCATION, OP_COARSE_LOCATION, OP_VIBRATE, OP_READ_CONTACTS, OP_WRITE_CONTACTS,
		OP_READ_CALL_LOG, OP_WRITE_CALL_LOG, OP_READ_CALENDAR, OP_WRITE_CALENDAR, OP_COARSE_LOCATION,
		OP_POST_NOTIFICATION, OP_COARSE_LOCATION, OP_CALL_PHONE, OP_READ_SMS, OP_WRITE_SMS, OP_RECEIVE_SMS,
		OP_RECEIVE_SMS, OP_RECEIVE_MMS, OP_RECEIVE_WAP_PUSH, OP_SEND_SMS, OP_READ_SMS, OP_WRITE_SMS,
		OP_WRITE_SETTINGS, OP_SYSTEM_ALERT_WINDOW, OP_ACCESS_NOTIFICATIONS, OP_CAMERA, OP_RECORD_AUDIO, OP_PLAY_AUDIO,
		OP_READ_CLIPBOARD, OP_WRITE_CLIPBOARD, OP_TAKE_MEDIA_BUTTONS, OP_TAKE_AUDIO_FOCUS, OP_AUDIO_MASTER_VOLUME,
		OP_AUDIO_VOICE_VOLUME, OP_AUDIO_RING_VOLUME, OP_AUDIO_MEDIA_VOLUME, OP_AUDIO_ALARM_VOLUME,
		OP_AUDIO_NOTIFICATION_VOLUME, OP_AUDIO_BLUETOOTH_VOLUME, OP_WAKE_LOCK, OP_COARSE_LOCATION, OP_COARSE_LOCATION,
		OP_GET_USAGE_STATS, OP_MUTE_MICROPHONE, OP_TOAST_WINDOW, OP_PROJECT_MEDIA, OP_ACTIVATE_VPN,
		OP_WRITE_WALLPAPER, OP_ASSIST_STRUCTURE, OP_ASSIST_SCREENSHOT, OP_READ_PHONE_STATE, OP_ADD_VOICEMAIL,
		OP_USE_SIP, OP_PROCESS_OUTGOING_CALLS, OP_USE_FINGERPRINT, OP_BODY_SENSORS, OP_READ_CELL_BROADCASTS,
		OP_MOCK_LOCATION, OP_READ_EXTERNAL_STORAGE, OP_WRITE_EXTERNAL_STORAGE, OP_TURN_SCREEN_ON, OP_GET_ACCOUNTS,
		OP_RUN_IN_BACKGROUND, OP_AUDIO_ACCESSIBILITY_VOLUME, OP_READ_PHONE_NUMBERS, OP_REQUEST_INSTALL_PACKAGES,
		OP_PICTURE_IN_PICTURE, OP_INSTANT_APP_START_FOREGROUND, OP_ANSWER_PHONE_CALLS, OP_RUN_ANY_IN_BACKGROUND,
		OP_CHANGE_WIFI_STATE, OP_REQUEST_DELETE_PACKAGES, OP_BIND_ACCESSIBILITY_SERVICE, OP_ACCEPT_HANDOVER,
		OP_MANAGE_IPSEC_TUNNELS, OP_START_FOREGROUND, OP_BLUETOOTH_SCAN, OP_USE_BIOMETRIC, OP_ACTIVITY_RECOGNITION,
		OP_SMS_FINANCIAL_TRANSACTIONS, OP_READ_MEDIA_AUDIO, OP_WRITE_MEDIA_AUDIO, OP_READ_MEDIA_VIDEO,
		OP_WRITE_MEDIA_VIDEO, OP_READ_MEDIA_IMAGES, OP_WRITE_MEDIA_IMAGES, OP_LEGACY_STORAGE, OP_ACCESS_ACCESSIBILITY,
		OP_READ_DEVICE_IDENTIFIERS, OP_ACCESS_MEDIA_LOCATION, OP_QUERY_ALL_PACKAGES, OP_MANAGE_EXTERNAL_STORAGE,
		OP_INTERACT_ACROSS_PROFILES, OP_ACTIVATE_PLATFORM_VPN, OP_LOADER_USAGE_STATS, OP_DEPRECATED_1,
		OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED, OP_AUTO_REVOKE_MANAGED_BY_INSTALLER, OP_NO_ISOLATED_STORAGE,
		OP_PHONE_CALL_MICROPHONE, OP_PHONE_CALL_CAMERA, OP_RECORD_AUDIO_HOTWORD, OP_MANAGE_ONGOING_CALLS,
		OP_MANAGE_CREDENTIALS, OP_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER, OP_RECORD_AUDIO_OUTPUT,
		OP_SCHEDULE_EXACT_ALARM, OP_FINE_LOCATION, OP_COARSE_LOCATION, OP_MANAGE_MEDIA, OP_BLUETOOTH_CONNECT,
		OP_UWB_RANGING, OP_ACTIVITY_RECOGNITION, OP_BLUETOOTH_ADVERTISE, OP_RECORD_INCOMING_PHONE_AUDIO,
		OP_NEARBY_WIFI_DEVICES, OP_ESTABLISH_VPN_SERVICE, OP_ESTABLISH_VPN_MANAGER, OP_ACCESS_RESTRICTED_SETTINGS,
		OP_RECEIVE_AMBIENT_TRIGGER_AUDIO, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, OP_RUN_USER_INITIATED_JOBS,
		OP_READ_MEDIA_VISUAL_USER_SELECTED, OP_SYSTEM_EXEMPT_FROM_SUSPENSION,
		OP_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS, OP_READ_WRITE_HEALTH_DATA, OP_FOREGROUND_SERVICE_SPECIAL_USE,
		OP_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS, OP_SYSTEM_EXEMPT_FROM_HIBERNATION,
		OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION, OP_CAPTURE_CONSENTLESS_BUGREPORT_ON_USERDEBUG_BUILD,
		OP_DEPRECATED_2, OP_USE_FULL_SCREEN_INTENT, OP_CAMERA_SANDBOXED, OP_RECORD_AUDIO_SANDBOXED
	};

	private static final String[] sOpPerms = {
		"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION", null,
		"android.permission.VIBRATE", "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
		"android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.READ_CALENDAR",
		"android.permission.WRITE_CALENDAR", "android.permission.ACCESS_WIFI_STATE",
		"android.permission.POST_NOTIFICATIONS", null, "android.permission.CALL_PHONE", "android.permission.READ_SMS",
		null, "android.permission.RECEIVE_SMS", "android.permission.RECEIVE_EMERGENCY_BROADCAST",
		"android.permission.RECEIVE_MMS", "android.permission.RECEIVE_WAP_PUSH", "android.permission.SEND_SMS",
		"android.permission.READ_SMS", null, "android.permission.WRITE_SETTINGS",
		"android.permission.SYSTEM_ALERT_WINDOW", "android.permission.ACCESS_NOTIFICATIONS",
		"android.permission.CAMERA", "android.permission.RECORD_AUDIO", null, null, null, null, null, null, null,
		null, null, null, null, null, "android.permission.WAKE_LOCK", null, null,
		"android.permission.PACKAGE_USAGE_STATS", null, null, null, null, null, null, null,
		"android.permission.READ_PHONE_STATE", "android.permission.ADD_VOICEMAIL", "android.permission.USE_SIP",
		"android.permission.PROCESS_OUTGOING_CALLS", "android.permission.USE_FINGERPRINT",
		"android.permission.BODY_SENSORS", "android.permission.READ_CELL_BROADCASTS", null,
		"android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
		"android.permission.TURN_SCREEN_ON", "android.permission.GET_ACCOUNTS", null, null,
		"android.permission.READ_PHONE_NUMBERS", "android.permission.REQUEST_INSTALL_PACKAGES", null,
		"android.permission.INSTANT_APP_FOREGROUND_SERVICE", "android.permission.ANSWER_PHONE_CALLS", null,
		"android.permission.CHANGE_WIFI_STATE", "android.permission.REQUEST_DELETE_PACKAGES",
		"android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.ACCEPT_HANDOVER",
		"android.permission.MANAGE_IPSEC_TUNNELS", "android.permission.FOREGROUND_SERVICE",
		"android.permission.BLUETOOTH_SCAN", "android.permission.USE_BIOMETRIC",
		"android.permission.ACTIVITY_RECOGNITION", "android.permission.SMS_FINANCIAL_TRANSACTIONS",
		"android.permission.READ_MEDIA_AUDIO", null, "android.permission.READ_MEDIA_VIDEO", null,
		"android.permission.READ_MEDIA_IMAGES", null, null, null, null, "android.permission.ACCESS_MEDIA_LOCATION",
		null, "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.INTERACT_ACROSS_PROFILES", null,
		"android.permission.LOADER_USAGE_STATS", null, null, null, null, null, null, null,
		"android.permission.MANAGE_ONGOING_CALLS", null, "android.permission.USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER",
		null, "android.permission.SCHEDULE_EXACT_ALARM", null, null, "android.permission.MANAGE_MEDIA",
		"android.permission.BLUETOOTH_CONNECT", "android.permission.UWB_RANGING", null,
		"android.permission.BLUETOOTH_ADVERTISE", null, "android.permission.NEARBY_WIFI_DEVICES", null, null, null,
		null, null, null, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", null, null, null,
		"android.permission.FOREGROUND_SERVICE_SPECIAL_USE", null, null, null,
		"android.permission.CAPTURE_CONSENTLESS_BUGREPORT_ON_USERDEBUG_BUILD", null,
		"android.permission.USE_FULL_SCREEN_INTENT", null, null
	};

	private static final int[] sOpDefaultMode = {
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_IGNORED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_DEFAULT, MODE_DEFAULT, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_DEFAULT, MODE_ALLOWED, MODE_ALLOWED, MODE_IGNORED, MODE_IGNORED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ERRORED, MODE_ALLOWED, MODE_ALLOWED, MODE_DEFAULT, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_DEFAULT, MODE_ALLOWED, MODE_DEFAULT, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ERRORED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_DEFAULT, MODE_ALLOWED, MODE_ERRORED, MODE_ALLOWED,
		MODE_ERRORED, MODE_ALLOWED, MODE_ERRORED, MODE_DEFAULT, MODE_ALLOWED, MODE_ERRORED, MODE_ALLOWED,
		MODE_DEFAULT, MODE_DEFAULT, MODE_DEFAULT, MODE_IGNORED, MODE_DEFAULT, MODE_IGNORED, MODE_DEFAULT,
		MODE_ALLOWED, MODE_ERRORED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_DEFAULT, MODE_DEFAULT,
		MODE_DEFAULT, MODE_ALLOWED, MODE_DEFAULT, MODE_ALLOWED, MODE_ALLOWED, MODE_DEFAULT, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED, MODE_ALLOWED,
		MODE_ALLOWED, MODE_ALLOWED, MODE_DEFAULT, MODE_ALLOWED, MODE_ALLOWED, MODE_DEFAULT, MODE_DEFAULT,
		MODE_ALLOWED, MODE_DEFAULT, MODE_DEFAULT, MODE_DEFAULT, MODE_DEFAULT, MODE_DEFAULT, MODE_IGNORED,
		MODE_DEFAULT, MODE_ALLOWED, MODE_ALLOWED
	};

	/** AOSP RUNTIME_PERMISSION_OPS: ops whose mode mirrors a runtime permission's grant */
	private static final int[] RUNTIME_PERMISSION_OPS = {
		OP_READ_CONTACTS, OP_WRITE_CONTACTS, OP_GET_ACCOUNTS, OP_READ_CALENDAR, OP_WRITE_CALENDAR, OP_SEND_SMS,
		OP_RECEIVE_SMS, OP_READ_SMS, OP_RECEIVE_WAP_PUSH, OP_RECEIVE_MMS, OP_READ_CELL_BROADCASTS,
		OP_READ_EXTERNAL_STORAGE, OP_WRITE_EXTERNAL_STORAGE, OP_ACCESS_MEDIA_LOCATION, OP_COARSE_LOCATION,
		OP_FINE_LOCATION, OP_READ_PHONE_STATE, OP_READ_PHONE_NUMBERS, OP_CALL_PHONE, OP_READ_CALL_LOG,
		OP_WRITE_CALL_LOG, OP_ADD_VOICEMAIL, OP_USE_SIP, OP_PROCESS_OUTGOING_CALLS, OP_ANSWER_PHONE_CALLS,
		OP_ACCEPT_HANDOVER, OP_RECORD_AUDIO, OP_CAMERA, OP_BODY_SENSORS, OP_ACTIVITY_RECOGNITION,
		OP_READ_MEDIA_AUDIO, OP_WRITE_MEDIA_AUDIO, OP_READ_MEDIA_VIDEO, OP_WRITE_MEDIA_VIDEO, OP_READ_MEDIA_IMAGES,
		OP_WRITE_MEDIA_IMAGES, OP_BLUETOOTH_SCAN, OP_BLUETOOTH_CONNECT, OP_BLUETOOTH_ADVERTISE, OP_UWB_RANGING,
		OP_NEARBY_WIFI_DEVICES, OP_POST_NOTIFICATION
	};

	/** AOSP APP_OP_PERMISSION_PACKAGE_OPS and APP_OP_PERMISSION_UID_OPS: ops that gate an appop permission */
	private static final int[] APP_OP_PERMISSION_OPS = {
		OP_ACCESS_NOTIFICATIONS, OP_SYSTEM_ALERT_WINDOW, OP_WRITE_SETTINGS, OP_REQUEST_INSTALL_PACKAGES,
		OP_START_FOREGROUND, OP_SMS_FINANCIAL_TRANSACTIONS, OP_MANAGE_IPSEC_TUNNELS, OP_INSTANT_APP_START_FOREGROUND,
		OP_LOADER_USAGE_STATS, OP_MANAGE_EXTERNAL_STORAGE, OP_INTERACT_ACROSS_PROFILES, OP_MANAGE_ONGOING_CALLS,
		OP_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER, OP_SCHEDULE_EXACT_ALARM, OP_MANAGE_MEDIA, OP_TURN_SCREEN_ON,
		OP_RUN_USER_INITIATED_JOBS, OP_READ_MEDIA_VISUAL_USER_SELECTED, OP_FOREGROUND_SERVICE_SPECIAL_USE,
		OP_CAPTURE_CONSENTLESS_BUGREPORT_ON_USERDEBUG_BUILD, OP_USE_FULL_SCREEN_INTENT
	};

	private static final HashMap<String, Integer> sOpStrToOp = new HashMap<>();
	private static final HashMap<String, Integer> sPermToOp = new HashMap<>();
	private static final boolean[] sRuntimePermissionOp = new boolean[_NUM_OP];

	static {
		if (sOpToString.length != _NUM_OP || sOpToSwitch.length != _NUM_OP || sOpPerms.length != _NUM_OP || sOpDefaultMode.length != _NUM_OP)
			throw new IllegalStateException("app op table has the wrong length");
		for (int i = 0; i < _NUM_OP; i++) {
			if (sOpToString[i] != null)
				sOpStrToOp.put(sOpToString[i], i);
		}
		for (int op : RUNTIME_PERMISSION_OPS) {
			sRuntimePermissionOp[op] = true;
			if (sOpPerms[op] != null)
				sPermToOp.put(sOpPerms[op], op);
		}
		for (int op : APP_OP_PERMISSION_OPS) {
			if (sOpPerms[op] != null)
				sPermToOp.put(sOpPerms[op], op);
		}
	}

	public interface OnOpChangedListener {
		void onOpChanged(String op, String packageName);
	}

	/** AOSP: whether an op is being performed (startOp/finishOp). */
	public interface OnOpActiveChangedListener {
		void onOpActiveChanged(String op, int uid, String packageName, boolean active);
	}

	/* ---- AppOpsService state (one per process) ---- */

	private static final Object sLock = new Object();
	/* modes the platform set: "u<uid>:<op>" for a uid, "p<uid>:<package>:<op>" for a package */
	private static Properties sModes;
	/* {switch op or OP_NONE, package or null, listener} */
	private static final List<Object[]> sModeWatchers = new ArrayList<>();
	/* {int[] ops, delivering listener, the caller's listener} */
	private static final List<Object[]> sActiveWatchers = new ArrayList<>();
	/* "<uid>:<package>:<op>" -> how many starts are not finished */
	private static final Map<String, Integer> sActive = new HashMap<>();

	private final Context mContext;

	public AppOpsManager() {
		this(null);
	}

	/** {@hide} */
	public AppOpsManager(Context context) {
		mContext = context;
	}

	private static File modesFile() {
		return new File(android.atl.ATLPaths.app_data_dir_base, "_system_/appops.properties");
	}

	private static Properties modesLocked() {
		if (sModes == null) {
			sModes = new Properties();
			File f = modesFile();
			if (f.isFile()) {
				try (FileInputStream in = new FileInputStream(f)) {
					sModes.load(in);
				} catch (IOException | IllegalArgumentException e) {
					Slog.w(TAG, "could not read " + f, e);
				}
			}
		}
		return sModes;
	}

	private static void writeModesLocked() {
		File f = modesFile();
		File tmp = new File(f.getPath() + ".tmp");
		try {
			f.getParentFile().mkdirs();
			try (FileOutputStream out = new FileOutputStream(tmp)) {
				sModes.store(out, "app op modes set by the platform (AOSP appops.xml)");
			}
			if (!tmp.renameTo(f))
				Slog.w(TAG, "could not replace " + f);
		} catch (IOException e) {
			Slog.w(TAG, "could not write " + f, e);
		}
	}

	/* ---- the table (AOSP static helpers) ---- */

	/** AOSP (hidden): the op code of an op string; IllegalArgumentException for an unknown one. */
	public static int strOpToOp(String op) {
		Integer val = sOpStrToOp.get(op);
		if (val == null)
			throw new IllegalArgumentException("Unknown operation string: " + op);
		return val;
	}

	/** AOSP (hidden) */
	public static String opToPublicName(int op) {
		return sOpToString[op];
	}

	/** AOSP (hidden): the op's name (its op string without the "android:" prefix, upper case). */
	public static String opToName(int op) {
		if (op == OP_NONE)
			return "NONE";
		if (op < 0 || op >= _NUM_OP)
			return "Unknown(" + op + ")";
		String s = sOpToString[op];
		return s == null ? "DEPRECATED_1" : s.substring("android:".length()).toUpperCase(java.util.Locale.ROOT);
	}

	/** AOSP (hidden) */
	public static int opToSwitch(int op) {
		return sOpToSwitch[op];
	}

	/** AOSP (hidden) */
	public static String opToPermission(int op) {
		return sOpPerms[op];
	}

	/** AOSP: the permission behind an op, or null. */
	public static String opToPermission(String op) {
		return opToPermission(strOpToOp(op));
	}

	/** AOSP (hidden) */
	public static int opToDefaultMode(int op) {
		return sOpDefaultMode[op];
	}

	/** AOSP (hidden): the op code a runtime or appop permission is mirrored into, or OP_NONE. */
	public static int permissionToOpCode(String permission) {
		Integer op = sPermToOp.get(permission);
		return op != null ? op : OP_NONE;
	}

	/** AOSP: the op string a runtime or appop permission is mirrored into, or null. */
	public static String permissionToOp(String permission) {
		Integer op = sPermToOp.get(permission);
		return op == null ? null : sOpToString[op];
	}

	/** AOSP (hidden) */
	public static String modeToName(int mode) {
		switch (mode) {
			case MODE_ALLOWED: return "allow";
			case MODE_IGNORED: return "ignore";
			case MODE_ERRORED: return "deny";
			case MODE_DEFAULT: return "default";
			case MODE_FOREGROUND: return "foreground";
			default: return "mode=" + mode;
		}
	}

	/* ---- the mode of an op (AppOpsService) ---- */

	private static boolean granted(String permission, int uid, String packageName) {
		PackageManager pm = new PackageManager();
		int r = packageName != null ? pm.checkPermission(permission, packageName) : pm.checkUidPermission(permission, uid);
		return r == PackageManager.PERMISSION_GRANTED;
	}

	/* PermissionPolicyService.PermissionToOpSynchroniser: the mode a runtime permission's state gives its op */
	private static int runtimePermissionMode(int op, int uid, String packageName) {
		if (!granted(sOpPerms[op], uid, packageName))
			return MODE_IGNORED;
		switch (op) {
			case OP_COARSE_LOCATION:
			case OP_FINE_LOCATION:
				return granted("android.permission.ACCESS_BACKGROUND_LOCATION", uid, packageName) ? MODE_ALLOWED : MODE_FOREGROUND;
			case OP_CAMERA:
			case OP_RECORD_AUDIO:
				/* their background permissions are system-only */
				return MODE_FOREGROUND;
			default:
				return MODE_ALLOWED;
		}
	}

	/** the raw mode of an op (switch code applied) for a uid and package */
	private static int rawMode(int code, int uid, String packageName) {
		int op = sOpToSwitch[code];
		if (uid == Process.SYSTEM_UID || uid == 0)
			return MODE_ALLOWED; // AOSP: the system and root are not restricted by app ops
		synchronized (sLock) {
			Properties modes = modesLocked();
			String pkgMode = packageName != null ? modes.getProperty("p" + uid + ":" + packageName + ":" + op) : null;
			if (pkgMode != null)
				return Integer.parseInt(pkgMode);
			String uidMode = modes.getProperty("u" + uid + ":" + op);
			if (uidMode != null)
				return Integer.parseInt(uidMode);
		}
		if (sRuntimePermissionOp[op] && sOpPerms[op] != null)
			return runtimePermissionMode(op, uid, packageName);
		return sOpDefaultMode[op];
	}

	/* AOSP evalMode: the app this runtime runs is the foreground app */
	private static int evalMode(int mode) {
		return mode == MODE_FOREGROUND ? MODE_ALLOWED : mode;
	}

	/* AOSP verifyAndGetBypass: the package must belong to the uid */
	private static boolean packageBelongsToUid(int uid, String packageName) {
		if (packageName == null)
			return true;
		if (uid == Process.SYSTEM_UID && "android".equals(packageName))
			return true;
		android.atl.ATLLoadedApp app = android.atl.ATLLoadedApp.getPrimaryApplication();
		if (app != null && app.pkg != null && packageName.equals(app.pkg.packageName))
			return uid == Process.myUid();
		for (String p : android.atl.ATLPackageSettings.packagesForUid(uid)) {
			if (p.equals(packageName))
				return true;
		}
		return false;
	}

	private static String securityExceptionMessage(int op, int uid, String packageName) {
		return packageName + " from uid " + uid + " not allowed to perform " + opToName(op);
	}

	/* ---- checking ---- */

	public int unsafeCheckOpRawNoThrow(String op, int uid, String packageName) {
		return unsafeCheckOpRawNoThrow(strOpToOp(op), uid, packageName);
	}

	/** {@hide} */
	public int unsafeCheckOpRawNoThrow(int op, int uid, String packageName) {
		if (!packageBelongsToUid(uid, packageName))
			return MODE_IGNORED;
		return rawMode(op, uid, packageName);
	}

	public int unsafeCheckOpRaw(String op, int uid, String packageName) {
		int mode = unsafeCheckOpRawNoThrow(op, uid, packageName);
		if (mode == MODE_ERRORED)
			throw new SecurityException(securityExceptionMessage(strOpToOp(op), uid, packageName));
		return mode;
	}

	public int unsafeCheckOpNoThrow(String op, int uid, String packageName) {
		return evalMode(unsafeCheckOpRawNoThrow(op, uid, packageName));
	}

	public int unsafeCheckOp(String op, int uid, String packageName) {
		int mode = unsafeCheckOpNoThrow(op, uid, packageName);
		if (mode == MODE_ERRORED)
			throw new SecurityException(securityExceptionMessage(strOpToOp(op), uid, packageName));
		return mode;
	}

	@Deprecated
	public int checkOp(String op, int uid, String packageName) {
		return unsafeCheckOp(op, uid, packageName);
	}

	@Deprecated
	public int checkOpNoThrow(String op, int uid, String packageName) {
		return unsafeCheckOpNoThrow(op, uid, packageName);
	}

	/** {@hide} */
	public int checkOp(int op, int uid, String packageName) {
		int mode = checkOpNoThrow(op, uid, packageName);
		if (mode == MODE_ERRORED)
			throw new SecurityException(securityExceptionMessage(op, uid, packageName));
		return mode;
	}

	/** {@hide} */
	public int checkOpNoThrow(int op, int uid, String packageName) {
		return evalMode(unsafeCheckOpRawNoThrow(op, uid, packageName));
	}

	/** AOSP: SecurityException unless the package belongs to the uid. */
	public void checkPackage(int uid, String packageName) {
		if (packageName == null || !packageBelongsToUid(uid, packageName))
			throw new SecurityException("Package " + packageName + " does not belong to " + uid);
	}

	/* ---- noting ---- */

	/** {@hide} */
	public int noteOpNoThrow(int op, int uid, String packageName, String attributionTag, String message) {
		if (!packageBelongsToUid(uid, packageName))
			return MODE_ERRORED;
		return evalMode(rawMode(op, uid, packageName));
	}

	/** {@hide} */
	public int noteOp(int op, int uid, String packageName, String attributionTag, String message) {
		int mode = noteOpNoThrow(op, uid, packageName, attributionTag, message);
		if (mode == MODE_ERRORED)
			throw new SecurityException(securityExceptionMessage(op, uid, packageName));
		return mode;
	}

	/** {@hide} */
	public int noteOp(int op, int uid, String packageName) {
		return noteOp(op, uid, packageName, null, null);
	}

	/** {@hide} */
	public int noteOpNoThrow(int op, int uid, String packageName) {
		return noteOpNoThrow(op, uid, packageName, null, null);
	}

	@Deprecated
	public int noteOp(String op, int uid, String packageName) {
		return noteOp(strOpToOp(op), uid, packageName, null, null);
	}

	public int noteOp(String op, int uid, String packageName, String attributionTag, String message) {
		return noteOp(strOpToOp(op), uid, packageName, attributionTag, message);
	}

	@Deprecated
	public int noteOpNoThrow(String op, int uid, String packageName) {
		return noteOpNoThrow(strOpToOp(op), uid, packageName, null, null);
	}

	public int noteOpNoThrow(String op, int uid, String packageName, String attributionTag, String message) {
		return noteOpNoThrow(strOpToOp(op), uid, packageName, attributionTag, message);
	}

	/* AOSP noteProxyOp: the proxied app is checked first, then the caller (this app) as the proxy */
	public int noteProxyOpNoThrow(String op, String proxiedPackageName, int proxiedUid, String proxiedAttributionTag, String message) {
		int code = strOpToOp(op);
		int proxied = noteOpNoThrow(code, proxiedUid, proxiedPackageName, proxiedAttributionTag, message);
		if (proxied != MODE_ALLOWED)
			return proxied;
		String self = mContext != null ? mContext.getOpPackageName() : null;
		return noteOpNoThrow(code, Binder.getCallingUid(), self, null, message);
	}

	public int noteProxyOp(String op, String proxiedPackageName, int proxiedUid, String proxiedAttributionTag, String message) {
		int mode = noteProxyOpNoThrow(op, proxiedPackageName, proxiedUid, proxiedAttributionTag, message);
		if (mode == MODE_ERRORED)
			throw new SecurityException("Proxy package " + (mContext != null ? mContext.getOpPackageName() : null) + " from uid " + Binder.getCallingUid()
			    + " or calling package " + proxiedPackageName + " from uid " + proxiedUid + " not allowed to perform " + opToName(strOpToOp(op)));
		return mode;
	}

	@Deprecated
	public int noteProxyOp(String op, String proxiedPackageName) {
		return noteProxyOp(op, proxiedPackageName, Binder.getCallingUid(), null, null);
	}

	@Deprecated
	public int noteProxyOpNoThrow(String op, String proxiedPackageName) {
		return noteProxyOpNoThrow(op, proxiedPackageName, Binder.getCallingUid(), null, null);
	}

	public int noteProxyOpNoThrow(String op, String proxiedPackageName, int proxiedUid) {
		return noteProxyOpNoThrow(op, proxiedPackageName, proxiedUid, null, null);
	}

	/* ---- starting and finishing (active ops) ---- */

	private static void dispatchActive(int op, int uid, String packageName, boolean active) {
		List<OnOpActiveChangedListener> targets = new ArrayList<>();
		synchronized (sLock) {
			for (Object[] w : sActiveWatchers) {
				for (int o : (int[]) w[0]) {
					if (o == op) {
						targets.add((OnOpActiveChangedListener) w[1]);
						break;
					}
				}
			}
		}
		for (OnOpActiveChangedListener l : targets)
			l.onOpActiveChanged(sOpToString[op], uid, packageName, active);
	}

	/** {@hide} */
	public int startOpNoThrow(int op, int uid, String packageName, boolean startIfModeDefault, String attributionTag, String message) {
		int mode = noteOpNoThrow(op, uid, packageName, attributionTag, message);
		if (mode != MODE_ALLOWED && !(startIfModeDefault && mode == MODE_DEFAULT))
			return mode;
		boolean first;
		String key = uid + ":" + packageName + ":" + op;
		synchronized (sLock) {
			Integer n = sActive.get(key);
			first = n == null;
			sActive.put(key, n == null ? 1 : n + 1);
		}
		if (first)
			dispatchActive(op, uid, packageName, true);
		return mode;
	}

	public int startOpNoThrow(String op, int uid, String packageName, String attributionTag, String message) {
		return startOpNoThrow(strOpToOp(op), uid, packageName, false, attributionTag, message);
	}

	@Deprecated
	public int startOpNoThrow(String op, int uid, String packageName) {
		return startOpNoThrow(op, uid, packageName, null, null);
	}

	public int startOp(String op, int uid, String packageName, String attributionTag, String message) {
		int mode = startOpNoThrow(op, uid, packageName, attributionTag, message);
		if (mode == MODE_ERRORED)
			throw new SecurityException(securityExceptionMessage(strOpToOp(op), uid, packageName));
		return mode;
	}

	@Deprecated
	public int startOp(String op, int uid, String packageName) {
		return startOp(op, uid, packageName, null, null);
	}

	/** {@hide} */
	public void finishOp(int op, int uid, String packageName, String attributionTag) {
		boolean last = false;
		String key = uid + ":" + packageName + ":" + op;
		synchronized (sLock) {
			Integer n = sActive.get(key);
			if (n != null) {
				if (n <= 1) {
					sActive.remove(key);
					last = true;
				} else {
					sActive.put(key, n - 1);
				}
			}
		}
		if (last)
			dispatchActive(op, uid, packageName, false);
	}

	public void finishOp(String op, int uid, String packageName, String attributionTag) {
		finishOp(strOpToOp(op), uid, packageName, attributionTag);
	}

	public void finishOp(String op, int uid, String packageName) {
		finishOp(strOpToOp(op), uid, packageName, null);
	}

	/** AOSP (hidden): whether the op is being performed by the package. */
	public boolean isOpActive(String op, int uid, String packageName) {
		synchronized (sLock) {
			return sActive.containsKey(uid + ":" + packageName + ":" + strOpToOp(op));
		}
	}

	public void startWatchingActive(String[] ops, final java.util.concurrent.Executor executor, final OnOpActiveChangedListener callback) {
		if (ops == null || callback == null)
			throw new NullPointerException();
		int[] codes = new int[ops.length];
		for (int i = 0; i < ops.length; i++)
			codes[i] = strOpToOp(ops[i]);
		OnOpActiveChangedListener deliver = executor == null ? callback : new OnOpActiveChangedListener() {
			@Override
			public void onOpActiveChanged(final String op, final int uid, final String packageName, final boolean active) {
				executor.execute(new Runnable() {
					public void run() {
						callback.onOpActiveChanged(op, uid, packageName, active);
					}
				});
			}
		};
		synchronized (sLock) {
			sActiveWatchers.add(new Object[] {codes, deliver, callback});
		}
	}

	public void stopWatchingActive(OnOpActiveChangedListener callback) {
		synchronized (sLock) {
			for (int i = sActiveWatchers.size() - 1; i >= 0; i--) {
				if (sActiveWatchers.get(i)[2] == callback)
					sActiveWatchers.remove(i);
			}
		}
	}

	/* ---- modes (MANAGE_APP_OPS_MODES: the platform only) ---- */

	private static void enforceManageAppOpsModes() {
		int uid = Binder.getCallingUid();
		if (uid != Process.SYSTEM_UID && uid != 0)
			throw new SecurityException("uid " + uid + " does not have android.permission.MANAGE_APP_OPS_MODES.");
	}

	private static void dispatchModeChanged(int op, String packageName) {
		List<OnOpChangedListener> targets = new ArrayList<>();
		synchronized (sLock) {
			for (Object[] w : sModeWatchers) {
				int wop = (Integer) w[0];
				String wpkg = (String) w[1];
				if ((wop == OP_NONE || wop == op) && (wpkg == null || packageName == null || wpkg.equals(packageName)))
					targets.add((OnOpChangedListener) w[2]);
			}
		}
		for (OnOpChangedListener l : targets)
			l.onOpChanged(sOpToString[op], packageName);
	}

	/** {@hide} AOSP setMode: the mode of the op for a package. */
	public void setMode(int code, int uid, String packageName, int mode) {
		enforceManageAppOpsModes();
		int op = sOpToSwitch[code];
		synchronized (sLock) {
			Properties modes = modesLocked();
			String key = "p" + uid + ":" + packageName + ":" + op;
			if (mode == sOpDefaultMode[op] && !sRuntimePermissionOp[op])
				modes.remove(key);
			else
				modes.setProperty(key, Integer.toString(mode));
			writeModesLocked();
		}
		dispatchModeChanged(op, packageName);
	}

	/** {@hide} */
	public void setMode(String op, int uid, String packageName, int mode) {
		setMode(strOpToOp(op), uid, packageName, mode);
	}

	/** {@hide} AOSP setUidMode: the mode of the op for every package of the uid. */
	public void setUidMode(int code, int uid, int mode) {
		enforceManageAppOpsModes();
		int op = sOpToSwitch[code];
		synchronized (sLock) {
			Properties modes = modesLocked();
			String key = "u" + uid + ":" + op;
			if (mode == sOpDefaultMode[op] && !sRuntimePermissionOp[op])
				modes.remove(key);
			else
				modes.setProperty(key, Integer.toString(mode));
			writeModesLocked();
		}
		dispatchModeChanged(op, null);
	}

	/** {@hide} */
	public void setUidMode(String appOp, int uid, int mode) {
		setUidMode(strOpToOp(appOp), uid, mode);
	}

	public void startWatchingMode(String op, String packageName, OnOpChangedListener callback) {
		startWatchingMode(op, packageName, 0, callback);
	}

	public void startWatchingMode(String op, String packageName, int flags, OnOpChangedListener callback) {
		if (callback == null)
			throw new NullPointerException("callback");
		int code = op == null ? OP_NONE : sOpToSwitch[strOpToOp(op)];
		synchronized (sLock) {
			sModeWatchers.add(new Object[] {code, packageName, callback});
		}
	}

	public void stopWatchingMode(OnOpChangedListener callback) {
		synchronized (sLock) {
			for (int i = sModeWatchers.size() - 1; i >= 0; i--) {
				if (sModeWatchers.get(i)[2] == callback)
					sModeWatchers.remove(i);
			}
		}
	}
}
