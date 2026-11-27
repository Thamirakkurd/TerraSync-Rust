use std::collections::{HashMap, HashSet};
use std::io::Write;
use std::sync::{OnceLock, RwLock};

struct GeoPin {
    id: String,
    layer_id: String,
    label_shaped: String,
    status_code: u8, // 0=done, 1=issue, 2=pending
    lng: f64,
    lat: f64,
}

struct CachedGeoPins {
    version: i64,
    done_icon: String,
    issue_icon: String,
    pending_icon: String,
    pins: Vec<GeoPin>,
}

static GEO_PINS_CACHE: OnceLock<RwLock<Option<CachedGeoPins>>> = OnceLock::new();

fn cache_lock() -> &'static RwLock<Option<CachedGeoPins>> {
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

#[inline]
fn status_draw_priority(status_code: u8) -> u8 {
    match status_code {
        1 => 3, // issue highest priority
        2 => 2, // pending next
        _ => 1, // done lowest
    }
}

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
                    b'\\',
                    b'u',
                    b'0',
                    b'0',
                    HEX[(b >> 4) as usize],
                    HEX[(b & 0x0F) as usize],
                ]);
            }
            _ => out.push(b),
        }
    }
    out.push(b'"');
}

/// Uploads pin snapshot once per dataVersion and pre-shapes Kurdish labels off-JVM.
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
        pins.push(GeoPin {
            id: id.to_owned(),
            layer_id: layer_id.to_owned(),
            label_shaped: truncate_and_shape_label(name),
            status_code,
            lng,
            lat,
        });
    }

    let Ok(mut guard) = cache_lock().write() else {
        return false;
    };
    *guard = Some(CachedGeoPins {
        version,
        done_icon: done_icon.to_owned(),
        issue_icon: issue_icon.to_owned(),
        pending_icon: pending_icon.to_owned(),
        pins,
    });
    true
}

/// Viewport-aware & overdraw-thinned GeoJSON builder for smooth 60-120fps map panning.
/// Filter wire:
///   [u8 status_mask (bit0=done, bit1=issue, bit2=pending)]
///   [u32 hidden_count][hidden layer ids...]
///   [u32 sent_count][sent pin ids...]
///   [u8 use_viewport][f64 south][f64 west][f64 north][f64 east][f64 zoom]
///   [u8 clustered][u32 max_unclustered_pins][str selected_pin_id]
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

    // Screen-space grid thinning when unclustered viewport still has too many stacked pins
    let final_indices: Vec<usize> = if !clustered && max_unclustered > 0 && candidates.len() > max_unclustered && check_vp {
        let grid_side = ((max_unclustered as f64).sqrt().ceil() as u32).clamp(24, 48);
        let grid_f = grid_side as f64;
        let lat_span = (north - south).max(1e-6);
        let lng_span = (east - west).max(1e-6);
        let mut cells: HashMap<u64, (usize, u8)> = HashMap::with_capacity(max_unclustered);

        for &idx in &candidates {
            let p = &cached.pins[idx];
            let gx = (((p.lng - west) / lng_span) * grid_f).floor().clamp(0.0, grid_f - 1.0) as u32;
            let gy = (((p.lat - south) / lat_span) * grid_f).floor().clamp(0.0, grid_f - 1.0) as u32;
            let key = ((gx as u64) << 32) | (gy as u64);
            let prio = status_draw_priority(p.status_code);
            cells
                .entry(key)
                .and_modify(|e| {
                    if prio > e.1 {
                        *e = (idx, prio);
                    }
                })
                .or_insert((idx, prio));
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

pub fn clear_geo_pins() {
    if let Ok(mut guard) = cache_lock().write() {
        *guard = None;
    }
}

/// Viewport-culled & zoom-decimated LineString GeoJSON builder for tracks.
/// Wire: [u8 use_viewport][f64 south][f64 west][f64 north][f64 east][f64 zoom]
///       [u32 track_count] followed by `track_count` UTF-8 coords strings.
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
            let Some((lng_s, lat_s)) = seg.split_once(',') else {
                continue;
            };
            let (Ok(lng), Ok(lat)) = (lng_s.trim().parse::<f64>(), lat_s.trim().parse::<f64>()) else {
                continue;
            };
            if !lng.is_finite() || !lat.is_finite() {
                continue;
            }
            if lat < t_min_lat { t_min_lat = lat; }
            if lat > t_max_lat { t_max_lat = lat; }
            if lng < t_min_lng { t_min_lng = lng; }
            if lng > t_max_lng { t_max_lng = lng; }
            pts.push((lng, lat));
        }

        if pts.len() < 2 {
            continue;
        }
        if check_vp && (t_max_lat < south || t_min_lat > north || t_max_lng < west || t_min_lng > east) {
            continue;
        }

        if !first_feat {
            out.push(b',');
        }
        first_feat = false;
        out.extend_from_slice(br#"{"type":"Feature","geometry":{"type":"LineString","coordinates":["#);

        let last_idx = pts.len() - 1;
        let mut prev_emitted = pts[0];
        let _ = write!(&mut out, "[{},{}]", prev_emitted.0, prev_emitted.1);

        for (i, &(lng, lat)) in pts.iter().enumerate().skip(1) {
            if i != last_idx && min_step_sq > 0.0 {
                let dx = lng - prev_emitted.0;
                let dy = lat - prev_emitted.1;
                if dx * dx + dy * dy < min_step_sq {
                    continue;
                }
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
    if n < 3 {
        return false;
    }
    let mut inside = false;
    let mut j = n - 1;
    for i in 0..n {
        let (xi, yi) = poly[i];
        let (xj, yj) = poly[j];
        let intersect = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi);
        if intersect {
            inside = !inside;
        }
        j = i;
    }
    inside
}

/// Fast Rust lasso hit-test over resident pins using geographic polygon vertices.
/// Payload wire:
///   [u8 status_mask][u32 hidden_count][hidden...][u32 sent_count][sent...]
///   [u32 poly_pts][poly_pts * (f64 lng, f64 lat)]
/// Returns: [u32 match_count][u32 pin_indices...]
pub fn filter_pins_in_polygon(
    version: i64,
    expected_len: usize,
    payload: &[u8],
) -> Option<Vec<u8>> {
    let guard = cache_lock().read().ok()?;
    let cached = guard.as_ref()?;
    if cached.version != version || cached.pins.len() != expected_len {
        return None;
    }

    let mut r = BinReader::new(payload);
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

    let poly_len = r.read_u32()? as usize;
    if poly_len < 3 {
        return Some(0u32.to_le_bytes().to_vec());
    }
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
        if p.lng < min_lng || p.lng > max_lng || p.lat < min_lat || p.lat > max_lat {
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
        if point_in_geo_polygon(p.lng, p.lat, &poly) {
            matched.push(i as u32);
        }
    }

    let mut out = Vec::with_capacity(4 + matched.len() * 4);
    out.extend_from_slice(&(matched.len() as u32).to_le_bytes());
    for idx in matched {
        out.extend_from_slice(&idx.to_le_bytes());
    }
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
    fn culls_viewport_thins_overdraw_and_raycasts_lasso() {
        let mut load = Vec::new();
        put_str(&mut load, "pin-done");
        put_str(&mut load, "pin-issue");
        put_str(&mut load, "pin-pending");
        load.extend_from_slice(&3u32.to_le_bytes());

        // Inside Akre viewport (pending)
        put_str(&mut load, "p1");
        put_str(&mut load, "layerA");
        put_str(&mut load, "هەولێر");
        load.push(2u8);
        load.extend_from_slice(&43.89f64.to_le_bytes());
        load.extend_from_slice(&36.74f64.to_le_bytes());

        // Inside Akre viewport (done)
        put_str(&mut load, "p2");
        put_str(&mut load, "layerA");
        put_str(&mut load, "Pole A");
        load.push(0u8);
        load.extend_from_slice(&43.90f64.to_le_bytes());
        load.extend_from_slice(&36.75f64.to_le_bytes());

        // Outside viewport (Zakho)
        put_str(&mut load, "p3");
        put_str(&mut load, "layerA");
        put_str(&mut load, "Zakho");
        load.push(1u8);
        load.extend_from_slice(&42.68f64.to_le_bytes());
        load.extend_from_slice(&37.14f64.to_le_bytes());

        assert!(load_geo_pins(11, &load));

        // Viewport around Akre only
        let mut flt = Vec::new();
        flt.push(0b111u8);
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.push(1u8); // use_viewport
        flt.extend_from_slice(&36.70f64.to_le_bytes()); // south
        flt.extend_from_slice(&43.80f64.to_le_bytes()); // west
        flt.extend_from_slice(&36.80f64.to_le_bytes()); // north
        flt.extend_from_slice(&44.00f64.to_le_bytes()); // east
        flt.extend_from_slice(&15.0f64.to_le_bytes()); // zoom
        flt.push(0u8); // unclustered
        flt.extend_from_slice(&1000u32.to_le_bytes());
        put_str(&mut flt, "");

        let json_bytes = build_pins_geojson_viewport(11, 3, &flt).unwrap();
        let json = std::str::from_utf8(&json_bytes).unwrap();
        assert!(json.contains("\"id\":\"p1\""));
        assert!(json.contains("\"id\":\"p2\""));
        assert!(!json.contains("\"id\":\"p3\""));
        assert!(json.contains(&crate::kurdish::shape("هەولێر")));

        // Lasso polygon around p1 only
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
        let cnt = u32::from_le_bytes(hit[0..4].try_into().unwrap());
        assert_eq!(cnt, 1);
        assert_eq!(u32::from_le_bytes(hit[4..8].try_into().unwrap()), 0);

        clear_geo_pins();
    }
}
