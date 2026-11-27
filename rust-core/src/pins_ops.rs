use std::collections::{HashMap, VecDeque};

const EARTH_RADIUS_M: f64 = 6_371_000.0;
const STACKED_CELL_SIZE: f64 = 0.00003;

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

    fn read_i64(&mut self) -> Option<i64> {
        let slice = self.buf.get(self.pos..self.pos + 8)?;
        self.pos += 8;
        Some(i64::from_le_bytes(slice.try_into().ok()?))
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
fn stacked_bucket_key(cx: i32, cy: i32) -> i64 {
    ((cx as i64) << 32) ^ ((cy as i64) & 0xFFFF_FFFF)
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

pub fn analyze_same_coord_clusters(payload: &[u8]) -> Option<Vec<u8>> {
    let mut r = BinReader::new(payload);
    let count = r.read_u32()? as usize;

    struct Node<'a> {
        lat: f64,
        lng: f64,
        cx: i32,
        cy: i32,
        rank: u8,
        edited_at: i64,
        updated_at: i64,
        id: &'a str,
    }

    let mut nodes: Vec<Node<'_>> = Vec::with_capacity(count.min(250_000));
    let mut buckets: HashMap<i64, Vec<usize>> = HashMap::with_capacity(count.min(250_000));

    for idx in 0..count {
        let lat = r.read_f64()?;
        let lng = r.read_f64()?;
        let rank = r.read_u8()?;
        let edited_at = r.read_i64()?;
        let updated_at = r.read_i64()?;
        let id = r.read_str()?;
        let cx = (lat / STACKED_CELL_SIZE).floor() as i32;
        let cy = (lng / STACKED_CELL_SIZE).floor() as i32;
        buckets.entry(stacked_bucket_key(cx, cy)).or_default().push(idx);
        nodes.push(Node { lat, lng, cx, cy, rank, edited_at, updated_at, id });
    }

    let mut visited = vec![false; count];
    let mut extras: Vec<u32> = Vec::new();
    let mut sized: Vec<(u32, u32)> = Vec::new();
    let mut queue: VecDeque<usize> = VecDeque::with_capacity(16);
    let mut cluster: Vec<usize> = Vec::with_capacity(16);

    for i in 0..count {
        if visited[i] { continue; }
        visited[i] = true;
        queue.clear();
        cluster.clear();
        queue.push_back(i);

        while let Some(cur_idx) = queue.pop_front() {
            cluster.push(cur_idx);
            let cur = &nodes[cur_idx];
            for dx in -1..=1 {
                for dy in -1..=1 {
                    let Some(cands) = buckets.get(&stacked_bucket_key(cur.cx + dx, cur.cy + dy)) else {
                        continue;
                    };
                    for &cand_idx in cands {
                        if visited[cand_idx] { continue; }
                        let cand = &nodes[cand_idx];
                        if haversine_meters(cur.lat, cur.lng, cand.lat, cand.lng) <= 2.0 {
                            visited[cand_idx] = true;
                            queue.push_back(cand_idx);
                        }
                    }
                }
            }
        }

        if cluster.len() >= 2 {
            cluster.sort_by(|&a, &b| {
                let na = &nodes[a];
                let nb = &nodes[b];
                nb.rank
                    .cmp(&na.rank)
                    .then_with(|| nb.edited_at.cmp(&na.edited_at))
                    .then_with(|| nb.updated_at.cmp(&na.updated_at))
                    .then_with(|| na.id.cmp(nb.id))
            });
            let c_len = cluster.len() as u32;
            for (pos, &member_idx) in cluster.iter().enumerate() {
                sized.push((member_idx as u32, c_len));
                if pos >= 1 {
                    extras.push(member_idx as u32);
                }
            }
        }
    }

    let mut out = Vec::with_capacity(8 + extras.len() * 4 + sized.len() * 8);
    out.extend_from_slice(&(extras.len() as u32).to_le_bytes());
    for e in extras { out.extend_from_slice(&e.to_le_bytes()); }
    out.extend_from_slice(&(sized.len() as u32).to_le_bytes());
    for (idx, sz) in sized {
        out.extend_from_slice(&idx.to_le_bytes());
        out.extend_from_slice(&sz.to_le_bytes());
    }
    Some(out)
}

pub fn sort_and_score_pins(payload: &[u8]) -> Option<Vec<u8>> {
    let mut r = BinReader::new(payload);
    let has_loc = r.read_u8()? != 0;
    let (u_lat, u_lng) = if has_loc { (r.read_f64()?, r.read_f64()?) } else { (0.0, 0.0) };
    let use_nearest = r.read_u8()? != 0 && has_loc;
    let count = r.read_u32()? as usize;

    let mut sel_prio: Vec<u8> = Vec::with_capacity(count.min(250_000));
    let mut dists: Vec<f64> = if has_loc { Vec::with_capacity(count.min(250_000)) } else { Vec::new() };
    let mut names: Vec<&str> = if !use_nearest { Vec::with_capacity(count.min(250_000)) } else { Vec::new() };

    for _ in 0..count {
        let sp = r.read_u8()?;
        let lat = r.read_f64()?;
        let lng = r.read_f64()?;
        sel_prio.push(sp);
        if has_loc { dists.push(haversine_meters(u_lat, u_lng, lat, lng)); }
        if !use_nearest { names.push(r.read_str()?); }
    }

    let mut order: Vec<usize> = (0..count).collect();
    if use_nearest {
        order.sort_by(|&a, &b| sel_prio[a].cmp(&sel_prio[b]).then_with(|| dists[a].total_cmp(&dists[b])));
    } else {
        order.sort_by(|&a, &b| sel_prio[a].cmp(&sel_prio[b]).then_with(|| names[a].cmp(names[b])));
    }

    let stride = if has_loc { 12 } else { 4 };
    let mut out = Vec::with_capacity(5 + count * stride);
    out.extend_from_slice(&(count as u32).to_le_bytes());
    out.push(if has_loc { 1 } else { 0 });
    for idx in order {
        out.extend_from_slice(&(idx as u32).to_le_bytes());
        if has_loc { out.extend_from_slice(&dists[idx].to_le_bytes()); }
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
    fn clusters_within_2m_and_sorts_nearest() {
        let mut buf = Vec::new();
        buf.extend_from_slice(&2u32.to_le_bytes());
        buf.extend_from_slice(&36.740000f64.to_le_bytes());
        buf.extend_from_slice(&43.890000f64.to_le_bytes());
        buf.push(0u8);
        buf.extend_from_slice(&100i64.to_le_bytes());
        buf.extend_from_slice(&100i64.to_le_bytes());
        put_str(&mut buf, "a");

        buf.extend_from_slice(&36.740005f64.to_le_bytes());
        buf.extend_from_slice(&43.890005f64.to_le_bytes());
        buf.push(3u8);
        buf.extend_from_slice(&200i64.to_le_bytes());
        buf.extend_from_slice(&200i64.to_le_bytes());
        put_str(&mut buf, "b");

        let res = analyze_same_coord_clusters(&buf).unwrap();
        assert_eq!(u32::from_le_bytes(res[0..4].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(res[4..8].try_into().unwrap()), 0);
    }
}
