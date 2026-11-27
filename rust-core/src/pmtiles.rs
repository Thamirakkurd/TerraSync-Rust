use flate2::read::GzDecoder;
use std::collections::HashMap;
use std::fs::File;
use std::io::Read;
use std::sync::{Arc, Mutex, OnceLock};

#[cfg(unix)]
use std::os::unix::fs::FileExt;

const HEADER_SIZE: usize = 127;
const COMPRESSION_NONE: u8 = 1;
const COMPRESSION_GZIP: u8 = 2;
const MAX_LEAF_CACHE: usize = 32;
const MAX_SLICE_LEN: usize = 32 * 1024 * 1024;
const MAX_DIR_ENTRIES: usize = 2_000_000;

#[derive(Debug, Clone, Copy)]
pub struct PmHeader {
    pub root_dir_offset: u64,
    pub root_dir_length: u64,
    pub leaf_dirs_offset: u64,
    pub tile_data_offset: u64,
    pub internal_compression: u8,
    pub tile_compression: u8,
}

#[derive(Debug, Clone, Copy)]
pub struct DirEntry {
    pub tile_id: u64,
    pub offset: u64,
    pub length: u32,
    pub run_length: u32,
}

struct OpenPmArchive {
    last_modified: i64,
    file_size: i64,
    file: File,
    header: PmHeader,
    root_entries: Vec<DirEntry>,
    leaf_cache: Mutex<Vec<(u64, Arc<Vec<DirEntry>>)>>,
}

static ARCHIVE_CACHE: OnceLock<Mutex<HashMap<String, Arc<OpenPmArchive>>>> = OnceLock::new();

fn archive_cache() -> &'static Mutex<HashMap<String, Arc<OpenPmArchive>>> {
    ARCHIVE_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

#[cfg(unix)]
fn read_exact_at(file: &File, offset: u64, len: usize) -> Option<Vec<u8>> {
    if len == 0 || len > MAX_SLICE_LEN {
        return None;
    }
    let mut buf = vec![0u8; len];
    let mut done = 0usize;
    while done < len {
        let n = file.read_at(&mut buf[done..], offset.checked_add(done as u64)?).ok()?;
        if n == 0 {
            return None;
        }
        done += n;
    }
    Some(buf)
}

pub fn parse_header_bytes(raw: &[u8]) -> Option<PmHeader> {
    if raw.len() < HEADER_SIZE {
        return None;
    }
    if &raw[0..7] != b"PMTiles" || raw[7] != 3 {
        return None;
    }
    let get_u64 = |off: usize| -> u64 {
        u64::from_le_bytes(raw[off..off + 8].try_into().unwrap())
    };
    let root_dir_offset = get_u64(8);
    let root_dir_length = get_u64(16);
    let leaf_dirs_offset = get_u64(40);
    let tile_data_offset = get_u64(56);
    let internal_compression = raw[97];
    let tile_compression = raw[98];

    if root_dir_length == 0 || tile_data_offset < HEADER_SIZE as u64 {
        return None;
    }
    if internal_compression != COMPRESSION_NONE && internal_compression != COMPRESSION_GZIP {
        return None;
    }
    if tile_compression != COMPRESSION_NONE && tile_compression != COMPRESSION_GZIP {
        return None;
    }
    Some(PmHeader {
        root_dir_offset,
        root_dir_length,
        leaf_dirs_offset,
        tile_data_offset,
        internal_compression,
        tile_compression,
    })
}

pub fn zxy_to_tile_id(z: u8, x: u32, y: u32) -> u64 {
    if z == 0 {
        return 0;
    }
    let base_id = ((1u64 << (2 * z as u32)) - 1) / 3;
    let n = 1u64 << (z as u32);
    let mut tx = x as u64;
    let mut ty = y as u64;
    let mut d = 0u64;
    let mut s = n >> 1;
    while s > 0 {
        let rx = if (tx & s) != 0 { 1u64 } else { 0u64 };
        let ry = if (ty & s) != 0 { 1u64 } else { 0u64 };
        d += s * s * ((3 * rx) ^ ry);
        if ry == 0 {
            if rx == 1 {
                tx = n - 1 - tx;
                ty = n - 1 - ty;
            }
            std::mem::swap(&mut tx, &mut ty);
        }
        s >>= 1;
    }
    base_id + d
}

#[inline]
fn read_varint(data: &[u8], pos: &mut usize) -> Option<u64> {
    let mut res = 0u64;
    let mut shift = 0u32;
    while *pos < data.len() && shift < 64 {
        let b = data[*pos];
        *pos += 1;
        res |= ((b & 0x7F) as u64) << shift;
        if (b & 0x80) == 0 {
            return Some(res);
        }
        shift += 7;
    }
    None
}

pub fn parse_directory(raw: &[u8], compression: u8) -> Option<Vec<DirEntry>> {
    let owned: Vec<u8>;
    let data: &[u8] = match compression {
        COMPRESSION_NONE => raw,
        COMPRESSION_GZIP => {
            let mut gz = GzDecoder::new(raw);
            let mut dec = Vec::with_capacity(raw.len().saturating_mul(2));
            gz.read_to_end(&mut dec).ok()?;
            owned = dec;
            &owned
        }
        _ => return None,
    };

    let mut pos = 0usize;
    let num_entries = usize::try_from(read_varint(data, &mut pos)?).ok()?;
    if num_entries > MAX_DIR_ENTRIES {
        return None;
    }
    let mut tile_ids = Vec::with_capacity(num_entries);
    let mut last_id = 0u64;
    for _ in 0..num_entries {
        last_id = last_id.checked_add(read_varint(data, &mut pos)?)?;
        tile_ids.push(last_id);
    }
    let mut run_lengths = Vec::with_capacity(num_entries);
    for _ in 0..num_entries {
        run_lengths.push(u32::try_from(read_varint(data, &mut pos)?).ok()?);
    }
    let mut lengths = Vec::with_capacity(num_entries);
    for _ in 0..num_entries {
        lengths.push(u32::try_from(read_varint(data, &mut pos)?).ok()?);
    }
    let mut offsets: Vec<u64> = Vec::with_capacity(num_entries);
    for i in 0..num_entries {
        let tmp = read_varint(data, &mut pos)?;
        let off = if i > 0 && tmp == 0 {
            offsets[i - 1].checked_add(lengths[i - 1] as u64)?
        } else {
            tmp.checked_sub(1)?
        };
        offsets.push(off);
    }

    let mut entries = Vec::with_capacity(num_entries);
    for i in 0..num_entries {
        entries.push(DirEntry {
            tile_id: tile_ids[i],
            offset: offsets[i],
            length: lengths[i],
            run_length: run_lengths[i],
        });
    }
    Some(entries)
}

fn binary_search_entry(entries: &[DirEntry], tile_id: u64) -> Option<DirEntry> {
    if entries.is_empty() {
        return None;
    }
    let mut lo = 0isize;
    let mut hi = (entries.len() as isize) - 1;
    while lo <= hi {
        let mid = ((lo + hi) as usize) >> 1;
        let mid_id = entries[mid].tile_id;
        if mid_id < tile_id {
            lo = (mid as isize) + 1;
        } else if mid_id > tile_id {
            hi = (mid as isize) - 1;
        } else {
            return Some(entries[mid]);
        }
    }
    if hi >= 0 {
        Some(entries[hi as usize])
    } else {
        None
    }
}

impl OpenPmArchive {
    fn get_leaf_directory(&self, offset: u64, length: u32) -> Option<Arc<Vec<DirEntry>>> {
        if let Ok(mut cache) = self.leaf_cache.lock() {
            if let Some(pos) = cache.iter().position(|(k, _)| *k == offset) {
                let entry = cache.remove(pos);
                let arc = Arc::clone(&entry.1);
                cache.push(entry);
                return Some(arc);
            }
        }
        let raw = read_exact_at(
            &self.file,
            self.header.leaf_dirs_offset.checked_add(offset)?,
            length as usize,
        )?;
        let decoded = Arc::new(parse_directory(&raw, self.header.internal_compression)?);
        if let Ok(mut cache) = self.leaf_cache.lock() {
            cache.retain(|(k, _)| *k != offset);
            while cache.len() >= MAX_LEAF_CACHE {
                cache.remove(0);
            }
            cache.push((offset, Arc::clone(&decoded)));
        }
        Some(decoded)
    }

    fn lookup(&self, tile_id: u64) -> Option<Vec<u8>> {
        let mut leaf_holder: Option<Arc<Vec<DirEntry>>> = None;
        for _depth in 0..=3 {
            let entries: &[DirEntry] = match &leaf_holder {
                Some(arc) => arc.as_slice(),
                None => self.root_entries.as_slice(),
            };
            let entry = binary_search_entry(entries, tile_id)?;
            if entry.run_length == 0 {
                leaf_holder = Some(self.get_leaf_directory(entry.offset, entry.length)?);
            } else if tile_id < entry.tile_id.saturating_add(entry.run_length as u64) {
                return read_exact_at(
                    &self.file,
                    self.header.tile_data_offset.checked_add(entry.offset)?,
                    entry.length as usize,
                );
            } else {
                return None;
            }
        }
        None
    }
}

fn open_archive(path: &str, last_modified: i64, file_size: i64) -> Option<Arc<OpenPmArchive>> {
    let file = File::open(path).ok()?;
    let hdr_bytes = read_exact_at(&file, 0, HEADER_SIZE)?;
    let header = parse_header_bytes(&hdr_bytes)?;
    let root_len = usize::try_from(header.root_dir_length).ok()?;
    if root_len == 0 || root_len > 16 * 1024 * 1024 {
        return None;
    }
    let root_raw = read_exact_at(&file, header.root_dir_offset, root_len)?;
    let root_entries = parse_directory(&root_raw, header.internal_compression)?;
    Some(Arc::new(OpenPmArchive {
        last_modified,
        file_size,
        file,
        header,
        root_entries,
        leaf_cache: Mutex::new(Vec::with_capacity(MAX_LEAF_CACHE)),
    }))
}

fn get_or_open(path: &str, last_modified: i64, file_size: i64) -> Option<Arc<OpenPmArchive>> {
    if let Ok(cache) = archive_cache().lock() {
        if let Some(arc) = cache.get(path) {
            if arc.last_modified == last_modified && arc.file_size == file_size {
                return Some(Arc::clone(arc));
            }
        }
    }
    let opened = open_archive(path, last_modified, file_size)?;
    if let Ok(mut cache) = archive_cache().lock() {
        cache.insert(path.to_string(), Arc::clone(&opened));
    }
    Some(opened)
}

pub fn warm_archive(path: &str, last_modified: i64, file_size: i64) -> bool {
    get_or_open(path, last_modified, file_size).is_some()
}

pub fn lookup_tile(
    path: &str,
    last_modified: i64,
    file_size: i64,
    tile_id: u64,
) -> Result<Option<Vec<u8>>, ()> {
    let arc = get_or_open(path, last_modified, file_size).ok_or(())?;
    Ok(arc.lookup(tile_id))
}

#[inline]
pub fn is_gzip_bytes(bytes: &[u8]) -> bool {
    bytes.len() >= 2 && bytes[0] == 0x1F && bytes[1] == 0x8B
}

pub fn gunzip_bytes(bytes: &[u8]) -> Option<Vec<u8>> {
    let mut gz = GzDecoder::new(bytes);
    let mut out = Vec::with_capacity(bytes.len().saturating_mul(2));
    gz.read_to_end(&mut out).ok()?;
    Some(out)
}

/// Fused offline tile pipeline: PMTiles lookup -> GZIP inflate -> Kurdish MVT patch in one native call.
pub fn lookup_and_patch_tile(
    path: &str,
    last_modified: i64,
    file_size: i64,
    tile_id: u64,
    tile_is_gzipped: bool,
) -> Result<Option<Vec<u8>>, ()> {
    let arc = get_or_open(path, last_modified, file_size).ok_or(())?;
    let Some(raw) = arc.lookup(tile_id) else {
        return Ok(None);
    };
    let plain = if tile_is_gzipped || is_gzip_bytes(&raw) {
        gunzip_bytes(&raw).unwrap_or(raw)
    } else {
        raw
    };
    let final_bytes = crate::mvt::patch_tile(&plain).unwrap_or(plain);
    Ok(Some(final_bytes))
}

/// Fused online tile helper: inflates if gzipped, then patches Kurdish MVT labels.
/// Returns `None` if input was neither gzipped nor modified (zero-copy passthrough).
pub fn gunzip_and_patch_mvt(raw: &[u8]) -> Option<Vec<u8>> {
    if is_gzip_bytes(raw) {
        let plain = gunzip_bytes(raw)?;
        Some(crate::mvt::patch_tile(&plain).unwrap_or(plain))
    } else {
        crate::mvt::patch_tile(raw)
    }
}

pub fn evict_archive(path: &str) {
    if let Ok(mut cache) = archive_cache().lock() {
        cache.remove(path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Write;

    fn write_varint(out: &mut Vec<u8>, mut v: u64) {
        loop {
            if (v & !0x7Fu64) == 0 {
                out.push(v as u8);
                return;
            }
            out.push(((v & 0x7Fu64) | 0x80u64) as u8);
            v >>= 7;
        }
    }

    #[test]
    fn hilbert_zxy_matches_spec_vectors() {
        assert_eq!(zxy_to_tile_id(0, 0, 0), 0);
        assert_eq!(zxy_to_tile_id(1, 0, 0), 1);
        assert_eq!(zxy_to_tile_id(1, 0, 1), 2);
        assert_eq!(zxy_to_tile_id(1, 1, 1), 3);
        assert_eq!(zxy_to_tile_id(1, 1, 0), 4);
        assert_eq!(zxy_to_tile_id(2, 0, 0), 5);
        assert_eq!(zxy_to_tile_id(2, 1, 0), 6);
        assert_eq!(zxy_to_tile_id(2, 3, 2), 16);
        assert_eq!(zxy_to_tile_id(2, 3, 1), 17);
        assert_eq!(zxy_to_tile_id(2, 3, 0), 20);
    }

    #[test]
    fn reads_tile_from_gzipped_pmtiles_v3_archive() {
        let tile_payload = b"hello_mvt_payload";
        // Build 1-entry directory for tile_id=17, run_length=1, offset=0, length=tile_payload.len()
        let mut raw_dir = Vec::new();
        write_varint(&mut raw_dir, 1); // num_entries
        write_varint(&mut raw_dir, 17); // tile_id delta
        write_varint(&mut raw_dir, 1); // run_length
        write_varint(&mut raw_dir, tile_payload.len() as u64); // length
        write_varint(&mut raw_dir, 1); // offset + 1 -> offset = 0

        let mut gz_dir = Vec::new();
        {
            let mut enc = GzEncoder::new(&mut gz_dir, Compression::default());
            enc.write_all(&raw_dir).unwrap();
            enc.finish().unwrap();
        }

        let root_offset = HEADER_SIZE as u64;
        let root_len = gz_dir.len() as u64;
        let tile_data_offset = root_offset + root_len;

        let mut hdr = vec![0u8; HEADER_SIZE];
        hdr[0..7].copy_from_slice(b"PMTiles");
        hdr[7] = 3;
        hdr[8..16].copy_from_slice(&root_offset.to_le_bytes());
        hdr[16..24].copy_from_slice(&root_len.to_le_bytes());
        hdr[56..64].copy_from_slice(&tile_data_offset.to_le_bytes());
        hdr[64..72].copy_from_slice(&(tile_payload.len() as u64).to_le_bytes());
        hdr[97] = COMPRESSION_GZIP;
        hdr[98] = COMPRESSION_NONE;

        let tmp = std::env::temp_dir().join("terrasync_test_sample.pmtiles");
        {
            let mut f = File::create(&tmp).unwrap();
            f.write_all(&hdr).unwrap();
            f.write_all(&gz_dir).unwrap();
            f.write_all(tile_payload).unwrap();
        }
        let sz = std::fs::metadata(&tmp).unwrap().len() as i64;
        let path_str = tmp.to_str().unwrap();

        assert!(warm_archive(path_str, 1, sz));
        let found = lookup_tile(path_str, 1, sz, 17).unwrap().unwrap();
        assert_eq!(found, tile_payload);
        let miss = lookup_tile(path_str, 1, sz, 99).unwrap();
        assert!(miss.is_none());

        evict_archive(path_str);
        let _ = std::fs::remove_file(&tmp);
    }
}
