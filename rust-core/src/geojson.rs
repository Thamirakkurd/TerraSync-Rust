use std::collections::{HashMap, HashSet};
#[cfg(test)]
use std::io::Write;
use std::sync::{OnceLock, RwLock};

const EARTH_RADIUS_M: f64 = 6_371_000.0;

pub(crate) struct GeoPin {
    pub(crate) id: String,
    pub(crate) layer_id: String,
    pub(crate) name: String,
    pub(crate) label_shaped: String,
    pub(crate) status_code: u8, // 0=done, 1=issue, 2=pending
    pub(crate) lng: f64,
    pub(crate) lat: f64,
    pub(crate) mx: f64,
    pub(crate) my: f64,
    pub(crate) description: String,
    pub(crate) issue_reason: String,
    pub(crate) search_blob: String,
    pub(crate) coords_text: String,
}

pub(crate) struct CachedGeoPins {
    pub(crate) version: i64,
    pub(crate) done_icon: String,
    pub(crate) issue_icon: String,
    pub(crate) pending_icon: String,
    pub(crate) pins: Vec<GeoPin>,
    pub(crate) id_to_idx: HashMap<String, usize>,
    pub(crate) x_index: Vec<(f64, usize)>,
    pub(crate) last_delta_version: i64,
    pub(crate) last_delta_coords: Vec<(f64, f64)>,
    pub(crate) folder_names: HashMap<String, String>,
    pub(crate) has_folder_names: bool,
}

#[cfg(test)]
pub(crate) static TEST_CACHE_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

static GEO_PINS_CACHE: OnceLock<RwLock<Option<CachedGeoPins>>> = OnceLock::new();

pub(crate) fn cache_lock() -> &'static RwLock<Option<CachedGeoPins>> {
    GEO_PINS_CACHE.get_or_init(|| RwLock::new(None))
}

struct BinReader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> BinReader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn read_u8(&mut self) -> Option<u8> {
        let b = *self.buf.get(self.pos)?;
        self.pos += 1;
        Some(b)
    }

    fn read_u32(&mut self) -> Option<u32> {
        let slice = self.buf.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(u32::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_f64(&mut self) -> Option<f64> {
        let slice = self.buf.get(self.pos..self.pos + 8)?;
        self.pos += 8;
        Some(f64::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_str(&mut self) -> Option<&'a str> {
        let len = self.read_u32()? as usize;
        let slice = self.buf.get(self.pos..self.pos + len)?;
        self.pos += len;
        std::str::from_utf8(slice).ok()
    }

    fn consume_magic(&mut self, magic: &[u8; 4]) -> bool {
        if self.buf.get(self.pos..self.pos + 4) == Some(magic.as_slice()) {
            self.pos += 4;
            true
        } else {
            false
        }
    }
}

#[inline]
fn status_search_label(status_code: u8) -> &'static str {
    match status_code {
        0 => "done",
        1 => "issue",
        _ => "pending",
    }
}

fn build_search_blob(
    name: &str,
    description: &str,
    issue_reason: &str,
    status_code: u8,
    folder_name: &str,
) -> String {
    let st = status_search_label(status_code);
    let mut s = String::with_capacity(
        name.len() + description.len() + issue_reason.len() + st.len() + folder_name.len() + 8,
    );
    s.push_str(name);
    s.push(' ');
    if !description.trim().is_empty() {
        s.push_str(description);
        s.push(' ');
    }
    if !issue_reason.trim().is_empty() {
        s.push_str(issue_reason);
        s.push(' ');
    }
    s.push_str(st);
    s.push(' ');
    s.push_str(folder_name);
    s.to_lowercase()
}

fn format_coords_text(lat: f64, lng: f64) -> String {
    if lat.is_finite() && lng.is_finite() {
        format!("{:.6}, {:.6}", lat, lng)
    } else {
        String::new()
    }
}

#[inline]
fn haversine_meters(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let d_lat = (lat2 - lat1).to_radians();
    let d_lon = (lon2 - lon1).to_radians();
    let a = (d_lat * 0.5).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lon * 0.5).sin().powi(2);
    let c = 2.0 * a.sqrt().atan2((1.0 - a).max(0.0).sqrt());
    EARTH_RADIUS_M * c
}

#[inline]
pub(crate) fn lng_lat_to_mercator_unit(lng: f64, lat: f64) -> (f64, f64) {
    let mx = ((lng + 180.0) / 360.0).clamp(0.0, 1.0);
    let sin_lat = lat.clamp(-85.05112878, 85.05112878).to_radians().sin();
    let my = (0.5 - ((1.0 + sin_lat) / (1.0 - sin_lat)).ln() / (4.0 * std::f64::consts::PI)).clamp(0.0, 1.0);
    (mx, my)
}

#[cfg(test)]
#[inline]
fn web_mercator_px(lng: f64, lat: f64, world_px: f64) -> (f64, f64) {
    let x = ((lng + 180.0) / 360.0) * world_px;
    let sin_lat = lat.clamp(-85.05112878, 85.05112878).to_radians().sin();
    let y = (0.5 - ((1.0 + sin_lat) / (1.0 - sin_lat)).ln() / (4.0 * std::f64::consts::PI)) * world_px;
    (x, y)
}

fn truncate_and_shape_label(name: &str) -> String {
    let trimmed = name.trim();
    if trimmed.chars().count() > 22 {
        let prefix: String = trimmed.chars().take(21).collect();
        let mut shortened = prefix.trim_end().to_string();
        shortened.push('…');
        crate::kurdish::shape(&shortened)
    } else {
        crate::kurdish::shape(trimmed)
    }
}

#[cfg(test)]
#[inline]
fn push_json_str(out: &mut Vec<u8>, s: &str) {
    out.push(b'"');
    for &b in s.as_bytes() {
        match b {
            b'"' => out.extend_from_slice(b"\\\""),
            b'\\' => out.extend_from_slice(b"\\\\"),
            0x08 => out.extend_from_slice(b"\\b"),
            0x0C => out.extend_from_slice(b"\\f"),
            b'\n' => out.extend_from_slice(b"\\n"),
            b'\r' => out.extend_from_slice(b"\\r"),
            b'\t' => out.extend_from_slice(b"\\t"),
            0x00..=0x1F => {
                const HEX: &[u8; 16] = b"0123456789abcdef";
                out.extend_from_slice(&[
                    b'\\', b'u', b'0', b'0',
                    HEX[(b >> 4) as usize],
                    HEX[(b & 0x0F) as usize],
                ]);
            }
            _ => out.push(b),
        }
    }
    out.push(b'"');
}

pub fn load_geo_pins(version: i64, payload: &[u8]) -> bool {
    let mut r = BinReader::new(payload);
    let (Some(done_icon), Some(issue_icon), Some(pending_icon), Some(count)) = (
        r.read_str(),
        r.read_str(),
        r.read_str(),
        r.read_u32().map(|v| v as usize),
    ) else {
        return false;
    };

    let mut pins = Vec::with_capacity(count.min(250_000));
    let mut id_to_idx = HashMap::with_capacity(count.min(250_000));
    for idx in 0..count {
        let (Some(id), Some(layer_id), Some(name), Some(status_code), Some(lng), Some(lat)) = (
            r.read_str(),
            r.read_str(),
            r.read_str(),
            r.read_u8(),
            r.read_f64(),
            r.read_f64(),
        ) else {
            return false;
        };
        let id_owned = id.to_owned();
        id_to_idx.insert(id_owned.clone(), idx);
        let (mx, my) = if lng.is_finite() && lat.is_finite() {
            lng_lat_to_mercator_unit(lng, lat)
        } else {
            (-1.0, -1.0)
        };
        pins.push(GeoPin {
            id: id_owned,
            layer_id: layer_id.to_owned(),
            label_shaped: truncate_and_shape_label(name),
            name: name.to_owned(),
            status_code,
            lng,
            lat,
            mx,
            my,
            description: String::new(),
            issue_reason: String::new(),
            search_blob: String::new(),
            coords_text: format_coords_text(lat, lng),
        });
    }

    let mut folder_names: HashMap<String, String> = HashMap::new();
    let mut has_folder_names = false;
    if r.consume_magic(b"W12S") {
        let Some(f_count) = r.read_u32().map(|v| v as usize) else {
            return false;
        };
        folder_names.reserve(f_count.min(4096));
        for _ in 0..f_count {
            let (Some(fid), Some(fname)) = (r.read_str(), r.read_str()) else {
                return false;
            };
            folder_names.insert(fid.to_owned(), fname.to_owned());
        }
        has_folder_names = !folder_names.is_empty();
        for pin in &mut pins {
            let (Some(desc), Some(reason)) = (r.read_str(), r.read_str()) else {
                return false;
            };
            pin.description = desc.to_owned();
            pin.issue_reason = reason.to_owned();
        }
    }

    for pin in &mut pins {
        let fname = folder_names
            .get(pin.layer_id.as_str())
            .map(|s| s.as_str())
            .unwrap_or("Folder");
        pin.search_blob = build_search_blob(
            &pin.name,
            &pin.description,
            &pin.issue_reason,
            pin.status_code,
            fname,
        );
    }

    let mut x_index: Vec<(f64, usize)> = pins
        .iter()
        .enumerate()
        .filter(|(_, p)| p.mx >= 0.0)
        .map(|(i, p)| (p.mx, i))
        .collect();
    x_index.sort_by(|a, b| a.0.total_cmp(&b.0));

    let Ok(mut guard) = cache_lock().write() else {
        return false;
    };
    *guard = Some(CachedGeoPins {
        version,
        done_icon: done_icon.to_owned(),
        issue_icon: issue_icon.to_owned(),
        pending_icon: pending_icon.to_owned(),
        pins,
        id_to_idx,
        x_index,
        last_delta_version: -1,
        last_delta_coords: Vec::new(),
        folder_names,
        has_folder_names,
    });
    true
}

/// Viewport-culled, Earth-locked Web Mercator pixel-deduped GeoJSON builder.
/// Preserves true green/orange/red color mix with zero pan flicker.
#[cfg(test)]
pub fn build_pins_geojson_viewport(
    version: i64,
    expected_len: usize,
    filter_payload: &[u8],
) -> Option<Vec<u8>> {
    let guard = cache_lock().read().ok()?;
    let cached = guard.as_ref()?;
    if cached.version != version || cached.pins.len() != expected_len {
        return None;
    }

    let mut r = BinReader::new(filter_payload);
    let status_mask = r.read_u8()?;
    let hidden_count = r.read_u32()? as usize;
    let mut hidden_layers: HashSet<&str> = HashSet::with_capacity(hidden_count);
    for _ in 0..hidden_count {
        hidden_layers.insert(r.read_str()?);
    }
    let sent_count = r.read_u32()? as usize;
    let mut sent_ids: HashSet<&str> = HashSet::with_capacity(sent_count);
    for _ in 0..sent_count {
        sent_ids.insert(r.read_str()?);
    }

    let use_viewport = r.read_u8()? != 0;
    let south = r.read_f64()?;
    let west = r.read_f64()?;
    let north = r.read_f64()?;
    let east = r.read_f64()?;
    let zoom = r.read_f64()?;
    let clustered = r.read_u8()? != 0;
    let max_unclustered = r.read_u32()? as usize;
    let selected_id = r.read_str()?;

    let has_hidden = !hidden_layers.is_empty();
    let has_sent = !sent_ids.is_empty();
    let check_vp = use_viewport && north > south && east > west;
    let emit_labels = cached.pins.len() <= 1500 || zoom >= 14.2;

    let mut candidates: Vec<usize> = Vec::with_capacity(cached.pins.len().min(4096));
    let mut selected_idx: Option<usize> = None;

    for (i, p) in cached.pins.iter().enumerate() {
        if !p.lng.is_finite() || !p.lat.is_finite() {
            continue;
        }
        if has_hidden && hidden_layers.contains(p.layer_id.as_str()) {
            continue;
        }
        if has_sent && sent_ids.contains(p.id.as_str()) {
            continue;
        }
        let status_ok = match p.status_code {
            0 => (status_mask & 1) != 0,
            1 => (status_mask & 2) != 0,
            2 => (status_mask & 4) != 0,
            _ => false,
        };
        if !status_ok {
            continue;
        }
        if !selected_id.is_empty() && p.id == selected_id {
            selected_idx = Some(i);
        }
        if check_vp && (p.lat < south || p.lat > north || p.lng < west || p.lng > east) {
            continue;
        }
        candidates.push(i);
    }

    // Earth-locked Web Mercator pixel deduplication (color-fair: closest pin to cell center wins)
    let threshold = max_unclustered.max(2400);
    let final_indices: Vec<usize> = if !clustered && max_unclustered > 0 && check_vp && zoom < 15.8 && candidates.len() > threshold {
        let z_clamped = zoom.clamp(0.0, 22.0);
        let world_px = 256.0 * 2.0_f64.powf(z_clamped);
        let cell_px = if z_clamped >= 14.5 {
            9.0
        } else if z_clamped >= 13.0 {
            11.0
        } else if z_clamped >= 11.0 {
            12.5
        } else {
            14.0
        };

        let mut cells: HashMap<(i64, i64), (usize, f64)> = HashMap::with_capacity(threshold);
        for &idx in &candidates {
            let p = &cached.pins[idx];
            let (px_x, px_y) = web_mercator_px(p.lng, p.lat, world_px);
            let gx = (px_x / cell_px).floor() as i64;
            let gy = (px_y / cell_px).floor() as i64;
            let cx = (gx as f64 + 0.5) * cell_px;
            let cy = (gy as f64 + 0.5) * cell_px;
            let dist_sq = (px_x - cx) * (px_x - cx) + (px_y - cy) * (px_y - cy);
            cells
                .entry((gx, gy))
                .and_modify(|e| {
                    if dist_sq < e.1 {
                        *e = (idx, dist_sq);
                    }
                })
                .or_insert((idx, dist_sq));
        }

        let mut kept: Vec<usize> = cells.into_values().map(|(idx, _)| idx).collect();
        if let Some(sel) = selected_idx {
            if !kept.contains(&sel) {
                kept.push(sel);
            }
        }
        kept
    } else {
        candidates
    };

    let mut done_idx = Vec::with_capacity(final_indices.len() / 2);
    let mut issue_idx = Vec::with_capacity(final_indices.len() / 4);
    let mut pending_idx = Vec::with_capacity(final_indices.len() / 4);
    for idx in final_indices {
        match cached.pins[idx].status_code {
            0 => done_idx.push(idx),
            1 => issue_idx.push(idx),
            _ => pending_idx.push(idx),
        }
    }

    let total = done_idx.len() + issue_idx.len() + pending_idx.len();
    let mut out = Vec::with_capacity(64 + total * 136);
    out.extend_from_slice(br#"{"type":"FeatureCollection","features":["#);

    let mut first = true;
    let groups: [(&[usize], &str); 3] = [
        (&done_idx, cached.done_icon.as_str()),
        (&issue_idx, cached.issue_icon.as_str()),
        (&pending_idx, cached.pending_icon.as_str()),
    ];

    for (indices, icon_id) in groups {
        for &idx in indices {
            let p = &cached.pins[idx];
            if !first {
                out.push(b',');
            }
            first = false;
            out.extend_from_slice(br#"{"type":"Feature","geometry":{"type":"Point","coordinates":["#);
            let _ = write!(&mut out, "{},{}", p.lng, p.lat);
            out.extend_from_slice(br#"]},"properties":{"id":"#);
            push_json_str(&mut out, &p.id);
            if emit_labels {
                out.extend_from_slice(br#","label":"#);
                push_json_str(&mut out, &p.label_shaped);
            }
            out.extend_from_slice(br#","icon":"#);
            push_json_str(&mut out, icon_id);
            out.extend_from_slice(b"}}");
        }
    }

    out.extend_from_slice(b"]}");
    Some(out)
}


/// O(1) in-place delta update for 1..N edited pins in resident Rust RAM.
/// Preserves exact pin indices and bumps cached version without re-uploading 20,000 pins.
/// Wire: [u32 count] followed by count * ([str id][str layer_id][str name][u8 status_code][f64 lng][f64 lat])
pub fn patch_geo_pins(
    expected_old_version: i64,
    new_version: i64,
    expected_len: usize,
    payload: &[u8],
) -> bool {
    let mut r = BinReader::new(payload);
    let Some(count) = r.read_u32().map(|v| v as usize) else {
        return false;
    };
    let mut updates = Vec::with_capacity(count.min(4096));
    for _ in 0..count {
        let (Some(id), Some(layer_id), Some(name), Some(status_code), Some(lng), Some(lat)) = (
            r.read_str(),
            r.read_str(),
            r.read_str(),
            r.read_u8(),
            r.read_f64(),
            r.read_f64(),
        ) else {
            return false;
        };
        updates.push((id, layer_id, name, status_code, lng, lat));
    }
    let mut search_meta: Option<Vec<(&str, &str)>> = None;
    if r.consume_magic(b"W12S") {
        let mut meta = Vec::with_capacity(updates.len());
        for _ in 0..updates.len() {
            let (Some(desc), Some(reason)) = (r.read_str(), r.read_str()) else {
                return false;
            };
            meta.push((desc, reason));
        }
        search_meta = Some(meta);
    }

    let Ok(mut guard) = cache_lock().write() else {
        return false;
    };
    let Some(cached) = guard.as_mut() else {
        return false;
    };
    if cached.version != expected_old_version || cached.pins.len() != expected_len {
        return false;
    }

    // Verify all IDs exist before mutating to guarantee atomic update
    let mut resolved = Vec::with_capacity(updates.len());
    for (id, layer_id, name, status_code, lng, lat) in updates {
        let Some(&idx) = cached.id_to_idx.get(id) else {
            return false;
        };
        resolved.push((idx, layer_id, name, status_code, lng, lat));
    }

    let mut coords_changed = false;
    let mut dirty_coords = Vec::with_capacity(resolved.len() * 2);
    for (i, (idx, layer_id, name, status_code, lng, lat)) in resolved.into_iter().enumerate() {
        let pin = &mut cached.pins[idx];
        if pin.mx >= 0.0 && pin.my >= 0.0 {
            dirty_coords.push((pin.mx, pin.my));
        }
        if pin.layer_id != layer_id {
            pin.layer_id.clear();
            pin.layer_id.push_str(layer_id);
        }
        if pin.name != name {
            pin.name.clear();
            pin.name.push_str(name);
            pin.label_shaped = truncate_and_shape_label(name);
        }
        pin.status_code = status_code;
        if let Some(ref meta) = search_meta {
            let (desc, reason) = meta[i];
            if pin.description != desc {
                pin.description.clear();
                pin.description.push_str(desc);
            }
            if pin.issue_reason != reason {
                pin.issue_reason.clear();
                pin.issue_reason.push_str(reason);
            }
        }
        let fname = cached
            .folder_names
            .get(pin.layer_id.as_str())
            .map(|s| s.as_str())
            .unwrap_or("Folder");
        pin.search_blob = build_search_blob(
            &pin.name,
            &pin.description,
            &pin.issue_reason,
            pin.status_code,
            fname,
        );
        if pin.lng != lng || pin.lat != lat {
            pin.lng = lng;
            pin.lat = lat;
            pin.coords_text = format_coords_text(lat, lng);
            let (mx, my) = if lng.is_finite() && lat.is_finite() {
                lng_lat_to_mercator_unit(lng, lat)
            } else {
                (-1.0, -1.0)
            };
            pin.mx = mx;
            pin.my = my;
            if mx >= 0.0 && my >= 0.0 {
                dirty_coords.push((mx, my));
            }
            coords_changed = true;
        }
    }
    cached.last_delta_version = new_version;
    cached.last_delta_coords = dirty_coords;
    if coords_changed {
        cached.x_index = cached
            .pins
            .iter()
            .enumerate()
            .filter(|(_, p)| p.mx >= 0.0)
            .map(|(i, p)| (p.mx, i))
            .collect();
        cached.x_index.sort_by(|a, b| a.0.total_cmp(&b.0));
    }
    cached.version = new_version;
    true
}


pub fn is_geo_pins_synced(version: i64, expected_len: usize) -> bool {
    let Ok(guard) = cache_lock().read() else {
        return false;
    };
    match guard.as_ref() {
        Some(cached) => cached.version == version && cached.pins.len() == expected_len,
        None => false,
    }
}

pub fn clear_geo_pins() {
    if let Ok(mut guard) = cache_lock().write() {
        *guard = None;
    }
}

#[cfg(test)]
pub fn build_tracks_geojson_viewport(payload: &[u8]) -> Option<Vec<u8>> {
    let mut r = BinReader::new(payload);
    let use_viewport = r.read_u8()? != 0;
    let south = r.read_f64()?;
    let west = r.read_f64()?;
    let north = r.read_f64()?;
    let east = r.read_f64()?;
    let zoom = r.read_f64()?;
    let track_count = r.read_u32()? as usize;

    let check_vp = use_viewport && north > south && east > west;
    let min_step_sq = if use_viewport && zoom < 14.5 {
        let step = 0.00012 * 2.0_f64.powf((14.5 - zoom).clamp(0.0, 8.0));
        step * step
    } else {
        0.0
    };

    let mut out = Vec::with_capacity(64 + payload.len().min(512 * 1024));
    out.extend_from_slice(br#"{"type":"FeatureCollection","features":["#);

    let mut first_feat = true;
    let mut pts: Vec<(f64, f64)> = Vec::with_capacity(128);

    for _ in 0..track_count {
        let raw = r.read_str()?;
        pts.clear();
        let mut t_min_lat = f64::INFINITY;
        let mut t_max_lat = f64::NEG_INFINITY;
        let mut t_min_lng = f64::INFINITY;
        let mut t_max_lng = f64::NEG_INFINITY;

        for seg in raw.split(';') {
            let Some((lng_s, lat_s)) = seg.split_once(',') else { continue; };
            let (Ok(lng), Ok(lat)) = (lng_s.trim().parse::<f64>(), lat_s.trim().parse::<f64>()) else { continue; };
            if !lng.is_finite() || !lat.is_finite() { continue; }
            if lat < t_min_lat { t_min_lat = lat; }
            if lat > t_max_lat { t_max_lat = lat; }
            if lng < t_min_lng { t_min_lng = lng; }
            if lng > t_max_lng { t_max_lng = lng; }
            pts.push((lng, lat));
        }

        if pts.len() < 2 { continue; }
        if check_vp && (t_max_lat < south || t_min_lat > north || t_max_lng < west || t_min_lng > east) {
            continue;
        }

        if !first_feat { out.push(b','); }
        first_feat = false;
        out.extend_from_slice(br#"{"type":"Feature","geometry":{"type":"LineString","coordinates":["#);

        let last_idx = pts.len() - 1;
        let mut prev_emitted = pts[0];
        let _ = write!(&mut out, "[{},{}]", prev_emitted.0, prev_emitted.1);

        for (i, &(lng, lat)) in pts.iter().enumerate().skip(1) {
            if i != last_idx && min_step_sq > 0.0 {
                let dx = lng - prev_emitted.0;
                let dy = lat - prev_emitted.1;
                if dx * dx + dy * dy < min_step_sq { continue; }
            }
            let _ = write!(&mut out, ",[{},{}]", lng, lat);
            prev_emitted = (lng, lat);
        }
        out.extend_from_slice(br#"]},"properties":{}}"#);
    }

    out.extend_from_slice(b"]}");
    Some(out)
}

#[inline]
fn point_in_geo_polygon(x: f64, y: f64, poly: &[(f64, f64)]) -> bool {
    let n = poly.len();
    if n < 3 { return false; }
    let mut inside = false;
    let mut j = n - 1;
    for i in 0..n {
        let (xi, yi) = poly[i];
        let (xj, yj) = poly[j];
        let intersect = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi);
        if intersect { inside = !inside; }
        j = i;
    }
    inside
}

pub fn filter_pins_in_polygon(version: i64, expected_len: usize, payload: &[u8]) -> Option<Vec<u8>> {
    let guard = cache_lock().read().ok()?;
    let cached = guard.as_ref()?;
    if cached.version != version || cached.pins.len() != expected_len {
        return None;
    }

    let mut r = BinReader::new(payload);
    let status_mask = r.read_u8()?;
    let hidden_count = r.read_u32()? as usize;
    let mut hidden_layers: HashSet<&str> = HashSet::with_capacity(hidden_count);
    for _ in 0..hidden_count { hidden_layers.insert(r.read_str()?); }
    let sent_count = r.read_u32()? as usize;
    let mut sent_ids: HashSet<&str> = HashSet::with_capacity(sent_count);
    for _ in 0..sent_count { sent_ids.insert(r.read_str()?); }

    let poly_len = r.read_u32()? as usize;
    if poly_len < 3 { return Some(0u32.to_le_bytes().to_vec()); }
    let mut poly = Vec::with_capacity(poly_len);
    let mut min_lng = f64::INFINITY;
    let mut max_lng = f64::NEG_INFINITY;
    let mut min_lat = f64::INFINITY;
    let mut max_lat = f64::NEG_INFINITY;
    for _ in 0..poly_len {
        let lng = r.read_f64()?;
        let lat = r.read_f64()?;
        if lng < min_lng { min_lng = lng; }
        if lng > max_lng { max_lng = lng; }
        if lat < min_lat { min_lat = lat; }
        if lat > max_lat { max_lat = lat; }
        poly.push((lng, lat));
    }

    let has_hidden = !hidden_layers.is_empty();
    let has_sent = !sent_ids.is_empty();
    let mut matched: Vec<u32> = Vec::new();

    for (i, p) in cached.pins.iter().enumerate() {
        if p.lng < min_lng || p.lng > max_lng || p.lat < min_lat || p.lat > max_lat { continue; }
        if has_hidden && hidden_layers.contains(p.layer_id.as_str()) { continue; }
        if has_sent && sent_ids.contains(p.id.as_str()) { continue; }
        let status_ok = match p.status_code {
            0 => (status_mask & 1) != 0,
            1 => (status_mask & 2) != 0,
            2 => (status_mask & 4) != 0,
            _ => false,
        };
        if !status_ok { continue; }
        if point_in_geo_polygon(p.lng, p.lat, &poly) {
            matched.push(i as u32);
        }
    }

    let mut out = Vec::with_capacity(4 + matched.len() * 4);
    out.extend_from_slice(&(matched.len() as u32).to_le_bytes());
    for idx in matched { out.extend_from_slice(&idx.to_le_bytes()); }
    Some(out)
}

/// Fast 2m stacked-pin lookup directly over resident Rust pin store.
/// Wire: [str target_id][f64 target_lat][f64 target_lng][f64 cell_size][u8 status_mask]
///       [u32 hidden_cnt][hidden...][u32 sent_cnt][sent...]
/// Returns: [u32 match_count][u32 indices sorted by pin.id...]
pub fn find_exact_stacked_pins(version: i64, expected_len: usize, payload: &[u8]) -> Option<Vec<u8>> {
    let guard = cache_lock().read().ok()?;
    let cached = guard.as_ref()?;
    if cached.version != version || cached.pins.len() != expected_len {
        return None;
    }

    let mut r = BinReader::new(payload);
    let target_id = r.read_str()?;
    let target_lat = r.read_f64()?;
    let target_lng = r.read_f64()?;
    let cell_size = r.read_f64()?;
    let status_mask = r.read_u8()?;
    let hidden_count = r.read_u32()? as usize;
    let mut hidden_layers: HashSet<&str> = HashSet::with_capacity(hidden_count);
    for _ in 0..hidden_count { hidden_layers.insert(r.read_str()?); }
    let sent_count = r.read_u32()? as usize;
    let mut sent_ids: HashSet<&str> = HashSet::with_capacity(sent_count);
    for _ in 0..sent_count { sent_ids.insert(r.read_str()?); }

    let has_hidden = !hidden_layers.is_empty();
    let has_sent = !sent_ids.is_empty();
    let mut stack: Vec<usize> = Vec::with_capacity(4);
    let mut contains_target = false;

    for (i, p) in cached.pins.iter().enumerate() {
        if (p.lat - target_lat).abs() > cell_size || (p.lng - target_lng).abs() > cell_size {
            continue;
        }
        if has_hidden && hidden_layers.contains(p.layer_id.as_str()) { continue; }
        if has_sent && sent_ids.contains(p.id.as_str()) { continue; }
        let status_ok = match p.status_code {
            0 => (status_mask & 1) != 0,
            1 => (status_mask & 2) != 0,
            2 => (status_mask & 4) != 0,
            _ => false,
        };
        if !status_ok { continue; }
        if haversine_meters(p.lat, p.lng, target_lat, target_lng) <= 2.0 {
            if p.id == target_id { contains_target = true; }
            stack.push(i);
        }
    }

    if !contains_target {
        return Some(0u32.to_le_bytes().to_vec());
    }
    if stack.len() > 1 {
        stack.sort_by(|&a, &b| cached.pins[a].id.cmp(&cached.pins[b].id));
    }

    let mut out = Vec::with_capacity(4 + stack.len() * 4);
    out.extend_from_slice(&(stack.len() as u32).to_le_bytes());
    for idx in stack { out.extend_from_slice(&(idx as u32).to_le_bytes()); }
    Some(out)
}


/// Wave 13: Finds the nearest matching pin in the resident `GEO_PINS_CACHE`.
/// Query wire:
///   [u8 status_mask (bit0=done, bit1=issue, bit2=pending)]
///   [f64 lat] [f64 lng]
///   [u32 hidden_count] [hidden_layer_ids...]
///   [u32 sent_count] [sent_pin_ids...]
/// Output wire (12 bytes):
///   [i32 best_idx (-1 if none)] [f64 best_dist_m]
pub fn find_nearest_pin(
    version: i64,
    expected_len: usize,
    query_payload: &[u8],
) -> Option<Vec<u8>> {
    let mut r = BinReader::new(query_payload);
    let status_mask = r.read_u8()?;
    let q_lat = r.read_f64()?;
    let q_lng = r.read_f64()?;
    if !q_lat.is_finite() || !q_lng.is_finite() {
        return None;
    }
    let hidden_count = r.read_u32()? as usize;
    let mut hidden_layers: HashSet<&str> = HashSet::with_capacity(hidden_count.min(4096));
    for _ in 0..hidden_count {
        hidden_layers.insert(r.read_str()?);
    }
    let sent_count = r.read_u32()? as usize;
    let mut sent_ids: HashSet<&str> = HashSet::with_capacity(sent_count.min(250_000));
    for _ in 0..sent_count {
        sent_ids.insert(r.read_str()?);
    }

    let guard = cache_lock().read().ok()?;
    let cached = guard.as_ref()?;
    if cached.version != version || cached.pins.len() != expected_len {
        return None;
    }

    let has_hidden = !hidden_layers.is_empty();
    let has_sent = !sent_ids.is_empty();
    let mut best_idx: i32 = -1;
    let mut best_dist: f64 = f64::INFINITY;

    for (idx, p) in cached.pins.iter().enumerate() {
        if (status_mask & (1u8 << p.status_code.min(7))) == 0 {
            continue;
        }
        if !p.lat.is_finite() || !p.lng.is_finite() {
            continue;
        }
        if has_hidden && hidden_layers.contains(p.layer_id.as_str()) {
            continue;
        }
        if has_sent && sent_ids.contains(p.id.as_str()) {
            continue;
        }
        // Conservative latitude lower bound (1 deg lat >= 110,574m everywhere on Earth)
        if best_dist.is_finite() && (p.lat - q_lat).abs() * 110_000.0 >= best_dist {
            continue;
        }
        let d = haversine_meters(q_lat, q_lng, p.lat, p.lng);
        if d < best_dist {
            best_dist = d;
            best_idx = idx as i32;
        }
    }

    let mut out = Vec::with_capacity(12);
    out.extend_from_slice(&best_idx.to_le_bytes());
    out.extend_from_slice(&(if best_idx >= 0 { best_dist } else { 0.0 }).to_le_bytes());
    Some(out)
}

/// Wave 13: Counts visible Done / Issue / Pending pins in a single pass over `GEO_PINS_CACHE`.
/// Filter wire:
///   [u32 hidden_count] [hidden_layer_ids...]
///   [u32 sent_count] [sent_pin_ids...]
/// Output wire (16 bytes):
///   [u32 total_visible] [u32 done] [u32 issue] [u32 pending]
pub fn count_visible_statuses(
    version: i64,
    expected_len: usize,
    filter_payload: &[u8],
) -> Option<Vec<u8>> {
    let mut r = BinReader::new(filter_payload);
    let hidden_count = r.read_u32()? as usize;
    let mut hidden_layers: HashSet<&str> = HashSet::with_capacity(hidden_count.min(4096));
    for _ in 0..hidden_count {
        hidden_layers.insert(r.read_str()?);
    }
    let sent_count = r.read_u32()? as usize;
    let mut sent_ids: HashSet<&str> = HashSet::with_capacity(sent_count.min(250_000));
    for _ in 0..sent_count {
        sent_ids.insert(r.read_str()?);
    }

    let guard = cache_lock().read().ok()?;
    let cached = guard.as_ref()?;
    if cached.version != version || cached.pins.len() != expected_len {
        return None;
    }

    let has_hidden = !hidden_layers.is_empty();
    let has_sent = !sent_ids.is_empty();
    let mut done: u32 = 0;
    let mut issue: u32 = 0;
    let mut pending: u32 = 0;

    for p in &cached.pins {
        if has_hidden && hidden_layers.contains(p.layer_id.as_str()) {
            continue;
        }
        if has_sent && sent_ids.contains(p.id.as_str()) {
            continue;
        }
        match p.status_code {
            0 => done += 1,
            1 => issue += 1,
            _ => pending += 1,
        }
    }
    let total = done + issue + pending;
    let mut out = Vec::with_capacity(16);
    out.extend_from_slice(&total.to_le_bytes());
    out.extend_from_slice(&done.to_le_bytes());
    out.extend_from_slice(&issue.to_le_bytes());
    out.extend_from_slice(&pending.to_le_bytes());
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_str(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
        buf.extend_from_slice(s.as_bytes());
    }

    #[test]
    fn culls_viewport_preserves_color_balance_and_raycasts_lasso() {
        let _guard = TEST_CACHE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let mut load = Vec::new();
        put_str(&mut load, "pin-done");
        put_str(&mut load, "pin-issue");
        put_str(&mut load, "pin-pending");
        load.extend_from_slice(&3u32.to_le_bytes());

        put_str(&mut load, "p1");
        put_str(&mut load, "layerA");
        put_str(&mut load, "هەولێر");
        load.push(2u8);
        load.extend_from_slice(&43.89_f64.to_le_bytes());
        load.extend_from_slice(&36.74_f64.to_le_bytes());

        put_str(&mut load, "p2");
        put_str(&mut load, "layerA");
        put_str(&mut load, "Pole A");
        load.push(0u8);
        load.extend_from_slice(&43.90_f64.to_le_bytes());
        load.extend_from_slice(&36.75_f64.to_le_bytes());

        put_str(&mut load, "p3");
        put_str(&mut load, "layerA");
        put_str(&mut load, "Zakho");
        load.push(1u8);
        load.extend_from_slice(&42.68_f64.to_le_bytes());
        load.extend_from_slice(&37.14_f64.to_le_bytes());

        assert!(load_geo_pins(11, &load));

        let mut flt = Vec::new();
        flt.push(0b111u8);
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.push(1u8);
        flt.extend_from_slice(&36.70_f64.to_le_bytes());
        flt.extend_from_slice(&43.80_f64.to_le_bytes());
        flt.extend_from_slice(&36.80_f64.to_le_bytes());
        flt.extend_from_slice(&44.00_f64.to_le_bytes());
        flt.extend_from_slice(&15.0_f64.to_le_bytes());
        flt.push(0u8);
        flt.extend_from_slice(&2400u32.to_le_bytes());
        put_str(&mut flt, "");

        let json_bytes = build_pins_geojson_viewport(11, 3, &flt).unwrap();
        let json = std::str::from_utf8(&json_bytes).unwrap();
        assert!(json.contains("\"id\":\"p1\""));
        assert!(json.contains("\"id\":\"p2\""));
        assert!(!json.contains("\"id\":\"p3\""));

        let mut lasso = Vec::new();
        lasso.push(0b111u8);
        lasso.extend_from_slice(&0u32.to_le_bytes());
        lasso.extend_from_slice(&0u32.to_le_bytes());
        lasso.extend_from_slice(&4u32.to_le_bytes());
        for (lng, lat) in [(43.885_f64, 36.735_f64), (43.895_f64, 36.735_f64), (43.895_f64, 36.745_f64), (43.885_f64, 36.745_f64)] {
            lasso.extend_from_slice(&lng.to_le_bytes());
            lasso.extend_from_slice(&lat.to_le_bytes());
        }
        let hit = filter_pins_in_polygon(11, 3, &lasso).unwrap();
        assert_eq!(u32::from_le_bytes(hit[0..4].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(hit[4..8].try_into().unwrap()), 0);

        clear_geo_pins();
    }

    #[test]
    fn patches_single_pin_in_place_and_bumps_version() {
        let _guard = TEST_CACHE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let mut load = Vec::new();
        put_str(&mut load, "pin-done");
        put_str(&mut load, "pin-issue");
        put_str(&mut load, "pin-pending");
        load.extend_from_slice(&2u32.to_le_bytes());

        put_str(&mut load, "p1");
        put_str(&mut load, "layerA");
        put_str(&mut load, "Meter 1");
        load.push(2u8); // pending
        load.extend_from_slice(&43.89_f64.to_le_bytes());
        load.extend_from_slice(&36.74_f64.to_le_bytes());

        put_str(&mut load, "p2");
        put_str(&mut load, "layerA");
        put_str(&mut load, "Meter 2");
        load.push(2u8);
        load.extend_from_slice(&43.90_f64.to_le_bytes());
        load.extend_from_slice(&36.75_f64.to_le_bytes());

        assert!(load_geo_pins(100, &load));

        // Patch only p1 -> Done (0) and bump version 100 -> 101
        let mut delta = Vec::new();
        delta.extend_from_slice(&1u32.to_le_bytes());
        put_str(&mut delta, "p1");
        put_str(&mut delta, "layerA");
        put_str(&mut delta, "Meter 1");
        delta.push(0u8); // done
        delta.extend_from_slice(&43.89_f64.to_le_bytes());
        delta.extend_from_slice(&36.74_f64.to_le_bytes());

        assert!(patch_geo_pins(100, 101, 2, &delta));
        assert!(!patch_geo_pins(100, 102, 2, &delta)); // stale old version must reject

        // Filter DONE-only on version 101 must return p1 without re-syncing
        let mut flt = Vec::new();
        flt.push(0b001u8); // done only
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.push(0u8);
        for _ in 0..5 { flt.extend_from_slice(&0.0_f64.to_le_bytes()); }
        flt.push(0u8);
        flt.extend_from_slice(&0u32.to_le_bytes());
        put_str(&mut flt, "");

        let json = String::from_utf8(build_pins_geojson_viewport(101, 2, &flt).unwrap()).unwrap();
        assert!(json.contains(r#""id":"p1""#));
        assert!(!json.contains(r#""id":"p2""#));
        clear_geo_pins();
    }
}
