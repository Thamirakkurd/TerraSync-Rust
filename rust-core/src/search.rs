#[cfg(test)]
use std::sync::{OnceLock, RwLock};

const EARTH_RADIUS_M: f64 = 6_371_000.0;

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

#[inline]
fn haversine_meters(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let d_lat = (lat2 - lat1).to_radians();
    let d_lon = (lon2 - lon1).to_radians();
    let a = (d_lat * 0.5).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lon * 0.5).sin().powi(2);
    let c = 2.0 * a.sqrt().atan2((1.0 - a).max(0.0).sqrt());
    EARTH_RADIUS_M * c
}

/// Wave 12 Unified Pin Store search: queries the single resident `GEO_PINS_CACHE`
/// directly (zero duplicate search index in Rust RAM, automatically updated by `patch_geo_pins`).
///
/// Query wire:
///   [u32 token_count] [tokens...] [u8 has_digits] [u8 has_loc]
///   ([f64 lat] [f64 lng] if has_loc) [optional u8 require_folder_names]
/// Output wire:
///   [u32 match_count] [u8 has_loc] followed by [u32 idx] (+ [f64 dist_m] if has_loc)
pub fn query_geo_pins(version: i64, expected_len: usize, query_payload: &[u8]) -> Option<Vec<u8>> {
    let mut r = BinReader::new(query_payload);
    let token_count = r.read_u32()? as usize;
    let mut tokens: Vec<&str> = Vec::with_capacity(token_count.min(64));
    for _ in 0..token_count {
        let t = r.read_str()?;
        if !t.is_empty() {
            tokens.push(t);
        }
    }
    let has_digits = r.read_u8()? != 0;
    let has_loc = r.read_u8()? != 0;
    let (loc_lat, loc_lng) = if has_loc {
        (r.read_f64()?, r.read_f64()?)
    } else {
        (0.0, 0.0)
    };
    let require_folders = r.read_u8().map(|b| b != 0).unwrap_or(false);

    let guard = crate::geojson::cache_lock().read().ok()?;
    let cached = guard.as_ref()?;
    if cached.version != version || cached.pins.len() != expected_len {
        return None;
    }
    if require_folders && !cached.has_folder_names {
        return None;
    }

    if tokens.is_empty() {
        let mut empty = Vec::with_capacity(5);
        empty.extend_from_slice(&0u32.to_le_bytes());
        empty.push(if has_loc { 1 } else { 0 });
        return Some(empty);
    }

    let mut matches: Vec<(u32, f64)> = Vec::new();
    for (idx, pin) in cached.pins.iter().enumerate() {
        let ok = tokens
            .iter()
            .all(|&tok| pin.search_blob.contains(tok) || (has_digits && pin.coords_text.contains(tok)));
        if ok {
            let d = if has_loc {
                haversine_meters(loc_lat, loc_lng, pin.lat, pin.lng)
            } else {
                0.0
            };
            matches.push((idx as u32, d));
        }
    }

    let stride = if has_loc { 12 } else { 4 };
    let mut out = Vec::with_capacity(5 + matches.len() * stride);
    out.extend_from_slice(&(matches.len() as u32).to_le_bytes());
    out.push(if has_loc { 1 } else { 0 });
    for (idx, dist) in matches {
        out.extend_from_slice(&idx.to_le_bytes());
        if has_loc {
            out.extend_from_slice(&dist.to_le_bytes());
        }
    }
    Some(out)
}

#[cfg(test)]
struct SearchDoc {
    blob: String,
    coords: String,
    lat: f64,
    lng: f64,
}

#[cfg(test)]
struct CachedSearchIndex {
    version: i64,
    docs: Vec<SearchDoc>,
}

#[cfg(test)]
static SEARCH_INDEX: OnceLock<RwLock<Option<CachedSearchIndex>>> = OnceLock::new();

#[cfg(test)]
fn index_lock() -> &'static RwLock<Option<CachedSearchIndex>> {
    SEARCH_INDEX.get_or_init(|| RwLock::new(None))
}

#[cfg(test)]
pub fn load_index(version: i64, payload: &[u8]) -> bool {
    let mut r = BinReader::new(payload);
    let Some(count) = r.read_u32().map(|v| v as usize) else {
        return false;
    };
    let mut docs = Vec::with_capacity(count.min(250_000));
    for _ in 0..count {
        let (Some(blob), Some(coords), Some(lat), Some(lng)) =
            (r.read_str(), r.read_str(), r.read_f64(), r.read_f64())
        else {
            return false;
        };
        docs.push(SearchDoc {
            blob: blob.to_owned(),
            coords: coords.to_owned(),
            lat,
            lng,
        });
    }
    let Ok(mut guard) = index_lock().write() else {
        return false;
    };
    *guard = Some(CachedSearchIndex { version, docs });
    true
}

#[cfg(test)]
pub fn query_from_payload(version: i64, expected_len: usize, query_payload: &[u8]) -> Option<Vec<u8>> {
    let guard = index_lock().read().ok()?;
    let cached = guard.as_ref()?;
    if cached.version != version || cached.docs.len() != expected_len {
        return None;
    }
    let mut r = BinReader::new(query_payload);
    let token_count = r.read_u32()? as usize;
    let mut tokens: Vec<&str> = Vec::with_capacity(token_count.min(64));
    for _ in 0..token_count {
        let t = r.read_str()?;
        if !t.is_empty() {
            tokens.push(t);
        }
    }
    let has_digits = r.read_u8()? != 0;
    let has_loc = r.read_u8()? != 0;
    let (loc_lat, loc_lng) = if has_loc {
        (r.read_f64()?, r.read_f64()?)
    } else {
        (0.0, 0.0)
    };
    let mut matches: Vec<(u32, f64)> = Vec::new();
    for (idx, doc) in cached.docs.iter().enumerate() {
        if tokens
            .iter()
            .all(|&tok| doc.blob.contains(tok) || (has_digits && doc.coords.contains(tok)))
        {
            let d = if has_loc {
                haversine_meters(loc_lat, loc_lng, doc.lat, doc.lng)
            } else {
                0.0
            };
            matches.push((idx as u32, d));
        }
    }
    let stride = if has_loc { 12 } else { 4 };
    let mut out = Vec::with_capacity(5 + matches.len() * stride);
    out.extend_from_slice(&(matches.len() as u32).to_le_bytes());
    out.push(if has_loc { 1 } else { 0 });
    for (idx, dist) in matches {
        out.extend_from_slice(&idx.to_le_bytes());
        if has_loc {
            out.extend_from_slice(&dist.to_le_bytes());
        }
    }
    Some(out)
}

#[cfg(test)]
pub fn clear_index() {
    if let Ok(mut guard) = index_lock().write() {
        *guard = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_str(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
        buf.extend_from_slice(s.as_bytes());
    }

    #[test]
    fn unified_geo_pins_search_and_delta_patch() {
        let _lock = crate::geojson::TEST_CACHE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        crate::geojson::clear_geo_pins();

        let mut load = Vec::new();
        put_str(&mut load, "pin-done");
        put_str(&mut load, "pin-issue");
        put_str(&mut load, "pin-pending");
        load.extend_from_slice(&3u32.to_le_bytes());

        // Pin 0: Done in Akre Feeder 12
        put_str(&mut load, "p0");
        put_str(&mut load, "f_akre");
        put_str(&mut load, "Meter 101");
        load.push(0u8);
        load.extend_from_slice(&43.8935_f64.to_le_bytes());
        load.extend_from_slice(&36.7412_f64.to_le_bytes());

        // Pin 1: Done in Duhok Feeder 3
        put_str(&mut load, "p1");
        put_str(&mut load, "f_duhok");
        put_str(&mut load, "Meter 102");
        load.push(0u8);
        load.extend_from_slice(&42.9900_f64.to_le_bytes());
        load.extend_from_slice(&36.8600_f64.to_le_bytes());

        // Pin 2: Pending in Akre Feeder 12
        put_str(&mut load, "p2");
        put_str(&mut load, "f_akre");
        put_str(&mut load, "Meter 103 ئاکرێ");
        load.push(2u8);
        load.extend_from_slice(&43.9011_f64.to_le_bytes());
        load.extend_from_slice(&36.7455_f64.to_le_bytes());

        // W12S trailer: folder names + (description, issue_reason) per pin
        load.extend_from_slice(b"W12S");
        load.extend_from_slice(&2u32.to_le_bytes());
        put_str(&mut load, "f_akre");
        put_str(&mut load, "Akre Feeder 12");
        put_str(&mut load, "f_duhok");
        put_str(&mut load, "Duhok Feeder 3");
        put_str(&mut load, "pole 12");
        put_str(&mut load, "");
        put_str(&mut load, "pole 7");
        put_str(&mut load, "");
        put_str(&mut load, "smart meter");
        put_str(&mut load, "need electricity");

        assert!(crate::geojson::load_geo_pins(42, &load));

        // 1) Token + folder + coordinate + Haversine query
        let mut q_buf = Vec::new();
        q_buf.extend_from_slice(&2u32.to_le_bytes());
        put_str(&mut q_buf, "akre");
        put_str(&mut q_buf, "36.74");
        q_buf.push(1u8); // has_digits
        q_buf.push(1u8); // has_loc
        q_buf.extend_from_slice(&36.7412_f64.to_le_bytes());
        q_buf.extend_from_slice(&43.8935_f64.to_le_bytes());
        q_buf.push(1u8); // require_folders

        let res = query_geo_pins(42, 3, &q_buf).expect("unified query should hit");
        let count = u32::from_le_bytes(res[0..4].try_into().unwrap());
        assert_eq!(count, 2);
        let i0 = u32::from_le_bytes(res[5..9].try_into().unwrap());
        let d0 = f64::from_le_bytes(res[9..17].try_into().unwrap());
        let i1 = u32::from_le_bytes(res[17..21].try_into().unwrap());
        let d1 = f64::from_le_bytes(res[21..29].try_into().unwrap());
        assert_eq!((i0, i1), (0, 2));
        assert!(d0 < 1.0);
        assert!(d1 > 500.0 && d1 < 1500.0);

        // 2) Delta patch Pin 2 -> status=Issue + new issue_reason -> Search sees it immediately
        let mut delta = Vec::new();
        delta.extend_from_slice(&1u32.to_le_bytes());
        put_str(&mut delta, "p2");
        put_str(&mut delta, "f_akre");
        put_str(&mut delta, "Meter 103 ئاکرێ");
        delta.push(1u8); // issue
        delta.extend_from_slice(&43.9011_f64.to_le_bytes());
        delta.extend_from_slice(&36.7455_f64.to_le_bytes());
        delta.extend_from_slice(b"W12S");
        put_str(&mut delta, "smart meter");
        put_str(&mut delta, "locked gate");
        assert!(crate::geojson::patch_geo_pins(42, 43, 3, &delta));

        let mut q2 = Vec::new();
        q2.extend_from_slice(&2u32.to_le_bytes());
        put_str(&mut q2, "locked");
        put_str(&mut q2, "issue");
        q2.push(0u8);
        q2.push(0u8);
        q2.push(1u8);

        let res2 = query_geo_pins(43, 3, &q2).expect("post-delta search should hit");
        let count2 = u32::from_le_bytes(res2[0..4].try_into().unwrap());
        assert_eq!(count2, 1);
        assert_eq!(u32::from_le_bytes(res2[5..9].try_into().unwrap()), 2);

        // Stale version must miss cleanly
        assert!(query_geo_pins(42, 3, &q2).is_none());
        crate::geojson::clear_geo_pins();
    }
}
