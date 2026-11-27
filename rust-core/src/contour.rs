use std::collections::{HashMap, VecDeque};
use std::f64::consts::PI;

#[derive(Debug, Clone)]
pub struct ContourLine {
    pub is_major: bool,
    pub level: i32,
    pub points: Vec<(f64, f64)>, // (lng, lat)
}

#[inline]
fn tile_x_to_lng(x: f64, z: u32) -> f64 {
    x / (1u64 << z) as f64 * 360.0 - 180.0
}

#[inline]
fn tile_y_to_lat(y: f64, z: u32) -> f64 {
    let n = PI - 2.0 * PI * y / (1u64 << z) as f64;
    n.sinh().atan().to_degrees()
}

#[inline]
fn interp(v0: f32, v1: f32, level: f32) -> f64 {
    let d = v1 - v0;
    if d.abs() < 1e-4 {
        0.5
    } else {
        (((level - v0) / d) as f64).clamp(0.0, 1.0)
    }
}

#[inline]
fn pt_key(lng: f64, lat: f64) -> u64 {
    let qx = (lng * 100_000.0 + 0.5).floor() as i64;
    let qy = (lat * 100_000.0 + 0.5).floor() as i64;
    ((qx as u64) << 32) ^ ((qy as u64) & 0xFFFF_FFFF)
}

pub fn extract_contours(
    elev: &[f32],
    z: i32,
    x: i32,
    y: i32,
    n: usize,
    step: i32,
    major_step: i32,
) -> Vec<ContourLine> {
    if n == 0 || step <= 0 || major_step <= 0 || !(0..=22).contains(&z) {
        return Vec::new();
    }
    let stride = n + 1;
    let needed = stride * stride;
    if elev.len() < needed {
        return Vec::new();
    }

    let mut min_e = f32::INFINITY;
    let mut max_e = f32::NEG_INFINITY;
    for &v in &elev[..needed] {
        if v < min_e { min_e = v; }
        if v > max_e { max_e = v; }
    }
    if !min_e.is_finite() || !max_e.is_finite() || (max_e - min_e) < (step as f32) * 0.5 {
        return Vec::new();
    }

    let start_level = (((min_e as f64) / (step as f64)).floor() as i32 + 1) * step;
    let end_level = (((max_e as f64) / (step as f64)).floor() as i32) * step;
    if end_level < start_level {
        return Vec::new();
    }

    let zu = z as u32;
    let west = tile_x_to_lng(x as f64, zu);
    let east = tile_x_to_lng((x + 1) as f64, zu);
    let lng_span = east - west;
    let nf = n as f64;

    let mut row_lats = Vec::with_capacity(stride);
    for gy in 0..stride {
        row_lats.push(tile_y_to_lat(y as f64 + (gy as f64) / nf, zu));
    }

    let mut out = Vec::new();
    let mut segments: Vec<[f64; 4]> = Vec::with_capacity(128);
    let mut level = start_level;

    while level <= end_level {
        if level <= 0 {
            level += step;
            continue;
        }
        let lf = level as f32;
        let is_major = (level % major_step) == 0;
        segments.clear();

        for gy in 0..n {
            let lat_top = row_lats[gy];
            let lat_bot = row_lats[gy + 1];
            let row0 = gy * stride;
            let row1 = (gy + 1) * stride;
            for gx in 0..n {
                let v_tl = elev[row0 + gx];
                let v_tr = elev[row0 + gx + 1];
                let v_br = elev[row1 + gx + 1];
                let v_bl = elev[row1 + gx];

                let mut mask = 0u8;
                if v_tl >= lf { mask |= 8; }
                if v_tr >= lf { mask |= 4; }
                if v_br >= lf { mask |= 2; }
                if v_bl >= lf { mask |= 1; }
                if mask == 0 || mask == 15 {
                    continue;
                }

                let lng_l = west + lng_span * (gx as f64 / nf);
                let lng_r = west + lng_span * ((gx + 1) as f64 / nf);

                let top_lng = || lng_l + (lng_r - lng_l) * interp(v_tl, v_tr, lf);
                let right_lat = || lat_top + (lat_bot - lat_top) * interp(v_tr, v_br, lf);
                let bot_lng = || lng_l + (lng_r - lng_l) * interp(v_bl, v_br, lf);
                let left_lat = || lat_top + (lat_bot - lat_top) * interp(v_tl, v_bl, lf);

                match mask {
                    1 | 14 => segments.push([lng_l, left_lat(), bot_lng(), lat_bot]),
                    2 | 13 => segments.push([bot_lng(), lat_bot, lng_r, right_lat()]),
                    3 | 12 => segments.push([lng_l, left_lat(), lng_r, right_lat()]),
                    4 | 11 => segments.push([top_lng(), lat_top, lng_r, right_lat()]),
                    5 => {
                        segments.push([lng_l, left_lat(), top_lng(), lat_top]);
                        segments.push([bot_lng(), lat_bot, lng_r, right_lat()]);
                    }
                    6 | 9 => segments.push([top_lng(), lat_top, bot_lng(), lat_bot]),
                    7 | 8 => segments.push([lng_l, left_lat(), top_lng(), lat_top]),
                    10 => {
                        segments.push([lng_l, left_lat(), bot_lng(), lat_bot]);
                        segments.push([top_lng(), lat_top, lng_r, right_lat()]);
                    }
                    _ => {}
                }
            }
        }

        if !segments.is_empty() {
            let mut adj: HashMap<u64, Vec<usize>> = HashMap::with_capacity(segments.len() * 2);
            for (idx, seg) in segments.iter().enumerate() {
                adj.entry(pt_key(seg[0], seg[1])).or_default().push(idx);
                adj.entry(pt_key(seg[2], seg[3])).or_default().push(idx);
            }
            let mut used = vec![false; segments.len()];
            for i in 0..segments.len() {
                if used[i] {
                    continue;
                }
                used[i] = true;
                let s0 = segments[i];
                let mut chain: VecDeque<(f64, f64)> = VecDeque::with_capacity(16);
                chain.push_back((s0[0], s0[1]));
                chain.push_back((s0[2], s0[3]));

                loop {
                    let &(tx, ty) = chain.back().unwrap();
                    let tail_key = pt_key(tx, ty);
                    let next_idx = adj
                        .get(&tail_key)
                        .and_then(|cands| cands.iter().copied().find(|&id| !used[id]));
                    let Some(ni) = next_idx else { break; };
                    used[ni] = true;
                    let s = segments[ni];
                    if pt_key(s[0], s[1]) == tail_key {
                        chain.push_back((s[2], s[3]));
                    } else {
                        chain.push_back((s[0], s[1]));
                    }
                }

                loop {
                    let &(hx, hy) = chain.front().unwrap();
                    let head_key = pt_key(hx, hy);
                    let prev_idx = adj
                        .get(&head_key)
                        .and_then(|cands| cands.iter().copied().find(|&id| !used[id]));
                    let Some(pi) = prev_idx else { break; };
                    used[pi] = true;
                    let s = segments[pi];
                    if pt_key(s[0], s[1]) == head_key {
                        chain.push_front((s[2], s[3]));
                    } else {
                        chain.push_front((s[0], s[1]));
                    }
                }

                if chain.len() >= 2 {
                    out.push(ContourLine {
                        is_major,
                        level,
                        points: chain.into_iter().collect(),
                    });
                }
            }
        }

        level += step;
    }

    out
}

pub fn encode_contours(lines: &[ContourLine]) -> Vec<u8> {
    let total_pts: usize = lines.iter().map(|l| l.points.len()).sum();
    let mut out = Vec::with_capacity(4 + lines.len() * 9 + total_pts * 16);
    out.extend_from_slice(&(lines.len() as u32).to_le_bytes());
    for line in lines {
        out.push(if line.is_major { 1 } else { 0 });
        out.extend_from_slice(&line.level.to_le_bytes());
        out.extend_from_slice(&(line.points.len() as u32).to_le_bytes());
        for &(lng, lat) in &line.points {
            out.extend_from_slice(&lng.to_le_bytes());
            out.extend_from_slice(&lat.to_le_bytes());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_and_stitches_major_and_minor_contours() {
        let n = 10usize;
        let stride = n + 1;
        let mut elev = vec![0.0f32; stride * stride];
        // Smooth mountain peak from 350m at edges to 1150m at center
        for gy in 0..stride {
            for gx in 0..stride {
                let dx = gx as f32 - 5.0;
                let dy = gy as f32 - 5.0;
                let dist = (dx * dx + dy * dy).sqrt();
                elev[gy * stride + gx] = (1150.0 - dist * 120.0).max(350.0);
            }
        }
        let lines = extract_contours(&elev, 11, 1273, 799, n, 100, 500);
        assert!(!lines.is_empty());
        assert!(lines.iter().any(|l| l.level == 500 && l.is_major && l.points.len() >= 4));
        assert!(lines.iter().any(|l| l.level == 1000 && l.is_major && l.points.len() >= 4));
        assert!(lines.iter().any(|l| l.level == 700 && !l.is_major));

        let encoded = encode_contours(&lines);
        let decoded_count = u32::from_le_bytes(encoded[0..4].try_into().unwrap()) as usize;
        assert_eq!(decoded_count, lines.len());
    }
}
