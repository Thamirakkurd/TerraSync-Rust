use jni::objects::{JByteArray, JClass, JDoubleArray, JFloatArray, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jfloatArray, jint, jlong, jlongArray, jsize};
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
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
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
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeBuildKmlBytes<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let xml = crate::kml_export::build_kml_bytes(&bytes)?;
        env.byte_array_from_slice(&xml).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeExportKmlFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
    out_path: JString<'local>,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let path_str: String = env.get_string(&out_path).ok()?.into();
        Some(crate::kml_export::export_kml_to_file(&bytes, &path_str))
    }));
    match result { Ok(Some(true)) => 1, _ => 0 }
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
            &path_str, last_modified, file_size, start_lat, start_lng, end_lat, end_lng, mode,
        )?;
        let encoded = crate::routing::encode_route_result(&route_res);
        env.byte_array_from_slice(&encoded).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
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
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeGunzipAndPatchMvt<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    raw_or_gz: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&raw_or_gz).ok()?;
        let out_slice: Vec<u8> = crate::pmtiles::gunzip_and_patch_mvt(&bytes).unwrap_or_default();
        env.byte_array_from_slice(&out_slice).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
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
    match result { Ok(Some(true)) => 1, _ => 0 }
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
        if tile_id < 0 { return None; }
        let path_str: String = env.get_string(&path).ok()?.into();
        let bytes_opt = crate::pmtiles::lookup_tile(&path_str, last_modified, file_size, tile_id as u64).ok()?;
        let slice = bytes_opt.unwrap_or_default();
        env.byte_array_from_slice(&slice).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeLookupAndPatchPmTile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    last_modified: jlong,
    file_size: jlong,
    tile_id: jlong,
    tile_is_gzipped: jboolean,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if tile_id < 0 { return None; }
        let path_str: String = env.get_string(&path).ok()?.into();
        let bytes_opt = crate::pmtiles::lookup_and_patch_tile(
            &path_str, last_modified, file_size, tile_id as u64, tile_is_gzipped != 0,
        ).ok()?;
        let slice = bytes_opt.unwrap_or_default();
        env.byte_array_from_slice(&slice).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
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
        if n <= 0 { return None; }
        let len = env.get_array_length(&elev).ok()? as usize;
        let mut buf = vec![0.0f32; len];
        env.get_float_array_region(&elev, 0, &mut buf).ok()?;
        let lines = crate::contour::extract_contours(&buf, z, x, y, n as usize, step, major_step);
        let encoded = crate::contour::encode_contours(&lines);
        env.byte_array_from_slice(&encoded).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeQuerySearchIndex<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    expected_len: jint,
    query_payload: JByteArray<'local>,
) -> jbyteArray {
    if expected_len < 0 {
        return std::ptr::null_mut();
    }
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(&query_payload).ok()?;
        let out = crate::search::query_geo_pins(version, expected_len as usize, &bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(raw)) => raw,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeFindNearestPin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    expected_len: jint,
    query_payload: JByteArray<'local>,
) -> jbyteArray {
    if expected_len < 0 {
        return std::ptr::null_mut();
    }
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(&query_payload).ok()?;
        let out = crate::geojson::find_nearest_pin(version, expected_len as usize, &bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(raw)) => raw,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeCountVisibleStatuses<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    expected_len: jint,
    filter_payload: JByteArray<'local>,
) -> jbyteArray {
    if expected_len < 0 {
        return std::ptr::null_mut();
    }
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(&filter_payload).ok()?;
        let out = crate::geojson::count_visible_statuses(version, expected_len as usize, &bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(raw)) => raw,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeLoadGeoPins<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    payload: JByteArray<'local>,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        Some(crate::geojson::load_geo_pins(version, &bytes))
    }));
    match result { Ok(Some(true)) => 1, _ => 0 }
}


#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeClearGeoPins(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = std::panic::catch_unwind(crate::geojson::clear_geo_pins);
}


#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeFilterPinsInPolygon<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    expected_len: jint,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if expected_len < 0 { return None; }
        let bytes = env.convert_byte_array(&payload).ok()?;
        let out = crate::geojson::filter_pins_in_polygon(version, expected_len as usize, &bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeAnalyzeSameCoordClusters<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let out = crate::pins_ops::analyze_same_coord_clusters(&bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeSortAndScorePins<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let out = crate::pins_ops::sort_and_score_pins(&bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeFindExactStackedPins<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    expected_len: jint,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if expected_len < 0 { return None; }
        let bytes = env.convert_byte_array(&payload).ok()?;
        let out = crate::geojson::find_exact_stacked_pins(version, expected_len as usize, &bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeDeduplicatePins<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let out = crate::report::deduplicate_pins_indices(&bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeFindDuplicateExtraIds<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let out = crate::report::find_duplicate_extra_indices(&bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeBuildDailyReport<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let out = crate::report::build_daily_report(&bytes)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeDecodePolyline6<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    encoded: JString<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let s: String = env.get_string(&encoded).ok()?.into();
        let out = crate::nav_geo::decode_polyline6_wire(&s);
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeLoadNavRoute<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    route_id: jlong,
    coords_lng_lat: JDoubleArray<'local>,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let len = env.get_array_length(&coords_lng_lat).ok()? as usize;
        let mut buf = vec![0.0f64; len];
        env.get_double_array_region(&coords_lng_lat, 0, &mut buf).ok()?;
        Some(crate::nav_geo::load_nav_route(route_id, &buf))
    }));
    match result { Ok(Some(true)) => 1, _ => 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeTrackNavRoute<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    route_id: jlong,
    expected_len: jint,
    lat: jdouble,
    lng: jdouble,
    last_seg_idx: jint,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if expected_len < 2 { return None; }
        let out = crate::nav_geo::track_nav_route(route_id, expected_len as usize, lat, lng, last_seg_idx)?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeExportCsvFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
    out_path: JString<'local>,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let path_str: String = env.get_string(&out_path).ok()?.into();
        Some(crate::csv_export::export_csv_file(&bytes, &path_str))
    }));
    match result { Ok(Some(true)) => 1, _ => 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeDecodeTerrariumGrid<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    pixels: JIntArray<'local>,
    w: jint,
    h: jint,
    n: jint,
) -> jfloatArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if w <= 0 || h <= 0 || n <= 0 { return None; }
        let len = env.get_array_length(&pixels).ok()? as usize;
        let mut buf = vec![0i32; len];
        env.get_int_array_region(&pixels, 0, &mut buf).ok()?;
        let elev = crate::contour_geo::decode_terrarium_grid(&buf, w as usize, h as usize, n as usize)?;
        let out_arr = env.new_float_array(elev.len() as jsize).ok()?;
        env.set_float_array_region(&out_arr, 0, &elev).ok()?;
        Some(out_arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeBuildContoursGeoJsonBatch<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        let json = crate::contour_geo::build_contours_geojson_batch(&bytes)?;
        env.byte_array_from_slice(&json).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeCountOutsideWorkingHours<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        crate::audit::count_outside_working_hours(&bytes)
    }));
    match result { Ok(Some(v)) => v, _ => -1 }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativePatchGeoPins<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    old_version: jlong,
    new_version: jlong,
    expected_len: jint,
    payload: JByteArray<'local>,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if expected_len < 0 { return None; }
        let bytes = env.convert_byte_array(&payload).ok()?;
        Some(crate::geojson::patch_geo_pins(
            old_version,
            new_version,
            expected_len as usize,
            &bytes,
        ))
    }));
    match result { Ok(Some(true)) => 1, _ => 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeSetMvtPinsFilter<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        Some(crate::mvt_pins::set_mvt_filter(&bytes))
    }));
    match result { Ok(Some(true)) => 1, _ => 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeRenderPinsMvtTile<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    z: jint,
    x: jint,
    y: jint,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let mvt = crate::mvt_pins::render_pins_mvt_tile(z, x, y);
        env.byte_array_from_slice(&mvt).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeIsGeoPinsSynced(
    _env: JNIEnv,
    _class: JClass,
    version: jlong,
    expected_len: jint,
) -> jboolean {
    if expected_len < 0 {
        return 0;
    }
    let ok = std::panic::catch_unwind(|| {
        crate::geojson::is_geo_pins_synced(version, expected_len as usize)
    })
    .unwrap_or(false);
    if ok { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeLoadGeoTracks<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    payload: JByteArray<'local>,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let bytes = env.convert_byte_array(&payload).ok()?;
        Some(crate::mvt_tracks::load_geo_tracks(version, &bytes))
    }));
    match result { Ok(Some(true)) => 1, _ => 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeIsGeoTracksSynced(
    _env: JNIEnv,
    _class: JClass,
    version: jlong,
    expected_len: jint,
) -> jboolean {
    if expected_len < 0 { return 0; }
    let ok = std::panic::catch_unwind(|| {
        crate::mvt_tracks::is_geo_tracks_synced(version, expected_len as usize)
    }).unwrap_or(false);
    if ok { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeClearGeoTracks(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = std::panic::catch_unwind(crate::mvt_tracks::clear_geo_tracks);
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeFilterDirtyMvtTiles<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    packed_tiles: JLongArray<'local>,
) -> jlongArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let len = env.get_array_length(&packed_tiles).ok()? as usize;
        let mut buf = vec![0i64; len];
        env.get_long_array_region(&packed_tiles, 0, &mut buf).ok()?;
        let dirty = crate::mvt_pins::filter_dirty_mvt_tiles(version, &buf)?;
        let out = env.new_long_array(dirty.len() as jsize).ok()?;
        env.set_long_array_region(&out, 0, &dirty).ok()?;
        Some(out.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeComputeResidentBounds<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jlong,
    pins_len: jint,
    tracks_len: jint,
    layer_id: JString<'local>,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        if pins_len < 0 || tracks_len < 0 { return None; }
        let lid: String = env.get_string(&layer_id).ok()?.into();
        let out = crate::mvt_tracks::compute_resident_bounds(
            version,
            pins_len as usize,
            tracks_len as usize,
            &lid,
        )?;
        env.byte_array_from_slice(&out).ok().map(|arr| arr.into_raw())
    }));
    match result { Ok(Some(arr)) => arr, _ => std::ptr::null_mut() }
}


#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeTsrCompilerCreate<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::tsr_compiler::create_session()
    }));
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeTsrCompilerIngestChunk<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    chunk_path: JString<'local>,
) -> jni::sys::jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let path: String = match env.get_string(&chunk_path) {
            Ok(s) => s.into(),
            Err(_) => return env.new_string("Invalid chunk path").ok().map(|s| s.into_raw()),
        };
        match crate::tsr_compiler::ingest_chunk(handle, &path) {
            Ok(()) => None,
            Err(err) => env.new_string(err).ok().map(|s| s.into_raw()),
        }
    }));
    match result {
        Ok(Some(s)) => s,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeTsrCompilerCounts<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jni::sys::jintArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let (nodes, edges, names) = crate::tsr_compiler::counts(handle)?;
        let arr = env.new_int_array(3).ok()?;
        env.set_int_array_region(&arr, 0, &[nodes, edges, names]).ok()?;
        Some(arr.into_raw())
    }));
    match result {
        Ok(Some(arr)) => arr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeTsrCompilerWriteTsr<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    part_path: JString<'local>,
    out_path: JString<'local>,
    region_name: JString<'local>,
    s_lat: jni::sys::jdouble,
    w_lng: jni::sys::jdouble,
    n_lat: jni::sys::jdouble,
    e_lng: jni::sys::jdouble,
    now: jlong,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let part: String = env.get_string(&part_path).ok()?.into();
        let out_p: String = env.get_string(&out_path).ok()?.into();
        let name: String = env.get_string(&region_name).ok()?.into();
        let payload = match crate::tsr_compiler::write_tsr(
            handle, &part, &out_p, &name, s_lat, w_lng, n_lat, e_lng, now,
        ) {
            Ok(size) => {
                let mut v = Vec::with_capacity(9);
                v.push(1u8);
                v.extend_from_slice(&(size as i64).to_le_bytes());
                v
            }
            Err(err) => {
                let b = err.as_bytes();
                let mut v = Vec::with_capacity(1 + b.len());
                v.push(0u8);
                v.extend_from_slice(b);
                v
            }
        };
        env.byte_array_from_slice(&payload).ok().map(|arr| arr.into_raw())
    }));
    match result {
        Ok(Some(arr)) => arr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeTsrCompilerDestroy<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::tsr_compiler::destroy_session(handle);
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_terrasync_app_RustCoreBridge_nativeTrimNativeMemory<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    aggressive: jni::sys::jboolean,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::memory::trim_native_memory(aggressive != 0) as jint
    }));
    result.unwrap_or(0)
}
