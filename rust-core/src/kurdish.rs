const ALM: char = '\u{061C}';
const ZWJ: char = '\u{200D}';

pub const YEH_SMALL_V_ISOL: char = '\u{FB5A}';
pub const YEH_SMALL_V_FINA: char = '\u{FB5B}';
pub const YEH_SMALL_V_INIT: char = '\u{FB5C}';
pub const YEH_SMALL_V_MEDI: char = '\u{FB5D}';

pub const LAM_SMALL_V_ISOL: char = '\u{FB62}';
pub const LAM_SMALL_V_FINA: char = '\u{FB63}';
pub const LAM_SMALL_V_INIT: char = '\u{FB64}';
pub const LAM_SMALL_V_MEDI: char = '\u{FB65}';

pub const VEH_ISOL: char = '\u{FB6A}';
pub const VEH_FINA: char = '\u{FB6B}';
pub const VEH_INIT: char = '\u{FB6C}';
pub const VEH_MEDI: char = '\u{FB6D}';

pub const REH_SMALL_V_ISOL: char = '\u{FB6E}';
pub const REH_SMALL_V_FINA: char = '\u{FB6F}';

#[inline]
fn is_transparent(c: char) -> bool {
    let cp = c as u32;
    (0x064B..=0x065F).contains(&cp)
        || cp == 0x0670
        || cp == 0x0610
        || (0x06D6..=0x06ED).contains(&cp)
}

#[inline]
fn is_dual_joining(c: char) -> bool {
    matches!(
        c,
        '\u{0626}'
            | '\u{0628}'
            | '\u{062A}'
            | '\u{062B}'
            | '\u{062C}'
            | '\u{062D}'
            | '\u{062E}'
            | '\u{0633}'
            | '\u{0634}'
            | '\u{0635}'
            | '\u{0636}'
            | '\u{0637}'
            | '\u{0638}'
            | '\u{0639}'
            | '\u{063A}'
            | '\u{0640}'
            | '\u{0641}'
            | '\u{0642}'
            | '\u{0643}'
            | '\u{0644}'
            | '\u{0645}'
            | '\u{0646}'
            | '\u{0647}'
            | '\u{0649}'
            | '\u{064A}'
            | '\u{067E}'
            | '\u{0686}'
            | '\u{06A4}'
            | '\u{06A9}'
            | '\u{06AF}'
            | '\u{06B5}'
            | '\u{06BE}'
            | '\u{06CC}'
            | '\u{06CE}'
            | '\u{200D}'
    )
}

#[inline]
fn is_right_joining(c: char) -> bool {
    is_dual_joining(c)
        || matches!(
            c,
            '\u{0622}'
                | '\u{0623}'
                | '\u{0624}'
                | '\u{0625}'
                | '\u{0627}'
                | '\u{0629}'
                | '\u{062F}'
                | '\u{0630}'
                | '\u{0631}'
                | '\u{0632}'
                | '\u{0648}'
                | '\u{0695}'
                | '\u{0698}'
                | '\u{06C6}'
                | '\u{06D5}'
        )
}

#[inline]
fn is_custom_slot_source(c: char) -> bool {
    matches!(c, '\u{06CE}' | '\u{06B5}' | '\u{06A4}' | '\u{0695}')
}

#[inline]
pub fn needs_kurdish_shaping(text: &str) -> bool {
    text.chars()
        .any(|c| matches!(c, '\u{06D5}' | '\u{06CE}' | '\u{06B5}' | '\u{0695}' | '\u{06A4}'))
}

#[inline]
pub fn contains_kurdish_utf8(data: &[u8]) -> bool {
    if data.len() < 2 {
        return false;
    }
    let mut i = 0;
    let limit = data.len() - 1;
    while i < limit {
        let b0 = data[i];
        if b0 == 0xDB {
            let b1 = data[i + 1];
            if b1 == 0x95 || b1 == 0x8E {
                return true;
            }
        } else if b0 == 0xDA {
            let b1 = data[i + 1];
            if b1 == 0xB5 || b1 == 0xA4 {
                return true;
            }
        } else if b0 == 0xD9 && data[i + 1] == 0x95 {
            return true;
        }
        i += 1;
    }
    false
}

pub fn shape(text: &str) -> String {
    if !needs_kurdish_shaping(text) {
        return text.to_string();
    }
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mut out = String::with_capacity(text.len() + 16);

    for i in 0..n {
        let ch = chars[i];
        if ch == '\u{06D5}' {
            out.push('\u{0647}');
            out.push(ALM);
            continue;
        }
        if !is_custom_slot_source(ch) {
            out.push(ch);
            continue;
        }

        let mut prev = '\0';
        let mut j = i;
        while j > 0 {
            j -= 1;
            if !is_transparent(chars[j]) {
                prev = chars[j];
                break;
            }
        }

        let mut next = '\0';
        let mut k = i + 1;
        while k < n {
            if !is_transparent(chars[k]) {
                next = chars[k];
                break;
            }
            k += 1;
        }

        let join_prev = is_dual_joining(prev);
        let join_next = (ch != '\u{0695}') && is_right_joining(next);

        if join_prev && !is_custom_slot_source(prev) && prev != ZWJ {
            out.push(ZWJ);
        }

        let glyph = match ch {
            '\u{06CE}' => match (join_prev, join_next) {
                (true, true) => YEH_SMALL_V_MEDI,
                (true, false) => YEH_SMALL_V_FINA,
                (false, true) => YEH_SMALL_V_INIT,
                (false, false) => YEH_SMALL_V_ISOL,
            },
            '\u{06B5}' => match (join_prev, join_next) {
                (true, true) => LAM_SMALL_V_MEDI,
                (true, false) => LAM_SMALL_V_FINA,
                (false, true) => LAM_SMALL_V_INIT,
                (false, false) => LAM_SMALL_V_ISOL,
            },
            '\u{06A4}' => match (join_prev, join_next) {
                (true, true) => VEH_MEDI,
                (true, false) => VEH_FINA,
                (false, true) => VEH_INIT,
                (false, false) => VEH_ISOL,
            },
            _ => {
                if join_prev {
                    REH_SMALL_V_FINA
                } else {
                    REH_SMALL_V_ISOL
                }
            }
        };
        out.push(glyph);

        if join_next && !is_custom_slot_source(next) && next != ZWJ {
            out.push(ZWJ);
        }
    }
    out
}

pub fn unshape(text: &str) -> String {
    if !text
        .chars()
        .any(|c| c == ALM || c == ZWJ || ('\u{FB5A}'..='\u{FB6F}').contains(&c))
    {
        return text.to_string();
    }
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < n {
        let c = chars[i];
        if c == '\u{0647}' && i + 1 < n && chars[i + 1] == ALM {
            out.push('\u{06D5}');
            i += 2;
            continue;
        }
        match c {
            ALM | ZWJ => {}
            YEH_SMALL_V_ISOL | YEH_SMALL_V_FINA | YEH_SMALL_V_INIT | YEH_SMALL_V_MEDI => {
                out.push('\u{06CE}')
            }
            LAM_SMALL_V_ISOL | LAM_SMALL_V_FINA | LAM_SMALL_V_INIT | LAM_SMALL_V_MEDI => {
                out.push('\u{06B5}')
            }
            VEH_ISOL | VEH_FINA | VEH_INIT | VEH_MEDI => out.push('\u{06A4}'),
            REH_SMALL_V_ISOL | REH_SMALL_V_FINA => out.push('\u{0695}'),
            _ => out.push(c),
        }
        i += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_kotlin_kurdish_shaper_test_vectors() {
        assert_eq!(
            shape("هەولێر"),
            format!("هه\u{061C}ول\u{200D}{}\u{200D}ر", YEH_SMALL_V_MEDI)
        );
        assert_eq!(
            shape("قەڵا"),
            format!("قه\u{061C}{}\u{200D}ا", LAM_SMALL_V_INIT)
        );
        assert_eq!(
            shape("پڕ"),
            format!("پ\u{200D}{}", REH_SMALL_V_FINA)
        );
        assert_eq!(
            shape("کۆڵانا سەردەم"),
            format!("کۆ{}\u{200D}انا سه\u{061C}رده\u{061C}م", LAM_SMALL_V_INIT)
        );
        assert_eq!(shape("Erbil 100m"), "Erbil 100m");

        for sample in ["هەولێر", "سەردەم", "مزگەوتی فەرحان سەفەر", "قەڵا", "پڕ", "کۆڵانا سەردەم", "ئاکرێ"] {
            assert_eq!(unshape(&shape(sample)), sample);
        }
    }
}
