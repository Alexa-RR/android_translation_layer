aapt = find_program('aapt', required: false)

if aapt.found()
  aapt_command = [aapt, 'package', '-x', '-f', '--custom-package', 'com.android.internal', '-S', join_paths(dir_base, 'res/framework-res/res'), '-A', join_paths(dir_base, 'res/framework-res/assets'), '-M', join_paths(dir_base, 'res/framework-res/AndroidManifest.xml'), '-J', join_paths(dir_base, 'src/api-impl/com/android/internal'), '-F', '@OUTPUT@']
else  # fallback to prebuilt APK
  aapt_command = ['cp', join_paths(dir_base, 'res/framework-res/framework-res.apk'), '@OUTPUT@']
endif

  framework_res_unsigned = custom_target('framework-res-unsigned.apk',
    output : 'framework-res-unsigned.apk',
    command : aapt_command)

# the framework package is signed with ATL's platform key, as AOSP signs its framework
# (build-aux/sign-framework-res.sh; -Dplatform_keystore=PATH, else a key made in the build directory)
keytool = find_program('keytool')
jarsigner = find_program('jarsigner')
platform_keystore = get_option('platform_keystore')
if platform_keystore == ''
  platform_keystore = meson.current_build_dir() / 'platform-key.p12'
endif

  framework_res = custom_target('framework-res.apk',
    input : framework_res_unsigned,
    output : 'framework-res.apk',
    command : [find_program('sh'), files(join_paths(dir_base, 'build-aux/sign-framework-res.sh')), keytool, jarsigner, platform_keystore, '@INPUT@', '@OUTPUT@'],
    install : true,
    install_dir : get_option('libdir') / 'java/dex/android_translation_layer')
