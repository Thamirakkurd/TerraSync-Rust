use std::io::Write;

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

    fn read_i32(&mut self) -> Option<i32> {
        let slice = self.buf.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(i32::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_u32(&mut self) -> Option<u32> {
        let slice = self.buf.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(u32::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_f32(&mut self) -> Option<f32> {
        let slice = self.buf.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(f32::from_le_bytes(slice.try_into().ok()?))
    }

    fn read_f64(&mut self) -> Option<f64> {
        let slice = self.buf.get(self.pos..self.pos + 8)?;
        self.pos += 8;
        Some(f64::from_le_bytes(slice.try_into().ok()?))
    }
}

/// Decodes Android ARGB_8888 Terrarium PNG pixels into an (n+1)x(n+1) elevation Float grid.
pub fn decode_terrarium_grid(pixels: &[i32], w: usize, h: usize, n: usize) -> Option<Vec<f32>> {
    if w == 0 || h == 0 || n == 0 || pixels.len() < w * h {
        return None;
    }
    let stride = n + 1;
    let mut elev = vec![0.0f32; stride * stride];
    for gy in 0..=n {
        let py = ((gy * (h - 1)) / n).min(h - 1);
        let row_off = py * w;
        for gx in 0..=n {
            let px = ((gx * (w - 1)) / n).min(w - 1);
            let pixel = pixels[row_off + px] as u32;
            let r = ((pixel >> 16) & 0xFF) as f32;
            let g = ((pixel >> 8) & 0xFF) as f32;
            let b = (pixel & 0xFF) as f32;
            elev[gy * stride + gx] = (r * 256.0 + g + b / 256.0) - 32768.0;
        }
    }
    Some(elev)
}

/// Extracts marching-squares contours for a batch of Terrarium tile grids and emits
/// a single MapLibre GeoJSON FeatureCollection UTF-8 byte buffer (skips JVM Features & Gson).
/// Wire: [i32 step][i32 major_step][u32 grid_count]
///       grid_count * ([i32 z][i32 x][i32 y][i32 n][u32 elev_len][elev_len * f32])
pub fn build_contours_geojson_batch(payload: &[u8]) -> Option<Vec<u8>> {
    let mut r = BinReader::new(payload);
    let step = r.read_i32()?;
    let major_step = r.read_i32()?;
    let grid_count = r.read_u32()? as usize;

    let mut out = Vec::with_capacity(64 + grid_count * 16 * 1024);
    out.extend_from_slice(br#"{"type":"FeatureCollection","features":["#);
    let mut first_feat = true;

    for _ in 0..grid_count {
        let z = r.read_i32()?;
        let x = r.read_i32()?;
        let y = r.read_i32()?;
        let n = r.read_i32()?;
        let elev_len = r.read_u32()? as usize;
        if n <= 0 || elev_len > 1_000_000 {
            return None;
        }
        let mut elev = Vec::with_capacity(elev_len);
        for _ in 0..elev_len {
            elev.push(r.read_f32()?);
        }

        let lines = crate::contour::extract_contours(&elev, z, x, y, n as usize, step, major_step);
        let encoded = crate::contour::encode_contours(&lines);
        let mut lr = BinReader::new(&encoded);
        let line_count = lr.read_u32()? as usize;

        for _ in 0..line_count {
            let is_major = lr.read_u8()? != 0;
            let level = lr.read_i32()?;
            let pt_count = lr.read_u32()? as usize;
            if pt_count < 2 {
                for _ in 0..pt_count {
                    let _ = lr.read_f64()?;
                    let _ = lr.read_f64()?;
                }
                continue;
            }
            if !first_feat {
                out.push(b',');
            }
            first_feat = false;
            out.extend_from_slice(br#"{"type":"Feature","geometry":{"type":"LineString","coordinates":["#);
            for i in 0..pt_count {
                let lng = lr.read_f64()?;
                let lat = lr.read_f64()?;
                if i > 0 {
                    out.push(b',');
                }
                let _ = write!(&mut out, "[{lng:.6},{lat:.6}]");
            }
            let _ = write!(
                &mut out,
                r#"]}},"properties":{{"isMajor":{},"ele":"{level}"}}}}"#,
                if is_major { "true" } else { "false" }
            );
        }
    }

    out.extend_from_slice(b"]}");
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_terrarium_pixels_and_builds_geojson() {
        // R=131, G=232, B=0 -> 131*256 + 232 - 32768 = 1000.0m
        let p_high = (0xFFu32 << 24) | (131u32 << 16) | (232u32 << 8);
        // R=128, G=0, B=0 -> 0.0m
        let p_low = (0xFFu32 << 24) | (128u32 << 16);
        let pixels = vec![p_low as i32, p_high as i32, p_low as i32, p_high as i32];
        let grid = decode_terrarium_grid(&pixels, 2, 2, 1).unwrap();
        assert_eq!(grid.len(), 4);
        assert!((grid[0] - 0.0).abs() < 0.01);
        assert!((grid[1] - 1000.0).abs() < 0.01);

        let mut payload = Vec::new();
        payload.extend_from_slice(&200i32.to_le_bytes());
        payload.extend_from_slice(&500i32.to_le_bytes());
        payload.extend_from_slice(&1u32.to_le_bytes());
        payload.extend_from_slice(&11i32.to_le_bytes());
        payload.extend_from_slice(&1273i32.to_le_bytes());
        payload.extend_from_slice(&795i32.to_le_bytes());
        payload.extend_from_slice(&1i32.to_le_bytes());
        payload.extend_from_slice(&(grid.len() as u32).to_le_bytes());
        for v in grid {
            payload.extend_from_slice(&v.to_le_bytes());
        }
        let json = String::from_utf8(build_contours_geojson_batch(&payload).unwrap()).unwrap();
        assert!(json.starts_with("{\"type\":\"FeatureCollection\""));
        assert!(json.contains("\"LineString\""));
    }
}
