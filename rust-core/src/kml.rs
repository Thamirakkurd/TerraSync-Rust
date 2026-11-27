use quick_xml::events::{BytesStart, Event};
use quick_xml::reader::Reader;
use std::collections::HashMap;
use std::fs::File;
use std::io::Read;

pub const MAX_KML_BYTES: usize = 25 * 1024 * 1024;

const IMPLICIT_REASON_KEYS: &[&str] = &[
    "meter linked",
    "inaccessible",
    "no bill",
    "need electricity",
    "customer refused / no access",
    "wrong location / coordinates",
    "closed / nobody home",
    "safety hazard / network fault",
];

const GENERIC_META_KEYS: &[&str] = &[
    "status",
    "pin_status",
    "issue",
    "issue_reason",
    "done_note",
    "status_note",
    "edited_by",
    "edited_at",
    "edit_history",
];

#[derive(Debug, Default, Clone, PartialEq)]
pub struct NativePin {
    pub name: String,
    pub description: String,
    pub lat: f64,
    pub lng: f64,
    pub status: String,
    pub issue_reason: String,
    pub is_edited: bool,
    pub edited_by: String,
    pub edited_at: i64,
    pub edit_history: String,
    pub folder_path: String,
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct NativeTrack {
    pub name: String,
    pub coords: String,
    pub folder_path: String,
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct ParsedKmlData {
    pub document_name: String,
    pub pins: Vec<NativePin>,
    pub tracks: Vec<NativeTrack>,
}

pub fn read_bounded_file(path: &str) -> Option<Vec<u8>> {
    let f = File::open(path).ok()?;
    let mut out = Vec::new();
    let mut limited = f.take((MAX_KML_BYTES + 1) as u64);
    limited.read_to_end(&mut out).ok()?;
    if out.len() > MAX_KML_BYTES {
        return None;
    }
    Some(out)
}

pub fn parse_kml_bytes(raw: &[u8]) -> ParsedKmlData {
    if raw.len() > MAX_KML_BYTES {
        return ParsedKmlData::default();
    }
    let trimmed = if raw.len() >= 3 && raw[0] == 0xEF && raw[1] == 0xBB && raw[2] == 0xBF {
        &raw[3..]
    } else {
        raw
    };
    let xml_str = String::from_utf8_lossy(trimmed);
    if let Ok(parsed) = parse_kml_xml(&xml_str) {
        return parsed;
    }
    let sanitized = sanitize_kml_xml(&xml_str);
    parse_kml_xml(&sanitized).unwrap_or_default()
}

pub fn sanitize_kml_xml(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 64);
    let bytes = input.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if b < 0x20 && b != b'\t' && b != b'\n' && b != b'\r' {
            i += 1;
            continue;
        }
        if b == b'&' {
            if is_valid_xml_entity(&input[i..]) {
                out.push('&');
            } else {
                out.push_str("&amp;");
            }
            i += 1;
            continue;
        }
        let ch = input[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn is_valid_xml_entity(s: &str) -> bool {
    if !s.starts_with('&') {
        return false;
    }
    let rest = &s[1..];
    for known in &["amp;", "lt;", "gt;", "quot;", "apos;"] {
        if rest.starts_with(known) {
            return true;
        }
    }
    if let Some(after_hash) = rest.strip_prefix('#') {
        if let Some(hex) = after_hash.strip_prefix('x') {
            let mut count = 0usize;
            for b in hex.bytes() {
                if b == b';' {
                    return count > 0;
                }
                if !b.is_ascii_hexdigit() {
                    return false;
                }
                count += 1;
                if count > 10 {
                    return false;
                }
            }
            return false;
        } else {
            let mut count = 0usize;
            for b in after_hash.bytes() {
                if b == b';' {
                    return count > 0;
                }
                if !b.is_ascii_digit() {
                    return false;
                }
                count += 1;
                if count > 10 {
                    return false;
                }
            }
            return false;
        }
    }
    false
}

fn local_tag_lower(name: &[u8]) -> String {
    let s = std::str::from_utf8(name).unwrap_or("");
    s.rsplit(':').next().unwrap_or(s).to_ascii_lowercase()
}

fn get_attr_ignore_case(e: &BytesStart<'_>, target: &str) -> String {
    for attr in e.attributes().flatten() {
        let key_str = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
        let local = key_str.rsplit(':').next().unwrap_or(key_str);
        if local.eq_ignore_ascii_case(target) {
            if let Ok(val) = attr.unescape_value() {
                return val.trim().to_string();
            }
            return String::from_utf8_lossy(&attr.value).trim().to_string();
        }
    }
    String::new()
}

fn normalize_status(raw: &str) -> Option<&'static str> {
    let clean = raw.trim().to_lowercase();
    if clean.is_empty() {
        return None;
    }
    if clean == "done"
        || clean.starts_with("done:")
        || clean.starts_with("done ")
        || clean == "completed"
        || clean == "complete"
        || clean == "finished"
        || clean == "meter linked"
        || clean == "inaccessible"
        || clean == "no bill"
    {
        return Some("done");
    }
    if clean == "issue"
        || clean.starts_with("issue:")
        || clean.starts_with("issue ")
        || clean == "problem"
        || clean == "error"
        || clean == "need electricity"
        || clean == "customer refused / no access"
        || clean == "wrong location / coordinates"
        || clean == "closed / nobody home"
        || clean == "safety hazard / network fault"
    {
        return Some("issue");
    }
    if clean == "pending"
        || clean.starts_with("pending:")
        || clean.starts_with("pending ")
        || clean == "todo"
        || clean == "open"
    {
        return Some("pending");
    }
    None
}

fn extract_implicit_reason_from_status(raw: Option<&str>) -> String {
    let trimmed = raw.unwrap_or("").trim();
    let low = trimmed.to_lowercase();
    if low.starts_with("done:") || low.starts_with("issue:") {
        if let Some((_, after)) = trimmed.split_once(':') {
            return after.trim().to_string();
        }
        return String::new();
    }
    if IMPLICIT_REASON_KEYS.contains(&low.as_str()) {
        return trimmed.to_string();
    }
    String::new()
}

fn status_from_style_url(style_url: &str) -> Option<&'static str> {
    let low = style_url.trim().to_lowercase();
    if low.is_empty() {
        return None;
    }
    if low.contains("green")
        || low.contains("grn")
        || low.contains("done")
        || low.contains("0f9d58")
        || low.contains("22c55e")
    {
        return Some("done");
    }
    if low.contains("red")
        || low.contains("issue")
        || low.contains("db4436")
        || low.contains("ef4444")
    {
        return Some("issue");
    }
    if low.contains("orange")
        || low.contains("yellow")
        || low.contains("ylw")
        || low.contains("amber")
        || low.contains("pending")
    {
        return Some("pending");
    }
    None
}

fn strip_html(raw: &str) -> String {
    if !raw.contains('<') && !raw.contains('&') {
        return raw.to_string();
    }
    let mut out = String::with_capacity(raw.len());
    let mut i = 0;
    while i < raw.len() {
        if raw.as_bytes()[i] == b'<' {
            if let Some(rel_end) = raw[i + 1..].find('>') {
                if rel_end > 0 {
                    let inner = &raw[i + 1..i + 1 + rel_end];
                    if is_html_br(inner) || is_html_block_end(inner) {
                        out.push('\n');
                    }
                    i += rel_end + 2;
                    continue;
                }
            }
        }
        let ch = raw[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
}

fn is_html_br(inner: &str) -> bool {
    if inner.len() < 2 {
        return false;
    }
    let b = inner.as_bytes();
    if !b[0].eq_ignore_ascii_case(&b'b') || !b[1].eq_ignore_ascii_case(&b'r') {
        return false;
    }
    let rest = inner[2..].trim_start();
    rest.is_empty() || rest == "/"
}

fn is_html_block_end(inner: &str) -> bool {
    let Some(after) = inner.strip_prefix('/') else {
        return false;
    };
    after.eq_ignore_ascii_case("p")
        || after.eq_ignore_ascii_case("div")
        || after.eq_ignore_ascii_case("li")
        || after.eq_ignore_ascii_case("tr")
}

fn parse_coordinates(raw: &str) -> Vec<(f64, f64)> {
    let mut out = Vec::new();
    for token in raw.split_whitespace() {
        if let Some(c1) = token.find(',') {
            if c1 > 0 && c1 + 1 < token.len() {
                let rest = &token[c1 + 1..];
                let lat_str = match rest.find(',') {
                    Some(c2) => &rest[..c2],
                    None => rest,
                };
                if let (Ok(lng), Ok(lat)) = (token[..c1].parse::<f64>(), lat_str.parse::<f64>()) {
                    if lat.is_finite()
                        && lng.is_finite()
                        && (-90.0..=90.0).contains(&lat)
                        && (-180.0..=180.0).contains(&lng)
                    {
                        out.push((lng, lat));
                    }
                }
            }
        }
    }
    out
}

fn format_coord_f64(v: f64) -> String {
    if v.fract() == 0.0 && v.abs() < 1e15 {
        format!("{:.1}", v)
    } else {
        v.to_string()
    }
}

#[derive(Default)]
struct ParserState {
    pins: Vec<NativePin>,
    tracks: Vec<NativeTrack>,
    doc_name: String,

    in_placemark: bool,
    in_style: bool,
    in_point: bool,
    line_depth: usize,
    in_description: bool,
    in_extended_data: bool,
    current_data_key: String,
    active_tag: String,
    text_buf: String,
    desc_buf: String,
    folder_stack: Vec<String>,
    folder_named: Vec<bool>,

    pm_name: String,
    pm_desc: String,
    pm_style_url: String,
    pm_extra: Vec<String>,
    pm_meta: HashMap<String, String>,
    pm_generic: HashMap<String, String>,
    pm_points: Vec<(f64, f64)>,
    pm_lines: Vec<Vec<(f64, f64)>>,
}

impl ParserState {
    fn on_start(&mut self, e: &BytesStart<'_>) {
        let tag = local_tag_lower(e.name().as_ref());
        if self.in_description {
            if matches!(tag.as_str(), "br" | "p" | "div" | "li" | "tr") {
                self.desc_buf.push('\n');
            }
            return;
        }
        self.active_tag = tag.clone();
        self.text_buf.clear();
        match tag.as_str() {
            "style" | "stylemap" => self.in_style = true,
            "folder" => {
                self.folder_stack.push(String::new());
                self.folder_named.push(false);
            }
            "placemark" => {
                self.in_placemark = true;
                self.in_point = false;
                self.line_depth = 0;
                self.in_description = false;
                self.in_extended_data = false;
                self.current_data_key.clear();
                self.pm_name.clear();
                self.pm_desc.clear();
                self.pm_style_url.clear();
                self.desc_buf.clear();
                self.pm_extra.clear();
                self.pm_meta.clear();
                self.pm_generic.clear();
                self.pm_points.clear();
                self.pm_lines.clear();
            }
            "description" => {
                if self.in_placemark && !self.in_extended_data {
                    self.in_description = true;
                    self.desc_buf.clear();
                }
            }
            "point" => self.in_point = true,
            "linestring" | "polygon" | "linearring" => self.line_depth += 1,
            "extendeddata" => self.in_extended_data = true,
            "data" | "simpledata" => {
                if self.in_placemark {
                    self.current_data_key = get_attr_ignore_case(e, "name");
                }
            }
            _ => {}
        }
    }

    fn on_text(&mut self, text: &str) {
        if self.in_description {
            self.desc_buf.push_str(text);
        } else if (!self.in_placemark && !self.in_style && self.active_tag == "name")
            || (self.in_placemark
                && matches!(
                    self.active_tag.as_str(),
                    "name" | "styleurl" | "coordinates" | "value" | "simpledata"
                ))
        {
            self.text_buf.push_str(text);
        }
    }

    fn on_end(&mut self, tag: &str) {
        if self.in_description {
            if tag == "description" {
                self.in_description = false;
                if self.pm_desc.trim().is_empty() {
                    self.pm_desc = self.desc_buf.trim().to_string();
                }
                self.active_tag.clear();
            } else if matches!(tag, "p" | "div" | "li" | "tr") {
                self.desc_buf.push('\n');
            }
            return;
        }

        if tag == "style" || tag == "stylemap" {
            self.in_style = false;
        } else if tag == "folder" && !self.in_placemark {
            self.folder_stack.pop();
            self.folder_named.pop();
        } else if !self.in_placemark && !self.in_style && tag == "name" {
            let nm = self.text_buf.trim().to_string();
            if let (Some(last_folder), Some(last_named)) =
                (self.folder_stack.last_mut(), self.folder_named.last_mut())
            {
                if !*last_named {
                    *last_folder = nm;
                    *last_named = true;
                } else if self.doc_name.trim().is_empty() {
                    self.doc_name = nm;
                }
            } else if self.doc_name.trim().is_empty() {
                self.doc_name = nm;
            }
        } else if self.in_placemark {
            match tag {
                "name" => {
                    if !self.in_extended_data && self.pm_name.trim().is_empty() {
                        self.pm_name = self.text_buf.trim().to_string();
                    }
                }
                "styleurl" => {
                    if !self.in_extended_data && self.pm_style_url.trim().is_empty() {
                        self.pm_style_url = self.text_buf.trim().to_string();
                    }
                }
                "value" | "simpledata" => {
                    let v = self.text_buf.trim().to_string();
                    if !self.current_data_key.trim().is_empty() {
                        let key_low = self.current_data_key.to_lowercase().replace(' ', "_");
                        if key_low == "ts_raw_description" {
                            self.pm_meta.insert(key_low, v);
                        } else if !v.is_empty() {
                            if key_low.starts_with("ts_") {
                                self.pm_meta.insert(key_low, v);
                            } else if GENERIC_META_KEYS.contains(&key_low.as_str()) {
                                self.pm_generic.insert(key_low, v);
                            } else {
                                self.pm_extra
                                    .push(format!("{}: {}", self.current_data_key, v));
                            }
                        }
                    }
                    if tag == "simpledata" {
                        self.current_data_key.clear();
                    }
                }
                "data" => self.current_data_key.clear(),
                "extendeddata" => self.in_extended_data = false,
                "coordinates" => {
                    let pts = parse_coordinates(&self.text_buf);
                    if self.in_point && !pts.is_empty() {
                        self.pm_points.push(pts[0]);
                    } else if self.line_depth > 0 && pts.len() >= 2 {
                        self.pm_lines.push(pts);
                    }
                }
                "point" => self.in_point = false,
                "linestring" | "polygon" | "linearring" => {
                    self.line_depth = self.line_depth.saturating_sub(1);
                }
                "placemark" => self.finish_placemark(),
                _ => {}
            }
        }

        if !self.in_description {
            self.active_tag.clear();
        }
    }

    fn finish_placemark(&mut self) {
        let raw_status_field = self
            .pm_meta
            .get("ts_status")
            .or_else(|| self.pm_generic.get("status"))
            .or_else(|| self.pm_generic.get("pin_status"))
            .cloned();

        let mut restored_status = raw_status_field
            .as_deref()
            .and_then(normalize_status);

        let mut restored_reason = self
            .pm_meta
            .get("ts_issue_reason")
            .or_else(|| self.pm_generic.get("done_note"))
            .or_else(|| self.pm_generic.get("issue_reason"))
            .or_else(|| self.pm_generic.get("status_note"))
            .or_else(|| self.pm_generic.get("issue"))
            .cloned()
            .unwrap_or_else(|| extract_implicit_reason_from_status(raw_status_field.as_deref()));

        if restored_status.is_none() {
            if self
                .pm_generic
                .get("done_note")
                .map(|s| !s.trim().is_empty())
                .unwrap_or(false)
            {
                restored_status = Some("done");
            } else if self
                .pm_generic
                .get("issue_reason")
                .map(|s| !s.trim().is_empty())
                .unwrap_or(false)
                || self
                    .pm_generic
                    .get("issue")
                    .map(|s| !s.trim().is_empty())
                    .unwrap_or(false)
            {
                restored_status = Some("issue");
            }
        }

        let mut restored_edited_by = self
            .pm_meta
            .get("ts_edited_by")
            .or_else(|| self.pm_generic.get("edited_by"))
            .cloned()
            .unwrap_or_default();

        let restored_edited_at = self
            .pm_meta
            .get("ts_edited_at")
            .and_then(|s| s.parse::<i64>().ok())
            .or_else(|| {
                self.pm_generic
                    .get("edited_at")
                    .and_then(|s| s.parse::<i64>().ok())
            })
            .unwrap_or(0);

        let restored_history = self
            .pm_meta
            .get("ts_edit_history")
            .or_else(|| self.pm_generic.get("edit_history"))
            .cloned()
            .unwrap_or_default();

        let mut restored_is_edited = self
            .pm_meta
            .get("ts_is_edited")
            .map(|s| s.eq_ignore_ascii_case("true"))
            .unwrap_or(false);

        let raw_desc_from_meta = self.pm_meta.get("ts_raw_description").cloned();

        let resolved_folder_path = self
            .pm_meta
            .get("ts_folder_path")
            .filter(|s| !s.trim().is_empty())
            .cloned()
            .unwrap_or_else(|| {
                self.folder_stack
                    .iter()
                    .filter(|s| !s.trim().is_empty())
                    .cloned()
                    .collect::<Vec<_>>()
                    .join(" / ")
            });

        let mut cleaned_desc_lines: Vec<String> = Vec::new();
        if let Some(raw_desc) = raw_desc_from_meta {
            if !raw_desc.trim().is_empty() {
                cleaned_desc_lines.push(raw_desc);
            }
        } else if !self.pm_desc.trim().is_empty() {
            let stripped = strip_html(&self.pm_desc);
            for line in stripped.lines() {
                let trimmed = line.trim();
                let low = trimmed.to_lowercase();
                if low.starts_with("status:") {
                    if restored_status.is_none() {
                        if let Some((_, after)) = trimmed.split_once(':') {
                            restored_status = normalize_status(after);
                        }
                    }
                } else if low.starts_with("done note:") {
                    if let Some((_, after)) = trimmed.split_once(':') {
                        let note_val = after.trim();
                        if restored_reason.trim().is_empty() && !note_val.is_empty() {
                            restored_reason = note_val.to_string();
                        }
                    }
                    if restored_status.is_none() {
                        restored_status = Some("done");
                    }
                } else if low.starts_with("issue reason:") || low.starts_with("issue:") {
                    if let Some((_, after)) = trimmed.split_once(':') {
                        let issue_val = after.trim();
                        if restored_reason.trim().is_empty() && !issue_val.is_empty() {
                            restored_reason = issue_val.to_string();
                        }
                    }
                    if restored_status.is_none() {
                        restored_status = Some("issue");
                    }
                } else if low.starts_with("edited by:") {
                    if restored_edited_by.trim().is_empty() {
                        if let Some((_, after)) = trimmed.split_once(':') {
                            restored_edited_by = after.trim().to_string();
                            restored_is_edited = true;
                        }
                    }
                } else if !trimmed.is_empty() {
                    cleaned_desc_lines.push(line.to_string());
                }
            }
        }

        if restored_status.is_none() {
            restored_status = status_from_style_url(&self.pm_style_url);
        }

        let final_status = restored_status.unwrap_or("pending").to_string();
        if final_status != "pending"
            || !restored_reason.trim().is_empty()
            || !restored_edited_by.trim().is_empty()
            || !restored_history.trim().is_empty()
        {
            restored_is_edited = true;
        }

        let mut full_desc = String::new();
        let base_desc = cleaned_desc_lines.join("\n");
        let base_desc_trim = base_desc.trim();
        if !base_desc_trim.is_empty() {
            full_desc.push_str(base_desc_trim);
        }
        if !self.pm_extra.is_empty() {
            if !full_desc.is_empty() {
                full_desc.push('\n');
            }
            full_desc.push_str(&self.pm_extra.join("\n"));
        }

        let base_pin_name = if self.pm_name.trim().is_empty() {
            format!("Pin {}", self.pins.len() + 1)
        } else {
            self.pm_name.clone()
        };
        let pt_total = self.pm_points.len();
        for (pt_idx, &(lng, lat)) in self.pm_points.iter().enumerate() {
            let pt_name = if pt_total > 1 {
                format!("{} ({}/{})", base_pin_name, pt_idx + 1, pt_total)
            } else {
                base_pin_name.clone()
            };
            self.pins.push(NativePin {
                name: pt_name,
                description: full_desc.clone(),
                lat,
                lng,
                status: final_status.clone(),
                issue_reason: restored_reason.clone(),
                is_edited: restored_is_edited,
                edited_by: restored_edited_by.clone(),
                edited_at: restored_edited_at,
                edit_history: restored_history.clone(),
                folder_path: resolved_folder_path.clone(),
            });
        }

        let base_track_name = if self.pm_name.trim().is_empty() {
            format!("Track {}", self.tracks.len() + 1)
        } else {
            self.pm_name.clone()
        };
        let line_total = self.pm_lines.len();
        for (line_idx, pts) in self.pm_lines.iter().enumerate() {
            let tr_name = if line_total > 1 {
                format!("{} ({}/{})", base_track_name, line_idx + 1, line_total)
            } else {
                base_track_name.clone()
            };
            let encoded = pts
                .iter()
                .map(|(lng, lat)| format!("{},{}", format_coord_f64(*lng), format_coord_f64(*lat)))
                .collect::<Vec<_>>()
                .join(";");
            self.tracks.push(NativeTrack {
                name: tr_name,
                coords: encoded,
                folder_path: resolved_folder_path.clone(),
            });
        }

        self.in_placemark = false;
        self.in_point = false;
        self.line_depth = 0;
        self.in_description = false;
        self.in_extended_data = false;
        self.current_data_key.clear();
    }
}

fn parse_kml_xml(xml_str: &str) -> Result<ParsedKmlData, ()> {
    let mut reader = Reader::from_str(xml_str);
    reader.config_mut().trim_text(false);
    let mut state = ParserState::default();

    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) => state.on_start(&e),
            Ok(Event::Empty(e)) => {
                let tag = local_tag_lower(e.name().as_ref());
                state.on_start(&e);
                state.on_end(&tag);
            }
            Ok(Event::End(e)) => {
                let tag = local_tag_lower(e.name().as_ref());
                state.on_end(&tag);
            }
            Ok(Event::Text(e)) => {
                let text = e.unescape().map_err(|_| ())?;
                state.on_text(&text);
            }
            Ok(Event::CData(e)) => {
                let text = String::from_utf8_lossy(e.as_ref());
                state.on_text(&text);
            }
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(_) => return Err(()),
        }
    }

    Ok(ParsedKmlData {
        document_name: state.doc_name,
        pins: state.pins,
        tracks: state.tracks,
    })
}

fn write_str(out: &mut Vec<u8>, s: &str) {
    let b = s.as_bytes();
    out.extend_from_slice(&(b.len() as u32).to_le_bytes());
    out.extend_from_slice(b);
}

pub fn encode_parsed_kml(parsed: &ParsedKmlData) -> Vec<u8> {
    let mut out = Vec::with_capacity(
        64 + parsed.pins.len() * 128 + parsed.tracks.len() * 96,
    );
    write_str(&mut out, &parsed.document_name);
    out.extend_from_slice(&(parsed.pins.len() as u32).to_le_bytes());
    for pin in &parsed.pins {
        write_str(&mut out, &pin.name);
        write_str(&mut out, &pin.description);
        out.extend_from_slice(&pin.lat.to_le_bytes());
        out.extend_from_slice(&pin.lng.to_le_bytes());
        write_str(&mut out, &pin.status);
        write_str(&mut out, &pin.issue_reason);
        out.push(if pin.is_edited { 1 } else { 0 });
        write_str(&mut out, &pin.edited_by);
        out.extend_from_slice(&pin.edited_at.to_le_bytes());
        write_str(&mut out, &pin.edit_history);
        write_str(&mut out, &pin.folder_path);
    }
    out.extend_from_slice(&(parsed.tracks.len() as u32).to_le_bytes());
    for tr in &parsed.tracks {
        write_str(&mut out, &tr.name);
        write_str(&mut out, &tr.coords);
        write_str(&mut out, &tr.folder_path);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_folders_pins_tracks_and_extended_data() {
        let kml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
          <Document>
            <name>Akre Feeder 12</name>
            <Style id="greenPin"><name>IgnoreStyleName</name></Style>
            <Folder>
              <name>Zone A</name>
              <Placemark>
                <name>Meter 101</name>
                <description><![CDATA[<p>Pole 42 &amp; Box</p><br/>Done Note: Meter Linked<br/>Edited By: Thamir]]></description>
                <ExtendedData>
                  <Data name="Feeder"><value>F-12</value></Data>
                  <Data name="ts_edited_at"><value>1700000000</value></Data>
                </ExtendedData>
                <Point><coordinates>43.8935,36.7412,0</coordinates></Point>
              </Placemark>
              <Placemark>
                <name>Cable Run</name>
                <LineString><coordinates>43.89,36.74,0 43.90,36.75,0</coordinates></LineString>
              </Placemark>
            </Folder>
          </Document>
        </kml>"#;

        let res = parse_kml_bytes(kml.as_bytes());
        assert_eq!(res.document_name, "Akre Feeder 12");
        assert_eq!(res.pins.len(), 1);
        let p = &res.pins[0];
        assert_eq!(p.name, "Meter 101");
        assert_eq!(p.status, "done");
        assert_eq!(p.issue_reason, "Meter Linked");
        assert_eq!(p.edited_by, "Thamir");
        assert_eq!(p.edited_at, 1700000000);
        assert!(p.is_edited);
        assert_eq!(p.folder_path, "Zone A");
        assert!(p.description.contains("Pole 42 & Box"));
        assert!(p.description.contains("Feeder: F-12"));
        assert!((p.lat - 36.7412).abs() < 1e-6);
        assert!((p.lng - 43.8935).abs() < 1e-6);

        assert_eq!(res.tracks.len(), 1);
        assert_eq!(res.tracks[0].name, "Cable Run");
        assert_eq!(res.tracks[0].coords, "43.89,36.74;43.9,36.75");
        assert_eq!(res.tracks[0].folder_path, "Zone A");
    }

    #[test]
    fn recovers_from_unescaped_ampersand_and_bom() {
        let mut raw = vec![0xEF, 0xBB, 0xBF];
        raw.extend_from_slice(
            b"<kml><Document><name>A & B</name><Placemark><name>Kurd \xD8\xA6\xD8\xA7\xDA\xA9\xD8\xB1\xDB\x8E &amp; Co & Sons</name><styleUrl>#red-issue</styleUrl><Point><coordinates>43.89,36.74</coordinates></Point></Placemark></Document></kml>",
        );
        let res = parse_kml_bytes(&raw);
        assert_eq!(res.document_name, "A & B");
        assert_eq!(res.pins.len(), 1);
        assert_eq!(res.pins[0].status, "issue");
        assert!(res.pins[0].name.contains("Co & Sons"));
        assert!(res.pins[0].name.contains("ئاکرێ"));
    }

    #[test]
    fn preserves_ts_raw_description_even_when_blank() {
        let kml = r#"<kml><Document><Placemark>
            <name>P1</name>
            <description>Status: done</description>
            <ExtendedData>
              <Data name="ts_raw_description"><value></value></Data>
              <Data name="ts_status"><value>Need Electricity</value></Data>
            </ExtendedData>
            <Point><coordinates>43.0,36.0</coordinates></Point>
        </Placemark></Document></kml>"#;
        let res = parse_kml_bytes(kml.as_bytes());
        assert_eq!(res.pins.len(), 1);
        assert_eq!(res.pins[0].status, "issue");
        assert_eq!(res.pins[0].issue_reason, "Need Electricity");
        assert_eq!(res.pins[0].description, "");
    }

    #[test]
    fn encodes_binary_wire_format() {
        let kml = r#"<kml><Document><name>Doc</name><Placemark><name>P</name><Point><coordinates>43.5,36.5</coordinates></Point></Placemark></Document></kml>"#;
        let parsed = parse_kml_bytes(kml.as_bytes());
        let bytes = encode_parsed_kml(&parsed);
        assert!(bytes.len() > 20);
        // First 4 bytes = length of "Doc" (3)
        assert_eq!(u32::from_le_bytes(bytes[0..4].try_into().unwrap()), 3);
    }
}
