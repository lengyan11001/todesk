use anyhow::Result;
use base64::{engine::general_purpose::STANDARD, Engine};

pub fn is_protection_available() -> bool {
    cfg!(windows)
}

pub fn protect_to_base64(input: &[u8]) -> Result<String> {
    let protected = protect(input)?;
    Ok(STANDARD.encode(protected))
}

pub fn unprotect_from_base64(input: &str) -> Result<Vec<u8>> {
    let raw = STANDARD.decode(input)?;
    unprotect(&raw)
}

#[cfg(windows)]
fn protect(input: &[u8]) -> Result<Vec<u8>> {
    use windows::Win32::Foundation::{LocalFree, HLOCAL};
    use windows::Win32::Security::Cryptography::{CryptProtectData, CRYPT_INTEGER_BLOB};

    let mut data = CRYPT_INTEGER_BLOB {
        cbData: input.len() as u32,
        pbData: input.as_ptr() as *mut u8,
    };
    let mut out = CRYPT_INTEGER_BLOB::default();
    unsafe {
        CryptProtectData(&mut data, windows::core::w!("BHZN ToDesk Agent"), None, None, None, 0, &mut out)?;
        let bytes = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        let _ = LocalFree(Some(HLOCAL(out.pbData as *mut _)));
        Ok(bytes)
    }
}

#[cfg(windows)]
fn unprotect(input: &[u8]) -> Result<Vec<u8>> {
    use windows::Win32::Foundation::{LocalFree, HLOCAL};
    use windows::Win32::Security::Cryptography::{CryptUnprotectData, CRYPT_INTEGER_BLOB};

    let mut data = CRYPT_INTEGER_BLOB {
        cbData: input.len() as u32,
        pbData: input.as_ptr() as *mut u8,
    };
    let mut out = CRYPT_INTEGER_BLOB::default();
    unsafe {
        CryptUnprotectData(&mut data, None, None, None, None, 0, &mut out)?;
        let bytes = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        let _ = LocalFree(Some(HLOCAL(out.pbData as *mut _)));
        Ok(bytes)
    }
}

#[cfg(not(windows))]
fn protect(input: &[u8]) -> Result<Vec<u8>> {
    Ok(input.to_vec())
}

#[cfg(not(windows))]
fn unprotect(input: &[u8]) -> Result<Vec<u8>> {
    Ok(input.to_vec())
}
