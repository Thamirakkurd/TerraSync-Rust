use std::fs::File;
use std::io::{BufWriter, Write};

struct BinReader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> BinReader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
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

fn write_csv_escaped<W: Write>(w: &mut W, raw: &str) -> std::io::Result<()> {
    w.write_all(b"\"")?;
    let mut first_char = true;
    for ch in raw.chars() {
        let needs_guard = first_char && matches!(ch, '=' | '+' | '-' | '@' | '\t');
        first_char = false;
        if needs_guard {
            w.write_all(b"'")?;
        }
        match ch {
            '\r' => w.write_all(b" ")?,
            '\n' => w.write_all(b" | ")?,
            '"' => w.write_all(b"\"\"")?,
            _ => {
                let mut tmp = [0u8; 4];
                let s = ch.encode_utf8(&mut tmp);
                w.write_all(s.as_bytes())?;
            }
        }
    }
    w.write_all(b"\"")
}

/// Streams UTF-8 BOM + Excel-safe CSV directly to `out_path`.
/// Wire: [u32 count] followed by count * ([str name][str status_label][str issue_reason][str desc][f64 lat][f64 lng][str edited_by][str time_str])
pub fn export_csv_file(payload: &[u8], out_path: &str) -> bool {
    let mut r = BinReader::new(payload);
    let Some(count) = r.read_u32().map(|v| v as usize) else {
        return false;
    };
    let Ok(file) = File::create(out_path) else {
        return false;
    };
    let mut w = BufWriter::with_capacity(64 * 1024, file);
    if w.write_all(&[0xEF, 0xBB, 0xBF]).is_err() {
        return false;
    }
    if w.write_all(b"Name,Status,StatusNote,Description,Latitude,Longitude,EditedBy,EditedAt\n")
        .is_err()
    {
        return false;
    }

    for _ in 0..count {
        let (
            Some(name),
            Some(status_lbl),
            Some(reason),
            Some(desc),
            Some(lat),
            Some(lng),
            Some(edited_by),
            Some(time_str),
        ) = (
            r.read_str(),
            r.read_str(),
            r.read_str(),
            r.read_str(),
            r.read_f64(),
            r.read_f64(),
            r.read_str(),
            r.read_str(),
        )
        else {
            return false;
        };

        let ok = write_csv_escaped(&mut w, name).is_ok()
            && w.write_all(b",").is_ok()
            && write_csv_escaped(&mut w, status_lbl).is_ok()
            && w.write_all(b",").is_ok()
            && write_csv_escaped(&mut w, reason).is_ok()
            && w.write_all(b",").is_ok()
            && write_csv_escaped(&mut w, desc).is_ok()
            && write!(&mut w, ",{lat},{lng},").is_ok()
            && write_csv_escaped(&mut w, edited_by).is_ok()
            && w.write_all(b",").is_ok()
            && write_csv_escaped(&mut w, time_str).is_ok()
            && w.write_all(b"\n").is_ok();
        if !ok {
            return false;
        }
    }

    w.flush().is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_str(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
        buf.extend_from_slice(s.as_bytes());
    }

    #[test]
    fn writes_bom_and_formula_guarded_kurdish_csv() {
        let mut buf = Vec::new();
        buf.extend_from_slice(&1u32.to_le_bytes());
        put_str(&mut buf, "=MTR \"ئاکرێ\"\nLine2");
        put_str(&mut buf, "Done");
        put_str(&mut buf, "Meter Linked");
        put_str(&mut buf, "Feeder 01");
        buf.extend_from_slice(&36.74_f64.to_le_bytes());
        buf.extend_from_slice(&43.89_f64.to_le_bytes());
        put_str(&mut buf, "Team 1");
        put_str(&mut buf, "2026-11-27 12:00");

        let tmp = std::env::temp_dir().join("terrasync_csv_test.csv");
        assert!(export_csv_file(&buf, tmp.to_str().unwrap()));
        let text = std::fs::read_to_string(&tmp).unwrap();
        let _ = std::fs::remove_file(&tmp);
        assert!(text.starts_with('\u{FEFF}'));
        assert!(text.contains("\"'=MTR \"\"ئاکرێ\"\" | Line2\""));
        assert!(text.contains(",36.74,43.89,"));
    }
}
