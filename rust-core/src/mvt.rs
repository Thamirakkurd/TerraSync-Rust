use crate::kurdish;

#[inline]
fn read_varint(buf: &[u8], mut pos: usize) -> Option<(u64, usize)> {
    let mut result = 0u64;
    let mut shift = 0u32;
    while pos < buf.len() && shift < 64 {
        let b = buf[pos];
        pos += 1;
        result |= ((b & 0x7F) as u64) << shift;
        if (b & 0x80) == 0 {
            return Some((result, pos));
        }
        shift += 7;
    }
    None
}

#[inline]
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

#[inline]
fn skip_scalar(buf: &[u8], pos: usize, wire_type: u64) -> Option<usize> {
    match wire_type {
        0 => read_varint(buf, pos).map(|(_, next)| next),
        1 => pos.checked_add(8),
        5 => pos.checked_add(4),
        _ => None,
    }
}

fn patch_value(data: &[u8], start: usize, end: usize) -> Option<Vec<u8>> {
    if start >= end || data[start] != 0x0A {
        return None;
    }
    let (len_u64, str_start) = read_varint(data, start + 1)?;
    let len = usize::try_from(len_u64).ok()?;
    let str_end = str_start.checked_add(len)?;
    if len == 0 || str_end != end {
        return None;
    }
    let original = std::str::from_utf8(&data[str_start..str_end]).ok()?;
    if !kurdish::needs_kurdish_shaping(original) {
        return None;
    }
    let shaped = kurdish::shape(original);
    if shaped == original {
        return None;
    }
    let shaped_bytes = shaped.as_bytes();
    let mut out = Vec::with_capacity(shaped_bytes.len() + 6);
    out.push(0x0A);
    write_varint(&mut out, shaped_bytes.len() as u64);
    out.extend_from_slice(shaped_bytes);
    Some(out)
}

fn patch_layer(data: &[u8], start: usize, end: usize) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity((end - start) + 32);
    let mut pos = start;
    let mut modified = false;

    while pos < end {
        let field_start = pos;
        let (tag, next_pos) = read_varint(data, pos)?;
        pos = next_pos;
        let field_num = tag >> 3;
        let wire_type = tag & 7;

        if wire_type == 2 {
            let (len_u64, body_start) = read_varint(data, pos)?;
            let len = usize::try_from(len_u64).ok()?;
            let body_end = body_start.checked_add(len)?;
            if body_end > end {
                return None;
            }
            pos = body_end;

            if field_num == 4 {
                if let Some(patched_val) = patch_value(data, body_start, body_end) {
                    modified = true;
                    write_varint(&mut out, tag);
                    write_varint(&mut out, patched_val.len() as u64);
                    out.extend_from_slice(&patched_val);
                } else {
                    out.extend_from_slice(&data[field_start..pos]);
                }
            } else {
                out.extend_from_slice(&data[field_start..pos]);
            }
        } else {
            pos = skip_scalar(data, pos, wire_type)?;
            if pos > end {
                return None;
            }
            out.extend_from_slice(&data[field_start..pos]);
        }
    }

    if modified { Some(out) } else { None }
}

/// Returns `Some(patched_bytes)` if any Kurdish label was rewritten, or `None` if unmodified.
pub fn patch_tile(data: &[u8]) -> Option<Vec<u8>> {
    if data.len() < 4 || !kurdish::contains_kurdish_utf8(data) {
        return None;
    }
    let mut out = Vec::with_capacity(data.len() + 64);
    let mut pos = 0;
    let end = data.len();
    let mut modified = false;

    while pos < end {
        let field_start = pos;
        let (tag, next_pos) = read_varint(data, pos)?;
        pos = next_pos;
        let field_num = tag >> 3;
        let wire_type = tag & 7;

        if wire_type == 2 {
            let (len_u64, body_start) = read_varint(data, pos)?;
            let len = usize::try_from(len_u64).ok()?;
            let body_end = body_start.checked_add(len)?;
            if body_end > end {
                return None;
            }
            pos = body_end;

            if field_num == 3 {
                if let Some(patched_layer) = patch_layer(data, body_start, body_end) {
                    modified = true;
                    write_varint(&mut out, tag);
                    write_varint(&mut out, patched_layer.len() as u64);
                    out.extend_from_slice(&patched_layer);
                } else {
                    out.extend_from_slice(&data[field_start..pos]);
                }
            } else {
                out.extend_from_slice(&data[field_start..pos]);
            }
        } else {
            pos = skip_scalar(data, pos, wire_type)?;
            if pos > end {
                return None;
            }
            out.extend_from_slice(&data[field_start..pos]);
        }
    }

    if modified { Some(out) } else { None }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build_mvt_with_value(s: &str) -> Vec<u8> {
        // Value { string_value (field 1) = s }
        let mut val = Vec::new();
        val.push(0x0A);
        write_varint(&mut val, s.len() as u64);
        val.extend_from_slice(s.as_bytes());

        // Layer { name (field 1) = "place", values (field 4) = val, version (field 15) = 2 }
        let mut layer = Vec::new();
        layer.push(0x0A);
        write_varint(&mut layer, 5);
        layer.extend_from_slice(b"place");
        layer.push((4 << 3) | 2);
        write_varint(&mut layer, val.len() as u64);
        layer.extend_from_slice(&val);
        layer.push((15 << 3) | 0);
        write_varint(&mut layer, 2);

        // Tile { layers (field 3) = layer }
        let mut tile = Vec::new();
        tile.push((3 << 3) | 2);
        write_varint(&mut tile, layer.len() as u64);
        tile.extend_from_slice(&layer);
        tile
    }

    #[test]
    fn skips_latin_and_patches_kurdish_mvt() {
        let latin = build_mvt_with_value("Akre City");
        assert!(patch_tile(&latin).is_none());

        let kurdish_tile = build_mvt_with_value("هەولێر");
        let patched = patch_tile(&kurdish_tile).expect("Kurdish tile must be patched");
        let expected_shaped = kurdish::shape("هەولێر");
        assert!(patched
            .windows(expected_shaped.len())
            .any(|w| w == expected_shaped.as_bytes()));
    }
}
