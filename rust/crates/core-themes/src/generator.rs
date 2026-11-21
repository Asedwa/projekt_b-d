use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::CustomThemeSpec;

static ENTROPY_COUNTER: AtomicU64 = AtomicU64::new(0x9E37_79B9_7F4A_7C15);

struct Rng(u64);

impl Rng {
    fn from_entropy() -> Self {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0x517C_C1B7_2722_0A95);
        let count = ENTROPY_COUNTER.fetch_add(1, Ordering::Relaxed);
        let mut seed = nanos ^ count.wrapping_mul(0xBF58_476D_1CE4_E5B9);
        if seed == 0 {
            seed = 0x9E37_79B9_7F4A_7C15;
        }
        Self(seed)
    }

    fn next_u64(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    fn next_f32(&mut self) -> f32 {
        ((self.next_u64() >> 40) as f32) / ((1u64 << 24) as f32)
    }

    fn range_f32(&mut self, min: f32, max: f32) -> f32 {
        min + (max - min) * self.next_f32()
    }

    fn index(&mut self, len: usize) -> usize {
        (self.next_u64() as usize) % len
    }
}

fn hsv_to_rgb(h: f32, s: f32, v: f32) -> (u8, u8, u8) {
    let h = ((h % 360.0) + 360.0) % 360.0;
    let s = s.clamp(0.0, 1.0);
    let v = v.clamp(0.0, 1.0);

    let c = v * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = v - c;

    let (r1, g1, b1) = if h < 60.0 {
        (c, x, 0.0)
    } else if h < 120.0 {
        (x, c, 0.0)
    } else if h < 180.0 {
        (0.0, c, x)
    } else if h < 240.0 {
        (0.0, x, c)
    } else if h < 300.0 {
        (x, 0.0, c)
    } else {
        (c, 0.0, x)
    };

    let r = ((r1 + m) * 255.0).round().clamp(0.0, 255.0) as u8;
    let g = ((g1 + m) * 255.0).round().clamp(0.0, 255.0) as u8;
    let b = ((b1 + m) * 255.0).round().clamp(0.0, 255.0) as u8;
    (r, g, b)
}

fn rgb_to_hex(r: u8, g: u8, b: u8) -> String {
    format!("#{r:02X}{g:02X}{b:02X}")
}

fn hsv_hex(h: f32, s: f32, v: f32) -> String {
    let (r, g, b) = hsv_to_rgb(h, s, v);
    rgb_to_hex(r, g, b)
}

fn pick_theme_name(rng: &mut Rng, accent_hue: f32, is_dark: bool) -> String {
    let hue_word = match ((accent_hue % 360.0) + 360.0) % 360.0 {
        h if h < 20.0 || h >= 340.0 => ["Yakut", "Mercan", "Kızıl", "Nar"][rng.index(4)],
        h if h < 45.0 => ["Kehribar", "Bakır", "Şeftali", "Gün Batımı"][rng.index(4)],
        h if h < 75.0 => ["Altın", "Bal", "Güneş", "Sarı"][rng.index(4)],
        h if h < 150.0 => ["Zümrüt", "Orman", "Yeşim", "Nane"][rng.index(4)],
        h if h < 195.0 => ["Turkuaz", "Buzul", "Okyanus", "Lagün"][rng.index(4)],
        h if h < 250.0 => ["Safir", "Kobalt", "Kuzey", "Gece Mavisi"][rng.index(4)],
        h if h < 295.0 => ["Lavanta", "Mor", "Ametist", "Kozmos"][rng.index(4)],
        _ => ["Gül", "Fuşya", "Kiraz", "Neon Pembe"][rng.index(4)],
    };

    let mood_word = if is_dark {
        ["Gecesi", "Gölgesi", "Rüyası", "Vakti", "Derinliği", "Işıltısı"][rng.index(6)]
    } else {
        ["Sabahı", "Şafağı", "Esintisi", "Bulutu", "Işığı", "Sisi"][rng.index(6)]
    };

    format!("{hue_word} {mood_word}")
}

/// Generates a harmonious, high-contrast random custom theme using color-theory
/// relationships (monochromatic, analogous, or soft split-complementary).
pub fn generate_random_custom_theme(prefer_dark: Option<bool>) -> CustomThemeSpec {
    let mut rng = Rng::from_entropy();
    let is_dark = prefer_dark.unwrap_or_else(|| rng.next_f32() < 0.78);

    let accent_hue = rng.range_f32(0.0, 360.0);

    // Choose background hue relationship:
    // 0: tinted monochromatic, 1: analogous (+28..45°), 2: analogous (-28..45°), 3: soft complementary (+155..205°)
    let bg_hue = match rng.index(4) {
        0 => accent_hue,
        1 => (accent_hue + rng.range_f32(25.0, 48.0)) % 360.0,
        2 => (accent_hue - rng.range_f32(25.0, 48.0) + 360.0) % 360.0,
        _ => (accent_hue + rng.range_f32(155.0, 205.0)) % 360.0,
    };

    let (bg_hex, card_hex, accent_hex, text_hex) = if is_dark {
        let bg_sat = rng.range_f32(0.18, 0.44);
        let bg_val = rng.range_f32(0.07, 0.13);
        let card_sat = (bg_sat * rng.range_f32(0.85, 1.05)).clamp(0.12, 0.46);
        let card_val = (bg_val + rng.range_f32(0.06, 0.10)).clamp(0.14, 0.24);

        let accent_sat = rng.range_f32(0.60, 0.88);
        let accent_val = rng.range_f32(0.80, 0.98);

        let text_sat = rng.range_f32(0.02, 0.08);
        let text_val = rng.range_f32(0.94, 0.99);

        (
            hsv_hex(bg_hue, bg_sat, bg_val),
            hsv_hex(bg_hue, card_sat, card_val),
            hsv_hex(accent_hue, accent_sat, accent_val),
            hsv_hex(bg_hue, text_sat, text_val),
        )
    } else {
        let bg_sat = rng.range_f32(0.05, 0.13);
        let bg_val = rng.range_f32(0.95, 0.99);
        let card_sat = rng.range_f32(0.07, 0.16);
        let card_val = rng.range_f32(0.89, 0.94);

        let accent_sat = rng.range_f32(0.64, 0.90);
        let accent_val = rng.range_f32(0.56, 0.78);

        let text_sat = rng.range_f32(0.14, 0.28);
        let text_val = rng.range_f32(0.10, 0.16);

        (
            hsv_hex(bg_hue, bg_sat, bg_val),
            hsv_hex(bg_hue, card_sat, card_val),
            hsv_hex(accent_hue, accent_sat, accent_val),
            hsv_hex(bg_hue, text_sat, text_val),
        )
    };

    let id_suffix = format!("{:08x}", (rng.next_u64() & 0xFFFF_FFFF) as u32);
    CustomThemeSpec {
        id: format!("custom.{id_suffix}"),
        name: pick_theme_name(&mut rng, accent_hue, is_dark),
        background_hex: bg_hex,
        card_hex,
        accent_hex,
        text_hex,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex_luminance(hex: &str) -> f32 {
        let h = hex.trim_start_matches('#');
        let r = u8::from_str_radix(&h[0..2], 16).unwrap() as f32 / 255.0;
        let g = u8::from_str_radix(&h[2..4], 16).unwrap() as f32 / 255.0;
        let b = u8::from_str_radix(&h[4..6], 16).unwrap() as f32 / 255.0;
        0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    #[test]
    fn generates_valid_dark_and_light_themes() {
        for _ in 0..20 {
            let dark = generate_random_custom_theme(Some(true));
            assert!(dark.background_hex.starts_with('#') && dark.background_hex.len() == 7);
            assert!(hex_luminance(&dark.background_hex) < 0.25);
            assert!(hex_luminance(&dark.text_hex) > 0.80);

            let light = generate_random_custom_theme(Some(false));
            assert!(light.background_hex.starts_with('#') && light.background_hex.len() == 7);
            assert!(hex_luminance(&light.background_hex) > 0.75);
            assert!(hex_luminance(&light.text_hex) < 0.25);
        }
    }

    #[test]
    fn consecutive_calls_produce_different_themes() {
        let a = generate_random_custom_theme(Some(true));
        let b = generate_random_custom_theme(Some(true));
        assert_ne!(a.accent_hex, b.accent_hex);
    }
}
