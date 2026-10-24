//! `cargo run --features bindgen --bin uniffi-bindgen -- generate --library …` — Kotlin-биндинги
//! из собранной библиотеки. Зовёт Gradle (`:tunnel`, задача `generateTunnelBindings`).

fn main() {
    uniffi::uniffi_bindgen_main()
}
