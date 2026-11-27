use std::collections::{HashMap, HashSet};
use std::fmt::Write as _;

const UNMAPPED_DEVICE_LABEL: &str = "Unnamed Device";
const OTHER_CUSTOM_NOTE_BUCKET: &str = "Other / Custom Note";

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
fn is_unicode_ws(c: char) -> bool {
    c.is_whitespace() || matches!(c, '\u{00A0}' | '\u{200B}' | '\u{200E}' | '\u{200F}' | '\u{FEFF}')
}

fn collapse_whitespace(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut prev_ws = true;
    for c in raw.chars() {
        if is_unicode_ws(c) {
            if !prev_ws {
                out.push(' ');
                prev_ws = true;
            }
        } else {
            out.push(c);
            prev_ws = false;
        }
    }
    if out.ends_with(' ') {
        out.pop();
    }
    out
}

#[inline]
fn flatten_single_line(raw: &str) -> String {
    collapse_whitespace(raw)
}

#[inline]
fn pin_dedup_key(name: &str, lat: f64, lng: f64) -> (String, i64, i64) {
    let norm = collapse_whitespace(name).to_lowercase();
    let q_lat = (lat * 100_000.0).round() as i64;
    let q_lng = (lng * 100_000.0).round() as i64;
    (norm, q_lat, q_lng)
}

#[inline]
fn parse_status_code(raw: &str) -> u8 {
    let s = raw.trim();
    if s.eq_ignore_ascii_case("done") || s.eq_ignore_ascii_case("completed") {
        0 // DONE
    } else if s.eq_ignore_ascii_case("issue") || s.eq_ignore_ascii_case("problem") {
        1 // ISSUE
    } else {
        2 // PENDING
    }
}

#[inline]
fn status_label(code: u8) -> &'static str {
    match code {
        0 => "Done",
        1 => "Issue",
        _ => "Pending",
    }
}

/// Parity with DailyReportGenerator.deduplicatePins.
/// Input wire: [u32 count] followed by count * ([str id][str name][f64 lat][f64 lng][u8 status_code][u8 worked][i64 edited_at][i64 updated_at])
/// Output wire: [u32 kept_count][u32 orig_indices...]
pub fn deduplicate_pins_indices(payload: &[u8]) -> Option<Vec<u8>> {
    let mut r = BinReader::new(payload);
    let count = r.read_u32()? as usize;

    struct Row<'a> {
        orig_idx: usize,
        id: &'a str,
        name: &'a str,
        lat: f64,
        lng: f64,
        status_rank: u8, // DONE=2, ISSUE=1, PENDING=0
        worked: u8,
        edited_at: i64,
        updated_at: i64,
    }

    let mut rows = Vec::with_capacity(count.min(250_000));
    for idx in 0..count {
        let id = r.read_str()?;
        let name = r.read_str()?;
        let lat = r.read_f64()?;
        let lng = r.read_f64()?;
        let status_code = r.read_u8()?;
        let worked = r.read_u8()?;
        let edited_at = r.read_i64()?;
        let updated_at = r.read_i64()?;
        let status_rank = match status_code { 0 => 2, 1 => 1, _ => 0 };
        rows.push(Row { orig_idx: idx, id, name, lat, lng, status_rank, worked, edited_at, updated_at });
    }

    rows.sort_by(|a, b| {
        b.worked
            .cmp(&a.worked)
            .then_with(|| b.edited_at.cmp(&a.edited_at))
            .then_with(|| b.status_rank.cmp(&a.status_rank))
            .then_with(|| b.updated_at.cmp(&a.updated_at))
            .then_with(|| a.id.cmp(b.id))
    });

    let mut seen: HashSet<(String, i64, i64)> = HashSet::with_capacity(count.min(250_000));
    let mut kept: Vec<u32> = Vec::with_capacity(count.min(250_000));
    for row in rows {
        let key = pin_dedup_key(row.name, row.lat, row.lng);
        if seen.insert(key) {
            kept.push(row.orig_idx as u32);
        }
    }

    let mut out = Vec::with_capacity(4 + kept.len() * 4);
    out.extend_from_slice(&(kept.len() as u32).to_le_bytes());
    for idx in kept { out.extend_from_slice(&idx.to_le_bytes()); }
    Some(out)
}

/// Parity with PinsListController.findDuplicateExtraIds.
/// Input wire: [u32 count] followed by count * ([str id][str name][f64 lat][f64 lng][u8 prio_rank][i64 edited_at][i64 updated_at])
/// Output wire: [u32 extra_count][u32 orig_indices...]
pub fn find_duplicate_extra_indices(payload: &[u8]) -> Option<Vec<u8>> {
    let mut r = BinReader::new(payload);
    let count = r.read_u32()? as usize;

    struct Row<'a> {
        orig_idx: usize,
        id: &'a str,
        prio: u8,
        edited_at: i64,
        updated_at: i64,
    }

    let mut groups: HashMap<(String, i64, i64), Vec<Row<'_>>> = HashMap::with_capacity(count.min(250_000));
    for idx in 0..count {
        let id = r.read_str()?;
        let name = r.read_str()?;
        let lat = r.read_f64()?;
        let lng = r.read_f64()?;
        let prio = r.read_u8()?;
        let edited_at = r.read_i64()?;
        let updated_at = r.read_i64()?;
        let key = pin_dedup_key(name, lat, lng);
        groups.entry(key).or_default().push(Row { orig_idx: idx, id, prio, edited_at, updated_at });
    }

    let mut extras: Vec<u32> = Vec::new();
    for mut grp in groups.into_values() {
        if grp.len() >= 2 {
            grp.sort_by(|a, b| {
                b.prio
                    .cmp(&a.prio)
                    .then_with(|| b.edited_at.cmp(&a.edited_at))
                    .then_with(|| b.updated_at.cmp(&a.updated_at))
                    .then_with(|| a.id.cmp(b.id))
            });
            for row in grp.into_iter().skip(1) {
                extras.push(row.orig_idx as u32);
            }
        }
    }

    let mut out = Vec::with_capacity(4 + extras.len() * 4);
    out.extend_from_slice(&(extras.len() as u32).to_le_bytes());
    for idx in extras { out.extend_from_slice(&idx.to_le_bytes()); }
    Some(out)
}

// --- Daily Field Report helpers ---

fn find_device_hex_id(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'#' {
            let start = i + 1;
            let mut end = start;
            while end < bytes.len() && bytes[end].is_ascii_hexdigit() && (end - start) < 16 {
                end += 1;
            }
            if end - start >= 4 {
                return Some(s[start..end].to_ascii_uppercase());
            }
        }
        i += 1;
    }
    None
}

fn match_paren_device(clean: &str) -> Option<(&str, &str)> {
    let trimmed = clean.trim_end();
    if !trimmed.ends_with(')') {
        return None;
    }
    let open_idx = trimmed.rfind('(')?;
    let prefix = trimmed[..open_idx].trim();
    let inside = &trimmed[open_idx + 1..trimmed.len() - 1];
    if prefix.is_empty() || inside.contains(')') {
        return None;
    }
    if find_device_hex_id(inside).is_some() {
        Some((prefix, inside))
    } else {
        None
    }
}

fn match_team_number(clean: &str) -> Option<&str> {
    let low_prefix = clean.get(..4)?;
    if !low_prefix.eq_ignore_ascii_case("team") {
        return None;
    }
    let rest = clean[4..].trim_start_matches(|c: char| c.is_whitespace() || c == '_' || c == '-');
    if !rest.is_empty() && rest.bytes().all(|b| b.is_ascii_digit()) {
        Some(rest)
    } else {
        None
    }
}

fn normalize_team_display_name(raw: &str) -> String {
    let clean = collapse_whitespace(raw);
    if clean.is_empty() {
        return String::new();
    }
    if let Some(num) = match_team_number(&clean) {
        return format!("Team {num}");
    }
    let mut out = String::with_capacity(clean.len());
    for (idx, word) in clean.split(' ').enumerate() {
        if idx > 0 { out.push(' '); }
        if word.is_empty() { continue; }
        let has_ascii = word.bytes().any(|b| b.is_ascii_alphabetic());
        if !has_ascii {
            out.push_str(word);
        } else {
            let all_upper = word.chars().all(|c| !c.is_alphabetic() || c.is_uppercase());
            let all_lower = word.chars().all(|c| !c.is_alphabetic() || c.is_lowercase());
            if all_upper || all_lower {
                let mut chars = word.chars();
                if let Some(first) = chars.next() {
                    out.push(first.to_ascii_uppercase());
                    for c in chars { out.push(c.to_ascii_lowercase()); }
                }
            } else {
                let mut chars = word.chars();
                if let Some(first) = chars.next() {
                    out.push(if first.is_ascii_lowercase() { first.to_ascii_uppercase() } else { first });
                    out.push_str(chars.as_str());
                }
            }
        }
    }
    out
}

fn team_group_key(normalized_display: &str) -> String {
    let clean = collapse_whitespace(normalized_display).to_lowercase();
    let mut out = String::with_capacity(clean.len());
    let mut prev_sep = true;
    for c in clean.chars() {
        if c.is_whitespace() || c == '_' || c == '-' {
            if !prev_sep {
                out.push(' ');
                prev_sep = true;
            }
        } else {
            out.push(c);
            prev_sep = false;
        }
    }
    out.trim().to_string()
}

fn is_raw_model_only(clean: &str) -> bool {
    let low = clean.to_ascii_lowercase();
    const BRANDS: &[&str] = &[
        "honor", "samsung", "xiaomi", "redmi", "poco", "oppo", "vivo", "realme", "huawei",
        "oneplus", "google pixel", "pixel", "iphone", "ipad", "motorola", "nokia", "tecno",
        "infinix", "sony", "lg", "asus", "lenovo", "nothing",
    ];
    for &b in BRANDS {
        if let Some(rest) = low.strip_prefix(b) {
            if rest.is_empty() || !rest.as_bytes()[0].is_ascii_alphanumeric() {
                return true;
            }
        }
    }
    for pref in ["sm-", "ptp-"] {
        if let Some(rest) = low.strip_prefix(pref) {
            if rest.as_bytes().first().map_or(false, |b| b.is_ascii_alphanumeric()) {
                return true;
            }
        }
    }
    false
}

fn is_raw_device_string(raw: &str) -> bool {
    let clean = collapse_whitespace(raw);
    if clean.is_empty() { return false; }
    if match_paren_device(&clean).is_some() { return false; }
    if clean.contains("• #") { return true; }
    if let Some(hex) = clean.strip_prefix('#') {
        if hex.len() >= 4 && hex.len() <= 16 && hex.bytes().all(|b| b.is_ascii_hexdigit()) {
            return true;
        }
    }
    if !clean.contains('(') && !clean.contains(')') {
        if let Some((_, after)) = clean.split_once('•') {
            let t = after.trim_start();
            if let Some(hex) = t.strip_prefix('#') {
                if hex.len() >= 4 && hex.len() <= 16 && hex.bytes().all(|b| b.is_ascii_hexdigit()) {
                    return true;
                }
            }
        }
    }
    is_raw_model_only(&clean)
}

fn is_team_signature_line(line: &str) -> bool {
    let mut s = line.trim();
    if s.len() >= 3 && s[..3].eq_ignore_ascii_case("by ") {
        s = s[3..].trim_start();
    }
    if match_team_number(s).is_some() { return true; }
    if (s.starts_with('t') || s.starts_with('T')) && s.len() >= 2 {
        let rest = s[1..].trim_start_matches(|c: char| c.is_whitespace() || c == '_' || c == '-');
        if !rest.is_empty() && rest.bytes().all(|b| b.is_ascii_digit()) {
            return true;
        }
    }
    const ROLES: &[&str] = &["supervisor", "admin", "inspector", "operator", "engineer", "manager", "lead", "driver"];
    ROLES.iter().any(|&r| s.eq_ignore_ascii_case(r))
}

fn strip_trailing_team_sig(line: &str) -> Option<(String, String)> {
    let trimmed = line.trim_end();
    let without_paren = trimmed.strip_suffix(')').unwrap_or(trimmed).trim_end();
    let low = without_paren.to_ascii_lowercase();

    // Check trailing "supervisor" or "team[\s_-]*\d+"
    let sig_start = if low.ends_with("supervisor") {
        without_paren.len() - "supervisor".len()
    } else {
        let mut d_start = without_paren.len();
        while d_start > 0 && without_paren.as_bytes()[d_start - 1].is_ascii_digit() {
            d_start -= 1;
        }
        if d_start == without_paren.len() { return None; }
        let mut sep_start = d_start;
        while sep_start > 0 && matches!(without_paren.as_bytes()[sep_start - 1], b' ' | b'\t' | b'_' | b'-') {
            sep_start -= 1;
        }
        if sep_start < 4 || !without_paren[sep_start - 4..sep_start].eq_ignore_ascii_case("team") {
            return None;
        }
        sep_start - 4
    };

    if sig_start == 0 { return None; }
    let sig = without_paren[sig_start..].trim();
    let before = &without_paren[..sig_start];
    let sep_trimmed = before.trim_end_matches(|c: char| {
        c.is_whitespace() || matches!(c, ',' | ';' | '•' | '|' | '/' | '-' | '–' | '—' | '(')
    });
    if sep_trimmed.len() == before.len() || sep_trimmed.is_empty() {
        return None;
    }
    Some((sep_trimmed.trim().to_string(), sig.to_string()))
}

const KNOWN_CATEGORY_RULES: &[(&str, &[&str])] = &[
    ("Meter Linked", &["meter linked", "meter link", "linked"]),
    ("Inaccessible", &["inaccessible", "inaccess"]),
    ("No Bill", &["no bill", "nobill", "no-bill"]),
    ("Need Electricity", &["need electricity", "needs electricity", "no electricity", "need electric", "no electric"]),
    ("Customer Refused / No Access", &["customer refused / no access", "customer refused", "refused", "no access"]),
    ("Wrong Location / Coordinates", &["wrong location / coordinates", "wrong location", "wrong coordinates", "wrong coords", "wrong gps"]),
    ("Closed / Nobody Home", &["closed / nobody home", "nobody home", "no one home", "closed"]),
    ("Safety Hazard / Network Fault", &["safety hazard / network fault", "safety hazard", "network fault", "hazard"]),
    ("Wrong Link", &["wrong link"]),
    ("Disconnected", &["disconnected"]),
    ("Replaced", &["replaced", "meter replaced"]),
    ("Damaged", &["damaged", "broken"]),
];

fn match_known_category(line: &str) -> Option<(&'static str, String)> {
    let clean = collapse_whitespace(line);
    let low = clean.to_ascii_lowercase();
    for &(canonical, aliases) in KNOWN_CATEGORY_RULES {
        for &alias in aliases {
            if low == alias {
                return Some((canonical, String::new()));
            }
            if low.starts_with(alias) && clean.len() > alias.len() {
                if let Some(next_ch) = clean[alias.len()..].chars().next() {
                    if matches!(next_ch, ':' | '-' | '–' | '—' | '/' | ',' | '|' | '•' | '(') {
                        let rem = clean[alias.len() + next_ch.len_utf8()..]
                            .trim()
                            .trim_end_matches(')')
                            .trim()
                            .to_string();
                        return Some((canonical, rem));
                    }
                }
            }
        }
    }
    None
}

fn parse_status_note(raw_reason: &str, status_code: u8) -> (String, String, Option<String>) {
    let default_bucket = match status_code {
        0 => "Completed",
        1 => "Other Issue",
        _ => "",
    };
    if raw_reason.trim().is_empty() {
        return (default_bucket.to_string(), String::new(), None);
    }

    let mut extracted_team: Option<String> = None;
    let mut meaningful: Vec<String> = Vec::new();
    for raw_line in raw_reason.split(|c| c == '\n' || c == '\r') {
        let line = collapse_whitespace(raw_line);
        if line.is_empty() { continue; }
        if is_team_signature_line(&line) {
            if extracted_team.is_none() {
                let s = if line.len() >= 3 && line[..3].eq_ignore_ascii_case("by ") { line[3..].trim() } else { line.as_str() };
                extracted_team = Some(normalize_team_display_name(s));
            }
            continue;
        }
        let mut cleaned_line = line;
        if let Some((without_sig, sig)) = strip_trailing_team_sig(&cleaned_line) {
            cleaned_line = without_sig;
            if extracted_team.is_none() {
                extracted_team = Some(normalize_team_display_name(&sig));
            }
        }
        if !cleaned_line.is_empty() && !is_team_signature_line(&cleaned_line) {
            meaningful.push(cleaned_line);
        }
    }

    if meaningful.is_empty() {
        return (default_bucket.to_string(), String::new(), extracted_team);
    }

    if let Some((canonical, rem)) = match_known_category(&meaningful[0]) {
        let mut parts: Vec<String> = Vec::new();
        let r = flatten_single_line(&rem);
        if !r.is_empty() && !is_team_signature_line(&r) { parts.push(r); }
        for rest in meaningful.iter().skip(1) {
            let f = flatten_single_line(rest);
            if !f.is_empty() && !is_team_signature_line(&f) { parts.push(f); }
        }
        return (canonical.to_string(), parts.join(" • "), extracted_team);
    }

    let full_custom = meaningful
        .iter()
        .map(|s| flatten_single_line(s))
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" • ");
    (OTHER_CUSTOM_NOTE_BUCKET.to_string(), full_custom, extracted_team)
}

fn latest_operator_from_history(edit_history: &str) -> String {
    let Some(first_line) = edit_history.lines().map(str::trim).find(|l| !l.is_empty()) else {
        return String::new();
    };
    let Some((_, after_bullet)) = first_line.split_once('•') else { return String::new(); };
    let ab = after_bullet.trim();
    if let Some((op, _)) = ab.split_once('→') {
        op.trim().to_string()
    } else if ab.len() >= 11 && ab[ab.len() - 11..].eq_ignore_ascii_case("edited text") {
        ab[..ab.len() - 11].trim().to_string()
    } else {
        String::new()
    }
}

fn detect_genuine_rework(edit_history: &str, current_status: u8) -> bool {
    if current_status == 0 || edit_history.trim().is_empty() {
        return false;
    }
    let mut iter = edit_history.lines().map(str::trim).filter(|l| l.contains('→'));
    if iter.next().is_none() { return false; }
    for line in iter {
        if let Some((_, rhs)) = line.split_once('→') {
            let head = rhs.trim().split(':').next().unwrap_or("");
            if parse_status_code(head) == 0 {
                return true;
            }
        }
    }
    false
}

fn truncate_clean(text: &str, max_len: usize) -> String {
    let clean = flatten_single_line(text);
    if clean.chars().count() <= max_len {
        return clean;
    }
    let take_n = max_len.saturating_sub(1).max(1);
    let prefix: String = clean.chars().take(take_n).collect();
    let mut out = prefix.trim_end().to_string();
    out.push('…');
    out
}

fn format_compact_pin_label(raw_name: &str) -> String {
    let clean = flatten_single_line(raw_name);
    if clean.is_empty() { return "Pin".to_string(); }
    // Split on " - ", " – ", " — ", " | "
    let mut segments: Vec<&str> = Vec::new();
    let mut start = 0usize;
    let bytes = clean.as_bytes();
    let mut i = 0usize;
    while i < clean.len() {
        if bytes[i] == b' ' {
            let mut j = i + 1;
            while j < clean.len() && bytes[j] == b' ' { j += 1; }
            if let Some(ch) = clean[j..].chars().next() {
                if matches!(ch, '-' | '–' | '—' | '|') {
                    let after_ch = j + ch.len_utf8();
                    if after_ch < clean.len() && bytes[after_ch] == b' ' {
                        let seg = clean[start..i].trim();
                        if !seg.is_empty() { segments.push(seg); }
                        let mut k = after_ch + 1;
                        while k < clean.len() && bytes[k] == b' ' { k += 1; }
                        start = k;
                        i = k;
                        continue;
                    }
                }
            }
        }
        let ch_len = clean[i..].chars().next().map_or(1, char::len_utf8);
        i += ch_len;
    }
    let tail = clean[start..].trim();
    if !tail.is_empty() { segments.push(tail); }

    if segments.len() >= 2 {
        let mut picked: Vec<&str> = Vec::with_capacity(3);
        let mut acc_len = 0usize;
        for seg in segments {
            if picked.len() >= 3 { break; }
            if picked.iter().any(|p| p.eq_ignore_ascii_case(seg)) { continue; }
            let seg_len = seg.chars().count();
            let add_len = if picked.is_empty() { seg_len } else { 3 + seg_len };
            if picked.len() >= 2 && acc_len + add_len > 42 { break; }
            picked.push(seg);
            acc_len += add_len;
        }
        return truncate_clean(&picked.join(" / "), 44);
    }
    truncate_clean(&clean, 40)
}

fn resolve_operator(
    raw_operator: &str,
    device_to_team: &HashMap<String, String>,
    fallback_hint: Option<&str>,
) -> Option<(String, String)> {
    let clean = collapse_whitespace(raw_operator);
    if clean.is_empty() {
        let hint = fallback_hint.map(normalize_team_display_name).unwrap_or_default();
        return if !hint.is_empty() { Some((team_group_key(&hint), hint)) } else { None };
    }
    if let Some((raw_name, _)) = match_paren_device(&clean) {
        if !raw_name.is_empty() && !is_raw_device_string(raw_name) {
            let norm = normalize_team_display_name(raw_name);
            return Some((team_group_key(&norm), norm));
        }
    }
    if let Some(dev_id) = find_device_hex_id(&clean) {
        let mapped = device_to_team.get(&dev_id).or_else(|| {
            if dev_id.len() > 4 { device_to_team.get(&dev_id[dev_id.len() - 4..]) } else { None }
        });
        if let Some(m) = mapped {
            if !m.trim().is_empty() {
                return Some((team_group_key(m), m.clone()));
            }
        }
    }
    if is_raw_device_string(&clean) {
        let hint = fallback_hint.map(normalize_team_display_name).unwrap_or_default();
        if !hint.is_empty() { return Some((team_group_key(&hint), hint)); }
        return Some((team_group_key(UNMAPPED_DEVICE_LABEL), UNMAPPED_DEVICE_LABEL.to_string()));
    }
    let stripped = clean.split(" (").next().unwrap_or(&clean).trim();
    let stripped = if stripped.is_empty() { clean.as_str() } else { stripped };
    if is_raw_device_string(stripped) {
        let hint = fallback_hint.map(normalize_team_display_name).unwrap_or_default();
        if !hint.is_empty() { return Some((team_group_key(&hint), hint)); }
        return Some((team_group_key(UNMAPPED_DEVICE_LABEL), UNMAPPED_DEVICE_LABEL.to_string()));
    }
    let norm = normalize_team_display_name(stripped);
    if norm.is_empty() { None } else { Some((team_group_key(&norm), norm)) }
}

#[inline]
fn plural(count: usize, singular: &str, plural_form: &str) -> String {
    format!("{} {}", count, if count == 1 { singular } else { plural_form })
}

/// Full Daily Field Report generator in Rust (parity with DailyReportGenerator.generateFromUnique).
pub fn build_daily_report(payload: &[u8]) -> Option<Vec<u8>> {
    let mut r = BinReader::new(payload);
    let folder_name = r.read_str()?;
    let now_str = r.read_str()?;
    let sync_badge = r.read_str()?;
    let dup_skipped = r.read_u32()? as usize;
    let outside_work_line = r.read_str()?;

    let alias_count = r.read_u32()? as usize;
    let mut device_to_team: HashMap<String, String> = HashMap::with_capacity(alias_count + 16);
    for _ in 0..alias_count {
        let raw_id = r.read_str()?;
        let raw_name = r.read_str()?;
        let id = raw_id.trim().trim_start_matches('#').to_ascii_uppercase();
        let name = normalize_team_display_name(raw_name);
        if !id.is_empty() && !name.is_empty() && !is_raw_device_string(&name) {
            if id.len() > 4 {
                let short_id = id[id.len() - 4..].to_string();
                device_to_team.entry(short_id).or_insert_with(|| name.clone());
            }
            device_to_team.insert(id, name);
        }
    }

    let inspect_stamp = |stamp: &str, map: &mut HashMap<String, String>| {
        let clean = collapse_whitespace(stamp);
        let Some((raw_name, inside)) = match_paren_device(&clean) else { return; };
        if raw_name.is_empty() || is_raw_device_string(raw_name) { return; }
        let Some(dev_id) = find_device_hex_id(inside) else { return; };
        let norm = normalize_team_display_name(raw_name);
        if !norm.is_empty() {
            if dev_id.len() > 4 {
                let short_id = dev_id[dev_id.len() - 4..].to_string();
                map.entry(short_id).or_insert_with(|| norm.clone());
            }
            map.insert(dev_id, norm);
        }
    };

    let known_count = r.read_u32()? as usize;
    for _ in 0..known_count {
        let edited_by = r.read_str()?;
        let edit_history = r.read_str()?;
        if !edited_by.trim().is_empty() {
            inspect_stamp(edited_by, &mut device_to_team);
        }
        if !edit_history.trim().is_empty() {
            for line in edit_history.lines() {
                if let Some((_, after_bullet)) = line.split_once('•') {
                    let ab = after_bullet.trim();
                    let op = if let Some((l, _)) = ab.split_once('→') {
                        l.trim()
                    } else if ab.len() >= 11 && ab[ab.len() - 11..].eq_ignore_ascii_case("edited text") {
                        ab[..ab.len() - 11].trim()
                    } else {
                        ""
                    };
                    if !op.is_empty() { inspect_stamp(op, &mut device_to_team); }
                }
            }
        }
    }

    let unique_count = r.read_u32()? as usize;
    if unique_count == 0 {
        let txt = format!(
            "*{} — Daily Field Report*\n_{} • {}_\n\nNo pins in this scope.",
            flatten_single_line(folder_name),
            now_str,
            sync_badge
        );
        return Some(txt.into_bytes());
    }

    let mut done_count = 0usize;
    let mut issue_count = 0usize;
    let mut pending_total = 0usize;
    let mut pending_touched = 0usize;

    let mut done_reasons: HashMap<String, usize> = HashMap::new();
    let mut issue_reasons: HashMap<String, usize> = HashMap::new();
    let mut field_notes: Vec<String> = Vec::new();

    struct TeamStats {
        display_name: String,
        done: usize,
        issue: usize,
        pending_touched: usize,
    }
    let mut team_map: HashMap<String, TeamStats> = HashMap::new();

    for _ in 0..unique_count {
        let name = r.read_str()?;
        let status_code = r.read_u8()?;
        let issue_reason = r.read_str()?;
        let is_edited = r.read_u8()? != 0;
        let edited_by = r.read_str()?;
        let edit_history = r.read_str()?;

        let raw_reason = issue_reason.trim();
        let (category, custom_note, embedded_sig) = parse_status_note(raw_reason, status_code);
        let fallback_op;
        let raw_op = if !edited_by.trim().is_empty() {
            edited_by.trim()
        } else {
            fallback_op = latest_operator_from_history(edit_history);
            fallback_op.as_str()
        };
        let team_kv = resolve_operator(raw_op, &device_to_team, embedded_sig.as_deref());

        match status_code {
            0 => {
                done_count += 1;
                let mut bucket = flatten_single_line(&category);
                if bucket.is_empty() { bucket = "Completed".to_string(); }
                *done_reasons.entry(bucket).or_insert(0) += 1;
                if let Some((k, lbl)) = &team_kv {
                    team_map.entry(k.clone()).or_insert_with(|| TeamStats {
                        display_name: lbl.clone(), done: 0, issue: 0, pending_touched: 0,
                    }).done += 1;
                }
            }
            1 => {
                issue_count += 1;
                let mut bucket = flatten_single_line(&category);
                if bucket.is_empty() { bucket = "Other Issue".to_string(); }
                *issue_reasons.entry(bucket).or_insert(0) += 1;
                if let Some((k, lbl)) = &team_kv {
                    team_map.entry(k.clone()).or_insert_with(|| TeamStats {
                        display_name: lbl.clone(), done: 0, issue: 0, pending_touched: 0,
                    }).issue += 1;
                }
            }
            _ => {
                pending_total += 1;
                let is_touched = is_edited || team_kv.is_some() || !raw_reason.is_empty() || !edit_history.trim().is_empty();
                if is_touched {
                    pending_touched += 1;
                    if let Some((k, lbl)) = &team_kv {
                        team_map.entry(k.clone()).or_insert_with(|| TeamStats {
                            display_name: lbl.clone(), done: 0, issue: 0, pending_touched: 0,
                        }).pending_touched += 1;
                    }
                }
            }
        }

        let is_rework = detect_genuine_rework(edit_history, status_code);
        if !custom_note.trim().is_empty() || is_rework {
            let team_prefix = match &team_kv {
                Some((_, lbl)) => format!("[{lbl}] "),
                None => String::new(),
            };
            let status_part = if is_rework && status_code == 2 {
                "Rework (Pending)".to_string()
            } else if is_rework {
                format!("Rework ({})", status_label(status_code))
            } else if category != OTHER_CUSTOM_NOTE_BUCKET
                && category != "Completed"
                && category != "Other Issue"
                && !category.trim().is_empty()
            {
                format!("{} ({})", status_label(status_code), category)
            } else {
                status_label(status_code).to_string()
            };
            let short_pin = format_compact_pin_label(name);
            let note_body = if !custom_note.trim().is_empty() {
                truncate_clean(&custom_note, 100)
            } else {
                "Reverted after Done".to_string()
            };
            field_notes.push(flatten_single_line(&format!("{team_prefix}{status_part} • {short_pin}: {note_body}")));
        }
    }

    let total = unique_count;
    let pending_untouched = pending_total.saturating_sub(pending_touched);
    let visited_count = done_count + issue_count;
    let pct_visited = ((visited_count as f64 * 100.0) / total as f64).round() as usize;
    let pct_done = ((done_count as f64 * 100.0) / total as f64).round() as usize;

    let mut out = String::with_capacity(2048);
    let _ = writeln!(out, "*{} — Daily Field Report*", flatten_single_line(folder_name));
    let _ = writeln!(out, "_{} • {}_", now_str, sync_badge);
    out.push('\n');
    let _ = writeln!(out, "*Summary*");
    if dup_skipped > 0 {
        let _ = writeln!(out, "• Total Pins: *{total}* ({} deduplicated)", plural(dup_skipped, "duplicate", "duplicates"));
    } else {
        let _ = writeln!(out, "• Total Pins: *{total}*");
    }
    let _ = writeln!(out, "• Visited Progress: *{pct_visited}%* ({visited_count} / {total})");
    let _ = writeln!(out, "• Done / Resolved: *{pct_done}%* ({done_count} / {total})");
    let _ = writeln!(out, "• ✓ Done: *{done_count}*");
    let _ = writeln!(out, "• ⚠ Issue: *{issue_count}*");
    let _ = writeln!(out, "• ⏳ Pending: *{pending_total}* ({pending_untouched} untouched, {pending_touched} touched)");
    if !outside_work_line.is_empty() {
        let _ = writeln!(out, "{outside_work_line}");
    }

    if done_count > 0 {
        out.push('\n');
        let _ = writeln!(out, "*✓ Done Breakdown ({done_count})*");
        let mut entries: Vec<(String, usize)> = done_reasons.into_iter().collect();
        entries.sort_by(|a, b| {
            (a.0 == OTHER_CUSTOM_NOTE_BUCKET)
                .cmp(&(b.0 == OTHER_CUSTOM_NOTE_BUCKET))
                .then_with(|| b.1.cmp(&a.1))
                .then_with(|| a.0.cmp(&b.0))
        });
        for (reason, cnt) in entries {
            let _ = writeln!(out, "• {}: *{cnt}*", flatten_single_line(&reason));
        }
    }

    if issue_count > 0 {
        out.push('\n');
        let _ = writeln!(out, "*⚠ Issue Breakdown ({issue_count})*");
        let mut entries: Vec<(String, usize)> = issue_reasons.into_iter().collect();
        entries.sort_by(|a, b| {
            (a.0 == OTHER_CUSTOM_NOTE_BUCKET)
                .cmp(&(b.0 == OTHER_CUSTOM_NOTE_BUCKET))
                .then_with(|| b.1.cmp(&a.1))
                .then_with(|| a.0.cmp(&b.0))
        });
        for (reason, cnt) in entries {
            let _ = writeln!(out, "• {}: *{cnt}*", flatten_single_line(&reason));
        }
    }

    if !team_map.is_empty() {
        out.push('\n');
        let _ = writeln!(out, "*Per-Team Activity*");
        let mut teams: Vec<TeamStats> = team_map.into_values().collect();
        teams.sort_by(|a, b| {
            (a.display_name == UNMAPPED_DEVICE_LABEL)
                .cmp(&(b.display_name == UNMAPPED_DEVICE_LABEL))
                .then_with(|| (b.done + b.issue + b.pending_touched).cmp(&(a.done + a.issue + a.pending_touched)))
                .then_with(|| a.display_name.cmp(&b.display_name))
        });
        for t in teams {
            let exact = t.done + t.issue + t.pending_touched;
            if exact == 0 { continue; }
            let mut parts: Vec<String> = Vec::with_capacity(3);
            if t.done > 0 { parts.push(format!("{} Done", t.done)); }
            if t.issue > 0 { parts.push(format!("{} Issue", t.issue)); }
            if t.pending_touched > 0 { parts.push(format!("{} Pending", t.pending_touched)); }
            let _ = writeln!(out, "• *{}*: {exact} worked ({})", t.display_name, parts.join(" • "));
        }
    }

    if !field_notes.is_empty() {
        out.push('\n');
        let _ = writeln!(out, "*Field Notes / Rework ({})*", field_notes.len());
        for note in field_notes.iter().take(20) {
            let _ = writeln!(out, "• {note}");
        }
        if field_notes.len() > 20 {
            let more = field_notes.len() - 20;
            let _ = writeln!(out, "• _+{}_", plural(more, "more note", "more notes"));
        }
    }

    Some(out.trim().as_bytes().to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_str(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
        buf.extend_from_slice(s.as_bytes());
    }

    #[test]
    fn dedups_pins_and_generates_accurate_daily_report() {
        let mut d_buf = Vec::new();
        d_buf.extend_from_slice(&2u32.to_le_bytes());
        // pin 0: untouched pending
        put_str(&mut d_buf, "p1");
        put_str(&mut d_buf, "MTR-01   Akre");
        d_buf.extend_from_slice(&36.74_f64.to_le_bytes());
        d_buf.extend_from_slice(&43.89_f64.to_le_bytes());
        d_buf.push(2u8);
        d_buf.push(0u8);
        d_buf.extend_from_slice(&0i64.to_le_bytes());
        d_buf.extend_from_slice(&100i64.to_le_bytes());
        // pin 1: worked done at same coord -> must win
        put_str(&mut d_buf, "p2");
        put_str(&mut d_buf, "MTR-01 Akre");
        d_buf.extend_from_slice(&36.74_f64.to_le_bytes());
        d_buf.extend_from_slice(&43.89_f64.to_le_bytes());
        d_buf.push(0u8);
        d_buf.push(1u8);
        d_buf.extend_from_slice(&500i64.to_le_bytes());
        d_buf.extend_from_slice(&500i64.to_le_bytes());

        let kept = deduplicate_pins_indices(&d_buf).unwrap();
        assert_eq!(u32::from_le_bytes(kept[0..4].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(kept[4..8].try_into().unwrap()), 1);

        // Build daily report
        let mut r_buf = Vec::new();
        put_str(&mut r_buf, "Akre Feeder 01");
        put_str(&mut r_buf, "27 Nov 2026");
        put_str(&mut r_buf, "Local");
        r_buf.extend_from_slice(&1u32.to_le_bytes()); // dup_skipped
        put_str(&mut r_buf, "");
        r_buf.extend_from_slice(&1u32.to_le_bytes()); // 1 device alias
        put_str(&mut r_buf, "A1B2");
        put_str(&mut r_buf, "team 3");
        r_buf.extend_from_slice(&0u32.to_le_bytes()); // known pins
        r_buf.extend_from_slice(&2u32.to_le_bytes()); // 2 unique pins

        put_str(&mut r_buf, "MTR-01 ئاکرێ");
        r_buf.push(0u8);
        put_str(&mut r_buf, "Meter Linked");
        r_buf.push(1u8);
        put_str(&mut r_buf, "HONOR • #A1B2");
        put_str(&mut r_buf, "");

        put_str(&mut r_buf, "MTR-02 قەڵا");
        r_buf.push(1u8);
        put_str(&mut r_buf, "Need Electricity: pole broken");
        r_buf.push(1u8);
        put_str(&mut r_buf, "Team 3 (#A1B2)");
        put_str(&mut r_buf, "");

        let txt = String::from_utf8(build_daily_report(&r_buf).unwrap()).unwrap();
        assert!(txt.contains("Total Pins: *2* (1 duplicate deduplicated)"));
        assert!(txt.contains("Meter Linked: *1*"));
        assert!(txt.contains("Need Electricity: *1*"));
        assert!(txt.contains("*Team 3*: 2 worked (1 Done • 1 Issue)"));
        assert!(txt.contains("pole broken"));
    }
}
