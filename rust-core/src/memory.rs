/// Trims idle Rust native caches and asks the Android Bionic/Scudo allocator
/// to return freed pages back to the OS kernel.
pub fn trim_native_memory(aggressive: bool) -> u32 {
    let mut trimmed = 0u32;
    trimmed = trimmed.saturating_add(crate::routing::trim_graph_cache(aggressive) as u32);
    trimmed = trimmed.saturating_add(crate::pmtiles::trim_caches(aggressive) as u32);
    if aggressive {
        crate::tsr_compiler::shrink_session_map();
    }
    if purge_android_allocator() {
        trimmed = trimmed.saturating_add(1);
    }
    trimmed
}

#[cfg(all(target_os = "android", target_pointer_width = "64"))]
fn purge_android_allocator() -> bool {
    use std::ffi::{c_char, c_int, c_void};

    #[link(name = "dl")]
    extern "C" {
        fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    }

    type MalloptFn = unsafe extern "C" fn(c_int, c_int) -> c_int;
    const M_PURGE: c_int = -101;

    unsafe {
        let sym = dlsym(std::ptr::null_mut(), b"mallopt\0".as_ptr() as *const c_char);
        if sym.is_null() {
            return false;
        }
        let mallopt: MalloptFn = std::mem::transmute(sym);
        mallopt(M_PURGE, 0) != 0
    }
}

#[cfg(not(all(target_os = "android", target_pointer_width = "64")))]
fn purge_android_allocator() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_trim_native_memory_safe() {
        let _ = trim_native_memory(false);
        let _ = trim_native_memory(true);
    }
}
