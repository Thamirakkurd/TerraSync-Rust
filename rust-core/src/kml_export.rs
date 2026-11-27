use std::collections::HashMap;
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

struct ExportPin<'a> {
    name: &'a str,
    description: &'a str,
    lat: f64,
    lng: f64,
    norm_status: &'a str,
    status_label: &'a str,
    issue_reason: &'a str,
    is_edited: bool,
    edited_by: &'a str,
    edited_at: i64,
    edit_history: &'a str,
    folder_path: &'a str,
}

struct ExportTrack<'a> {
    name: &'a str,
    coords: &'a str,
    folder_path: &'a str,
}

fn write_xml_escaped<W: Write>(w: &mut W, s: &str) -> std::io::Result<()> {
    for ch in s.chars() {
        match ch {
            '&' => w.write_all(b"&amp;")?,
            '<' => w.write_all(b"&lt;")?,
            '>' => w.write_all(b"&gt;")?,
            '"' => w.write_all(b"&quot;")?,
            '\'' => w.write_all(b"&apos;")?,
            '\t' | '\n' | '\r' => {
                let mut b = [0u8; 4];
                w.write_all(ch.encode_utf8(&mut b).as_bytes())?;
            }
            c if (c as u32) >= 0x20 => {
                let mut b = [0u8; 4];
                w.write_all(c.encode_utf8(&mut b).as_bytes())?;
            }
            _ => {}
        }
    }
    Ok(())
}

fn style_url_for_status(norm_status: &str) -> &'static str {
    match norm_status {
        "done" => "#placemark-green",
        "issue" => "#placemark-red",
        _ => "#placemark-orange",
    }
}

fn write_pin<W: Write>(w: &mut W, p: &ExportPin<'_>, indent: &str) -> std::io::Result<()> {
    let has_meaningful_edit = p.norm_status != "pending"
        || !p.issue_reason.trim().is_empty()
        || p.is_edited
        || !p.edited_by.trim().is_empty()
        || !p.edit_history.trim().is_empty();

    let mut desc_parts: Vec<String> = Vec::with_capacity(4);
    desc_parts.push(format!("Status: {}", p.status_label));
    if !p.issue_reason.trim().is_empty() {
        let note_label = if p.norm_status == "done" { "Done Note" } else { "Issue" };
        desc_parts.push(format!("{}: {}", note_label, p.issue_reason));
    }
    if !p.description.trim().is_empty() {
        desc_parts.push(p.description.to_string());
    }
    if p.is_edited && !p.edited_by.trim().is_empty() {
        desc_parts.push(format!("Edited by: {}", p.edited_by));
    }
    let desc_joined = desc_parts.join("\n");

    write!(w, "{}<Placemark>\n", indent)?;
    write!(w, "{}  <name>", indent)?;
    write_xml_escaped(w, p.name)?;
    w.write_all(b"</name>\n")?;

    write!(w, "{}  <styleUrl>{}</styleUrl>\n", indent, style_url_for_status(p.norm_status))?;

    write!(w, "{}  <description>", indent)?;
    write_xml_escaped(w, &desc_joined)?;
    w.write_all(b"</description>\n")?;

    write!(w, "{}  <ExtendedData>\n", indent)?;
    write!(w, "{}    <Data name=\"ts_status\"><value>", indent)?;
    write_xml_escaped(w, p.norm_status)?;
    w.write_all(b"</value></Data>\n")?;

    if !p.issue_reason.trim().is_empty() {
        write!(w, "{}    <Data name=\"ts_issue_reason\"><value>", indent)?;
        write_xml_escaped(w, p.issue_reason)?;
        w.write_all(b"</value></Data>\n")?;
    }

    write!(w, "{}    <Data name=\"ts_raw_description\"><value>", indent)?;
    write_xml_escaped(w, p.description)?;
    w.write_all(b"</value></Data>\n")?;

    if has_meaningful_edit {
        write!(w, "{}    <Data name=\"ts_is_edited\"><value>true</value></Data>\n", indent)?;
    }

    if !p.edited_by.trim().is_empty() {
        write!(w, "{}    <Data name=\"ts_edited_by\"><value>", indent)?;
        write_xml_escaped(w, p.edited_by)?;
        w.write_all(b"</value></Data>\n")?;
    }

    if p.edited_at > 0 {
        write!(w, "{}    <Data name=\"ts_edited_at\"><value>{}</value></Data>\n", indent, p.edited_at)?;
    }

    if !p.edit_history.trim().is_empty() {
        write!(w, "{}    <Data name=\"ts_edit_history\"><value>", indent)?;
        write_xml_escaped(w, p.edit_history)?;
        w.write_all(b"</value></Data>\n")?;
    }

    if !p.folder_path.trim().is_empty() {
        write!(w, "{}    <Data name=\"ts_folder_path\"><value>", indent)?;
        write_xml_escaped(w, p.folder_path)?;
        w.write_all(b"</value></Data>\n")?;
    }

    write!(w, "{}  </ExtendedData>\n", indent)?;
    write!(w, "{}  <Point><coordinates>{},{},0</coordinates></Point>\n", indent, p.lng, p.lat)?;
    write!(w, "{}</Placemark>\n", indent)?;
    Ok(())
}

fn write_track<W: Write>(w: &mut W, t: &ExportTrack<'_>, indent: &str) -> std::io::Result<()> {
    let valid_pts: Vec<&str> = t
        .coords
        .split(';')
        .map(|s| s.trim())
        .filter(|s| s.contains(','))
        .collect();
    if valid_pts.len() < 2 {
        return Ok(());
    }
    write!(w, "{}<Placemark>\n", indent)?;
    write!(w, "{}  <name>", indent)?;
    write_xml_escaped(w, t.name)?;
    w.write_all(b"</name>\n")?;

    if !t.folder_path.trim().is_empty() {
        write!(w, "{}  <ExtendedData><Data name=\"ts_folder_path\"><value>", indent)?;
        write_xml_escaped(w, t.folder_path)?;
        w.write_all(b"</value></Data></ExtendedData>\n")?;
    }

    write!(w, "{}  <LineString><coordinates>", indent)?;
    for (idx, pt) in valid_pts.iter().enumerate() {
        if idx > 0 {
            w.write_all(b" ")?;
        }
        w.write_all(pt.as_bytes())?;
        w.write_all(b",0")?;
    }
    w.write_all(b"</coordinates></LineString>\n")?;
    write!(w, "{}</Placemark>\n", indent)?;
    Ok(())
}

fn write_kml_from_payload<W: Write>(payload: &[u8], w: &mut W) -> Option<()> {
    let mut r = BinReader::new(payload);
    let export_title = r.read_str()?;

    let pin_count = r.read_u32()? as usize;
    let mut pins = Vec::with_capacity(pin_count.min(200_000));
    for _ in 0..pin_count {
        let name = r.read_str()?;
        let description = r.read_str()?;
        let lat = r.read_f64()?;
        let lng = r.read_f64()?;
        let norm_status = r.read_str()?;
        let status_label = r.read_str()?;
        let issue_reason = r.read_str()?;
        let is_edited = r.read_u8()? != 0;
        let edited_by = r.read_str()?;
        let edited_at = r.read_i64()?;
        let edit_history = r.read_str()?;
        let folder_path = r.read_str()?;
        pins.push(ExportPin {
            name,
            description,
            lat,
            lng,
            norm_status,
            status_label,
            issue_reason,
            is_edited,
            edited_by,
            edited_at,
            edit_history,
            folder_path,
        });
    }

    let track_count = r.read_u32()? as usize;
    let mut tracks = Vec::with_capacity(track_count.min(100_000));
    for _ in 0..track_count {
        let name = r.read_str()?;
        let coords = r.read_str()?;
        let folder_path = r.read_str()?;
        tracks.push(ExportTrack {
            name,
            coords,
            folder_path,
        });
    }

    w.write_all(b"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n").ok()?;
    w.write_all(b"<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n").ok()?;
    w.write_all(b"  <name>").ok()?;
    write_xml_escaped(w, export_title).ok()?;
    w.write_all(b"</name>\n").ok()?;
    w.write_all(b"  <Style id=\"placemark-green\">\n    <IconStyle><color>ff5ec522</color><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href></Icon></IconStyle>\n  </Style>\n").ok()?;
    w.write_all(b"  <Style id=\"placemark-red\">\n    <IconStyle><color>ff4444ef</color><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href></Icon></IconStyle>\n  </Style>\n").ok()?;
    w.write_all(b"  <Style id=\"placemark-orange\">\n    <IconStyle><color>ff0b9ef5</color><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/ylw-circle.png</href></Icon></IconStyle>\n  </Style>\n").ok()?;

    let has_folder_groups = pins.iter().any(|p| !p.folder_path.trim().is_empty())
        || tracks.iter().any(|t| !t.folder_path.trim().is_empty());

    if !has_folder_groups {
        for p in &pins {
            write_pin(w, p, "  ").ok()?;
        }
        for t in &tracks {
            write_track(w, t, "  ").ok()?;
        }
    } else {
        let mut ordered_paths: Vec<&str> = Vec::new();
        let mut pins_by_path: HashMap<&str, Vec<usize>> = HashMap::new();
        let mut tracks_by_path: HashMap<&str, Vec<usize>> = HashMap::new();

        for (i, p) in pins.iter().enumerate() {
            if !pins_by_path.contains_key(p.folder_path) {
                ordered_paths.push(p.folder_path);
            }
            pins_by_path.entry(p.folder_path).or_default().push(i);
        }
        for (i, t) in tracks.iter().enumerate() {
            if !pins_by_path.contains_key(t.folder_path) && !tracks_by_path.contains_key(t.folder_path) {
                ordered_paths.push(t.folder_path);
            }
            tracks_by_path.entry(t.folder_path).or_default().push(i);
        }

        if let Some(root_pins) = pins_by_path.get("") {
            for &idx in root_pins {
                write_pin(w, &pins[idx], "  ").ok()?;
            }
        }
        if let Some(root_tracks) = tracks_by_path.get("") {
            for &idx in root_tracks {
                write_track(w, &tracks[idx], "  ").ok()?;
            }
        }
        for fp in ordered_paths {
            if fp.trim().is_empty() {
                continue;
            }
            w.write_all(b"  <Folder>\n    <name>").ok()?;
            write_xml_escaped(w, fp).ok()?;
            w.write_all(b"</name>\n").ok()?;
            if let Some(fp_pins) = pins_by_path.get(fp) {
                for &idx in fp_pins {
                    write_pin(w, &pins[idx], "    ").ok()?;
                }
            }
            if let Some(fp_tracks) = tracks_by_path.get(fp) {
                for &idx in fp_tracks {
                    write_track(w, &tracks[idx], "    ").ok()?;
                }
            }
            w.write_all(b"  </Folder>\n").ok()?;
        }
    }

    w.write_all(b"</Document>\n</kml>\n").ok()?;
    w.flush().ok()?;
    Some(())
}

pub fn build_kml_bytes(payload: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(payload.len().saturating_mul(2).max(1024));
    write_kml_from_payload(payload, &mut out)?;
    Some(out)
}

pub fn export_kml_to_file(payload: &[u8], out_path: &str) -> bool {
    let Ok(file) = File::create(out_path) else {
        return false;
    };
    let mut writer = BufWriter::with_capacity(64 * 1024, file);
    write_kml_from_payload(payload, &mut writer).is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_str(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
        buf.extend_from_slice(s.as_bytes());
    }

    #[test]
    fn exported_kml_round_trips_through_native_kml_parser() {
        let mut payload = Vec::new();
        put_str(&mut payload, "Akre & Feeder 7");
        payload.extend_from_slice(&1u32.to_le_bytes()); // 1 pin
        put_str(&mut payload, "Meter #10 <A&B>");
        put_str(&mut payload, "Pole 4\nNear Mosque");
        payload.extend_from_slice(&36.7412f64.to_le_bytes());
        payload.extend_from_slice(&43.8935f64.to_le_bytes());
        put_str(&mut payload, "done");
        put_str(&mut payload, "Done");
        put_str(&mut payload, "Meter Linked");
        payload.push(1u8);
        put_str(&mut payload, "Thamir");
        payload.extend_from_slice(&1700000123i64.to_le_bytes());
        put_str(&mut payload, "[]");
        put_str(&mut payload, "Zone A / Block 2");

        payload.extend_from_slice(&1u32.to_le_bytes()); // 1 track
        put_str(&mut payload, "Cable 1");
        put_str(&mut payload, "43.89,36.74;43.9,36.75");
        put_str(&mut payload, "Zone A / Block 2");

        let xml = build_kml_bytes(&payload).expect("KML build should succeed");
        let reparsed = crate::kml::parse_kml_bytes(&xml);
        assert_eq!(reparsed.document_name, "Akre & Feeder 7");
        assert_eq!(reparsed.pins.len(), 1);
        let p = &reparsed.pins[0];
        assert_eq!(p.name, "Meter #10 <A&B>");
        assert_eq!(p.status, "done");
        assert_eq!(p.issue_reason, "Meter Linked");
        assert_eq!(p.edited_by, "Thamir");
        assert_eq!(p.edited_at, 1700000123);
        assert_eq!(p.folder_path, "Zone A / Block 2");
        assert_eq!(reparsed.tracks.len(), 1);
        assert_eq!(reparsed.tracks[0].name, "Cable 1");
        assert_eq!(reparsed.tracks[0].folder_path, "Zone A / Block 2");
    }
}
