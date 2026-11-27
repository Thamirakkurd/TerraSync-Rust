use std::sync::{OnceLock, RwLock};

const EARTH_RADIUS_M: f64 = 6_371_000.0;

struct CachedNavRoute {
    route_id: i64,
    coords: Vec<(f64, f64)>, // (lng, lat)
    seg_lens: Vec<f64>,
    cum_lens: Vec<f64>,
}

static ACTIVE_NAV_ROUTE: OnceLock<RwLock<Option<CachedNavRoute>>> = OnceLock::new();

fn route_lock() -> &'static RwLock<Option<CachedNavRoute>> {
    ACTIVE_NAV_ROUTE.get_or_init(|| RwLock::new(None))
}

#[inline]
fn haversine_meters(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let d_lat = (lat2 - lat1).to_radians();
    let d_lon = (lon2 - lon1).to_radians();
    let a = ((d_lat * 0.5).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lon * 0.5).sin().powi(2))
    .clamp(0.0, 1.0);
    2.0 * EARTH_RADIUS_M * a.sqrt().atan2((1.0 - a).sqrt())
}

/// Decodes a precision-6 encoded polyline into flat [u32 count][lng0, lat0, ...] f64 wire bytes.
pub fn decode_polyline6_wire(encoded: &str) -> Vec<u8> {
    let bytes = encoded.as_bytes();
    let len = bytes.len();
    let mut idx = 0usize;
    let mut lat: i64 = 0;
    let mut lng: i64 = 0;
    let mut pts: Vec<(f64, f64)> = Vec::with_capacity(len / 4 + 8);

    while idx < len {
        let mut shift = 0u32;
        let mut result: i64 = 0;
        loop {
            if idx >= len {
                break;
            }
            let b = (bytes[idx] as i64) - 63;
            idx += 1;
            result |= (b & 0x1F) << shift;
            shift += 5;
            if b < 0x20 || shift > 55 {
                break;
            }
        }
        let dlat = if (result & 1) != 0 { !(result >> 1) } else { result >> 1 };
        lat += dlat;

        if idx >= len {
            break;
        }
        shift = 0;
        result = 0;
        loop {
            if idx >= len {
                break;
            }
            let b = (bytes[idx] as i64) - 63;
            idx += 1;
            result |= (b & 0x1F) << shift;
            shift += 5;
            if b < 0x20 || shift > 55 {
                break;
            }
        }
        let dlng = if (result & 1) != 0 { !(result >> 1) } else { result >> 1 };
        lng += dlng;

        pts.push((lng as f64 / 1e6, lat as f64 / 1e6));
    }

    let mut out = Vec::with_capacity(4 + pts.len() * 16);
    out.extend_from_slice(&(pts.len() as u32).to_le_bytes());
    for (lo, la) in pts {
        out.extend_from_slice(&lo.to_le_bytes());
        out.extend_from_slice(&la.to_le_bytes());
    }
    out
}

/// Caches active navigation route geometry (flat [lng0, lat0, ...]) and precomputes segment lengths.
pub fn load_nav_route(route_id: i64, flat_lng_lat: &[f64]) -> bool {
    let pt_count = flat_lng_lat.len() / 2;
    if pt_count < 2 {
        return false;
    }
    let mut coords = Vec::with_capacity(pt_count);
    for i in 0..pt_count {
        coords.push((flat_lng_lat[i * 2], flat_lng_lat[i * 2 + 1]));
    }
    let seg_count = pt_count - 1;
    let mut seg_lens = Vec::with_capacity(seg_count);
    let mut cum_lens = Vec::with_capacity(pt_count);
    cum_lens.push(0.0);
    let mut acc = 0.0;
    for i in 0..seg_count {
        let len = haversine_meters(coords[i].1, coords[i].0, coords[i + 1].1, coords[i + 1].0);
        seg_lens.push(len);
        acc += len;
        cum_lens.push(acc);
    }

    let Ok(mut guard) = route_lock().write() else {
        return false;
    };
    *guard = Some(CachedNavRoute {
        route_id,
        coords,
        seg_lens,
        cum_lens,
    });
    true
}

/// Zero-copy per-GPS-tick route progress tracker (exact parity with RoutingEngine.trackRouteProgressInternal).
/// Returns 44 bytes: [i32 seg_idx][f64 off_route_m][f64 traveled_m][f64 remaining_m][f64 proj_lat][f64 proj_lng]
pub fn track_nav_route(
    route_id: i64,
    expected_len: usize,
    lat: f64,
    lng: f64,
    last_seg_idx: i32,
) -> Option<[u8; 44]> {
    let guard = route_lock().read().ok()?;
    let route = guard.as_ref()?;
    if route.route_id != route_id || route.coords.len() != expected_len || route.coords.len() < 2 {
        return None;
    }

    let cos_lat = lat.to_radians().cos();
    let m_per_deg_lat = 111_132.0;
    let m_per_deg_lng = 111_320.0 * cos_lat;
    let seg_count = route.coords.len() - 1;

    let scan_range = |start_idx: usize, end_idx: usize| -> (usize, f64, f64) {
        let mut best_idx = start_idx;
        let mut best_dist = f64::MAX;
        let mut best_t = 0.0;
        for i in start_idx..=end_idx {
            let (a_lng, a_lat) = route.coords[i];
            let (b_lng, b_lat) = route.coords[i + 1];
            let ax = (a_lng - lng) * m_per_deg_lng;
            let ay = (a_lat - lat) * m_per_deg_lat;
            let bx = (b_lng - lng) * m_per_deg_lng;
            let by = (b_lat - lat) * m_per_deg_lat;
            let dx = bx - ax;
            let dy = by - ay;
            let len_sq = dx * dx + dy * dy;
            let t = if len_sq > 1e-6 {
                (-(ax * dx + ay * dy) / len_sq).clamp(0.0, 1.0)
            } else {
                0.0
            };
            let px = ax + t * dx;
            let py = ay + t * dy;
            let d = px.hypot(py);
            if d < best_dist {
                best_dist = d;
                best_idx = i;
                best_t = t;
            }
        }
        (best_idx, best_t, best_dist)
    };

    let safe_last = (last_seg_idx.max(0) as usize).min(seg_count - 1);
    let win_start = safe_last.saturating_sub(3);
    let mut win_end = safe_last;
    while win_end < seg_count - 1 {
        let ahead = route.cum_lens[win_end + 1] - route.cum_lens[safe_last];
        if ahead >= 2000.0 && win_end - safe_last >= 35 {
            break;
        }
        win_end += 1;
    }

    let (mut best_seg_idx, mut best_t, mut best_dist) = scan_range(win_start, win_end);
    if best_dist > 45.0 {
        let (full_idx, full_t, full_dist) = scan_range(0, seg_count - 1);
        if full_dist < best_dist - 12.0 {
            best_seg_idx = full_idx;
            best_t = full_t;
            best_dist = full_dist;
        }
    }

    let (s_lng, s_lat) = route.coords[best_seg_idx];
    let (e_lng, e_lat) = route.coords[best_seg_idx + 1];
    let proj_lng = s_lng + best_t * (e_lng - s_lng);
    let proj_lat = s_lat + best_t * (e_lat - s_lat);
    let traveled = route.cum_lens[best_seg_idx] + route.seg_lens[best_seg_idx] * best_t;
    let remaining = (route.cum_lens[seg_count] - traveled).max(0.0);

    let mut out = [0u8; 44];
    out[0..4].copy_from_slice(&(best_seg_idx as i32).to_le_bytes());
    out[4..12].copy_from_slice(&best_dist.to_le_bytes());
    out[12..20].copy_from_slice(&traveled.to_le_bytes());
    out[20..28].copy_from_slice(&remaining.to_le_bytes());
    out[28..36].copy_from_slice(&proj_lat.to_le_bytes());
    out[36..44].copy_from_slice(&proj_lng.to_le_bytes());
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_polyline6_and_snaps_gps_progress() {
        // Simple 3-point route in Akre
        let flat = [43.890_f64, 36.740_f64, 43.900_f64, 36.740_f64, 43.910_f64, 36.740_f64];
        assert!(load_nav_route(77, &flat));
        let res = track_nav_route(77, 3, 36.74005, 43.895, 0).unwrap();
        let seg_idx = i32::from_le_bytes(res[0..4].try_into().unwrap());
        let off_m = f64::from_le_bytes(res[4..12].try_into().unwrap());
        let traveled = f64::from_le_bytes(res[12..20].try_into().unwrap());
        let remaining = f64::from_le_bytes(res[20..28].try_into().unwrap());
        assert_eq!(seg_idx, 0);
        assert!(off_m < 10.0);
        assert!(traveled > 400.0 && remaining > 1200.0);

        // Polyline6 decode sanity check (single encoded point at (38.5, -120.2) in prec 6)
        let wire = decode_polyline6_wire("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI");
        let cnt = u32::from_le_bytes(wire[0..4].try_into().unwrap());
        assert_eq!(cnt, 3);
    }
}
