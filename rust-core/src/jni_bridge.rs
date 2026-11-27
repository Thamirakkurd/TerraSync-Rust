use jni::objects::{JByteArray, JClass, JFloatArray, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong};
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

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeRouteOffline<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tsr_path: JString<'local>,
    last_modified: jlong,
    file_size: jlong,
    start_lat: jdouble,
    start_lng: jdouble,
    end_lat: jdouble,
    end_lng: jdouble,
    mode_code: jint,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let path_str: String = env.get_string(&tsr_path).ok()?.into();
        let mode = crate::routing::TravelMode::from_code(mode_code)?;
        let route_res = crate::routing::route_from_tsr(
            &path_str,
            last_modified,
            file_size,
            start_lat,
            start_lng,
            end_lat,
            end_lng,
            mode,
        )?;
        let encoded = crate::routing::encode_route_result(&route_res);
        env.byte_array_from_slice(&encoded).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(arr)) => arr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeEvictRoadGraph<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tsr_path: JString<'local>,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if let Ok(path_str) = env.get_string(&tsr_path) {
            let s: String = path_str.into();
            crate::routing::evict_graph(&s);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativePatchMvtKurdish<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    raw_mvt: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&raw_mvt).ok()?;
        let out_slice: Vec<u8> = crate::mvt::patch_tile(&bytes).unwrap_or_default();
        env.byte_array_from_slice(&out_slice).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(arr)) => arr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeWarmPmTiles<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    last_modified: jlong,
    file_size: jlong,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let path_str: String = env.get_string(&path).ok()?.into();
        Some(crate::pmtiles::warm_archive(&path_str, last_modified, file_size))
    }));
    match result {
        Ok(Some(true)) => 1,
        _ => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeLookupPmTile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    last_modified: jlong,
    file_size: jlong,
    tile_id: jlong,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if tile_id < 0 {
            return None;
        }
        let path_str: String = env.get_string(&path).ok()?.into();
        let bytes_opt = crate::pmtiles::lookup_tile(
            &path_str,
            last_modified,
            file_size,
            tile_id as u64,
        )
        .ok()?;
        let slice = bytes_opt.unwrap_or_default();
        env.byte_array_from_slice(&slice).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(arr)) => arr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeEvictPmTiles<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if let Ok(path_str) = env.get_string(&path) {
            let s: String = path_str.into();
            crate::pmtiles::evict_archive(&s);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeExtractContours<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    elev: JFloatArray<'local>,
    z: jint,
    x: jint,
    y: jint,
    n: jint,
    step: jint,
    major_step: jint,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if n <= 0 {
            return None;
        }
        let len = env.get_array_length(&elev).ok()? as usize;
        let mut buf = vec![0.0f32; len];
        env.get_float_array_region(&elev, 0, &mut buf).ok()?;
        let lines = crate::contour::extract_contours(&buf, z, x, y, n as usize, step, major_step);
        let encoded = crate::contour::encode_contours(&lines);
        env.byte_array_from_slice(&encoded).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(arr)) => arr,
        _ => std::ptr::null_mut(),
    }
}
