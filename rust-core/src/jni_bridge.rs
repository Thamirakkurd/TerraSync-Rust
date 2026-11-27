use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativePing(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeParseKmlBytes<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&input).ok()?;
        let parsed = crate::kml::parse_kml_bytes(&bytes);
        let encoded = crate::kml::encode_parsed_kml(&parsed);
        env.byte_array_from_slice(&encoded).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(arr)) => arr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeParseKmlFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let path_str: String = env.get_string(&path).ok()?.into();
        let bytes = crate::kml::read_bounded_file(&path_str)?;
        let parsed = crate::kml::parse_kml_bytes(&bytes);
        let encoded = crate::kml::encode_parsed_kml(&parsed);
        env.byte_array_from_slice(&encoded).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(arr)) => arr,
        _ => std::ptr::null_mut(),
    }
}
