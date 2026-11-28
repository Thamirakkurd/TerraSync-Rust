#[cfg(test)]
mod tests {
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::fs::File;
    use std::io::Write;
    use std::time::Instant;

    fn write_java_utf<W: Write>(w: &mut W, s: &str) {
        let b = s.as_bytes();
        w.write_all(&(b.len() as u16).to_be_bytes()).unwrap();
        w.write_all(b).unwrap();
    }

    #[test]
    fn bench_stress_kml_file() {
        let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
        let path = std::path::PathBuf::from(home).join("stress.kml");
        let Ok(bytes) = std::fs::read(&path) else {
            println!("STRESS_KML_SKIP | ~/stress.kml not found");
            return;
        };

        let t0 = Instant::now();
        let parsed = crate::kml::parse_kml_bytes(&bytes);
        let parse_ms = t0.elapsed().as_secs_f64() * 1000.0;

        let t1 = Instant::now();
        let wire = crate::kml::encode_parsed_kml(&parsed);
        let encode_ms = t1.elapsed().as_secs_f64() * 1000.0;

        println!(
            "STRESS_KML_SUMMARY | bytes={} | pins={} | tracks={} | parse_ms={:.2} | encode_ms={:.2} | wire_bytes={}",
            bytes.len(),
            parsed.pins.len(),
            parsed.tracks.len(),
            parse_ms,
            encode_ms,
            wire.len()
        );
        assert_eq!(parsed.pins.len(), 20_000);
        assert_eq!(parsed.tracks.len(), 100);
    }

    #[test]
    fn bench_wave6_20k_ops() {
        let _lock = crate::geojson::TEST_CACHE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        crate::geojson::clear_geo_pins();
        crate::mvt_tracks::clear_geo_tracks();
        let mut load = Vec::with_capacity(20_000 * 64);
        let put_s = |b: &mut Vec<u8>, s: &str| {
            b.extend_from_slice(&(s.len() as u32).to_le_bytes());
            b.extend_from_slice(s.as_bytes());
        };
        put_s(&mut load, "pin-done");
        put_s(&mut load, "pin-issue");
        put_s(&mut load, "pin-pending");
        load.extend_from_slice(&20_000u32.to_le_bytes());
        let mut cluster_in = Vec::with_capacity(20_000 * 48);
        cluster_in.extend_from_slice(&20_000u32.to_le_bytes());
        let mut sort_in = Vec::with_capacity(20_000 * 24);
        sort_in.push(1u8);
        sort_in.extend_from_slice(&36.74f64.to_le_bytes());
        sort_in.extend_from_slice(&43.89f64.to_le_bytes());
        sort_in.push(1u8);
        sort_in.extend_from_slice(&20_000u32.to_le_bytes());

        for i in 0..20_000usize {
            let id = format!("id-{i}");
            let name = format!("MTR-{i:05} ئاکرێ Pole");
            let lat = 36.70 + (i % 200) as f64 * 0.0005;
            let lng = 43.85 + (i / 200) as f64 * 0.0005;
            put_s(&mut load, &id);
            put_s(&mut load, "layer1");
            put_s(&mut load, &name);
            load.push((i % 3) as u8);
            load.extend_from_slice(&lng.to_le_bytes());
            load.extend_from_slice(&lat.to_le_bytes());

            cluster_in.extend_from_slice(&lat.to_le_bytes());
            cluster_in.extend_from_slice(&lng.to_le_bytes());
            cluster_in.push((i % 4) as u8);
            cluster_in.extend_from_slice(&(i as i64).to_le_bytes());
            cluster_in.extend_from_slice(&(i as i64).to_le_bytes());
            put_s(&mut cluster_in, &id);

            sort_in.push(1u8);
            sort_in.extend_from_slice(&lat.to_le_bytes());
            sort_in.extend_from_slice(&lng.to_le_bytes());
        }

        let t0 = Instant::now();
        assert!(crate::geojson::load_geo_pins(1, &load));
        let load_ms = t0.elapsed().as_secs_f64() * 1000.0;

        let mut flt = Vec::new();
        flt.push(0b111u8);
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.extend_from_slice(&0u32.to_le_bytes());
        let t1 = Instant::now();
        flt.push(0u8);
        for _ in 0..5 { flt.extend_from_slice(&0.0_f64.to_le_bytes()); }
        flt.push(0u8);
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.extend_from_slice(&0u32.to_le_bytes());
        let gj = crate::geojson::build_pins_geojson_viewport(1, 20_000, &flt).unwrap();
        let geojson_ms = t1.elapsed().as_secs_f64() * 1000.0;
        crate::geojson::clear_geo_pins();

        let t2 = Instant::now();
        let _cl = crate::pins_ops::analyze_same_coord_clusters(&cluster_in).unwrap();
        let cluster_ms = t2.elapsed().as_secs_f64() * 1000.0;

        let t3 = Instant::now();
        let _sr = crate::pins_ops::sort_and_score_pins(&sort_in).unwrap();
        let sort_ms = t3.elapsed().as_secs_f64() * 1000.0;

        println!(
            "WAVE6_20K_BENCH | geo_load_shape={:.2} ms | geojson_emit={:.2} ms ({} KB) | cluster_2m={:.2} ms | nearest_sort={:.2} ms",
            load_ms, geojson_ms, gj.len() / 1024, cluster_ms, sort_ms
        );
    }

    #[test]
    fn bench_wave7_viewport_20k() {
        let _lock = crate::geojson::TEST_CACHE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        crate::geojson::clear_geo_pins();
        crate::mvt_tracks::clear_geo_tracks();
        let put_s = |b: &mut Vec<u8>, s: &str| {
            b.extend_from_slice(&(s.len() as u32).to_le_bytes());
            b.extend_from_slice(s.as_bytes());
        };
        let mut load = Vec::with_capacity(20_000 * 64);
        put_s(&mut load, "pin-done");
        put_s(&mut load, "pin-issue");
        put_s(&mut load, "pin-pending");
        load.extend_from_slice(&20_000u32.to_le_bytes());
        for i in 0..20_000usize {
            let id = format!("id-{i}");
            let name = format!("MTR-{i:05} ئاکرێ Pole");
            let lat = 36.70 + (i % 200) as f64 * 0.002;
            let lng = 42.68 + (i / 200) as f64 * 0.012;
            put_s(&mut load, &id);
            put_s(&mut load, "layer1");
            put_s(&mut load, &name);
            load.push((i % 3) as u8);
            load.extend_from_slice(&lng.to_le_bytes());
            load.extend_from_slice(&lat.to_le_bytes());
        }
        let t0 = Instant::now();
        assert!(crate::geojson::load_geo_pins(77, &load));
        let load_ms = t0.elapsed().as_secs_f64() * 1000.0;

        // Viewport covering ~25% of pins (unclustered + thinned to <=1100)
        let mut flt = Vec::new();
        flt.push(0b111u8);
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.push(1u8);
        flt.extend_from_slice(&36.70f64.to_le_bytes());
        flt.extend_from_slice(&43.50f64.to_le_bytes());
        flt.extend_from_slice(&36.90f64.to_le_bytes());
        flt.extend_from_slice(&43.95f64.to_le_bytes());
        flt.extend_from_slice(&13.5f64.to_le_bytes());
        flt.push(0u8);
        flt.extend_from_slice(&1100u32.to_le_bytes());
        put_s(&mut flt, "");

        let t1 = Instant::now();
        let gj = crate::geojson::build_pins_geojson_viewport(77, 20_000, &flt).unwrap();
        let vp_ms = t1.elapsed().as_secs_f64() * 1000.0;

        // Lasso polygon test across all 20,000 pins
        let mut lasso = Vec::new();
        lasso.push(0b111u8);
        lasso.extend_from_slice(&0u32.to_le_bytes());
        lasso.extend_from_slice(&0u32.to_le_bytes());
        lasso.extend_from_slice(&4u32.to_le_bytes());
        for (lng, lat) in [(43.60_f64, 36.75_f64), (43.80_f64, 36.75_f64), (43.80_f64, 36.85_f64), (43.60_f64, 36.85_f64)] {
            lasso.extend_from_slice(&lng.to_le_bytes());
            lasso.extend_from_slice(&lat.to_le_bytes());
        }
        let t2 = Instant::now();
        let hits = crate::geojson::filter_pins_in_polygon(77, 20_000, &lasso).unwrap();
        let lasso_ms = t2.elapsed().as_secs_f64() * 1000.0;
        let hit_cnt = u32::from_le_bytes(hits[0..4].try_into().unwrap());
        crate::geojson::clear_geo_pins();

        println!(
            "WAVE7_20K_BENCH | load_20k={:.2} ms | viewport_thinned_geojson={:.2} ms ({} KB) | lasso_20k={:.2} ms ({} hits)",
            load_ms, vp_ms, gj.len() / 1024, lasso_ms, hit_cnt
        );
    }

    #[test]
    fn bench_wave8_report_20k() {
        let put_s = |b: &mut Vec<u8>, s: &str| {
            b.extend_from_slice(&(s.len() as u32).to_le_bytes());
            b.extend_from_slice(s.as_bytes());
        };
        let mut dedup_in = Vec::with_capacity(20_000 * 64);
        dedup_in.extend_from_slice(&20_000u32.to_le_bytes());
        let mut rep_in = Vec::with_capacity(20_000 * 80);
        put_s(&mut rep_in, "Akre & Kurdistan Stress");
        put_s(&mut rep_in, "27 Nov 2026");
        put_s(&mut rep_in, "Local");
        rep_in.extend_from_slice(&0u32.to_le_bytes());
        put_s(&mut rep_in, "");
        rep_in.extend_from_slice(&0u32.to_le_bytes());
        rep_in.extend_from_slice(&0u32.to_le_bytes());
        rep_in.extend_from_slice(&20_000u32.to_le_bytes());

        for i in 0..20_000usize {
            let id = format!("id-{i}");
            let name = format!("MTR-{i:05} ئاکرێ Pole");
            let lat = 36.70 + (i % 200) as f64 * 0.0005;
            let lng = 43.85 + (i / 200) as f64 * 0.0005;
            let st = (i % 3) as u8;
            put_s(&mut dedup_in, &id);
            put_s(&mut dedup_in, &name);
            dedup_in.extend_from_slice(&lat.to_le_bytes());
            dedup_in.extend_from_slice(&lng.to_le_bytes());
            dedup_in.push(st);
            dedup_in.push(if st != 2 { 1 } else { 0 });
            dedup_in.extend_from_slice(&(i as i64).to_le_bytes());
            dedup_in.extend_from_slice(&(i as i64).to_le_bytes());

            put_s(&mut rep_in, &name);
            rep_in.push(st);
            put_s(&mut rep_in, if st == 0 { "Meter Linked" } else if st == 1 { "Need Electricity" } else { "" });
            rep_in.push(if st != 2 { 1 } else { 0 });
            put_s(&mut rep_in, if st != 2 { "Team 1 (#A1B2)" } else { "" });
            put_s(&mut rep_in, "");
        }

        let t0 = Instant::now();
        let kept = crate::report::deduplicate_pins_indices(&dedup_in).unwrap();
        let dedup_ms = t0.elapsed().as_secs_f64() * 1000.0;

        let t1 = Instant::now();
        let rep = crate::report::build_daily_report(&rep_in).unwrap();
        let report_ms = t1.elapsed().as_secs_f64() * 1000.0;

        println!(
            "WAVE8_20K_BENCH | dedup_20k={:.2} ms ({} kept) | daily_report_20k={:.2} ms ({} bytes)",
            dedup_ms,
            u32::from_le_bytes(kept[0..4].try_into().unwrap()),
            report_ms,
            rep.len()
        );
    }

    #[test]
    fn bench_wave9_ops() {
        let put_s = |b: &mut Vec<u8>, s: &str| {
            b.extend_from_slice(&(s.len() as u32).to_le_bytes());
            b.extend_from_slice(s.as_bytes());
        };

        // 1. 5,000-vertex route load + 1,000 live GPS ticks
        let mut flat = Vec::with_capacity(10_000);
        for i in 0..5_000usize {
            flat.push(43.0 + i as f64 * 0.0002);
            flat.push(36.7 + (i % 7) as f64 * 0.00003);
        }
        assert!(crate::nav_geo::load_nav_route(999, &flat));
        let t0 = Instant::now();
        let mut seg = 0i32;
        for tick in 0..1_000usize {
            let idx = tick * 4;
            let res = crate::nav_geo::track_nav_route(999, 5_000, flat[idx * 2 + 1], flat[idx * 2], seg).unwrap();
            seg = i32::from_le_bytes(res[0..4].try_into().unwrap());
        }
        let nav_1k_ticks_ms = t0.elapsed().as_secs_f64() * 1000.0;

        // 2. 20,000-pin streaming CSV export
        let mut csv_in = Vec::with_capacity(20_000 * 96);
        csv_in.extend_from_slice(&20_000u32.to_le_bytes());
        for i in 0..20_000usize {
            put_s(&mut csv_in, &format!("MTR-{i:05} ئاکرێ"));
            put_s(&mut csv_in, "Done");
            put_s(&mut csv_in, "Meter Linked");
            put_s(&mut csv_in, "Feeder 01");
            csv_in.extend_from_slice(&(36.74_f64 + (i % 100) as f64 * 0.0001).to_le_bytes());
            csv_in.extend_from_slice(&(43.89_f64 + (i / 100) as f64 * 0.0001).to_le_bytes());
            put_s(&mut csv_in, "Team 1");
            put_s(&mut csv_in, "2026-11-27 14:30");
        }
        let csv_path = std::env::temp_dir().join("terrasync_bench_20k.csv");
        let t1 = Instant::now();
        assert!(crate::csv_export::export_csv_file(&csv_in, csv_path.to_str().unwrap()));
        let csv_ms = t1.elapsed().as_secs_f64() * 1000.0;
        let _ = std::fs::remove_file(&csv_path);

        // 3. 20,000-pin working-hours audit
        let mut aud_in = Vec::with_capacity(20_000 * 64);
        aud_in.extend_from_slice(&(8 * 60i32).to_le_bytes());
        aud_in.extend_from_slice(&(17 * 60i32).to_le_bytes());
        aud_in.extend_from_slice(&(3 * 3600 * 1000i64).to_le_bytes());
        aud_in.extend_from_slice(&20_000u32.to_le_bytes());
        for i in 0..20_000usize {
            put_s(&mut aud_in, if i % 4 == 0 { "27 Nov 19:45 • Team 1 → Done" } else { "27 Nov 11:20 • Team 1 → Done" });
            aud_in.push(1u8);
            aud_in.extend_from_slice(&0i64.to_le_bytes());
            aud_in.extend_from_slice(&0i64.to_le_bytes());
        }
        let t2 = Instant::now();
        let late = crate::audit::count_outside_working_hours(&aud_in).unwrap();
        let audit_ms = t2.elapsed().as_secs_f64() * 1000.0;

        println!(
            "WAVE9_BENCH | nav_1k_ticks_5k_route={:.2} ms | csv_export_20k={:.2} ms | audit_20k={:.2} ms (late={})",
            nav_1k_ticks_ms, csv_ms, audit_ms, late
        );
    }

    #[test]
    fn bench_delta_patch_20k() {
        let _lock = crate::geojson::TEST_CACHE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        crate::geojson::clear_geo_pins();
        crate::mvt_tracks::clear_geo_tracks();
        let put_s = |b: &mut Vec<u8>, s: &str| {
            b.extend_from_slice(&(s.len() as u32).to_le_bytes());
            b.extend_from_slice(s.as_bytes());
        };
        let mut load = Vec::with_capacity(20_000 * 64);
        put_s(&mut load, "pin-done");
        put_s(&mut load, "pin-issue");
        put_s(&mut load, "pin-pending");
        load.extend_from_slice(&20_000u32.to_le_bytes());
        for i in 0..20_000usize {
            put_s(&mut load, &format!("id-{i}"));
            put_s(&mut load, "layer1");
            put_s(&mut load, &format!("MTR-{i:05} ئاکرێ"));
            load.push((i % 3) as u8);
            load.extend_from_slice(&(43.85 + (i / 200) as f64 * 0.001).to_le_bytes());
            load.extend_from_slice(&(36.70 + (i % 200) as f64 * 0.001).to_le_bytes());
        }
        assert!(crate::geojson::load_geo_pins(500, &load));

        let mut delta = Vec::new();
        delta.extend_from_slice(&1u32.to_le_bytes());
        put_s(&mut delta, "id-12345");
        put_s(&mut delta, "layer1");
        put_s(&mut delta, "MTR-12345 ئاکرێ");
        delta.push(0u8);
        delta.extend_from_slice(&43.91_f64.to_le_bytes());
        delta.extend_from_slice(&36.74_f64.to_le_bytes());

        let t0 = Instant::now();
        assert!(crate::geojson::patch_geo_pins(500, 501, 20_000, &delta));
        let delta_us = t0.elapsed().as_secs_f64() * 1_000_000.0;
        crate::geojson::clear_geo_pins();

        println!("DELTA_20K_BENCH | single_pin_patch_in_20k={:.1} us", delta_us);
    }

    #[test]
    fn bench_wave10_mvt_20k() {
        let _lock = crate::geojson::TEST_CACHE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        crate::geojson::clear_geo_pins();
        crate::mvt_tracks::clear_geo_tracks();
        let put_s = |b: &mut Vec<u8>, s: &str| {
            b.extend_from_slice(&(s.len() as u32).to_le_bytes());
            b.extend_from_slice(s.as_bytes());
        };
        let mut load = Vec::with_capacity(20_000 * 64);
        put_s(&mut load, "pin-done");
        put_s(&mut load, "pin-issue");
        put_s(&mut load, "pin-pending");
        load.extend_from_slice(&20_000u32.to_le_bytes());
        for i in 0..20_000usize {
            put_s(&mut load, &format!("id-{i}"));
            put_s(&mut load, "layer1");
            put_s(&mut load, &format!("MTR-{i:05} ئاکرێ"));
            load.push((i % 3) as u8);
            load.extend_from_slice(&(43.85_f64 + (i / 200) as f64 * 0.001).to_le_bytes());
            load.extend_from_slice(&(36.70_f64 + (i % 200) as f64 * 0.001).to_le_bytes());
        }
        assert!(crate::geojson::load_geo_pins(1000, &load));

        let (mx, my) = crate::geojson::lng_lat_to_mercator_unit(43.90, 36.75);
        let z = 13;
        let n = (1 << z) as f64;
        let cx = (mx * n).floor() as i32;
        let cy = (my * n).floor() as i32;

        // Unclustered 3x3 viewport (9 tiles)
        let mut flt_u = vec![0b111u8, 0u8, 1u8];
        flt_u.extend_from_slice(&0u32.to_le_bytes());
        flt_u.extend_from_slice(&0u32.to_le_bytes());
        assert!(crate::mvt_pins::set_mvt_filter(&flt_u));
        let t0 = Instant::now();
        let mut bytes_u = 0usize;
        for dx in -1..=1 {
            for dy in -1..=1 {
                bytes_u += crate::mvt_pins::render_pins_mvt_tile(z, cx + dx, cy + dy).len();
            }
        }
        let unclustered_9tiles_ms = t0.elapsed().as_secs_f64() * 1000.0;

        // Clustered 3x3 viewport (9 tiles)
        let mut flt_c = vec![0b111u8, 1u8, 1u8];
        flt_c.extend_from_slice(&0u32.to_le_bytes());
        flt_c.extend_from_slice(&0u32.to_le_bytes());
        assert!(crate::mvt_pins::set_mvt_filter(&flt_c));
        let t1 = Instant::now();
        let mut bytes_c = 0usize;
        for dx in -1..=1 {
            for dy in -1..=1 {
                bytes_c += crate::mvt_pins::render_pins_mvt_tile(z, cx + dx, cy + dy).len();
            }
        }
        let clustered_9tiles_ms = t1.elapsed().as_secs_f64() * 1000.0;
        crate::geojson::clear_geo_pins();

        println!(
            "WAVE10_MVT_20K | 9_tiles_unclustered={:.2} ms ({} KB) | 9_tiles_clustered={:.2} ms ({} KB)",
            unclustered_9tiles_ms, bytes_u / 1024, clustered_9tiles_ms, bytes_c / 1024
        );
    }

    #[test]
    fn bench_wave11_mvt_tracks_and_delta() {
        let _lock = crate::geojson::TEST_CACHE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        crate::geojson::clear_geo_pins();
        crate::mvt_tracks::clear_geo_tracks();
        let put_s = |b: &mut Vec<u8>, s: &str| {
            b.extend_from_slice(&(s.len() as u32).to_le_bytes());
            b.extend_from_slice(s.as_bytes());
        };
        // Load 20,000 pins
        let mut p_load = Vec::with_capacity(20_000 * 64);
        put_s(&mut p_load, "pin-done");
        put_s(&mut p_load, "pin-issue");
        put_s(&mut p_load, "pin-pending");
        p_load.extend_from_slice(&20_000u32.to_le_bytes());
        for i in 0..20_000usize {
            put_s(&mut p_load, &format!("id-{i}"));
            put_s(&mut p_load, "layer1");
            put_s(&mut p_load, &format!("MTR-{i:05} ئاکرێ"));
            p_load.push((i % 3) as u8);
            p_load.extend_from_slice(&(43.85_f64 + (i / 200) as f64 * 0.001).to_le_bytes());
            p_load.extend_from_slice(&(36.70_f64 + (i % 200) as f64 * 0.001).to_le_bytes());
        }
        assert!(crate::geojson::load_geo_pins(2000, &p_load));

        // Load 200 diagonal tracks (100 vertices each = 20,000 track vertices)
        let mut t_load = Vec::with_capacity(200 * 2400);
        t_load.extend_from_slice(&200u32.to_le_bytes());
        for t in 0..200usize {
            put_s(&mut t_load, "layer1");
            let mut cs = String::with_capacity(2000);
            for v in 0..100usize {
                if v > 0 { cs.push(';'); }
                let lng = 43.85 + (t as f64) * 0.0005 + (v as f64) * 0.0005;
                let lat = 36.70 + (v as f64) * 0.001;
                cs.push_str(&format!("{lng:.5},{lat:.5}"));
            }
            put_s(&mut t_load, &cs);
        }
        let t0 = Instant::now();
        assert!(crate::mvt_tracks::load_geo_tracks(2000, &t_load));
        let trk_load_ms = t0.elapsed().as_secs_f64() * 1000.0;

        let mut flt = vec![0b111u8, 0u8, 1u8];
        flt.extend_from_slice(&0u32.to_le_bytes());
        flt.extend_from_slice(&0u32.to_le_bytes());
        assert!(crate::mvt_pins::set_mvt_filter(&flt));

        let (mx, my) = crate::geojson::lng_lat_to_mercator_unit(43.90, 36.75);
        let z = 13;
        let n = (1 << z) as f64;
        let cx = (mx * n).floor() as i32;
        let cy = (my * n).floor() as i32;

        // Render 9 combined pins+tracks MVT tiles
        let t1 = Instant::now();
        let mut total_bytes = 0usize;
        let mut tracked = Vec::with_capacity(49);
        for dx in -3..=3 {
            for dy in -3..=3 {
                let tx = cx + dx;
                let ty = cy + dy;
                tracked.push(((z as i64) << 48) | ((tx as i64) << 24) | (ty as i64));
                if dx.abs() <= 1 && dy.abs() <= 1 {
                    total_bytes += crate::mvt_pins::render_pins_mvt_tile(z, tx, ty).len();
                }
            }
        }
        let combined_9tiles_ms = t1.elapsed().as_secs_f64() * 1000.0;

        // Patch 1 pin & filter 49 tracked tiles -> should only dirty 1-2 tiles
        let mut delta = Vec::new();
        delta.extend_from_slice(&1u32.to_le_bytes());
        put_s(&mut delta, "id-10050");
        put_s(&mut delta, "layer1");
        put_s(&mut delta, "MTR-10050 ئاکرێ");
        delta.push(0u8);
        delta.extend_from_slice(&43.90_f64.to_le_bytes());
        delta.extend_from_slice(&36.75_f64.to_le_bytes());
        assert!(crate::geojson::patch_geo_pins(2000, 2001, 20_000, &delta));

        let t2 = Instant::now();
        let dirty = crate::mvt_pins::filter_dirty_mvt_tiles(2001, &tracked).unwrap();
        let dirty_us = t2.elapsed().as_secs_f64() * 1_000_000.0;

        // Resident bounds over 20k pins + 200 tracks
        assert!(crate::mvt_tracks::load_geo_tracks(2001, &t_load));
        let t3 = Instant::now();
        let bounds = crate::mvt_tracks::compute_resident_bounds(2001, 20_000, 200, "").unwrap();
        let bounds_us = t3.elapsed().as_secs_f64() * 1_000_000.0;
        let b_cnt = u32::from_le_bytes(bounds[0..4].try_into().unwrap());

        crate::geojson::clear_geo_pins();
        crate::mvt_tracks::clear_geo_tracks();

        println!(
            "WAVE11_BENCH | tracks_load_20k_pts={:.2} ms | combined_9tiles={:.2} ms ({} KB) | dirty_tile_filter={:.1} us ({}/{} tiles) | bounds_40k={:.1} us ({} pts)",
            trk_load_ms, combined_9tiles_ms, total_bytes / 1024, dirty_us, dirty.len(), tracked.len(), bounds_us, b_cnt
        );
    }

    #[test]
    fn bench_native_engines() {
        // 1. KML parse + export benchmark (500 field pins)
        let mut kml = String::from(
            r#"<?xml version="1.0" encoding="UTF-8"?><kml><Document><name>Akre Grid</name><Folder><name>Feeder 12</name>"#,
        );
        for i in 0..500 {
            kml.push_str(&format!(
                r#"<Placemark><name>Meter-{i} ئاکرێ</name><description>Status: done
Done Note: Meter Linked</description><Point><coordinates>{:.5},{:.5},0</coordinates></Point></Placemark>"#,
                43.85 + (i % 50) as f64 * 0.001,
                36.70 + (i / 50) as f64 * 0.001
            ));
        }
        kml.push_str("</Folder></Document></kml>");
        let kml_bytes = kml.as_bytes();

        let t0 = Instant::now();
        let mut total_pins = 0usize;
        for _ in 0..20 {
            let parsed = crate::kml::parse_kml_bytes(kml_bytes);
            total_pins += parsed.pins.len();
        }
        let kml_ms = t0.elapsed().as_secs_f64() * 1000.0 / 20.0;
        assert_eq!(total_pins, 500 * 20);

        // 2. Kurdish shaper + MVT patch benchmark
        let sample_label = "مزگەوتی فەرحان سەفەر لە ئاکرێ و هەولێر";
        let t1 = Instant::now();
        for _ in 0..5000 {
            let s = crate::kurdish::shape(sample_label);
            std::hint::black_box(s);
        }
        let kurdish_us = t1.elapsed().as_secs_f64() * 1_000_000.0 / 5000.0;

        // 3. TSR1 graph load + A* route benchmark (2,000-node grid)
        let grid_side = 45usize; // 2,025 nodes
        let n_nodes = grid_side * grid_side;
        let tmp = std::env::temp_dir().join("terrasync_bench_grid.tsr");
        let mut edges: Vec<(i32, i32, f32)> = Vec::new();
        for r in 0..grid_side {
            for c in 0..grid_side {
                let u = (r * grid_side + c) as i32;
                if c + 1 < grid_side {
                    let v = (r * grid_side + c + 1) as i32;
                    edges.push((u, v, 250.0));
                    edges.push((v, u, 250.0));
                }
                if r + 1 < grid_side {
                    let v = ((r + 1) * grid_side + c) as i32;
                    edges.push((u, v, 250.0));
                    edges.push((v, u, 250.0));
                }
            }
        }
        {
            let f = File::create(&tmp).unwrap();
            let mut gz = GzEncoder::new(f, Compression::fast());
            gz.write_all(&0x5453_5231i32.to_be_bytes()).unwrap();
            write_java_utf(&mut gz, "BenchGrid");
            gz.write_all(&36.70f64.to_be_bytes()).unwrap();
            gz.write_all(&43.80f64.to_be_bytes()).unwrap();
            gz.write_all(&36.90f64.to_be_bytes()).unwrap();
            gz.write_all(&44.00f64.to_be_bytes()).unwrap();
            gz.write_all(&1i64.to_be_bytes()).unwrap();
            gz.write_all(&(n_nodes as i32).to_be_bytes()).unwrap();
            gz.write_all(&(edges.len() as i32).to_be_bytes()).unwrap();
            gz.write_all(&1i32.to_be_bytes()).unwrap();
            write_java_utf(&mut gz, "Main Rd");
            for r in 0..grid_side {
                for c in 0..grid_side {
                    let lat = (36.72 + r as f64 * 0.0022) as f32;
                    let lng = (43.82 + c as f64 * 0.0025) as f32;
                    gz.write_all(&lat.to_be_bytes()).unwrap();
                    gz.write_all(&lng.to_be_bytes()).unwrap();
                }
            }
            for &(u, v, d) in &edges {
                gz.write_all(&u.to_be_bytes()).unwrap();
                gz.write_all(&v.to_be_bytes()).unwrap();
                gz.write_all(&d.to_be_bytes()).unwrap();
                gz.write_all(&50.0f32.to_be_bytes()).unwrap();
                gz.write_all(&[1u8]).unwrap();
                gz.write_all(&1i32.to_be_bytes()).unwrap();
            }
            gz.finish().unwrap();
        }
        let sz = std::fs::metadata(&tmp).unwrap().len() as i64;
        let p_str = tmp.to_str().unwrap();

        let t2 = Instant::now();
        let route = crate::routing::route_from_tsr(
            p_str, 1, sz, 36.721, 43.821, 36.815, 43.925, crate::routing::TravelMode::Car,
        )
        .unwrap()
        .unwrap();
        let cold_route_ms = t2.elapsed().as_secs_f64() * 1000.0;
        assert!(!route.geom.is_empty());

        let t3 = Instant::now();
        for _ in 0..50 {
            let r2 = crate::routing::route_from_tsr(
                p_str, 1, sz, 36.721, 43.821, 36.815, 43.925, crate::routing::TravelMode::Car,
            )
            .unwrap()
            .unwrap();
            std::hint::black_box(r2.total_dist);
        }
        let warm_route_ms = t3.elapsed().as_secs_f64() * 1000.0 / 50.0;

        crate::routing::evict_graph(p_str);
        let _ = std::fs::remove_file(&tmp);

        println!(
            "BENCH_SUMMARY | kml_500_pins_avg={:.2} ms | kurdish_shape_avg={:.2} us | tsr_2025n_cold={:.2} ms | tsr_2025n_warm_astar={:.3} ms",
            kml_ms, kurdish_us, cold_route_ms, warm_route_ms
        );
    }

    #[test]
    fn bench_wave12_unified_search_20k() {
        let _lock = crate::geojson::TEST_CACHE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        crate::geojson::clear_geo_pins();

        fn put_s(buf: &mut Vec<u8>, s: &str) {
            buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
            buf.extend_from_slice(s.as_bytes());
        }

        let n = 20_000usize;
        let mut load = Vec::with_capacity(n * 96 + 256);
        put_s(&mut load, "pin-done");
        put_s(&mut load, "pin-issue");
        put_s(&mut load, "pin-pending");
        load.extend_from_slice(&(n as u32).to_le_bytes());
        for i in 0..n {
            put_s(&mut load, &format!("id-{i}"));
            put_s(&mut load, if i % 2 == 0 { "f_akre" } else { "f_duhok" });
            put_s(&mut load, &format!("MTR-{i} ئاکرێ"));
            load.push((i % 3) as u8);
            let lng = 43.85 + (i % 200) as f64 * 0.0005;
            let lat = 36.70 + (i / 200) as f64 * 0.0005;
            load.extend_from_slice(&lng.to_le_bytes());
            load.extend_from_slice(&lat.to_le_bytes());
        }
        load.extend_from_slice(b"W12S");
        load.extend_from_slice(&2u32.to_le_bytes());
        put_s(&mut load, "f_akre");
        put_s(&mut load, "Akre Feeder 12");
        put_s(&mut load, "f_duhok");
        put_s(&mut load, "Duhok Feeder 3");
        for i in 0..n {
            put_s(&mut load, &format!("pole-{}", i % 500));
            put_s(&mut load, if i % 3 == 1 { "no bill" } else { "" });
        }

        let t0 = Instant::now();
        assert!(crate::geojson::load_geo_pins(3000, &load));
        let unified_load_ms = t0.elapsed().as_secs_f64() * 1000.0;

        let mut q = Vec::new();
        q.extend_from_slice(&2u32.to_le_bytes());
        put_s(&mut q, "akre");
        put_s(&mut q, "pole-42");
        q.push(1u8);
        q.push(1u8);
        q.extend_from_slice(&36.74_f64.to_le_bytes());
        q.extend_from_slice(&43.89_f64.to_le_bytes());
        q.push(1u8);

        let t1 = Instant::now();
        let res = crate::search::query_geo_pins(3000, n, &q).unwrap();
        let query_ms = t1.elapsed().as_secs_f64() * 1000.0;
        let cnt = u32::from_le_bytes(res[0..4].try_into().unwrap());
        assert!(cnt > 0);

        let mut delta = Vec::new();
        delta.extend_from_slice(&1u32.to_le_bytes());
        put_s(&mut delta, "id-10050");
        put_s(&mut delta, "f_akre");
        put_s(&mut delta, "MTR-10050 ئاکرێ");
        delta.push(1u8);
        delta.extend_from_slice(&43.90_f64.to_le_bytes());
        delta.extend_from_slice(&36.75_f64.to_le_bytes());
        delta.extend_from_slice(b"W12S");
        put_s(&mut delta, "pole-50");
        put_s(&mut delta, "unique-wave12-reason");

        let t2 = Instant::now();
        assert!(crate::geojson::patch_geo_pins(3000, 3001, n, &delta));
        let patch_us = t2.elapsed().as_secs_f64() * 1_000_000.0;

        let mut q2 = Vec::new();
        q2.extend_from_slice(&1u32.to_le_bytes());
        put_s(&mut q2, "unique-wave12-reason");
        q2.push(1u8);
        q2.push(0u8);
        q2.push(1u8);
        let res2 = crate::search::query_geo_pins(3001, n, &q2).unwrap();
        assert_eq!(u32::from_le_bytes(res2[0..4].try_into().unwrap()), 1);

        crate::geojson::clear_geo_pins();
        println!(
            "WAVE12_BENCH | unified_load_20k={:.2} ms | search_20k={:.2} ms ({} hits) | delta_patch_1pin={:.1} us",
            unified_load_ms, query_ms, cnt, patch_us
        );
    }


    #[test]
    fn bench_wave13_nearest_and_counts_20k() {
        let _lock = crate::geojson::TEST_CACHE_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        crate::geojson::clear_geo_pins();

        fn put_s(buf: &mut Vec<u8>, s: &str) {
            buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
            buf.extend_from_slice(s.as_bytes());
        }

        let n = 20_000usize;
        let mut load = Vec::with_capacity(n * 64 + 64);
        put_s(&mut load, "pin-done");
        put_s(&mut load, "pin-issue");
        put_s(&mut load, "pin-pending");
        load.extend_from_slice(&(n as u32).to_le_bytes());
        for i in 0..n {
            put_s(&mut load, &format!("p-{i}"));
            put_s(&mut load, if i % 4 == 0 { "f_hidden" } else { "f_vis" });
            put_s(&mut load, &format!("M-{i}"));
            load.push((i % 3) as u8);
            let lng = 43.80 + (i % 200) as f64 * 0.001;
            let lat = 36.65 + (i / 200) as f64 * 0.001;
            load.extend_from_slice(&lng.to_le_bytes());
            load.extend_from_slice(&lat.to_le_bytes());
        }
        assert!(crate::geojson::load_geo_pins(4000, &load));

        // Nearest PENDING pin excluding f_hidden
        let mut q = Vec::new();
        q.push(1u8 << 2); // pending only
        q.extend_from_slice(&36.7001_f64.to_le_bytes());
        q.extend_from_slice(&43.9001_f64.to_le_bytes());
        q.extend_from_slice(&1u32.to_le_bytes());
        put_s(&mut q, "f_hidden");
        q.extend_from_slice(&0u32.to_le_bytes());

        let t0 = Instant::now();
        let res = crate::geojson::find_nearest_pin(4000, n, &q).unwrap();
        let nearest_us = t0.elapsed().as_secs_f64() * 1_000_000.0;
        let idx = i32::from_le_bytes(res[0..4].try_into().unwrap());
        let dist = f64::from_le_bytes(res[4..12].try_into().unwrap());
        assert!(idx >= 0 && (idx as usize) % 3 == 2 && (idx as usize) % 4 != 0);
        assert!(dist < 500.0);

        // Count visible statuses excluding f_hidden
        let mut cf = Vec::new();
        cf.extend_from_slice(&1u32.to_le_bytes());
        put_s(&mut cf, "f_hidden");
        cf.extend_from_slice(&0u32.to_le_bytes());

        let t1 = Instant::now();
        let cres = crate::geojson::count_visible_statuses(4000, n, &cf).unwrap();
        let counts_us = t1.elapsed().as_secs_f64() * 1_000_000.0;
        let total = u32::from_le_bytes(cres[0..4].try_into().unwrap());
        let done = u32::from_le_bytes(cres[4..8].try_into().unwrap());
        let issue = u32::from_le_bytes(cres[8..12].try_into().unwrap());
        let pending = u32::from_le_bytes(cres[12..16].try_into().unwrap());
        assert_eq!(total, 15_000);
        assert_eq!(done + issue + pending, 15_000);

        crate::geojson::clear_geo_pins();
        println!(
            "WAVE13_BENCH | nearest_pending_20k={:.1} us (idx={}, {:.1}m) | count_statuses_20k={:.1} us ({}/{}/{})",
            nearest_us, idx, dist, counts_us, done, issue, pending
        );
    }

}
