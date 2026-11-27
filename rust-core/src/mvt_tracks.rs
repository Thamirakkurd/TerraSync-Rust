use std::collections::HashSet;
use std::sync::{OnceLock, RwLock};

use crate::geojson::{cache_lock as pins_cache_lock, lng_lat_to_mercator_unit};

const MVT_EXTENT: i32 = 4096;
const TRACK_CLIP_PAD: f64 = 256.0;
const MIN_VERTEX_STEP: i32 = 3;

pub(crate) struct GeoTrack {
    pub(crate) layer_id: String,
    pub(crate) min_mx: f64,
    pub(crate) max_mx: f64,
    pub(crate) min_my: f64,
    pub(crate) max_my: f64,
    pub(crate) min_lat: f64,
    pub(crate) max_lat: f64,
    pub(crate) min_lng: f64,
    pub(crate) max_lng: f64,
    pub(crate) pts: Vec<(f64, f64)>, // (mx, my)
}

pub(crate) struct CachedGeoTracks {
    pub(crate) version: i64,
    pub(crate) raw_len: usize,
    pub(crate) tracks: Vec<GeoTrack>,
}

static GEO_TRACKS_CACHE: OnceLock<RwLock<Option<CachedGeoTracks>>> = OnceLock::new();

pub(crate) fn tracks_cache_lock() -> &'static RwLock<Option<CachedGeoTracks>> {
    GEO_TRACKS_CACHE.get_or_init(|| RwLock::new(None))
}

struct BinReader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> BinReader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn read_u32(&mut self) -> Option<u32> {
        let slice = self.buf.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(u32::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_str(&mut self) -> Option<&'a str> {
        let len = self.read_u32()? as usize;
        let slice = self.buf.get(self.pos..self.pos + len)?;
        self.pos += len;
        std::str::from_utf8(slice).ok()
    }
}

/// Uploads tracks once per dataVersion, pre-projecting vertices to Web Mercator unit space.
/// Wire: [u32 count] followed by count * ([str layer_id][str coords])
pub fn load_geo_tracks(version: i64, payload: &[u8]) -> bool {
    let mut r = BinReader::new(payload);
    let Some(count) = r.read_u32().map(|v| v as usize) else {
        return false;
    };

    let mut tracks = Vec::with_capacity(count.min(65_536));
    for _ in 0..count {
        let (Some(layer_id), Some(raw_coords)) = (r.read_str(), r.read_str()) else {
            return false;
        };

        let mut pts = Vec::with_capacity(32);
        let mut min_mx = f64::INFINITY;
        let mut max_mx = f64::NEG_INFINITY;
        let mut min_my = f64::INFINITY;
        let mut max_my = f64::NEG_INFINITY;
        let mut min_lat = f64::INFINITY;
        let mut max_lat = f64::NEG_INFINITY;
        let mut min_lng = f64::INFINITY;
        let mut max_lng = f64::NEG_INFINITY;

        for seg in raw_coords.split(';') {
            let Some((lng_s, lat_s)) = seg.split_once(',') else {
                continue;
            };
            let (Ok(lng), Ok(lat)) = (lng_s.trim().parse::<f64>(), lat_s.trim().parse::<f64>()) else {
                continue;
            };
            if !lng.is_finite() || !lat.is_finite() || !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lng) {
                continue;
            }
            let (mx, my) = lng_lat_to_mercator_unit(lng, lat);
            if mx < min_mx { min_mx = mx; }
            if mx > max_mx { max_mx = mx; }
            if my < min_my { min_my = my; }
            if my > max_my { max_my = my; }
            if lat < min_lat { min_lat = lat; }
            if lat > max_lat { max_lat = lat; }
            if lng < min_lng { min_lng = lng; }
            if lng > max_lng { max_lng = lng; }
            pts.push((mx, my));
        }

        if pts.len() >= 2 {
            tracks.push(GeoTrack {
                layer_id: layer_id.to_owned(),
                min_mx,
                max_mx,
                min_my,
                max_my,
                min_lat,
                max_lat,
                min_lng,
                max_lng,
                pts,
            });
        }
    }

    let Ok(mut guard) = tracks_cache_lock().write() else {
        return false;
    };
    *guard = Some(CachedGeoTracks {
        version,
        raw_len: count,
        tracks,
    });
    true
}

pub fn is_geo_tracks_synced(version: i64, expected_len: usize) -> bool {
    let Ok(guard) = tracks_cache_lock().read() else {
        return false;
    };
    match guard.as_ref() {
        Some(cached) => cached.version == version && cached.raw_len == expected_len,
        None => false,
    }
}

pub fn clear_geo_tracks() {
    if let Ok(mut guard) = tracks_cache_lock().write() {
        *guard = None;
    }
}

#[inline]
fn write_varint(out: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        out.push(((v as u8) & 0x7F) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

#[inline]
fn write_len_delimited(out: &mut Vec<u8>, tag: u8, bytes: &[u8]) {
    out.push(tag);
    write_varint(out, bytes.len() as u64);
    out.extend_from_slice(bytes);
}

#[inline]
fn zigzag_i32(v: i32) -> u32 {
    ((v << 1) ^ (v >> 31)) as u32
}

/// Liang-Barsky 2D segment clipper against [min_c, max_c] x [min_c, max_c].
#[inline]
fn clip_segment(
    x0: f64,
    y0: f64,
    x1: f64,
    y1: f64,
    min_c: f64,
    max_c: f64,
) -> Option<(f64, f64, f64, f64)> {
    let dx = x1 - x0;
    let dy = y1 - y0;
    let mut t0 = 0.0_f64;
    let mut t1 = 1.0_f64;

    let checks = [
        (-dx, x0 - min_c),
        (dx, max_c - x0),
        (-dy, y0 - min_c),
        (dy, max_c - y0),
    ];

    for (p, q) in checks {
        if p.abs() < 1e-12 {
            if q < 0.0 {
                return None;
            }
        } else {
            let r = q / p;
            if p < 0.0 {
                if r > t1 { return None; }
                if r > t0 { t0 = r; }
            } else {
                if r < t0 { return None; }
                if r < t1 { t1 = r; }
            }
        }
    }

    if t0 > t1 {
        return None;
    }
    Some((x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy))
}

/// Encodes all visible tracks intersecting tile (z, x, y) into an MVT v2.1 "tracks" layer
/// and appends it to `out_tile` as Tile.layers (tag 0x1A).
pub fn append_tracks_mvt_layer(
    z: i32,
    x: i32,
    y: i32,
    hidden_layers: &HashSet<String>,
    out_tile: &mut Vec<u8>,
) {
    let Ok(guard) = tracks_cache_lock().read() else {
        return;
    };
    let Some(cached) = guard.as_ref() else {
        return;
    };
    if cached.tracks.is_empty() {
        return;
    }

    let n_f = (1_i64 << z) as f64;
    let tile_x0 = x as f64 / n_f;
    let tile_y0 = y as f64 / n_f;
    let tile_span = 1.0 / n_f;
    let scale = n_f * MVT_EXTENT as f64;

    let pad_frac = TRACK_CLIP_PAD / MVT_EXTENT as f64;
    let min_mx = tile_x0 - tile_span * pad_frac;
    let max_mx = tile_x0 + tile_span * (1.0 + pad_frac);
    let min_my = tile_y0 - tile_span * pad_frac;
    let max_my = tile_y0 + tile_span * (1.0 + pad_frac);

    let min_c = -TRACK_CLIP_PAD;
    let max_c = MVT_EXTENT as f64 + TRACK_CLIP_PAD;
    let has_hidden = !hidden_layers.is_empty();

    let mut features_buf: Vec<u8> = Vec::new();
    let mut scratch_feat: Vec<u8> = Vec::with_capacity(128);
    let mut scratch_geom: Vec<u8> = Vec::with_capacity(128);
    let mut runs: Vec<Vec<(i32, i32)>> = Vec::with_capacity(4);

    for (t_idx, trk) in cached.tracks.iter().enumerate() {
        if trk.max_mx < min_mx || trk.min_mx > max_mx || trk.max_my < min_my || trk.min_my > max_my {
            continue;
        }
        if has_hidden && hidden_layers.contains(trk.layer_id.as_str()) {
            continue;
        }

        runs.clear();
        let mut cur_run: Vec<(i32, i32)> = Vec::new();

        for win in trk.pts.windows(2) {
            let ax = (win[0].0 - tile_x0) * scale;
            let ay = (win[0].1 - tile_y0) * scale;
            let bx = (win[1].0 - tile_x0) * scale;
            let by = (win[1].1 - tile_y0) * scale;

            let Some((cx0, cy0, cx1, cy1)) = clip_segment(ax, ay, bx, by, min_c, max_c) else {
                if cur_run.len() >= 2 {
                    runs.push(std::mem::take(&mut cur_run));
                } else {
                    cur_run.clear();
                }
                continue;
            };

            let ix0 = cx0.round() as i32;
            let iy0 = cy0.round() as i32;
            let ix1 = cx1.round() as i32;
            let iy1 = cy1.round() as i32;
            if ix0 == ix1 && iy0 == iy1 {
                continue;
            }

            if let Some(&(lx, ly)) = cur_run.last() {
                if (lx - ix0).abs() <= 1 && (ly - iy0).abs() <= 1 {
                    if (ix1 - lx).abs() >= MIN_VERTEX_STEP || (iy1 - ly).abs() >= MIN_VERTEX_STEP {
                        cur_run.push((ix1, iy1));
                    } else if cur_run.len() == 1 {
                        cur_run.push((ix1, iy1));
                    } else {
                        *cur_run.last_mut().unwrap() = (ix1, iy1);
                    }
                } else {
                    if cur_run.len() >= 2 {
                        runs.push(std::mem::take(&mut cur_run));
                    } else {
                        cur_run.clear();
                    }
                    cur_run.push((ix0, iy0));
                    cur_run.push((ix1, iy1));
                }
            } else {
                cur_run.push((ix0, iy0));
                cur_run.push((ix1, iy1));
            }
        }

        if cur_run.len() >= 2 {
            runs.push(cur_run);
        }
        if runs.is_empty() {
            continue;
        }

        scratch_geom.clear();
        let mut cursor_x = 0_i32;
        let mut cursor_y = 0_i32;
        for run in &runs {
            if run.len() < 2 {
                continue;
            }
            // MoveTo(1)
            write_varint(&mut scratch_geom, 9);
            let (x0, y0) = run[0];
            write_varint(&mut scratch_geom, zigzag_i32(x0 - cursor_x) as u64);
            write_varint(&mut scratch_geom, zigzag_i32(y0 - cursor_y) as u64);
            cursor_x = x0;
            cursor_y = y0;

            // LineTo(run.len() - 1)
            let line_cnt = (run.len() - 1) as u64;
            write_varint(&mut scratch_geom, (line_cnt << 3) | 2);
            for &(px, py) in &run[1..] {
                write_varint(&mut scratch_geom, zigzag_i32(px - cursor_x) as u64);
                write_varint(&mut scratch_geom, zigzag_i32(py - cursor_y) as u64);
                cursor_x = px;
                cursor_y = py;
            }
        }
        if scratch_geom.is_empty() {
            continue;
        }

        scratch_feat.clear();
        // Feature.id (field 1)
        scratch_feat.push(0x08);
        write_varint(&mut scratch_feat, t_idx as u64 + 1);
        // Feature.type = LINESTRING (2) (field 3)
        scratch_feat.push(0x18);
        scratch_feat.push(0x02);
        // Feature.geometry (field 4)
        write_len_delimited(&mut scratch_feat, 0x22, &scratch_geom);

        write_len_delimited(&mut features_buf, 0x12, &scratch_feat);
    }

    if features_buf.is_empty() {
        return;
    }

    let mut layer = Vec::with_capacity(features_buf.len() + 32);
    // Layer.version = 2 (field 15)
    layer.push(0x78);
    layer.push(0x02);
    // Layer.name = "tracks" (field 1)
    write_len_delimited(&mut layer, 0x0A, b"tracks");
    // Layer.features (field 2)
    layer.extend_from_slice(&features_buf);
    // Layer.extent = 4096 (field 5)
    layer.push(0x28);
    write_varint(&mut layer, MVT_EXTENT as u64);

    // Tile.layers (field 3)
    write_len_delimited(out_tile, 0x1A, &layer);
}

/// Computes exact [minLat, minLng, maxLat, maxLng] across resident Rust pins and tracks
/// (all layers when `layer_filter` is empty, or a single folder when non-empty).
/// Returns 36 bytes: [u32 count][f64 min_lat][f64 min_lng][f64 max_lat][f64 max_lng]
pub fn compute_resident_bounds(
    version: i64,
    pins_len: usize,
    tracks_len: usize,
    layer_filter: &str,
) -> Option<[u8; 36]> {
    let mut min_lat = f64::INFINITY;
    let mut max_lat = f64::NEG_INFINITY;
    let mut min_lng = f64::INFINITY;
    let mut max_lng = f64::NEG_INFINITY;
    let mut count = 0_u32;

    if pins_len > 0 {
        let guard = pins_cache_lock().read().ok()?;
        let cached = guard.as_ref()?;
        if cached.version != version || cached.pins.len() != pins_len {
            return None;
        }
        for p in &cached.pins {
            if !layer_filter.is_empty() && p.layer_id != layer_filter {
                continue;
            }
            if p.lat.is_finite()
                && p.lng.is_finite()
                && (-90.0..=90.0).contains(&p.lat)
                && (-180.0..=180.0).contains(&p.lng)
            {
                if p.lat < min_lat { min_lat = p.lat; }
                if p.lat > max_lat { max_lat = p.lat; }
                if p.lng < min_lng { min_lng = p.lng; }
                if p.lng > max_lng { max_lng = p.lng; }
                count = count.saturating_add(1);
            }
        }
    }

    if tracks_len > 0 {
        let guard = tracks_cache_lock().read().ok()?;
        let cached = guard.as_ref()?;
        if cached.version != version || cached.raw_len != tracks_len {
            return None;
        }
        for t in &cached.tracks {
            if !layer_filter.is_empty() && t.layer_id != layer_filter {
                continue;
            }
            if t.min_lat < min_lat { min_lat = t.min_lat; }
            if t.max_lat > max_lat { max_lat = t.max_lat; }
            if t.min_lng < min_lng { min_lng = t.min_lng; }
            if t.max_lng > max_lng { max_lng = t.max_lng; }
            count = count.saturating_add(t.pts.len() as u32);
        }
    }

    let mut out = [0u8; 36];
    out[0..4].copy_from_slice(&count.to_le_bytes());
    if count > 0 {
        out[4..12].copy_from_slice(&min_lat.to_le_bytes());
        out[12..20].copy_from_slice(&min_lng.to_le_bytes());
        out[20..28].copy_from_slice(&max_lat.to_le_bytes());
        out[28..36].copy_from_slice(&max_lng.to_le_bytes());
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
    fn clips_and_encodes_tracks_mvt_layer_and_bounds() {
        let _guard = crate::geojson::TEST_CACHE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let mut buf = Vec::new();
        buf.extend_from_slice(&1u32.to_le_bytes());
        put_str(&mut buf, "layerA");
        put_str(&mut buf, "43.85,36.70;43.90,36.75;43.95,36.80");
        assert!(load_geo_tracks(55, &buf));
        assert!(is_geo_tracks_synced(55, 1));

        let (mx, my) = lng_lat_to_mercator_unit(43.90, 36.75);
        let z = 13;
        let n = (1 << z) as f64;
        let tx = (mx * n).floor() as i32;
        let ty = (my * n).floor() as i32;

        let mut tile = Vec::new();
        append_tracks_mvt_layer(z, tx, ty, &HashSet::new(), &mut tile);
        assert!(!tile.is_empty());
        assert_eq!(tile[0], 0x1A); // Tile.layers

        // Hidden layer must emit 0 bytes
        let mut hidden = HashSet::new();
        hidden.insert("layerA".to_string());
        let mut tile_hidden = Vec::new();
        append_tracks_mvt_layer(z, tx, ty, &hidden, &mut tile_hidden);
        assert!(tile_hidden.is_empty());

        // Bounds check
        let b = compute_resident_bounds(55, 0, 1, "layerA").unwrap();
        let cnt = u32::from_le_bytes(b[0..4].try_into().unwrap());
        let min_lat = f64::from_le_bytes(b[4..12].try_into().unwrap());
        let max_lng = f64::from_le_bytes(b[28..36].try_into().unwrap());
        assert_eq!(cnt, 3);
        assert!((min_lat - 36.70).abs() < 1e-6);
        assert!((max_lng - 43.95).abs() < 1e-6);

        clear_geo_tracks();
    }
}
