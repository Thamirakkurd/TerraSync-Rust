use flate2::write::GzEncoder;
use flate2::Compression;
use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, BufWriter, Read, Write};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

const MAGIC: i32 = 0x5453_5231; // TSR1
const FLAG_CAR: u8 = 1;
const FLAG_BIKE: u8 = 2;
const FLAG_WALK: u8 = 4;

#[derive(Clone, Copy)]
struct CompiledEdge {
    from_idx: i32,
    to_idx: i32,
    dist: f32,
    speed: f32,
    name_idx: i32,
    flags: u8,
}

pub struct TsrCompiler {
    chunk_nodes: HashMap<i64, (f32, f32)>,
    seen_ways: HashSet<i64>,
    osm_to_node_idx: HashMap<i64, u32>,
    node_lats: Vec<f32>,
    node_lngs: Vec<f32>,
    names: Vec<String>,
    name_map: HashMap<String, u32>,
    edges: Vec<CompiledEdge>,
}

impl TsrCompiler {
    pub fn new() -> Self {
        let mut names = Vec::with_capacity(256);
        let mut name_map = HashMap::with_capacity(256);
        names.push(String::new());
        name_map.insert(String::new(), 0u32);
        Self {
            chunk_nodes: HashMap::with_capacity(4096),
            seen_ways: HashSet::with_capacity(4096),
            osm_to_node_idx: HashMap::with_capacity(8192),
            node_lats: Vec::with_capacity(8192),
            node_lngs: Vec::with_capacity(8192),
            names,
            name_map,
            edges: Vec::with_capacity(16384),
        }
    }

    fn resolve_name_idx(&mut self, name: &str) -> i32 {
        let trimmed = name.trim();
        if let Some(&idx) = self.name_map.get(trimmed) {
            return idx as i32;
        }
        let idx = self.names.len() as u32;
        let owned = trimmed.to_string();
        self.names.push(owned.clone());
        self.name_map.insert(owned, idx);
        idx as i32
    }

    fn resolve_node(&mut self, osm_id: i64) -> Option<(u32, f32, f32)> {
        if let Some(&idx) = self.osm_to_node_idx.get(&osm_id) {
            let i = idx as usize;
            return Some((idx, self.node_lats[i], self.node_lngs[i]));
        }
        let &(lat, lng) = self.chunk_nodes.get(&osm_id)?;
        let idx = self.node_lats.len() as u32;
        self.node_lats.push(lat);
        self.node_lngs.push(lng);
        self.osm_to_node_idx.insert(osm_id, idx);
        Some((idx, lat, lng))
    }

    pub fn clear_chunk_nodes(&mut self) {
        self.chunk_nodes.clear();
        if self.chunk_nodes.capacity() > 65_536 {
            self.chunk_nodes.shrink_to(16_384);
        }
    }

    pub fn counts(&self) -> (i32, i32, i32) {
        (
            self.node_lats.len() as i32,
            self.edges.len() as i32,
            self.names.len() as i32,
        )
    }

    pub fn ingest_chunk_file(&mut self, chunk_path: &str) -> Result<(), String> {
        self.chunk_nodes.clear();

        // Pass 1: stream node coordinates + detect Overpass remarks
        let f1 = File::open(chunk_path).map_err(|e| format!("Failed to open chunk: {e}"))?;
        let mut sc1 = JsonScanner::new(BufReader::with_capacity(64 * 1024, f1));
        let (parsed_elements, remark_err) = parse_overpass_nodes(&mut sc1, &mut self.chunk_nodes)
            .map_err(|e| format!("Malformed road chunk JSON: {e}"))?;
        if !parsed_elements {
            self.clear_chunk_nodes();
            return Err(remark_err.unwrap_or_else(|| "Overpass returned invalid JSON".to_string()));
        }

        // Pass 2: stream ways and emit directed edges
        let f2 = File::open(chunk_path).map_err(|e| format!("Failed to reopen chunk: {e}"))?;
        let mut sc2 = JsonScanner::new(BufReader::with_capacity(64 * 1024, f2));
        let res = parse_overpass_ways(&mut sc2, self);
        self.clear_chunk_nodes();
        res.map_err(|e| format!("Failed to parse road ways: {e}"))
    }

    fn push_way(
        &mut self,
        wid: i64,
        hw: &str,
        access: &str,
        road_name: &str,
        oneway_tag: &str,
        junction: &str,
        way_node_ids: &[i64],
    ) {
        if access == "private" || access == "no" {
            return;
        }
        if !self.seen_ways.insert(wid) {
            return;
        }

        let (car_ok, bike_ok, walk_ok, car_speed) = match hw {
            "motorway" | "motorway_link" => (true, false, false, 95.0f32),
            "trunk" | "trunk_link" => (true, true, true, 80.0f32),
            "primary" | "primary_link" => (true, true, true, 65.0f32),
            "secondary" | "secondary_link" => (true, true, true, 55.0f32),
            "tertiary" | "tertiary_link" => (true, true, true, 45.0f32),
            "unclassified" | "road" => (true, true, true, 40.0f32),
            "residential" => (true, true, true, 30.0f32),
            "living_street" | "service" => (true, true, true, 20.0f32),
            "track" => (true, true, true, 16.0f32),
            "cycleway" => (false, true, true, 15.0f32),
            "path" | "footway" => (false, true, true, 10.0f32),
            _ => (false, false, false, 15.0f32),
        };

        let is_one_way_fwd = matches!(oneway_tag, "yes" | "true" | "1")
            || junction == "roundabout"
            || matches!(hw, "motorway" | "motorway_link");
        let is_one_way_rev = oneway_tag == "-1";

        let fwd_flags = (if car_ok && !is_one_way_rev { FLAG_CAR } else { 0 })
            | (if bike_ok && !is_one_way_rev { FLAG_BIKE } else { 0 })
            | (if walk_ok { FLAG_WALK } else { 0 });
        let rev_flags = (if car_ok && !is_one_way_fwd { FLAG_CAR } else { 0 })
            | (if bike_ok && !is_one_way_fwd { FLAG_BIKE } else { 0 })
            | (if walk_ok { FLAG_WALK } else { 0 });
        if fwd_flags == 0 && rev_flags == 0 {
            return;
        }

        let n_idx = self.resolve_name_idx(road_name);
        let mut prev: Option<(u32, f32, f32)> = None;
        for &osm_id in way_node_ids {
            let curr = self.resolve_node(osm_id);
            if let (Some((p_idx, p_lat, p_lng)), Some((c_idx, c_lat, c_lng))) = (prev, curr) {
                if p_idx != c_idx {
                    let dist = (haversine_meters(
                        p_lat as f64,
                        p_lng as f64,
                        c_lat as f64,
                        c_lng as f64,
                    ) as f32)
                        .max(0.5f32);
                    if fwd_flags != 0 {
                        self.edges.push(CompiledEdge {
                            from_idx: p_idx as i32,
                            to_idx: c_idx as i32,
                            dist,
                            speed: car_speed,
                            name_idx: n_idx,
                            flags: fwd_flags,
                        });
                    }
                    if rev_flags != 0 {
                        self.edges.push(CompiledEdge {
                            from_idx: c_idx as i32,
                            to_idx: p_idx as i32,
                            dist,
                            speed: car_speed,
                            name_idx: n_idx,
                            flags: rev_flags,
                        });
                    }
                }
            }
            if curr.is_some() {
                prev = curr;
            }
        }
    }

    pub fn write_tsr(
        &self,
        part_path: &str,
        out_path: &str,
        region_name: &str,
        s_lat: f64,
        w_lng: f64,
        n_lat: f64,
        e_lng: f64,
        now: i64,
    ) -> Result<u64, String> {
        let node_count = self.node_lats.len() as i32;
        let edge_count = self.edges.len() as i32;
        let name_count = self.names.len() as i32;
        if node_count <= 0 || edge_count <= 0 {
            return Err("No routable roads found in this area".to_string());
        }

        let _ = fs::remove_file(part_path);
        let file = File::create(part_path).map_err(|e| format!("Failed to create part file: {e}"))?;
        let bw = BufWriter::with_capacity(64 * 1024, file);
        let gz = GzEncoder::new(bw, Compression::default());
        let mut out = BufWriter::with_capacity(64 * 1024, gz);

        let write_res: io::Result<()> = (|| {
            out.write_all(&MAGIC.to_be_bytes())?;
            write_java_utf(&mut out, region_name)?;
            out.write_all(&s_lat.to_be_bytes())?;
            out.write_all(&w_lng.to_be_bytes())?;
            out.write_all(&n_lat.to_be_bytes())?;
            out.write_all(&e_lng.to_be_bytes())?;
            out.write_all(&now.to_be_bytes())?;
            out.write_all(&node_count.to_be_bytes())?;
            out.write_all(&edge_count.to_be_bytes())?;
            out.write_all(&name_count.to_be_bytes())?;

            for name in &self.names {
                write_java_utf(&mut out, name)?;
            }

            let mut node_buf = [0u8; 8];
            for i in 0..self.node_lats.len() {
                node_buf[0..4].copy_from_slice(&self.node_lats[i].to_be_bytes());
                node_buf[4..8].copy_from_slice(&self.node_lngs[i].to_be_bytes());
                out.write_all(&node_buf)?;
            }

            let mut edge_buf = [0u8; 21];
            for e in &self.edges {
                edge_buf[0..4].copy_from_slice(&e.from_idx.to_be_bytes());
                edge_buf[4..8].copy_from_slice(&e.to_idx.to_be_bytes());
                edge_buf[8..12].copy_from_slice(&e.dist.to_be_bytes());
                edge_buf[12..16].copy_from_slice(&e.speed.to_be_bytes());
                edge_buf[16] = e.flags;
                edge_buf[17..21].copy_from_slice(&e.name_idx.to_be_bytes());
                out.write_all(&edge_buf)?;
            }

            out.flush()?;
            let gz = out.into_inner().map_err(|e| e.into_error())?;
            let mut file_bw = gz.finish()?;
            file_bw.flush()?;
            let raw_file = file_bw.into_inner().map_err(|e| e.into_error())?;
            let _ = raw_file.sync_all();
            Ok(())
        })();

        if let Err(e) = write_res {
            let _ = fs::remove_file(part_path);
            return Err(format!("Failed writing TSR graph: {e}"));
        }

        let _ = fs::remove_file(out_path);
        if fs::rename(part_path, out_path).is_err() {
            fs::copy(part_path, out_path)
                .map_err(|e| format!("Failed finalizing TSR file: {e}"))?;
            let _ = fs::remove_file(part_path);
        }

        crate::routing::evict_graph(out_path);
        let size_bytes = fs::metadata(out_path)
            .map(|m| m.len())
            .unwrap_or(0);
        Ok(size_bytes)
    }
}

fn haversine_meters(lat1: f64, lng1: f64, lat2: f64, lng2: f64) -> f64 {
    const R: f64 = 6_371_000.0;
    let d_lat = (lat2 - lat1).to_radians();
    let d_lng = (lng2 - lng1).to_radians();
    let s1 = (d_lat * 0.5).sin();
    let s2 = (d_lng * 0.5).sin();
    let a = s1 * s1 + lat1.to_radians().cos() * lat2.to_radians().cos() * s2 * s2;
    let c = 2.0 * a.clamp(0.0, 1.0).sqrt().atan2((1.0 - a).max(0.0).sqrt());
    R * c
}

fn write_java_utf<W: Write>(w: &mut W, s: &str) -> io::Result<()> {
    let mut mutf8 = Vec::with_capacity(s.len());
    for ch in s.chars() {
        let code = ch as u32;
        if code >= 0x0001 && code <= 0x007F {
            if mutf8.len() + 1 > 65535 { break; }
            mutf8.push(code as u8);
        } else if code <= 0x07FF {
            if mutf8.len() + 2 > 65535 { break; }
            mutf8.push((0xC0 | ((code >> 6) & 0x1F)) as u8);
            mutf8.push((0x80 | (code & 0x3F)) as u8);
        } else if code <= 0xFFFF {
            if mutf8.len() + 3 > 65535 { break; }
            mutf8.push((0xE0 | ((code >> 12) & 0x0F)) as u8);
            mutf8.push((0x80 | ((code >> 6) & 0x3F)) as u8);
            mutf8.push((0x80 | (code & 0x3F)) as u8);
        } else {
            if mutf8.len() + 6 > 65535 { break; }
            let adj = code - 0x10000;
            let hi = 0xD800 | ((adj >> 10) & 0x3FF);
            let lo = 0xDC00 | (adj & 0x3FF);
            for u in [hi, lo] {
                mutf8.push((0xE0 | ((u >> 12) & 0x0F)) as u8);
                mutf8.push((0x80 | ((u >> 6) & 0x3F)) as u8);
                mutf8.push((0x80 | (u & 0x3F)) as u8);
            }
        }
    }
    w.write_all(&(mutf8.len() as u16).to_be_bytes())?;
    w.write_all(&mutf8)
}

// ---------------------------------------------------------------------------
// Zero-dependency streaming Overpass JSON scanner
// ---------------------------------------------------------------------------

struct JsonScanner<R: Read> {
    r: BufReader<R>,
    peeked: Option<u8>,
    str_raw: Vec<u8>,
    num_buf: String,
}

impl<R: Read> JsonScanner<R> {
    fn new(r: BufReader<R>) -> Self {
        Self {
            r,
            peeked: None,
            str_raw: Vec::with_capacity(128),
            num_buf: String::with_capacity(32),
        }
    }

    #[inline]
    fn next_byte(&mut self) -> io::Result<Option<u8>> {
        if let Some(b) = self.peeked.take() {
            return Ok(Some(b));
        }
        let b = {
            let buf = self.r.fill_buf()?;
            if buf.is_empty() {
                return Ok(None);
            }
            buf[0]
        };
        self.r.consume(1);
        Ok(Some(b))
    }

    #[inline]
    fn peek_byte(&mut self) -> io::Result<Option<u8>> {
        if self.peeked.is_none() {
            self.peeked = self.next_byte()?;
        }
        Ok(self.peeked)
    }

    #[inline]
    fn skip_ws(&mut self) -> io::Result<Option<u8>> {
        loop {
            match self.peek_byte()? {
                Some(b' ' | b'\n' | b'\r' | b'\t') => {
                    self.peeked = None;
                }
                other => return Ok(other),
            }
        }
    }

    fn expect_byte(&mut self, expected: u8) -> io::Result<()> {
        match self.skip_ws()? {
            Some(b) if b == expected => {
                self.peeked = None;
                Ok(())
            }
            other => Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("expected '{}', got {:?}", expected as char, other),
            )),
        }
    }

    fn read_hex4(&mut self) -> io::Result<u16> {
        let mut val = 0u16;
        for _ in 0..4 {
            let b = self
                .next_byte()?
                .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "eof in \\u escape"))?;
            let d = match b {
                b'0'..=b'9' => (b - b'0') as u16,
                b'a'..=b'f' => (b - b'a' + 10) as u16,
                b'A'..=b'F' => (b - b'A' + 10) as u16,
                _ => {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "invalid hex in \\u escape",
                    ))
                }
            };
            val = (val << 4) | d;
        }
        Ok(val)
    }

    fn read_string_into(&mut self, out: &mut String) -> io::Result<()> {
        self.expect_byte(b'"')?;
        self.str_raw.clear();
        let mut utf8_tmp = [0u8; 4];
        loop {
            let b = self
                .next_byte()?
                .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "unterminated string"))?;
            match b {
                b'"' => break,
                b'\\' => {
                    let esc = self
                        .next_byte()?
                        .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "eof after \\"))?;
                    match esc {
                        b'"' => self.str_raw.push(b'"'),
                        b'\\' => self.str_raw.push(b'\\'),
                        b'/' => self.str_raw.push(b'/'),
                        b'b' => self.str_raw.push(0x08),
                        b'f' => self.str_raw.push(0x0C),
                        b'n' => self.str_raw.push(b'\n'),
                        b'r' => self.str_raw.push(b'\r'),
                        b't' => self.str_raw.push(b'\t'),
                        b'u' => {
                            let u1 = self.read_hex4()?;
                            let ch = if (0xD800..=0xDBFF).contains(&u1) {
                                // Try surrogate pair
                                if self.peek_byte()? == Some(b'\\') {
                                    self.peeked = None;
                                    if self.next_byte()? == Some(b'u') {
                                        let u2 = self.read_hex4()?;
                                        if (0xDC00..=0xDFFF).contains(&u2) {
                                            let cp = 0x10000
                                                + (((u1 as u32 - 0xD800) << 10)
                                                    | (u2 as u32 - 0xDC00));
                                            char::from_u32(cp).unwrap_or(char::REPLACEMENT_CHARACTER)
                                        } else {
                                            char::REPLACEMENT_CHARACTER
                                        }
                                    } else {
                                        char::REPLACEMENT_CHARACTER
                                    }
                                } else {
                                    char::REPLACEMENT_CHARACTER
                                }
                            } else {
                                char::from_u32(u1 as u32).unwrap_or(char::REPLACEMENT_CHARACTER)
                            };
                            let encoded = ch.encode_utf8(&mut utf8_tmp);
                            self.str_raw.extend_from_slice(encoded.as_bytes());
                        }
                        other => self.str_raw.push(other),
                    }
                }
                other => self.str_raw.push(other),
            }
        }
        out.clear();
        match std::str::from_utf8(&self.str_raw) {
            Ok(s) => out.push_str(s),
            Err(_) => out.push_str(&String::from_utf8_lossy(&self.str_raw)),
        }
        Ok(())
    }

    fn read_number_token(&mut self) -> io::Result<()> {
        self.skip_ws()?;
        self.num_buf.clear();
        while let Some(b) = self.peek_byte()? {
            if matches!(b, b'0'..=b'9' | b'-' | b'+' | b'.' | b'e' | b'E') {
                self.peeked = None;
                self.num_buf.push(b as char);
            } else {
                break;
            }
        }
        if self.num_buf.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "expected number"));
        }
        Ok(())
    }

    fn read_i64_or_skip(&mut self) -> io::Result<i64> {
        match self.skip_ws()? {
            Some(b'-' | b'0'..=b'9') => {
                self.read_number_token()?;
                Ok(self.num_buf.parse::<i64>().unwrap_or(0))
            }
            _ => {
                self.skip_value(0)?;
                Ok(0)
            }
        }
    }

    fn read_f64_or_skip(&mut self) -> io::Result<f64> {
        match self.skip_ws()? {
            Some(b'-' | b'0'..=b'9') => {
                self.read_number_token()?;
                Ok(self.num_buf.parse::<f64>().unwrap_or(f64::NAN))
            }
            _ => {
                self.skip_value(0)?;
                Ok(f64::NAN)
            }
        }
    }

    fn read_string_or_skip(&mut self, out: &mut String) -> io::Result<bool> {
        match self.skip_ws()? {
            Some(b'"') => {
                self.read_string_into(out)?;
                Ok(true)
            }
            _ => {
                out.clear();
                self.skip_value(0)?;
                Ok(false)
            }
        }
    }

    fn skip_string(&mut self) -> io::Result<()> {
        self.expect_byte(b'"')?;
        loop {
            let b = self
                .next_byte()?
                .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "unterminated string"))?;
            match b {
                b'"' => return Ok(()),
                b'\\' => {
                    let _ = self.next_byte()?;
                }
                _ => {}
            }
        }
    }

    fn skip_value(&mut self, depth: u32) -> io::Result<()> {
        if depth > 64 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "JSON nesting too deep"));
        }
        match self.skip_ws()? {
            Some(b'"') => self.skip_string(),
            Some(b'{') => {
                self.peeked = None;
                if self.skip_ws()? == Some(b'}') {
                    self.peeked = None;
                    return Ok(());
                }
                loop {
                    self.skip_string()?;
                    self.expect_byte(b':')?;
                    self.skip_value(depth + 1)?;
                    match self.skip_ws()? {
                        Some(b',') => {
                            self.peeked = None;
                        }
                        Some(b'}') => {
                            self.peeked = None;
                            return Ok(());
                        }
                        _ => {
                            return Err(io::Error::new(
                                io::ErrorKind::InvalidData,
                                "malformed object",
                            ))
                        }
                    }
                }
            }
            Some(b'[') => {
                self.peeked = None;
                if self.skip_ws()? == Some(b']') {
                    self.peeked = None;
                    return Ok(());
                }
                loop {
                    self.skip_value(depth + 1)?;
                    match self.skip_ws()? {
                        Some(b',') => {
                            self.peeked = None;
                        }
                        Some(b']') => {
                            self.peeked = None;
                            return Ok(());
                        }
                        _ => {
                            return Err(io::Error::new(
                                io::ErrorKind::InvalidData,
                                "malformed array",
                            ))
                        }
                    }
                }
            }
            Some(b'-' | b'0'..=b'9') => self.read_number_token(),
            Some(b't') => {
                for expected in *b"true" {
                    if self.next_byte()? != Some(expected) {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad literal"));
                    }
                }
                Ok(())
            }
            Some(b'f') => {
                for expected in *b"false" {
                    if self.next_byte()? != Some(expected) {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad literal"));
                    }
                }
                Ok(())
            }
            Some(b'n') => {
                for expected in *b"null" {
                    if self.next_byte()? != Some(expected) {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad literal"));
                    }
                }
                Ok(())
            }
            _ => Err(io::Error::new(io::ErrorKind::InvalidData, "unexpected JSON token")),
        }
    }
}

fn parse_overpass_nodes<R: Read>(
    sc: &mut JsonScanner<R>,
    chunk_nodes: &mut HashMap<i64, (f32, f32)>,
) -> io::Result<(bool, Option<String>)> {
    let mut parsed_elements = false;
    let mut remark_err: Option<String> = None;
    let mut key = String::with_capacity(32);
    let mut field = String::with_capacity(32);
    let mut elem_type = String::with_capacity(16);
    let mut tmp = String::with_capacity(64);

    sc.expect_byte(b'{')?;
    if sc.skip_ws()? == Some(b'}') {
        sc.peeked = None;
        return Ok((false, None));
    }

    loop {
        sc.read_string_into(&mut key)?;
        sc.expect_byte(b':')?;
        match key.as_str() {
            "remark" => {
                if sc.read_string_or_skip(&mut tmp)? {
                    remark_err = Some(tmp.clone());
                }
            }
            "elements" => {
                parsed_elements = true;
                sc.expect_byte(b'[')?;
                if sc.skip_ws()? == Some(b']') {
                    sc.peeked = None;
                } else {
                    loop {
                        elem_type.clear();
                        let mut id = 0i64;
                        let mut lat = f64::NAN;
                        let mut lon = f64::NAN;

                        sc.expect_byte(b'{')?;
                        if sc.skip_ws()? == Some(b'}') {
                            sc.peeked = None;
                        } else {
                            loop {
                                sc.read_string_into(&mut field)?;
                                sc.expect_byte(b':')?;
                                match field.as_str() {
                                    "type" => {
                                        let _ = sc.read_string_or_skip(&mut elem_type)?;
                                    }
                                    "id" => id = sc.read_i64_or_skip()?,
                                    "lat" => lat = sc.read_f64_or_skip()?,
                                    "lon" => lon = sc.read_f64_or_skip()?,
                                    _ => sc.skip_value(0)?,
                                }
                                match sc.skip_ws()? {
                                    Some(b',') => sc.peeked = None,
                                    Some(b'}') => {
                                        sc.peeked = None;
                                        break;
                                    }
                                    _ => {
                                        return Err(io::Error::new(
                                            io::ErrorKind::InvalidData,
                                            "malformed element object",
                                        ))
                                    }
                                }
                            }
                        }

                        if elem_type == "node" && id != 0 && lat.is_finite() && lon.is_finite() {
                            chunk_nodes.insert(id, (lat as f32, lon as f32));
                        }

                        match sc.skip_ws()? {
                            Some(b',') => sc.peeked = None,
                            Some(b']') => {
                                sc.peeked = None;
                                break;
                            }
                            _ => {
                                return Err(io::Error::new(
                                    io::ErrorKind::InvalidData,
                                    "malformed elements array",
                                ))
                            }
                        }
                    }
                }
            }
            _ => sc.skip_value(0)?,
        }

        match sc.skip_ws()? {
            Some(b',') => sc.peeked = None,
            Some(b'}') => {
                sc.peeked = None;
                break;
            }
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "malformed root object",
                ))
            }
        }
    }

    Ok((parsed_elements, remark_err))
}

fn parse_overpass_ways<R: Read>(
    sc: &mut JsonScanner<R>,
    compiler: &mut TsrCompiler,
) -> io::Result<()> {
    let mut key = String::with_capacity(32);
    let mut field = String::with_capacity(32);
    let mut tag_key = String::with_capacity(32);
    let mut elem_type = String::with_capacity(16);
    let mut hw = String::with_capacity(32);
    let mut access = String::with_capacity(32);
    let mut road_name = String::with_capacity(64);
    let mut oneway_tag = String::with_capacity(16);
    let mut junction = String::with_capacity(32);
    let mut way_node_ids: Vec<i64> = Vec::with_capacity(64);

    sc.expect_byte(b'{')?;
    if sc.skip_ws()? == Some(b'}') {
        sc.peeked = None;
        return Ok(());
    }

    loop {
        sc.read_string_into(&mut key)?;
        sc.expect_byte(b':')?;
        if key == "elements" {
            sc.expect_byte(b'[')?;
            if sc.skip_ws()? == Some(b']') {
                sc.peeked = None;
            } else {
                loop {
                    elem_type.clear();
                    hw.clear();
                    access.clear();
                    road_name.clear();
                    oneway_tag.clear();
                    junction.clear();
                    way_node_ids.clear();
                    let mut wid = 0i64;

                    sc.expect_byte(b'{')?;
                    if sc.skip_ws()? == Some(b'}') {
                        sc.peeked = None;
                    } else {
                        loop {
                            sc.read_string_into(&mut field)?;
                            sc.expect_byte(b':')?;
                            match field.as_str() {
                                "type" => {
                                    let _ = sc.read_string_or_skip(&mut elem_type)?;
                                }
                                "id" => wid = sc.read_i64_or_skip()?,
                                "nodes" => {
                                    if sc.skip_ws()? == Some(b'[') {
                                        sc.peeked = None;
                                        if sc.skip_ws()? == Some(b']') {
                                            sc.peeked = None;
                                        } else {
                                            loop {
                                                let nid = sc.read_i64_or_skip()?;
                                                if nid != 0 {
                                                    way_node_ids.push(nid);
                                                }
                                                match sc.skip_ws()? {
                                                    Some(b',') => sc.peeked = None,
                                                    Some(b']') => {
                                                        sc.peeked = None;
                                                        break;
                                                    }
                                                    _ => {
                                                        return Err(io::Error::new(
                                                            io::ErrorKind::InvalidData,
                                                            "malformed nodes array",
                                                        ))
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        sc.skip_value(0)?;
                                    }
                                }
                                "tags" => {
                                    if sc.skip_ws()? == Some(b'{') {
                                        sc.peeked = None;
                                        if sc.skip_ws()? == Some(b'}') {
                                            sc.peeked = None;
                                        } else {
                                            loop {
                                                sc.read_string_into(&mut tag_key)?;
                                                sc.expect_byte(b':')?;
                                                match tag_key.as_str() {
                                                    "highway" => {
                                                        let _ = sc.read_string_or_skip(&mut hw)?;
                                                    }
                                                    "access" => {
                                                        let _ = sc.read_string_or_skip(&mut access)?;
                                                    }
                                                    "name" => {
                                                        let _ = sc.read_string_or_skip(&mut road_name)?;
                                                    }
                                                    "oneway" => {
                                                        let _ = sc.read_string_or_skip(&mut oneway_tag)?;
                                                    }
                                                    "junction" => {
                                                        let _ = sc.read_string_or_skip(&mut junction)?;
                                                    }
                                                    _ => sc.skip_value(0)?,
                                                }
                                                match sc.skip_ws()? {
                                                    Some(b',') => sc.peeked = None,
                                                    Some(b'}') => {
                                                        sc.peeked = None;
                                                        break;
                                                    }
                                                    _ => {
                                                        return Err(io::Error::new(
                                                            io::ErrorKind::InvalidData,
                                                            "malformed tags object",
                                                        ))
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        sc.skip_value(0)?;
                                    }
                                }
                                _ => sc.skip_value(0)?,
                            }
                            match sc.skip_ws()? {
                                Some(b',') => sc.peeked = None,
                                Some(b'}') => {
                                    sc.peeked = None;
                                    break;
                                }
                                _ => {
                                    return Err(io::Error::new(
                                        io::ErrorKind::InvalidData,
                                        "malformed element object",
                                    ))
                                }
                            }
                        }
                    }

                    if elem_type == "way"
                        && wid != 0
                        && !hw.is_empty()
                        && way_node_ids.len() >= 2
                    {
                        compiler.push_way(
                            wid,
                            &hw,
                            &access,
                            &road_name,
                            &oneway_tag,
                            &junction,
                            &way_node_ids,
                        );
                    }

                    match sc.skip_ws()? {
                        Some(b',') => sc.peeked = None,
                        Some(b']') => {
                            sc.peeked = None;
                            break;
                        }
                        _ => {
                            return Err(io::Error::new(
                                io::ErrorKind::InvalidData,
                                "malformed elements array",
                            ))
                        }
                    }
                }
            }
        } else {
            sc.skip_value(0)?;
        }

        match sc.skip_ws()? {
            Some(b',') => sc.peeked = None,
            Some(b'}') => {
                sc.peeked = None;
                break;
            }
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "malformed root object",
                ))
            }
        }
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Handle registry for incremental chunk compilation across JNI
// ---------------------------------------------------------------------------

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<Mutex<TsrCompiler>>>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<i64, Arc<Mutex<TsrCompiler>>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn create_session() -> i64 {
    let id = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let compiler = Arc::new(Mutex::new(TsrCompiler::new()));
    if let Ok(mut map) = sessions().lock() {
        map.insert(id, compiler);
    }
    id
}

fn get_session(handle: i64) -> Option<Arc<Mutex<TsrCompiler>>> {
    sessions().lock().ok()?.get(&handle).cloned()
}

pub fn ingest_chunk(handle: i64, chunk_path: &str) -> Result<(), String> {
    let sess = get_session(handle).ok_or_else(|| "Invalid TSR compiler session".to_string())?;
    let mut guard = sess
        .lock()
        .map_err(|_| "TSR compiler lock poisoned".to_string())?;
    guard.ingest_chunk_file(chunk_path)
}

pub fn counts(handle: i64) -> Option<(i32, i32, i32)> {
    let sess = get_session(handle)?;
    let guard = sess.lock().ok()?;
    Some(guard.counts())
}

pub fn write_tsr(
    handle: i64,
    part_path: &str,
    out_path: &str,
    region_name: &str,
    s_lat: f64,
    w_lng: f64,
    n_lat: f64,
    e_lng: f64,
    now: i64,
) -> Result<u64, String> {
    let sess = get_session(handle).ok_or_else(|| "Invalid TSR compiler session".to_string())?;
    let guard = sess
        .lock()
        .map_err(|_| "TSR compiler lock poisoned".to_string())?;
    guard.write_tsr(part_path, out_path, region_name, s_lat, w_lng, n_lat, e_lng, now)
}

pub fn destroy_session(handle: i64) {
    if let Ok(mut map) = sessions().lock() {
        map.remove(&handle);
        if map.is_empty() {
            map.shrink_to_fit();
        }
    }
}

pub fn shrink_session_map() {
    if let Ok(mut map) = sessions().lock() {
        if map.is_empty() {
            map.shrink_to_fit();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::read::GzDecoder;

    fn read_be_i32<R: Read>(r: &mut R) -> i32 {
        let mut b = [0u8; 4];
        r.read_exact(&mut b).unwrap();
        i32::from_be_bytes(b)
    }

    fn read_be_i64<R: Read>(r: &mut R) -> i64 {
        let mut b = [0u8; 8];
        r.read_exact(&mut b).unwrap();
        i64::from_be_bytes(b)
    }

    fn read_be_f32<R: Read>(r: &mut R) -> f32 {
        let mut b = [0u8; 4];
        r.read_exact(&mut b).unwrap();
        f32::from_be_bytes(b)
    }

    fn read_be_f64<R: Read>(r: &mut R) -> f64 {
        let mut b = [0u8; 8];
        r.read_exact(&mut b).unwrap();
        f64::from_be_bytes(b)
    }

    fn read_java_utf_simple<R: Read>(r: &mut R) -> String {
        let mut len_b = [0u8; 2];
        r.read_exact(&mut len_b).unwrap();
        let len = u16::from_be_bytes(len_b) as usize;
        let mut buf = vec![0u8; len];
        r.read_exact(&mut buf).unwrap();
        String::from_utf8(buf).unwrap()
    }

    #[test]
    fn test_overpass_multi_chunk_compile_tsr_binary() {
        let tmp = std::env::temp_dir().join(format!(
            "tsr_comp_test_{}",
            std::process::id()
        ));
        let _ = fs::create_dir_all(&tmp);
        let c1 = tmp.join("chunk_0.json");
        let c2 = tmp.join("chunk_1.json");
        let part = tmp.join("duhok.tsr.part");
        let out = tmp.join("duhok.tsr");

        // Chunk 1: one-way primary road (Kurdish UTF-8 name) + private road (must be ignored)
        fs::write(
            &c1,
            r#"{
              "version": 0.6,
              "elements": [
                {"type":"node","id":101,"lat":36.8600,"lon":43.0000},
                {"type":"node","id":102,"lat":36.8610,"lon":43.0000},
                {"type":"node","id":103,"lat":36.8620,"lon":43.0000},
                {"type":"way","id":500,"nodes":[101,102],"tags":{"highway":"primary","name":"ڕێگا \u0626\u0627\u06a9\u0631\u06ce","oneway":"yes"}},
                {"type":"way","id":501,"nodes":[102,103],"tags":{"highway":"residential","access":"private","name":"Private"}}
              ]
            }"#,
        )
        .unwrap();

        // Chunk 2: duplicates way 500 (must dedup), adds two-way residential 102->104
        fs::write(
            &c2,
            r#"{
              "elements": [
                {"type":"node","id":102,"lat":36.8610,"lon":43.0000},
                {"type":"node","id":104,"lat":36.8610,"lon":43.0020},
                {"type":"way","id":500,"nodes":[101,102],"tags":{"highway":"primary","name":"Dup"}},
                {"type":"way","id":502,"nodes":[102,104],"tags":{"highway":"residential","name":"Main St"}}
              ]
            }"#,
        )
        .unwrap();

        let h = create_session();
        ingest_chunk(h, c1.to_str().unwrap()).unwrap();
        ingest_chunk(h, c2.to_str().unwrap()).unwrap();

        let (nodes, edges, names) = counts(h).unwrap();
        assert_eq!(nodes, 3); // 101, 102, 104 (103 was only in ignored private way)
        // way 500 oneway=yes: fwd (CAR|BIKE|WALK) + rev (WALK) = 2 edges
        // way 502 residential two-way: fwd + rev = 2 edges
        assert_eq!(edges, 4);
        assert_eq!(names, 3); // "", "ڕێگا ئاکرێ", "Main St"

        let size = write_tsr(
            h,
            part.to_str().unwrap(),
            out.to_str().unwrap(),
            "Duhok Test",
            36.85,
            42.99,
            36.87,
            43.01,
            1_732_000_000_000,
        )
        .unwrap();
        destroy_session(h);
        assert!(size > 32);

        // Verify binary TSR1 payload matches Kotlin/Rust reader format byte-for-byte
        let mut gz = GzDecoder::new(File::open(&out).unwrap());
        assert_eq!(read_be_i32(&mut gz), MAGIC);
        assert_eq!(read_java_utf_simple(&mut gz), "Duhok Test");
        assert!((read_be_f64(&mut gz) - 36.85).abs() < 1e-9);
        assert!((read_be_f64(&mut gz) - 42.99).abs() < 1e-9);
        assert!((read_be_f64(&mut gz) - 36.87).abs() < 1e-9);
        assert!((read_be_f64(&mut gz) - 43.01).abs() < 1e-9);
        assert_eq!(read_be_i64(&mut gz), 1_732_000_000_000);
        assert_eq!(read_be_i32(&mut gz), 3);
        assert_eq!(read_be_i32(&mut gz), 4);
        assert_eq!(read_be_i32(&mut gz), 3);

        assert_eq!(read_java_utf_simple(&mut gz), "");
        assert_eq!(read_java_utf_simple(&mut gz), "ڕێگا ئاکرێ");
        assert_eq!(read_java_utf_simple(&mut gz), "Main St");

        let lat0 = read_be_f32(&mut gz);
        let lng0 = read_be_f32(&mut gz);
        assert!((lat0 - 36.86).abs() < 1e-4);
        assert!((lng0 - 43.00).abs() < 1e-4);
        let _ = (read_be_f32(&mut gz), read_be_f32(&mut gz));
        let _ = (read_be_f32(&mut gz), read_be_f32(&mut gz));

        // Edge 0: 0 -> 1, primary oneway fwd = CAR|BIKE|WALK (7), speed 65.0
        assert_eq!(read_be_i32(&mut gz), 0);
        assert_eq!(read_be_i32(&mut gz), 1);
        let dist0 = read_be_f32(&mut gz);
        assert!(dist0 > 100.0 && dist0 < 120.0);
        assert!((read_be_f32(&mut gz) - 65.0).abs() < 1e-4);
        let mut fl = [0u8; 1];
        gz.read_exact(&mut fl).unwrap();
        assert_eq!(fl[0], FLAG_CAR | FLAG_BIKE | FLAG_WALK);
        assert_eq!(read_be_i32(&mut gz), 1);

        // Edge 1: 1 -> 0, primary oneway rev = WALK only (4)
        assert_eq!(read_be_i32(&mut gz), 1);
        assert_eq!(read_be_i32(&mut gz), 0);
        let _ = read_be_f32(&mut gz);
        let _ = read_be_f32(&mut gz);
        gz.read_exact(&mut fl).unwrap();
        assert_eq!(fl[0], FLAG_WALK);
        assert_eq!(read_be_i32(&mut gz), 1);

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_overpass_remark_error_rejected() {
        let tmp = std::env::temp_dir().join(format!(
            "tsr_comp_err_{}.json",
            std::process::id()
        ));
        fs::write(&tmp, r#"{"remark":"runtime error: Query timed out"}"#).unwrap();
        let h = create_session();
        let err = ingest_chunk(h, tmp.to_str().unwrap()).unwrap_err();
        destroy_session(h);
        let _ = fs::remove_file(&tmp);
        assert!(err.contains("Query timed out"));
    }
}
