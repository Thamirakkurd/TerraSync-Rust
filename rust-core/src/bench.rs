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
}
