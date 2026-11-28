use flate2::read::GzDecoder;
use std::cell::RefCell;
use std::cmp::Ordering;
use std::collections::BinaryHeap;
use std::fs::File;
use std::io::{BufReader, Read};
use std::sync::{Arc, Mutex, OnceLock};

const MAGIC: i32 = 0x5453_5231; // TSR1
const FLAG_CAR: u8 = 1;
const FLAG_BIKE: u8 = 2;
const FLAG_WALK: u8 = 4;

const MAX_CACHE_GRAPHS: usize = 1;
const MAX_NODES: usize = 20_000_000;
const MAX_EDGES: usize = 60_000_000;
const MAX_NAMES: usize = 10_000_000;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum TravelMode {
    Car = 0,
    Bicycle = 1,
    Walking = 2,
}

impl TravelMode {
    pub fn from_code(code: i32) -> Option<Self> {
        match code {
            0 => Some(Self::Car),
            1 => Some(Self::Bicycle),
            2 => Some(Self::Walking),
            _ => None,
        }
    }

    fn flag(self) -> u8 {
        match self {
            Self::Car => FLAG_CAR,
            Self::Bicycle => FLAG_BIKE,
            Self::Walking => FLAG_WALK,
        }
    }

    fn max_speed_mps(self) -> f64 {
        match self {
            Self::Car => 100.0 / 3.6,
            Self::Bicycle => 22.0 / 3.6,
            Self::Walking => 5.5 / 3.6,
        }
    }

    fn approach_speed_mps(self) -> f64 {
        match self {
            Self::Car => 25.0 / 3.6,
            Self::Bicycle => 14.0 / 3.6,
            Self::Walking => 4.8 / 3.6,
        }
    }

    fn edge_speed_kmh(self, raw_speed: f32) -> f64 {
        match self {
            Self::Car => (raw_speed as f64).max(10.0),
            Self::Bicycle => 16.0,
            Self::Walking => 4.8,
        }
    }
}

struct SpatialNodeGrid {
    min_lat: f64,
    min_lng: f64,
    cell_lat: f64,
    cell_lng: f64,
    rows: usize,
    cols: usize,
    head: Vec<i32>,
    next: Vec<i32>,
}

pub struct RoadGraph {
    lats: Vec<f32>,
    lngs: Vec<f32>,
    adj_head: Vec<i32>,
    edge_to: Vec<i32>,
    edge_next: Vec<i32>,
    edge_dist: Vec<f32>,
    edge_speed: Vec<f32>,
    edge_flags: Vec<u8>,
    edge_name_idx: Vec<i32>,
    node_out_flags: Vec<u8>,
    node_in_flags: Vec<u8>,
    names: Vec<String>,
    spatial_grid: SpatialNodeGrid,
}

#[derive(Debug, Clone)]
pub struct NativeNavStep {
    pub instruction: String,
    pub arrow: String,
    pub dist_m: f64,
    pub lat: f64,
    pub lng: f64,
    pub cum_dist_m: f64,
}

#[derive(Debug, Clone)]
pub struct NativeRoute {
    pub geom: Vec<(f64, f64)>, // (lng, lat)
    pub total_dist: f64,
    pub total_dur: f64,
    pub steps: Vec<NativeNavStep>,
}

struct CacheEntry {
    path: String,
    last_modified: i64,
    file_size: i64,
    graph: Arc<RoadGraph>,
}

static GRAPH_CACHE: OnceLock<Mutex<Vec<CacheEntry>>> = OnceLock::new();

fn graph_cache() -> &'static Mutex<Vec<CacheEntry>> {
    GRAPH_CACHE.get_or_init(|| Mutex::new(Vec::with_capacity(MAX_CACHE_GRAPHS)))
}

pub fn evict_graph(path: &str) {
    if let Ok(mut cache) = graph_cache().lock() {
        cache.retain(|e| e.path != path);
    }
}

pub fn trim_graph_cache(_aggressive: bool) -> usize {
    if let Ok(mut cache) = graph_cache().lock() {
        let n = cache.len();
        cache.clear();
        cache.shrink_to_fit();
        return n;
    }
    0
}

#[derive(Default)]
struct AStarScratch {
    epoch: u32,
    seen_epoch: Vec<u32>,
    closed_epoch: Vec<u32>,
    g_score: Vec<f64>,
    prev_node: Vec<i32>,
    prev_edge: Vec<i32>,
}

impl AStarScratch {
    fn prepare(&mut self, n: usize) -> u32 {
        if self.seen_epoch.len() < n {
            let cap = n.max(256);
            self.seen_epoch.resize(cap, 0);
            self.closed_epoch.resize(cap, 0);
            self.g_score.resize(cap, 0.0);
            self.prev_node.resize(cap, -1);
            self.prev_edge.resize(cap, -1);
            self.seen_epoch.fill(0);
            self.closed_epoch.fill(0);
            self.epoch = 1;
        } else if self.epoch >= u32::MAX - 4 {
            self.seen_epoch.fill(0);
            self.closed_epoch.fill(0);
            self.epoch = 1;
        } else {
            self.epoch += 1;
        }
        self.epoch
    }
}

thread_local! {
    static ASTAR_SCRATCH: RefCell<AStarScratch> = RefCell::new(AStarScratch::default());
}

#[derive(Copy, Clone)]
struct QNode {
    u: usize,
    f: f64,
}

impl PartialEq for QNode {
    fn eq(&self, other: &Self) -> bool {
        self.u == other.u && self.f.to_bits() == other.f.to_bits()
    }
}
impl Eq for QNode {}
impl PartialOrd for QNode {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for QNode {
    fn cmp(&self, other: &Self) -> Ordering {
        other.f.total_cmp(&self.f).then_with(|| other.u.cmp(&self.u))
    }
}

fn read_u8<R: Read>(r: &mut R) -> std::io::Result<u8> {
    let mut b = [0u8; 1];
    r.read_exact(&mut b)?;
    Ok(b[0])
}

fn read_u16_be<R: Read>(r: &mut R) -> std::io::Result<u16> {
    let mut b = [0u8; 2];
    r.read_exact(&mut b)?;
    Ok(u16::from_be_bytes(b))
}

fn read_i32_be<R: Read>(r: &mut R) -> std::io::Result<i32> {
    let mut b = [0u8; 4];
    r.read_exact(&mut b)?;
    Ok(i32::from_be_bytes(b))
}

fn read_i64_be<R: Read>(r: &mut R) -> std::io::Result<i64> {
    let mut b = [0u8; 8];
    r.read_exact(&mut b)?;
    Ok(i64::from_be_bytes(b))
}

fn read_f32_be<R: Read>(r: &mut R) -> std::io::Result<f32> {
    let mut b = [0u8; 4];
    r.read_exact(&mut b)?;
    Ok(f32::from_be_bytes(b))
}

fn read_f64_be<R: Read>(r: &mut R) -> std::io::Result<f64> {
    let mut b = [0u8; 8];
    r.read_exact(&mut b)?;
    Ok(f64::from_be_bytes(b))
}

/// Decodes Java DataInputStream.readUTF() (Modified UTF-8).
fn read_java_utf<R: Read>(r: &mut R) -> std::io::Result<String> {
    let len = read_u16_be(r)? as usize;
    if len == 0 {
        return Ok(String::new());
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf)?;
    if let Ok(s) = std::str::from_utf8(&buf) {
        return Ok(s.to_string());
    }
    // Fallback for Java MUTF-8 (0xC0 0x80 null or 6-byte surrogate pairs)
    let mut units: Vec<u16> = Vec::with_capacity(len);
    let mut i = 0;
    while i < buf.len() {
        let b0 = buf[i];
        if b0 & 0x80 == 0 {
            units.push(b0 as u16);
            i += 1;
        } else if (b0 & 0xE0) == 0xC0 && i + 1 < buf.len() {
            let b1 = buf[i + 1];
            let u = (((b0 & 0x1F) as u16) << 6) | ((b1 & 0x3F) as u16);
            units.push(u);
            i += 2;
        } else if (b0 & 0xF0) == 0xE0 && i + 2 < buf.len() {
            let b1 = buf[i + 1];
            let b2 = buf[i + 2];
            let u = (((b0 & 0x0F) as u16) << 12)
                | (((b1 & 0x3F) as u16) << 6)
                | ((b2 & 0x3F) as u16);
            units.push(u);
            i += 3;
        } else {
            units.push(0xFFFD);
            i += 1;
        }
    }
    Ok(String::from_utf16_lossy(&units))
}

fn build_spatial_grid(lats: &[f32], lngs: &[f32]) -> SpatialNodeGrid {
    let n = lats.len();
    if n == 0 {
        return SpatialNodeGrid {
            min_lat: 0.0,
            min_lng: 0.0,
            cell_lat: 0.01,
            cell_lng: 0.01,
            rows: 1,
            cols: 1,
            head: vec![-1],
            next: Vec::new(),
        };
    }
    let mut min_lat = f64::INFINITY;
    let mut max_lat = f64::NEG_INFINITY;
    let mut min_lng = f64::INFINITY;
    let mut max_lng = f64::NEG_INFINITY;
    for i in 0..n {
        let la = lats[i] as f64;
        let lo = lngs[i] as f64;
        if la < min_lat { min_lat = la; }
        if la > max_lat { max_lat = la; }
        if lo < min_lng { min_lng = lo; }
        if lo > max_lng { max_lng = lo; }
    }
    let lat_span = (max_lat - min_lat).max(1e-4);
    let lng_span = (max_lng - min_lng).max(1e-4);
    let rows = ((lat_span / 0.01).ceil() as usize).clamp(1, 512);
    let cols = ((lng_span / 0.01).ceil() as usize).clamp(1, 512);
    let cell_lat = lat_span / rows as f64;
    let cell_lng = lng_span / cols as f64;
    let mut head = vec![-1i32; rows * cols];
    let mut next = vec![-1i32; n];

    let max_r = (rows - 1) as isize;
    let max_c = (cols - 1) as isize;
    for i in 0..n {
        let r = (((lats[i] as f64 - min_lat) / cell_lat) as isize).clamp(0, max_r) as usize;
        let c = (((lngs[i] as f64 - min_lng) / cell_lng) as isize).clamp(0, max_c) as usize;
        let cell_idx = r * cols + c;
        next[i] = head[cell_idx];
        head[cell_idx] = i as i32;
    }

    SpatialNodeGrid {
        min_lat,
        min_lng,
        cell_lat,
        cell_lng,
        rows,
        cols,
        head,
        next,
    }
}

pub fn load_tsr_graph(path: &str) -> Option<RoadGraph> {
    let file = File::open(path).ok()?;
    let gz = GzDecoder::new(BufReader::with_capacity(64 * 1024, file));
    let mut r = BufReader::with_capacity(64 * 1024, gz);

    let magic = read_i32_be(&mut r).ok()?;
    if magic != MAGIC {
        return None;
    }
    let _region_name = read_java_utf(&mut r).ok()?;
    let _min_lat = read_f64_be(&mut r).ok()?;
    let _min_lng = read_f64_be(&mut r).ok()?;
    let _max_lat = read_f64_be(&mut r).ok()?;
    let _max_lng = read_f64_be(&mut r).ok()?;
    let _downloaded_at = read_i64_be(&mut r).ok()?;

    let node_count_i32 = read_i32_be(&mut r).ok()?;
    let edge_count_i32 = read_i32_be(&mut r).ok()?;
    let name_count_i32 = read_i32_be(&mut r).ok()?;
    if node_count_i32 < 0 || edge_count_i32 < 0 || name_count_i32 < 0 {
        return None;
    }
    let node_count = node_count_i32 as usize;
    let edge_count = edge_count_i32 as usize;
    let name_count = name_count_i32 as usize;
    if node_count > MAX_NODES || edge_count > MAX_EDGES || name_count > MAX_NAMES {
        return None;
    }

    let mut names = Vec::with_capacity(name_count);
    for _ in 0..name_count {
        names.push(read_java_utf(&mut r).ok()?);
    }

    let mut lats = Vec::with_capacity(node_count);
    let mut lngs = Vec::with_capacity(node_count);
    for _ in 0..node_count {
        lats.push(read_f32_be(&mut r).ok()?);
        lngs.push(read_f32_be(&mut r).ok()?);
    }

    let mut adj_head = vec![-1i32; node_count];
    let mut edge_to = vec![0i32; edge_count];
    let mut edge_next = vec![-1i32; edge_count];
    let mut edge_dist = vec![0f32; edge_count];
    let mut edge_speed = vec![0f32; edge_count];
    let mut edge_flags = vec![0u8; edge_count];
    let mut edge_name_idx = vec![0i32; edge_count];
    let mut node_out_flags = vec![0u8; node_count];
    let mut node_in_flags = vec![0u8; node_count];

    for e in 0..edge_count {
        let from = read_i32_be(&mut r).ok()?;
        let to = read_i32_be(&mut r).ok()?;
        let dist = read_f32_be(&mut r).ok()?;
        let spd = read_f32_be(&mut r).ok()?;
        let flg = read_u8(&mut r).ok()?;
        let n_idx = read_i32_be(&mut r).ok()?;

        if from < 0 || to < 0 || (from as usize) >= node_count || (to as usize) >= node_count {
            return None;
        }
        let from_u = from as usize;
        let to_u = to as usize;

        edge_to[e] = to;
        edge_dist[e] = dist;
        edge_speed[e] = spd;
        edge_flags[e] = flg;
        edge_name_idx[e] = n_idx;
        edge_next[e] = adj_head[from_u];
        adj_head[from_u] = e as i32;
        node_out_flags[from_u] |= flg;
        node_in_flags[to_u] |= flg;
    }

    let spatial_grid = build_spatial_grid(&lats, &lngs);
    Some(RoadGraph {
        lats,
        lngs,
        adj_head,
        edge_to,
        edge_next,
        edge_dist,
        edge_speed,
        edge_flags,
        edge_name_idx,
        node_out_flags,
        node_in_flags,
        names,
        spatial_grid,
    })
}

fn get_or_load_graph(path: &str, last_modified: i64, file_size: i64) -> Option<Arc<RoadGraph>> {
    if let Ok(mut cache) = graph_cache().lock() {
        if let Some(pos) = cache.iter().position(|e| {
            e.path == path && e.last_modified == last_modified && e.file_size == file_size
        }) {
            let entry = cache.remove(pos);
            let arc = Arc::clone(&entry.graph);
            cache.push(entry); // mark MRU
            return Some(arc);
        }
    }

    let loaded = Arc::new(load_tsr_graph(path)?);

    if let Ok(mut cache) = graph_cache().lock() {
        cache.retain(|e| e.path != path);
        while cache.len() >= MAX_CACHE_GRAPHS {
            cache.remove(0); // evict LRU
        }
        cache.push(CacheEntry {
            path: path.to_string(),
            last_modified,
            file_size,
            graph: Arc::clone(&loaded),
        });
    }

    Some(loaded)
}

pub fn haversine_meters(lat1: f64, lng1: f64, lat2: f64, lng2: f64) -> f64 {
    const R: f64 = 6_371_000.0;
    let d_lat = (lat2 - lat1).to_radians();
    let d_lng = (lng2 - lng1).to_radians();
    let a = (d_lat * 0.5).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lng * 0.5).sin().powi(2);
    2.0 * R * a.sqrt().atan2((1.0 - a).max(0.0).sqrt())
}

fn bearing_deg(lat1: f64, lng1: f64, lat2: f64, lng2: f64) -> f64 {
    let phi1 = lat1.to_radians();
    let phi2 = lat2.to_radians();
    let d_lam = (lng2 - lng1).to_radians();
    let y = d_lam.sin() * phi2.cos();
    let x = phi1.cos() * phi2.sin() - phi1.sin() * phi2.cos() * d_lam.cos();
    (y.atan2(x).to_degrees() + 360.0).rem_euclid(360.0)
}

fn find_nearest_nodes(
    g: &RoadGraph,
    lat: f64,
    lng: f64,
    mode_flag: u8,
    need_incoming: bool,
    max_count: usize,
) -> Vec<usize> {
    if g.lats.is_empty() || max_count == 0 {
        return Vec::new();
    }
    let max_dist_sq = 3500.0 * 3500.0;
    let m_per_deg_lng = 111_320.0 * lat.to_radians().cos();
    let flags_arr = if need_incoming {
        &g.node_in_flags
    } else {
        &g.node_out_flags
    };

    struct TopK {
        best_idx: Vec<i32>,
        best_dist_sq: Vec<f64>,
        found_count: usize,
        worst_pos: usize,
        max_count: usize,
    }

    impl TopK {
        #[inline]
        fn consider(
            &mut self,
            i: usize,
            g: &RoadGraph,
            flags_arr: &[u8],
            mode_flag: u8,
            lat: f64,
            lng: f64,
            m_per_deg_lng: f64,
        ) {
            if (flags_arr[i] & mode_flag) == 0 {
                return;
            }
            let d_lat = (g.lats[i] as f64 - lat) * 111_132.0;
            let d_lat_sq = d_lat * d_lat;
            let limit = self.best_dist_sq[self.worst_pos];
            if d_lat_sq >= limit {
                return;
            }
            let d_lng = (g.lngs[i] as f64 - lng) * m_per_deg_lng;
            let d_sq = d_lat_sq + d_lng * d_lng;
            if d_sq < limit {
                if self.best_idx[self.worst_pos] < 0 {
                    self.found_count += 1;
                }
                self.best_idx[self.worst_pos] = i as i32;
                self.best_dist_sq[self.worst_pos] = d_sq;
                let mut max_d = self.best_dist_sq[0];
                let mut max_p = 0;
                for k in 1..self.max_count {
                    if self.best_dist_sq[k] > max_d {
                        max_d = self.best_dist_sq[k];
                        max_p = k;
                    }
                }
                self.worst_pos = max_p;
            }
        }
    }

    let mut top = TopK {
        best_idx: vec![-1i32; max_count],
        best_dist_sq: vec![max_dist_sq; max_count],
        found_count: 0,
        worst_pos: 0,
        max_count,
    };

    let grid = &g.spatial_grid;
    let t_r = (((lat - grid.min_lat) / grid.cell_lat) as isize)
        .clamp(0, (grid.rows - 1) as isize) as usize;
    let t_c = (((lng - grid.min_lng) / grid.cell_lng) as isize)
        .clamp(0, (grid.cols - 1) as isize) as usize;
    let max_ring = t_r
        .max(grid.rows - 1 - t_r)
        .max(t_c)
        .max(grid.cols - 1 - t_c);

    for ring in 0..=max_ring {
        let r_min = t_r.saturating_sub(ring);
        let r_max = (t_r + ring).min(grid.rows - 1);
        let c_min = t_c.saturating_sub(ring);
        let c_max = (t_c + ring).min(grid.cols - 1);

        for r in r_min..=r_max {
            if ring == 0 || r == r_min || r == r_max {
                for c in c_min..=c_max {
                    let mut idx = grid.head[r * grid.cols + c];
                    while idx != -1 {
                        let iu = idx as usize;
                        top.consider(iu, g, flags_arr, mode_flag, lat, lng, m_per_deg_lng);
                        idx = grid.next[iu];
                    }
                }
            } else {
                let cols_to_scan: [usize; 2] = [c_min, c_max];
                let count = if c_max == c_min { 1 } else { 2 };
                for &c in &cols_to_scan[..count] {
                    let mut idx = grid.head[r * grid.cols + c];
                    while idx != -1 {
                        let iu = idx as usize;
                        top.consider(iu, g, flags_arr, mode_flag, lat, lng, m_per_deg_lng);
                        idx = grid.next[iu];
                    }
                }
            }
        }

        let d_s = if t_r > ring {
            (lat - (grid.min_lat + (t_r - ring) as f64 * grid.cell_lat)).max(0.0) * 111_132.0
        } else {
            f64::INFINITY
        };
        let d_n = if t_r + ring < grid.rows - 1 {
            ((grid.min_lat + (t_r + ring + 1) as f64 * grid.cell_lat) - lat).max(0.0) * 111_132.0
        } else {
            f64::INFINITY
        };
        let d_w = if t_c > ring {
            (lng - (grid.min_lng + (t_c - ring) as f64 * grid.cell_lng)).max(0.0) * m_per_deg_lng
        } else {
            f64::INFINITY
        };
        let d_e = if t_c + ring < grid.cols - 1 {
            ((grid.min_lng + (t_c + ring + 1) as f64 * grid.cell_lng) - lng).max(0.0)
                * m_per_deg_lng
        } else {
            f64::INFINITY
        };
        let min_out = d_s.min(d_n).min(d_w).min(d_e);
        if min_out.is_infinite() {
            break;
        }
        let min_out_sq = min_out * min_out;
        if min_out_sq >= max_dist_sq {
            break;
        }
        if top.found_count >= max_count && min_out_sq >= top.best_dist_sq[top.worst_pos] {
            break;
        }
    }

    let mut matched: Vec<(usize, f64)> = (0..max_count)
        .filter(|&k| top.best_idx[k] >= 0)
        .map(|k| (top.best_idx[k] as usize, top.best_dist_sq[k]))
        .collect();
    matched.sort_by(|a, b| a.1.total_cmp(&b.1));
    matched.into_iter().map(|(idx, _)| idx).collect()
}

fn road_name(g: &RoadGraph, edge_idx: usize) -> &str {
    let idx = g.edge_name_idx[edge_idx];
    if idx >= 0 && (idx as usize) < g.names.len() {
        &g.names[idx as usize]
    } else {
        ""
    }
}

fn build_offline_steps(
    g: &RoadGraph,
    nodes: &[usize],
    edges: &[usize],
    cum_dists: &[f64],
    total_dist: f64,
    end_lat: f64,
    end_lng: f64,
) -> Vec<NativeNavStep> {
    let mut steps = Vec::new();
    if edges.is_empty() {
        steps.push(NativeNavStep {
            instruction: "Arrive at destination".to_string(),
            arrow: "🏁".to_string(),
            dist_m: total_dist,
            lat: end_lat,
            lng: end_lng,
            cum_dist_m: total_dist,
        });
        return steps;
    }

    let first_road = road_name(g, edges[0]);
    let onto_first = if !first_road.trim().is_empty() {
        format!(" onto {}", first_road)
    } else {
        String::new()
    };
    steps.push(NativeNavStep {
        instruction: format!("Head out{}", onto_first),
        arrow: "↑".to_string(),
        dist_m: 0.0,
        lat: g.lats[nodes[0]] as f64,
        lng: g.lngs[nodes[0]] as f64,
        cum_dist_m: 0.0,
    });

    let mut last_step_dist = 0.0;
    if nodes.len() > 2 {
        for i in 1..(nodes.len() - 1) {
            let dist_here = cum_dists[i];
            if dist_here - last_step_dist < 30.0 {
                continue;
            }

            let mut back_idx = i - 1;
            while back_idx > 0 && (dist_here - cum_dists[back_idx]) < 20.0 {
                back_idx -= 1;
            }
            let mut fwd_idx = i + 1;
            while fwd_idx < nodes.len() - 1 && (cum_dists[fwd_idx] - dist_here) < 20.0 {
                fwd_idx += 1;
            }

            let b_node = nodes[back_idx];
            let c_node = nodes[i];
            let f_node = nodes[fwd_idx];
            let b1 = bearing_deg(
                g.lats[b_node] as f64,
                g.lngs[b_node] as f64,
                g.lats[c_node] as f64,
                g.lngs[c_node] as f64,
            );
            let b2 = bearing_deg(
                g.lats[c_node] as f64,
                g.lngs[c_node] as f64,
                g.lats[f_node] as f64,
                g.lngs[f_node] as f64,
            );
            let delta = (b2 - b1 + 540.0).rem_euclid(360.0) - 180.0;

            let prev_road = road_name(g, edges[i - 1]);
            let next_road = road_name(g, edges[i]);
            let road_changed = !next_road.trim().is_empty() && next_road != prev_road;

            if delta.abs() >= 40.0 || road_changed {
                let onto = if !next_road.trim().is_empty() {
                    format!(" onto {}", next_road)
                } else {
                    String::new()
                };
                let (arrow, prefix) = if delta > 125.0 {
                    ("↘", "Sharp right")
                } else if delta > 45.0 {
                    ("↱", "Turn right")
                } else if delta > 25.0 {
                    ("↗", "Keep right")
                } else if delta < -125.0 {
                    ("↙", "Sharp left")
                } else if delta < -45.0 {
                    ("↰", "Turn left")
                } else if delta < -25.0 {
                    ("↖", "Keep left")
                } else {
                    ("↑", "Continue")
                };
                steps.push(NativeNavStep {
                    instruction: format!("{}{}", prefix, onto),
                    arrow: arrow.to_string(),
                    dist_m: dist_here - last_step_dist,
                    lat: g.lats[c_node] as f64,
                    lng: g.lngs[c_node] as f64,
                    cum_dist_m: dist_here,
                });
                last_step_dist = dist_here;
            }
        }
    }

    steps.push(NativeNavStep {
        instruction: "Arrive at destination".to_string(),
        arrow: "🏁".to_string(),
        dist_m: (total_dist - last_step_dist).max(0.0),
        lat: end_lat,
        lng: end_lng,
        cum_dist_m: total_dist,
    });
    steps
}

fn run_a_star_between_nodes(
    g: &RoadGraph,
    start_node: usize,
    end_node: usize,
    start_lat: f64,
    start_lng: f64,
    end_lat: f64,
    end_lng: f64,
    mode: TravelMode,
) -> Result<NativeRoute, &'static str> {
    if start_node == end_node {
        let geom = vec![(start_lng, start_lat), (end_lng, end_lat)];
        let d = haversine_meters(start_lat, start_lng, end_lat, end_lng);
        let steps = vec![NativeNavStep {
            instruction: "Arrive at destination".to_string(),
            arrow: "🏁".to_string(),
            dist_m: d,
            lat: end_lat,
            lng: end_lng,
            cum_dist_m: d,
        }];
        return Ok(NativeRoute {
            geom,
            total_dist: d,
            total_dur: d / 5.0,
            steps,
        });
    }

    let n = g.lats.len();
    let mode_flag = mode.flag();
    let inv_max_speed = 0.985 / mode.max_speed_mps();

    let end_lat_d = g.lats[end_node] as f64;
    let end_lng_d = g.lngs[end_node] as f64;
    let m_per_deg_lng = 111_320.0 * ((start_lat + end_lat_d) * 0.5).to_radians().cos();

    let fast_heuristic_sec = |lat: f32, lng: f32| -> f64 {
        let dy = (lat as f64 - end_lat_d) * 111_132.0;
        let dx = (lng as f64 - end_lng_d) * m_per_deg_lng;
        (dx * dx + dy * dy).sqrt() * inv_max_speed
    };

    let (path_nodes, path_edges) = ASTAR_SCRATCH.with(|cell| {
        let mut scratch = cell.borrow_mut();
        let ep = scratch.prepare(n);
        let AStarScratch {
            seen_epoch,
            closed_epoch,
            g_score,
            prev_node,
            prev_edge,
            ..
        } = &mut *scratch;

        let mut pq = BinaryHeap::with_capacity(256);
        seen_epoch[start_node] = ep;
        g_score[start_node] = 0.0;
        prev_node[start_node] = -1;
        prev_edge[start_node] = -1;
        pq.push(QNode {
            u: start_node,
            f: fast_heuristic_sec(g.lats[start_node], g.lngs[start_node]),
        });

        let mut found = false;
        while let Some(cur) = pq.pop() {
            let u = cur.u;
            if closed_epoch[u] == ep {
                continue;
            }
            closed_epoch[u] = ep;
            if u == end_node {
                found = true;
                break;
            }
            let mut e = g.adj_head[u];
            while e != -1 {
                let eu = e as usize;
                if (g.edge_flags[eu] & mode_flag) != 0 {
                    let v = g.edge_to[eu] as usize;
                    if closed_epoch[v] != ep {
                        let spd_kmh = mode.edge_speed_kmh(g.edge_speed[eu]);
                        let cost_sec = g.edge_dist[eu] as f64 / (spd_kmh / 3.6);
                        let tentative = g_score[u] + cost_sec;
                        let v_g = if seen_epoch[v] == ep {
                            g_score[v]
                        } else {
                            f64::INFINITY
                        };
                        if tentative < v_g {
                            seen_epoch[v] = ep;
                            g_score[v] = tentative;
                            prev_node[v] = u as i32;
                            prev_edge[v] = e;
                            pq.push(QNode {
                                u: v,
                                f: tentative + fast_heuristic_sec(g.lats[v], g.lngs[v]),
                            });
                        }
                    }
                }
                e = g.edge_next[eu];
            }
        }

        if !found {
            return Err("No offline road connection found between points");
        }

        let mut p_nodes = Vec::new();
        let mut p_edges = Vec::new();
        let mut cur = end_node as i32;
        while cur != -1 {
            let cu = cur as usize;
            p_nodes.push(cu);
            let pe = prev_edge[cu];
            if pe != -1 {
                p_edges.push(pe as usize);
            }
            cur = prev_node[cu];
        }
        p_nodes.reverse();
        p_edges.reverse();
        Ok((p_nodes, p_edges))
    })?;

    let mut geom = Vec::with_capacity(path_nodes.len() + 2);
    geom.push((start_lng, start_lat));
    for &idx in &path_nodes {
        geom.push((g.lngs[idx] as f64, g.lats[idx] as f64));
    }
    geom.push((end_lng, end_lat));

    let approach_speed_mps = mode.approach_speed_mps();
    let first_node = path_nodes[0];
    let last_node = *path_nodes.last().unwrap();
    let start_approach = haversine_meters(
        start_lat,
        start_lng,
        g.lats[first_node] as f64,
        g.lngs[first_node] as f64,
    );
    let end_approach = haversine_meters(
        g.lats[last_node] as f64,
        g.lngs[last_node] as f64,
        end_lat,
        end_lng,
    );

    let mut total_dist = start_approach;
    let mut total_dur = start_approach / approach_speed_mps;
    let mut cum_dists = vec![0.0; path_nodes.len()];
    cum_dists[0] = start_approach;

    for (i, &eu) in path_edges.iter().enumerate() {
        let d = g.edge_dist[eu] as f64;
        let spd_kmh = mode.edge_speed_kmh(g.edge_speed[eu]);
        total_dist += d;
        total_dur += d / (spd_kmh / 3.6);
        cum_dists[i + 1] = total_dist;
    }
    total_dist += end_approach;
    total_dur += end_approach / approach_speed_mps;

    let steps = build_offline_steps(g, &path_nodes, &path_edges, &cum_dists, total_dist, end_lat, end_lng);

    Ok(NativeRoute {
        geom,
        total_dist,
        total_dur,
        steps,
    })
}

pub fn compute_route(
    g: &RoadGraph,
    start_lat: f64,
    start_lng: f64,
    end_lat: f64,
    end_lng: f64,
    mode: TravelMode,
) -> Result<NativeRoute, &'static str> {
    let mode_flag = mode.flag();
    let start_candidates = find_nearest_nodes(g, start_lat, start_lng, mode_flag, false, 3);
    let end_candidates = find_nearest_nodes(g, end_lat, end_lng, mode_flag, true, 3);
    if start_candidates.is_empty() || end_candidates.is_empty() {
        return Err("No nearby road in offline map");
    }

    let mut pairs = Vec::with_capacity(start_candidates.len() * end_candidates.len());
    pairs.push((start_candidates[0], end_candidates[0]));
    for &s in &start_candidates {
        for &e in &end_candidates {
            if s == start_candidates[0] && e == end_candidates[0] {
                continue;
            }
            pairs.push((s, e));
        }
    }

    let mut last_err = "No offline road connection found between points";
    for (s_node, e_node) in pairs {
        match run_a_star_between_nodes(g, s_node, e_node, start_lat, start_lng, end_lat, end_lng, mode) {
            Ok(route) => return Ok(route),
            Err(err) => last_err = err,
        }
    }
    Err(last_err)
}

pub fn route_from_tsr(
    path: &str,
    last_modified: i64,
    file_size: i64,
    start_lat: f64,
    start_lng: f64,
    end_lat: f64,
    end_lng: f64,
    mode: TravelMode,
) -> Option<Result<NativeRoute, &'static str>> {
    let g = get_or_load_graph(path, last_modified, file_size)?;
    Some(compute_route(&g, start_lat, start_lng, end_lat, end_lng, mode))
}

fn write_str(out: &mut Vec<u8>, s: &str) {
    let b = s.as_bytes();
    out.extend_from_slice(&(b.len() as u32).to_le_bytes());
    out.extend_from_slice(b);
}

pub fn encode_route_result(res: &Result<NativeRoute, &str>) -> Vec<u8> {
    match res {
        Err(msg) => {
            let mut out = Vec::with_capacity(5 + msg.len());
            out.push(0u8);
            write_str(&mut out, msg);
            out
        }
        Ok(route) => {
            let mut out = Vec::with_capacity(
                32 + route.geom.len() * 16 + route.steps.len() * 64,
            );
            out.push(1u8);
            out.extend_from_slice(&route.total_dist.to_le_bytes());
            out.extend_from_slice(&route.total_dur.to_le_bytes());
            out.extend_from_slice(&(route.geom.len() as u32).to_le_bytes());
            for &(lng, lat) in &route.geom {
                out.extend_from_slice(&lng.to_le_bytes());
                out.extend_from_slice(&lat.to_le_bytes());
            }
            out.extend_from_slice(&(route.steps.len() as u32).to_le_bytes());
            for st in &route.steps {
                write_str(&mut out, &st.instruction);
                write_str(&mut out, &st.arrow);
                out.extend_from_slice(&st.dist_m.to_le_bytes());
                out.extend_from_slice(&st.lat.to_le_bytes());
                out.extend_from_slice(&st.lng.to_le_bytes());
                out.extend_from_slice(&st.cum_dist_m.to_le_bytes());
            }
            out
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Write;

    fn write_java_utf<W: Write>(w: &mut W, s: &str) {
        let b = s.as_bytes();
        w.write_all(&(b.len() as u16).to_be_bytes()).unwrap();
        w.write_all(b).unwrap();
    }

    fn write_sample_tsr(path: &std::path::Path) {
        // Simple L-shaped road network near Akre:
        // Node 0 (36.740, 43.890) --(North, one-way car, both-way walk, "Akre Main")--> Node 1 (36.745, 43.890)
        // Node 1 (36.745, 43.890) --(East, two-way car/bike/walk, "Gulan St")--> Node 2 (36.745, 43.896)
        let f = File::create(path).unwrap();
        let mut gz = GzEncoder::new(f, Compression::default());

        let nodes: &[(f32, f32)] = &[
            (36.740, 43.890),
            (36.745, 43.890),
            (36.745, 43.896),
        ];
        let names = ["", "Akre Main", "Gulan St"];
        let d01 = haversine_meters(36.740, 43.890, 36.745, 43.890) as f32;
        let d12 = haversine_meters(36.745, 43.890, 36.745, 43.896) as f32;

        // Edges: (from, to, dist, speed, flags, name_idx)
        let edges: &[(i32, i32, f32, f32, u8, i32)] = &[
            (0, 1, d01, 50.0, FLAG_CAR | FLAG_BIKE | FLAG_WALK, 1),
            (1, 0, d01, 50.0, FLAG_WALK, 1), // one-way for car/bike, walking ok
            (1, 2, d12, 40.0, FLAG_CAR | FLAG_BIKE | FLAG_WALK, 2),
            (2, 1, d12, 40.0, FLAG_CAR | FLAG_BIKE | FLAG_WALK, 2),
        ];

        gz.write_all(&MAGIC.to_be_bytes()).unwrap();
        write_java_utf(&mut gz, "Akre Test");
        gz.write_all(&36.70f64.to_be_bytes()).unwrap();
        gz.write_all(&43.85f64.to_be_bytes()).unwrap();
        gz.write_all(&36.80f64.to_be_bytes()).unwrap();
        gz.write_all(&43.95f64.to_be_bytes()).unwrap();
        gz.write_all(&1700000000i64.to_be_bytes()).unwrap();
        gz.write_all(&(nodes.len() as i32).to_be_bytes()).unwrap();
        gz.write_all(&(edges.len() as i32).to_be_bytes()).unwrap();
        gz.write_all(&(names.len() as i32).to_be_bytes()).unwrap();

        for nm in &names {
            write_java_utf(&mut gz, nm);
        }
        for &(la, lo) in nodes {
            gz.write_all(&la.to_be_bytes()).unwrap();
            gz.write_all(&lo.to_be_bytes()).unwrap();
        }
        for &(from, to, dist, spd, flg, n_idx) in edges {
            gz.write_all(&from.to_be_bytes()).unwrap();
            gz.write_all(&to.to_be_bytes()).unwrap();
            gz.write_all(&dist.to_be_bytes()).unwrap();
            gz.write_all(&spd.to_be_bytes()).unwrap();
            gz.write_all(&[flg]).unwrap();
            gz.write_all(&n_idx.to_be_bytes()).unwrap();
        }
        gz.finish().unwrap();
    }

    #[test]
    fn loads_tsr_routes_turn_right_and_respects_oneway() {
        let tmp = std::env::temp_dir().join("terrasync_test_akre.tsr");
        write_sample_tsr(&tmp);
        let meta = std::fs::metadata(&tmp).unwrap();
        let path_str = tmp.to_str().unwrap();

        // Car forward 0 -> 1 -> 2 should succeed with a Turn right onto Gulan St
        let res = route_from_tsr(
            path_str,
            1,
            meta.len() as i64,
            36.7401,
            43.8900,
            36.7450,
            43.8959,
            TravelMode::Car,
        )
        .unwrap()
        .expect("Car forward route should succeed");

        assert!(res.total_dist > 900.0 && res.total_dist < 1300.0);
        assert_eq!(res.geom.len(), 5); // start + 3 nodes + end
        assert!(res.steps.iter().any(|s| s.instruction.contains("Head out onto Akre Main")));
        assert!(res.steps.iter().any(|s| s.instruction.contains("Turn right onto Gulan St")));
        assert_eq!(res.steps.last().unwrap().instruction, "Arrive at destination");

        // Encoded binary buffer starts with status=1
        let wire = encode_route_result(&Ok(res));
        assert_eq!(wire[0], 1u8);

        // Walking reverse 2 -> 1 -> 0 should succeed even though car is one-way
        let walk_rev = route_from_tsr(
            path_str,
            1,
            meta.len() as i64,
            36.7450,
            43.8959,
            36.7401,
            43.8900,
            TravelMode::Walking,
        )
        .unwrap()
        .expect("Walking reverse should succeed");
        assert!(walk_rev.steps.iter().any(|s| s.instruction.contains("Turn left onto Akre Main")));

        evict_graph(path_str);
        let _ = std::fs::remove_file(&tmp);
    }

    #[test]
    fn car_cannot_go_reverse_on_oneway_only_corridor() {
        let tmp = std::env::temp_dir().join("terrasync_test_oneway.tsr");
        let f = File::create(&tmp).unwrap();
        let mut gz = GzEncoder::new(f, Compression::default());
        gz.write_all(&MAGIC.to_be_bytes()).unwrap();
        write_java_utf(&mut gz, "OneWay");
        gz.write_all(&36.70f64.to_be_bytes()).unwrap();
        gz.write_all(&43.80f64.to_be_bytes()).unwrap();
        gz.write_all(&36.90f64.to_be_bytes()).unwrap();
        gz.write_all(&44.00f64.to_be_bytes()).unwrap();
        gz.write_all(&1i64.to_be_bytes()).unwrap();
        gz.write_all(&4i32.to_be_bytes()).unwrap();
        gz.write_all(&3i32.to_be_bytes()).unwrap();
        gz.write_all(&1i32.to_be_bytes()).unwrap();
        write_java_utf(&mut gz, "");
        // 4 nodes along a ~7 km one-way highway: 0 -> 1 -> 2 -> 3
        let nodes: &[(f32, f32)] = &[
            (36.740, 43.890),
            (36.745, 43.890),
            (36.800, 43.890),
            (36.805, 43.890),
        ];
        for &(la, lo) in nodes {
            gz.write_all(&la.to_be_bytes()).unwrap();
            gz.write_all(&lo.to_be_bytes()).unwrap();
        }
        let edges: &[(i32, i32, f32)] = &[
            (0, 1, 555.0),
            (1, 2, 6112.0),
            (2, 3, 555.0),
        ];
        for &(u, v, d) in edges {
            gz.write_all(&u.to_be_bytes()).unwrap();
            gz.write_all(&v.to_be_bytes()).unwrap();
            gz.write_all(&d.to_be_bytes()).unwrap();
            gz.write_all(&80.0f32.to_be_bytes()).unwrap();
            gz.write_all(&[FLAG_CAR]).unwrap();
            gz.write_all(&0i32.to_be_bytes()).unwrap();
        }
        gz.finish().unwrap();

        let meta = std::fs::metadata(&tmp).unwrap();
        let path_str = tmp.to_str().unwrap();

        // Forward 0 -> 3 succeeds
        let ok = route_from_tsr(
            path_str, 1, meta.len() as i64,
            36.740, 43.890, 36.805, 43.890, TravelMode::Car,
        ).unwrap();
        assert!(ok.is_ok());

        // Reverse 36.805 -> 36.740 snaps locally (2 -> 1) and A* must reject one-way reversal
        let err = route_from_tsr(
            path_str, 1, meta.len() as i64,
            36.805, 43.890, 36.740, 43.890, TravelMode::Car,
        ).unwrap().unwrap_err();
        assert_eq!(err, "No offline road connection found between points");

        evict_graph(path_str);
        let _ = std::fs::remove_file(&tmp);
    }
}
