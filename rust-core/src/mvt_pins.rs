use std::collections::{HashMap, HashSet};
use std::sync::{OnceLock, RwLock};

use crate::geojson::cache_lock;

const MVT_EXTENT: i32 = 4096;
const TILE_BUFFER_UNITS: i32 = 256; // ~3.9% tile edge padding to prevent teardrop pin clipping
const CLUSTER_MAX_ZOOM: i32 = 15;

struct ActiveMvtFilter {
    status_mask: u8,
    hidden_layers: HashSet<String>,
    sent_ids: HashSet<String>,
    clustered: bool,
    show_titles: bool,
}

impl Default for ActiveMvtFilter {
    fn default() -> Self {
        Self {
            status_mask: 0b111,
            hidden_layers: HashSet::new(),
            sent_ids: HashSet::new(),
            clustered: false,
            show_titles: true,
        }
    }
}

static MVT_FILTER: OnceLock<RwLock<ActiveMvtFilter>> = OnceLock::new();

fn filter_lock() -> &'static RwLock<ActiveMvtFilter> {
    MVT_FILTER.get_or_init(|| RwLock::new(ActiveMvtFilter::default()))
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

    fn read_str(&mut self) -> Option<&'a str> {
        let len = self.read_u32()? as usize;
        let slice = self.buf.get(self.pos..self.pos + len)?;
        self.pos += len;
        std::str::from_utf8(slice).ok()
    }
}

/// Updates active filter state once when filters/toggles change so fetchTile(z, x, y) passes only 3 ints.
/// Wire: [u8 status_mask][u8 clustered][u8 show_titles][u32 hidden_cnt][hidden...][u32 sent_cnt][sent...]
pub fn set_mvt_filter(payload: &[u8]) -> bool {
    let mut r = BinReader::new(payload);
    let (Some(status_mask), Some(clustered_b), Some(show_titles_b)) =
        (r.read_u8(), r.read_u8(), r.read_u8())
    else {
        return false;
    };
    let Some(hidden_cnt) = r.read_u32().map(|v| v as usize) else {
        return false;
    };
    let mut hidden_layers = HashSet::with_capacity(hidden_cnt);
    for _ in 0..hidden_cnt {
        let Some(s) = r.read_str() else { return false; };
        hidden_layers.insert(s.to_owned());
    }
    let Some(sent_cnt) = r.read_u32().map(|v| v as usize) else {
        return false;
    };
    let mut sent_ids = HashSet::with_capacity(sent_cnt);
    for _ in 0..sent_cnt {
        let Some(s) = r.read_str() else { return false; };
        sent_ids.insert(s.to_owned());
    }

    let Ok(mut guard) = filter_lock().write() else {
        return false;
    };
    *guard = ActiveMvtFilter {
        status_mask,
        hidden_layers,
        sent_ids,
        clustered: clustered_b != 0,
        show_titles: show_titles_b != 0,
    };
    true
}

// --- Zero-dependency MVT v2.1 binary protobuf encoder ---

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

#[derive(Hash, Eq, PartialEq)]
enum MvtVal {
    Str(String),
    UInt(u64),
}

struct MvtLayerBuilder {
    keys: Vec<String>,
    key_map: HashMap<String, u32>,
    values: Vec<MvtVal>,
    val_map: HashMap<MvtVal, u32>,
    features_buf: Vec<u8>,
    scratch_feat: Vec<u8>,
    scratch_tags: Vec<u8>,
    scratch_geom: Vec<u8>,
}

impl MvtLayerBuilder {
    fn with_capacity(feat_est: usize) -> Self {
        Self {
            keys: Vec::with_capacity(8),
            key_map: HashMap::with_capacity(8),
            values: Vec::with_capacity(feat_est.min(4096) * 2),
            val_map: HashMap::with_capacity(feat_est.min(4096) * 2),
            features_buf: Vec::with_capacity(feat_est.min(4096) * 28),
            scratch_feat: Vec::with_capacity(64),
            scratch_tags: Vec::with_capacity(24),
            scratch_geom: Vec::with_capacity(16),
        }
    }

    fn intern_key(&mut self, k: &str) -> u32 {
        if let Some(&idx) = self.key_map.get(k) {
            return idx;
        }
        let idx = self.keys.len() as u32;
        self.keys.push(k.to_owned());
        self.key_map.insert(k.to_owned(), idx);
        idx
    }

    fn intern_str(&mut self, s: &str) -> u32 {
        let key = MvtVal::Str(s.to_owned());
        if let Some(&idx) = self.val_map.get(&key) {
            return idx;
        }
        let idx = self.values.len() as u32;
        self.values.push(MvtVal::Str(s.to_owned()));
        self.val_map.insert(key, idx);
        idx
    }

    fn intern_uint(&mut self, v: u64) -> u32 {
        let key = MvtVal::UInt(v);
        if let Some(&idx) = self.val_map.get(&key) {
            return idx;
        }
        let idx = self.values.len() as u32;
        self.values.push(MvtVal::UInt(v));
        self.val_map.insert(key, idx);
        idx
    }

    fn add_point_feature(&mut self, feat_id: u64, tx: i32, ty: i32, tags: &[(u32, u32)]) {
        self.scratch_feat.clear();
        self.scratch_tags.clear();
        self.scratch_geom.clear();

        // Feature.id (field 1, varint)
        self.scratch_feat.push(0x08);
        write_varint(&mut self.scratch_feat, feat_id);

        // Feature.tags (field 2, packed uint32)
        for &(k, v) in tags {
            write_varint(&mut self.scratch_tags, k as u64);
            write_varint(&mut self.scratch_tags, v as u64);
        }
        if !self.scratch_tags.is_empty() {
            write_len_delimited(&mut self.scratch_feat, 0x12, &self.scratch_tags);
        }

        // Feature.type = POINT (1) (field 3, varint)
        self.scratch_feat.push(0x18);
        self.scratch_feat.push(0x01);

        // Feature.geometry (field 4, packed uint32): MoveTo(count=1)=9, zigzag(tx), zigzag(ty)
        write_varint(&mut self.scratch_geom, 9);
        write_varint(&mut self.scratch_geom, zigzag_i32(tx) as u64);
        write_varint(&mut self.scratch_geom, zigzag_i32(ty) as u64);
        write_len_delimited(&mut self.scratch_feat, 0x22, &self.scratch_geom);

        // Append as Layer.features (field 2, length-delimited)
        write_len_delimited(&mut self.features_buf, 0x12, &self.scratch_feat);
    }

    fn finish_tile(self, layer_name: &str) -> Vec<u8> {
        if self.features_buf.is_empty() {
            return Vec::new();
        }
        let mut layer = Vec::with_capacity(self.features_buf.len() + self.values.len() * 16 + 64);

        // Layer.version = 2 (field 15, varint: tag 0x78)
        layer.push(0x78);
        layer.push(0x02);

        // Layer.name (field 1, string: tag 0x0A)
        write_len_delimited(&mut layer, 0x0A, layer_name.as_bytes());

        // Layer.features (field 2, pre-encoded)
        layer.extend_from_slice(&self.features_buf);

        // Layer.keys (field 3, string: tag 0x1A)
        for k in &self.keys {
            write_len_delimited(&mut layer, 0x1A, k.as_bytes());
        }

        // Layer.values (field 4, Value message: tag 0x22)
        let mut vbuf = Vec::with_capacity(64);
        for val in &self.values {
            vbuf.clear();
            match val {
                MvtVal::Str(s) => write_len_delimited(&mut vbuf, 0x0A, s.as_bytes()),
                MvtVal::UInt(u) => {
                    vbuf.push(0x28); // Value.uint_value = field 5
                    write_varint(&mut vbuf, *u);
                }
            }
            write_len_delimited(&mut layer, 0x22, &vbuf);
        }

        // Layer.extent = 4096 (field 5, varint: tag 0x28)
        layer.push(0x28);
        write_varint(&mut layer, MVT_EXTENT as u64);

        // Wrap in Tile.layers (field 3, length-delimited: tag 0x1A)
        let mut tile = Vec::with_capacity(layer.len() + 8);
        write_len_delimited(&mut tile, 0x1A, &layer);
        tile
    }
}

fn format_cluster_abbrev(count: u32) -> String {
    if count >= 1_000_000 {
        format!("{:.1}M", count as f64 / 1_000_000.0)
    } else if count >= 1_000 {
        let k = count as f64 / 1_000.0;
        if (k * 10.0).round() as u32 % 10 == 0 {
            format!("{}k", k.round() as u32)
        } else {
            format!("{:.1}k", k)
        }
    } else {
        count.to_string()
    }
}

/// Renders a binary MVT v2.1 tile ("pins" layer) directly from the resident Rust pin store.
/// Uses O(log N) binary search over Mercator X so each tile only visits pins inside its longitude band.
pub fn render_pins_mvt_tile(z: i32, x: i32, y: i32) -> Vec<u8> {
    if !(0..=22).contains(&z) {
        return Vec::new();
    }
    let n_tiles = 1_i64 << z;
    if x < 0 || y < 0 || (x as i64) >= n_tiles || (y as i64) >= n_tiles {
        return Vec::new();
    }

    let Ok(pins_guard) = cache_lock().read() else {
        return Vec::new();
    };
    let Some(cached) = pins_guard.as_ref() else {
        return Vec::new();
    };
    if cached.pins.is_empty() {
        return Vec::new();
    }

    let Ok(flt) = filter_lock().read() else {
        return Vec::new();
    };
    if flt.status_mask == 0 {
        return Vec::new();
    }

    let n_f = n_tiles as f64;
    let tile_x0 = x as f64 / n_f;
    let tile_y0 = y as f64 / n_f;
    let tile_span = 1.0 / n_f;

    let do_cluster = flt.clustered && z <= CLUSTER_MAX_ZOOM;
    let buf_frac = if do_cluster {
        0.0
    } else {
        TILE_BUFFER_UNITS as f64 / MVT_EXTENT as f64
    };
    let min_mx = tile_x0 - tile_span * buf_frac;
    let max_mx = tile_x0 + tile_span * (1.0 + buf_frac);
    let min_my = tile_y0 - tile_span * buf_frac;
    let max_my = tile_y0 + tile_span * (1.0 + buf_frac);

    let start_pos = cached.x_index.partition_point(|&(mx, _)| mx < min_mx);
    let end_pos = cached.x_index.partition_point(|&(mx, _)| mx <= max_mx);
    if start_pos >= end_pos {
        return Vec::new();
    }

    let has_hidden = !flt.hidden_layers.is_empty();
    let has_sent = !flt.sent_ids.is_empty();
    let mut hits: Vec<(usize, i32, i32)> = Vec::with_capacity((end_pos - start_pos).min(2048));

    for &(_, idx) in &cached.x_index[start_pos..end_pos] {
        let p = &cached.pins[idx];
        if p.my < min_my || p.my > max_my {
            continue;
        }
        if has_hidden && flt.hidden_layers.contains(p.layer_id.as_str()) {
            continue;
        }
        if has_sent && flt.sent_ids.contains(p.id.as_str()) {
            continue;
        }
        let status_ok = match p.status_code {
            0 => (flt.status_mask & 1) != 0,
            1 => (flt.status_mask & 2) != 0,
            2 => (flt.status_mask & 4) != 0,
            _ => false,
        };
        if !status_ok {
            continue;
        }
        let tx = ((p.mx - tile_x0) * n_f * MVT_EXTENT as f64).round() as i32;
        let ty = ((p.my - tile_y0) * n_f * MVT_EXTENT as f64).round() as i32;
        if do_cluster && !(0..MVT_EXTENT).contains(&tx) {
            continue;
        }
        if do_cluster && !(0..MVT_EXTENT).contains(&ty) {
            continue;
        }
        hits.push((idx, tx, ty));
    }

    if hits.is_empty() {
        return Vec::new();
    }

    let emit_labels = flt.show_titles && z >= 13;
    let mut builder = MvtLayerBuilder::with_capacity(hits.len());
    let k_id = builder.intern_key("id");
    let k_icon = builder.intern_key("icon");
    let k_label = if emit_labels { Some(builder.intern_key("label")) } else { None };
    let v_done_icon = builder.intern_str(&cached.done_icon);
    let v_issue_icon = builder.intern_str(&cached.issue_icon);
    let v_pending_icon = builder.intern_str(&cached.pending_icon);

    if do_cluster {
        // Spatial grid clustering inside the tile (~48px radius at 256px tile -> ~768 extent units)
        let cell_units = 768_i32;
        struct ClusterBin {
            count: u32,
            sum_x: i64,
            sum_y: i64,
            single_idx: usize,
            single_tx: i32,
            single_ty: i32,
        }
        let mut bins: HashMap<(i32, i32), ClusterBin> = HashMap::with_capacity(64);
        for &(idx, tx, ty) in &hits {
            let gx = tx.div_euclid(cell_units);
            let gy = ty.div_euclid(cell_units);
            bins.entry((gx, gy))
                .and_modify(|b| {
                    b.count += 1;
                    b.sum_x += tx as i64;
                    b.sum_y += ty as i64;
                })
                .or_insert(ClusterBin {
                    count: 1,
                    sum_x: tx as i64,
                    sum_y: ty as i64,
                    single_idx: idx,
                    single_tx: tx,
                    single_ty: ty,
                });
        }

        let k_pc = builder.intern_key("point_count");
        let k_pca = builder.intern_key("point_count_abbreviated");
        let mut cluster_fid = 1_000_000_u64;

        for bin in bins.into_values() {
            if bin.count == 1 {
                let p = &cached.pins[bin.single_idx];
                let v_id = builder.intern_str(&p.id);
                let v_icon = match p.status_code {
                    0 => v_done_icon,
                    1 => v_issue_icon,
                    _ => v_pending_icon,
                };
                if let Some(kl) = k_label {
                    let v_lbl = builder.intern_str(&p.label_shaped);
                    builder.add_point_feature(
                        bin.single_idx as u64 + 1,
                        bin.single_tx,
                        bin.single_ty,
                        &[(k_id, v_id), (k_icon, v_icon), (kl, v_lbl)],
                    );
                } else {
                    builder.add_point_feature(
                        bin.single_idx as u64 + 1,
                        bin.single_tx,
                        bin.single_ty,
                        &[(k_id, v_id), (k_icon, v_icon)],
                    );
                }
            } else {
                let cx = (bin.sum_x / bin.count as i64) as i32;
                let cy = (bin.sum_y / bin.count as i64) as i32;
                let v_pc = builder.intern_uint(bin.count as u64);
                let abbr = format_cluster_abbrev(bin.count);
                let v_pca = builder.intern_str(&abbr);
                cluster_fid += 1;
                builder.add_point_feature(cluster_fid, cx, cy, &[(k_pc, v_pc), (k_pca, v_pca)]);
            }
        }
        return builder.finish_tile("pins");
    }

    // Unclustered mode: color-fair, Earth-locked tile-pixel deduplication only when tile is super dense below z=15
    let selected_hits: Vec<(usize, i32, i32)> = if z < 15 && hits.len() > 350 {
        let cell_units = if z >= 13 { 144_i32 } else { 192_i32 }; // ~9-12px on 256px tile
        let half = cell_units / 2;
        let mut cells: HashMap<(i32, i32), (usize, i32, i32, i32)> = HashMap::with_capacity(400);
        for &(idx, tx, ty) in &hits {
            let gx = tx.div_euclid(cell_units);
            let gy = ty.div_euclid(cell_units);
            let dx = tx - (gx * cell_units + half);
            let dy = ty - (gy * cell_units + half);
            let d2 = dx * dx + dy * dy;
            cells
                .entry((gx, gy))
                .and_modify(|e| {
                    if d2 < e.3 {
                        *e = (idx, tx, ty, d2);
                    }
                })
                .or_insert((idx, tx, ty, d2));
        }
        cells.into_values().map(|(i, tx, ty, _)| (i, tx, ty)).collect()
    } else {
        hits
    };

    // Stable draw order: Done -> Issue -> Pending
    let mut done = Vec::with_capacity(selected_hits.len() / 2);
    let mut issue = Vec::with_capacity(selected_hits.len() / 4);
    let mut pending = Vec::with_capacity(selected_hits.len() / 4);
    for item in selected_hits {
        match cached.pins[item.0].status_code {
            0 => done.push(item),
            1 => issue.push(item),
            _ => pending.push(item),
        }
    }

    for (group, v_icon) in [(&done, v_done_icon), (&issue, v_issue_icon), (&pending, v_pending_icon)] {
        for &(idx, tx, ty) in group {
            let p = &cached.pins[idx];
            let v_id = builder.intern_str(&p.id);
            if let Some(kl) = k_label {
                let v_lbl = builder.intern_str(&p.label_shaped);
                builder.add_point_feature(
                    idx as u64 + 1,
                    tx,
                    ty,
                    &[(k_id, v_id), (k_icon, v_icon), (kl, v_lbl)],
                );
            } else {
                builder.add_point_feature(
                    idx as u64 + 1,
                    tx,
                    ty,
                    &[(k_id, v_id), (k_icon, v_icon)],
                );
            }
        }
    }

    builder.finish_tile("pins")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_str(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
        buf.extend_from_slice(s.as_bytes());
    }

    fn read_varint(buf: &[u8], pos: &mut usize) -> Option<u64> {
        let mut out = 0u64;
        let mut shift = 0u32;
        while *pos < buf.len() {
            let b = buf[*pos];
            *pos += 1;
            out |= ((b & 0x7F) as u64) << shift;
            if (b & 0x80) == 0 {
                return Some(out);
            }
            shift += 7;
        }
        None
    }

    #[test]
    fn encodes_valid_mvt_v2_tile_for_akre_unclustered_and_clustered() {
        let mut load = Vec::new();
        put_str(&mut load, "pin-done");
        put_str(&mut load, "pin-issue");
        put_str(&mut load, "pin-pending");
        load.extend_from_slice(&3u32.to_le_bytes());

        // 3 pins in Akre (36.74, 43.89)
        for (i, st) in [(0, 0u8), (1, 1u8), (2, 2u8)] {
            put_str(&mut load, &format!("p{i}"));
            put_str(&mut load, "layerA");
            put_str(&mut load, "ئاکرێ");
            load.push(st);
            load.extend_from_slice(&(43.89_f64 + i as f64 * 0.0001).to_le_bytes());
            load.extend_from_slice(&(36.74_f64 + i as f64 * 0.0001).to_le_bytes());
        }
        assert!(crate::geojson::load_geo_pins(1, &load));

        // Tile covering Akre at z=14
        let (mx, my) = crate::geojson::lng_lat_to_mercator_unit(43.89, 36.74);
        let z = 14;
        let n = (1 << z) as f64;
        let tx = (mx * n).floor() as i32;
        let ty = (my * n).floor() as i32;

        // Unclustered mode
        let mut flt = vec![0b111u8, 0u8, 1u8];
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.extend_from_slice(&0u32.to_le_bytes());
        assert!(set_mvt_filter(&flt));

        let mvt = render_pins_mvt_tile(z, tx, ty);
        assert!(!mvt.is_empty());

        // Strict protobuf structural decode of Tile -> Layer
        let mut p = 0;
        let tile_tag = read_varint(&mvt, &mut p).unwrap();
        assert_eq!(tile_tag, 0x1A); // field 3, length-delimited
        let layer_len = read_varint(&mvt, &mut p).unwrap() as usize;
        assert_eq!(p + layer_len, mvt.len());
        let layer = &mvt[p..];

        let mut lp = 0;
        let mut version = 0u64;
        let mut name = String::new();
        let mut feat_count = 0usize;
        let mut extent = 0u64;
        while lp < layer.len() {
            let tag = read_varint(layer, &mut lp).unwrap();
            let field = tag >> 3;
            let wt = tag & 7;
            match (field, wt) {
                (15, 0) => version = read_varint(layer, &mut lp).unwrap(),
                (1, 2) => {
                    let l = read_varint(layer, &mut lp).unwrap() as usize;
                    name = std::str::from_utf8(&layer[lp..lp + l]).unwrap().to_owned();
                    lp += l;
                }
                (2, 2) => {
                    let l = read_varint(layer, &mut lp).unwrap() as usize;
                    feat_count += 1;
                    lp += l;
                }
                (5, 0) => extent = read_varint(layer, &mut lp).unwrap(),
                (_, 2) => {
                    let l = read_varint(layer, &mut lp).unwrap() as usize;
                    lp += l;
                }
                (_, 0) => {
                    let _ = read_varint(layer, &mut lp).unwrap();
                }
                _ => panic!("unexpected wire type {wt} for field {field}"),
            }
        }
        assert_eq!(version, 2);
        assert_eq!(name, "pins");
        assert_eq!(extent, 4096);
        assert_eq!(feat_count, 3);

        // Clustered mode should collapse the 3 adjacent pins into 1 cluster feature
        let mut flt_c = vec![0b111u8, 1u8, 1u8];
        flt_c.extend_from_slice(&0u32.to_le_bytes());
        flt_c.extend_from_slice(&0u32.to_le_bytes());
        assert!(set_mvt_filter(&flt_c));
        let mvt_c = render_pins_mvt_tile(z, tx, ty);
        assert!(!mvt_c.is_empty());
        assert!(mvt_c.len() < mvt.len());

        // Empty ocean tile must return 0 bytes
        assert!(render_pins_mvt_tile(14, 0, 0).is_empty());

        crate::geojson::clear_geo_pins();
    }
}
